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

class Vivutruyen2Test : SiteContract() {
    override val site = Sites.all.first { it.name == "vivutruyen2" }

    /* Vietnamese source, so a downloaded novel is worth translating. */
    @Test
    fun `it is not an English site`() {
        assertFalse(site.english)
        assertEquals("Chương", site.headingWord)
    }

    /* www. and plain http both 301 to the https apex (measured), so either
       spelling a user pastes is this one site. vivutruyen.net — no 2 — is a
       DIFFERENT host serving its own catalogue, and nothing here may claim
       it: no page of it has been captured, so no selector has been measured
       against it. */
    @Test
    fun `both spellings of the host are recognised, the sister host is not`() {
        assertTrue(site.matches("https://vivutruyen2.net/nam-lan-mat-con/"))
        assertTrue(site.matches("https://www.vivutruyen2.net/nam-lan-mat-con/"))
        assertFalse(site.matches("https://vivutruyen.net/nam-lan-mat-con/"))
        assertFalse(site.matches("https://truyenfull.today/tu-tien/"))
    }

    /* the first path segment names the novel, and a chapter url resolves to
       its novel by dropping its own segment */
    @Test
    fun `urls normalise to the novel base and slug`() {
        val (base, slug) = site.normalize("https://vivutruyen2.net/nam-lan-mat-con/chuong-5/")
        assertEquals("nam-lan-mat-con", slug)
        assertEquals("https://vivutruyen2.net/nam-lan-mat-con/", base)
        assertEquals(Pair("", ""), site.normalize("https://vivutruyen2.net/"))
    }

    /* Every chapter-url shape the corpus actually holds. The plain and
       slug-suffixed shapes are the common ones; "full-chuong-N" and a bare
       "/full/" both come from one captured novel that mixes all of them in
       a single five-chapter list — the shape nobody would have imagined,
       which is what capturing before writing is for. */
    @Test
    fun `every captured chapter-url shape reads back its number, or declines`() {
        assertEquals(5, site.chapterNumFromUrl("https://vivutruyen2.net/nam-lan-mat-con/chuong-5/"))
        assertEquals(1, site.chapterNumFromUrl("https://vivutruyen2.net/dieu-bi-an/chuong-1-dieu-bi-an/"))
        assertEquals(5, site.chapterNumFromUrl("https://vivutruyen2.net/kieu-hoa-dung-truoc-vuc-sau/full-chuong-5/"))
        /* the un-numbered first chapter: null, and the caller falls back to
           the link text ("Chương 1") and the position — both right */
        assertNull(site.chapterNumFromUrl("https://vivutruyen2.net/kieu-hoa-dung-truoc-vuc-sau/full/"))
        /* a novel whose own slug carries the word must not answer with the
           slug's number */
        assertEquals(2, site.chapterNumFromUrl("https://vivutruyen2.net/tieng-chuong-thu-11/chuong-2/"))
    }

    /* Inside the site's own list, being there is the evidence — the "/full/"
       chapter has to be collected or the novel loses its first chapter and
       every position after it shifts. Outside the list, that url says
       nothing, so the strict rule refuses it. */
    @Test
    fun `the unnumbered full chapter is a chapter in the list and not outside it`() {
        assertTrue(site.isChapterInList("/kieu-hoa-dung-truoc-vuc-sau/full/", "kieu-hoa-dung-truoc-vuc-sau"))
        assertTrue(site.isChapterInList("/kieu-hoa-dung-truoc-vuc-sau/full-chuong-5/", "kieu-hoa-dung-truoc-vuc-sau"))
        assertFalse(site.isChapterPath("/kieu-hoa-dung-truoc-vuc-sau/full/", "kieu-hoa-dung-truoc-vuc-sau"))
        assertTrue(site.isChapterPath("/kieu-hoa-dung-truoc-vuc-sau/full-chuong-5/", "kieu-hoa-dung-truoc-vuc-sau"))
        /* the novel's own url and another novel's chapter are neither */
        assertFalse(site.isChapterInList("/kieu-hoa-dung-truoc-vuc-sau/", "kieu-hoa-dung-truoc-vuc-sau"))
        assertFalse(site.isChapterInList("/khac-truyen/chuong-1/", "kieu-hoa-dung-truoc-vuc-sau"))
    }

    /* THE ORDER. This site's listing runs newest-first — "Chương 5" at the
       top, "Chương 1" at the bottom, on every one of the captured pages —
       and positions name files, so reading it in document order names
       chapter 5 "Chapter 1.txt". listDescending is the fix; this holds it
       to the whole corpus: what Listing.collect hands the engine must run
       OLDEST-first, as read from the numbers the site prints in its own
       link text. */
    @Test
    fun `the newest-first listing reaches the engine oldest-first`() {
        for (r in novels) {
            val (_, slug) = site.normalize(r[3])
            val found = Listing.collect(Jsoup.parse(page(r[1]), r[3]), site, slug)
            val nums = found.links.mapNotNull { Extractor.parseHeading(it.second).first }
            assertTrue("${r[1]}: link texts carry no numbers", nums.isNotEmpty())
            assertEquals("${r[1]}: does not start at the first chapter", nums.minOrNull(), nums.firstOrNull())
            assertTrue(
                "${r[1]}: listing is not ascending after the flip: $nums",
                nums.zipWithNext().all { it.first <= it.second },
            )
        }
    }

    /* This host wraps its og:title in " - vivutruyen2.net" furniture on
       every captured page — the folder-naming trap novelfull.net set once
       already — which is why title() asks h1 > a.title-truyen instead of
       the shared og:title chain. Confirm the corpus really is that host,
       or the adapter's choice is untested. */
    @Test
    fun `og-title is furniture-wrapped on this host, which is why h1 is asked instead`() {
        var wrapped = 0
        for (r in novels) {
            val og = Jsoup.parse(page(r[1]), r[3])
                .selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
            if (og.contains("vivutruyen", ignoreCase = true)) wrapped++
        }
        assertEquals("every captured page wrapped its og:title when captured", novels.size, wrapped)
    }

    /* The "read on" pointer the site appends inside div.reading — the prose
       container itself, where the shared furniture filters never look. Six
       of the thirty captured chapters carry one, in three spellings; none
       may survive into a saved file, where TTS would read the url aloud. */
    @Test
    fun `the read-on pointer never survives into a chapter`() {
        var carried = 0
        for (r in chapters) {
            val raw = page(r[1])
            if (raw.contains("ĐỌC TIẾP", ignoreCase = true)) carried++
            val out = Extractor.parseChapter(Jsoup.parse(raw, r[2]), "", r[5].toIntOrNull() ?: 1, site)
            assertFalse("${r[1]}: kept the read-on pointer", out.contains("đọc tiếp", ignoreCase = true))
            assertFalse("${r[1]}: kept a url to the sister site", out.contains("vivutruyen", ignoreCase = true))
        }
        assertTrue("no captured chapter carries the pointer — the strip is untested", carried >= 3)
    }
}
