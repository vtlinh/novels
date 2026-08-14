package dev.vtlinh.noveldownloader

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/* Checking ONE novel against its site, and acting on the answer.

   Two screens ask for this now — the Library's sweep over everything, and the
   per-novel settings screen's "Check for new chapters" — and what they have to
   do is identical and not obvious: try the recorded URL before the guesses,
   make a guess prove it is the same novel, stop asking other hosts when the
   listing came back with a hole in it, and write the result without letting a
   locked database take the app down. Every one of those rules is a defect that
   was fixed once already. A second copy of them on the settings screen would
   be a second chance to get them wrong. The foreground auto-check
   (StatusAutoCheck) reuses the same sweep. */
object NovelCheck {

    /* Novels a check is running on RIGHT NOW, keyed like DownloadService's
       busy set. checkStatus renames files by listing position and deletes
       what the listing doesn't name; two of those interleaving on one folder
       renumber it against each other. The engine's own isBusy re-ask guards
       check-vs-download — this guards check-vs-check, which became reachable
       the moment the settings screen got its own Check button alongside the
       Library's sweep. */
    private val checking = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /* so a screen can tell "a check refused to stack" apart from "the site
       would not answer" — the two deserve different messages */
    fun isChecking(slug: String): Boolean = Ownership.normKey(slug) in checking

    /* Chapters of this novel on this device.

       The cached resolved listing when there is one — it is a real directory
       read and the only count that sees compressed chapters. The index and the
       one-time folder scan are the fallback for a novel that has never been
       listed. NOT the maximum of all three: that can only ever go up, so a
       chapter the dedupe removed stayed in the count for good. */
    fun localCount(store: DownloadStore, folder: String, rec: NovelRec): Int {
        val listCount = try { store.chapterListCount(folder, rec.slug) } catch (e: Exception) { 0 }
        if (listCount > 0) return listCount
        val dbCount = try { store.chapterCount(folder, rec.slug) } catch (e: Exception) { 0 }
        return maxOf(dbCount, rec.diskCount)
    }

    /* What a check found. `missing` is how many listed chapters are not here —
       what an auto-download would fetch — and `resumed` says the listing was
       read from where it ended rather than from page 1. */
    class Result(
        val url: String,
        val total: Int,
        val complete: Boolean,
        val missing: Int,
        val resumed: Boolean,
    )

    /* The hosts to try for a novel whose recorded URL doesn't answer. These
       sites move between domains, and a novel whose recorded host stops
       serving it was otherwise never checked again — on this sweep or any
       future one — with nothing said about why. */
    private fun candidates(rec: NovelRec): List<String> =
        (
            rec.url.ifEmpty { null }?.let { listOf(it) }.orEmpty() +
                listOf(
                    "https://truyenfull.today/${rec.slug}/",
                    "https://novelfull.com/${rec.slug}.html",
                    "https://truyenfull.live/${rec.slug}/",
                )
            ).distinct()

    /* Check one novel and record what came back. Null means no host answered
       for it (or the listing had a hole in it and the check refused to act).

       `display` is the title the caller is showing, used only to make a
       GUESSED url prove it is this novel: the slug is not proof — for a
       folder-scanned row it is derived from the folder NAME, and these sites
       tell same-titled books apart with a numeric suffix. */
    suspend fun one(
        engine: DownloadEngine,
        store: DownloadStore,
        folder: String,
        rec: NovelRec,
        display: String,
        local: Int,
    ): Result? {
        val key = Ownership.normKey(rec.slug)
        if (!checking.add(key)) return null   // a check is already on it — skip, don't stack
        try {
            return oneExclusive(engine, store, folder, rec, display, local)
        } finally {
            checking.remove(key)
        }
    }

    private suspend fun oneExclusive(
        engine: DownloadEngine,
        store: DownloadStore,
        folder: String,
        rec: NovelRec,
        display: String,
        local: Int,
    ): Result? {
        /* The listing as we last read it, by position. Built here rather than
           inside the engine because it is a database read and the engine's
           resumed check has to be given something it can compare against
           without knowing where it came from. */
        val recorded = try {
            Resume.recordedListing(
                store.getChapterOrder(folder, rec.slug),
                store.fileUrls(folder, rec.slug),
            )
        } catch (e: Exception) { emptyList() }
        val point = rec.resume
        if (point != null && rec.url.isNotEmpty() &&
            Resume.mayResume(point, recorded.size, rec.total, local, rec.complete)
        ) {
            val res = try {
                engine.checkStatusFrom(rec.url, folder, point, recorded)
            } catch (e: Exception) { null }
            /* Null is "that didn't work out" — the point no longer describes
               this site's listing, or the join disagreed. Fall through and
               read the whole thing, which is what the resumed read exists to
               avoid but is always the correct answer. */
            if (res != null) return save(store, folder, rec, display, rec.url, res, local, true)
        }
        for (u in candidates(rec)) {
            val res = try {
                engine.checkStatus(
                    u, folder,
                    expectTitle = if (u == rec.url) null else rec.title.ifEmpty { display },
                )
            } catch (e: DownloadEngine.PartialListing) {
                /* The check refused because the listing had a hole in it.
                   Asking the site's OTHER host next is the one thing that must
                   not happen: its chapter URLs are different, so every recorded
                   page reads as unlisted against it and the deletion this
                   refusal exists to prevent happens anyway. */
                return null
            } catch (e: Exception) { null } ?: continue
            return save(store, folder, rec, display, u, res, local, false)
        }
        return null
    }

