package dev.vtlinh.noveldownloader.sites

import dev.vtlinh.noveldownloader.SiteContract
import dev.vtlinh.noveldownloader.Sites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreewebnovelTest : SiteContract() {
    override val site = Sites.all.first { it.name == "freewebnovel" }

    /* Already English — translating it would pay the API to round-trip. */
    @Test
    fun `it is an English site`() {
        assertTrue(site.english)
        assertEquals("Chapter", site.headingWord)
    }

    @Test
    fun `a novel is under -novel- and its chapters under that`() {
        val (base, slug) = site.normalize("https://freewebnovel.com/novel/some-book?page=4")
        assertEquals("some-book", slug)
        assertEquals("https://freewebnovel.com/novel/some-book", base)
        assertTrue(site.isChapterPath("/novel/some-book/chapter-12", slug))
        assertFalse("the novel's own url is not a chapter", site.isChapterInList("/novel/some-book", slug))
    }

    /* "/chapter-24" is the POSITION the site files a chapter under, not the
       number it prints: /novel/awakening/chapter-24 prints "Chapter 40", and
       a second captured novel is out by one. Reading it would be wrong on 7%
       of chapters — in the saved file's heading, and in the only input to the
       legacy rename guess — so the url is declined outright and the chapter
       takes its number from its position in the listing instead.

       Pinned here because "returns null" is otherwise indistinguishable from
       an adapter that forgot to implement it. */
    @Test
    fun `the number in the url is a position, so it is not read as a chapter number`() {
        assertNull(site.chapterNumFromUrl("https://freewebnovel.com/novel/some-book/chapter-12"))
        var disagreed = 0
        for (r in chapters) {
            val printed = r[5].toIntOrNull() ?: continue
            val inUrl = Regex("/chapter-(\\d+)").find(r[2])?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            if (inUrl != printed) disagreed++
        }
        assertTrue(
            "no captured chapter has a url number that disagrees with the printed " +
                "one, so the reason for declining the url is not exercised here",
            disagreed >= 2,
        )
    }

    /* THE PAGE COUNT IS THE NUMBER OF OPTIONS. Every other supported site
       prints its page count in a link, and the shared highestLink helper
       reads it off the hrefs. This one prints no "?page=" link anywhere: the
       buttons are javascript:void(0) and every #indexselect option carries
       the same value, the novel's own url. Only the option COUNT says how
       long the listing is — so confirm the captured pages really are like
       that, or the adapter's unusual choice is untested. */
    @Test
    fun `no captured page links its own pagination, so the option count is all there is`() {
        var withOptions = 0
        for (r in novels) {
            val doc = org.jsoup.Jsoup.parse(page(r[1]), r[3])
            val opts = doc.select("#indexselect option")
            if (opts.isNotEmpty()) withOptions++
            assertTrue(
                "${r[1]}: a ?page= link exists after all — highestLink would do",
                doc.select("a[href*=?page=]").isEmpty(),
            )
            /* ...and the options really are all the same url, so their values
               cannot be paged through */
            if (opts.size > 1) {
                assertEquals(
                    "${r[1]}: option values differ, so they could have been followed",
                    1,
                    opts.map { it.attr("value") }.distinct().size,
                )
            }
        }
        /* EVERY page, not most of them. maxPage falls back to 1 without the
           select, and a novel that reports one listing page is a novel whose
           chapters past the fortieth are not in the listing at all. Measured:
           all 100 carry it. */
        assertEquals("every captured page must carry the select", novels.size, withOptions)
    }

    /* The listing container and the "latest chapters" widget share a class,
       one inside .m-newest1 and one inside .m-newest2, on every captured
       page. Reading the widget gives six links in the wrong order and calls
       them the whole novel. */
    @Test
    fun `the six-link latest widget sits beside the listing on every page`() {
        for (r in novels.take(20)) {
            val doc = org.jsoup.Jsoup.parse(page(r[1]), r[3])
            assertTrue("${r[1]}: no .m-newest1 widget", doc.selectFirst(".m-newest1") != null)
            val widget = doc.select(".m-newest1 a[href]").size
            val listing = doc.select("${site.listScope} a[href]").size
            assertTrue(
                "${r[1]}: the listing ($listing) is not larger than the widget ($widget)",
                listing > widget,
            )
        }
    }

    /* span.chapter carries the heading once; the h4 beside it prints it TWICE
       inside a single line, and sits inside the content container on most
       pages. Taking the h4 would put a doubled heading at the top of the
       saved file. Confirm the corpus actually contains that shape. */
    @Test
    fun `the doubled h4 heading is in the corpus, which is why span-chapter is used`() {
        var doubled = 0
        for (r in chapters) {
            val doc = org.jsoup.Jsoup.parse(page(r[1]), r[2])
            val h4 = doc.selectFirst("h4")?.text().orEmpty()
            val n = r[5]
            if (n.isNotEmpty() &&
                Regex("Chapter\\s*$n\\b.*Chapter\\s*$n\\b", RegexOption.IGNORE_CASE).containsMatchIn(h4)
            ) {
                doubled++
            }
            /* whatever the h4 does, the element the adapter uses says it once */
            val head = site.chapterHeading(doc)?.text().orEmpty()
            if (n.isNotEmpty()) {
                assertEquals(
                    "${r[1]}: the chapter heading names its number twice — \"$head\"",
                    1,
                    Regex("Chapter\\s*$n\\b", RegexOption.IGNORE_CASE).findAll(head).count(),
                )
            }
        }
        assertTrue(
            "no captured chapter carries the doubled h4, so the reason for not " +
                "using it is not exercised by this corpus",
            doubled >= 3,
        )
    }
}
