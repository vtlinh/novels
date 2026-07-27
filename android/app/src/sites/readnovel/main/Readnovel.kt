package dev.vtlinh.noveldownloader.sites

import dev.vtlinh.noveldownloader.Site
import dev.vtlinh.noveldownloader.SiteHelp
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/* read-novel.com — English, paginated as ?p=N.

   A novel is "/novel<id>-<name>.html" and its chapters are
   "/novel<id>-<name>/chapter<i>-<title>.html", so the id is part of the slug
   and the same string addresses both. That makes this the simple case
   truyenfullmoi is not: one slug, no second identifier to carry.

   What is NOT simple here is the chapter number — see chapterNumFromUrl. */
object Readnovel : Site {

    override val name = "readnovel"
    override val headingWord = "Chapter"
    override val english = true

    /* The REAL index. This page carries two ul.list-chapter: one inside
       #new-chapter, which is a six-link "latest chapters" widget, and one
       inside #list-chapter, which is the listing. Both were present on all
       100 captured pages, so a selector on the shared class would have picked
       whichever came first in the document and called six links a novel. */
    override val listScope = "#list-chapter"

    override val hosts = listOf("read-novel.com")

    private val urlRe = Regex("^https://read-novel\\.com/", RegexOption.IGNORE_CASE)

    override fun matches(url: String) = urlRe.containsMatchIn(url.trim())

    override fun normalize(url: String): Pair<String, String> {
        val u = URI(url.trim())
        val slug = u.path.orEmpty().trimStart('/').substringBefore('/').removeSuffix(".html")
        return Pair("https://${u.host}/$slug.html", slug)
    }

    override fun listPageUrl(base: String, slug: String, page: Int) = "$base?p=$page"

    /* Every chapter link on every captured listing is "chapter<i>-…", so the
       strict rule can insist on it — which is what keeps the "latest
       chapters" widget's identically-shaped links out when the listing
       container is missing and the whole document is read instead. */
    override fun isChapterPath(path: String, slug: String) =
        path.contains("/$slug/") &&
            Regex("/chapter\\d+-", RegexOption.IGNORE_CASE).containsMatchIn(path)

    override fun maxPage(doc: Document, slug: String) =
        SiteHelp.highestLink(doc, Regex("/" + Regex.escape(slug) + "\\.html\\?p=(\\d+)"))

    /* THE NUMBER IN THE URL IS NOT THE CHAPTER NUMBER. The leading
       "chapter<i>" is the chapter's zero-based POSITION in the listing, and
       the number the page prints is a different thing that appears — when it
       appears at all — inside the title slug that follows:

         /chapter0-chapter-1-the-servant-who-sweeps.html   prints Chapter 1
         /chapter8-chapter-9-nails-and-wooden-planks.html  prints Chapter 9
         /chapter49-chapter-50-47-martial-arts-….html      prints Chapter 50

       Reading the leading number would have disagreed with the printed one on
       26 of the 27 captured chapters that print a number — and that number is
       written into the saved file's heading and is the only input to the
       legacy rename guess. So: step over the positional prefix, then take the
       "chapter-N" from the remainder, and answer null when there is none.
       Three of the 30 captured chapters are named with no number anywhere,
       which is a fine answer — the caller falls back to the chapter's
       position in the listing, which is the site's own answer. */
    override fun chapterNumFromUrl(url: String): Int? {
        val seg = url.substringBefore('?').trimEnd('/')
            .substringAfterLast('/').removeSuffix(".html")
        val rest = Regex("^chapter\\d+-(.*)$", RegexOption.IGNORE_CASE)
            .find(seg)?.groupValues?.get(1) ?: seg
        return Regex("(?:^|-)chapter-(\\d+)(?:-|$)", RegexOption.IGNORE_CASE)
            .find(rest)?.groupValues?.get(1)?.toIntOrNull()
    }

    /* THE TEXT, NOT THE CLASS. Finished and running novels both print their
       status in <span class="text-primary"> here — 23 "Completed" and 77
       "Ongoing" across the captured corpus, all in the same class. The
       neighbouring sites mark a finished novel with text-success, and reusing
       that rule would have read every novel on this one as still running,
       which never stops sweeping a finished book. */
    override fun isCompleted(doc: Document) =
        doc.select(".info .info-chitiet span[class*=text-]").any {
            val t = it.text().trim()
            t.equals("Completed", true) || t.equals("Complete", true) ||
                t.equals("Full", true) || t.equals("Finished", true)
        }

    /* No og:title on any of the 100 captured pages, and the h1 is the bare
       novel name — measured at a median 0.7x the length of its slug, so
       nothing is wrapped around it. */
    override fun title(doc: Document) =
        doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null }
            ?: SiteHelp.metaTitle(doc)

    override fun author(doc: Document) =
        doc.selectFirst("a[itemprop=author]")?.text()?.trim()?.ifEmpty { null }

    override fun chapterContent(doc: Document) =
        SiteHelp.firstOf(doc, listOf(".chapter-content", "#chapter-c", ".chapter-c"))

    /* a.chapter-title and nothing looser. The shared heading list resolves in
       DOCUMENT order, and on every one of the 30 captured chapter pages the
       first element it reaches is a.truyen-title — the NOVEL's name, sitting
       in an h2 above the chapter's own heading. Using it would have put the
       book's title at the top of every chapter file in place of the
       chapter's. */
    override fun chapterHeading(doc: Document): Element? =
        doc.selectFirst("a.chapter-title")
}
