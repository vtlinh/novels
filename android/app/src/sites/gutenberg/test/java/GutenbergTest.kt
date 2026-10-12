package dev.vtlinh.noveldownloader.sites

import dev.vtlinh.noveldownloader.Extractor
import dev.vtlinh.noveldownloader.Listing
import dev.vtlinh.noveldownloader.SiteContract
import dev.vtlinh.noveldownloader.Sites
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GutenbergTest : SiteContract() {
    override val site = Sites.all.first { it.name == "gutenberg" }

    private fun novel(id: String) = novels.first { it[1] == "gutenberg/$id.html" }

    private fun links(id: String) = Listing.collect(listingDoc(novel(id)), site, id).links

    /* What the engine saves for a listed chapter: the section cut out of
       the held page, parsed on its own (DownloadEngine.fetchSection). */
    private fun saved(page: org.jsoup.nodes.Document, l: Listing.Link, num: Int): String {
        val html = site.section(page, l.url)
        assertNotNull("${l.url}: the page has no such section", html)
        return Extractor.parseChapter(Jsoup.parse(html!!, l.url), l.text, num, site)
    }

    @Test
    fun `the browser opens the site on its categories`() {
        assertEquals("/ebooks/categories", site.startPath)
        assertTrue(site.matches("https://www.gutenberg.org${site.startPath}"))
    }

    @Test
    fun `the host is recognised in both spellings, and only it`() {
        assertTrue(site.matches("https://www.gutenberg.org/ebooks/2701"))
        assertTrue(site.matches("https://gutenberg.org/ebooks/2701"))
        assertFalse(site.matches("https://www.gutenberg.org.evil.com/ebooks/2701"))
        assertFalse(site.matches("https://standardebooks.org/ebooks"))
    }

    /* The book page, its formats, the read-online file and a chapter of
       it, and the old file directory all name the same book. */
    @Test
    fun `every url of a book normalises to its book page`() {
        val book = Pair("https://www.gutenberg.org/ebooks/2701", "2701")
        for (u in listOf(
            "https://www.gutenberg.org/ebooks/2701",
            "https://gutenberg.org/ebooks/2701/",
            "https://www.gutenberg.org/ebooks/2701.epub3.images",
            "https://www.gutenberg.org/ebooks/2701.txt.utf-8",
            "https://www.gutenberg.org/cache/epub/2701/pg2701-images.html",
            "https://www.gutenberg.org/cache/epub/2701/pg2701-images.html#section-12",
            "https://www.gutenberg.org/files/2701/",
        )) {
            assertEquals(u, book, site.normalize(u))
        }
    }

    @Test
    fun `the catalogue's own pages name no book`() {
        for (u in listOf(
            "https://www.gutenberg.org/",
            "https://www.gutenberg.org/ebooks/categories",
            "https://www.gutenberg.org/ebooks/bookshelf/644",
            "https://www.gutenberg.org/ebooks/author/9",
            "https://www.gutenberg.org/ebooks/subject/11",
            "https://www.gutenberg.org/browse/scores/top",
        )) {
            assertEquals(u, Pair("", ""), site.normalize(u))
        }
    }

    /* Every captured book's read-online button points where listingUrl
       says the chapters are. */
    @Test
    fun `the chapters are read from the book's read-online page`() {
        for (r in novels) {
            val (base, slug) = site.normalize(r[3])
            val button = Jsoup.parse(page(r[1]), r[3]).selectFirst("a.read-online-button")
            assertNotNull("${r[1]}: no read-online button", button)
            assertEquals(r[1], site.listingUrl(base, slug), button!!.absUrl("href"))
        }
    }

    /* The engine's own path, over every chapter of every captured book:
       not one of them may fail to save. A section the shared parser
       throws on is a chapter that fails on every run. */
    @Test
    fun `every chapter of every captured book saves`() {
        var total = 0
        for (r in novels) {
            val page = listingDoc(r)
            val (base, slug) = site.normalize(r[3])
            val links = Listing.collect(page, site, slug).links
            assertTrue("${r[1]}: no chapters", links.isNotEmpty())
            for ((k, l) in links.withIndex()) {
                assertTrue("${l.url}: not a section of the book's page", l.url.startsWith(site.listingUrl(base, slug)!! + "#"))
                val out = try { saved(page, l, k + 1) } catch (e: Exception) {
                    throw AssertionError("${l.url} [${l.text}]: ${e.message}")
                }
                assertTrue("${l.url}: heading is \"${out.lineSequence().first()}\"", out.startsWith("Chapter ${k + 1}"))
                total++
            }
        }
        assertTrue("only $total chapters", total > 3000)
    }

    /* Every h1-h3 heads a section, and a section's own heads are its saved
       heading — so none may be copied into a section's text. A chapter in
       a <div> of its own, reached as a sibling of the head before it,
       used to be cloned whole: Dracula's first chapter was saved as its
       heading, then "CHAPTER I", then "JONATHAN HARKER'S JOURNAL". */
    @Test
    fun `no chapter repeats its heading in its text`() {
        val bad = ArrayList<String>()
        var total = 0
        for (r in novels) {
            val page = listingDoc(r)
            val (_, slug) = site.normalize(r[3])
            for (l in Listing.collect(page, site, slug).links) {
                val heads = Jsoup.parse(site.section(page, l.url)!!, l.url).select("h1, h2, h3")
                if (heads.isNotEmpty()) bad.add("${l.url} [${l.text}]: ${heads.first()!!.text()}")
                total++
            }
        }
        assertTrue("$total sections", total > 3000)
        assertTrue("${bad.size} sections:\n" + bad.take(20).joinToString("\n"), bad.isEmpty())
    }

    /* The section the engine cuts out and parses is the same chapter as
       the one read in place from the whole page. */
    @Test
    fun `a cut section reads the same as the section in its page`() {
        for (r in chapters) {
            val page = Jsoup.parse(page(r[1]), r[2].substringBefore('#'))
            val inPlace = site.chapterContent(Jsoup.parse(page(r[1]), r[2]))!!.text()
            val cut = site.chapterContent(Jsoup.parse(site.section(page, r[2])!!, r[2]))!!.text()
            assertEquals(r[2], inPlace, cut)
            assertEquals(
                r[2],
                site.chapterHeading(Jsoup.parse(page(r[1]), r[2]))!!.text(),
                site.chapterHeading(Jsoup.parse(site.section(page, r[2])!!, r[2]))!!.text(),
            )
        }
    }

    @Test
    fun `a section the page does not have is not there`() {
        val r = novel("2701")
        val page = listingDoc(r)
        assertNull(site.section(page, page.location() + "#section-100000"))
        assertNull(site.section(page, page.location() + "#chapter-1"))
    }

    /* Moby Dick's contents is a page of links, and its chapters are
       anchored in empty paragraphs before each heading rather than on it. */
    @Test
    fun `Moby Dick is its extracts, its chapters and its epilogue`() {
        val l = links("2701")
        assertEquals(139, l.size)
        assertTrue(l[0].text, l[0].text.startsWith("ETYMOLOGY."))
        assertEquals("Chapter 1: Loomings.", l[3].text)
        assertEquals("Chapter 135: The Chase.—Third Day.", l[137].text)
        assertTrue(l.last().text, l.last().text.startsWith("Epilogue"))
        val out = saved(listingDoc(novel("2701")), l[3], 4)
        val first = out.lines()[1]
        assertTrue(first, first.startsWith("Call me Ishmael."))
        /* the paragraph is one line, though the file wraps it at 70 columns */
        assertTrue(first, first.contains("see the watery part of the world."))
    }

    /* Contents lists are links on some books and plain text on others,
       and neither is a chapter. */
    @Test
    fun `no captured book lists its contents as a chapter`() {
        val names = Regex("^(?:table of )?contents\\.?:?$|^list of illustrations\\.?$", RegexOption.IGNORE_CASE)
        for (r in novels) {
            val (_, slug) = site.normalize(r[3])
            for (l in Listing.collect(listingDoc(r), site, slug).links) {
                assertFalse("${r[1]}: listed \"${l.text}\"", names.containsMatchIn(l.text))
            }
        }
    }

    /* Pride and Prejudice prints an illustration's caption and the printed
       edition's page number inside its chapter headings. */
    @Test
    fun `a heading is its own words, not its caption or page number`() {
        val l = links("1342")
        assertTrue(l.none { "{" in it.text })
        assertTrue(l.none { "black horse" in it.text })
        assertTrue(l.any { it.text == "Chapter 3" })
    }

    /* The printed edition's page numbers sit in the margin of most of
       these books; in the saved text they would be read out mid-sentence. */
    @Test
    fun `page numbers never survive into a chapter`() {
        for (r in chapters) {
            val doc = Jsoup.parse(page(r[1]), r[2])
            val numbers = site.section(Jsoup.parse(page(r[1]), r[2].substringBefore('#')), r[2])!!
            if (!Regex("class=\"[^\"]*pagenum").containsMatchIn(numbers)) continue
            val out = Extractor.parseChapter(doc, "", 1, site)
            assertFalse("${r[2]}: kept a page number", Regex("\\[(?:Pg|p\\.) ?[0-9ivxlc]+\\]").containsMatchIn(out))
        }
    }

    /* A short story is one heading and its text: nothing to drop it for. */
    @Test
    fun `a book with one section is that one chapter`() {
        val l = links("7256")
        assertEquals(1, l.size)
        assertEquals("The Gift of the Magi", l[0].text)
    }

    /* Hamlet's scenes are its sections, each under its act. */
    @Test
    fun `a play is its scenes`() {
        val l = links("1524")
        assertTrue(l.any { it.text.endsWith("ACT I: SCENE I. Elsinore. A platform before the Castle.") })
        assertTrue(l.any { it.text == "SCENE II. Elsinore. A room of state in the Castle." })
    }

    @Test
    fun `the author is the catalogue's name, read the right way round`() {
        val doc = Jsoup.parse(page(novel("2701")[1]), novel("2701")[3])
        assertEquals("Herman Melville", site.author(doc))
        assertEquals("Moby Dick; Or, The Whale", site.title(doc))
        val d = site.description(doc)!!
        assertTrue(d, d.startsWith("\"Moby Dick; Or, The Whale\" by Herman Melville"))
        assertFalse(d, d.contains("Read more") || d.contains("automatically generated"))
        /* an anthology has an editor and no author */
        val verse = Jsoup.parse(page(novel("74619")[1]), novel("74619")[3])
        assertEquals("John Collings Squire", site.author(verse))
    }
}
