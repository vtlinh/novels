package dev.vtlinh.noveldownloader.sites

import dev.vtlinh.noveldownloader.Site
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/* americanliterature.com/books — English, a library of public-domain books.

   THE SHAPE. Finished, published books: no status is printed because none
   is needed, and the table of contents is never split across pages — Don
   Quixote's 131 entries sit on one. What the site does print around every
   chapter is a study kit (FAQ, flashcards, vocabulary, quiz, summary), as
   badges beside each table-of-contents entry and as sub-pages under each
   chapter's url.

   URLS. A book is "/author/<author>/book/<book>" and its chapters are one
   more segment, named however the book names them: "chapter-1-marseilles-
   the-arrival", "chapter-vi", "book-1-chapter-15", "dedication", "vii".
   The study pages hang one segment further down ("/chapter-1/quiz"). The
   site was redesigned in 2026: the table of contents moved from
   ".../summary" — which is now an essay about the book on some titles — to
   the bare book url, which is its canonical address (measured from the
   captured pages' <link rel=canonical>). The www. spelling is accepted, but
   nothing is ever fetched from it: normalize() always answers the apex.

   THE SLUG is the book segment alone, because the base has to contain it.
   Two captured authors share one ("summer"); the app already files novels
   by title, and those two are both called "Summer", so the slug adds no
   collision the folder name does not already have. */
object Americanliterature : Site {

    override val name = "americanliterature"
    override val headingWord = "Chapter"
    override val english = true

    override val listScope = "#chapter-toc-list"

    override val hosts = listOf("americanliterature.com")

    override val paginates = false

    override val publishesUnfinished = false

    /* Measured on a phone: Moby-Dick at the engine's twenty-at-once saved 62
       chapters, then every request was answered 429 and stayed refused
       through all four retry passes. One request every 1.5s is 40 a minute,
       under whatever that burst crossed; a 429 even so doubles it (Pace). */
    override val requestGapMs = 1_500L

    private val urlRe = Regex("^https://(www\\.)?americanliterature\\.com(/|$)", RegexOption.IGNORE_CASE)

    override fun matches(url: String) = urlRe.containsMatchIn(url.trim())

    /* "book", and "novella" for the few the site files as one: The Scarlet
       Plague is listed on /books/ at ".../book/the-scarlet-plague", and its
       page then links every chapter as ".../novella/the-scarlet-plague/…". */
    private val bookRe = Regex("^/author/([^/]+)/(book|novella)/([^/]+)(/|$)")

    /* A short story, an author page or the home page names no book: the
       empty answer is what tells the browser and the share sheet so. */
    override fun normalize(url: String): Pair<String, String> {
        val path = URI(url.trim()).path.orEmpty()
        val m = bookRe.find(path) ?: return Pair("", "")
        val (author, kind, book) = m.destructured
        return Pair("https://americanliterature.com/author/$author/$kind/$book", book)
    }

    /* Never fetched — maxPage is always 1 — but the engine still asks what
       page 2 would be called. */
    override fun listPageUrl(base: String, slug: String, page: Int) = "$base?page=$page"

    /* The segment right under this book, or null when the path is not one
       level below it. */
    private fun childOf(path: String, slug: String): String? {
        val m = bookRe.find(path) ?: return null
        if (m.groupValues[3] != slug) return null
        val rest = path.substring(m.range.last + 1).trimEnd('/')
        if (rest.isEmpty() || rest.contains('/')) return null
        return rest
    }

    private val chapterSeg = Regex("(^|-)chapter-(\\d+)(-|$)")

    /* Strict, for the whole-document fallback only: the header menu links
       one book's chapter ("/the-great-gatsby/chapter-1/") from every page,
       so outside the list a url has to say "chapter-N" to count. */
    override fun isChapterPath(path: String, slug: String): Boolean {
        val seg = childOf(path, slug) ?: return false
        return chapterSeg.containsMatchIn(seg)
    }

