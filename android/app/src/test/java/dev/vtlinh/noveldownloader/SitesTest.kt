package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* Which links on a listing page count as chapters. Getting this wrong doesn't
   fail loudly — it inserts an entry that isn't a chapter, or drops one that
   is, and since positions name files every chapter after it is renamed onto
   its neighbour. */
class SitesTest {

    private val truyenfull = Sites.all.first { it.name == "truyenfull" }
    private val novelfull = Sites.all.first { it.name == "novelfull" }

    @Test
    fun `a truyenfull chapter path is in the listing`() {
        assertTrue(truyenfull.isChapterInList("/tu-tien/chuong-100/", "tu-tien"))
    }

    /* The pagination block sits INSIDE the chapter-list container, and its
       "page 1" link is the novel's own canonical URL. That is not /trang-N,
       so it was collected as a chapter — one bogus entry mid-listing, and
       every chapter after it a position out of place, on any novel past one
       listing page. */
    @Test
    fun `the novels own canonical url is not a chapter`() {
        assertFalse(
            "the pagination link back to page 1 must not read as a chapter",
            truyenfull.isChapterInList("/tu-tien/", "tu-tien"),
        )
    }

    @Test
    fun `listing page links are not chapters`() {
        assertFalse(truyenfull.isChapterInList("/tu-tien/trang-5/", "tu-tien"))
    }

    @Test
    fun `another novels path is not a chapter of this one`() {
        assertFalse(truyenfull.isChapterInList("/khac-truyen/chuong-1/", "tu-tien"))
    }

    @Test
    fun `novelfull chapter paths are title slugs not numbers`() {
        assertTrue(
            "novelfull chapter urls are title slugs, which is why the listing container is trusted",
            novelfull.isChapterInList("/the-mech-touch/chapter-1-a-title.html", "the-mech-touch"),
        )
    }

    @Test
    fun `novelfull canonical url is not a chapter`() {
        assertFalse(novelfull.isChapterInList("/the-mech-touch.html", "the-mech-touch"))
    }

    @Test
    fun `truyenfull urls normalise to the novel base and slug`() {
        val (base, slug) = truyenfull.normalize("https://truyenfull.today/tu-tien/chuong-100/")
        assertEquals("tu-tien", slug)
        assertEquals("https://truyenfull.today/tu-tien/", base)
    }

    @Test
    fun `novelfull urls normalise to the novel base and slug`() {
        val (base, slug) = novelfull.normalize("https://novelfull.com/the-mech-touch.html?page=3")
        assertEquals("the-mech-touch", slug)
        assertEquals("https://novelfull.com/the-mech-touch.html", base)
    }

    @Test
    fun `a chapter number is read from the url`() {
        assertEquals(100, truyenfull.chapterNumFromUrl("https://truyenfull.today/tu-tien/chuong-100/"))
    }
}

/* The pattern that decides whether a file in the folder is a chapter at all.
   A name it doesn't match is invisible to the reader, the dedupe and the
   surplus sweep — which is deliberate for some names and a bug for others. */
class ChapterNameTest {

    private val re = ChapterName.RE

    @Test
    fun `ordinary chapter names match`() {
        assertTrue(re.matches("Chapter 1.txt"))
        assertTrue(re.matches("Chapter 4120.txt"))
    }

    @Test
    fun `merged and legacy titled names match`() {
        assertTrue(re.matches("Chapter 70-71.txt"))
        assertTrue(re.matches("Chapter 70 - Some Title.txt"))
    }

    /* the rename pass parks a chapter the site dropped under this name, and
       it has to stay visible in the reader */
    @Test
    fun `an evicted unlisted chapter is still a chapter`() {
        assertTrue(re.matches("Chapter 70 (unlisted).txt"))
    }

    /* half-written files must be invisible: the whole point of the .part name
       is that nothing adopts it as a finished chapter. SAF rewrites the
       extension to match the mime type, so both forms have to stay unmatched. */
    @Test
    fun `half written files are not chapters`() {
        assertFalse(re.matches("Chapter 5.txt.gz.part"))
        assertFalse(re.matches("Chapter 5.txt.gz.part.gz"))
    }

    /* Both shapes have to be recognised: we ask SAF for "<name>.part", and a
       provider that forces the extension to match the mime type creates
       "<name>.part.gz" instead. Missing either leaks half-written files. */
    @Test
    fun `both half written shapes are recognised`() {
        assertTrue(Zips.isPartName("Chapter 5.txt.gz.part"))
        assertTrue(Zips.isPartName("Chapter 5.txt.gz.part.gz"))
    }

    /* The sweep DELETES whatever this matches, from any folder under the
       user's tree — which may be a shared folder holding their own files.
       Matching ".part" anywhere destroyed them silently and unrecoverably. */
    @Test
    fun `a users own file is never mistaken for a half written chapter`() {
        assertFalse(Zips.isPartName("Chapter 12.part2.txt"))
        assertFalse(Zips.isPartName("notes.partial.txt"))
        assertFalse(Zips.isPartName("my.particulars.doc"))
        assertFalse(Zips.isPartName("Chapter 5.txt"))
        assertFalse(Zips.isPartName("Chapter 5.txt.gz"))
    }

    /* An UNCOMPRESSED chapter can't carry the mark as a suffix: SAF forces
       the extension to match the mime type, so "Chapter 5.txt.part" written
       as text/plain lands as "Chapter 5.txt.part.txt" — which this pattern
       matches, so a half-written file would read as a finished chapter, be
       indexed, and never be fetched again. */
    @Test
    fun `a half written plain chapter is invisible to the chapter pattern`() {
        val tmp = Zips.partName("Chapter 5.txt")
        assertFalse("a temporary name must not read as a chapter", re.matches(tmp))
        assertFalse(re.matches("$tmp.txt"))
        assertTrue("...and the sweep must still recognise it", Zips.isPartName(tmp))
        assertTrue(Zips.isPartName("$tmp.txt"))
    }

    @Test
    fun `the finished name is unchanged by the temporary one`() {
        assertTrue(re.matches("Chapter 5.txt"))
        assertFalse(Zips.isPartName("Chapter 5.txt"))
    }

    @Test
    fun `a compressed chapter is not matched under its raw name`() {
        assertFalse(re.matches("Chapter 5.txt.gz"))
        assertTrue(Zips.isGzName("Chapter 5.txt.gz"))
        assertTrue(re.matches("Chapter 5.txt.gz".removeSuffix(".gz")))
    }

    @Test
    fun `unrelated files are not chapters`() {
        assertFalse(re.matches("cover.jpg"))
        assertFalse(re.matches("notes.txt"))
    }
}
