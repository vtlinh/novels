package dev.vtlinh.noveldownloader.sites

import dev.vtlinh.noveldownloader.Listing
import dev.vtlinh.noveldownloader.Site
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI

/* gutenberg.org — Project Gutenberg, the oldest library of public-domain
   ebooks: some 78,000 books, four in five of them English, every one
   finished.

   URLS. A book is "/ebooks/<number>", and its formats hang off the same
   number: "/ebooks/2701.epub3.images", "/cache/epub/2701/pg2701-images.html",
   "/files/2701/". The number is the slug — it is the only name the site
   gives a book, and its title is shared: there are three Moby Dicks.

   THE WHOLE BOOK IS ONE PAGE. The book page carries the title, author,
   subjects and summary and no chapters; "Read online" is a single HTML file
   of the entire text, from a short story to War and Peace's four megabytes.
   It is hand-made per book by its volunteers, so its contents list is no
   guide — links to the chapters, to footnotes and to illustrations look
   alike, and a quarter of the captured books have none at all. What every
   book does use is headings, so the chapters are read from those
   (`layout`), each one a fragment of that one url: "pg2701-images.html
   #section-12". The engine fetches the page once and cuts each section out
   of it (Site.chaptersShareAPage).

   No rate limit is published, and robots.txt asks only that the search not
   be crawled; the site does turn away automated traffic, so one request a
   second, and a book is two of them however long it is. */
object Gutenberg : Site {

    override val name = "gutenberg"
    override val headingWord = "Chapter"

    /* The other fifth — French, Finnish, German — is judged by its own
       text, as on every site: this only says what the site mostly carries. */
    override val english = true

    /* The chapters are the book page's headings (`chapterList`); this is
       only where the collector would look if that page were something else. */
    override val listScope = "body"

    override val hosts = listOf("www.gutenberg.org")

    override val paginates = false

    override val publishesUnfinished = false

    override val requestGapMs = 1000L

    /* The front page is news and search; the categories are the shelves. */
    override val startPath = "/ebooks/categories"

    override val chaptersShareAPage = true

    private val urlRe = Regex("^https?://(www\\.)?gutenberg\\.org(/|$)", RegexOption.IGNORE_CASE)

    override fun matches(url: String) = urlRe.containsMatchIn(url.trim())

    /* The book's number, from any of its own urls: the book page and its
       formats, the read-online file and the file directory. A category,
       an author or a search names no book. */
    private val bookPath = Regex("^/(?:ebooks/(\\d+)(?:\\.[a-z0-9.-]+)?|cache/epub/(\\d+)/.*|files/(\\d+)(?:/.*)?)/?$")

    override fun normalize(url: String): Pair<String, String> {
        val path = try { URI(url.trim()).path.orEmpty() } catch (e: Exception) { "" }
        val m = bookPath.matchEntire(path) ?: return Pair("", "")
        val id = m.groupValues.drop(1).first { it.isNotEmpty() }
        return Pair("https://www.gutenberg.org/ebooks/$id", id)
    }

    override fun listingUrl(base: String, slug: String) =
        "https://www.gutenberg.org/cache/epub/$slug/pg$slug-images.html"

    /* Never fetched — maxPage is always 1 — but the engine still asks what
       page 2 would be called. */
    override fun listPageUrl(base: String, slug: String, page: Int) =
        "${listingUrl(base, slug)}?page=$page"

    /* No link is ever a chapter here; the list is the page's headings. */
    override fun isChapterPath(path: String, slug: String) = false

    override fun isChapterInList(path: String, slug: String) = false

    /* A fragment counts sections, not chapters: the contents and the title
       page are sections too. */
    override fun chapterNumFromUrl(url: String): Int? = null

    override fun maxPage(doc: Document, slug: String) = 1

    override fun isCompleted(doc: Document) = true

    override fun title(doc: Document) =
        doc.selectFirst("table.bibrec td[itemprop=headline]")?.text()?.trim()?.ifEmpty { null }

    /* The catalogue files a name as "Melville, Herman, 1819-1891": read as
       "Herman Melville". A book of verse or a magazine has an editor
       instead, who is named the same way. */
    override fun author(doc: Document): String? {
        val names = doc.select("table.bibrec a[rel=marcrel:aut]").ifEmpty {
            doc.select("table.bibrec a[rel=marcrel:edt]")
        }
        return names.map { personName(it.text()) }.filter { it.isNotEmpty() }
            .distinct().joinToString(", ").ifEmpty { null }
    }

