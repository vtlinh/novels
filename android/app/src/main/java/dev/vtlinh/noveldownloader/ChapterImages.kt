package dev.vtlinh.noveldownloader

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/* One chapter image: post {hash}.txt, record that Slack thread in
   the database, poll for {hash}.png, and save it under scenes/. The
   chapter→image row is how the reader knows to draw a picture. Request
   threads stay until that save — a day-long give-up only stops
   polling after a Slack look completed and found no png. The wait
   stops sooner if Slack confirms the chapter post itself is gone.
   A network or service error is not a look.

   Auto-generate: Settings can turn this on for the whole library,
   with a minimum star rating and an unread-only filter. A
   novel's own ⚙ switch, when on, always includes that book and
   uses its Every / Starting from. The service posts chapter N ≥
   from where (N − from) is a multiple of every, at most one
   chapter every 30 minutes, including while the app is in the
   background. A post does not wait for that png before the next
   one is due. Novels are always tried in last-read order — the
   book opened most recently in the reader goes first. The next
   due chapter that is not on disk yet is skipped until it arrives
   — later due chapters are not pulled forward. A chapter already
   posted is not posted again. */
object ChapterImages {

    private const val WAIT_KEY = "slackImageWait"
    const val GIVE_UP_MS = 24L * 60L * 60L * 1000L
    const val AUTO_EVERY_DEFAULT = 20
    const val AUTO_FROM_DEFAULT = 1
    const val AUTO_MIN_STARS_DEFAULT = 7
    const val AUTO_GAP_MS = 30L * 60L * 1000L
    const val GLOBAL_ON_KEY = "autoImageGlobal"
    const val GLOBAL_EVERY_KEY = "autoImageGlobalEvery"
    const val GLOBAL_FROM_KEY = "autoImageGlobalFrom"
    const val GLOBAL_MIN_STARS_KEY = "autoImageMinStars"
    /* Stored under the old unfinished-only name so existing installs
       keep the filter they already picked. */
    const val GLOBAL_UNREAD_KEY = "autoImageUnfinishedOnly"
    private const val AUTO_LAST_KEY = "autoImageLastAt"
    private val inflight = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val autoLock = Any()
    /* Polls and auto posts must outlive the chapter list: opening the
       reader finishes that screen and would cancel a lifecycle-scoped
       wait. The service owns the long loop; this scope is only the
       short adopt-on-open pass. */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /* One novel the auto pass can post for. lastRead is 0 if the
       reader has never opened it — those sit after every book that
       has been read. */
    data class AutoNovel(
        val slug: String,
        val lastRead: Long,
        val from: Int,
        val every: Int,
        val chapters: List<String> = emptyList(),
    )

    data class AutoPick(val slug: String, val chapter: String)

    /* Newest lastRead first. Never-read (0) falls to the end.
       Equal times keep a stable slug order. */
    fun <T> byLastRead(
        items: List<T>,
        lastReadOf: (T) -> Long,
        tieOf: (T) -> String = { "" },
    ): List<T> =
        items.sortedWith(compareByDescending(lastReadOf).thenBy(tieOf))

    /* First due chapter that is actually on disk, walking from, from+every,
       from+2*every. A hole — Starting from 21 when only 1–20 are
       downloaded, or chapter 1 missing while 21 is present — waits
       rather than jumping ahead. skip is "already has a picture /
       already posted / already tried". */
    fun nextDueName(
        chapters: List<String>,
        from: Int,
        every: Int,
        skip: (chapter: String) -> Boolean,
    ): String? {
        if (every < 1 || from < 1) return null
        val byNum = LinkedHashMap<Int, String>()
        var maxN = 0
        for (chapter in chapters) {
            val n = Scenes.chapterNumber(chapter) ?: continue
            if (n !in byNum) byNum[n] = chapter
            if (n > maxN) maxN = n
        }
        if (maxN < from) return null
        var n = from
        while (n <= maxN) {
            val chapter = byNum[n] ?: return null
            if (!skip(chapter)) return chapter
            n += every
        }
        return null
    }

    /* First due chapter across novels already in last-read order.
       A novel whose next due chapter is not downloaded yet is
       skipped until that file arrives. */
    fun nextAuto(
        novels: List<AutoNovel>,
        skip: (slug: String, chapter: String) -> Boolean,
    ): AutoPick? {
        for (novel in byLastRead(novels, { it.lastRead }, { it.slug })) {
            val chapter = nextDueName(novel.chapters, novel.from, novel.every) { ch ->
                skip(novel.slug, ch)
            } ?: continue
            return AutoPick(novel.slug, chapter)
        }
        return null
    }

    @Volatile
    private var statusSink: ((String) -> Unit)? = null

    private fun log(msg: String) {
        DownloadService.appendLog("image: $msg")
    }

    /* Logs screen + the picture notification. A download in flight
       owns statusFlow, so do not overwrite that. */
    private fun report(msg: String) {
        log(msg)
        if (!DownloadService.runningFlow.value) {
            DownloadService.statusFlow.value = msg
        }
        statusSink?.invoke(msg)
    }

    /* Novel folder + chapter number — what a person reading Logs
       can match to the book they opened. */
    fun describe(novel: String, chapter: String): String {
        val book = novel.trim().ifEmpty { "This novel" }
        val n = Scenes.chapterNumber(chapter)
        if (n != null) return "$book, chapter $n"
        val stem = Scenes.chapterStem(chapter).trim()
        return if (stem.isNotEmpty()) "$book, $stem" else book
    }

