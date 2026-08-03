package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* Reading a listing from where it ended rather than from page 1.

   A spliced listing NAMES FILES BY POSITION, exactly as a full read does — so
   every case here is about the same question: does the prefix we did not read
   still mean what it meant last time? Refusing to resume costs one slow check.
   Resuming when the answer is no renames a library against a listing that has
   moved underneath it. */
class ResumeTest {

    private fun urls(from: Int, to: Int) = (from..to).map { "https://site/novel/chuong-$it/" }

    /* ---- mayResume ---- */

    /* the case the whole thing exists for: an ongoing novel, every chapter of
       it here, checked over and over for the two the site adds each week */
    @Test
    fun `a fully downloaded ongoing novel resumes`() {
        assertTrue(
            Resume.mayResume(
                Resume.Point(page = 4, url = "https://site/novel/trang-4/", before = 150),
                recordedSize = 200, total = 200, onDisk = 200, complete = false,
            ),
        )
    }

    /* A novel MISSING chapters is precisely the one the full check's rename
       and dedupe passes exist to repair. Resuming skips those passes, so a
       library with holes in it would stay broken for as long as the site kept
       adding chapters — for ever, for an ongoing novel. */
    @Test
    fun `a novel with chapters missing is read in full`() {
        assertFalse(
            "a novel short of its own listing needs the pass that repairs it",
            Resume.mayResume(
                Resume.Point(4, "https://site/novel/trang-4/", 150),
                recordedSize = 200, total = 200, onDisk = 187, complete = false,
            ),
        )
    }

    /* The recorded listing has to BE the site's count, or it was read from a
       listing with a hole in it and its end is not the novel's end. */
    @Test
    fun `an order that does not match the site count is read in full`() {
        assertFalse(
            Resume.mayResume(
                Resume.Point(4, "https://site/novel/trang-4/", 150),
                recordedSize = 160, total = 200, onDisk = 200, complete = false,
            ),
        )
    }

    @Test
    fun `a novel with no resume point is read in full`() {
        assertFalse(Resume.mayResume(null, 200, 200, 200, false))
    }

    /* A point that names no page, or a prefix longer than the listing it is a
       prefix of, is a record we cannot make sense of. */
    @Test
    fun `an unusable resume point is read in full`() {
        assertFalse(Resume.mayResume(Resume.Point(0, "", 0), 200, 200, 200, false))
        assertFalse(
            Resume.mayResume(Resume.Point(4, "https://site/novel/trang-4/", 900), 200, 200, 200, false),
        )
    }

    /* ---- splice ---- */

    @Test
    fun `new chapters at the end are appended`() {
        val recorded = urls(1, 100)
        val got = Resume.splice(recorded, before = 90, tail = urls(91, 103))
        assertNotNull(got)
        assertEquals(103, got!!.urls.size)
        assertEquals(3, got.added)
        assertEquals(urls(1, 103), got.urls)
    }

    /* the ordinary quiet week: the site has added nothing, and the check has
       to say so rather than invent a change */
    @Test
    fun `an unchanged listing splices back to itself`() {
        val recorded = urls(1, 100)
        val got = Resume.splice(recorded, before = 90, tail = urls(91, 100))
        assertNotNull(got)
        assertEquals(0, got!!.added)
        assertEquals(recorded, got.urls)
    }

    /* A one-page novel: nothing precedes the resume page, so the tail IS the
       whole listing. */
    @Test
    fun `a novel that fits on one page splices from nothing`() {
        val got = Resume.splice(urls(1, 12), before = 0, tail = urls(1, 14))
        assertNotNull(got)
        assertEquals(urls(1, 14), got!!.urls)
        assertEquals(2, got.added)
    }

    /* THE DEFECT THIS GUARDS. The site inserts a chapter part-way through the
       resume page, so everything after it moves down one. Splicing on
       position alone would keep the old prefix and file every later chapter
       one place early — the whole tail of the novel renamed onto its
       neighbour. The join has to be checked by URL. */
    @Test
    fun `a listing that shifted under the join is refused`() {
        val recorded = urls(1, 100)
        val tail = listOf("https://site/novel/chuong-90b/") + urls(91, 100)
        assertNull(
            "a chapter inserted at the join moves every position after it",
            Resume.splice(recorded, before = 90, tail = tail),
        )
    }

    /* Chapters have been REMOVED — the novel is shorter than it was. That is
       the full read's dedupe pass's business, and it decides by identity what
       is surplus; a splice can only append, so it must hand the novel over
       rather than paper over the loss. */
    @Test
    fun `a listing that lost chapters is refused`() {
        assertNull(Resume.splice(urls(1, 100), before = 90, tail = urls(91, 96)))
    }

    /* A library older than recorded pages, or one whose chapters simply have
       no URL against them, has empty entries. Refusing on those would mean it
       could never resume at all — and an absence of evidence is not a
       mismatch. */
    @Test
    fun `positions with no recorded url do not count as a mismatch`() {
        val recorded = urls(1, 89) + listOf("", "", "") + urls(93, 100)
        val got = Resume.splice(recorded, before = 89, tail = urls(90, 101))
        assertNotNull(got)
        assertEquals(1, got!!.added)
        assertEquals(urls(1, 101), got.urls)
    }

    @Test
    fun `a prefix longer than the recorded listing is refused`() {
        assertNull(Resume.splice(urls(1, 100), before = 101, tail = urls(101, 105)))
    }

    /* ---- headIntact ---- */

    /* The splice only ever checks the JOIN, so a chapter inserted at the
       FRONT would move every position by one and the join would still agree
       with itself. The novel page is fetched on every check anyway, which
       makes reading its links free. */
    @Test
    fun `a chapter inserted at the front is caught`() {
        val recorded = urls(1, 100)
        val head = listOf("https://site/novel/chuong-0/") + urls(1, 49)
        assertFalse(Resume.headIntact(recorded, head))
    }

    @Test
    fun `an unchanged front passes`() {
        assertTrue(Resume.headIntact(urls(1, 100), urls(1, 50)))
    }

    /* On a one-page novel the head IS the tail, and new chapters at the end
       are the ordinary case — a head running past the recorded listing is not
       evidence of anything. */
    @Test
    fun `a head longer than the recorded listing is not a shift`() {
        assertTrue(Resume.headIntact(urls(1, 12), urls(1, 14)))
    }

    /* ---- recordedListing ---- */

    @Test
    fun `the recorded listing is ordered by position, not by name`() {
        val order = mapOf("Chapter 1.txt" to 0, "Chapter 2.txt" to 1, "Chapter 3.txt" to 2)
        val urls = mapOf(
            "Chapter 3.txt" to "c3", "Chapter 1.txt" to "c1", "Chapter 2.txt" to "c2",
        )
        assertEquals(listOf("c1", "c2", "c3"), Resume.recordedListing(order, urls))
    }

    /* A chapter with no recorded page still HOLDS ITS POSITION. Dropping it
       would shorten the listing and move everything after it up a slot, which
       is the same damage the splice refuses to do. */
    @Test
    fun `a chapter with no recorded page keeps its slot`() {
        val order = mapOf("Chapter 1.txt" to 0, "Chapter 2.txt" to 1, "Chapter 3.txt" to 2)
        assertEquals(
            listOf("c1", "", "c3"),
            Resume.recordedListing(order, mapOf("Chapter 1.txt" to "c1", "Chapter 3.txt" to "c3")),
        )
    }

    @Test
    fun `an unindexed novel has no recorded listing`() {
        assertEquals(emptyList<String>(), Resume.recordedListing(emptyMap(), emptyMap()))
    }
}
