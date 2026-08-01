package dev.vtlinh.noveldownloader.sites

import dev.vtlinh.noveldownloader.SiteContract
import dev.vtlinh.noveldownloader.Sites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TruyenfullmoiTest : SiteContract() {
    override val site = Sites.all.first { it.name == "truyenfullmoi" }

    /* Vietnamese source, so a downloaded novel is worth translating. */
    @Test
    fun `it is not an English site`() {
        assertFalse(site.english)
        assertEquals("Chương", site.headingWord)
    }

    /* The apex 301s to www rather than serving a second catalogue, so both
       spellings are this one site and a novel keeps whichever host it was
       opened at — the fetch follows the redirect. */
    @Test
    fun `both spellings of the host are recognised`() {
        assertTrue(site.matches("https://truyenfullmoi.com/so-13-pho-mink.1666/"))
        assertTrue(site.matches("https://www.truyenfullmoi.com/so-13-pho-mink.1666/"))
        assertFalse(site.matches("https://truyenfull.today/tu-tien/"))
    }

    /* THE ID. The novel's own url carries one and its chapters do not, so the
       string that addresses the novel and the string that identifies it are
       different — the base keeps the id, the slug is the name its chapters
       are filed under. Both are needed: the base is what every listing page
       is built from, the slug is what says which links are this novel's. */
    @Test
    fun `the base keeps the id and the slug is what the chapters are under`() {
        val (base, slug) = site.normalize("https://www.truyenfullmoi.com/so-13-pho-mink.1666/trang-7/")
        assertEquals("so-13-pho-mink", slug)
        assertEquals("https://www.truyenfullmoi.com/so-13-pho-mink.1666/", base)
        assertTrue(site.isChapterPath("/so-13-pho-mink/chuong-12.html", slug))
        assertEquals(12, site.chapterNumFromUrl("https://www.truyenfullmoi.com/so-13-pho-mink/chuong-12.html"))
    }

    /* ...and a url with no id names no novel, so the adapter says so instead
       of inventing one. "/so-13-pho-mink/" is not a shorter spelling of the
       novel's url — the site answers it with a 301 to its home page — and a
       download started against it would read the home page as a chapter list.
       An empty slug is what the browser screen already reads as "this page
       isn't a novel", and it disables the download button rather than
       starting a job that cannot find anything. */
    @Test
    fun `a url that carries no id resolves to no novel at all`() {
        assertEquals(Pair("", ""), site.normalize("https://www.truyenfullmoi.com/so-13-pho-mink/chuong-1.html"))
        assertEquals(Pair("", ""), site.normalize("https://www.truyenfullmoi.com/"))
        assertEquals(Pair("", ""), site.normalize("https://www.truyenfullmoi.com/truyen-ngon-tinh/"))
    }

    /* The pagination block sits inside #list-chapter here, as it does on
       truyenfull — but these links carry the id and chapter links don't, so
       the id is what keeps them apart. A page link read as a chapter puts
       every chapter after it one position out, and positions name files. */
    @Test
    fun `a listing page link is not a chapter of the novel`() {
        assertFalse(site.isChapterInList("/so-13-pho-mink.1666/trang-2/", "so-13-pho-mink"))
        assertFalse(site.isChapterInList("/so-13-pho-mink.1666/", "so-13-pho-mink"))
        assertTrue(site.isChapterInList("/so-13-pho-mink/chuong-2.html", "so-13-pho-mink"))
    }

    /* This host serves no og:title on any captured page, so the shared title
       chain would reach h3.title only by falling through it — the shape that
       named novelfull.net's novels after the site. Confirm the corpus really
       is the host with no og:title, or the adapter's choice is untested. */
    @Test
    fun `the corpus is a host that serves no og-title, which is why h3 is asked first`() {
        val withOg = novels.count { r ->
            org.jsoup.Jsoup.parse(page(r[1]), r[3]).selectFirst("meta[property=og:title]") != null
        }
        assertEquals("this host served none when the pages were captured", 0, withOg)
    }
}