    fun sizeLabel(bytes: Int): String {
        if (bytes < 1024) return "$bytes B"
        val kb = (bytes + 512) / 1024
        if (kb < 1024) return "$kb KB"
        val tenths = (bytes * 10L + 512L * 1024L) / (1024L * 1024L)
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0L) "$whole MB" else "$whole.$frac MB"
    }

    fun waitLabel(ms: Long): String {
        if (ms >= 60L * 60_000L) {
            val hours = ((ms + 30L * 60_000L) / (60L * 60_000L)).coerceAtLeast(1L)
            return if (hours == 1L) "about 1 hour" else "about $hours hours"
        }
        val min = ((ms + 30_000L) / 60_000L).coerceAtLeast(1L)
        return if (min == 1L) "about 1 minute" else "about $min minutes"
    }

    fun catalogLookLine(novel: String, count: Int): String {
        val book = novel.trim().ifEmpty { "this novel" }
        return "Checking Slack for pictures already made for $book — ${countLabel(count)}"
    }

    fun catalogResultLine(novel: String, saved: Int, missing: Int): String {
        val book = novel.trim().ifEmpty { "This novel" }
        return when {
            saved <= 0 && missing <= 0 -> "$book: nothing to check on Slack"
            saved <= 0 -> "$book: Slack has no picture yet for ${countLabel(missing)}"
            missing <= 0 -> "$book: Slack already had pictures for ${countLabel(saved)} — saved"
            else -> "$book: saved ${countLabel(saved)} from Slack; ${countLabel(missing)} still have none"
        }
    }

    private fun countLabel(n: Int) = if (n == 1) "1 chapter" else "$n chapters"

    /* missing_scope / not_in_channel: another 8 minutes will not
       produce a png. Surface Slack's message and stop. */
    private fun deniedLook(
        novel: String,
        chapter: String,
        found: SlackPoster.Existing,
    ): Result<Boolean>? {
        if (!SlackPoster.lookDenied(found.png, found.readError)) return null
        val code = found.readError ?: return null
        report("${describe(novel, chapter)} — ${SlackPoster.describe(code)}")
        return Result.failure(SlackPoster.ApiException(code))
    }

    fun autoTriedKey(slug: String, chapter: String) = "imgAuto:$slug:$chapter"

    fun expired(startedAt: Long, now: Long = System.currentTimeMillis()) =
        now - startedAt >= GIVE_UP_MS

    /* A stale wait is dropped after a day, once a Slack look
       completed and found no png. The wait also stops as soon as
       Slack confirms the chapter post itself is gone. A network /
       503 / timeout is not a look — keep polling. */
    fun mayDrop(
        startedAt: Long,
        looked: Boolean,
        foundNothing: Boolean,
        now: Long = System.currentTimeMillis(),
        topLevelMissing: Boolean = false,
    ): Boolean = topLevelMissing ||
        (expired(startedAt, now) && looked && foundNothing)

    /* Chapter N is due when it is at or after `from` and lands on the
       every-th step from there. Defaults (from 1, every 20) → 1, 21, 41. */
    fun due(n: Int, from: Int, every: Int): Boolean {
        if (every < 1 || from < 1 || n < from) return false
        return (n - from) % every == 0
    }

    fun autoEnabledKey(slug: String) = "autoImage:$slug"
    fun autoEveryKey(slug: String) = "autoImageEvery:$slug"
    fun autoFromKey(slug: String) = "autoImageFrom:$slug"
    fun autoLastKey() = AUTO_LAST_KEY

    /* First auto post is immediate. After that, 30 minutes from the
       last auto start. lastAt 0 means never. */
    fun autoReady(
        lastAt: Long,
        now: Long = System.currentTimeMillis(),
        gapMs: Long = AUTO_GAP_MS,
    ): Boolean = lastAt <= 0L || now - lastAt >= gapMs

    fun autoWaitMs(
        lastAt: Long,
        now: Long = System.currentTimeMillis(),
        gapMs: Long = AUTO_GAP_MS,
    ): Long = if (lastAt <= 0L) 0L else (lastAt + gapMs - now).coerceAtLeast(0L)

    /* 0 means the service can stop. While pictures are still due or
       Slack still owes a png, keep looking — do not sit in
       waitForImage, and do not exit just because this pass posted
       nothing. */
    fun backgroundWaitMs(
        hasWork: Boolean,
        posted: Boolean,
        lastAt: Long,
        now: Long = System.currentTimeMillis(),
        gapMs: Long = AUTO_GAP_MS,
    ): Long {
        if (!hasWork) return 0L
        if (posted) return gapMs
        val left = autoWaitMs(lastAt, now, gapMs)
        return if (left > 0L) left else gapMs
    }

    fun autoEnabled(ctx: Context, slug: String): Boolean =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getBoolean(autoEnabledKey(slug), false)

    fun autoEvery(ctx: Context, slug: String): Int =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getInt(autoEveryKey(slug), AUTO_EVERY_DEFAULT).coerceAtLeast(1)

    fun autoFrom(ctx: Context, slug: String): Int =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getInt(autoFromKey(slug), AUTO_FROM_DEFAULT).coerceAtLeast(1)

    fun setAuto(ctx: Context, slug: String, enabled: Boolean, every: Int, from: Int) {
        if (slug.isEmpty()) return
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
            .putBoolean(autoEnabledKey(slug), enabled)
            .putInt(autoEveryKey(slug), every.coerceAtLeast(1))
            .putInt(autoFromKey(slug), from.coerceAtLeast(1))
            .commit()
        if (enabled) ImageService.start(ctx) else ImageService.startIfNeeded(ctx)
    }

    fun readKey(slug: String) = "novelRead:$slug"

    fun novelRead(prefs: SharedPreferences, slug: String): Boolean =
        slug.isNotEmpty() && prefs.getBoolean(readKey(slug), false)

    data class AutoCadence(val every: Int, val from: Int)

    /* A novel switch that is on always wins. Otherwise the global
       switch applies, then the star floor and the unread filter. */
    fun autoApplies(
        novelOn: Boolean,
        globalOn: Boolean,
        stars: Int,
        minStars: Int,
        read: Boolean,
        unreadOnly: Boolean,
    ): Boolean {
        if (novelOn) return true
        if (!globalOn) return false
        if (stars < minStars.coerceAtLeast(0)) return false
        if (unreadOnly && read) return false
        return true
    }

    fun autoCadence(
        novelOn: Boolean,
        novelEvery: Int,
        novelFrom: Int,
        globalEvery: Int,
        globalFrom: Int,
    ): AutoCadence =
        if (novelOn) AutoCadence(novelEvery.coerceAtLeast(1), novelFrom.coerceAtLeast(1))
        else AutoCadence(globalEvery.coerceAtLeast(1), globalFrom.coerceAtLeast(1))

    fun globalEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getBoolean(GLOBAL_ON_KEY, false)

    fun globalEvery(ctx: Context): Int =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getInt(GLOBAL_EVERY_KEY, AUTO_EVERY_DEFAULT).coerceAtLeast(1)

    fun globalFrom(ctx: Context): Int =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getInt(GLOBAL_FROM_KEY, AUTO_FROM_DEFAULT).coerceAtLeast(1)

    fun minStars(ctx: Context): Int =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getInt(GLOBAL_MIN_STARS_KEY, AUTO_MIN_STARS_DEFAULT)
            .coerceIn(0, NovelRating.MAX)

    fun unreadOnly(ctx: Context): Boolean =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getBoolean(GLOBAL_UNREAD_KEY, true)

    fun setGlobal(
        ctx: Context,
        enabled: Boolean,
        every: Int,
        from: Int,
        minStars: Int,
        unreadOnly: Boolean,
    ) {
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
            .putBoolean(GLOBAL_ON_KEY, enabled)
            .putInt(GLOBAL_EVERY_KEY, every.coerceAtLeast(1))
            .putInt(GLOBAL_FROM_KEY, from.coerceAtLeast(1))
            .putInt(GLOBAL_MIN_STARS_KEY, minStars.coerceIn(0, NovelRating.MAX))
            .putBoolean(GLOBAL_UNREAD_KEY, unreadOnly)
            .commit()
        if (enabled) ImageService.start(ctx) else ImageService.startIfNeeded(ctx)
    }

    fun autoOnFor(ctx: Context, slug: String): Boolean {
        if (slug.isEmpty()) return false
        val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        return autoApplies(
            novelOn = autoEnabled(ctx, slug),
            globalOn = globalEnabled(ctx),
            stars = NovelRating.get(prefs, slug),
            minStars = minStars(ctx),
            read = novelRead(prefs, slug),
            unreadOnly = unreadOnly(ctx),
        )
    }

    fun cadenceFor(ctx: Context, slug: String): AutoCadence =
        autoCadence(
            novelOn = autoEnabled(ctx, slug),
            novelEvery = autoEvery(ctx, slug),
            novelFrom = autoFrom(ctx, slug),
            globalEvery = globalEvery(ctx),
            globalFrom = globalFrom(ctx),
        )

    private fun slackReady(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        val token = (prefs.getString("slackBotToken", "") ?: "").trim()
        val channel = (prefs.getString("slackChannelId", "") ?: "").trim()
        return token.isNotEmpty() && channel.isNotEmpty()
    }

    private fun slackPoster(ctx: Context): SlackPoster? {
        val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        val token = (prefs.getString("slackBotToken", "") ?: "").trim()
        val channel = (prefs.getString("slackChannelId", "") ?: "").trim()
        if (token.isEmpty() || channel.isEmpty()) return null
        return SlackPoster(token, channel)
    }

    /* One Slack history + files.list, then match every missing chapter
       against that catalog. Saves any png already there so later
       request() calls do not crawl Slack again. */
    private fun adoptFromSlack(
        ctx: Context,
        slack: SlackPoster,
        folder: String,
        dirName: String,
        slug: String,
        chapters: List<String>,
    ) {
        val store = DownloadStore(ctx)
        val looks = mutableListOf<Triple<String, String, List<String>>>()
        for (chapter in chapters.distinct()) {
            if (chapter.isEmpty()) continue
            val onDisk = adoptDiskImage(ctx, folder, dirName, slug, chapter) != null
            if (onDisk && !needsAltRefresh(true, linkedAlt(ctx, folder, slug, chapter))) continue
            val reqs = try { store.imageReqs(folder, slug, chapter) } catch (e: Exception) {
                emptyList()
            }
            val hash = reqs.firstOrNull { it.hash.isNotEmpty() }?.hash
                ?: chapterText(ctx, folder, dirName, slug, chapter)?.let { Scenes.contentHash(it) }
                ?: continue
            looks.add(Triple(chapter, hash, reqs.map { it.threadTs }.filter { it.isNotEmpty() }))
        }
        if (looks.isEmpty()) return
        report(catalogLookLine(dirName, looks.size))
        val found = try {
            slack.findExistingMany(looks.map { it.second to it.third })
        } catch (e: Exception) {
            report("$dirName: could not read Slack (${e.message})")
            return
        }
        var saved = 0
        var missing = 0
        for ((chapter, hash, _) in looks) {
            val ex = found[hash] ?: continue
            if (SlackPoster.lookDenied(ex.png, ex.readError)) {
                report("$dirName — ${SlackPoster.describe(ex.readError ?: "")}")
                return
            }
            if (ex.png != null) {
                try {
                    keepImage(ctx, folder, dirName, slug, chapter, ex.png, ex.alt)
                    saved++
                } catch (e: Exception) {
                    report("${describe(dirName, chapter)} — could not save the picture Slack already had (${e.message})")
                    missing++
                }
            } else {
                missing++
            }
            for (ts in ex.threads) {
                if (ts.isNotEmpty()) markRequested(ctx, folder, slug, chapter, hash, ts)
            }
        }
        report(catalogResultLine(dirName, saved, missing))
    }

    private fun autoTried(ctx: Context, slug: String, chapter: String): Boolean =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getBoolean(autoTriedKey(slug, chapter), false)

    private fun markAutoTried(ctx: Context, slug: String, chapter: String) {
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
            .putBoolean(autoTriedKey(slug, chapter), true)
            .apply()
    }

    private fun autoLastAt(ctx: Context): Long =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getLong(AUTO_LAST_KEY, 0L)

    private fun markAutoLast(ctx: Context, at: Long = System.currentTimeMillis()) {
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
            .putLong(AUTO_LAST_KEY, at).apply()
    }

    /* Opening a novel (list or reader) asks Slack for pictures that
       are already there, including a description on a png we already
       saved with a blank caption. Auto-generate itself runs in
       ImageService, across every novel that has it on, last-read
       first — so leaving the app does not stop it, and opening a
       less-recent book does not jump the queue. */
    fun autoSweep(
        ctx: Context,
        folder: String,
        dirName: String,
        slug: String,
        chapters: List<String>,
        scope: CoroutineScope,
    ) {
        if (slug.isEmpty() || folder.isEmpty() || dirName.isEmpty()) return
        if (!slackReady(ctx)) return
        val autoOn = autoOnFor(ctx, slug)
        val cadence = cadenceFor(ctx, slug)
        val every = cadence.every
        val from = cadence.from
        val app = ctx.applicationContext
        work.launch {
            slackPoster(app)?.let { slack ->
                val dueCh = if (autoOn) {
                    chapters.filter { ch ->
                        val n = Scenes.chapterNumber(ch) ?: return@filter false
                        due(n, from, every)
                    }
                } else {
                    emptyList()
                }
                val blankAlt = chapters.filter { ch ->
                    adoptDiskImage(app, folder, dirName, slug, ch) != null &&
                        needsAltRefresh(true, linkedAlt(app, folder, slug, ch))
                }
                adoptFromSlack(app, slack, folder, dirName, slug, (dueCh + blankAlt).distinct())
            }
            ImageService.startIfNeeded(app)
        }
    }

    /* Slack is set and there is either a waiting download or at least
       one novel auto-generate applies to (its own switch, or the
       global switch plus filters). The service uses this to decide
       whether to start; the loop itself stops when a pass finds nothing
       left to post or fetch. */
    fun hasBackgroundWork(ctx: Context): Boolean {
        if (!slackReady(ctx)) return false
        val store = try { DownloadStore(ctx) } catch (e: Exception) { return false }
        val waiting = try { store.waitingImageReqs() } catch (e: Exception) { emptyList() }
        if (waiting.isNotEmpty()) return true
        val folder = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getString("tree", "") ?: ""
        if (folder.isEmpty()) return false
        val novels = try { store.novels(folder) } catch (e: Exception) { emptyList() }
        return novels.any { autoOnFor(ctx, it.slug) }
    }

    /* Keeps posting and fetching while the service holds the process.
       One auto post every 30 minutes, always the next due chapter of
       the most recently read novel that still has one. The post does
       not wait for that png — Slack can take much longer than the
       gap, and sitting on it delayed every later request. */
    suspend fun runBackground(ctx: Context, status: (String) -> Unit = {}) {
        val app = ctx.applicationContext
        statusSink = status
        try {
            while (true) {
                if (!slackReady(app)) return
                report("Saving chapter pictures that Slack already finished")
                resumeWaitingNow(app)
                val posted = runAutoPass(app)
                val wait = backgroundWaitMs(
                    hasBackgroundWork(app), posted, autoLastAt(app),
                )
                if (wait <= 0L) return
                report("Waiting ${waitLabel(wait)} before making the next picture")
                delay(wait)
            }
        } finally {
            statusSink = null
        }
    }

    private fun runAutoPass(app: Context): Boolean {
        if (!autoReady(autoLastAt(app))) return false
        val slack = slackPoster(app)
        for (novel in loadAutoNovels(app)) {
            if (!slackReady(app)) continue
            val chapters = loadChapters(app, novel.folder, novel.dirName, novel.slug)
            if (chapters.isEmpty()) continue
            slack?.let {
                val dueCh = chapters.filter { ch ->
                    val n = Scenes.chapterNumber(ch) ?: return@filter false
                    due(n, novel.from, novel.every)
                }
                val blankAlt = chapters.filter { ch ->
                    adoptDiskImage(app, novel.folder, novel.dirName, novel.slug, ch) != null &&
                        needsAltRefresh(true, linkedAlt(app, novel.folder, novel.slug, ch))
                }
                adoptFromSlack(
                    app, it, novel.folder, novel.dirName, novel.slug,
                    (dueCh + blankAlt).distinct(),
                )
            }
            if (autoTakeOne(
                    app, novel.folder, novel.dirName, novel.slug,
                    chapters, novel.every, novel.from,
                )
            ) {
                return true
            }
        }
        return false
    }

    private data class AutoTarget(
        val folder: String,
        val dirName: String,
        val slug: String,
        val lastRead: Long,
        val from: Int,
        val every: Int,
    )

    private fun loadAutoNovels(app: Context): List<AutoTarget> {
        val folder = app.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getString("tree", "") ?: ""
        if (folder.isEmpty()) return emptyList()
        val store = try { DownloadStore(app) } catch (e: Exception) { return emptyList() }
        val recs = try { store.novels(folder) } catch (e: Exception) { return emptyList() }
        val prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)
        val globalOn = globalEnabled(app)
        val gEvery = globalEvery(app)
        val gFrom = globalFrom(app)
        val floor = minStars(app)
        val onlyUnread = unreadOnly(app)
        val out = ArrayList<AutoTarget>()
        for (rec in recs) {
            val novelOn = autoEnabled(app, rec.slug)
            if (!autoApplies(
                    novelOn, globalOn,
                    NovelRating.get(prefs, rec.slug), floor,
                    novelRead(prefs, rec.slug), onlyUnread,
                )
            ) continue
            val dir = try {
                store.dirNameOrGuess(folder, rec.slug, rec.title)
            } catch (e: Exception) { "" }
            if (dir.isEmpty()) continue
            val cadence = autoCadence(
                novelOn, autoEvery(app, rec.slug), autoFrom(app, rec.slug),
                gEvery, gFrom,
            )
            out.add(
                AutoTarget(folder, dir, rec.slug, rec.lastRead, cadence.from, cadence.every),
            )
        }
        return byLastRead(out, { it.lastRead }, { it.slug })
    }

    private fun loadChapters(
        app: Context,
        folder: String,
        dirName: String,
        slug: String,
    ): List<String> {
        val order = try { DownloadStore(app).getChapterOrder(folder, slug) } catch (e: Exception) {
            emptyMap()
        }
        val ch = try {
            ChapterListActivity.chapterNames(app, Uri.parse(folder), dirName, order, slug)
        } catch (e: Exception) { null } ?: return emptyList()
        return ch.ordered
    }

    /* True when this sweep started a request. False when the gap is
       still running or nothing is due — do not mark a skipped chapter
       as tried. */
    private fun autoTakeOne(
        app: Context,
        folder: String,
        dirName: String,
        slug: String,
        chapters: List<String>,
        every: Int,
        from: Int,
    ): Boolean {
        val chapter = nextDueName(chapters, from, every) { ch ->
            if (hasLocalImage(app, folder, dirName, ch, slug)) return@nextDueName true
            if (alreadyRequested(app, folder, dirName, slug, ch)) {
                markAutoTried(app, slug, ch)
                return@nextDueName true
            }
            autoTried(app, slug, ch)
        } ?: return false
        /* Listing can name a chapter whose file is not here yet.
           Leave it untried so the next pass retries when it arrives. */
        val text = chapterText(app, folder, dirName, slug, chapter)
        if (text.isNullOrEmpty()) {
            report("${describe(dirName, chapter)} — chapter file is not downloaded yet")
            return false
        }
        synchronized(autoLock) {
            val last = autoLastAt(app)
            if (!autoReady(last)) {
                report("Waiting ${waitLabel(autoWaitMs(last))} before making the next picture")
                return false
            }
            markAutoLast(app)
        }
        markAutoTried(app, slug, chapter)
        report("${describe(dirName, chapter)} — asking Slack to make a picture")
        try {
            request(app, folder, dirName, slug, chapter, wait = false)
        } catch (e: Exception) {
            report("${describe(dirName, chapter)} — could not ask Slack (${e.message})")
        }
        return true
    }

    fun alreadyRequested(ctx: Context, dirName: String, slug: String, chapter: String): Boolean {
        val folder = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getString("tree", "") ?: ""
        return alreadyRequested(ctx, folder, dirName, slug, chapter)
    }

    enum class ImageAction { HIDE, GENERATE, POLL }

    /* Hide when the png is already saved. Poll when Slack already
       has this chapter's post — do not offer Generate again. */
    fun imageAction(hasImage: Boolean, hasReq: Boolean): ImageAction = when {
        hasImage -> ImageAction.HIDE
        hasReq -> ImageAction.POLL
        else -> ImageAction.GENERATE
    }

    fun imageAction(
        ctx: Context,
        folder: String,
        dirName: String,
        slug: String,
        chapter: String,
    ): ImageAction {
        if (folder.isEmpty() || slug.isEmpty() || chapter.isEmpty()) {
            return ImageAction.GENERATE
        }
        importLegacyWaits(ctx)
        return imageAction(
            hasImage = adoptDiskImage(ctx, folder, dirName, slug, chapter) != null,
            hasReq = DownloadStore(ctx).imageReqs(folder, slug, chapter).isNotEmpty(),
        )
    }

    /* True when Generate must not post again — the picture is saved
       or a Slack request row already exists. */
    fun alreadyRequested(
        ctx: Context,
        folder: String,
        dirName: String,
        slug: String,
        chapter: String,
    ): Boolean {
        if (folder.isEmpty() || slug.isEmpty() || chapter.isEmpty()) return false
        return imageAction(ctx, folder, dirName, slug, chapter) != ImageAction.GENERATE
    }

    fun lockGenerate(hasImage: Boolean, hasReq: Boolean): Boolean =
        imageAction(hasImage, hasReq) != ImageAction.GENERATE

    /* A chapter that already has a Slack {hash}.txt must not get another.
       "No png downloaded" is not "nothing posted" — Chapter 374 posted
       the same hash twice after files.list missed the first upload. */
    fun shouldPost(postIfMissing: Boolean, alreadyPosted: Boolean): Boolean =
        postIfMissing && !alreadyPosted

    fun markRequested(
        ctx: Context,
        folder: String,
        slug: String,
        chapter: String,
        hash: String,
        threadTs: String?,
        startedAt: Long = System.currentTimeMillis(),
    ) {
        DownloadStore(ctx).rememberImageReq(
            folder, slug, chapter, hash, threadTs.orEmpty(), startedAt,
        )
    }

    fun request(
        ctx: Context,
        folder: String,
        dirName: String,
        slug: String,
        chapter: String,
        postIfMissing: Boolean = true,
        wait: Boolean = true,
    ): Result<Boolean> {
        val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        val token = (prefs.getString("slackBotToken", "") ?: "").trim()
        val channel = (prefs.getString("slackChannelId", "") ?: "").trim()
        importLegacyWaits(ctx)
        val store = DownloadStore(ctx)
        val where = describe(dirName, chapter)
        return try {
            val onDisk = adoptDiskImage(ctx, folder, dirName, slug, chapter) != null
            if (onDisk && !needsAltRefresh(true, linkedAlt(ctx, folder, slug, chapter))) {
                report("$where — already on this phone")
                return Result.success(true)
            }
            if (token.isEmpty() || channel.isEmpty()) {
                report("$where — Slack is not set in Settings")
                return Result.failure(IOException("Set Slack in Settings."))
            }
            val slack = SlackPoster(token, channel)
            val reqs = store.imageReqs(folder, slug, chapter)
            val threads = reqs.map { it.threadTs }.filter { it.isNotEmpty() }
            val text = chapterText(ctx, folder, dirName, slug, chapter)
            if (text == null) {
                report("$where — could not read this chapter")
                return Result.failure(IOException("Could not read this chapter."))
            }
            if (text.isEmpty()) {
                report("$where — this chapter is empty")
                return Result.failure(IOException("This chapter is empty."))
            }
            var hash = reqs.firstOrNull { it.hash.isNotEmpty() }?.hash
                ?: Scenes.contentHash(text)
            report("$where — checking Slack for a picture already made")
            val found = slack.findExisting(hash, threads)
            deniedLook(dirName, chapter, found)?.let { return it }
            if (found.png != null) {
                keepImage(ctx, folder, dirName, slug, chapter, found.png, found.alt)
                return Result.success(true)
            }
            if (onDisk) {
                report("$where — already on this phone")
                return Result.success(true)
            }
            for (ts in found.threads) {
                if (ts.isNotEmpty()) markRequested(ctx, folder, slug, chapter, hash, ts)
            }
            val posted = reqs.isNotEmpty() || found.threads.isNotEmpty()
            if (posted && SlackPoster.lookTopLevelMissing(found)) {
                return lastLook(ctx, slack, folder, dirName, slug, chapter, hash, threads + found.threads)
            }
            val live = store.imageReqs(folder, slug, chapter).filter { !expired(it.startedAt) }
            if (live.isEmpty()) {
                if (!shouldPost(postIfMissing, posted)) {
                    report("$where — Slack already has this chapter posted — checking one last time")
                    return lastLook(ctx, slack, folder, dirName, slug, chapter, hash, threads + found.threads)
                }
                report("$where — asking Slack to make a picture")
                val post = slack.postChapter(text)
                hash = post.hash
                markRequested(ctx, folder, slug, chapter, hash, post.threadTs)
            }
            val all = store.imageReqs(folder, slug, chapter)
            val poll = all.map { it.threadTs }.filter { it.isNotEmpty() }
            val started = all.maxOfOrNull { it.startedAt }?.takeIf { it > 0L }
                ?: System.currentTimeMillis()
            if (!wait) {
                report("$where — asked Slack to make a picture")
                return Result.success(true)
            }
            if (!inflight.add(hash)) {
                report("$where — already waiting for Slack")
                return Result.success(false)
            }
            try {
                val left = started + GIVE_UP_MS - System.currentTimeMillis()
                val remain = left.coerceAtMost(SlackPoster.MAX_WAIT_MS)
                if (expired(started) || remain <= 0L) {
                    report("$where — the wait is up — checking Slack one last time")
                    return lastLook(ctx, slack, folder, dirName, slug, chapter, hash, poll)
                }
                report("$where — waiting for Slack (${waitLabel(left)} left)")
                val png = slack.waitForImage(hash, poll, remain)
                savePng(ctx, folder, dirName, slug, chapter, png.bytes, png.alt)
                Result.success(true)
            } finally {
                inflight.remove(hash)
            }
        } catch (e: Exception) {
            report("${describe(dirName, chapter)} — could not finish (${e.message})")
            val reqs = store.imageReqs(folder, slug, chapter)
            val hash = reqs.firstOrNull { it.hash.isNotEmpty() }?.hash
            val started = reqs.minOfOrNull { it.startedAt } ?: 0L
            if (hash != null && expired(started)) {
                val slack = SlackPoster(token, channel)
                return lastLook(
                    ctx, slack, folder, dirName, slug, chapter, hash,
                    reqs.map { it.threadTs },
                )
            }
            Result.failure(e)
        }
    }

    fun resumeWaiting(ctx: Context, scope: CoroutineScope) {
        ImageService.startIfNeeded(ctx)
    }

    /* Waiting downloads first, novels last-read first, then the next
       auto post. Called from the service so a backgrounded app still
       finishes a png that landed after we left. */
    private fun resumeWaitingNow(ctx: Context) {
        importLegacyWaits(ctx)
        val store = DownloadStore(ctx)
        val waiting = try { store.waitingImageReqs() } catch (e: Exception) { emptyList() }
        if (waiting.isEmpty()) return
        val app = ctx.applicationContext
        val lastRead = HashMap<String, Long>()
        for (folder in waiting.map { it.folder }.distinct()) {
            if (folder.isEmpty()) continue
            val recs = try { store.novels(folder) } catch (e: Exception) { emptyList() }
            for (rec in recs) lastRead["$folder\u0000${rec.slug}"] = rec.lastRead
        }
        val ordered = byLastRead(
            waiting,
            { lastRead["${it.folder}\u0000${it.slug}"] ?: 0L },
            { "${it.slug}\u0000${it.chapter}" },
        )
        report(
            if (ordered.size == 1) "Finishing 1 picture Slack already started"
            else "Finishing ${ordered.size} pictures Slack already started",
        )
        slackPoster(app)?.let { slack ->
            val byNovel = linkedMapOf<String, MutableList<String>>()
            val loc = mutableMapOf<String, Triple<String, String, String>>()
            for (w in ordered) {
                val found = try { store.imageResumeDir(w.folder, w.slug) } catch (e: Exception) {
                    null
                } ?: continue
                val (folder, dir) = found
                val key = "${folder}\u0000${w.slug}\u0000${dir}"
                loc[key] = Triple(folder, dir, w.slug)
                byNovel.getOrPut(key) { mutableListOf() }.add(w.chapter)
            }
            for ((key, chapters) in byNovel) {
                val (folder, dir, slug) = loc[key] ?: continue
                adoptFromSlack(app, slack, folder, dir, slug, chapters)
            }
        }
        val seen = mutableSetOf<String>()
        for (w in ordered) {
            val key = "${w.folder}\u0000${w.slug}\u0000${w.chapter}"
            if (!seen.add(key)) continue
            val found = try { store.imageResumeDir(w.folder, w.slug) } catch (e: Exception) {
                report("${w.chapter} — could not find this novel's folder (${e.message})")
                null
            }
            if (found == null) {
                report("${w.chapter} — could not find this novel's folder")
                continue
            }
            val (folder, dir) = found
            try {
                poll(app, folder, dir, w.slug, w.chapter)
            } catch (e: Exception) {
                report("${describe(dir, w.chapter)} — could not finish (${e.message})")
            }
        }
    }

    /* Prefs wait list from builds before the database rows. Fold it
       in once so a kill mid-poll still resumes. */
    private fun importLegacyWaits(ctx: Context) {
        val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        val raw = prefs.getString(WAIT_KEY, "") ?: ""
        if (raw.isEmpty()) return
        val arr = try { JSONArray(raw.ifEmpty { "[]" }) } catch (e: Exception) { JSONArray() }
        val store = DownloadStore(ctx)
        for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            val folder = w.optString("folder")
            val slug = w.optString("slug")
            val chapter = w.optString("chapter")
            if (folder.isEmpty() || slug.isEmpty() || chapter.isEmpty()) continue
            val started = w.optLong("startedAt", 0L)
            store.rememberImageReq(
                folder, slug, chapter,
                w.optString("hash"), w.optString("threadTs"),
                if (started > 0L) started else System.currentTimeMillis(),
            )
        }
        prefs.edit().remove(WAIT_KEY).apply()
    }

    /* One Slack lookup, no new post. Poll image uses this so a missed
       png can be fetched without uploading the chapter again. */
    fun poll(
        ctx: Context,
        folder: String,
        dirName: String,
        slug: String,
        chapter: String,
    ): Result<Boolean> {
        val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        val token = (prefs.getString("slackBotToken", "") ?: "").trim()
        val channel = (prefs.getString("slackChannelId", "") ?: "").trim()
        importLegacyWaits(ctx)
        val where = describe(dirName, chapter)
        report("$where — checking Slack again")
        val onDisk = adoptDiskImage(ctx, folder, dirName, slug, chapter) != null
        if (onDisk && !needsAltRefresh(true, linkedAlt(ctx, folder, slug, chapter))) {
            report("$where — already on this phone")
            return Result.success(true)
        }
        if (token.isEmpty() || channel.isEmpty()) {
            report("$where — Slack is not set in Settings")
            return Result.failure(IOException("Set Slack in Settings."))
        }
        val store = DownloadStore(ctx)
        val reqs = store.imageReqs(folder, slug, chapter)
        val threads = reqs.map { it.threadTs }.filter { it.isNotEmpty() }
        val hash = reqs.firstOrNull { it.hash.isNotEmpty() }?.hash
            ?: chapterText(ctx, folder, dirName, slug, chapter)?.let { Scenes.contentHash(it) }
        if (hash == null) {
            report("$where — could not read this chapter")
            return Result.failure(IOException("Could not read this chapter."))
        }
        val slack = SlackPoster(token, channel)
        val found = try { slack.findExisting(hash, threads) } catch (e: Exception) {
            report("$where — could not read Slack (${e.message})")
            return Result.failure(e)
        }
        deniedLook(dirName, chapter, found)?.let { return it }
        if (found.png != null) {
            keepImage(ctx, folder, dirName, slug, chapter, found.png, found.alt)
            return Result.success(true)
        }
        if (onDisk) {
            report("$where — already on this phone")
            return Result.success(true)
        }
        for (ts in found.threads) {
            if (ts.isNotEmpty()) markRequested(ctx, folder, slug, chapter, hash, ts)
        }
        store.markImageReqLooked(folder, slug, chapter)
        report("$where — Slack has no picture yet")
        return Result.failure(IOException("No image yet."))
    }

    /* One Slack lookup. Request threads stay unless the png is saved.
       A throw here is a transport / Slack-outage miss — do not mark
       looked, do not give up. */
    private fun lastLook(
        ctx: Context,
        slack: SlackPoster,
        folder: String,
        dirName: String,
        slug: String,
        chapter: String,
        hash: String,
        threads: Collection<String>,
    ): Result<Boolean> {
        val where = describe(dirName, chapter)
        report("$where — checking Slack one last time")
        val found = try { slack.findExisting(hash, threads) } catch (e: Exception) {
            report("$where — could not read Slack (${e.message})")
            return Result.failure(e)
        }
        deniedLook(dirName, chapter, found)?.let { return it }
        if (found.png != null) {
            keepImage(ctx, folder, dirName, slug, chapter, found.png, found.alt)
            return Result.success(true)
        }
        for (ts in found.threads) {
            if (ts.isNotEmpty()) markRequested(ctx, folder, slug, chapter, hash, ts)
        }
        val gone = SlackPoster.lookTopLevelMissing(found)
        val missing = SlackPoster.lookMissing(found.png, found.readError)
        val store = DownloadStore(ctx)
        val started = store.imageReqs(folder, slug, chapter)
            .minOfOrNull { it.startedAt } ?: 0L
        if (gone || (missing && expired(started))) {
            store.markImageReqLooked(folder, slug, chapter)
        }
        if (mayDrop(
                started,
                looked = gone || missing,
                foundNothing = missing,
                topLevelMissing = gone,
            )
        ) {
            report(
                if (gone) "$where — Slack no longer has this chapter posted"
                else "$where — gave up waiting — Slack still has no picture",
            )
            return Result.failure(IOException("Gave up waiting for the image."))
        }
        report("$where — Slack has no picture yet")
        return Result.failure(IOException("No image came back from Slack."))
    }

    fun chapterText(
        ctx: Context,
        folder: String,
        dirName: String,
        slug: String,
        chapter: String,
    ): String? {
        val treeUri = Uri.parse(folder)
        val store = DownloadStore(ctx)
        val order = try { store.getChapterOrder(folder, slug) } catch (e: Exception) { emptyMap() }
        val ch = try {
            ChapterListActivity.chapterNames(ctx, treeUri, dirName, order, slug)
        } catch (e: Exception) { null } ?: return null
        val ref = ch.translated[chapter] ?: ch.source[chapter] ?: return null
        return try {
            if (Zips.isGzRef(ref)) Zips.readGz(ctx.contentResolver, treeUri, Zips.gzDocId(ref))
            else Saf.readText(ctx.contentResolver, treeUri, ref)
        } catch (e: Exception) { null }
    }

    data class Saved(
        val chapter: String,
        val number: Int,
        val label: String,
        val uri: Uri,
        val alt: String = "",
    )

    /* Slack's description after trim. Padding is not a caption. */
    fun altText(alt: String): String = alt.trim()

    /* Empty / whitespace is not a caption — hide it so an older
       row or a disk-adopted png does not draw a blank line. */
    fun showAlt(alt: String): Boolean = altText(alt).isNotEmpty()

    /* A png already on disk with no caption should still ask Slack.
       Poll / adopt / resume fill chapter_image.alt and leave the file. */
    fun needsAltRefresh(hasImage: Boolean, storedAlt: String): Boolean =
        hasImage && !showAlt(storedAlt)

    /* chapter_image rows for this novel, lowest chapter number first.
       One exists-query per row — not a listing of scenes/. */
    fun listSaved(
        ctx: Context,
        folder: String,
        dirName: String,
        slug: String,
    ): List<Saved> {
        if (folder.isEmpty() || dirName.isEmpty() || slug.isEmpty()) return emptyList()
        val tree = Uri.parse(folder)
        val rootId = Saf.rootId(tree)
        val rows = try { DownloadStore(ctx).chapterImages(folder, slug) } catch (e: Exception) {
            emptyList()
        }
        val out = mutableListOf<Saved>()
        for (row in rows) {
            if (!imageOnDisk(ctx, folder, dirName, row.image)) {
                forgetMissingImage(ctx, folder, slug, row.chapter)
                continue
            }
            val uri = DocumentsContract.buildDocumentUriUsingTree(
                tree, resolveImageDocId(rootId, dirName, row.image),
            )
            out.add(savedOf(row.chapter, uri, row.alt))
        }
        return sortSaved(out)
    }

    fun savedOf(chapter: String, uri: Uri, alt: String = ""): Saved {
        val number = Scenes.chapterNumber(chapter) ?: Int.MAX_VALUE
        val label = if (number == Int.MAX_VALUE) Scenes.chapterStem(chapter) else number.toString()
        return Saved(chapter, number, label, uri, altText(alt))
    }

    fun sortSaved(items: List<Saved>): List<Saved> =
        items.sortedWith(compareBy<Saved> { it.number }.thenBy { it.chapter })

    /* What tapping a synopsis-grid picture does. Expand — never
       open the chapter. */
    enum class SynopsisTap { EXPAND, OPEN_CHAPTER }

    fun synopsisTap(): SynopsisTap = SynopsisTap.EXPAND

    /* Next or previous picture in the full-screen gallery.
       Does not wrap — first and last stay put. */
    fun neighborSaved(index: Int, size: Int, delta: Int): Int? {
        if (size <= 0 || delta == 0 || index !in 0 until size) return null
        val next = index + delta
        return next.takeIf { it in 0 until size }
    }

    fun indexOfSaved(items: List<Saved>, chapter: String): Int =
        items.indexOfFirst { it.chapter == chapter }

    /* The reader inserts one object-replacement char for the picture.
       A tap on that char (or just after it) is a tap on the picture. */
    fun objectReplacementAt(text: CharSequence, off: Int): Boolean {
        if (text.isEmpty()) return false
        val i = off.coerceIn(0, text.length)
        if (i < text.length && text[i] == '\uFFFC') return true
        if (i > 0 && text[i - 1] == '\uFFFC') return true
        return false
    }

    fun imageAt(text: CharSequence, off: Int): Boolean {
        if (!objectReplacementAt(text, off)) return false
        val s = text as? android.text.Spanned ?: return false
        val start = (off - 1).coerceAtLeast(0)
        val end = (off + 1).coerceAtMost(s.length)
        return s.getSpans(start, end, ChapterImageSpan::class.java).isNotEmpty()
    }

    /* The reader embeds one ChapterImageSpan under the heading.
       Used to show the picture button for that chapter. */
    fun hasEmbeddedPicture(text: CharSequence): Boolean {
        val s = text as? android.text.Spanned ?: return false
        return s.getSpans(0, s.length, ChapterImageSpan::class.java).isNotEmpty()
    }

    /* Horizontal swipe: left → next, right → previous. A vertical
       flick or a short/slow one is not a page change. */
    fun swipeDelta(
        dx: Float,
        dy: Float,
        vx: Float,
        minDist: Float,
        minSpeed: Float,
    ): Int? {
        if (kotlin.math.abs(dx) < kotlin.math.abs(dy)) return null
        if (kotlin.math.abs(dx) < minDist || kotlin.math.abs(vx) < minSpeed) return null
        return if (dx < 0f) 1 else -1
    }

    fun linkedImage(ctx: Context, folder: String, slug: String, chapter: String): String? {
        if (folder.isEmpty() || slug.isEmpty() || chapter.isEmpty()) return null
        return try { DownloadStore(ctx).chapterImage(folder, slug, chapter) } catch (e: Exception) { null }
    }

    fun linkedAlt(ctx: Context, folder: String, slug: String, chapter: String): String {
        if (folder.isEmpty() || slug.isEmpty() || chapter.isEmpty()) return ""
        return try { DownloadStore(ctx).chapterImageAlt(folder, slug, chapter) } catch (e: Exception) { "" }
    }

    fun hasLocalImage(
        ctx: Context,
        folder: String,
        dirName: String,
        chapter: String,
        slug: String = "",
    ): Boolean = adoptDiskImage(ctx, folder, dirName, slug, chapter) != null

    /* One document query for scenes/{chapter}.png — not a listing of
       scenes/. Generate image does this before any Slack call. A
       database row whose file is gone is dropped. */
    fun adoptDiskImage(
        ctx: Context,
        folder: String,
        dirName: String,
        slug: String,
        chapter: String,
    ): String? {
        if (folder.isEmpty() || dirName.isEmpty() || slug.isEmpty() || chapter.isEmpty()) return null
        val linked = linkedImage(ctx, folder, slug, chapter)
        if (!linked.isNullOrEmpty()) {
            if (imageOnDisk(ctx, folder, dirName, linked)) return linked
            report("${describe(dirName, chapter)} — the saved picture is missing from this phone")
            forgetMissingImage(ctx, folder, slug, chapter)
        }
        val name = Scenes.imageName(chapter)
        if (!imageOnDisk(ctx, folder, dirName, name)) return null
        try { DownloadStore(ctx).setChapterImage(folder, slug, chapter, name) } catch (e: Exception) {
            report("${describe(dirName, chapter)} — could not remember the picture on this phone (${e.message})")
            return null
        }
        return name
    }

    fun imageOnDisk(ctx: Context, folder: String, dirName: String, image: String): Boolean {
        if (folder.isEmpty() || dirName.isEmpty() || image.isEmpty()) return false
        val tree = Uri.parse(folder)
        return try {
            Saf.exists(ctx.contentResolver, tree, resolveImageDocId(Saf.rootId(tree), dirName, image))
        } catch (e: Exception) { false }
    }

    fun imageDocId(rootId: String, dirName: String, image: String): String {
        val base = "$rootId/$dirName/${Scenes.DIR}"
        return if (image.isEmpty()) base else "$base/$image"
    }

    /* A stored value with a slash is the provider's document id from
       the write. A bare filename is the older row — guess the path. */
    fun resolveImageDocId(rootId: String, dirName: String, stored: String): String {
        if (stored.contains('/')) return stored
        return imageDocId(rootId, dirName, stored)
    }

    fun chapterUri(
        ctx: Context,
        folder: String,
        dirName: String,
        chapter: String,
        slug: String = "",
    ): Uri? {
        val name = linkedImage(ctx, folder, slug, chapter) ?: return null
        val tree = Uri.parse(folder)
        return DocumentsContract.buildDocumentUriUsingTree(
            tree, resolveImageDocId(Saf.rootId(tree), dirName, name),
        )
    }

    fun forgetMissingImage(ctx: Context, folder: String, slug: String, chapter: String) {
        log("${chapter}: dropped the saved-picture row — the file is gone")
        try { DownloadStore(ctx).clearChapterImage(folder, slug, chapter) } catch (e: Exception) {}
    }

    fun thumb(ctx: Context, uri: Uri, edgePx: Int): android.graphics.Bitmap? {
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            }
            val w = opts.outWidth
            val h = opts.outHeight
            if (w <= 0 || h <= 0) return null
            val sample = maxOf(1, minOf(w, h) / edgePx.coerceAtLeast(1))
            val dec = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, dec)
            }
        } catch (e: Exception) { null }
    }

    /* Write a new png, or only the caption when the file is already
       on disk. A blank incoming alt keeps a stored caption. */
    private fun keepImage(
        ctx: Context,
        folder: String,
        dirName: String,
        slug: String,
        chapter: String,
        bytes: ByteArray,
        alt: String,
    ) {
        val existing = linkedImage(ctx, folder, slug, chapter)
        if (!existing.isNullOrEmpty() && imageOnDisk(ctx, folder, dirName, existing)) {
            DownloadStore(ctx).setChapterImage(folder, slug, chapter, existing, alt)
            if (altText(alt).isNotEmpty()) {
                report("${describe(dirName, chapter)} — saved the picture's description")
            }
            return
        }
        savePng(ctx, folder, dirName, slug, chapter, bytes, alt)
    }

    private fun savePng(
        ctx: Context,
        folder: String,
        dirName: String,
        slug: String,
        chapter: String,
        bytes: ByteArray,
        alt: String = "",
    ) {
        val dir = scenesDir(ctx, folder, dirName, create = true)
            ?: throw IOException("Could not create scenes/.")
        val name = Scenes.imageName(chapter)
        val docId = writeBytes(ctx, dir, name, "image/png", bytes)
        if (docId == null) {
            report("${describe(dirName, chapter)} — could not save the picture (${sizeLabel(bytes.size)})")
            throw IOException("Could not save the image.")
        }
        val store = DownloadStore(ctx)
        store.setChapterImage(folder, slug, chapter, docId, alt)
        report("${describe(dirName, chapter)} — saved (${sizeLabel(bytes.size)})")
        /* Keep the Slack threads. Clearing them was why Chapter 400
           posted a second {hash}.txt after the saved png could not be
           opened — the hash was gone, so the next tap hashed new text. */
        try { store.forgetDiskBytes(folder, slug) } catch (e: Exception) {}
    }

    private fun scenesDir(
        ctx: Context,
        folder: String,
        dirName: String,
        create: Boolean,
    ): DocumentFile? {
        val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(folder)) ?: return null
        val novel = tree.findFile(dirName)?.takeIf { it.isDirectory } ?: return null
        val existing = novel.findFile(Scenes.DIR)?.takeIf { it.isDirectory }
        if (existing != null || !create) return existing
        return novel.createDirectory(Scenes.DIR)
    }

    /* Write under a name nothing adopts, then rename — a kill mid-write
       otherwise leaves a short file treated as the finished image. */
    private fun writeBytes(
        ctx: Context,
        dir: DocumentFile,
        name: String,
        mime: String,
        bytes: ByteArray,
    ): String? {
        return try {
            dir.findFile(name)?.delete()
            val f = dir.createFile(mime, Zips.partName(name)) ?: return null
            try {
                ctx.contentResolver.openOutputStream(f.uri)?.use { it.write(bytes) }
                    ?: throw IOException("could not open $name")
                val done = DocumentsContract.renameDocument(ctx.contentResolver, f.uri, name)
                    ?: throw IOException("could not name $name")
                val got = Zips.docName(ctx.contentResolver, done)
                if (got != null && got != name) {
                    try { DocumentsContract.deleteDocument(ctx.contentResolver, done) } catch (e: Exception) {}
                    return null
                }
                try { DocumentsContract.getDocumentId(done) } catch (e: Exception) { name }
            } catch (e: Exception) {
                log("could not write $name (${e.message})")
                try { f.delete() } catch (e2: Exception) {}
                null
            }
        } catch (e: Exception) {
            log("could not write $name (${e.message})")
            null
        }
    }
}
