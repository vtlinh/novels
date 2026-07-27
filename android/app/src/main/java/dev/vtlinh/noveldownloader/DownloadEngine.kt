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
        /* where a chapter waits while a rename cycle is broken around it */
        private const val PARK = "Chapter __shift"

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
        /* The decision itself lives in Renumber.plan, away from the I/O, so it
           can be tested directly — this is the logic that decides which file
           becomes which chapter, and reasoning about it in place is what kept
           getting it wrong. */
        val plan = Renumber.plan(
            inSiteOrder.map { Renumber.Slot(it.url, it.filename) },
            byUrl,
            onRecord.keys,
            legacy,
        )
        val pending = plan.pending
        val claimedUrl = plan.claimedUrl
        /* files already in place whose page simply wasn't recorded yet */
        for ((name, url) in plan.linkNow) store.linkUrl(folderKey, slug, name, url)
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
        /* Set when a translation moved on its own — its source was named by
           the index but is not on disk (the index is a cache, and a
           translation whose source is gone still sits in translated/ under
           its old name). The move itself is right: that English belongs to
           the chapter now at `to`, and the missing source is re-fetched. But
           nothing else on this path runs — renameChapter, and with it
           clearChapterList, is skipped — so the CACHED listing kept pointing
           at the translation's old document id and the reader showed no
           English for that chapter until something else invalidated it.

           Deliberately not folded into `moved`: that would call
           renameChapter for a file that is not on disk, leaving the index
           claiming a document that does not exist, and would add an `applied`
           entry that remapSavedSpot would use to walk the reading mark onto a
           name with no file behind it. */
        var movedTranslationOnly = false

        fun move(from: String, to: String): Boolean {
            var moved = false
            var movedTr = false
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
                        movedTr = true
                    }
                }
            }
            if (movedTr && !moved) movedTranslationOnly = true
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
            /* `parked` restarts at 0 on every pass, so this name can be one a
               previous run stranded here. Renaming onto a taken name does not
               fail — SAF mints one — while the index write first DELETES the
               row holding that file's page, which is the one thing the file
               itself cannot rebuild. Take a free name. */
            var park = "$PARK$parked.txt"
            while (occupied(park) && parked < inSiteOrder.size + 8) {
                parked++
                park = "$PARK$parked.txt"
            }
            parked++
            move(e.key, park)
            pending.remove(e.key)
            pending[park] = e.value
            if (parked > inSiteOrder.size) break     // safety valve
        }
        /* Anything still parked when the loop gives up — Stop, the safety
           valve, a move the provider refused — is a real chapter sitting under
           a name that matches NOTHING: not the reader's pattern, not the
           dedupe's, not the half-written sweep's. Its row in the index still
           says it is that chapter, so nothing re-fetches it either: the
           chapter simply disappears from the novel. The park name is meant to
           live for one pass; put anything left back where the app can see it. */
        for ((key, want) in pending.entries.toList()) {
            if (!key.startsWith(PARK)) continue
            if (!occupied(want) && move(key, want)) { renamed++; continue }
            val stem = want.removeSuffix(".txt")
            var aside = "$stem (unlisted).txt"
            var n = 2
            while (occupied(aside) && n < 100) { aside = "$stem (unlisted $n).txt"; n++ }
            if (move(key, aside)) evicted++
        }
        /* A file matched by guesswork has now proved where it went, so give it
           its page and it never has to be guessed at again. */
        for ((orig, finalName) in applied) {
            claimedUrl[orig]?.let { store.linkUrl(folderKey, slug, finalName, it) }
        }
        remapSavedSpot(slug, applied)
        if (applied.isNotEmpty()) bumpRenameEpoch(slug)
        /* ...and a pass whose ONLY effect was moving translations still
           changed the listing, so the cache of it has to go. */
        else if (movedTranslationOnly) {
            try { store.clearChapterList(folderKey, slug) } catch (e: Exception) {}
        }
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
        /* the page's own title is a SITE question — which element carries it
           differs per site, and the shared guess was one of the selector lists
           that leaked out of the adapters */
        site: Site,
    ): DocumentFile? = try {
        /* The recorded directory first. Rebuilding the name from the title
           gives the UNSUFFIXED one, so for a novel that was pushed off a
           colliding name this resolved to the other novel's folder — and
           Check status then renamed and deduped that novel's files against
           this one's listing. */
        val name = store.dirNameFor(folderKey, slug)
            ?: store.getTitle(folderKey, slug)
            ?: Extractor.sanitize(
                Extractor.stripAuthor(site.title(doc) ?: slug, site.author(doc)),
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
        /* Half-written files from a run the system killed. They are invisible
           to everything else by design, and the compress pass — the only other
           thing that clears them — never runs at all with compression off, so
           they piled up in the folder for good. We are listing it anyway. */
        val halfWritten = ArrayList<String>()
        for (e in Saf.children(cr, treeUri, dirId)) {
            if (e.isDir) { if (e.name == "translated") translatedId = e.docId; continue }
            if (Zips.isPartName(e.name)) { halfWritten.add(e.docId); continue }
            val base = e.name.removeSuffix(".gz")
            if (re.matches(base)) chapterFiles.add(OnDisk(e.name, base, e.docId, e.size))
        }
        for (docId in halfWritten) {
            try {
                DocumentsContract.deleteDocument(
                    cr, DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                )
            } catch (e: Exception) {}
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
                    /* by BASE name: the source and its translation compress
                       independently, so "Chapter 900.txt.gz" beside a loose
                       "translated/Chapter 900.txt" matched nothing here and
                       left the translation orphaned — a file the reader hides,
                       the sweeps ignore, and the next chapter to take that
                       name inherits. purgeUnreferenced already keys on the
                       base; this is the same rule. */
                    if (!t.isDir && t.name.removeSuffix(".gz") == x.base) {
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
        /* "The site no longer lists this chapter" can only be read off a
           listing from the SAME site. These novels are served from more than
           one host — truyenfull.today and .live carry the same books — and the
           status sweep will happily probe the other one when the recorded host
           blips. Every recorded page then names a host the listing doesn't
           use, so every file looks removed, and for a novel with few enough
           chapters the count guard below waves it through: the whole novel
           deleted because one host was briefly down. A page we can't compare
           is unidentified, not removed. */
        val listedHosts = listedUrls.mapNotNullTo(HashSet()) { hostOf(it) }
        fun comparable(url: String) = hostOf(url).let { it != null && it in listedHosts }
        val unlisted = extras.count {
            val u = fileUrl[it.base]; u != null && comparable(u) && u !in listedUrls
        }
        val identified = fileUrl.size
        /* Half, not a fifth. A listing that is actually broken loses nearly
           everything — the widget-only fallback finds ten links out of seven
           thousand — while a site genuinely purging chapters takes a slice. At
           a fifth, a real purge of 15 from a 60-chapter novel tripped the
           guard, and the arithmetic is identical on every later run, so those
           files were parked as "(unlisted)" for good while the log promised to
           reconsider "until it reads correctly".

           PROPORTION ONLY. An absolute floor of ten sat in front of this as an
           `||`, so up to ten chapters were deleted on identity alone however
           small the novel and however obviously broken the listing: a site
           that drops its chapter-list container falls back to the "latest
           chapters" widget, five links, and a twelve-chapter novel lost seven
           chapters and their paid translations in one sweep. The proportional
           rule already tolerates a real purge at any size — two dropped from
           twelve is 4 <= 12 — and it is the one that notices when more than
           half the book goes quiet. */
        val trustDrops = unlisted * 2 <= identified
        if (!trustDrops) {
            log("! the site's list is missing $unlisted of the $identified chapters we have on record")
            log("! that is too much to be chapters the site removed — keeping them all until it reads correctly")
        }
        var dropped = 0
        var suspect = 0
        for (x in extras) {
            /* a page from another host tells us nothing about this listing —
               fall through to the content check rather than the identity one */
            val from = fileUrl[x.base]?.takeIf { comparable(it) }
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

    /* Is the chapter already at this name the SAME CHAPTER we just fetched?

       By its heading, not by its bytes. Byte-identity looked stricter and was
       useless: the files this matters for were written by an older build, and
       any change to the extractor — an ad pattern, a whitespace rule, the
       heading format itself — shifts a byte. Every chapter then read as
       "different" and its translation was dropped, which is the whole novel
       re-bought at the API's price on the first run after an update. The
       heading carries the chapter's number and title and is stable across all
       of that; when a name changes hands, it is the first thing that changes.

       False when nothing is there — then there is no translation to worry
       about either. */
    private fun sameOnDisk(look: (String) -> DocumentFile?, name: String, body: String): Boolean {
        val want = body.lineSequence().firstOrNull()?.trim() ?: return false
        val cr = context.contentResolver
        for (n in listOf("$name.gz", name)) {
            val f = try { look(n) } catch (e: Exception) { null } ?: continue
            val text = try {
                cr.openInputStream(f.uri)?.use { ins ->
                    if (n.endsWith(".gz")) java.util.zip.GZIPInputStream(ins).use { it.readBytes() }
                    else ins.readBytes()
                }?.toString(Charsets.UTF_8)
            } catch (e: Exception) { null } ?: continue
            /* an empty stub says nothing — try the other form before giving up */
            val head = text.lineSequence().firstOrNull()?.trim().orEmpty()
            if (head.isEmpty()) continue
            return head == want
        }
        return false
    }

    /* Remove the translation filed under a chapter's name. Called when the
       source at that name is replaced by a different chapter, because nothing
       else would ever notice: translated/ is keyed by filename only. */
    private fun dropTranslation(dir: DocumentFile, name: String) {
        try {
            val tdir = dir.findFile("translated")?.takeIf { it.isDirectory } ?: return
            for (n in listOf(name, "$name.gz")) {
                val f = try { tdir.findFile(n) } catch (e: Exception) { null } ?: continue
                val ok = try { f.delete() } catch (e: Exception) { false }
                /* A refusal is REPORTED, not thrown. This runs after the source
                   has already been overwritten, so a delete that quietly failed
                   leaves the PREVIOUS chapter's English under a name that now
                   holds different text — served as this chapter's translation
                   for good, and never re-bought because the name exists. It
                   cannot be fixed from here; say so, since nothing downstream
                   can detect it. */
                if (!ok && (try { f.exists() } catch (e: Exception) { true })) {
                    log("! could not remove the old translation of $n — it may show the previous chapter's text")
                }
            }
        } catch (e: Exception) {}
    }

    /* the host a recorded page came from, for telling "this listing doesn't
       have that chapter" apart from "this listing is a different site" */
    private fun hostOf(url: String): String? =
        try { java.net.URI(url).host?.lowercase() } catch (e: Exception) { null }

    /* the part that says WHICH chapter, independent of which host served it */
    private fun pathOf(url: String): String? =
        try { java.net.URI(url).path?.trimEnd('/')?.lowercase()?.ifEmpty { null } } catch (e: Exception) { null }

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

    /* The listing had a hole in it, so the check refused to act. Distinct
       from "this URL isn't this novel" — which the caller answers by asking
       the site's other host — because the right answer here is to stop
       asking. Another host's listing names its chapters with its own URLs, so
       every recorded page reads as unlisted against it, and the very deletion
       this refusal exists to prevent is what happens instead. */
    class PartialListing : Exception()

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
    /* `expectTitle` is the novel we believe we are asking about, and is set
       whenever the URL is a GUESS rather than the one on record. Everything
       below renames files by listing position, deletes what the listing does
       not name, and overwrites the novel's URL, chapter order and totals — so
       a page that turns out to be a different book must not get that far. */
    suspend fun checkStatus(
        novelUrl: String,
        folderKey: String,
        expectTitle: String? = null,
    ): SiteStatus? = withContext(Dispatchers.IO) {
        val site = Sites.forUrl(novelUrl) ?: return@withContext null
        val (base, slug) = site.normalize(novelUrl)
        val first = fetch(base)
        if (first.html == null) return@withContext null
        val doc = Jsoup.parse(first.html, base)
        if (expectTitle != null && expectTitle.isNotBlank()) {
            val pageTitle = Extractor.stripAuthor(site.title(doc) ?: "", site.author(doc))
            if (!Extractor.sameNovelTitle(pageTitle, expectTitle)) {
                log("! $slug: $base is \"$pageTitle\", not \"$expectTitle\" — not acting on it")
                return@withContext null
            }
        }
        /* after the identity check, not before: a guess that lands on a soft
           404 or another book used to replace the novel's cover on its way to
           being rejected */
        saveCover(slug, doc)
        val seen = LinkedHashMap<String, Chapter>()   // discovery (= site) order
        /* Set when a page had no usable chapter-list container and the links
           came from reading the whole document instead. Everything
           destructive is off in that case: those are widget links, not the
           listing. */
        var fellBack = false
        /* One implementation, in Listing.collect — this logic used to be
           written out separately here and in run(), which is how the two
           drifted apart. Adding preserves discovery order, which IS the
           site's order and therefore names every file. */
        fun addLinks(d: org.jsoup.nodes.Document): Int {
            val found = Listing.collect(d, site, slug)
            if (found.fellBack) fellBack = true
            for ((href, text) in found.links) {
                if (!seen.containsKey(href)) seen[href] = Chapter(href, text)
            }
            return found.links.size
        }
        addLinks(doc)
        var last = site.maxPage(doc, slug)
        /* Held by page number and parsed in page order, exactly as the
           download does it: discovery order is the site's order, and the
           site's order is what names every file. */
        val pageHtml = HashMap<Int, String>()
        val missed = LinkedHashSet<Int>()
        val gone = HashSet<Int>()
        var fetched = 1
        while (fetched < last) {
            val batch = ((fetched + 1)..last).toList()
            val htmls = arrayOfNulls<String>(batch.size)
            val codes = IntArray(batch.size)
            coroutineScope {
                val sem = Semaphore(10)
                for ((i, p) in batch.withIndex()) {
                    launch {
                        sem.withPermit {
                            val r = fetch(site.listPageUrl(base, slug, p))
                            htmls[i] = r.html
                            codes[i] = r.status
                        }
                    }
                }
            }
            for ((i, html) in htmls.withIndex()) {
                val p = batch[i]
                if (html == null) {
                    missed.add(p)
                    if (codes[i] == 404 || codes[i] == 410) gone.add(p)
                    continue
                }
                pageHtml[p] = html
                last = maxOf(last, site.maxPage(Jsoup.parse(html, base), slug))
            }
            fetched = batch.last()
        }
        /* Confirm every page that reported missing before writing any of them
           off. A status check gets ONE attempt at each page, where a download
           re-confirms the 404 tail and then retries every hole three times
           before forgiving anything — so a page that blips 404 once was
           forgiven here on the spot, the listing came back a page short, and
           the dedupe deleted those chapters with no download to put them
           back. Before the parse loop, because a page that turns out to be
           real still has to be read in its own place in the order. */
        /* Every page in range is either read or recorded as missing. A page
           count raised AFTER the fetch loop ended — by this confirm pass, or
           by a retry — leaves pages that were never requested at all, and the
           parse loop below simply skips what isn't in `pageHtml`. They never
           entered `missed`, so the listing read as complete with a page of
           chapters absent and the dedupe deleted them: the hole this whole
           mechanism exists to prevent, re-entered through the code added to
           strengthen it. */
        fun accountForRange() {
            for (p in 2..last) if (p !in pageHtml && p !in missed) missed.add(p)
        }
        accountForRange()
        val tailSuspects = missed.toList()
        if (tailSuspects.isNotEmpty()) {
            val htmls = arrayOfNulls<String>(tailSuspects.size)
            /* ...and here it matters more than in run(): this pass has no
               retry loop behind it, so whatever `gone` says now is what
               forgiveness decides on — and a status check renames and deletes
               without ever downloading, so nothing self-corrects. A page that
               did not answer 404 this time is no longer evidence of an
               over-read; leaving it in `missed` makes the check refuse the
               novel, which is the safe direction. */
            val codes = IntArray(tailSuspects.size)
            coroutineScope {
                val sem = Semaphore(10)
                for ((i, p) in tailSuspects.withIndex()) {
                    launch {
                        sem.withPermit {
                            val r = fetch(site.listPageUrl(base, slug, p))
                            htmls[i] = r.html
                            codes[i] = r.status
                        }
                    }
                }
            }
            for ((i, html) in htmls.withIndex()) {
                val p = tailSuspects[i]
                /* mayMarkMissing = false: this pass confirms EVERY outstanding
                   page, not only the ones already reported missing, so a
                   404 here for a page that failed pass one some other way is
                   a first report, not a second. Letting it into `gone` made
                   it newly forgivable and its chapters were deleted by a
                   check that never downloads. */
                if (!Listing.record(
                        p, Listing.answer(html, codes[i]), gone, missed,
                        mayMarkMissing = false,
                    )
                ) continue
                /* there after all — a real page, not an over-read */
                pageHtml[p] = html!!
                last = maxOf(last, site.maxPage(Jsoup.parse(html, base), slug))
            }
        }
        /* ...and again, since the pages just read can raise the count too */
        accountForRange()
        missed.removeAll(Listing.forgivableTailPages(missed, gone, pageHtml.keys, last).toSet())
        /* Collected in page order — discovery order is the site's order, and
           the site's order is what names every file. Bounded by the first gap,
           the same as the download: the refusal below already stops a holed
           listing, but the two must not describe the range differently — that
           divergence is what put a private copy of this logic in each of them
           and let them drift apart in the first place. */
        for (p in 2 until Listing.firstGap(missed, last)) {
            val html = pageHtml[p] ?: continue
            if (addLinks(Jsoup.parse(html, base)) > 0) continue
            /* No chapters on it: not read, whatever it answered — and NOT
               added to `gone`, which means "the site said not there" and is
               the only thing that excuses a page as an over-read. See run(). */
            pageHtml.remove(p)
            missed.add(p)
            break
        }
        /* A status check renames and deletes but never downloads, so nothing
           here self-corrects. Acting on a listing with a page missing would
           renumber the library against a short list and delete the chapters
           the gap hid. Report nothing rather than something wrong.

           BEFORE the empty check: a listing that is both partial and yielded
           nothing is still partial, and answering "not this novel" sends the
           caller to the site's other host — the one thing that must not
           happen with pages missing. */
        if (missed.isNotEmpty()) {
            log("! $slug: listing page ${missed.first()} would not load — skipping this novel rather than acting on a partial list")
            throw PartialListing()
        }
        if (fellBack) {
            log("! $slug: the site's chapter list wasn't there — not acting on what's left of the page")
            throw PartialListing()
        }
        if (seen.isEmpty()) return@withContext null
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
        val dir = novelDir(treeUri, store, folderKey, slug, doc, site)
        /* Ask again, here. The caller tests "is this novel downloading?"
           before calling, but the listing fetch above is tens of seconds of
           paginated requests, and the queue can pop this very novel inside
           that window — so the rename pass moved files under a live download
           that was holding their locations. Reading the listing costs nothing;
           acting on a stale answer costs chapters. */
        val busy = try {
            DownloadService.isBusy(slug.lowercase().filter { it.isLetterOrDigit() })
        } catch (e: Exception) { false }
        if (dir != null && !busy) {
            renameToListingOrder(treeUri, dir, store, folderKey, slug, siteOrdered)
            dedupeExtras(
                treeUri, dir, store, folderKey, slug,
                siteOrdered.mapNotNull { it.filename }.toSet(),
                siteOrdered.map { it.url }.toSet(),
            )
        }
        SiteStatus(
            siteOrdered.size, site.isCompleted(doc), site.author(doc),
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
        val author = site.author(doc)
        /* sites often append "- {author}" to the title — folder names and the
           novels list carry the bare title, the author is stored separately */
        val title = Extractor.stripAuthor(
            site.title(doc) ?: slug,
            author,
        )

        val seen = LinkedHashMap<String, Chapter>()
        /* see checkStatus */
        var fellBack = false
        fun addLinks(d: org.jsoup.nodes.Document): Int {
            val found = Listing.collect(d, site, slug)
            if (found.fellBack) fellBack = true
            for ((href, text) in found.links) {
                if (!seen.containsKey(href)) seen[href] = Chapter(href, text)
            }
            return found.links.size
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
        /* Every page in range is either read or recorded as missing. A page
           count raised AFTER the fetch loop ended — by the tail confirmation
           or a retry, both of which read pagination off pages page 1 hadn't
           seen — leaves pages that were never requested at all. The collect
           loop simply skips what isn't in `pageHtml`, so those pages never
           entered `missed`: the listing read as COMPLETE with fifty chapters
           absent, and the dedupe deleted their files. */
        fun accountForRange() {
            for (p in 2..last) if (p !in pageHtml && p !in missed) missed.add(p)
        }
        fun forgiveOverRead() {
            /* the rule itself is in Listing.forgivableTailPages, so it can be
               tested without a network — see ListingTest */
            val forgivable = Listing.forgivableTailPages(missed, gone, pageHtml.keys, last)
            if (forgivable.isNotEmpty()) {
                missed.removeAll(forgivable.toSet())
                return
            }
            val looked = missed.count { it in gone }
            if (looked > 0) {
                log("! $looked listing page(s) at the end all report missing — treating that as a gap, not a miscount")
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
        accountForRange()
        val tailSuspects = missed.filter { p ->
            p in gone && p > (pageHtml.keys.maxOrNull() ?: 1)
        }
        if (tailSuspects.isNotEmpty() && !stopRequested) {
            status("Confirming ${tailSuspects.size} listing page(s) that report missing…")
            val htmls = arrayOfNulls<String>(tailSuspects.size)
            /* The status, not just "did it load". This is the LAST word on a
               tail page before forgiveness is decided a few lines below, and
               forgiveness treats the page's chapters as never having existed
               — the dedupe deletes their files and their paid translations.
               Throwing the code away meant a page that 404'd once and then
               merely timed out here was forgiven on the strength of the first
               answer, and a timeout is no evidence at all. */
            val codes = IntArray(tailSuspects.size)
            coroutineScope {
                val sem = Semaphore(conc)
                for ((i, p) in tailSuspects.withIndex()) {
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
                val p = tailSuspects[i]
                if (!Listing.record(p, Listing.answer(html, codes[i]), gone, missed)) continue
                /* it was there after all — a real page, not an over-read */
                pageHtml[p] = html!!
                last = maxOf(last, site.maxPage(Jsoup.parse(html, base), slug))
            }
            accountForRange()
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
                /* One rule for all three passes — see Listing.answer. `gone`
                   is what forgiveness is granted on, so it has to say what the
                   LAST attempt found rather than what any attempt ever found. */
                if (!Listing.record(retry[i], Listing.answer(html, codes[i]), gone, missed)) continue
                pageHtml[retry[i]] = html!!
                last = maxOf(last, site.maxPage(Jsoup.parse(html, base), slug))
            }
            accountForRange()
        }
        if (stopRequested) { status("Stopped."); return@withContext }
        accountForRange()
        forgiveOverRead()
        var listingComplete = missed.isEmpty()
        /* A hole doesn't invalidate the whole listing — only what comes AFTER
           it. Chapters discovered before the first missing page are still at
           the positions they belong to, so take that prefix and leave the rest
           for a run that can read the whole thing. Refusing outright meant one
           throttled page out of ninety downloaded nothing at all; naming from
           the full set would have written every later chapter over its
           neighbour. Everything destructive is already gated on
           listingComplete, so a prefix run only ever adds. */
        var firstGap = Listing.firstGap(missed, last)
        /* A page can fail without failing: HTTP 200 carrying a "not found"
           body, a WAF interstitial, or a layout the selectors no longer match.
           Those never reached `missed`, so the listing read as COMPLETE while
           a page of chapters was simply absent — and the dedupe then deleted
           those chapters' files as ones the site no longer lists.

           A page holding no chapter links was not really read, whatever it
           answered, so it goes back with the pages that didn't load and the
           SAME rule judges it: a hole inside the book stops the run, a blank
           page past the last real one is the page count over-reading and is
           forgiven. That rule is the fix for what was here before, which
           excused a blank page whenever no later page had loaded — i.e. it
           never examined the LAST page at all. A blank last page is exactly
           where a soft 404 lands, and its fifty chapters went into the dedupe
           as ones the site no longer lists. */
        var blank = 0
        for (p in 2 until firstGap) {
            val html = pageHtml[p] ?: continue
            if (addLinks(Jsoup.parse(html, base)) > 0) continue
            pageHtml.remove(p)
            missed.add(p)
            /* NOT `gone`. That set means "the site said this page is not
               there", which is the only evidence that excuses a page as the
               count over-reading — and forgiveness is what declares the
               listing complete. Filing a blank page there forgave it exactly
               when it was the LAST page (nothing loaded after it, because we
               just removed it), so the previous version of this fix rebuilt
               the very hole it was written to close: 200-with-no-chapters on
               the final page, listing "complete", fifty chapters deleted by
               the dedupe. A page that answered 200 is a page that exists; if
               it holds no chapters we could not read it, and the run stops
               collecting there and stays additive. */
            blank = p
            break
        }
        if (fellBack) {
            listingComplete = false
            log("! the site's chapter list wasn't where it should be — downloading only what the page shows")
        }
        if (blank > 0) {
            forgiveOverRead()
            listingComplete = missed.isEmpty()
            firstGap = Listing.firstGap(missed, last)
            if (!listingComplete) {
                log("! listing page $blank came back with no chapters on it — treating it as a gap")
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
           Batches API) so a NEW novel's chapters save straight into an
           "English (Vietnamese)" folder. A novel that already has a folder
           keeps it — this used to rename the Vietnamese one in place, which
           went wrong in every direction it could; Ownership.translatedFolder
           lists them. */
        val vietName = Extractor.folderName(title, slug)
        var folderName = vietName
        /* ...and only when there is anything to translate. The chapter pass
           skips a source that is already English (`sourceEnglish` below), but
           this ran before it and unconditionally: resuming an English novel
           with the translate preference left on spent an API call on an
           English title and could then RENAME the whole folder to the
           round-tripped result, which the user never asked for. */
        if (translate && apiKey.isNotBlank() && !stopRequested &&
            (!sourceEnglish || forceTranslate)
        ) {
            status("Translating title…")
            val t = translator ?: Translator(context, apiKey, log, status).also { translator = it }
            val english = try {
                t.ensureEnglishTitle(title, store, folderKey, slug) { stopRequested }
            } catch (e: Exception) { log("Title translation failed — ${e.message}"); null }
            if (!english.isNullOrBlank()) {
                /* Pick a folder; never rename one. See Ownership.translatedFolder
                   for why — every way that rename could go wrong, it did. A
                   novel that already has a directory keeps it; only one with
                   no directory yet is given the English name, and creating a
                   folder touches nothing that exists. */
                val recorded = try { store.dirNameFor(folderKey, slug) } catch (e: Exception) { null }
                folderName = Ownership.translatedFolder(
                    english = english,
                    vietName = vietName,
                    recordedDir = recorded,
                    vietIsOurs = Ownership.ours(
                        slug, vietName,
                        try { store.slugOwningName(folderKey, vietName) } catch (e: Exception) { null },
                        null,
                        try { store.chapterCount(folderKey, slug) } catch (e: Exception) { 0 },
                        try { store.hasOtherChapters(folderKey, slug) } catch (e: Exception) { true },
                    ),
                    onDisk = { root.findFile(it)?.isDirectory == true },
                )
                if (folderName != english) log("Keeping the existing folder \"$folderName\"")
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
           of — and then rename and write over that novel's chapters. */
        val recordedDir = try { store.dirNameFor(folderKey, slug) } catch (e: Exception) { null }
        val myChapters = try { store.chapterCount(folderKey, slug) } catch (e: Exception) { 0 }
        /* On failure, assume there ARE others: that is the cautious answer,
           the one that steps aside rather than moves in. */
        val knowsOthers = try { store.hasOtherChapters(folderKey, slug) } catch (e: Exception) { true }
        val choice = Ownership.choose(
            slug = slug,
            wanted = folderName,
            alt = Extractor.sanitize("$folderName ($slug)").ifEmpty { slug },
            owner = owner,
            recordedDir = recordedDir,
            myChapters = myChapters,
            indexKnowsOthers = knowsOthers,
            recordedDirOnDisk = { root.findFile(recordedDir ?: "")?.isDirectory == true },
            wantedOccupied = {
                val existingDir = root.findFile(folderName)?.takeIf { it.isDirectory }
                existingDir != null &&
                    try { existingDir.listFiles().isNotEmpty() } catch (e: Exception) { false }
            },
        )
        folderName = choice.name
        if (choice.stepAside) log("Another novel already uses that folder name — saving to \"$folderName\"")
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
                   clear() here threw the identities away too, and they are
                   the one thing that cannot be recovered. The app no longer
                   renames a novel's folder itself, but the user can, and a
                   restore or a remounted card recreates one under new
                   document ids. */
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
                /* Half-written files are invisible to every other lister by
                   design; this one indexed them as chapters, so a run killed
                   mid-write left rows pointing at documents the sweep then
                   deleted — a count the Library shows and the ownership check
                   trusts, backed by nothing. */
                if (Zips.isPartName(n)) continue
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
            /* A page from a different HOST. These novels are served from
               several — the status sweep records whichever answered — and the
               two answers are almost always the same book: the path carries
               the slug and the chapter, so `/tu-tien/chuong-100/` on .today
               and on .live is one chapter with two addresses. Migrate the
               identity to the address this run is using and the file is
               settled, with nothing re-downloaded and nothing re-translated.
               Only a genuinely different path is a different chapter.

               Calling every mismatch settled (which is what this did) left
               files holding their neighbours' text with nothing in the app
               able to notice; calling every mismatch stale re-downloaded whole
               novels on a host blip. */
            if (hostOf(owner) != hostOf(ch.url)) {
                if (pathOf(owner) != null && pathOf(owner) == pathOf(ch.url)) {
                    try { store.linkUrl(folderKey, slug, name, ch.url) } catch (e: Exception) {}
                    return true
                }
                stale.add(name)
                return false
            }
            stale.add(name)
            return false
        }

        val toFetch =
            if (refetchAll) chapters.filter { it.filename != null }
            else chapters.filter { it.filename != null && !settled(it) }
        if (stale.isNotEmpty()) log("${stale.size} chapter file(s) hold the wrong page — re-fetching those")
        val skipped = chapters.size - toFetch.size
        if (skipped > 0) log("skip $skipped already-downloaded chapter(s)")

        /* One listing of the folder, shared. `findFile` is a full
           listFiles()+getName() sweep per call, so the replace path — which
           looks a name up to clear it, and again to read what is there —
           turned a re-fetch of an N-chapter novel into O(N^2) binder queries.
           Only built when something is actually going to be replaced. */
        val diskIndex: Map<String, DocumentFile>? =
            if (refetchAll || stale.isNotEmpty()) {
                try { dir.listFiles().mapNotNull { f -> f.name?.let { it to f } }.toMap() }
                catch (e: Exception) { null }
            } else null
        val look: (String) -> DocumentFile? = { n ->
            diskIndex?.get(n) ?: try { dir.findFile(n) } catch (e: Exception) { null }
        }

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
                                    Jsoup.parse(res.html, ch.url), ch.text, ch.num ?: 0, site,
                                )
                                val replacing = refetchAll || ch.filename in stale
                                /* Is what is already under this name the same
                                   chapter? Read it BEFORE the write, because
                                   that is the only thing that can answer
                                   whether the translation beside it belongs to
                                   this chapter or to a previous occupant.
                                   Guessing from which path we are on doesn't
                                   work: the re-fetch-everything path sets
                                   `replace` for every chapter and populates
                                   `stale` for none, so a rule keyed on `stale`
                                   fires exactly nowhere on the one path that
                                   overwrites blindly. */
                                val sameText = replacing && sameOnDisk(look, ch.filename!!, body)
                                val uri = writeFile(
                                    dir, ch.filename!!, body, compressOn,
                                    replace = replacing, look = look,
                                )
                                /* The translation under that name belongs to
                                   the chapter we just overwrote, not to this
                                   one — and translated/ has no identity of its
                                   own: the translator decides what is already
                                   done from the names in the folder, and the
                                   reader joins the two halves by name alone.
                                   Left there, this chapter served the previous
                                   occupant's English for good, and was never
                                   re-translated because its name was present.
                                   Drop it; the next translate run buys the
                                   right one. */
                                /* The text under this name changed, so the
                                   translation under it is the old chapter's.
                                   translated/ has no identity of its own — the
                                   translator skips a name that exists and the
                                   reader joins by name alone — so left there it
                                   is served as this chapter's English for good.
                                   Unchanged text keeps its translation, which
                                   is what stops a legacy re-fetch re-buying a
                                   whole novel that was already translated. */
                                if (replacing && !sameText) dropTranslation(dir, ch.filename!!)
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
    private fun writeFile(
        dir: DocumentFile,
        name: String,
        text: String,
        compress: Boolean,
        replace: Boolean = false,
        look: ((String) -> DocumentFile?)? = null,
    ): String {
        /* SAF does not overwrite: creating a name that already exists yields
           "Chapter 5 (1).txt", which matches no pattern the app knows — the
           reader can't see it and the surplus sweep would delete the copy we
           just fetched. Clear the old document first when we know one is
           there. (Only then: findFile is a query per call, and the normal
           path never collides.) */
        /* A provider reports a refusal by RETURNING false; only a missing
           file throws. Ignoring the result meant going on to create a name
           that is still taken — which is the very thing this block exists
           to prevent, and it left "Chapter 5 (1).txt" behind: invisible to
           the reader, and deleted by the next sweep as surplus. Stop
           instead; the chapter stays as it was and the next run retries. */
        fun clear(n: String) {
            val f = try { look?.invoke(n) ?: dir.findFile(n) } catch (e: Exception) { null } ?: return
            val ok = try { f.delete() } catch (e: Exception) { false }
            if (ok) return
            /* delete() also returns false for a file that was already gone
               by the time we asked, so confirm before giving up */
            if (try { f.exists() } catch (e: Exception) { true }) {
                throw RuntimeException("could not replace $n")
            }
        }
        if (replace) {
            clear(name)
            clear("$name.gz")
        }
        if (compress) {
            Zips.writeGzDoc(context.contentResolver, dir.uri, "$name.gz", text)
                ?.let { return it.toString() }
            /* The write refuses a name the provider would have MINTED around
               ("Chapter 5.txt (1).gz"), so a failure here can mean the name is
               held by a file we don't count: a zero-length .gz from a killed
               run, which the index skips and `replace` therefore never
               cleared. Left alone the chapter could never be written under its
               own name again — every attempt minted a new invisible file while
               the reader kept serving the empty one. Clear the name and try
               once more. */
            /* ...but only when what holds the name is EMPTY. The write fails
               for transient reasons too (no space, a provider error), and
               clearing on those would delete a perfectly good chapter the
               index simply hadn't recorded. A zero-length file is the one
               case this retry exists for. */
            /* Read it, don't ask its length: DocumentFile.length() returns 0
               when the provider simply doesn't report a size, and clearing on
               that would delete a perfectly good chapter — the very thing this
               narrowing exists to prevent. */
            val squatterEmpty = !replace && listOf(name, "$name.gz").any { n ->
                val f = try { look?.invoke(n) ?: dir.findFile(n) } catch (e: Exception) { null }
                if (f == null || !f.isFile) return@any false
                val bytes = try {
                    context.contentResolver.openInputStream(f.uri)?.use { it.readBytes() }
                } catch (e: Exception) { null }
                bytes != null && bytes.isEmpty()
            }
            if (squatterEmpty) {
                try { clear(name); clear("$name.gz") } catch (e: Exception) {}
                Zips.writeGzDoc(context.contentResolver, dir.uri, "$name.gz", text)
                    ?.let { return it.toString() }
            }
        }
        /* Write under a name nothing adopts and rename it into place once the
           bytes are down — the same protection the compressed path has had.
           Without it a process killed mid-write leaves a short file that has a
           length and no recorded page, so the index counts it as the finished
           chapter and never fetches it again: truncated for good. */
        val tmp = Zips.partName(name)
        val f = dir.createFile("text/plain", tmp)
            ?: throw RuntimeException("could not create $name")
        try {
            val out = context.contentResolver.openOutputStream(f.uri)
                ?: throw RuntimeException("could not open $name")
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            val done = DocumentsContract.renameDocument(context.contentResolver, f.uri, name)
                ?: throw RuntimeException("could not name $name")
            /* renameDocument does not fail on a taken name — it MINTS one and
               returns a valid Uri for it. Recording that as the chapter left
               the real name pointing at whatever was already there, and the
               file we just wrote under a name nothing in the app matches. */
            val got = Zips.docName(context.contentResolver, done)
            if (got != null && Zips.isMinted(name, got)) {
                try { DocumentsContract.deleteDocument(context.contentResolver, done) } catch (e2: Exception) {}
                throw RuntimeException("$name is taken")
            }
            return done.toString()
        } catch (e: Exception) {
            try { f.delete() } catch (e2: Exception) {}
            throw e
        }
    }
}
