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

class StandardebooksTest : SiteContract() {
    override val site = Sites.all.first { it.name == "standardebooks" }

    private fun chapter(name: String) = chapters.first { it[1].endsWith("/$name.html") }

    private fun parse(r: List<String>, num: Int = 7) =
        Extractor.parseChapter(Jsoup.parse(page(r[1]), r[2]), "", num, site)

    @Test
    fun `it is an English site`() {
        assertTrue(site.english)
        assertEquals("Chapter", site.headingWord)
    }

    @Test
    fun `the browser opens the site on its ebooks`() {
        assertEquals("/ebooks", site.startPath)
        assertTrue(site.matches("https://standardebooks.org${site.startPath}"))
    }

    @Test
    fun `the host is recognised in both spellings, and only it`() {
        assertTrue(site.matches("https://standardebooks.org/ebooks/herman-melville/moby-dick"))
        assertTrue(site.matches("https://www.standardebooks.org/ebooks/herman-melville/moby-dick"))
        assertFalse(site.matches("https://standardebooks.org.evil.com/ebooks/x/y"))
        assertFalse(site.matches("https://americanliterature.com/books/"))
    }

    /* Every url a user can be looking at names the same book: the book
       page, its table of contents, a chapter, the cover, a www. spelling
       and a trailing slash. */
    @Test
    fun `every page of a book normalises to the bare book url`() {
        val base = "https://standardebooks.org/ebooks/herman-melville/moby-dick"
        for (u in listOf(
            base,
            "$base/",
            "$base/text",
            "$base/text/chapter-1",
            "$base/text/chapter-1#chapter-1",
            "$base/downloads/cover.jpg",
            "https://www.standardebooks.org/ebooks/herman-melville/moby-dick",
        )) {
            assertEquals(u, Pair(base, "herman-melville_moby-dick"), site.normalize(u))
        }
    }

    /* A translation is its own book, at a third segment, and two
       translations of one work are two books. */
    @Test
    fun `an edition keeps its translator in the url and the slug`() {
        val base = "https://standardebooks.org/ebooks/leo-tolstoy/war-and-peace/louise-maude_aylmer-maude"
        for (u in listOf(base, "$base/text", "$base/text/chapter-1-1-1")) {
            assertEquals(
                u,
                Pair(base, "leo-tolstoy_war-and-peace_louise-maude_aylmer-maude"),
                site.normalize(u),
            )
        }
    }

    @Test
    fun `a page that is not a book names no novel`() {
        for (u in listOf(
            "https://standardebooks.org/",
            "https://standardebooks.org/ebooks",
            "https://standardebooks.org/ebooks?query=melville",
            "https://standardebooks.org/ebooks/herman-melville",
            "https://standardebooks.org/collections/the-guardians-best-100-novels-in-english-2015",
            "https://standardebooks.org/honeypot",
        )) {
            assertEquals(u, Pair("", ""), site.normalize(u))
        }
    }

    /* The title segment repeats across the catalogue ("short-fiction",
       "poetry"), so the slug has to carry the author — two books with one
       slug would share one record. */
    @Test
    fun `no two captured books share a slug`() {
        val slugs = novels.map { site.normalize(it[3]).second }
        assertEquals(slugs.size, slugs.toSet().size)
        assertTrue(slugs.contains("edgar-allan-poe_short-fiction"))
        assertTrue(slugs.contains("thomas-hardy_short-fiction"))
    }

    /* The book page links the table of contents and no chapter: read on its
       own, it has no list at all. */
    @Test
    fun `the chapter list is the table of contents, not the book page`() {
        for (r in novels) {
            val (base, slug) = site.normalize(r[3])
            assertEquals("$base/text", site.listingUrl(base, slug))
            val book = Jsoup.parse(page(r[1]), r[3])
            assertNull("${r[1]}: the book page has a table of contents", book.selectFirst(site.listScope))
            assertTrue("${r[1]}: chapters on the book page", Listing.collect(book, site, slug).links.isEmpty())
        }
    }