    /* Loose, for links inside the table of contents: any single segment
       under the book. "/summary" is the essay about the book, not a part
       of it, and is linked from inside some lists' study badges. */
    override fun isChapterInList(path: String, slug: String): Boolean {
        val seg = childOf(path, slug) ?: return false
        return seg != "summary"
    }

    /* Each entry is <div class="toc-entry"><span class="toc-chapter-link">
       <a>…</a></span><span class="toc-enrichment-badges">…</span></div>.
       Only the chapter link itself is the listing. The badges point at the
       chapter again ("#faq") and at its study pages; and an entry classed
       toc-section-marker is a "PART I" / "Book II - …" heading whose page
       holds a title and no text (17 of them in The Brothers Karamazov). */
    override fun inListing(a: Element): Boolean {
        val entry = a.closest(".toc-entry") ?: return false
        if (entry.hasClass("toc-section-marker")) return false
        return a.parent()?.hasClass("toc-chapter-link") == true
    }

    /* "chapter-59-the-will" says 59, and that is what its page prints. A
       roman numeral in the url ("chapter-vi") is not read: "vi" and "mix"
       are words as well as numbers, and the caller's fallback — the
       chapter's position in the listing — is right anyway. */
    override fun chapterNumFromUrl(url: String): Int? {
        val path = try { URI(url.trim()).path.orEmpty() } catch (e: Exception) { url }
        val seg = path.trimEnd('/').substringAfterLast('/')
        return chapterSeg.find(seg)?.groupValues?.get(2)?.toIntOrNull()
    }

    override fun maxPage(doc: Document, slug: String) = 1

    /* Every book here is a finished, published work. */
    override fun isCompleted(doc: Document) = true

    /* div.book-header h1 > cite holds the bare title; og:title carries the
       same and is the fallback. */
    override fun title(doc: Document) =
        doc.selectFirst(".book-header h1")?.text()?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifEmpty { null }

    override fun author(doc: Document) =
        doc.selectFirst("p.book-byline a")?.text()?.trim()?.ifEmpty { null }

    /* div.book-content is the site's introduction to the book: paragraphs,
       and on some books a bulleted list ("Why This Novel Endures:") whose
       items are kept one to a paragraph. */
    override fun description(doc: Document): String? {
        val el = doc.selectFirst("div.book-content") ?: return null
        val parts = ArrayList<String>()
        for (c in el.children()) {
            if (c.tagName() == "ul" || c.tagName() == "ol") {
                for (li in c.select("li")) parts.add(li.text())
            } else {
                parts.add(c.text())
            }
        }
        val t = parts.map { it.replace('\u00a0', ' ').trim() }.filter { it.isNotEmpty() }
            .joinToString("\n\n")
            .ifEmpty { el.text().replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim() }
        return t.ifEmpty { null }
    }

    private val navRe = Regex("^(previous chapter|next chapter|copy link|\\s)*$", RegexOption.IGNORE_CASE)

    /* article.chapter-content opens with the heading (the chapter's name and
       a link back to the book), a "Copy Link" share button, and the
       Previous / Next Chapter links, each in a div of its own between <hr>s;
       the prose follows as plain <p>s. The study FAQ is outside the article.
       Returns a cleaned copy, so the heading is still there for
       chapterHeading to read — the engine asks for both, in either order. */
    override fun chapterContent(doc: Document): Element? {
        val el = doc.selectFirst("article.chapter-content")?.clone() ?: return null
        for (c in el.children()) {
            when {
                c.tagName() == "h1" || c.tagName() == "hr" -> c.remove()
                c.tagName() == "div" && navRe.matches(c.text()) -> c.remove()
            }
        }
        dropStudyLinks(el)
        return el
    }

