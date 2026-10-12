package dev.vtlinh.noveldownloader.sites

import dev.vtlinh.noveldownloader.Extractor
import dev.vtlinh.noveldownloader.Listing
import dev.vtlinh.noveldownloader.SiteContract
import dev.vtlinh.noveldownloader.Sites
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmericanliteratureTest : SiteContract() {
    override val site = Sites.all.first { it.name == "americanliterature" }

    @Test
    fun `it is an English site`() {
        assertTrue(site.english)
        assertEquals("Chapter", site.headingWord)
    }

    @Test
    fun `the host is recognised in both spellings, and only it`() {
        assertTrue(site.matches("https://americanliterature.com/author/hg-wells/book/the-invisible-man"))
        assertTrue(site.matches("https://www.americanliterature.com/author/hg-wells/book/the-invisible-man"))
        assertFalse(site.matches("https://americanliterature.com.evil.com/author/x/book/y"))
        assertFalse(site.matches("https://novelfull.com/the-mech-touch.html"))
    }

    /* Every url a user can be looking at names the same book: the bare book
       page, the essay at /summary that replaced the old contents page, a
       chapter, and a chapter's study pages. A www. url is answered with the
       apex, the only spelling measured. */
    @Test
    fun `every page of a book normalises to the bare book url`() {
        val base = "https://americanliterature.com/author/alexandre-dumas/book/the-count-of-monte-cristo"
        for (u in listOf(
            base,
            "$base/",
            "$base/summary",
            "$base/chapter-59-the-will",
            "$base/chapter-59-the-will/quiz",
            "https://www.americanliterature.com/author/alexandre-dumas/book/the-count-of-monte-cristo",
        )) {
            assertEquals(u, Pair(base, "the-count-of-monte-cristo"), site.normalize(u))
        }
    }

    /* The Scarlet Plague is listed on /books/ under /book/, but the site
       serves it — canonical and chapter links alike — under /novella/. */
    @Test
    fun `a novella keeps the address its chapters live under`() {
        val base = "https://americanliterature.com/author/jack-london/novella/the-scarlet-plague"
        for (u in listOf(base, "$base/chapter-vi")) {
            assertEquals(u, Pair(base, "the-scarlet-plague"), site.normalize(u))
        }
    }

    /* The rest of the site is short stories, poems and author pages, which
       are not books with chapters: no novel, so no download. */
    @Test
    fun `a page that is not a book names no novel`() {
        for (u in listOf(
            "https://americanliterature.com/",
            "https://americanliterature.com/books/",
            "https://americanliterature.com/author/hg-wells",
            "https://americanliterature.com/author/o-henry/short-story/the-gift-of-the-magi/",
        )) {
            assertEquals(u, Pair("", ""), site.normalize(u))
        }
    }

    @Test
    fun `every captured chapter-url shape reads back its number, or declines`() {
        val b = "https://americanliterature.com/author/x/book/y"
        assertEquals(59, site.chapterNumFromUrl("$b/chapter-59-the-will"))
        assertEquals(9, site.chapterNumFromUrl("$b/chapter-9"))
        assertEquals(15, site.chapterNumFromUrl("$b/book-1-chapter-15"))
        assertNull(site.chapterNumFromUrl("$b/chapter-vi"))
        assertNull(site.chapterNumFromUrl("$b/dedication"))
        assertNull(site.chapterNumFromUrl("$b/vii"))
    }

    /* One segment under the book is a chapter in the list; the study pages
       one further down, the book's own url and its /summary essay are not,
       and neither is another book's chapter. */
    @Test
    fun `only a book's own chapters are chapters`() {
        val slug = "the-great-gatsby"
        val b = "/author/f-scott-fitzgerald/book/the-great-gatsby"
        assertTrue(site.isChapterInList("$b/chapter-1", slug))
        assertTrue(site.isChapterInList("$b/american-literature-introduction-to-the-great-gatsby", slug))
        assertFalse(site.isChapterInList("$b/chapter-1/flashcards", slug))
        assertFalse(site.isChapterInList("$b/summary", slug))
        assertFalse(site.isChapterInList(b, slug))
        assertFalse(site.isChapterInList("/author/edith-wharton/book/summer/chapter-1", slug))
        /* the header menu links this book's first chapter from every page
           of the site, with a trailing slash — the strict rule takes it,
           which is right: it IS this book's chapter */
        assertTrue(site.isChapterPath("$b/chapter-1/", slug))
        assertFalse(site.isChapterPath("$b/american-literature-introduction-to-the-great-gatsby", slug))
    }

    /* THE LISTING IS THE CHAPTER LINKS, IN ORDER, AND NOTHING ELSE. Each
       table-of-contents entry carries five study badges, one of them a
       second link to the chapter itself ("#faq"), and some books interleave
       "PART I" / "Book II - …" section entries whose pages hold no text.
       Collected, the badges would be harmless duplicates — but a section
       entry is a chapter that fails to save on every run. */
    @Test
    fun `the listing is each entry's chapter link, section headings left out`() {
        var sections = 0
        var badges = 0
        for (r in novels) {
            val doc = Jsoup.parse(page(r[1]), r[3])
            val (_, slug) = site.normalize(r[3])
            val want = doc.select("#chapter-toc-list > .toc-entry:not(.toc-section-marker) > .toc-chapter-link > a")
                .map { it.absUrl("href").substringBefore('#') }
            sections += doc.select("#chapter-toc-list > .toc-section-marker").size
            badges += doc.select("#chapter-toc-list .toc-enrichment-badges a").size
            val got = Listing.collect(doc, site, slug).links.map { it.url }
            assertEquals("${r[1]}: listing", want, got)
        }
        assertTrue("no captured book has section entries — the exclusion is untested", sections > 0)
        assertTrue("no captured book has study badges — the exclusion is untested", badges > 0)
    }

    /* The title is the book's name. og:title agrees with the h1 on every
       captured page, so neither is furniture-wrapped here — confirmed rather
       than assumed, since novelfull.net's og:title was. */
    @Test
    fun `the title is the bare book name`() {
        for (r in novels) {
            val doc = Jsoup.parse(page(r[1]), r[3])
            val og = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            assertEquals("${r[1]}: title vs og:title", og, site.title(doc))
        }
    }

    /* The saved heading is "Chapter <position>: <the chapter's name>". The
       name must not open with the page's own chapter number restated in a
       form the shared parser cannot read: "Chapter 4: Chapter IV - …" and
       "Chapter 4: . The Will" are what the raw headings would have made. */
    @Test
    fun `a chapter's own number never lingers in the saved heading's title`() {
        for (r in chapters) {
            val first = Extractor.parseChapter(Jsoup.parse(page(r[1]), r[2]), "", 500, site)
                .lineSequence().first()
            val title = first.removePrefix("Chapter 500").removePrefix(": ")
            assertFalse("${r[1]}: heading is \"$first\"", Regex("^(?i)(chapter|ch\\.)\\s*[0-9ivxlcdm]+\\b").containsMatchIn(title))
            assertFalse("${r[1]}: heading is \"$first\"", Regex("^[.:\\-–—]").containsMatchIn(title))
        }
    }

    /* A heading that does not open with a chapter number — "Dedication",
       "Book 1 Chapter 15", "Book 13: Battlefield" — is the chapter's whole
       name, and is saved whole: nothing in it may be read as a number and
       dropped. */
    @Test
    fun `a heading without a leading number is saved whole`() {
        var seen = 0
        for (r in chapters) {
            if (r[5].isNotEmpty()) continue
            val doc = Jsoup.parse(page(r[1]), r[2])
            val printed = doc.selectFirst("article.chapter-content h1 > span")!!.text().trim()
            val first = Extractor.parseChapter(doc, "", 500, site).lineSequence().first()
            assertEquals("${r[1]}: heading", "Chapter 500: $printed", first)
            seen++
        }
        assertTrue("no captured chapter has an unnumbered heading", seen > 0)
    }

    /* Every shape the captured tables of contents use — roman numerals,
       a "." separator, "Ch." — restated as the parser reads it, and the
       shapes with no leading chapter number left exactly as printed. */
    @Test
    fun `leading chapter numbers are restated, everything else is left alone`() {
        val a = Americanliterature
        assertEquals("Chapter 1: Marseilles -- The Arrival.", a.restate("Chapter 1. Marseilles -- The Arrival."))
        assertEquals("Chapter 1: The Kentucky Home", a.restate("Chapter I: The Kentucky Home"))
        assertEquals("Chapter 1", a.restate("CHAPTER I."))
        assertEquals("Chapter 74", a.restate("CHAPTER LXXIV."))
        assertEquals("Chapter 2: Hunting-Song of the Seeonee Pack", a.restate("Ch. 2: Hunting-Song of the Seeonee Pack"))
        assertEquals("Chapter 1: IN WHICH WE ARE INTRODUCED", a.restate("Chapter I - IN WHICH WE ARE INTRODUCED"))
        assertEquals("Chapter 15: Book 1 Chapter 15", a.restate("Book 1 Chapter 15"))
        assertEquals("Chapter 1: Part One.: Chapter 1: Mr. Bucket", a.restate("Part One.: Chapter 1: Mr. Bucket"))
        for (t in listOf(
            "PART I - CHAPTER I.", "Dedication", "Chapter the First",
            "The Epilogue", "Chapters of a Life",
        )) {
            assertEquals(t, a.restate(t))
        }
    }

    /* What surrounds the prose on a chapter page: the share button, the
       Previous / Next links, the book's name in the heading and, below the
       article, the study FAQ. None of it may be saved into the chapter. */
    @Test
    fun `the page's own furniture never survives into a chapter`() {
        for (r in chapters) {
            val body = Extractor.parseChapter(Jsoup.parse(page(r[1]), r[2]), "", 1, site)
                .substringAfter('\n', "")
            for (j in listOf("Copy Link", "Previous Chapter", "Next Chapter", "Frequently Asked Questions", "Flashcards")) {
                assertFalse("${r[1]}: kept \"$j\"", body.contains(j, ignoreCase = true))
            }
        }
    }

    /* The study-link sweep matches by shape, not by class, so it must be
       shown to take nothing else: on every captured chapter — none of which
       carries the study row — the prose comes out exactly as without it. */
    @Test
    fun `the study-link sweep takes no prose from any captured chapter`() {
        for (r in chapters) {
            val doc = Jsoup.parse(page(r[1]), r[2])
            val article = doc.selectFirst("article.chapter-content")!!
            val prose = article.select("p").filter { it.parents().none { p -> p.tagName() == "p" } }.map { it.text() }
            val kept = site.chapterContent(doc)!!.select("p")
                .filter { it.parents().none { p -> p.tagName() == "p" } }.map { it.text() }
            assertEquals("${r[1]}: paragraphs", prose, kept)
        }
    }

    /* The phone saved Moby-Dick's "ETYMOLOGY" opening with the study row
       ("❓ FAQ", "🃏 Flashcards", "📚 Vocabulary", "✏ Quiz", "📄 Summary")
       above the prose. No capture holds that page as the site serves it now,
       so the row is the site's OWN badge markup — lifted from the table of
       contents of the captured Moby-Dick page, labels and links as served —
       set where the phone showed it, after the chapter navigation. */
    @Test
    fun `the chapter's study row is never saved as prose`() {
        val book = Jsoup.parse(
            page("americanliterature/herman-melville__moby-dick-or-the-whale.html"),
            "https://americanliterature.com/author/herman-melville/book/moby-dick-or-the-whale",
        )
        val row = book.selectFirst("#chapter-toc-list .toc-enrichment-badges")!!
        val labels = row.select("a").map { it.text() }
        assertTrue(labels.toString(), labels.any { it.contains("Flashcards") })
        val r = chapters.first { it[1].contains("moby-dick") }
        val doc = Jsoup.parse(page(r[1]), r[2])
        val article = doc.selectFirst("article.chapter-content")!!
        val before = article.selectFirst("p")!!.text()
        article.select("> hr").last()!!.after(row.outerHtml())
        val body = Extractor.parseChapter(doc, "", 1, site).substringAfter('\n')
        for (j in listOf("FAQ", "Flashcards", "Vocab", "Quiz", "Summary")) {
            assertFalse("kept \"$j\":\n${body.take(300)}", body.contains(j, ignoreCase = true))
        }
        assertTrue(body.take(300), body.startsWith(before.take(40)))
    }
}