    /* A request for /honeypot bans the phone for a day. Every captured page
       carries the link, and no listing may ever yield it — or anything
       outside the book's own reading pages — not even read from the whole
       page, with the contents gone. */
    @Test
    fun `the honeypot is never collected`() {
        for (r in novels) {
            val (base, slug) = site.normalize(r[3])
            val doc = listingDoc(r)
            assertTrue("${r[1]}: no honeypot to avoid", doc.select("a[href=/honeypot]").isNotEmpty())
            for (l in Listing.collect(doc, site, slug).links) {
                assertTrue("${r[1]}: collected $l", l.url.startsWith("$base/text/"))
            }
            doc.selectFirst(site.listScope)!!.remove()
            for (l in Listing.collect(doc, site, slug).links) {
                assertTrue("${r[1]}: collected $l", l.url.startsWith("$base/text/"))
            }
        }
    }

    /* Every book lists its title page, imprint, colophon and public-domain
       dedication, and none of them is the book. */
    @Test
    fun `the edition's furniture is left out of the listing`() {
        val furniture = Regex("/text/(titlepage|halftitlepage|imprint|colophon|uncopyright|loi|frontispiece|endnotes)$")
        for (r in novels) {
            val (_, slug) = site.normalize(r[3])
            val doc = listingDoc(r)
            assertTrue(
                "${r[1]}: no colophon in the contents",
                doc.select("nav#toc a[href]").any { it.absUrl("href").endsWith("/text/colophon") },
            )
            for (l in Listing.collect(doc, site, slug).links) {
                assertFalse("${r[1]}: collected $l", furniture.containsMatchIn(l.url))
            }
        }
    }

    /* The Rubáiyát links each of its 101 quatrains into the one file that
       holds the poem, and no link names that file bare. */
    @Test
    fun `a file linked only by its sections is still one entry`() {
        val r = novels.first { it[1].endsWith("/omar-khayyam__the-rubaiyat-of-omar-khayyam__edward-fitzgerald.html") }
        val (base, slug) = site.normalize(r[3])
        val urls = Listing.collect(listingDoc(r), site, slug).links.map { it.url }
        assertTrue(urls.size > 100)
        assertEquals(
            listOf("$base/text/preface", "$base/text/the-rubaiyat-of-omar-khayyam"),
            urls.distinct(),
        )
    }

    @Test
    fun `a chapter's heading is restated with its number, and its title kept`() {
        val out = parse(chapter("herman-melville__moby-dick__chapter-1"), 1)
        assertEquals("Chapter 1: Loomings", out.lineSequence().first())
        assertTrue(out.lineSequence().drop(1).first().startsWith("Call me Ishmael."))
    }

    /* War and Peace prints only "I" over its first chapter, and its contents
       list the chapter as "I" too. The engine hands the parser that link
       text, which stands in for a title the page lacks — and a numeral is
       not one. */
    @Test
    fun `a chapter that prints only its number is saved without a title`() {
        val r = chapter("leo-tolstoy__war-and-peace__louise-maude_aylmer-maude__chapter-1-1-1")
        val book = novels.first { it[3] == r[2].substringBefore("/text/") }
        val link = Listing.collect(listingDoc(book), site, r[3]).links.first { it.url == r[2] }
        assertEquals("I", link.text)
        val out = Extractor.parseChapter(Jsoup.parse(page(r[1]), r[2]), link.text, 7, site)
        assertEquals("Chapter 7", out.lineSequence().first())
    }

    /* The page prints "Book I" and nothing else; the chapters of that book
       are files of their own. */
    @Test
    fun `a divider page is saved as its heading`() {
        val out = parse(chapter("leo-tolstoy__war-and-peace__louise-maude_aylmer-maude__book-1"))
        assertEquals("Chapter 7: Book I\nBook I", out)
        val part = parse(chapter("leo-tolstoy__war-and-peace__louise-maude_aylmer-maude__part-1-1"))
        assertEquals("Chapter 7: Part I: 1805\nPart I: 1805", part)
    }

