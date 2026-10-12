package dev.vtlinh.noveldownloader.sites

import dev.vtlinh.noveldownloader.Site
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/* standardebooks.org — English, a volunteer-run library of public-domain
   ebooks, each one finished and proofread.

   URLS. A book is "/ebooks/<author>/<title>", with a third segment for a
   translated or illustrated edition ("/ebooks/leo-tolstoy/war-and-peace/
   louise-maude_aylmer-maude"). Its table of contents is "<book>/text" and
   every entry is one file under it: "<book>/text/chapter-12",
   "<book>/text/dedication", "<book>/text/book-1". The site drops a trailing
   slash with a redirect, so no url here ends in one.

   THE LISTING is a page of its own. The book page carries the title,
   author, cover and blurb and not one chapter link — see Site.listingUrl.

   THE SLUG is the book's own identifier with "/" read as "_", which is also
   the name of its source repository on GitHub. The title segment alone is
   no name at all: 75 books in the catalogue are called "short-fiction" and
   59 "poetry".

   THE HONEYPOT. Every reading page carries a hidden "/honeypot" link, and a
   request for it bans the address for 24 hours. The list is read from the
   table of contents' own <nav>, and nothing but this book's own reading
   pages is ever a chapter, even when a page is read whole. */
object Standardebooks : Site {

    override val name = "standardebooks"
    override val headingWord = "Chapter"
    override val english = true

    override val listScope = "nav#toc"

    override val hosts = listOf("standardebooks.org")

    override val paginates = false

    override val publishesUnfinished = false

    /* Static pages with no rate limit of their own (only the ebook-file
       downloads are limited), but run by volunteers: two pages a second
       rather than the engine's twenty at once. */
    override val requestGapMs = 500L

    /* The front page is the project; the books are here. */
    override val startPath = "/ebooks"

    private val urlRe = Regex("^https://(www\\.)?standardebooks\\.org(/|$)", RegexOption.IGNORE_CASE)

    override fun matches(url: String) = urlRe.containsMatchIn(url.trim())

    /* The book's identifier and what follows it. Two segments, or three for
       an edition; the reading pages and downloads hang off "text" and
       "downloads", which no author, title or translator segment is ever
       called. */
    private fun split(path: String): Pair<String, List<String>>? {
        val segs = path.split('/').filter { it.isNotEmpty() }
        if (segs.firstOrNull() != "ebooks") return null
        val rest = segs.drop(1)
        val cut = rest.indexOfFirst { it == "text" || it == "downloads" }
        val id = if (cut < 0) rest else rest.subList(0, cut)
        if (id.size !in 2..3) return null
        return Pair(id.joinToString("/"), if (cut < 0) emptyList() else rest.subList(cut, rest.size))
    }

    /* An author page, a collection or the catalogue names no book: the empty
       answer is what tells the browser and the share sheet so. */
    override fun normalize(url: String): Pair<String, String> {
        val path = URI(url.trim()).path.orEmpty()
        val (id, _) = split(path) ?: return Pair("", "")
        return Pair("https://standardebooks.org/ebooks/$id", id.replace('/', '_'))
    }

    override fun listingUrl(base: String, slug: String) = "$base/text"

    /* Never fetched — maxPage is always 1 — but the engine still asks what
       page 2 would be called. */
    override fun listPageUrl(base: String, slug: String, page: Int) = "$base/text?page=$page"

    /* The edition's furniture, and pages the reader has no use for: the
       title pages and imprint, the colophon and the public-domain
       dedication, a list of the illustrations, and a frontispiece, which is
       a picture and nothing to read. The endnotes go too: the markers that
       point at them are taken out of the chapters, and without those they
       are numbered notes about nothing. */
    private val furniture = setOf(
        "titlepage", "halftitlepage", "imprint", "colophon", "uncopyright",
        "loi", "frontispiece", "endnotes", "single-page",
    )

    /* The file a reading-page url names under this book, or null. */
    private fun fileOf(path: String, slug: String): String? {
        val (id, rest) = split(path) ?: return null
        if (id.replace('/', '_') != slug) return null
        if (rest.size != 2 || rest[0] != "text") return null
        return rest[1]
    }

    override fun isChapterPath(path: String, slug: String): Boolean {
        val file = fileOf(path, slug) ?: return false
        return file !in furniture
    }

    private val chapterFile = Regex("^chapter-(\\d+)$")

    /* "chapter-12" is the book's twelfth chapter and prints XII. A book
       that restarts its numbering names its files "chapter-2-12" — book 2,
       chapter 12 — and that is not read: "12" would be the twelfth chapter
       of every book in it. */
    override fun chapterNumFromUrl(url: String): Int? {
        val path = try { URI(url.trim()).path.orEmpty() } catch (e: Exception) { url }
        return chapterFile.find(path.substringAfterLast('/'))?.groupValues?.get(1)?.toIntOrNull()
    }

    override fun maxPage(doc: Document, slug: String) = 1

    /* Every book here is finished; the site carries nothing serialised. */
    override fun isCompleted(doc: Document) = true

    override fun title(doc: Document) =
        doc.selectFirst("article.ebook h1[property=schema:name]")?.text()?.trim()?.ifEmpty { null }

