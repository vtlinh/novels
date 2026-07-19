package dev.vtlinh.noveldownloader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
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
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
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

    /* adaptive parallelism, ported from the web app: start at CONC_START, a
       batch with >=20% throttle statuses (429/503/408/network) halves it
       (floor CONC_MIN), a fully-clean batch raises it by 10 (ceil CONC_MAX) */
    @Volatile private var conc = CONC_START
    private fun isThrottle(s: Int) = s == 429 || s == 503 || s == 408 || s == 0
    private fun adaptConc(okCount: Int, statuses: List<Int>) {
        val totalN = okCount + statuses.size
        if (totalN == 0) return
        val throttled = statuses.count { isThrottle(it) }
        if (throttled >= maxOf(2, Math.ceil(totalN * 0.2).toInt())) {
            conc = maxOf(CONC_MIN, conc / 2)
        } else if (statuses.isEmpty() && conc < CONC_MAX) {
            conc = minOf(CONC_MAX, conc + 10)
        }
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

    suspend fun run(
        novelUrl: String,
        treeUri: Uri,
        translate: Boolean,
        apiKey: String,
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
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("h3.title")?.text()?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null }
            ?: slug

        val seen = LinkedHashMap<String, Chapter>()
        fun addLinks(d: org.jsoup.nodes.Document) {
            for (a in d.select("a[href]")) {
                val href = a.absUrl("href").substringBefore('#')
                if (href.isEmpty() || seen.containsKey(href)) continue
                val path = try { java.net.URI(href).path ?: "" } catch (e: Exception) { continue }
                if (site.isChapterPath(path, slug)) seen[href] = Chapter(href, a.text().trim())
            }
        }
        addLinks(doc)
        var last = site.maxPage(doc, slug)
        var page = 1
        while (page < last && !stopRequested) {
            page++
            status("Listing chapters: page $page of $last…")
            val html = fetch(site.listPageUrl(base, slug, page)).html ?: continue
            val d = Jsoup.parse(html, base)
            addLinks(d)
            last = maxOf(last, site.maxPage(d, slug))
        }
        if (stopRequested) { status("Stopped."); return@withContext }

        val chapters = seen.values.toMutableList()
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
                if (f.length() > 0) { existing.add(n); rows.add(n to f.uri.toString()) }
            }
            store.addAll(folderKey, slug, rows)
        }

        val toFetch = chapters.filter { it.filename != null && it.filename !in existing }
        val skipped = chapters.size - toFetch.size
        if (skipped > 0) log("skip $skipped already-downloaded chapter(s)")

        conc = CONC_START
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

        /* one FETCH_BATCH-sized group, its chapters fetched concurrently up to
           the CURRENT `conc`; returns the batch's HTTP statuses so the caller
           can adapt speed and detect throttling. countProgress bumps the bar
           only on the main pass (retried chapters were already counted). */
        suspend fun runBatch(batch: List<Chapter>, countProgress: Boolean): List<Int> = coroutineScope {
            val sem = Semaphore(conc.coerceIn(CONC_MIN, CONC_MAX))
            val statuses = java.util.Collections.synchronizedList(mutableListOf<Int>())
            batch.map { ch ->
                launch {
                    if (stopRequested) return@launch
                    sem.withPermit {
                        if (stopRequested) return@withPermit
                        inFlight.incrementAndGet()
                        try {
                            val res = fetch(ch.url)
                            if (res.html == null) {
                                statuses.add(res.status)
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
                            statuses.add(-1)   // parse/write error, not a throttle signal
                            failed.add(ch)
                            log("FAILED ${ch.url} — ${e.message}")
                        } finally {
                            inFlight.decrementAndGet()
                        }
                        if (countProgress) { done.incrementAndGet(); progress(done.get(), total) }
                        report()
                    }
                }
            }.forEach { it.join() }
            statuses
        }

        suspend fun fetchGroups(list: List<Chapter>, countProgress: Boolean) {
            var i = 0
            while (i < list.size && !stopRequested) {
                val batch = list.subList(i, minOf(i + FETCH_BATCH, list.size))
                i += batch.size
                val okBefore = saved.get()
                val statuses = runBatch(batch, countProgress)
                if (statuses.isNotEmpty()) {
                    val throttle = statuses.count { isThrottle(it) }
                    log("batch: ${statuses.size} of ${batch.size} failed" +
                        (if (throttle > 0) " — site appears to be rate limiting" else ""))
                }
                adaptConc(saved.get() - okBefore, statuses)
            }
        }

        fetchGroups(toFetch, true)

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
            fetchGroups(toRetry, false)
            log("Retry pass $pass/4: ${toRetry.size - failed.size} recovered, ${failed.size} still failing")
        }

        val secs = (System.currentTimeMillis() - fetchStart) / 1000.0
        val avg = if (secs > 1 && saved.get() > 0) " Avg %.1f/s.".format(saved.get() / secs) else ""
        val summary = "${saved.get()} saved, $skipped skipped, ${failed.size} failed.$avg"
        log((if (stopRequested) "Stopped — re-run to resume. " else "✓ Finished. ") + summary)

        if (translate && apiKey.isNotBlank() && !stopRequested) {
            try {
                val t = translator ?: Translator(context, apiKey, log, status).also { translator = it }
                t.translate(dir, store, folderKey, slug, chapters.mapNotNull { it.filename }) { stopRequested }
            } catch (e: Exception) {
                log("TRANSLATION FAILED — ${e.message}")
            }
        }
        status((if (stopRequested) "Stopped: " else "Done: ") + summary)
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
