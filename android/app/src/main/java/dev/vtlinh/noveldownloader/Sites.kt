package dev.vtlinh.noveldownloader

import org.jsoup.nodes.Document
import java.net.URI

/* Port of the web app's site adapters: each supported site knows how to turn
   any of its URLs into a listing base + slug, page through the chapter list,
   recognise chapter links, and read a chapter number out of a chapter URL. */
class Site(
    val name: String,
    private val urlRe: Regex,
    val headingWord: String,
    val normalize: (String) -> Pair<String, String>,        // url -> (base, slug)
    val listPageUrl: (String, String, Int) -> String,       // (base, slug, page)
    val isChapterPath: (String, String) -> Boolean,         // (path, slug)
    val maxPage: (Document, String) -> Int,                 // (doc, slug)
    val chapterNumFromUrl: (String) -> Int?,
    val isCompleted: (Document) -> Boolean,                 // novel page says finished
    /* site publishes in English, so there is nothing to translate to English */
    val english: Boolean = false,
    /* container of the REAL chapter list — pages also carry a "latest
       chapters" widget whose links must not pollute the chapter order */
    val listScope: String = "#list-chapter",
) {
    fun matches(url: String) = urlRe.containsMatchIn(url.trim())
}

object Sites {
    val all: List<Site> = listOf(
        Site(
            name = "truyenfull",
            urlRe = Regex("^https://truyenfull\\.(today|live)(/|$)", RegexOption.IGNORE_CASE),
            headingWord = "Chương",
            normalize = { url ->
                val u = URI(url.trim())
                val slug = u.path.trimStart('/').substringBefore('/')
                Pair("https://${u.host}/$slug/", slug)
            },
            listPageUrl = { base, _, p -> "${base}trang-$p/" },
            isChapterPath = { path, slug ->
                path.contains("/$slug/") && Regex("chuong-\\d+").containsMatchIn(path)
            },
            maxPage = { doc, slug ->
                var max = 1
                val re = Regex("/" + Regex.escape(slug) + "/trang-(\\d+)")
                for (a in doc.select("a[href]")) {
                    val m = re.find(a.attr("href"))
                    if (m != null) max = maxOf(max, m.groupValues[1].toInt())
                }
                max
            },
            chapterNumFromUrl = { url ->
                Regex("chuong-(\\d+)").findAll(url).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
            },
            /* novel page: <h3>Trạng thái:</h3><span class="text-success">Full</span> */
            isCompleted = { doc ->
                doc.select("span.text-success").any {
                    val t = it.text().trim()
                    t.equals("Full", true) || t.contains("Hoàn", true)
                }
            },
        ),
        Site(
            name = "novelfull",
            urlRe = Regex("^https://novelfull\\.com/", RegexOption.IGNORE_CASE),
            headingWord = "Chapter",
            normalize = { url ->
                val u = URI(url.trim())
                val first = u.path.trimStart('/').substringBefore('/')
                val slug = first.removeSuffix(".html").removeSuffix(".htm")
                Pair("https://${u.host}/$slug.html", slug)
            },
            listPageUrl = { base, _, p -> "$base?page=$p" },
            /* Chapters live under /<slug>/ and are named after their title,
               not numbered: "/1-ren.html", "/2-going-to-zone-a.html". Only
               some carry a "chapter-N" slug, so requiring that pattern hid
               most of the novel — the listing found a few hundred of a few
               thousand chapters and called the download complete. */
            isChapterPath = { path, slug ->
                path.contains("/$slug/") && path.endsWith(".html", ignoreCase = true)
            },
            maxPage = { doc, _ ->
                var max = 1
                val re = Regex("[?&]page=(\\d+)")
                for (a in doc.select("a[href]")) {
                    val m = re.find(a.attr("href"))
                    if (m != null) max = maxOf(max, m.groupValues[1].toInt())
                }
                max
            },
            /* "chapter-601-..." where the site numbers it, otherwise the
               leading number of the title slug ("/10-foraging....html" -> 10) */
            chapterNumFromUrl = { url ->
                val seg = url.substringBefore('?').trimEnd('/')
                    .substringAfterLast('/').removeSuffix(".html")
                Regex("chapter-(\\d+)", RegexOption.IGNORE_CASE)
                    .findAll(url).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("^(\\d+)").find(seg)?.groupValues?.get(1)?.toIntOrNull()
            },
            /* novel page: <h3>Status:</h3><a href=".../status/Completed">Completed</a> */
            isCompleted = { doc ->
                doc.select("a[href*=/status/]").any { it.text().trim().equals("Completed", true) }
            },
            english = true,
        ),
    )

    fun forUrl(url: String): Site? = all.firstOrNull { it.matches(url) }

    /* author name from a novel page: truyenfull marks it itemprop=author,
       novelfull links it under /author/ */
    fun author(doc: Document): String? =
        doc.selectFirst("a[itemprop=author]")?.text()?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("a[href*=/author/], a[href*=tac-gia]")?.text()?.trim()?.ifEmpty { null }
}
