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

    /* A finished story gains nothing, so there is nothing for a resumed read
       to find. The Library's sweep skips such a novel outright; the per-novel
       Check button does not, and it tells the reader which of the two reads it
       is about to do — so this has to be the same answer the check itself
       gives, or the screen promises one thing and does another. */
    @Test
    fun `a finished novel is read in full`() {
        assertFalse(
            Resume.mayResume(
                Resume.Point(4, "https://site/novel/trang-4/", 150),
                recordedSize = 200, total = 200, onDisk = 200, complete = true,
            ),
        )
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

    /* THE ONE WITH NO EVIDENCE AT ALL. `fileUrls` only returns chapters whose
       page was recorded, and a library adopted by the folder scan has none —
       it is indexed by name with no page against any row. One full check still
       fills in its chapter_order, so it arrives here with a listing of the
       right LENGTH and not one URL in it.

       Every comparison is then skipped and the join succeeds whatever the site
       has done. The order that gets written names files by positions nothing
       verified, the reader sorts by exactly that order, and an auto-download
       fetches the "new" chapters into filenames belonging to chapters already
       on disk. Reading the whole listing is the right answer there: that pass
       renames and dedupes by identity, which is what gives such a library its
       recorded pages in the first place. */
    @Test
    fun `a listing with no recorded urls at all cannot verify a join`() {
        val recorded = List(100) { "" }
        assertNull(
            "a join checked against nothing has not been checked",
            Resume.splice(recorded, before = 90, tail = urls(91, 103)),
        )
    }

    /* ...and one URL in the overlap is enough to make the join real. The
       refusal above must be about the ABSENCE of evidence, not about empty
       entries being present — a library with a handful of unrecorded chapters
       is ordinary and must still resume. */
    @Test
    fun `one recorded url in the overlap is enough`() {
        val recorded = List(99) { "" } + urls(100, 100)
        val got = Resume.splice(recorded, before = 90, tail = urls(91, 103))
        assertNotNull(got)
        assertEquals(3, got!!.added)
    }

    /* A prefix that reaches the end of the recorded listing leaves no
       overlapping position to compare, so there is no join to check. The
       resume page is the page that held the listing's LAST chapters, so this
       cannot arise from a point and an order written together — it means the
       two have come apart. */
    @Test
    fun `a prefix that covers the whole recorded listing is refused`() {
        assertNull(Resume.splice(urls(1, 100), before = 100, tail = urls(101, 104)))
        assertFalse(
            Resume.mayResume(
                Resume.Point(4, "https://site/novel/trang-4/", 200),
                recordedSize = 200, total = 200, onDisk = 200, complete = false,
            ),
        )
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

    /* ---- tailComplete ---- */

    /* THE HOLE THIS RULE CLOSED. A throttle page answers 200 with no chapter
       list; the first version of the resumed check accepted any number of
       those as long as they trailed the last page with links — so the tail
       came back short, the splice still lined up, and the site's new
       chapters read as "up to date". With the finished flag in the same
       answer, the novel latched complete at the short count and dropped out
       of every future sweep. A blank page is never forgivable: only a
       genuine 404 can be the pagination over-reading. */
    @Test
    fun `a trailing 200-blank page is not a readable tail`() {
        assertFalse(
            Resume.tailComplete(
                missed = setOf(6), gone = emptySet(), loaded = setOf(5), last = 6,
            ),
        )
    }

    /* the one shape a pagination over-read takes: a single trailing page
       that answered a real 404 — the full read forgives exactly this, and
       the resumed read must not be stricter or every over-reading novel
       loses its shortcut for good */
    @Test
    fun `a single trailing 404 is the over-read and is forgiven`() {
        assertTrue(
            Resume.tailComplete(
                missed = setOf(6), gone = setOf(6), loaded = setOf(5), last = 6,
            ),
        )
    }

    @Test
    fun `two trailing 404s are a gap, not a miscount`() {
        assertFalse(
            Resume.tailComplete(
                missed = setOf(6, 7), gone = setOf(6, 7), loaded = setOf(5), last = 7,
            ),
        )
    }

    @Test
    fun `a hole before the last real page is never forgiven`() {
        assertFalse(
            Resume.tailComplete(
                missed = setOf(5), gone = setOf(5), loaded = setOf(4, 6), last = 6,
            ),
        )
    }

    @Test
    fun `a fully read tail passes`() {
        assertTrue(
            Resume.tailComplete(
                missed = emptySet(), gone = emptySet(), loaded = setOf(5, 6), last = 6,
            ),
        )
    }
}