    /* ...and a divider with words under its heading keeps them. */
    @Test
    fun `a divider's own text is kept`() {
        val out = parse(chapter("thomas-a-kempis__the-imitation-of-christ__william-benham__book-4"))
        assertTrue(out, out.contains("A devout exhortation to the Holy Communion."))
        assertFalse(out, out.lineSequence().drop(1).first().startsWith("Book"))
    }

    /* The Rubáiyát's one file is 101 quatrains side by side, each headed by
       its numeral: the first quatrain's "I" is not the page's heading, and
       taking it as one dropped it from the poem. */
    @Test
    fun `pieces side by side keep their own headings`() {
        val out = parse(chapter("omar-khayyam__the-rubaiyat-of-omar-khayyam__edward-fitzgerald__the-rubaiyat-of-omar-khayyam"))
        assertEquals(
            listOf("Chapter 7: The Rubáiyát of Omar Khayyám", "I", "Wake! For the Sun, who scatter’d into flight"),
            out.lines().take(3),
        )
        assertTrue(out.lines().contains("CI"))
    }

    /* The source lays a heading's parts out on lines of their own. */
    @Test
    fun `a heading inside the chapter stays on one line`() {
        val out = parse(chapter("william-shakespeare__edward-iii__act-1"))
        assertEquals(listOf("Chapter 7: Act I", "Scene I"), out.lines().take(2))
    }

    /* A dedication has no heading; the page's <title> names it. */
    @Test
    fun `a page with no heading is named by its title`() {
        val out = parse(chapter("a-a-milne__winnie-the-pooh__dedication"), 1)
        assertEquals("Chapter 1: To Her", out.lineSequence().first())
    }

    /* The note markers link to the endnotes, which are not downloaded; in
       the middle of a sentence or a heading they are stray digits. */
    @Test
    fun `note markers are never saved`() {
        var marked = 0
        for (r in chapters) {
            val doc = Jsoup.parse(page(r[1]), r[2])
            if (doc.select("a[href]").any { "noteref" in it.attr("epub:type").split(' ') }) marked++
            val el = site.chapterContent(doc)!!
            assertTrue(r[1], el.select("a[href]").none { "noteref" in it.attr("epub:type").split(' ') })
        }
        assertTrue("no captured chapter carries a note marker", marked >= 3)
        val out = parse(chapter("edgar-allan-poe__short-fiction__ms-found-in-a-bottle"))
        assertEquals("Chapter 7: MS. Found in a Bottle", out.lineSequence().first())
    }

    @Test
    fun `leading chapter numbers are restated, everything else is left alone`() {
        val st = Standardebooks
        assertEquals("Chapter 1: Loomings", st.restate("I: Loomings"))
        assertEquals("Chapter 12", st.restate("XII"))
        assertEquals("Chapter 4", st.restate("Chapter IV"))
        assertEquals("Chapter 30: The Pipe", st.restate("30: The Pipe"))
        assertEquals("Book I", st.restate("Book I"))
        assertEquals("Etymology", st.restate("Etymology"))
        assertEquals("Mix", st.restate("Mix"))
        assertEquals("I Learn My Neighbour’s Profession", st.restate("I Learn My Neighbour’s Profession"))
    }

    /* The header and footer around every reading page: the navigation, the
       hidden ban link, and the links to the previous and next pages. */
    @Test
    fun `the page's own furniture never survives into a chapter`() {
        for (r in chapters) {
            val out = parse(r)
            for (j in listOf("Previous:", "Next:", "Back to ebook", "Table of contents", "ban your IP")) {
                assertFalse("${r[1]}: kept \"$j\"", out.contains(j))
            }
        }
    }
}
