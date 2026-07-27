package dev.vtlinh.noveldownloader.sites

import dev.vtlinh.noveldownloader.Site
import dev.vtlinh.noveldownloader.SiteHelp
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/* freewebnovel.com — English, paginated as ?page=N, 40 chapters a page.

   The novel is "/novel/<slug>" and its chapters "/novel/<slug>/chapter-N",
   uniformly: all 3,905 chapter links across the captured listings have that
   one shape. */
object Freewebnovel : Site {

    override val name = "freewebnovel"
    override val headingWord = "Chapter"
    override val english = true

    /* The index. .m-newest1 beside it is a six-link "latest chapters" widget
       and both are on every one of the 100 captured pages, so a selector on
       their shared ul.ul-list5 class would take whichever came first and call
       six links a novel. */
    override val listScope = ".m-newest2"

    override val hosts = listOf("freewebnovel.com")

    private val urlRe = Regex("^https://(www\\.)?freewebnovel\\.com/", RegexOption.IGNORE_CASE)

    override fun matches(url: String) = urlRe.containsMatchIn(url.trim())

    override fun normalize(url: String): Pair<String, String> {
        val u = URI(url.trim())
        val parts = u.path.orEmpty().trimStart('/').split('/')
        val slug = if (parts.firstOrNull() == "novel" && parts.size > 1) parts[1] else parts.first()
        return Pair("https://${u.host}/novel/$slug", slug)
    }

    override fun listPageUrl(base: String, slug: String, page: Int) = "$base?page=$page"

    override fun isChapterPath(path: String, slug: String) =
        path.contains("/novel/$slug/") &&
            Regex("/chapter-\\d+", RegexOption.IGNORE_CASE).containsMatchIn(path)

    /* THE PAGE COUNT IS THE NUMBER OF OPTIONS IN THE SELECT.

       Every other site here states its page count in a link the pagination
       block prints, and SiteHelp.highestLink reads it off the hrefs. This one
       prints no such link: there is not a single "?page=" anywhere on a novel
       page, the First/Next buttons are "javascript:void(0)", and the
       #indexselect dropdown that drives the listing gives EVERY option the
       same value — the novel's own url, page one.

       What the options do say is their ranges: "C.1 - C.40", "C.41 - C.80",
       one per page, so counting them is the page count. Measured against a
       684-chapter novel: 18 options, and 684/40 rounds up to 18. Asking for
       "?page=2" then really does return chapters 41-80, so the pages exist —
       only the link to them does not. */
    override fun maxPage(doc: Document, slug: String) =
        maxOf(1, doc.select("#indexselect option").size)

    /* NULL, DELIBERATELY. "/chapter-24" looks like the chapter's number and
       is not: it is the position the site files it under, and where the
       site's own numbering has drifted the two disagree. Two of the 30
       captured chapters are such — /novel/awakening/chapter-24 prints
       "Chapter 40", and one other is out by one — so reading it would be
       wrong on 7% of chapters.

       That is not a harmless 7%: the number goes into the saved file's
       heading and is the sole input to the legacy rename guess. Null is a
       supported answer and the right one, and the caller then names the
       chapter by its POSITION in the listing, which is what the url was
       telling us in the first place. */
    override fun chapterNumFromUrl(url: String): Int? = null

    /* novel page: the status sits in the .item whose icon is the clock. Read
       the text rather than a class — the neighbouring read-novel.com prints
       both states in one class, which is a trap worth not walking into twice. */
    override fun isCompleted(doc: Document) =
        doc.select(".item").any { item ->
            item.selectFirst("span.glyphicon-time") != null &&
                item.selectFirst(".right")?.text()?.trim()
                    ?.startsWith("Completed", true) == true
        }

    /* og:title is the bare novel name on all 100 captured pages — no site
       furniture, unlike novelfull.net's — and the h1 agrees with it. */
    override fun title(doc: Document) =
        doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null }

    override fun author(doc: Document) =
        doc.selectFirst("a[href*=/author/]")?.text()?.trim()?.ifEmpty { null }
            ?: doc.selectFirst(".item span.s1 a")?.text()?.trim()?.ifEmpty { null }

    override fun chapterContent(doc: Document) =
        SiteHelp.firstOf(doc, listOf("#article", ".txt"))

    /* span.chapter, which carries the heading ONCE. The h4 beside it prints
       it twice inside a single line ("Chapter 26: Chapter 26: Proud") on 5 of
       the 30 captured chapter pages, and sits INSIDE #article on 19 of them —
       so taking the h4 would put a doubled heading at the top of the file and
       leave the body's copy behind it. The only h1 on a chapter page is the
       NOVEL's name, which is why the shared heading chain is no use here. */
    override fun chapterHeading(doc: Document): Element? =
        doc.selectFirst("span.chapter")
}