    private val dates = Regex("\\d|\\bBCE?\\b|^-|^\\?|\\bfl\\.|\\bactive\\b")

    internal fun personName(catalogued: String): String {
        val parts = catalogued.split(", ").map { it.trim() }.filter { it.isNotEmpty() }
            .takeWhile { !dates.containsMatchIn(it) }
        return when (parts.size) {
            0 -> catalogued.trim()
            1 -> parts[0]
            else -> "${parts[1]} ${parts[0]}"
        }
    }

    override fun genres(doc: Document): String? =
        doc.select("table.bibrec td[property=dcterms:subject] a").map { it.text().trim() }
            .filter { it.isNotEmpty() }.distinct().joinToString(", ").ifEmpty { null }

    /* The summary above the download links, without its "Read more"
       toggle. Generated by the site, and it says so; the reader is told on
       the book page, not in the app. */
    override fun description(doc: Document): String? {
        val el = doc.selectFirst(".summary-text-container")?.clone() ?: return null
        el.select("label, input").remove()
        return el.text().replace("(This is an automatically generated summary.)", "")
            .replace('\u00a0', ' ').trim().ifEmpty { null }
    }

    /* ---- the book page: its sections ---- */

    private val sectionTags = setOf("h1", "h2", "h3")

    /* Page numbers from the printed edition, "[Pg 12]" or "{ix}" in the
       margin: every book that keeps them marks them with one of these. */
    private fun isPageNumber(e: Element) =
        e.classNames().any { "pagenum" in it || it == "pageno" }

    private fun skipped(e: Element) =
        isPageNumber(e) || e.hasClass("pg-boilerplate") || e.tagName() == "script" || e.tagName() == "style"

    private class Section(val heads: MutableList<Element>) {
        var chars = 0
        var linkChars = 0
    }

    private class Layout(val sections: List<Section>, val kept: List<Int>, val footer: Element?)

    private fun visible(s: String) = s.count { !it.isWhitespace() }

