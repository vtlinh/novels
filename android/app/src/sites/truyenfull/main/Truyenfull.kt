package dev.vtlinh.noveldownloader.sites

import dev.vtlinh.noveldownloader.Site
import dev.vtlinh.noveldownloader.SiteHelp
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/* truyenfull — Vietnamese, paginated as /<slug>/trang-N/. */
object Truyenfull : Site {

    override val name = "truyenfull"
    override val headingWord = "Chương"
    override val listScope = "#list-chapter"

    /* both spellings serve the same catalogue */
    override val hosts = listOf("truyenfull.today", "truyenfull.live")

    private val urlRe = Regex("^https://truyenfull\\.(today|live)(/|$)", RegexOption.IGNORE_CASE)

    override fun matches(url: String) = urlRe.containsMatchIn(url.trim())

    override fun normalize(url: String): Pair<String, String> {
        val u = URI(url.trim())
        val slug = u.path.trimStart('/').substringBefore('/')
        return Pair("https://${u.host}/$slug/", slug)
    }

    override fun listPageUrl(base: String, slug: String, page: Int) = "${base}trang-$page/"

    override fun isChapterPath(path: String, slug: String) =
        path.contains("/$slug/") && Regex("chuong-\\d+").containsMatchIn(path)

    /* In the chapter list, anything under the novel is a chapter except the
       listing's own page links. "Under" has to mean something actually
       follows: the pagination block sits INSIDE the chapter-list container,
       and its link back to page 1 is the novel's own canonical URL — which is
       not /trang-N and so read as a chapter, one bogus entry mid-listing that
       pushed every chapter after it a position out of place. */
    override fun isChapterInList(path: String, slug: String): Boolean {
        val marker = "/$slug/"
        val i = path.indexOf(marker)
        return i >= 0 && path.length > i + marker.length &&
            !Regex("/trang-\\d+").containsMatchIn(path)
    }

    override fun maxPage(doc: Document, slug: String) =
        SiteHelp.highestLink(doc, Regex("/" + Regex.escape(slug) + "/trang-(\\d+)"))

    override fun chapterNumFromUrl(url: String) =
        Regex("chuong-(\\d+)").findAll(url).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    /* novel page: <h3>Trạng thái:</h3><span class="text-success">Full</span> */
    override fun isCompleted(doc: Document) =
        doc.select("span.text-success").any {
            val t = it.text().trim()
            t.equals("Full", true) || t.contains("Hoàn", true)
        }

    override fun title(doc: Document) = SiteHelp.metaTitle(doc)

    override fun author(doc: Document) =
        doc.selectFirst("a[itemprop=author]")?.text()?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("a[href*=tac-gia]")?.text()?.trim()?.ifEmpty { null }

    override fun chapterContent(doc: Document) =
        SiteHelp.firstOf(doc, listOf("#chapter-c", ".chapter-c", "div[itemprop=articleBody]", ".box-chap"))

    override fun chapterHeading(doc: Document): Element? =
        SiteHelp.anyOf(doc, listOf("a.chapter-title", "h2 a", "h3 a", ".chapter-title", ".chapter-text"))
}
