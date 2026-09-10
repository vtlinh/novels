package dev.vtlinh.noveldownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* A Slack image wait older than an hour is dropped. */
class ChapterImagesTest {

    @Test
    fun `a wait is stale after one hour`() {
        val start = 1_000_000L
        assertFalse(ChapterImages.expired(start, start))
        assertFalse(ChapterImages.expired(start, start + ChapterImages.GIVE_UP_MS - 1))
        assertTrue(ChapterImages.expired(start, start + ChapterImages.GIVE_UP_MS))
        assertTrue(ChapterImages.expired(start, start + ChapterImages.GIVE_UP_MS + 60_000L))
        assertTrue(ChapterImages.expired(0L, start))
    }
}