    /* Each author is a link in the header under the title; a book can have
       more than one (English as She Is Spoke has two). */
    override fun author(doc: Document): String? =
        doc.select("article.ebook > header [property=schema:author] > [property=schema:name]")
            .map { it.text().trim() }.filter { it.isNotEmpty() }
            .joinToString(", ").ifEmpty { null }

    override fun genres(doc: Document): String? =
        doc.select("article.ebook ul.tags a").map { it.text().trim() }.filter { it.isNotEmpty() }
            .joinToString(", ").ifEmpty { null }

    override fun description(doc: Document): String? {
        val el = doc.selectFirst("article.ebook [property=schema:description]") ?: return null
        val t = el.select("p").map { it.text().replace('\u00a0', ' ').trim() }
            .filter { it.isNotEmpty() }.joinToString("\n\n")
            .ifEmpty { el.text().replace('\u00a0', ' ').trim() }
        return t.ifEmpty { null }
    }

    /* ---- the chapter page ---- */

    private fun epubType(e: Element) = e.attr("epub:type").split(' ')

    private val headingTags = setOf("hgroup", "h1", "h2", "h3", "h4", "h5", "h6")

    /* The page's own heading: the first heading in <main>, when nothing has
       been printed before it. A dedication or an epigraph has none — and
       neither does a page of several pieces side by side: the Rubáiyát's
       file is 101 quatrains, each an <article> headed by its numeral, and
       quatrain I's "I" names the quatrain, not the page. */
    private fun leadHeading(main: Element): Element? {
        val h = main.allElements.firstOrNull { it.tagName() in headingTags } ?: return null
        if (!main.text().trim().startsWith(h.text().trim())) return null
        var up = h.parent()
        while (up != null && up !== main) {
            val tag = up.tagName()
            if ((tag == "section" || tag == "article") &&
                up.siblingElements().any { it.tagName() == tag }
            ) return null
            up = up.parent()
        }
        return h
    }

    /* The superscript note numbers, which link to the endnotes. Read in the
       middle of a sentence they are stray digits. */
    private fun dropNoteRefs(el: Element) {
        for (a in el.select("a[href]")) if ("noteref" in epubType(a)) a.remove()
    }

    /* "I: Loomings" from an hgroup of the ordinal and the title — the form
       the site gives every page its <title> in. */
    private fun headingText(h: Element): String =
        if (h.tagName() == "hgroup") {
            h.children().map { it.text().trim() }.filter { it.isNotEmpty() }.joinToString(": ")
        } else {
            h.text().trim()
        }

    /* <main> is the book's own text, and the site's header and footer
       navigation sit outside it. The heading is taken off, since the saved
       file writes its own.

       A page that is ONLY its heading is a divider — "Book I", "Part III:
       1812" — with the chapters of that part in files of their own. 150 of
       them in 30 of the captured books, two with an epigraph or a paragraph
       under the heading, so they are kept as entries rather than left out:
       they are where a long book says which part the reader is in. Saved
       with the heading as their text, since a page with nothing in it
       fails to save on every run. */
    override fun chapterContent(doc: Document): Element? {
        val main = doc.selectFirst("main")?.clone() ?: return null
        dropNoteRefs(main)
        val head = leadHeading(main)
        val label = head?.let { headingText(it) }
        head?.remove()
        /* The source puts a heading's parts on lines of their own, and the
           page's line breaks are kept: a play's "Scene" and "I" were saved
           as two lines. */
        for (h in main.select("h1, h2, h3, h4, h5, h6")) h.text(h.text())
        if (label == null || main.text().isNotBlank()) return main
        return Element("div").appendChild(Element("p").text(label))
    }

    /* The page's heading, with a leading chapter number restated in the
       "Chapter N" form the shared parser reads: "I: Loomings" becomes
       "Chapter 1: Loomings", and "Book I" or "Etymology" are left as they
       are. A page with no heading is named by its <title>, which the site
       writes as "<book> - <entry>". */
    override fun chapterHeading(doc: Document): Element? {
        val main = doc.selectFirst("main")?.clone() ?: return null
        dropNoteRefs(main)
        val text = leadHeading(main)?.let { headingText(it) }?.ifEmpty { null }
            ?: doc.title().substringAfter(" - ", "").trim().ifEmpty { null }
            ?: return null
        return Element("p").text(restate(text))
    }

    /* Upper-case numerals only, as the site prints them, so a title that
       is a word ("Mix", "Did") is never read as a number. */
    private val leadRe = Regex("^(?:(?i:chapter)\\s+)?([0-9]+|[IVXLCDM]+)(?:\\s*[:.]\\s*(.*))?$")

    internal fun restate(text: String): String {
        val m = leadRe.find(text) ?: return text
        val n = m.groupValues[1].toIntOrNull() ?: roman(m.groupValues[1]) ?: return text
        val rest = m.groupValues[2].trim()
        return if (rest.isEmpty()) "Chapter $n" else "Chapter $n: $rest"
    }

    private val romanRe = Regex("^M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$")

    private fun roman(s: String): Int? {
        if (s.isEmpty() || !romanRe.matches(s)) return null
        val v = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        for (i in s.indices) {
            val cur = v.getValue(s[i])
            val next = if (i + 1 < s.length) v.getValue(s[i + 1]) else 0
            total += if (cur < next) -cur else cur
        }
        return total
    }
}
