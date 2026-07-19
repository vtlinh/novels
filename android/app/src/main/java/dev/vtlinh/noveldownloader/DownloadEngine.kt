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

    companion object {
        const val CONCURRENCY = 20
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun fetch(url: String): String? = try {
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept-Language", "vi,en;q=0.8")
                .build(),
        ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (e: Exception) {
        null
    }

    class Chapter(val url: String, val text: String) {
        var num: Int? = null
        var filename: String? = null
    }

    suspend fun run(novelUrl: String, treeUri: Uri) = withContext(Dispatchers.IO) {
        val site = Sites.forUrl(novelUrl)
        if (site == null) {
            log("Unsupported URL: $novelUrl")
            return@withContext
        }
        val (base, slug) = site.normalize(novelUrl)

        status("Listing chapters…")
        val firstHtml = fetch(base)
        if (firstHtml == null) {
            log("Could not load $base")
            status("Error: could not load the novel page")
            return@withContext
        }
        val doc = Jsoup.parse(firstHtml, base)
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
            val html = fetch(site.listPageUrl(base, slug, page)) ?: continue
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
        val folderName = Extractor.sanitize(title)
        val dir = root.findFile(folderName)?.takeIf { it.isDirectory }
            ?: root.createDirectory(folderName)
        if (dir == null) {
            log("Could not create folder \"$folderName\"")
            status("Error: could not create the novel folder")
            return@withContext
        }
        log("Saving to: $folderName/")

        status("Checking already-downloaded chapters…")
        val existing = dir.listFiles().mapNotNull { it.name }.toHashSet()
        val toFetch = chapters.filter { it.filename != null && it.filename !in existing }
        val skipped = chapters.size - toFetch.size
        if (skipped > 0) log("skip $skipped already-downloaded chapter(s)")

        val done = AtomicInteger(0)
        val saved = AtomicInteger(0)
        val failed = java.util.Collections.synchronizedList(mutableListOf<Chapter>())
        val total = toFetch.size
        val sem = Semaphore(CONCURRENCY)

        suspend fun fetchAll(list: List<Chapter>, countProgress: Boolean) = coroutineScope {
            for (ch in list) {
                if (stopRequested) break
                launch {
                    sem.withPermit {
                        if (stopRequested) return@withPermit
                        try {
                            val html = fetch(ch.url) ?: throw RuntimeException("fetch failed")
                            val body = Extractor.parseChapter(
                                Jsoup.parse(html, ch.url), ch.text, ch.num ?: 0, site.headingWord,
                            )
                            writeFile(dir, ch.filename!!, body)
                            saved.incrementAndGet()
                        } catch (e: Exception) {
                            failed.add(ch)
                            log("FAILED ${ch.url} — ${e.message}")
                        }
                        if (countProgress) {
                            val d = done.incrementAndGet()
                            progress(d, total)
                            status("$d/$total done (${saved.get()} saved, ${failed.size} failed)")
                        }
                    }
                }
            }
        }

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

        val summary = "${saved.get()} saved, $skipped skipped, ${failed.size} failed"
        status((if (stopRequested) "Stopped: " else "Done: ") + summary)
        log((if (stopRequested) "Stopped — re-run to resume. " else "✓ Finished. ") + summary)
    }

    private fun writeFile(dir: DocumentFile, name: String, text: String) {
        val f = dir.createFile("text/plain", name)
            ?: throw RuntimeException("could not create $name")
        val out = context.contentResolver.openOutputStream(f.uri)
            ?: throw RuntimeException("could not open $name")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
