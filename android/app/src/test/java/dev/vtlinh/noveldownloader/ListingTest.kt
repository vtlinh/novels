package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/* Which listing pages may be written off as "the page count over-read the
   pagination", and where a partial listing stops being trustworthy.

   Every case here is a bug that actually shipped. Forgiving a page means its
   chapters are treated as never having existed and the dedupe deletes their
   files, so the cost of getting this wrong is measured in deleted chapters. */
class ListingTest {

    /* the ordinary reason this rule exists: maxPage says 90, page 90 has
       never existed, and the novel must not be stuck as "incomplete" forever */
    @Test
    fun `single phantom page past the end is forgiven`() {
        val forgivable = Listing.forgivableTailPages(
            missed = setOf(90),
            gone = setOf(90),
            loaded = (2..89).toSet(),
            lastPage = 90,
        )
        assertEquals(listOf(90), forgivable)
    }

    /* a 404 with real pages after it is a hole, not an over-read: forgiving
       it made the listing look complete while ~50 chapters were absent, and
       the rename pass then renumbered the novel around them */
    @Test
    fun `404 in the middle is never forgiven`() {
        val forgivable = Listing.forgivableTailPages(
            missed = setOf(45),
            gone = setOf(45),
            loaded = (2..90).toSet() - 45,
            lastPage = 90,
        )
        assertTrue("a hole with real pages after it must stay a hole", forgivable.isEmpty())
    }

    /* eleven pages at the end going quiet is 550 chapters, not an off-by-one.
       Forgiving that wholesale deleted every one of their files. */
    @Test
    fun `long tail of missing pages is treated as a gap not a miscount`() {
        val gone = (80..90).toSet()
        val forgivable = Listing.forgivableTailPages(
            missed = gone,
            gone = gone,
            loaded = (2..79).toSet(),
            lastPage = 90,
        )
        assertTrue("11 of 90 pages is too much to write off", forgivable.isEmpty())
    }

    /* the site changes its pagination URL scheme and EVERY page 404s. The
       listing must come back short, not "complete" with only page 1's
       chapters — that version deleted the rest of the novel. */
    @Test
    fun `nothing past page one loading is a gap not a complete listing`() {
        val gone = (2..90).toSet()
        val forgivable = Listing.forgivableTailPages(
            missed = gone,
            gone = gone,
            loaded = emptySet(),
            lastPage = 90,
        )
        assertTrue("an all-404 listing must not read as complete", forgivable.isEmpty())
    }

    /* a page that failed for a reason OTHER than "not there" — throttled,
       blocked, timed out — is a page that exists and wouldn't load */
    @Test
    fun `a page that did not report missing is never forgiven`() {
        val forgivable = Listing.forgivableTailPages(
            missed = setOf(90),
            gone = emptySet(),           // 429/503/timeout, not 404
            loaded = (2..89).toSet(),
            lastPage = 90,
        )
        assertTrue("only a page that says 'not there' can be an over-read", forgivable.isEmpty())
    }

    /* a small novel must not be refused its one over-read page just because
       lastPage/20 rounds to zero */
    @Test
    fun `one page is always forgivable however small the novel`() {
        val forgivable = Listing.forgivableTailPages(
            missed = setOf(3),
            gone = setOf(3),
            loaded = setOf(2),
            lastPage = 3,
        )
        assertEquals(listOf(3), forgivable)
    }

    /* the tail must be contiguous back to the last real page: a gap with a
       throttled page inside it is not an over-read */
    @Test
    fun `tail broken by a non-missing page is not forgiven`() {
        val forgivable = Listing.forgivableTailPages(
            missed = setOf(88, 90),
            gone = setOf(90),            // 88 failed for some other reason
            loaded = (2..87).toSet(),
            lastPage = 90,
        )
        assertTrue("page 89/88 unaccounted for breaks the tail", forgivable.isEmpty())
    }

    /* A page that answers 200 with no chapters on it — a soft 404, a WAF
       interstitial, a layout change — is put back with the pages that didn't
       load and judged by this same rule. The two cases it has to separate:
       a blank page in the MIDDLE takes its chapters into the dedupe as ones
       the site no longer lists... */
    @Test
    fun `a blank page inside the book is a hole`() {
        val forgivable = Listing.forgivableTailPages(
            missed = setOf(45),
            gone = setOf(45),            // blank pages are recorded as "not there"
            loaded = (2..90).toSet() - 45,
            lastPage = 90,
        )
        assertTrue(forgivable.isEmpty())
    }

    /* ...while a blank page past the last real one is the page count
       over-reading, and blocking on it would leave the novel permanently
       un-renamed and un-deduped. Note `loaded` must EXCLUDE the blank page:
       counting it as loaded is what made the old check skip the last page
       entirely, which is exactly where a soft 404 lands. */
    @Test
    fun `a blank page past the end is an over-read`() {
        val forgivable = Listing.forgivableTailPages(
            missed = setOf(90),
            gone = setOf(90),
            loaded = (2..89).toSet(),
            lastPage = 90,
        )
        assertEquals(listOf(90), forgivable)
    }

    @Test
    fun `first gap is where collecting stops`() {
        assertEquals(11, Listing.firstGap(setOf(11, 40), lastPage = 90))
    }

    @Test
    fun `no gaps means collect everything`() {
        assertEquals(91, Listing.firstGap(emptySet(), lastPage = 90))
    }
}
