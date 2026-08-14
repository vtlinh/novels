package dev.vtlinh.noveldownloader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/* Automatic library status check, same lifecycle as the self-updater:
   ProcessLifecycleOwner.onStart (any screen) decides whether enough days
   have passed, then runs the same NovelCheck.sweep the Library's Check
   button uses. The interval lives in Settings — 0 means never. */
object StatusAutoCheck {

    const val DAYS_KEY = "statusCheckDays"
    private const val LAST_KEY = "lastStatusAutoCheck"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private val running = AtomicBoolean(false)

    /* 0 = never; 1..7 = minimum days since the last auto sweep. */
    fun days(context: Context): Int =
        prefs(context).getInt(DAYS_KEY, 0).coerceIn(0, 7)

    fun setDays(context: Context, days: Int) {
        prefs(context).edit().putInt(DAYS_KEY, days.coerceIn(0, 7)).apply()
    }

    /* Remember that a status sweep just ran (manual or automatic), so the
       next foreground auto-check waits the configured minimum days. */
    fun markChecked(context: Context) {
        prefs(context).edit().putLong(LAST_KEY, System.currentTimeMillis()).apply()
    }

    /* Runs whenever the app is brought to the foreground. Skips when the
       setting is Never, when the last auto sweep was too recent, when no
       download folder is set, or when a sweep is already in flight. */
    fun autoCheck(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
        val days = days(context)
        if (days <= 0) return
        val prefs = prefs(context)
        val folder = prefs.getString("tree", null) ?: return
        val now = System.currentTimeMillis()
        val last = prefs.getLong(LAST_KEY, 0L)
        if (last > 0L && now - last < days * DAY_MS) return
        if (!running.compareAndSet(false, true)) return
        /* Stamp at the start so a long sweep (or a crash mid-way) doesn't
           restart on every foreground until it finishes — same idea as
           Updater's in-memory lastAutoCheck, persisted across process death. */
        markChecked(context)
        val app = context.applicationContext
        scope.launch {
            try {
                runSweep(app, folder)
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun runSweep(context: Context, folder: String) {
        val store = DownloadStore(context)
        val engine = DownloadEngine(
            context,
            { line -> DownloadService.appendLog(line) },
            {},
            { _, _ -> },
        )
        val targets = withContext(Dispatchers.IO) {
            try {
                targets(context, store, folder)
            } catch (e: Exception) {
                DownloadService.appendLog(
                    "Automatic status check couldn't read the library — ${e.message}",
                )
                emptyList()
            }
        }
        if (targets.isEmpty()) return
        DownloadService.appendLog(
            "Automatic status check — ${targets.size} novel(s).",
        )
        val result = NovelCheck.sweep(context, engine, store, folder, targets)
        DownloadService.appendLog(
            "Automatic status check finished (${result.asked} novel(s))." + when {
                result.fetchUrls.isEmpty() -> ""
                result.started == result.fetchUrls.size ->
                    " Downloading ${result.started} with new chapters."
                else ->
                    " ${result.started} of ${result.fetchUrls.size} downloads started" +
                        " — the rest were refused; open the app and check again."
            },
        )
    }

    /* Same filters the Library's Check button applies: skip busy downloads
       and novels that are complete, fully on disk, and already indexed.
       Garbage-marked novels stay out. Built from the registry only — the
       Library's one-time folder scan still belongs to that screen. */
    private fun targets(
        context: Context,
        store: DownloadStore,
        folder: String,
    ): List<NovelCheck.Target> {
        val garbage = prefs(context)
            .getStringSet(NovelListActivity.GARBAGE_KEY, emptySet())
            ?: emptySet()
        val out = ArrayList<NovelCheck.Target>()
        for (rec in store.novels(folder)) {
            val key = Ownership.normKey(rec.slug)
            if (key in garbage) continue
            if (DownloadService.isBusy(key)) continue
            val local = NovelCheck.localCount(store, folder, rec)
            val done = rec.complete && rec.total > 0 && local == rec.total
            if (done && store.chapterOrderCount(folder, rec.slug) > 0) continue
            val display = Extractor.stripAuthor(
                store.getTitle(folder, rec.slug) ?: rec.title.ifEmpty { rec.slug },
                rec.author,
            )
            out.add(NovelCheck.Target(rec, display, local))
        }
        return out
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("app", Context.MODE_PRIVATE)
}