    /* Write the check's findings. Guarded as a whole: these run off the main
       thread inside a sweep with no handler, and one bad write took the whole
       app down mid-check instead of costing one novel its update. */
    private fun save(
        store: DownloadStore,
        folder: String,
        rec: NovelRec,
        display: String,
        url: String,
        res: DownloadEngine.SiteStatus,
        local: Int,
        resumed: Boolean,
    ): Result {
        val complete = res.completed && local >= res.total
        try {
            /* record the URL that answered, so the next sweep doesn't start by
               asking the host that no longer has it */
            if (url != rec.url) store.recordNovelUrl(folder, rec.slug, url, display, 0L)
            store.setNovelInfo(
                folder, rec.slug,
                author = res.info.author ?: res.author,
                altNames = res.info.altNames,
                genres = res.info.genres,
                source = res.info.source,
                description = res.info.description,
                statusLabel = res.info.statusLabel,
            )
            /* index the site's chapter order for the reader — skipped when
               already indexed and unchanged */
            if (store.chapterOrderCount(folder, rec.slug) != res.orderedFilenames.size) {
                store.setChapterOrder(folder, rec.slug, res.orderedFilenames)
            }
            store.updateNovelCheck(folder, rec.slug, res.total, complete)
        } catch (e: Exception) {
            DownloadService.appendLog("Could not save the check for $display — ${e.message}")
        }
        return Result(url, res.total, complete, maxOf(0, res.total - local), resumed)
    }

    /* One novel the Library (or auto-check) wants visited. `display` is the
       title shown to the user; `local` is chapters already on this device. */
    class Target(
        val rec: NovelRec,
        val display: String,
        val local: Int,
    )

    /* What a sweep finished with — how many novels were asked, which URLs
       should be fetched (auto-download), and how many of those starts the
       service actually accepted. */
    class SweepResult(
        val asked: Int,
        val fetchUrls: List<String>,
        val started: Int,
    )

    /* Visit every target, up to three at a time, then queue auto-downloads
       for novels whose setting says to fetch what the check found. Same
       rules the Library's Check button used inline — extracted so the
       foreground auto-check cannot drift. */
    suspend fun sweep(
        context: Context,
        engine: DownloadEngine,
        store: DownloadStore,
        folder: String,
        targets: List<Target>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SweepResult {
        if (targets.isEmpty()) return SweepResult(0, emptyList(), 0)
        val done = AtomicInteger(0)
        /* Collected rather than started on the spot: this runs three wide
           and a download started mid-sweep gets its files renamed and
           deduped out from under the write by the very sweep that started
           it. They go to the queue once every novel has been asked. */
        val fetch = java.util.Collections.synchronizedList(ArrayList<String>())
        withContext(Dispatchers.IO) {
            coroutineScope {
                val sem = Semaphore(3)
                for (row in targets) {
                    launch {
                        sem.withPermit {
                            /* The busy test at snapshot time is not enough:
                               this sweep runs three wide for minutes, and
                               a download started after it began gets its
                               files renamed and deduped out from under the
                               write. Ask again when this novel's turn
                               actually comes. */
                            if (DownloadService.isBusy(Ownership.normKey(row.rec.slug))) {
                                done.incrementAndGet()
                                return@withPermit
                            }
                            val res = one(
                                engine, store, folder, row.rec, row.display, row.local,
                            )
                            /* the setting as it is NOW, not as it was when
                               the sweep snapshotted its targets — the busy
                               test got a re-ask for the same reason, and a
                               sweep runs for minutes: un-ticking
                               auto-download mid-sweep must stick, because
                               with translation pinned on it is money */
                            if (res != null && res.missing > 0 &&
                                try {
                                    store.novel(folder, row.rec.slug)?.autoDownload == true
                                } catch (e: Exception) { false }
                            ) {
                                fetch.add(res.url)
                            }
                            val n = done.incrementAndGet()
                            onProgress(n, targets.size)
                        }
                    }
                }
            }
        }
        val urls = fetch.toList()
        val started = urls.count { startDownload(context, it) }
        return SweepResult(targets.size, urls, started)
    }

    /* Queue this novel for download. The service lines it up behind whatever
       is already running, so calling this for several novels in a sweep just
       fills the queue.

       False when the START ITSELF was refused, which the caller must say
       rather than reporting success: from API 31 a foreground service cannot
       be started while the app is in the background, and a check long enough
       to background the app during is exactly when this fires — the screen
       then said "downloading" over a download that never began. */
    fun startDownload(context: Context, url: String): Boolean {
        val p = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        val tree = p.getString("tree", null) ?: return false
        return try {
            context.startForegroundService(
                Intent(context, DownloadService::class.java)
                    .putExtra("url", url)
                    .putExtra("tree", tree)
                    /* the app-wide switch; the service asks the novel's own
                       setting whether to follow it — see DownloadStore.translateFor */
                    .putExtra("translate", p.getBoolean("translate", false))
                    .putExtra("apiKey", p.getString("apiKey", "") ?: ""),
            )
            true
        } catch (e: Exception) {
            DownloadService.appendLog("Could not start the download — ${e.message}")
            false
        }
    }
}
