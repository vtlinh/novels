package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlugMergeTest {

    @Test
    fun `merge copies the personal star ranking`() {
        assertTrue(
            "folder-scan ratings live under novelRating:\$slug; dropping the " +
                "prefix made a rated novel look unrated after the site row won",
            "novelRating:" in SlugMerge.PREF_PREFIXES,
        )
        assertEquals(
            "SlugMerge and NovelRating must name the same pref",
            NovelRating.PREF_PREFIX,
            "novelRating:",
        )
    }

    @Test
    fun `merge still copies the finished mark and reading place`() {
        for (k in listOf("novelRead:", "lastCh:", "ttsPos:")) {
            assertTrue("$k must travel with the scan row", k in SlugMerge.PREF_PREFIXES)
        }
    }

    @Test
    fun `a last-read on the scan row is kept when the site row was never opened`() {
        assertEquals(50L, SlugMerge.lastReadToCarry(0, 50))
    }

    @Test
    fun `a newer last-read on the winner is left alone`() {
        assertNull(SlugMerge.lastReadToCarry(100, 50))
        assertNull(SlugMerge.lastReadToCarry(50, 50))
        assertNull(SlugMerge.lastReadToCarry(0, 0))
    }
}
