package dev.vtlinh.noveldownloader.sites

import dev.vtlinh.noveldownloader.Site
import dev.vtlinh.noveldownloader.SiteHelp
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/* novelfull — English, paginated as ?page=N. */
object Novelfull : Site {

    override val name = "novelfull"
    override val headingWord = "Chapter"
    override val english = true
    override val listScope = "#list-chapter"

    /* .com first: it is the larger catalogue, and .net carries novels it
       does not rather than being a mirror of it */
    override val hosts = listOf("novelfull.com", "novelfull.net")

    /* .com and .net serve the same application — same #list-chapter, same
       /slug/chapter-name.html links, same ?page=N pagination — and .net
       carries novels .com's catalogue does not. normalize keeps whichever
       host the url came from, so a novel stays where it was found. */
    private val urlRe = Regex("^https://novelfull\\.(com|net)/", RegexOption.IGNORE_CASE)

    override fun matches(url: String) = urlRe.containsMatchIn(url.trim())

    override fun normalize(url: String): Pair<String, String> {
        val u = URI(url.trim())
        val first = u.path.trimStart('/').substringBefore('/')
        val slug = first.removeSuffix(".html").removeSuffix(".htm")
        return Pair("https://${u.host}/$slug.html", slug)
    }

    override fun listPageUrl(base: String, slug: String, page: Int) = "$base?page=$page"

    /* fallback only — outside the chapter list the "latest chapters" widget
       has this same URL shape, so insist on a numbered slug */
    override fun isChapterPath(path: String, slug: String) =
        path.contains("/$slug/") &&
            Regex("chapter-\\d+", RegexOption.IGNORE_CASE).containsMatchIn(path)

    /* Chapters are named after their title, not numbered: "/1-ren.html",
       "/2-going-to-zone-a.html". Only a minority carry a "chapter-N" slug, so
       demanding that pattern hid most of the novel — a 19-page listing came
       back as a few hundred chapters and the download then called itself
       finished. */
    override fun isChapterInList(path: String, slug: String) =
        path.contains("/$slug/") && path.endsWith(".html", ignoreCase = true)

    override fun maxPage(doc: Document, slug: String) =
        SiteHelp.highestLink(doc, Regex("[?&]page=(\\d+)"))

    /* "chapter-601-..." where the site numbers it, otherwise the leading
       number of the title slug ("/10-foraging....html" -> 10).

       No guessing at misspelt words ("chpater-2812-…"): a name the site got
       wrong is not worth pattern-matching, and anything this fails to read
       takes its number from where it sits in the listing instead — which is
       the site's actual answer.

       The FIRST "chapter-N", not the last. This site's slug embeds the chapter
       title, and the title itself frequently contains "Chapter N" —
       ".../chapter-20-chapter-20-chapter-19-mirror2.html" is a real one,
       printed as chapter 20. Across the captured listings 79 links carry more
       than one match and 32 of those disagree; in all 32 the first is the
       site's printed number and the last is part of the title. A wrong number
       here is written into the saved file's heading and is the sole input to
       legacyNames, which is what turns it into a rename. */
    override fun chapterNumFromUrl(url: String): Int? {
        val seg = url.substringBefore('?').trimEnd('/')
            .substringAfterLast('/').removeSuffix(".html")
        return Regex("chapter-(\\d+)", RegexOption.IGNORE_CASE)
            .findAll(url).firstOrNull()?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("^(\\d+)").find(seg)?.groupValues?.get(1)?.toIntOrNull()
    }

    /* novel page: <h3>Status:</h3><a href=".../status/Completed">Completed</a> */
    override fun isCompleted(doc: Document) =
        doc.select("a[href*=/status/]").any { it.text().trim().equals("Completed", true) }

/* h3.title FIRST, unlike every other site here. novelfull.com has no
       og:title at all, so the shared chain happened to land on h3.title and
       give a clean name — but .net does have one, and it is wrapped in site
       furniture ("Read <title> novel online free - NovelFull"). Taking og
       first would have made that the FOLDER NAME for every novel captured
       from .net, permanently, since the recorded directory is never
       recomputed. Measured on both hosts before choosing. */
    override fun title(doc: Document) =
        doc.selectFirst("h3.title")?.text()?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null }
            ?: SiteHelp.metaTitle(doc)

    /* Every author link, not just the first — Library of Heaven's Path lists
       both "Heng Sao Tian Ya" and "横扫天涯", and the info screen shows both. */
    override fun author(doc: Document) =
        SiteHelp.infoField(doc, "Author")
            ?: doc.select("a[href*=/author/]")
                .map { it.text().trim() }.filter { it.isNotEmpty() }
                .distinct().joinToString(", ").ifEmpty { null }

    override fun alternativeNames(doc: Document) =
        SiteHelp.infoField(doc, "Alternative names", "Alternative name")

    /* .com prints "Genre:", .net prints "Genres:" — same field */
    override fun genres(doc: Document) = SiteHelp.infoField(doc, "Genre", "Genres")

    override fun source(doc: Document) = SiteHelp.infoField(doc, "Source")

    override fun statusLabel(doc: Document) = SiteHelp.infoField(doc, "Status")

    override fun description(doc: Document) = SiteHelp.descriptionText(doc)

    override fun chapterContent(doc: Document) =
        SiteHelp.firstOf(doc, listOf("#chapter-content", ".chapter-content", "#chapter-c", ".chapter-c"))

    override fun chapterHeading(doc: Document): Element? =
        SiteHelp.anyOf(doc, listOf("a.chapter-title", "h2 a", "h3 a", ".chapter-title", ".chapter-text"))
}