    /* Since October the site also prints the chapter's study links inside
       the article — "❓ FAQ", "🃏 Flashcards", "📚 Vocabulary", "✏ Quiz",
       "📄 Summary" — and they were saved as the chapter's opening lines. No
       capture holds that markup (the archive's latest predates it and the
       live site refuses this sandbox), so it is matched on what it must be
       rather than on a class name: a link to a study page that reads as
       nothing but a study label, or a wrapper that reads as nothing but
       those labels, and whatever held them left with no words of its own.
       Nothing inside a <p> — a word of prose is still prose, even when it
       is "Summary" or links to one. */
    private val studyHref = Regex("(/(flashcards|vocabulary|vocab|quiz|summary)/?|#faq)$", RegexOption.IGNORE_CASE)
    private val studyText = Regex(
        "^(?:[^\\p{L}\\p{N}]*(?:faq|flashcards|fc|vocab(?:ulary)?|voc|quiz|summary|sum)(?![\\p{L}\\p{N}]))+[^\\p{L}\\p{N}]*$",
        RegexOption.IGNORE_CASE,
    )
    private val wordRe = Regex("[\\p{L}\\p{N}]")

    private fun dropStudyLinks(el: Element) {
        val links = el.select("a[href]").filter {
            studyHref.containsMatchIn(it.attr("href").substringBefore('?')) && studyText.matches(it.text())
        }
        val wrappers = el.select("div, nav, ul, ol, li, section, aside, span").filter {
            studyText.matches(it.text())
        }
        for (s in links + wrappers) {
            if (s.closest("p") != null || s.parent() == null) continue
            var up = s.parent()
            s.remove()
            while (up != null && up !== el && up.parent() != null && !wordRe.containsMatchIn(up.text())) {
                val next = up.parent()
                up.remove()
                up = next
            }
        }
    }

    /* The chapter's name is the first <span> of the article's h1 — the h1
       also holds the BOOK's name, in a.chapter-book-link beside it. Its
       text is the table-of-contents entry, in every shape the books use:
       "Chapter 1", "Chapter 1. Marseilles -- The Arrival.", "Chapter VI",
       "CHAPTER I.", "Ch. 2: Hunting-Song of the Seeonee Pack", and plenty
       with no number at all ("Dedication", "PART I - CHAPTER I.", "Book 1
       Chapter 15"). The shared heading parser reads only "Chapter <digits>"
       followed by ":" or "-", so a LEADING chapter number is restated in
       that form here — roman numerals and a "." separator included — and
       anything else is passed through as the page prints it. Except: that
       parser also finds "Chapter <digits>" mid-line, and would read "Book 1
       Chapter 15" as chapter 15 with no name, dropping "Book 1" — a book
       whose chapters restart per part would lose which part each is in. So
       such a heading is kept whole, as the name. */
    override fun chapterHeading(doc: Document): Element? {
        val span = doc.selectFirst("article.chapter-content h1 > span") ?: return null
        val text = span.text().trim()
        if (text.isEmpty()) return null
        return Element(span.tagName()).text(restate(text))
    }

    private val leadRe = Regex(
        "^(?:chapter|ch\\.)\\s*([0-9]+|[ivxlcdm]+)(?![\\p{L}\\d])\\s*[.:\\-–—]*\\s*(.*)$",
        RegexOption.IGNORE_CASE,
    )

    private val innerRe = Regex("chapter\\s*(\\d+)", RegexOption.IGNORE_CASE)

    internal fun restate(text: String): String {
        val m = leadRe.find(text)
            ?: return innerRe.find(text)?.let { "Chapter ${it.groupValues[1]}: $text" } ?: text
        val n = m.groupValues[1].toIntOrNull() ?: roman(m.groupValues[1]) ?: return text
        val rest = m.groupValues[2].trim()
        return if (rest.isEmpty()) "Chapter $n" else "Chapter $n: $rest"
    }

    private val romanRe = Regex("^M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$")

    /* Canonical numerals only, so "Chapter Did…" is a title and not 1001. */
    private fun roman(s: String): Int? {
        val u = s.uppercase()
        if (u.isEmpty() || !romanRe.matches(u)) return null
        val v = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        for (i in u.indices) {
            val cur = v.getValue(u[i])
            val next = if (i + 1 < u.length) v.getValue(u[i + 1]) else 0
            total += if (cur < next) -cur else cur
        }
        return total
    }
}