    /* The book cut at its headings. A heading begins a section; a run of
       headings with no text between them is ONE section's heading — "CHAPTER
       I" over "Nature or Nurture?", a title over its byline, "BOOK ONE"
       over its first chapter. h1 to h3: a third of the captured books head
       most of their chapters with h3, under an h2 for the volume or the
       act. Section 0 is
       whatever comes before the first heading — the whole book, in the one
       captured book with no headings at all. */
    private fun cut(body: Element): List<Section> {
        val sections = mutableListOf(Section(mutableListOf()))
        var cur = sections[0]
        var between = 0
        fun walk(node: Node, inLink: Boolean, minor: Boolean) {
            for (ch in node.childNodes()) {
                when (ch) {
                    is TextNode -> {
                        val n = visible(ch.wholeText)
                        cur.chars += n
                        if (inLink) cur.linkChars += n
                        if (!minor) between += n
                    }
                    is Element -> {
                        if (skipped(ch)) continue
                        if (ch.tagName() in sectionTags) {
                            if (cur.heads.isNotEmpty() && between == 0) {
                                cur.heads.add(ch)
                            } else {
                                cur = Section(mutableListOf(ch))
                                sections.add(cur)
                            }
                            between = 0
                        } else {
                            walk(
                                ch,
                                inLink || (ch.tagName() == "a" && ch.hasAttr("href")),
                                minor || ch.tagName() in minorTags,
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
        walk(body, false, false)
        return sections
    }

    /* Headings too small to begin a section, which do not part two that
       do either: a title page's "BY" between the title and the author. */
    private val minorTags = setOf("h4", "h5", "h6")

    /* A contents list with no links in it, by its heading — exactly that
       and nothing else, so "The Riches of Content" is still a chapter. The
       words the captured books use for it. */
    private val contentsNames = setOf(
        "contents", "tableofcontents", "listofchapters", "illustrations", "listofillustrations",
        "index", "inhalt", "inhaltsverzeichnis", "indice", "índice", "tabledesmatières",
        "sisällys", "tartalom", "contenido", "inhoud",
    )

    private fun isContents(s: Section) =
        s.heads.joinToString("") { headText(it) }.lowercase().filter { it.isLetter() } in contentsNames

    /* Which sections are chapters.

       Not a contents list: one whose text is at least half links, or one
       headed with nothing but the word for it (`contentsNames`) — over half
       the captured contents are plain text. No chapter comes near half
       links; the footnote markers in a chapter are a sliver of it. The
       title page that so often carries the contents goes with them.

       Not the title page when it is all there is of it: the section before
       the first heading, or the one the title heads, under a thousand
       characters and with the book going on after it — the publisher, the
       edition, a dedication. When nothing else follows, it IS the book: a
       short story is one heading and its text. A title page that runs
       straight into the first chapter, with no text between, is that
       chapter and stays. */
    private fun layout(doc: Document): Layout? {
        synchronized(this) { last?.let { if (it.first === doc) return it.second } }
        val body = doc.body() ?: return null
        if (doc.getElementById("pg-header") == null) return null
        val sections = cut(body)
        val listed = sections.indices.filter { i ->
            val s = sections[i]
            (s.chars > 0 || s.heads.isNotEmpty()) && !(s.chars > 0 && s.linkChars * 2 >= s.chars) &&
                !isContents(s)
        }
        val firstH1 = body.select("h1").firstOrNull { el -> el.parents().none { skipped(it) } }
        fun titlePage(i: Int): Boolean {
            val s = sections[i]
            if (s.chars >= 1000) return false
            if (i == 0) return true
            if (firstH1 == null || firstH1 !in s.heads) return false
            return s.heads.none { numbered(headText(it)) != null }
        }
        val kept = listed.filterIndexed { k, i -> !(k < listed.size - 1 && titlePage(i)) }
        val layout = Layout(sections, kept, doc.getElementById("pg-footer"))
        synchronized(this) { last = Pair(doc, layout) }
        return layout
    }

    /* The page the engine is cutting chapters from, its layout read once. */
    private var last: Pair<Document, Layout>? = null

    override fun chapterList(doc: Document, slug: String): List<Listing.Link>? {
        val layout = layout(doc) ?: return null
        val page = doc.location().substringBefore('#')
        return layout.kept.map { i -> Listing.Link("$page#section-$i", label(doc, layout.sections[i])) }
    }

    /* A section with no heading of its own — text printed before the
       book's first — is named after the book. */
    private fun label(page: Document, s: Section): String = heading(s).ifEmpty {
        page.selectFirst("meta[name=dc.title]")?.attr("content")?.trim()?.ifEmpty { null }
            ?: page.title().trim().ifEmpty { null }
            ?: page.selectFirst("h1")?.let { headText(it) }.orEmpty()
    }

    private val fragmentRe = Regex("#section-(\\d+)$")

    private fun sectionIndex(url: String) = fragmentRe.find(url)?.groupValues?.get(1)?.toIntOrNull()

    /* The section copied out of the page: everything from its first heading
       up to the next section's, or to the site's footer, with the heading
       itself left out and kept as an attribute. A section the page does not
       have is null. */
    private fun render(page: Document, url: String): Element? {
        val layout = layout(page) ?: return null
        val i = sectionIndex(url) ?: return null
        val s = layout.sections.getOrNull(i) ?: return null
        val out = Element("div").attr("data-gutenberg-section", "$i").attr("data-heading", label(page, s))
        val end = layout.sections.getOrNull(i + 1)?.heads?.first() ?: layout.footer
        val start: Node = s.heads.firstOrNull()
            ?: page.getElementById("pg-header")?.nextSibling()
            ?: return out
        copyRange(start, end, page.body(), s.heads.toSet(), out)
        return out
    }

    private fun isAncestor(a: Node, b: Node): Boolean {
        var p = b.parentNode()
        while (p != null) {
            if (p === a) return true
            p = p.parentNode()
        }
        return false
    }

    /* Copies of every node from `from` up to `to` in document order, the
       ancestors they share flattened away: a chapter wrapped in its own
       <div> and one laid flat between headings come out the same. */
    private fun copyRange(from: Node, to: Node?, root: Element, leave: Set<Element>, out: Element) {
        fun take(n: Node) {
            if (n is Element && (n in leave || skipped(n))) return
            if (n is Comment) return
            out.appendChild(n.clone())
        }
        fun into(container: Node): Boolean {
            for (c in container.childNodes()) {
                if (c === to) return true
                if (to != null && isAncestor(c, to)) {
                    into(c)
                    return true
                }
                take(c)
            }
            return false
        }
        var cur: Node = from
        var n: Node? = from
        while (true) {
            while (n != null) {
                if (n === to) return
                if (to != null && isAncestor(n, to)) {
                    into(n)
                    return
                }
                take(n)
                n = n.nextSibling()
            }
            val p = cur.parentNode() ?: return
            if (p === root) return
            cur = p
            n = p.nextSibling()
        }
    }

    override fun section(page: Document, url: String): String? = render(page, url)?.outerHtml()

    /* The chapter the engine was handed: a section cut by `section`, or —
       read in place — the section of the whole page its url names. */
    private fun sectionOf(doc: Document): Element? =
        if (doc.getElementById("pg-header") == null) doc.selectFirst("div[data-gutenberg-section]")
        else render(doc, doc.location())

    /* ---- headings ---- */

    /* A heading's own words: the margin's page number and an illustration's
       caption printed inside it left out, and its picture's description
       when a picture is all it is ("PREFACE." drawn rather than set). */
    private fun headText(h: Element): String {
        val c = h.clone()
        c.select("*").filter { it !== c && (isPageNumber(it) || it.hasClass("caption")) }.forEach { it.remove() }
        val t = c.text().replace('\u00a0', ' ').trim()
        if (t.isNotEmpty()) return t
        return c.select("img[alt]").joinToString(" ") { it.attr("alt").trim() }.trim()
    }

    /* "CHAPTER III.", "Chapter 1. Loomings.", "XIV", "Chapitre II": the
       number and whatever title follows. A bare numeral has to stand alone
       or end in a stop, so "I Have a Dream" is not chapter one. */
    private val numberedRe = Regex(
        "^(?:(?i:chapter|chapitre|kapitel|cap[ií]tulo|capitolo|hoofdstuk|luku|fejezet)\\s+([0-9]+|(?i:[IVXLCDM]+))\\b[.:]?|" +
            "([0-9]+|[IVXLCDM]+)(?:[.:)]|$))\\s*(.*)$",
    )

    private fun numbered(text: String): Pair<Int, String>? {
        val m = numberedRe.find(text) ?: return null
        val raw = m.groupValues[1].ifEmpty { m.groupValues[2] }
        val n = raw.toIntOrNull() ?: roman(raw.uppercase()) ?: return null
        return Pair(n, m.groupValues[3].trim().trimStart('—', '–', '-', ':', '.', ' '))
    }

    /* One line for a section's run of headings. The numbered one, and what
       follows it, when there is one — "Chapter 1: Nature or Nurture?" over
       the title and byline it ran on from; the book's title for its title
       page; otherwise all of them, as an act and its scene are. */
    private fun heading(s: Section): String {
        val parts = s.heads.map { headText(it) }.filter { it.isNotEmpty() }
        val at = parts.indexOfFirst { numbered(it) != null }
        if (at >= 0) {
            val (n, rest) = numbered(parts[at])!!
            val title = (listOf(rest) + parts.drop(at + 1)).filter { it.isNotEmpty() }.joinToString(": ")
            return if (title.isEmpty()) "Chapter $n" else "Chapter $n: $title"
        }
        val first = s.heads.firstOrNull()
        if (first?.tagName() == "h1") return headText(first).ifEmpty { parts.joinToString(": ") }
        return parts.joinToString(": ")
    }

    /* ---- the chapter ---- */

    /* The section's text. A heading with nothing under it — "VOLUME ONE"
       just before the footer — keeps its heading as its text, since a page
       with nothing in it fails to save on every run. */
    override fun chapterContent(doc: Document): Element? {
        val s = sectionOf(doc)?.clone() ?: return null
        s.select("*").filter { it !== s && isPageNumber(it) }.forEach { it.remove() }
        reflow(s)
        if (s.text().isNotBlank()) return s
        val label = s.attr("data-heading").ifEmpty { return s }
        return Element("div").appendChild(Element("p").text(label))
    }

    /* The files wrap their paragraphs at seventy-odd columns, and the saved
       text takes the page's own line breaks as its lines: every paragraph
       would be a dozen lines broken mid-sentence. Joined back up — except
       in <pre>, where the breaks are the text — and every block that is not
       a paragraph given a line of its own, since a line of verse is as often
       a <div> as a <br>. */
    private fun reflow(root: Element) {
        val ws = Regex("\\s+")
        for (e in root.select("*")) {
            if (e.tagName() == "pre" || e.parents().any { it.tagName() == "pre" }) continue
            for (t in e.textNodes()) t.text(t.wholeText.replace(ws, " "))
        }
        for (e in root.select("*")) {
            if (e === root || !e.isBlock || e.tagName() == "p") continue
            e.before(TextNode("\n"))
            e.after(TextNode("\n"))
        }
    }

    override fun chapterHeading(doc: Document): Element? {
        val label = sectionOf(doc)?.attr("data-heading")?.ifEmpty { null } ?: return null
        return Element("p").text(label)
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
