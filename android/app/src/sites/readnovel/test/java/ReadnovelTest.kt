package dev.vtlinh.noveldownloader.sites

import dev.vtlinh.noveldownloader.SiteContract
import dev.vtlinh.noveldownloader.Sites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadnovelTest : SiteContract() {
    override val site = Sites.all.first { it.name == "readnovel" }

    /* Already English — translating it would pay the API to round-trip. */
    @Test
    fun `it is an English site`() {
        assertTrue(site.english)
        assertEquals("Chapter", site.headingWord)
    }

    @Test
    fun `the id is part of the slug, so one string addresses the novel`() {
        val (base, slug) = site.normalize("https://read-novel.com/novel1000-martial-peak.html?p=7")
        assertEquals("novel1000-martial-peak", slug)
        assertEquals("https://read-novel.com/novel1000-martial-peak.html", base)
        assertTrue(site.isChapterPath("/novel1000-martial-peak/chapter0-chapter-1-x.html", slug))
    }

    /* THE NUMBER IN THE URL IS NOT THE CHAPTER NUMBER. "chapter<i>" is the
       zero-based position the site files a chapter under; the number it
       PRINTS lives in the title slug after it. Reading the leading one
       disagreed with the printed number on 26 of the 27 captured chapters
       that print a number — and that number goes into the saved file's
       heading and is the sole input to the legacy rename guess.

       The contract already checks every captured chapter url against its
       measured heading number; this pins the rule itself, including the
       answer for a chapter the site names with no number at all. */
    @Test
    fun `the positional prefix is stepped over, not read as the chapter number`() {
        val u = "https://read-novel.com/novel1-x/chapter8-chapter-9-nails-and-wooden-planks.html"
        assertEquals(9, site.chapterNumFromUrl(u))
        assertNotEquals("the leading number is a position, not a chapter", 8, site.chapterNumFromUrl(u))
        assertEquals(
            1,
            site.chapterNumFromUrl("https://read-novel.com/novel1-x/chapter0-chapter-1-the-servant.html"),
        )
        /* a chapter named without a number anywhere — three of the thirty
           captured pages are like this, and null is the right answer: the
           caller falls back to its position in the listing */
        assertNull(site.chapterNumFromUrl("https://read-novel.com/novel1-x/chapter12-13-cycling.html"))
    }

    /* THE TEXT, NOT THE CLASS. Both states print in span.text-primary here,
       so the neighbouring sites' "text-success means finished" rule would
       read every novel on this one as still running — and a finished novel
       that never reads as finished is swept forever. Confirm the corpus
       really does carry both states in the same class, or the adapter's
       choice is untested. */
    @Test
    fun `finished and running novels share one class and differ only in the text`() {
        var completedInPrimary = 0
        var ongoingInPrimary = 0
        for (r in novels) {
            val doc = org.jsoup.Jsoup.parse(page(r[1]), r[3])
            for (e in doc.select(".info .info-chitiet span.text-primary")) {
                when (e.text().trim()) {
                    "Completed" -> completedInPrimary++
                    "Ongoing" -> ongoingInPrimary++
                }
            }
        }
        assertTrue("no finished novels marked in text-primary", completedInPrimary >= 5)
        assertTrue("no running novels marked in text-primary", ongoingInPrimary >= 5)
        assertTrue(
            "no captured page marks a finished novel with text-success, so the " +
                "rule this adapter does NOT use is confirmed wrong for this site",
            novels.none {
                org.jsoup.Jsoup.parse(page(it[1]), it[3])
                    .select("span.text-success").any { s -> s.text().trim() == "Completed" }
            },
        )
    }

    /* The listing container and the "latest chapters" widget use the SAME
       ul.list-chapter class, one inside #list-chapter and one inside
       #new-chapter. Both are on every captured page, so a selector on the
       class alone would read whichever came first — six links, in the wrong
       order, called a whole novel. */
    @Test
    fun `the six-link latest widget is on every page and is not the listing`() {
        for (r in novels.take(20)) {
            val doc = org.jsoup.Jsoup.parse(page(r[1]), r[3])
            assertTrue("${r[1]}: no #new-chapter widget", doc.selectFirst("#new-chapter") != null)
            assertTrue(
                "${r[1]}: both containers use ul.list-chapter, which is the trap",
                doc.select("ul.list-chapter").size >= 2,
            )
            val widget = doc.select("#new-chapter a[href]").size
            val listing = doc.select("${site.listScope} a[href]").size
            assertTrue("${r[1]}: the listing ($listing) is not larger than the widget ($widget)",
                listing > widget)
        }
    }

    @Test
    fun `a listing page link is not a chapter`() {
        assertFalse(site.isChapterInList("/novel1000-martial-peak.html", "novel1000-martial-peak"))
    }
}
