package dev.vtlinh.noveldownloader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/* Native download engine. No proxy: Android apps have no CORS, so chapters
   are fetched straight from the novel site. Chapters download concurrently
   (Semaphore-capped) and write through the Storage Access Framework. */
class DownloadEngine(
    private val context: Context,
    private val log: (String) -> Unit,
    private val status: (String) -> Unit,
    private val progress: (Int, Int) -> Unit,
) {
    @Volatile var stopRequested = false
    @Volatile private var translator: Translator? = null

    /* Stop instantly: set the cooperative flag AND abort every in-flight
       HTTP request (chapter fetches, and any running translation call) so
       we don't wait out sockets/timeouts. */
    fun stop() {
        stopRequested = true
        try { client.dispatcher.cancelAll() } catch (e: Exception) {}
        try { translator?.cancel() } catch (e: Exception) {}
    }

    companion object {
        const val CONC_START = 20
        const val CONC_MIN = 5
        const val CONC_MAX = 50
        const val FETCH_BATCH = 50
        /* a differing heading shifts a chapter's size by only a few bytes */
        private const val HEADING_SLACK = 96L
        /* how many chapters a listing may drop before we stop believing the
           listing instead of the files: below this it reads as ordinary site
           housekeeping, above it only a proportional check will do */
        private const val MAX_QUIET_DROPS = 10
        /* a listing page that wouldn't load is usually throttling, so give it
           room rather than spending all three retries inside one busy second */
        private const val LIST_RETRY_MS = 7_000

        /* Bumped whenever a rename pass actually moves a file. A reader that
           is open on that novel is holding a list of names that just stopped
           being true, and it saves the reading spot BY NAME — so it needs to
           know its copy is stale rather than writing it back over the remap
           the rename just did. Keyed by novel: one counter for the library
           meant a rename of ANY novel — a queued download, a status sweep —
           silently stopped recording the place in the one being read. */
        private val renameEpochs = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /* Letters and digits only, so the two sides agree. The engine bumps
           under the site's slug; a reader opened on a novel adopted by the
           folder scan holds a slug derived from the folder name instead, and
           on a raw string compare those never match — leaving the guard
           permanently off for exactly the libraries most likely to be
           renumbered. Same rule the Library uses to reconcile the two. */
        private fun epochKey(slug: String) = slug.lowercase().filter { it.isLetterOrDigit() }

        fun renameEpochOf(slug: String): Long = renameEpochs[epochKey(slug)] ?: 0L

        fun bumpRenameEpoch(slug: String) {
            renameEpochs[epochKey(slug)] = renameEpochOf(slug) + 1
        }

        /* app-private cover thumbnail for a novel */
        fun coverFile(context: Context, slug: String): java.io.File =
            java.io.File(context.filesDir, "covers/$slug.jpg")

        private const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        /* Does the novel page read as English? (>50% of its characters are
           a-z/A-Z — Vietnamese's diacritics fall below that.) Used to warn
           before translating an already-English novel. Null if the check
           couldn't run (network error) so callers don't over-warn. */
        suspend fun sourceLooksEnglish(url: String): Boolean? = withContext(Dispatchers.IO) {
            try {
                val doc = org.jsoup.Jsoup.connect(url).userAgent(UA).timeout(15000).get()
                val t = doc.text()
                if (t.isEmpty()) return@withContext null
                var eng = 0
                for (c in t) if (c in 'a'..'z' || c in 'A'..'Z') eng++
                eng.toDouble() / t.length > 0.5
            } catch (e: Exception) { null }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    class FetchResult(val html: String?, val status: Int)

    private fun fetch(url: String): FetchResult = try {
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept-Language", "vi,en;q=0.8")
                .build(),
        ).execute().use { r ->
            FetchResult(if (r.isSuccessful) r.body?.string() else null, r.code)
        }
    } catch (e: Exception) {
        FetchResult(null, 0)   // 0 = network error
    }

    /* Adaptive parallelism, same rules as the web app — >=20% throttle
       statuses (429/503/408/network) in a window halves the limit (floor
       CONC_MIN), a fully-clean window raises it by 10 (ceil CONC_MAX) — but
       applied to a rolling window of results instead of batch boundaries:
       chapters stream through one shared pool with no barrier, so a slow
       straggler never idles the other slots.

       The pool is a fixed CONC_MAX-permit Semaphore whose effective limit is
       shrunk by holding back "filler" permits; adjusting `conc` just acquires
       or releases fillers. */
    @Volatile private var conc = CONC_START
    private fun isThrottle(s: Int) = s == 429 || s == 503 || s == 408 || s == 0

    private val pool = Semaphore(CONC_MAX)
    private val fillerLock = Mutex()
    private var filler = 0   // permits held back; effective limit = CONC_MAX - filler

    private suspend fun setConc(target: Int) {
        val t = target.coerceIn(CONC_MIN, CONC_MAX)
        conc = t
        fillerLock.withLock {
            val want = CONC_MAX - t
            while (filler < want) { pool.acquire(); filler++ }
            while (filler > want) { pool.release(); filler-- }
        }
    }

    /* rolling adaptation window: once FETCH_BATCH results accumulate, apply
       the halve/grow rules and start a fresh window. `status` null = saved. */
    private val adaptLock = Mutex()
    private var winOk = 0
    private var winStatuses = ArrayList<Int>()

    private suspend fun recordResult(status: Int?) {
        var target = -1
        adaptLock.withLock {
            if (status == null) winOk++ else winStatuses.add(status)
            val n = winOk + winStatuses.size
            if (n >= FETCH_BATCH) {
                val throttled = winStatuses.count { isThrottle(it) }
                if (winStatuses.isNotEmpty()) {
                    log("last $n chapters: ${winStatuses.size} failed" +
                        (if (throttled > 0) " — site appears to be rate limiting" else ""))
                }
                if (throttled >= maxOf(2, Math.ceil(n * 0.2).toInt())) {
                    target = maxOf(CONC_MIN, conc / 2)
                } else if (winStatuses.isEmpty() && conc < CONC_MAX) {
                    target = minOf(CONC_MAX, conc + 10)
                }
                winOk = 0
                winStatuses = ArrayList()
            }
        }
        if (target > 0) setConc(target)
    }

    /* rolling 7-second download-rate readout */
    private val rateStamps = java.util.concurrent.ConcurrentLinkedDeque<Long>()
    @Volatile private var fetchStart = 0L
    private fun noteSaved() { rateStamps.add(System.currentTimeMillis()) }
    private fun liveRate(): String {
        if (fetchStart == 0L) return ""
        val now = System.currentTimeMillis()
        while (rateStamps.isNotEmpty() && rateStamps.peekFirst() < now - 7000) rateStamps.pollFirst()
        val windowSecs = minOf(7000L, now - fetchStart) / 1000.0
        if (windowSecs < 1) return ""
        return " · %.1f/s".format(rateStamps.size / windowSecs)
    }

    class Chapter(val url: String, val text: String) {
        var num: Int? = null
        var filename: String? = null
    }

    /* The site's own number for a chapter, used for the heading written
       inside the file. A link the site doesn't number — its own typo, or an
       unnumbered extra like an interlude — takes the number of the last
       numbered chapter ahead of it, which is where the page puts it.
       Nothing about the FILE depends on this any more. */
    private fun numberByPosition(inSiteOrder: List<Chapter>) {
        var last: Int? = null
        for (ch in inSiteOrder) {
            if (ch.num != null) last = ch.num else ch.num = last
        }
    }

    /* Bring the files into the page's order: the chapter at listing position
       i lives in "Chapter i+1.txt". A chapter added in the middle pushes
       every chapter after it up one, and the files move with them.

       Renames run from the LAST chapter backwards. Positions shift up when
       something is inserted, so working from the end frees each target name
       just before it is needed — nothing has to be parked under a temporary
       name, and no file is moved twice however many insertions there were. A
       listing that LOST a chapter shifts the other way, so a forward pass
       picks up whatever the backward pass could not place, and a genuine
       cycle falls back to a temp name.

       A chapter's page URL says which file is its own. Files saved before
       that was recorded are matched by the name the old numbering would have
       given them, so an existing library is renamed into order rather than
       downloaded again. */
    private fun renameToListingOrder(
        treeUri: Uri,
        dir: DocumentFile,
        store: DownloadStore,
        folderKey: String,
        slug: String,
        inSiteOrder: List<Chapter>,
    ) {
        val cr = context.contentResolver
        val byUrl = store.urlMap(folderKey, slug)
        val onRecord = store.get(folderKey, slug)
        if (byUrl.isEmpty() && onRecord.isEmpty()) return    // nothing downloaded yet
        val legacy = legacyNames(inSiteOrder)

        /* Work out what has to move BEFORE listing anything: a library-wide
           status check runs this per novel, and most of them need no moves
           at all — those should cost a DB read, not a directory walk. */
        val pending = LinkedHashMap<String, String>()
        /* Files that already answer for a chapter. The legacy fallback below
           guesses at a name from the site's own numbering, and the two schemes
           share one namespace — so where the site's number and the listing
           position differ, that guess can land on a file another chapter has
           already proved is its own, and rename it away from that chapter. A
           recorded page beats a guess. */
        val spokenFor = byUrl.values.toHashSet()
        val claimedUrl = HashMap<String, String>()   // legacy-matched name -> its page
        for (ch in inSiteOrder.asReversed()) {           // last chapter first
            val want = ch.filename ?: continue
            val have = byUrl[ch.url]
                ?: legacy[ch.url]?.takeIf { it in onRecord && it !in spokenFor }
                ?: continue
            if (have != want) {
                pending[have] = want
                /* Recognised by guesswork, so record the page once the move
                   lands: otherwise the row stays url-less and every later run
                   has to guess again from the same ambiguous namespace. */
                if (ch.url !in byUrl) claimedUrl[have] = ch.url
            } else if (ch.url !in byUrl) {
                store.linkUrl(folderKey, slug, have, ch.url)
            }
        }
        if (pending.isEmpty()) return

        val dirId = try { DocumentsContract.getDocumentId(dir.uri) } catch (e: Exception) { return }
        val files = HashMap<String, String>()                // name -> docId
        var translatedId: String? = null
        for (e in Saf.children(cr, treeUri, dirId)) {
            if (e.isDir) { if (e.name == "translated") translatedId = e.docId } else files[e.name] = e.docId
        }
        val translated = HashMap<String, String>()
        translatedId?.let { id ->
            for (e in Saf.children(cr, treeUri, id)) if (!e.isDir) translated[e.name] = e.docId
        }

        /* Free means free in BOTH folders. A translation whose source is gone
           still sits in translated/ under its old name, and renaming a
           chapter onto it would collide there while the main folder looked
           clear — leaving the translation stranded on the wrong chapter. */
        fun occupied(n: String) =
            files.containsKey(n) || files.containsKey("$n.gz") ||
                translated.containsKey(n) || translated.containsKey("$n.gz")

        /* Where the reader and the read-aloud left off is remembered by
           FILENAME, so a mark has to travel with its file. Only moves that
           the provider actually performed count: an intended move that never
           happened — held up by a file nothing will shift — would otherwise
           walk the mark one chapter back on every single pass. `whereFrom`
           follows a file that gets parked mid-cycle back to the name the mark
           knows it by. */
        val applied = LinkedHashMap<String, String>()
        val whereFrom = HashMap<String, String>()

        /* move both the chapter and its translated counterpart; a compressed
           chapter is the same name with .gz on the end */
        fun move(from: String, to: String): Boolean {
            var moved = false
            for (sfx in listOf("", ".gz")) {
                files[from + sfx]?.let { id ->
                    Saf.rename(cr, treeUri, id, to + sfx)?.let { newId ->
                        files.remove(from + sfx)
                        files[to + sfx] = newId
                        store.renameChapter(
                            folderKey, slug, from, to,
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, newId).toString(),
                        )
                        moved = true
                    }
                }
                translated[from + sfx]?.let { id ->
                    Saf.rename(cr, treeUri, id, to + sfx)?.let { newId ->
                        translated.remove(from + sfx)
                        translated[to + sfx] = newId
                    }
                }
            }
            if (moved) {
                val orig = whereFrom.remove(from) ?: from
                whereFrom[to] = orig
                applied[orig] = to
            }
            return moved
        }

        status("Renumbering chapters to match the site…")
        var renamed = 0
        var parked = 0
        var evicted = 0
        while (pending.isNotEmpty() && !stopRequested) {
            var progress = false
            val walk = pending.entries.iterator()
            while (walk.hasNext()) {
                val e = walk.next()
                if (occupied(e.value)) {
                    if (pending.containsKey(e.value)) continue    // its turn comes
                    /* Held by a file no rename will move — a chapter the site
                       dropped, which has no place in the numbering at all.
                       Giving up on this one used to be contagious: the rename
                       below it then saw an occupant that was no longer pending
                       either, so a single squatter unravelled the whole shift
                       one chapter per pass, and the files stopped matching the
                       order the reader sorts by. Move the squatter aside under
                       a name that says what it is; the dedupe pass that runs
                       next accounts for it. */
                    val stem = e.value.removeSuffix(".txt")
                    var aside = "$stem (unlisted).txt"
                    var n = 2
                    while (occupied(aside) && n < 100) { aside = "$stem (unlisted $n).txt"; n++ }
                    move(e.value, aside)
                    if (occupied(e.value)) {          // the provider refused — leave this one
                        walk.remove()
                        progress = true
                        continue
                    }
                    evicted++
                }
                if (move(e.key, e.value)) renamed++
                walk.remove()
                progress = true
            }
            if (progress) continue
            /* nothing could move: the remaining renames form a cycle, so park
               one out of the way to break it */
            val e = pending.entries.first()
            val park = "Chapter __shift$parked.txt"
            parked++
            move(e.key, park)
            pending.remove(e.key)
            pending[park] = e.value
            if (parked > inSiteOrder.size) break     // safety valve
        }
        /* A file matched by guesswork has now proved where it went, so give it
           its page and it never has to be guessed at again. */
        for ((orig, finalName) in applied) {
            claimedUrl[orig]?.let { store.linkUrl(folderKey, slug, finalName, it) }
        }
        remapSavedSpot(slug, applied)
        if (applied.isNotEmpty()) bumpRenameEpoch(slug)
        if (renamed > 0) log("renamed $renamed chapter file(s) into the site's order")
        if (evicted > 0) log("$evicted file(s) the site no longer lists moved out of the numbering")
    }

    /* The novel's folder, as the download names it: the stored English
       title when there is one, otherwise the site's own title sanitised.
       Null when it isn't there yet — nothing downloaded, nothing to fix. */
    private fun novelDir(
        treeUri: Uri,
        store: DownloadStore,
        folderKey: String,
        slug: String,
        doc: org.jsoup.nodes.Document,
    ): DocumentFile? = try {
        /* The recorded directory first. Rebuilding the name from the title
           gives the UNSUFFIXED one, so for a novel that was pushed off a
           colliding name this resolved to the other novel's folder — and
           Check status then renamed and deduped that novel's files against
           this one's listing. */
        val name = store.dirNameFor(folderKey, slug)
            ?: store.getTitle(folderKey, slug)
            ?: Extractor.sanitize(
                Extractor.stripAuthor(
                    doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifEmpty { null }
                        ?: doc.selectFirst("h3.title")?.text()?.trim()?.ifEmpty { null }
                        ?: doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null }
                        ?: slug,
                    Sites.author(doc),
                ),
            )
        DocumentFile.fromTreeUri(context, treeUri)?.findFile(name)?.takeIf { it.isDirectory }
    } catch (e: Exception) {
        null
    }

    /* The saved reading spot and read-aloud spot both name their chapter by
       filename, so a rename has to carry them across. Fed only the moves the
       rename pass really made, once it has finished — the marks are a
       pointer, not a file, and nothing reads them mid-pass. */
    private fun remapSavedSpot(slug: String, moves: Map<String, String>) {
        try {
            val p = context.getSharedPreferences("app", Context.MODE_PRIVATE)
            val e = p.edit()
            var any = false
            p.getString("lastCh:$slug", null)?.let { cur ->
                moves[cur]?.let { e.putString("lastCh:$slug", it); any = true }
            }
            p.getString("ttsPos:$slug", null)?.let { cur ->
                val name = cur.substringBefore('|')
                moves[name]?.let {
                    e.putString("ttsPos:$slug", it + "|" + cur.substringAfter('|', ""))
                    any = true
                }
            }
            if (any) e.apply()
        } catch (ex: Exception) {}
    }

    /* Chapter files nothing in the listing points at — left behind when an
       earlier build named the same chapter differently and downloaded it a
       second time. An extra is only removed once its CONTENT is shown to
       match a file we're keeping: same size, then same hash. Anything whose
       bytes are unique stays put and is reported, because a file no longer
       referenced is not the same thing as a file that's safe to delete.

       Hashing costs a read, so it's confined to orphans and the kept files
       that share their exact size, and every hash computed is remembered so
       later passes don't repeat the work. */
    private fun dedupeExtras(
        treeUri: Uri,
        dir: DocumentFile,
        store: DownloadStore,
        folderKey: String,
        slug: String,
        assigned: Set<String>,
        listedUrls: Set<String>,
    ): Int {
        val cr = context.contentResolver
        val dirId = try { DocumentsContract.getDocumentId(dir.uri) } catch (e: Exception) { return 0 }
        val re = ChapterListActivity.CHAPTER_RE

        class OnDisk(val name: String, val base: String, val docId: String, val size: Long)
        val chapterFiles = ArrayList<OnDisk>()
        var translatedId: String? = null
        for (e in Saf.children(cr, treeUri, dirId)) {
            if (e.isDir) { if (e.name == "translated") translatedId = e.docId; continue }
            val base = e.name.removeSuffix(".gz")
            if (re.matches(base)) chapterFiles.add(OnDisk(e.name, base, e.docId, e.size))
        }
        val extras = chapterFiles.filter { it.base !in assigned }
        if (extras.isEmpty()) return 0

        /* Fingerprint the BODY, not the file. Every chapter is written as
           "<heading>\n<content>" and the heading carries the chapter number,
           so the same chapter saved under two different numbering schemes
           differs in its first line — and in its length, which is why an
           exact size+hash match misses these entirely. Comparing what comes
           after the heading recognises them as the same text.

           Sizes still narrow the field: only the heading differs, so a twin
           is within a few dozen bytes. */
        val known = store.fingerprints(folderKey, slug)
        fun bodyHash(f: OnDisk): String? {
            known[f.base]?.let { if (it.first == f.size) return it.second }
            val text = (
                if (f.name.endsWith(".gz")) Zips.readGz(cr, treeUri, f.docId)
                else Saf.readText(cr, treeUri, f.docId)
                ) ?: return null
            val body = text.substringAfter('\n', "").ifEmpty { text }
            val h = java.security.MessageDigest.getInstance("SHA-256")
                .digest(body.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            known[f.base] = Pair(f.size, h)
            store.setFingerprint(folderKey, slug, f.base, f.size, h)
            return h
        }

        val keptSorted = chapterFiles.filter { it.base in assigned }.sortedBy { it.size }
        /* a heading swap moves the size by only a handful of bytes */
        fun nearBySize(size: Long): List<OnDisk> {
            val lo = keptSorted.binarySearch { it.size.compareTo(size - HEADING_SLACK) }
                .let { if (it < 0) -it - 1 else it }
            val out = ArrayList<OnDisk>()
            var i = lo
            while (i < keptSorted.size && keptSorted[i].size <= size + HEADING_SLACK) {
                out.add(keptSorted[i]); i++
            }
            return out
        }

        fun remove(x: OnDisk): Boolean {
            val ok = try {
                DocumentsContract.deleteDocument(
                    cr, DocumentsContract.buildDocumentUriUsingTree(treeUri, x.docId),
                )
            } catch (e: Exception) { false }
            if (!ok) return false
            translatedId?.let { tid ->
                for (t in Saf.children(cr, treeUri, tid)) {
                    if (!t.isDir && t.name == x.name) {
                        try {
                            DocumentsContract.deleteDocument(
                                cr, DocumentsContract.buildDocumentUriUsingTree(treeUri, t.docId),
                            )
                        } catch (e: Exception) {}
                    }
                }
            }
            store.removeChapter(folderKey, slug, x.base)
            return true
        }

        var removed = 0
        var kept = 0
        /* filename -> the page it came from: what turns "some file nothing
           points at" into an answerable question */
        val fileUrl = store.fileUrls(folderKey, slug)
        /* Identity is only ever as good as the list it is compared against,
           and a listing can come back short without any fetch failing — the
           scoped container gone after a layout change, so discovery fell back
           to the whole document and found only the "latest chapters" widget;
           or the novel served from the site's other host, which makes every
           recorded page look unlisted. A real site removal takes a chapter or
           two. Losing a large slice of everything we can identify is evidence
           about the LISTING, not about the files, so say so and keep them. */
        val unlisted = extras.count { val u = fileUrl[it.base]; u != null && u !in listedUrls }
        val identified = fileUrl.size
        /* Half, not a fifth. A listing that is actually broken loses nearly
           everything — the widget-only fallback finds ten links out of seven
           thousand — while a site genuinely purging chapters takes a slice. At
           a fifth, a real purge of 15 from a 60-chapter novel tripped the
           guard, and the arithmetic is identical on every later run, so those
           files were parked as "(unlisted)" for good while the log promised to
           reconsider "until it reads correctly". */
        val trustDrops = unlisted <= MAX_QUIET_DROPS || unlisted * 2 <= identified
        if (!trustDrops) {
            log("! the site's list is missing $unlisted of the $identified chapters we have on record")
            log("! that is too much to be chapters the site removed — keeping them all until it reads correctly")
        }
        var dropped = 0
        var suspect = 0
        for (x in extras) {
            val from = fileUrl[x.base]
            if (from != null && from !in listedUrls) {
                /* We know exactly which chapter this is, and the site no
                   longer lists it. Not a mystery and not a duplicate — a
                   chapter that has been removed, so its file goes with it.
                   No content check: identity already answered the question. */
                if (!trustDrops) { suspect++; continue }
                if (remove(x)) { dropped++; continue }
            }
            if (from != null) {
                /* Its chapter IS listed, so the file is live — the rename
                   pass just couldn't place it (a name still held, most
                   likely). Leave it; next run it moves. */
                kept++
                log("  kept, still listed but not yet in place: ${x.name}")
                continue
            }
            val candidates = nearBySize(x.size)
            val xh = if (candidates.isEmpty()) null else bodyHash(x)
            val twin = if (xh == null) null else candidates.firstOrNull { bodyHash(it) == xh }
            if (twin == null) { kept++; log("  extra kept (text found nowhere else): ${x.name}"); continue }
            /* same bytes as a chapter we're keeping — drop the copy */
            if (remove(x)) removed++
        }
        if (removed > 0) log("removed $removed duplicate chapter file(s) — same text as a chapter we kept")
        if (dropped > 0) log("removed $dropped chapter file(s) the site no longer lists")
        if (suspect > 0) log("$suspect file(s) the list didn't mention were kept — the list looks wrong, not the files")
        if (kept > 0) log("$kept file(s) left alone — see above for why")
        return kept + suspect
    }

    /* After a forced full re-download every listed chapter is on disk under
       its assigned name, verified by the download itself. At that point a
       chapter file nothing points at isn't a maybe-duplicate — it is simply
       not part of the novel any more, and can go without inspecting its
       text. This is the only place that deletes on "unreferenced" alone,
       and it is only reachable once the fetch has proved the alternative. */
    private fun purgeUnreferenced(
        treeUri: Uri,
        dir: DocumentFile,
        store: DownloadStore,
        folderKey: String,
        slug: String,
        assigned: Set<String>,
    ): Int {
        val cr = context.contentResolver
        val dirId = try { DocumentsContract.getDocumentId(dir.uri) } catch (e: Exception) { return 0 }
        val re = ChapterListActivity.CHAPTER_RE
        var translatedId: String? = null
        val doomed = ArrayList<Pair<String, String>>()      // name -> docId
        for (e in Saf.children(cr, treeUri, dirId)) {
            if (e.isDir) { if (e.name == "translated") translatedId = e.docId; continue }
            val base = e.name.removeSuffix(".gz")
            if (re.matches(base) && base !in assigned) doomed.add(Pair(base, e.docId))
        }
        if (doomed.isEmpty()) return 0
        val translated = HashMap<String, String>()
        translatedId?.let { id ->
            for (e in Saf.children(cr, treeUri, id)) if (!e.isDir) translated[e.name.removeSuffix(".gz")] = e.docId
        }
        var gone = 0
        for ((base, docId) in doomed) {
            val ok = try {
                DocumentsContract.deleteDocument(
                    cr, DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                )
            } catch (e: Exception) { false }
            if (!ok) continue
            translated[base]?.let { tid ->
                try {
                    DocumentsContract.deleteDocument(
                        cr, DocumentsContract.buildDocumentUriUsingTree(treeUri, tid),
                    )
                } catch (e: Exception) {}
            }
            store.removeChapter(folderKey, slug, base)
            gone++
        }
        return gone
    }

    /* How chapters were named before the URL map existed: the site's own
       number, plus a suffix for a repeat. Only used to recognise files
       already on disk so they aren't downloaded again under new names. */
    private fun legacyNames(inSiteOrder: List<Chapter>): Map<String, String> {
        val counts = HashMap<Int, Int>()
        val out = HashMap<String, String>()
        for (ch in inSiteOrder.filter { it.num != null }.sortedBy { it.num }) {
            val n = ch.num ?: continue
            val c = (counts[n] ?: 0) + 1
            counts[n] = c
            out[ch.url] = "Chapter $n" + (if (c > 1) "-$c" else "") + ".txt"
        }
        return out
    }

    /* fetch the novel-page cover once into app-private storage (non-fatal) */
    private fun saveCover(slug: String, doc: org.jsoup.nodes.Document) {
        try {
            val f = coverFile(context, slug)
            if (f.exists()) return
            val url = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifEmpty { null }
                ?: doc.selectFirst(".book img")?.absUrl("src")?.ifEmpty { null }
                ?: return
            client.newCall(
                Request.Builder().url(url).header("User-Agent", UA).build(),
            ).execute().use { r ->
                if (!r.isSuccessful) return
                val bytes = r.body?.bytes() ?: return
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
            }
        } catch (e: Exception) {}
    }

    class SiteStatus(
        val total: Int,
        val completed: Boolean,
        val author: String?,
        val orderedFilenames: List<String>,   // site listing order, engine filenames
    )

    /* Status probe for the List Novels screen: list the novel's chapters the
       same way run() would and return the site chapter count, finished flag,
       author, and the chapters' filenames in the SITE's order — or null if
       the URL doesn't load as a novel page. */
    suspend fun checkStatus(novelUrl: String, folderKey: String): SiteStatus? = withContext(Dispatchers.IO) {
        val site = Sites.forUrl(novelUrl) ?: return@withContext null
        val (base, slug) = site.normalize(novelUrl)
        val first = fetch(base)
        if (first.html == null) return@withContext null
        val doc = Jsoup.parse(first.html, base)
        saveCover(slug, doc)
        val seen = LinkedHashMap<String, Chapter>()   // discovery (= site) order
        var listingComplete = true
        /* Collect chapter links from the REAL chapter list only — pages also
           carry a "latest chapters" widget whose links come first in the HTML
           and would corrupt the site order. Whole-document fallback when the
           scoped container is missing or yields nothing new. */
        fun addLinks(d: org.jsoup.nodes.Document) {
            /* Returns how many chapter links the container HOLDS, not how many
               were new. A listing page whose chapters were all seen already is
               still a real chapter list — counting only new ones made it look
               empty and sent us to the whole-document fallback, which is
               exactly where the "latest chapters" widget lives.

               `inList` reads links from the site's own chapter list, where
               being in that container is what identifies a chapter; the
               whole-document fallback has no such evidence and falls back to
               the stricter URL test. */
            fun collect(root: org.jsoup.nodes.Element, inList: Boolean): Int {
                var found = 0
                for (a in root.select("a[href]")) {
                    val href = a.absUrl("href").substringBefore('#')
                    if (href.isEmpty()) continue
                    val path = try { java.net.URI(href).path ?: "" } catch (e: Exception) { continue }
                    val isChapter =
                        if (inList) site.isChapterInList(path, slug)
                        else site.isChapterPath(path, slug)
                    if (!isChapter) continue
                    found++
                    if (!seen.containsKey(href)) seen[href] = Chapter(href, a.text().trim())
                }
                return found
            }
            val scope = d.selectFirst(site.listScope)
            if (scope == null || collect(scope, inList = true) == 0) collect(d, inList = false)
        }
        addLinks(doc)
        var last = site.maxPage(doc, slug)
        var fetched = 1
        while (fetched < last) {
            val batch = ((fetched + 1)..last).toList()
            val htmls = arrayOfNulls<String>(batch.size)
            coroutineScope {
                val sem = Semaphore(10)
                for ((i, p) in batch.withIndex()) {
                    launch { sem.withPermit { htmls[i] = fetch(site.listPageUrl(base, slug, p)).html } }
                }
            }
            for (html in htmls) {
                if (html == null) { listingComplete = false; continue }
                val d = Jsoup.parse(html, base)
                addLinks(d)
                last = maxOf(last, site.maxPage(d, slug))
            }
            fetched = batch.last()
        }
        if (seen.isEmpty()) return@withContext null
        /* A status check renames and deletes but never downloads, so nothing
           here self-corrects. Acting on a listing with a page missing would
           renumber the library against a short list and delete the chapters
           the gap hid. Report nothing rather than something wrong. */
        if (!listingComplete) {
            log("! ${'$'}slug: a listing page failed to load — skipping this novel rather than acting on a partial list")
            return@withContext null
        }
        val siteOrdered = seen.values.toList()
        for (ch in siteOrdered) ch.num = site.chapterNumFromUrl(ch.url) ?: Extractor.parseHeading(ch.text).first
        numberByPosition(siteOrdered)
        /* Names follow the page, exactly as a download would set them. */
        for ((i, ch) in siteOrdered.withIndex()) ch.filename = "Chapter ${i + 1}.txt"
        /* ...and the files are brought into line here too, so a library is
           tidied by a status check without having to re-download every
           novel to get it. Both passes leave early when there's nothing to
           do, which is the normal case for most novels in a sweep. */
        val store = DownloadStore(context)
        val treeUri = Uri.parse(folderKey)
        val dir = novelDir(treeUri, store, folderKey, slug, doc)
        if (dir != null) {
            renameToListingOrder(treeUri, dir, store, folderKey, slug, siteOrdered)
            dedupeExtras(
                treeUri, dir, store, folderKey, slug,
                siteOrdered.mapNotNull { it.filename }.toSet(),
                siteOrdered.map { it.url }.toSet(),
            )
        }
        SiteStatus(
            siteOrdered.size, site.isCompleted(doc), Sites.author(doc),
            siteOrdered.mapNotNull { it.filename },
        )
    }

    suspend fun run(
        novelUrl: String,
        treeUri: Uri,
        translate: Boolean,
        apiKey: String,
        forceTranslate: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val site = Sites.forUrl(novelUrl)
        if (site == null) {
            log("Unsupported URL: $novelUrl")
            return@withContext
        }
        val (base, slug) = site.normalize(novelUrl)

        status("Listing chapters…")
        val first = fetch(base)
        if (first.html == null) {
            if (first.status == 404 || first.status == 410) {
                log("HTTP ${first.status} — this novel isn't on the site. Check the URL (it may only be hosted elsewhere).")
                status("Error: novel not found on this site (HTTP ${first.status})")
            } else {
                log("Could not load $base (HTTP ${first.status})")
                status("Error: could not load the novel page")
            }
            return@withContext
        }
        val doc = Jsoup.parse(first.html, base)
        /* language of the SOURCE, judged from the novel page's own text */
        val sourceEnglish = looksEnglish(doc.text())
        val author = Sites.author(doc)
        /* sites often append "- {author}" to the title — folder names and the
           novels list carry the bare title, the author is stored separately */
        val title = Extractor.stripAuthor(
            doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifEmpty { null }
                ?: doc.selectFirst("h3.title")?.text()?.trim()?.ifEmpty { null }
                ?: doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null }
                ?: slug,
            author,
        )

        val seen = LinkedHashMap<String, Chapter>()
        /* Collect chapter links from the REAL chapter list only — pages also
           carry a "latest chapters" widget whose links come first in the HTML
           and would corrupt the site order. Whole-document fallback when the
           scoped container is missing or yields nothing new. */
        /* Returns how many chapter links this page held, so the caller can
           tell a real listing page from one that answered 200 with nothing on
           it — a soft 404, a WAF interstitial, a layout change. Those never
           entered `missed`, so the listing looked complete while a page of
           chapters was simply absent, and the dedupe then deleted their files
           as chapters the site no longer lists. */
        fun addLinks(d: org.jsoup.nodes.Document): Int {
            /* Counts how many chapter links the container HOLDS, not how many
               were new. A listing page whose chapters were all seen already is
               still a real chapter list — counting only new ones made it look
               empty and sent us to the whole-document fallback, which is
               exactly where the "latest chapters" widget lives.

               `inList` reads links from the site's own chapter list, where
               being in that container is what identifies a chapter; the
               whole-document fallback has no such evidence and falls back to
               the stricter URL test. */
            fun collect(root: org.jsoup.nodes.Element, inList: Boolean): Int {
                var found = 0
                for (a in root.select("a[href]")) {
                    val href = a.absUrl("href").substringBefore('#')
                    if (href.isEmpty()) continue
                    val path = try { java.net.URI(href).path ?: "" } catch (e: Exception) { continue }
                    val isChapter =
                        if (inList) site.isChapterInList(path, slug)
                        else site.isChapterPath(path, slug)
                    if (!isChapter) continue
                    found++
                    if (!seen.containsKey(href)) seen[href] = Chapter(href, a.text().trim())
                }
                return found
            }
            val scope = d.selectFirst(site.listScope)
            val inScope = if (scope == null) 0 else collect(scope, inList = true)
            return if (inScope == 0) collect(d, inList = false) else inScope
        }
        addLinks(doc)
        var last = site.maxPage(doc, slug)
        /* fetch all remaining listing pages in parallel (Semaphore-capped);
           parse in page order so chapter discovery stays deterministic. A
           later page can raise the page count, so loop until none are left. */
        /* Every destructive decision below — which files are surplus, where
           each chapter belongs — reads this listing as the whole truth. A
           page we failed to fetch would make it a LIE by omission: chapters
           beyond the gap look unlisted, and identity-based removal deletes
           them outright. Track it, and refuse to act on a partial answer. */
        /* Hold each page's HTML rather than parsing as it lands: a page that
           has to be re-fetched must still be read IN PAGE ORDER, because
           discovery order is the site's order and the site's order is what
           names every file. Parsing a retried page last would move its
           chapters to the end of the book. */
        val pageHtml = HashMap<Int, String>()
        val missed = LinkedHashSet<Int>()      // pages that wouldn't load
        val gone = HashSet<Int>()              // ...of those, the ones that said "not there"
        var fetched = 1
        while (fetched < last && !stopRequested) {
            val batch = ((fetched + 1)..last).toList()
            status("Listing chapters: pages ${batch.first()}-${batch.last()} of $last…")
            val htmls = arrayOfNulls<String>(batch.size)
            val codes = IntArray(batch.size)
            coroutineScope {
                val sem = Semaphore(conc)
                for ((i, p) in batch.withIndex()) {
                    launch {
                        sem.withPermit {
                            if (!stopRequested) {
                                val r = fetch(site.listPageUrl(base, slug, p))
                                htmls[i] = r.html
                                codes[i] = r.status
                            }
                        }
                    }
                }
            }
            for ((i, html) in htmls.withIndex()) {
                val p = batch[i]
                if (html == null) {
                    /* "Gone" only excuses a page past the END of the list,
                       where the page count over-read the pagination and there
                       was never anything to miss. A 404 in the MIDDLE is a
                       hole like any other, and treating it as nothing left the
                       listing looking complete while ~50 chapters were absent
                       — enough for the rename pass to renumber the novel
                       around them and the dedupe to delete them as chapters
                       the site no longer lists. Record it; the check below
                       forgives it only once we know nothing real followed. */
                    missed.add(p)
                    if (codes[i] == 404 || codes[i] == 410) gone.add(p)
                    continue
                }
                pageHtml[p] = html
                last = maxOf(last, site.maxPage(Jsoup.parse(html, base), slug))
            }
            fetched = batch.last()
        }
        /* Forgiving a page that says "not there" is how an over-read page
           count stops blocking a novel — but only just barely, because
           everything forgiven is a page whose chapters we then treat as never
           having existed, and the dedupe deletes their files. Three
           conditions, all necessary:
             - nothing real after it, so it can only be past the end;
             - a contiguous run back to the last page that did load, so a 404
               island inside the book is never swallowed;
             - and few enough to be a miscount. A tail of eleven 404s on a
               ninety-page novel is not the pagination being off by one, it is
               550 chapters going quiet — and forgiving that wholesale deleted
               every one of their files. That also covers the case where NOTHING
               past page 1 loads: the whole tail is "gone", far too much to
               excuse, so the listing is short rather than falsely complete. */
        fun forgiveOverRead() {
            val lastReal = pageHtml.keys.maxOrNull() ?: 1
            val forgivable = missed.filter { p ->
                p in gone && p > lastReal && ((lastReal + 1) until p).all { it in gone }
            }
            if (forgivable.isEmpty()) return
            if (forgivable.size <= maxOf(1, last / 20)) {
                missed.removeAll(forgivable.toSet())
            } else {
                log("! ${forgivable.size} listing pages at the end all report missing — treating that as a gap, not a miscount")
            }
        }
        /* Forgiving a page removes it from the retry list, so forgiving before
           the retries meant a page that 404s ONCE was never asked again — and
           a real last page that blips 404 had its fifty chapters deleted as
           "no longer listed". But retrying a page that has never existed cost
           three passes and 21 seconds on every download of a novel whose page
           count over-reads by one. Ask once more, immediately and without the
           wait: that is cheap enough to spend on a phantom page and enough to
           unmask a transient one. Only what is still missing afterwards can
           be forgiven. */
        val tailSuspects = missed.filter { p ->
            p in gone && p > (pageHtml.keys.maxOrNull() ?: 1)
        }
        if (tailSuspects.isNotEmpty() && !stopRequested) {
            status("Confirming ${tailSuspects.size} listing page(s) that report missing…")
            val htmls = arrayOfNulls<String>(tailSuspects.size)
            coroutineScope {
                val sem = Semaphore(conc)
                for ((i, p) in tailSuspects.withIndex()) {
                    launch {
                        sem.withPermit {
                            if (!stopRequested) htmls[i] = fetch(site.listPageUrl(base, slug, p)).html
                        }
                    }
                }
            }
            for ((i, html) in htmls.withIndex()) {
                if (html == null) continue
                /* it was there after all — a real page, not an over-read */
                val p = tailSuspects[i]
                pageHtml[p] = html
                gone.remove(p)
                missed.remove(p)
                last = maxOf(last, site.maxPage(Jsoup.parse(html, base), slug))
            }
        }
        forgiveOverRead()

        /* A page we couldn't read doesn't just hide its own chapters: every
           chapter after it moves up a position, and position is the filename.
           Retry the holes before drawing any conclusion from the listing. */
        var listPass = 0
        while (missed.isNotEmpty() && listPass < 3 && !stopRequested) {
            listPass++
            /* Wait between passes. Most of what lands here is throttling, and
               three immediate re-fetches are three more refusals — the whole
               novel then failed over one busy moment. */
            var waited = 0
            while (waited < LIST_RETRY_MS && !stopRequested) {
                status("Listing incomplete — retrying ${missed.size} page(s) in ${(LIST_RETRY_MS - waited) / 1000}s…")
                kotlinx.coroutines.delay(500)
                waited += 500
            }
            if (stopRequested) break
            status("Re-fetching ${missed.size} listing page(s) — pass $listPass/3…")
            val retry = missed.toList()
            val htmls = arrayOfNulls<String>(retry.size)
            val codes = IntArray(retry.size)
            coroutineScope {
                val sem = Semaphore(conc)
                for ((i, p) in retry.withIndex()) {
                    launch {
                        sem.withPermit {
                            if (!stopRequested) {
                                val r = fetch(site.listPageUrl(base, slug, p))
                                htmls[i] = r.html
                                codes[i] = r.status
                            }
                        }
                    }
                }
            }
            for ((i, html) in htmls.withIndex()) {
                if (html == null) {
                    if (codes[i] == 404 || codes[i] == 410) gone.add(retry[i])
                    continue
                }
                pageHtml[retry[i]] = html
                gone.remove(retry[i])
                missed.remove(retry[i])
            }
        }
        if (stopRequested) { status("Stopped."); return@withContext }
        forgiveOverRead()
        var listingComplete = missed.isEmpty()
        /* the highest page that actually came back, so "no chapters on it" can
           be told apart from "there is simply nothing after here" */
        val lastLoaded = pageHtml.keys.maxOrNull() ?: 1
        /* A hole doesn't invalidate the whole listing — only what comes AFTER
           it. Chapters discovered before the first missing page are still at
           the positions they belong to, so take that prefix and leave the rest
           for a run that can read the whole thing. Refusing outright meant one
           throttled page out of ninety downloaded nothing at all; naming from
           the full set would have written every later chapter over its
           neighbour. Everything destructive is already gated on
           listingComplete, so a prefix run only ever adds. */
        var firstGap = missed.minOrNull() ?: (last + 1)
        /* A page can fail without failing: HTTP 200 carrying a "not found"
           body, a WAF interstitial, or a layout the selectors no longer match.
           Those never reached `missed`, so the listing read as COMPLETE while
           a page of chapters was simply absent — and the dedupe then deleted
           those chapters' files as ones the site no longer lists. A real
           listing page holds chapter links; one that holds none, with real
           pages after it, is a hole whatever status it returned. */
        for (p in 2 until firstGap) {
            val html = pageHtml[p] ?: continue
            if (addLinks(Jsoup.parse(html, base)) == 0 && p < lastLoaded) {
                log("! listing page $p came back with no chapters on it — treating it as a gap")
                missed.add(p)
                listingComplete = false
                firstGap = p
                break
            }
        }
        if (!listingComplete) {
            log("! ${missed.size} listing page(s) would not load — the chapter list is incomplete")
            log(
                "Downloading only the ${seen.size} chapter(s) listed before page $firstGap: " +
                    "past the gap every position would be wrong. Re-run later for the rest.",
            )
        }

        val store = DownloadStore(context)
        val folderKey = treeUri.toString()
        val chapters = seen.values.toMutableList()
        /* the site's exact order (listing-page sequence): this is where each
           chapter belongs. The filename is only its identity on disk. */
        val siteOrdered = chapters.toList()
        /* the site's own number, still wanted for the heading inside the file */
        for (ch in chapters) ch.num = site.chapterNumFromUrl(ch.url) ?: Extractor.parseHeading(ch.text).first
        numberByPosition(siteOrdered)
        for ((i, ch) in siteOrdered.withIndex()) ch.filename = "Chapter ${i + 1}.txt"
        if (chapters.isEmpty()) {
            log("No chapters found — site layout may have changed")
            status("Error: no chapters found")
            return@withContext
        }
        log("$title — ${chapters.size} chapters")

        val root = DocumentFile.fromTreeUri(context, treeUri)
        if (root == null) {
            log("Saved folder is unavailable — pick it again")
            status("Error: folder unavailable")
            return@withContext
        }
        /* register for the List Novels screen (first run keeps its timestamp;
           last_dl always bumps so the list sorts newest download first) */
        store.registerNovel(folderKey, slug, base, title, System.currentTimeMillis())
        store.touchNovel(folderKey, slug, System.currentTimeMillis())
        author?.let { store.setAuthor(folderKey, slug, it) }
        saveCover(slug, doc)
        /* Index the site's chapter order for the reader — but only from a
           listing we read in full. A prefix would replace a 4500-chapter
           order with the 500 we managed to see, and the reader sorts by
           exactly this, so the rest of the novel would fall out of order. */
        if (listingComplete) {
            store.setChapterOrder(folderKey, slug, siteOrdered.mapNotNull { it.filename })
        }

        /* When translating, render the English folder name up front (Sonnet,
           Batches API) so chapters save straight into an "English (Vietnamese)"
           folder. If a plain Vietnamese-named folder already exists from an
           earlier non-translated run, rename it in place instead of starting
           a fresh one. */
        val vietName = Extractor.folderName(title, slug)
        var folderName = vietName
        if (translate && apiKey.isNotBlank() && !stopRequested) {
            status("Translating title…")
            val t = translator ?: Translator(context, apiKey, log, status).also { translator = it }
            val english = try {
                t.ensureEnglishTitle(title, store, folderKey, slug) { stopRequested }
            } catch (e: Exception) { log("Title translation failed — ${e.message}"); null }
            if (!english.isNullOrBlank()) {
                folderName = english
                if (english != vietName) {
                    val existingEng = root.findFile(english)?.takeIf { it.isDirectory }
                    val existingViet = root.findFile(vietName)?.takeIf { it.isDirectory }
                    if (existingEng == null && existingViet != null) {
                        val ok = try { existingViet.renameTo(english) } catch (e: Exception) { false }
                        if (ok) {
                            /* the folder moved, so the cached URIs are stale —
                               but each file is still the chapter it was */
                            store.clearUris(folderKey, slug)
                            /* Record the move NOW. The ownership check below
                               asks which directory this novel uses; left
                               saying the Vietnamese name, it saw a name that
                               no longer exists (we just renamed it), decided
                               the English folder was somebody else's because
                               it was full, and pushed this novel into an empty
                               "Title (slug)" beside its own chapters — then
                               re-downloaded the novel and re-translated it. */
                            try { store.setDirName(folderKey, slug, english) } catch (e: Exception) {}
                            try { store.claimFolderName(folderKey, english, slug) } catch (e: Exception) {}
                            log("Renamed existing folder to \"$english\"")
                        } else {
                            /* The chapters are still in the Vietnamese folder,
                               so stay with it. Going ahead under the English
                               name built an empty folder beside a full one:
                               the index still resolved into the old folder, so
                               the spot-check passed and nothing re-downloaded,
                               while the translator saw an empty translated/
                               and re-sent the entire novel to the API. */
                            folderName = vietName
                            log("Could not rename the folder — keeping \"$vietName\"")
                        }
                    }
                }
            }
        }

        /* Sanitising folds tone marks away, so "Thần Y" and "Thân Y" — two
           different novels — both land on "Than Y". Reusing a folder by name
           alone let the second one write Chapter 1..N straight over the
           first's, and the reader then showed the wrong book's text. Names are
           owned by the slug that claimed them; a different owner means make a
           new folder rather than move in. */
        val owner = try { store.slugOwningName(folderKey, folderName) } catch (e: Exception) { null }
        /* The directory this novel actually used last time, if we know it.
           This is the only reliable answer: "has this slug got chapters" is
           true of every novel on its second run and says nothing about WHICH
           folder they are in, so it let a novel that had been pushed onto a
           suffixed name walk straight back into the folder it was pushed out
           of — and then rename and write over that novel's chapters.

           A library older than this column has no recorded name; there,
           having chapters plus an unclaimed folder still means it is ours,
           which is what keeps an existing library where it is. */
        val recordedDir = try { store.dirNameFor(folderKey, slug) } catch (e: Exception) { null }
        val legacyMine = recordedDir == null && owner == null &&
            try { store.chapterCount(folderKey, slug) > 0 } catch (e: Exception) { false }
        val ours = owner == slug || recordedDir == folderName || legacyMine
        /* We own a folder under a different name — a suffix from a past
           collision, or the name before a translated rename. Keep using it
           rather than recomputing our way back into somebody else's. */
        if (!ours && recordedDir != null && root.findFile(recordedDir)?.isDirectory == true) {
            folderName = recordedDir
        } else if (!ours) {
            /* Unclaimed but already full is somebody else's work — the first
               of two colliding novels to run must not be able to claim, and
               then be evicted from, the folder it has been living in. */
            val existingDir = root.findFile(folderName)?.takeIf { it.isDirectory }
            val occupied = owner != null ||
                (existingDir != null && try { existingDir.listFiles().isNotEmpty() } catch (e: Exception) { false })
            if (occupied) {
                folderName = Extractor.sanitize("$folderName ($slug)").ifEmpty { slug }
                log("Another novel already uses that folder name — saving to \"$folderName\"")
            }
        }
        val dir = root.findFile(folderName)?.takeIf { it.isDirectory }
            ?: root.createDirectory(folderName)
        if (dir == null) {
            log("Could not create folder \"$folderName\"")
            status("Error: could not create the novel folder")
            return@withContext
        }
        /* Claim the name, so the next novel that sanitises to the same thing
           is told to go elsewhere instead of writing over this one. Its own
           table — this used to write `titles`, which is the TRANSLATED-title
           cache, so an untranslated run pointed the library at an empty
           folder and threw away a paid title translation. */
        try { store.claimFolderName(folderKey, folderName, slug) } catch (e: Exception) {}
        /* ...and record it against the novel, so the next run resolves to this
           directory instead of recomputing a name that may be another
           novel's. `registerNovel` above created the row. */
        try { store.setDirName(folderKey, slug, folderName) } catch (e: Exception) {}
        val assigned = siteOrdered.mapNotNull { it.filename }.toSet()
        if (listingComplete) renameToListingOrder(treeUri, dir, store, folderKey, slug, siteOrdered)
        /* Files we can neither place nor prove are copies. Rather than leave
           the novel in a state nobody can explain, fetch every chapter again
           and let the download settle it: afterwards each listed chapter is
           on disk under its own name, and whatever is left over is surplus.
           The index is dropped with it — a wrong index is one reason a file
           goes unrecognised in the first place — and rebuilt from the saves. */
        val listedUrls = siteOrdered.map { it.url }.toSet()
        val unexplained =
            if (listingComplete) dedupeExtras(treeUri, dir, store, folderKey, slug, assigned, listedUrls)
            else 0
        /* Re-fetching a whole novel is the last resort, not the repair. Every
           file we can identify is settled by its recorded page — kept,
           dropped, or matched — and a chapter that turns out to be missing is
           simply one of the chapters this run fetches anyway. Only a novel
           with no recorded identity at all has nothing to reconcile against,
           and there the fetch is the only way to establish what's real. */
        val refetchAll = unexplained > 0 && store.fileUrls(folderKey, slug).isEmpty()
        if (refetchAll) {
            log("$unexplained file(s) and nothing on record to identify them — fetching the novel again")
            try { store.clear(folderKey, slug) } catch (e: Exception) {}
        }
        log("Saving to: $folderName/")

        status("Checking already-downloaded chapters…")
        val existing = HashSet<String>()   // filenames known present on disk

        val cached = store.get(folderKey, slug)   // filename -> document URI
        var usedCache = false
        if (cached.isNotEmpty()) {
            /* O(1) spot-check: does a sample indexed file still exist? Catches
               a deleted/moved folder without listing the whole directory.
               A blank URI is not a missing file — it's a location we
               deliberately dropped after the folder moved — so sample a row
               that actually has one, and fall through to the re-listing when
               none does. */
            val sampleUri = cached.values.firstOrNull { it.isNotEmpty() }
            val ok = sampleUri != null && try {
                DocumentFile.fromSingleUri(context, Uri.parse(sampleUri))?.exists() == true
            } catch (e: Exception) { false }
            if (ok) {
                existing.addAll(cached.keys)
                usedCache = true
            } else {
                /* Only the locations are wrong. Which chapter each file IS is
                   still recorded, and nothing on disk can rebuild that — so
                   drop the URIs and let the re-listing below refresh them.
                   clear() here undid the folder rename's care on the very
                   next run. */
                store.clearUris(folderKey, slug)
                log("Saved-chapter locations were stale (folder moved) — re-listing.")
            }
        }
        if (!usedCache) {
            /* fallback: one folder listing (verify non-empty — files may have
               been copied in from elsewhere), then rebuild the index */
            val rows = ArrayList<Pair<String, String>>()
            for (f in dir.listFiles()) {
                val n = f.name ?: continue
                if (Zips.isGzName(n)) {
                    /* A compressed chapter IS that chapter — index it under
                       its plain name against the .gz document. Counting it as
                       present without recording it left the row with no
                       location, and the translator, which reaches chapters
                       through the index, then found nothing to translate and
                       reported the novel already done. Chapters are written
                       gzipped now, so this was every chapter. */
                    val base = n.removeSuffix(".gz")
                    /* An empty .gz is a chapter that never finished writing.
                       Counting it as present kept it out of every later fetch,
                       so it stayed broken for good — the loose branch has
                       always guarded on length; this one didn't. */
                    if (f.length() > 0) {
                        existing.add(base)
                        rows.add(base to f.uri.toString())
                    }
                    continue
                }
                if (f.length() > 0) { existing.add(n); rows.add(n to f.uri.toString()) }
            }
            store.addAll(folderKey, slug, rows)
        }

        /* the same preference the post-download pass reads — taken once here
           so each chapter is written in its final form rather than rewritten */
        val compressOn = context.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
            .let { it.getBoolean("compressNovels", it.getBoolean("zipDownloads", true)) }

        /* Sitting at a chapter's number is not the same as being that chapter.
           When a rename couldn't be applied — its target held by a file
           nothing would move — the old occupant is still at that number, and
           skipping the fetch because the name looks taken would serve its
           text as this chapter for good. Go by the page each file was
           downloaded from; fall back to the name only for files saved before
           pages were recorded. */
        val ownerOf = HashMap<String, String>()        // filename -> the page it holds
        for ((u, f) in store.urlMap(folderKey, slug)) ownerOf[f] = u
        val stale = HashSet<String>()                  // names held by the wrong page
        fun settled(ch: Chapter): Boolean {
            val name = ch.filename ?: return false
            if (name !in existing) return false
            /* A prefix run adds, and ONLY adds. Its positions are right for
               the chapters it saw, but the files on disk were named under the
               whole listing — and the pass that reconciles the two was
               skipped, precisely because the listing is short. So a shifted
               chapter looks like it holds the wrong page, and re-fetching it
               would DELETE the file first (that is what replace does) to write
               a neighbour's text over it. That is the deletion-without-proof
               the completeness gate exists to prevent, walking in through the
               write path instead of the dedupe. Leave every existing file
               alone; a complete run puts them right. */
            if (!listingComplete) return true
            val owner = ownerOf[name] ?: return true   // unclaimed — a legacy save
            if (owner == ch.url) return true
            stale.add(name)
            return false
        }

        val toFetch =
            if (refetchAll) chapters.filter { it.filename != null }
            else chapters.filter { it.filename != null && !settled(it) }
        if (stale.isNotEmpty()) log("${stale.size} chapter file(s) hold the wrong page — re-fetching those")
        val skipped = chapters.size - toFetch.size
        if (skipped > 0) log("skip $skipped already-downloaded chapter(s)")

        val done = AtomicInteger(0)
        val saved = AtomicInteger(0)
        val inFlight = AtomicInteger(0)
        val failed = java.util.Collections.synchronizedList(mutableListOf<Chapter>())
        val total = toFetch.size
        fetchStart = System.currentTimeMillis()

        fun report() {
            if (stopRequested) {
                status("Stopping… (${inFlight.get()} left)")
            } else {
                status("${done.get()}/$total done (${saved.get()} saved, ${failed.size} failed)${liveRate()}")
            }
        }

        /* Every chapter is queued at once and streams through the shared
           dynamic pool — a request starts the moment a slot frees up, no
           batch barrier. countProgress bumps the bar only on the main pass
           (retried chapters were already counted). */
        suspend fun fetchAll(list: List<Chapter>, countProgress: Boolean) = coroutineScope {
            list.map { ch ->
                launch {
                    if (stopRequested) return@launch
                    pool.acquire()
                    var outcome: Int? = null   // null = saved, else the failure status
                    var finished = false
                    try {
                        if (stopRequested) return@launch
                        inFlight.incrementAndGet()
                        try {
                            val res = fetch(ch.url)
                            if (res.html == null) {
                                outcome = res.status
                                failed.add(ch)
                                log("FAILED ${ch.url} — HTTP ${res.status}")
                            } else {
                                val body = Extractor.parseChapter(
                                    Jsoup.parse(res.html, ch.url), ch.text, ch.num ?: 0, site.headingWord,
                                )
                                val uri = writeFile(
                                    dir, ch.filename!!, body, compressOn,
                                    replace = refetchAll || ch.filename in stale,
                                )
                                /* record the page it came from: that mapping is
                                   what keeps this file's name stable if the site
                                   later relabels or renumbers the chapter */
                                store.add(folderKey, slug, ch.filename!!, uri, ch.url)
                                saved.incrementAndGet()
                                noteSaved()
                            }
                        } catch (e: Exception) {
                            outcome = -1   // parse/write error, not a throttle signal
                            failed.add(ch)
                            log("FAILED ${ch.url} — ${e.message}")
                        } finally {
                            inFlight.decrementAndGet()
                        }
                        finished = true
                    } finally {
                        pool.release()
                    }
                    if (finished) {
                        if (countProgress) { done.incrementAndGet(); progress(done.get(), total) }
                        report()
                        recordResult(outcome)
                    }
                }
            }.forEach { it.join() }
        }

        setConc(CONC_START)
        fetchAll(toFetch, true)

        /* end-of-run retry passes over pooled failures, 7s apart */
        var pass = 0
        while (pass < 4 && failed.isNotEmpty() && !stopRequested) {
            pass++
            val toRetry = ArrayList(failed)
            failed.clear()
            status("Waiting 7s before retry pass $pass/4 (${toRetry.size} failed)…")
            var waited = 0
            while (waited < 7000 && !stopRequested) {
                kotlinx.coroutines.delay(500); waited += 500
            }
            if (stopRequested) { failed.addAll(toRetry); break }
            status("Retry pass $pass/4: ${toRetry.size} chapter(s)…")
            fetchAll(toRetry, false)
            log("Retry pass $pass/4: ${toRetry.size - failed.size} recovered, ${failed.size} still failing")
        }

        val secs = (System.currentTimeMillis() - fetchStart) / 1000.0
        val avg = if (secs > 1 && saved.get() > 0) " Avg %.1f/s.".format(saved.get() / secs) else ""
        val summary = "${saved.get()} saved, $skipped skipped, ${failed.size} failed.$avg"
        log((if (stopRequested) "Stopped — re-run to resume. " else "✓ Finished. ") + summary)

        /* record the site's chapter count + finished flag right here at
           download time (not only when the user taps Check status). Complete =
           the site says finished AND every listed chapter is on disk after
           this run (nothing failed, nothing stopped). */
        val allOnDisk = !stopRequested && failed.isEmpty()
        /* The re-fetch has just written every listed chapter under its own
           name, so what remains unclaimed is settled: not part of the novel.
           Only trust that if the fetch actually finished — a stopped or
           partly-failed run proves nothing, and the guarded pass keeps
           looking after those files instead. */
        if (refetchAll && allOnDisk && listingComplete) {
            val gone = purgeUnreferenced(treeUri, dir, store, folderKey, slug, assigned)
            if (gone > 0) log("removed $gone file(s) the novel no longer contains")
        }
        try {
            /* a count taken from a partial listing would mark the novel
               complete at the wrong total */
            if (listingComplete) {
                store.updateNovelCheck(folderKey, slug, chapters.size, site.isCompleted(doc) && allOnDisk)
            }
            /* ...and the authoritative on-disk count. The Library can't derive
               it for a compressed novel — the chapters index only tracks loose
               files, and the folder scan is one-time — so without this the row
               keeps showing a stale count after a download. We know it exactly
               here: the chapters that were already present plus what we saved. */
            /* Not from a prefix either: "the chapters already present plus
               what we saved" counts only the ones this run knew about, so a
               500-of-4500 run would report the novel as having 500. */
            if (listingComplete) store.setDiskCount(
                folderKey, slug,
                if (refetchAll) {
                    saved.get()
                } else {
                    /* A stale name is in `existing` (it was on disk) AND was
                       re-fetched into `saved` — counting both made the Library
                       read 103/100 and kept the novel in every future sweep,
                       since its skip test wants local == total. */
                    chapters.count {
                        it.filename != null && it.filename in existing && it.filename !in stale
                    } + saved.get()
                },
            )
        } catch (e: Exception) {}

        if (translate && sourceEnglish && !forceTranslate) {
            log("Source is already in English — skipping translation.")
        } else if (translate && apiKey.isNotBlank() && !stopRequested) {
            try {
                val t = translator ?: Translator(context, apiKey, log, status).also { translator = it }
                t.translate(dir, store, folderKey, slug, chapters.mapNotNull { it.filename }) { stopRequested }
            } catch (e: Exception) {
                log("TRANSLATION FAILED — ${e.message}")
            }
        }
        /* "Compress my novels" on → gzip this novel's chapters after download */
        if (!stopRequested &&
            context.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
                .let { it.getBoolean("compressNovels", it.getBoolean("zipDownloads", true)) }
        ) {
            status("Compressing chapters…")
            try {
                val docId = DocumentsContract.getDocumentId(dir.uri)
                if (Zips.compressDir(context, context.contentResolver, treeUri, Saf.Entry(docId, folderName, true))) {
                    log("Chapters compressed")
                }
            } catch (e: Exception) {
                log("Compress failed — ${e.message}")
            }
        }
        status((if (stopRequested) "Stopped: " else "Done: ") + summary)
    }

    /* already English? more than half the characters are English-alphabet
       letters (Vietnamese's diacritics push its ratio below that). */
    private fun looksEnglish(text: String): Boolean {
        if (text.isEmpty()) return false
        var eng = 0
        for (c in text) if (c in 'a'..'z' || c in 'A'..'Z') eng++
        return eng.toDouble() / text.length > 0.5
    }

    /* With compression on, write the chapter gzipped in the first place
       rather than saving it plain for the post-download pass to read back,
       rewrite and delete — three times the I/O for the same file. Falls
       back to a plain write if the provider won't take the .gz, and that
       pass still catches anything left loose. */
    private fun writeFile(dir: DocumentFile, name: String, text: String, compress: Boolean, replace: Boolean = false): String {
        /* SAF does not overwrite: creating a name that already exists yields
           "Chapter 5 (1).txt", which matches no pattern the app knows — the
           reader can't see it and the surplus sweep would delete the copy we
           just fetched. Clear the old document first when we know one is
           there. (Only then: findFile is a query per call, and the normal
           path never collides.) */
        if (replace) {
            try { dir.findFile(name)?.delete() } catch (e: Exception) {}
            try { dir.findFile("$name.gz")?.delete() } catch (e: Exception) {}
        }
        if (compress) {
            Zips.writeGzDoc(context.contentResolver, dir.uri, "$name.gz", text)
                ?.let { return it.toString() }
        }
        val f = dir.createFile("text/plain", name)
            ?: throw RuntimeException("could not create $name")
        val out = context.contentResolver.openOutputStream(f.uri)
            ?: throw RuntimeException("could not open $name")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return f.uri.toString()
    }
}
