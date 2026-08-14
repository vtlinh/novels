package dev.vtlinh.noveldownloader.sites

import dev.vtlinh.noveldownloader.Site
import dev.vtlinh.noveldownloader.SiteHelp
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/* truyenfullmoi.com — Vietnamese, paginated as /<slug>.<id>/trang-N/.

   THE ID. A novel is addressed as "/<slug>.<id>/" — "/so-13-pho-mink.1666/"
   — and the id is not optional: "/so-13-pho-mink/" answers 301 to the site's
   home page, as does any id that isn't the right one. Its CHAPTERS carry no
   id at all ("/so-13-pho-mink/chuong-1.html"), so the slug that identifies
   the novel and the segment that addresses it are two different strings, and
   this adapter keeps both: the base holds the id, the slug does not.

   REDIRECTS. Three kinds, all measured, and OkHttp follows every one:
     - the apex 301s to www, so a novel opened at truyenfullmoi.com is fetched
       through one hop per page;
     - "/<slug>.<id>/trang-1/" 301s back to the bare novel URL — harmless,
       since page 1 IS the novel page and the engine never asks for it;
     - "/<slug>/" with the id dropped 301s to the home page, which is why
       normalize refuses to invent a base for a url that carries no id. */
object Truyenfullmoi : Site {

    override val name = "truyenfullmoi"
    override val headingWord = "Chương"
    override val listScope = "#list-chapter"

    /* the apex redirects to www, so either opens the same site */
    override val hosts = listOf("truyenfullmoi.com")

    private val urlRe = Regex("^https://(www\\.)?truyenfullmoi\\.com(/|$)", RegexOption.IGNORE_CASE)

    override fun matches(url: String) = urlRe.containsMatchIn(url.trim())

    /* "<slug>.<id>" — the only path segment that names a novel here */
    private val novelSeg = Regex("^([a-z0-9-]+)\\.(\\d+)$", RegexOption.IGNORE_CASE)

    /* An EMPTY answer for a url that names no novel — a chapter page, the
       home page, a genre listing. The id lives only on the novel's own url,
       so a chapter url cannot be turned into one: "/so-13-pho-mink/" is not
       a shorter way of saying "/so-13-pho-mink.1666/", it is a redirect to
       the home page. Returning that as the base would start a download that
       reads the home page as a chapter list and finds nothing; empty is the
       answer the browser screen already understands as "not a novel page". */
    override fun normalize(url: String): Pair<String, String> {
        val u = URI(url.trim())
        val first = u.path.orEmpty().trimStart('/').substringBefore('/')
        val m = novelSeg.find(first) ?: return Pair("", "")
        return Pair("https://${u.host}/$first/", m.groupValues[1])
    }

    override fun listPageUrl(base: String, slug: String, page: Int) = "${base}trang-$page/"

    /* Chapters are numbered, always — every chapter link across the captured
       listings is "/<slug>/chuong-N.html", none is named after its title. The
       pagination sits INSIDE #list-chapter, but its links carry the id
       ("/<slug>.<id>/trang-2/") and so fail this test on the slug alone,
       which is why the loose in-list rule can be the strict one. */
    override fun isChapterPath(path: String, slug: String) =
        path.contains("/$slug/") && Regex("chuong-\\d+").containsMatchIn(path)

    override fun maxPage(doc: Document, slug: String) =
        SiteHelp.highestLink(doc, Regex("/" + Regex.escape(slug) + "\\.\\d+/trang-(\\d+)"))

    override fun chapterNumFromUrl(url: String) =
        Regex("chuong-(\\d+)").findAll(url).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    /* novel page: <h3>Trạng thái:</h3><span class="text-success">Full</span>,
       against <span class="text-primary">Đang ra</span> for one still
       running. Across the captured corpus span.text-success occurs exactly
       once on each of the 52 finished novels and not at all on the other 48,
       so the mark is unambiguous rather than merely usual. */
    override fun isCompleted(doc: Document) =
        doc.select("span.text-success").any {
            val t = it.text().trim()
            t.equals("Full", true) || t.contains("Hoàn", true)
        }

    /* h3.title, not the shared og:title chain: this host serves no og:title
       on any of the 100 captured pages, so the chain would reach h3.title by
       falling through — which is exactly how novelfull ended up one host away
       from naming every novel's folder "Read … - NovelFull". Ask for the
       element that actually carries the name. Measured: present on all 100,
       and the same length as the slug on every one of them. */
    override fun title(doc: Document) =
        doc.selectFirst("h3.title")?.text()?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null }

    override fun author(doc: Document) =
        SiteHelp.infoField(doc, "Tác giả")
            ?: doc.selectFirst("a[itemprop=author]")?.text()?.trim()?.ifEmpty { null }

    override fun genres(doc: Document) = SiteHelp.infoField(doc, "Thể loại")

    override fun source(doc: Document) = SiteHelp.infoField(doc, "Nguồn")

    override fun statusLabel(doc: Document) = SiteHelp.infoField(doc, "Trạng thái")

    override fun description(doc: Document) = SiteHelp.descriptionText(doc)

    override fun chapterContent(doc: Document) =
        SiteHelp.firstOf(doc, listOf("#chapter-c", ".chapter-c"))

    override fun chapterHeading(doc: Document): Element? =
        SiteHelp.anyOf(doc, listOf("a.chapter-title", ".chapter-title"))
}
