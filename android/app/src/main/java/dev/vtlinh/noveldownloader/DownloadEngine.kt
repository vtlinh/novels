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
    suspend fun checkStatus(novelUrl: String): SiteStatus? = withContext(Dispatchers.IO) {
        val site = Sites.forUrl(novelUrl) ?: return@withContext null
        val (base, slug) = site.normalize(novelUrl)
        val first = fetch(base)
        if (first.html == null) return@withContext null
        val doc = Jsoup.parse(first.html, base)
        saveCover(slug, doc)
        val seen = LinkedHashMap<String, Chapter>()   // discovery (= site) order
        /* Collect chapter links from the REAL chapter list only — pages also
           carry a "latest chapters" widget whose links come first in the HTML
           and would corrupt the site order. Whole-document fallback when the
           scoped container is missing or yields nothing new. */
        fun addLinks(d: org.jsoup.nodes.Document) {
            /* returns how many chapter links the container HOLDS, not how many
               were new. A listing page whose chapters were all seen already is
               still a real chapter list — counting only new ones made it look
               empty and sent us to the whole-document fallback, which is
               exactly where the "latest chapters" widget lives. */
            fun collect(root: org.jsoup.nodes.Element): Int {
                var found = 0
                for (a in root.select("a[href]")) {
                    val href = a.absUrl("href").substringBefore('#')
                    if (href.isEmpty()) continue
                    val path = try { java.net.URI(href).path ?: "" } catch (e: Exception) { continue }
                    if (!site.isChapterPath(path, slug)) continue
                    found++
                    if (!seen.containsKey(href)) seen[href] = Chapter(href, a.text().trim())
                }
                return found
            }
            val scope = d.selectFirst(site.listScope)
            if (scope == null || collect(scope) == 0) collect(d)
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
                val d = Jsoup.parse(html ?: continue, base)
                addLinks(d)
                last = maxOf(last, site.maxPage(d, slug))
            }
            fetched = batch.last()
        }
        if (seen.isEmpty()) return@withContext null
        /* assign filenames exactly like run(): numeric sort decides the
           duplicate suffixes, site order is what we report */
        val siteOrdered = seen.values.toList()
        for (ch in siteOrdered) ch.num = site.chapterNumFromUrl(ch.url) ?: Extractor.parseHeading(ch.text).first
        val sorted = siteOrdered.sortedBy { it.num ?: Int.MAX_VALUE }
        val counts = HashMap<Int, Int>()
        for (ch in sorted) {
            val n = ch.num ?: continue
            val c = (counts[n] ?: 0) + 1
            counts[n] = c
            ch.filename = "Chapter $n" + (if (c > 1) "-$c" else "") + ".txt"
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
        fun addLinks(d: org.jsoup.nodes.Document) {
            /* returns how many chapter links the container HOLDS, not how many
               were new. A listing page whose chapters were all seen already is
               still a real chapter list — counting only new ones made it look
               empty and sent us to the whole-document fallback, which is
               exactly where the "latest chapters" widget lives. */
            fun collect(root: org.jsoup.nodes.Element): Int {
                var found = 0
                for (a in root.select("a[href]")) {
                    val href = a.absUrl("href").substringBefore('#')
                    if (href.isEmpty()) continue
                    val path = try { java.net.URI(href).path ?: "" } catch (e: Exception) { continue }
                    if (!site.isChapterPath(path, slug)) continue
                    found++
                    if (!seen.containsKey(href)) seen[href] = Chapter(href, a.text().trim())
                }
                return found
            }
            val scope = d.selectFirst(site.listScope)
            if (scope == null || collect(scope) == 0) collect(d)
        }
        addLinks(doc)
        var last = site.maxPage(doc, slug)
        /* fetch all remaining listing pages in parallel (Semaphore-capped);
           parse in page order so chapter discovery stays deterministic. A
           later page can raise the page count, so loop until none are left. */
        var fetched = 1
        while (fetched < last && !stopRequested) {
            val batch = ((fetched + 1)..last).toList()
            status("Listing chapters: pages ${batch.first()}-${batch.last()} of $last…")
            val htmls = arrayOfNulls<String>(batch.size)
            coroutineScope {
                val sem = Semaphore(conc)
                for ((i, p) in batch.withIndex()) {
                    launch {
                        sem.withPermit {
                            if (!stopRequested) htmls[i] = fetch(site.listPageUrl(base, slug, p)).html
                        }
                    }
                }
            }
            for (html in htmls) {
                val d = Jsoup.parse(html ?: continue, base)
                addLinks(d)
                last = maxOf(last, site.maxPage(d, slug))
            }
            fetched = batch.last()
        }
        if (stopRequested) { status("Stopped."); return@withContext }

        val chapters = seen.values.toMutableList()
        /* the site's exact order (listing-page sequence) — captured before the
           numeric sort so the reader can present chapters as the site does */
        val siteOrdered = chapters.toList()
        for (ch in chapters) ch.num = site.chapterNumFromUrl(ch.url) ?: Extractor.parseHeading(ch.text).first
        chapters.sortBy { it.num ?: Int.MAX_VALUE }
        val counts = HashMap<Int, Int>()
        for (ch in chapters) {
            val n = ch.num ?: continue
            val c = (counts[n] ?: 0) + 1
            counts[n] = c
            ch.filename = "Chapter $n" + (if (c > 1) "-$c" else "") + ".txt"
        }
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
        val store = DownloadStore(context)
        val folderKey = treeUri.toString()
        /* register for the List Novels screen (first run keeps its timestamp;
           last_dl always bumps so the list sorts newest download first) */
        store.registerNovel(folderKey, slug, base, title, System.currentTimeMillis())
        store.touchNovel(folderKey, slug, System.currentTimeMillis())
        author?.let { store.setAuthor(folderKey, slug, it) }
        saveCover(slug, doc)
        /* index the site's chapter order for the reader */
        store.setChapterOrder(folderKey, slug, siteOrdered.mapNotNull { it.filename })

        /* When translating, render the English folder name up front (Sonnet,
           Batches API) so chapters save straight into an "English (Vietnamese)"
           folder. If a plain Vietnamese-named folder already exists from an
           earlier non-translated run, rename it in place instead of starting
           a fresh one. */
        val vietName = Extractor.sanitize(title)
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
                            store.clear(folderKey, slug)   // rename changes child URIs — rebuild the index
                            log("Renamed existing folder to \"$english\"")
                        } else {
                            log("Could not rename folder — using \"$english\"")
                        }
                    }
                }
            }
        }

        val dir = root.findFile(folderName)?.takeIf { it.isDirectory }
            ?: root.createDirectory(folderName)
        if (dir == null) {
            log("Could not create folder \"$folderName\"")
            status("Error: could not create the novel folder")
            return@withContext
        }
        log("Saving to: $folderName/")

        status("Checking already-downloaded chapters…")
        val existing = HashSet<String>()   // filenames known present on disk

        val cached = store.get(folderKey, slug)   // filename -> document URI
        var usedCache = false
        if (cached.isNotEmpty()) {
            /* O(1) spot-check: does a sample indexed file still exist? Catches
               a deleted/moved folder without listing the whole directory. */
            val sampleUri = cached.values.first()
            val ok = try {
                DocumentFile.fromSingleUri(context, Uri.parse(sampleUri))?.exists() == true
            } catch (e: Exception) { false }
            if (ok) {
                existing.addAll(cached.keys)
                usedCache = true
            } else {
                store.clear(folderKey, slug)
                log("Saved-chapter index was stale (folder moved or removed) — re-listing.")
            }
        }
        if (!usedCache) {
            /* fallback: one folder listing (verify non-empty — files may have
               been copied in from elsewhere), then rebuild the index */
            val rows = ArrayList<Pair<String, String>>()
            for (f in dir.listFiles()) {
                val n = f.name ?: continue
                if (Zips.isGzName(n)) {
                    /* per-chapter compressed file counts as its .txt */
                    existing.add(n.removeSuffix(".gz"))
                    continue
                }
                if (Zips.isZipName(n)) {
                    /* compressed chapters count as present (top-level entries
                       are the Vietnamese sources) but stay out of the index */
                    try {
                        context.contentResolver.openInputStream(f.uri)?.use { ins ->
                            java.util.zip.ZipInputStream(ins).use { z ->
                                var e = z.nextEntry
                                while (e != null) {
                                    if (!e.isDirectory && !e.name.contains('/')) existing.add(e.name)
                                    e = z.nextEntry
                                }
                            }
                        }
                    } catch (e: Exception) {}
                    continue
                }
                if (f.length() > 0) { existing.add(n); rows.add(n to f.uri.toString()) }
            }
            store.addAll(folderKey, slug, rows)
        }

        val toFetch = chapters.filter { it.filename != null && it.filename !in existing }
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
                                val uri = writeFile(dir, ch.filename!!, body)
                                store.add(folderKey, slug, ch.filename!!, uri)
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
        try {
            val allOnDisk = !stopRequested && failed.isEmpty()
            store.updateNovelCheck(folderKey, slug, chapters.size, site.isCompleted(doc) && allOnDisk)
            /* ...and the authoritative on-disk count. The Library can't derive
               it for a compressed novel — the chapters index only tracks loose
               files, and the folder scan is one-time — so without this the row
               keeps showing a stale count after a download. We know it exactly
               here: the chapters that were already present plus what we saved. */
            store.setDiskCount(
                folderKey, slug,
                chapters.count { it.filename != null && it.filename in existing } + saved.get(),
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
                    log("Chapters compressed into ${Zips.NAME}")
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

    private fun writeFile(dir: DocumentFile, name: String, text: String): String {
        val f = dir.createFile("text/plain", name)
            ?: throw RuntimeException("could not create $name")
        val out = context.contentResolver.openOutputStream(f.uri)
            ?: throw RuntimeException("could not open $name")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return f.uri.toString()
    }
}
