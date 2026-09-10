package dev.vtlinh.noveldownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* A Slack image wait older than an hour is dropped. Auto-generate
   picks chapters N ≥ from where (N − from) is a multiple of every. */
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

    @Test
    fun `defaults pick chapter 1 then every 20`() {
        val from = ChapterImages.AUTO_FROM_DEFAULT
        val every = ChapterImages.AUTO_EVERY_DEFAULT
        assertTrue(ChapterImages.due(1, from, every))
        assertFalse(ChapterImages.due(2, from, every))
        assertFalse(ChapterImages.due(20, from, every))
        assertTrue(ChapterImages.due(21, from, every))
        assertFalse(ChapterImages.due(40, from, every))
        assertTrue(ChapterImages.due(41, from, every))
    }

    @Test
    fun `a later start skips earlier chapters`() {
        assertFalse(ChapterImages.due(1, 5, 10))
        assertFalse(ChapterImages.due(4, 5, 10))
        assertTrue(ChapterImages.due(5, 5, 10))
        assertFalse(ChapterImages.due(6, 5, 10))
        assertFalse(ChapterImages.due(10, 5, 10))
        assertTrue(ChapterImages.due(15, 5, 10))
        assertTrue(ChapterImages.due(25, 5, 10))
    }

    @Test
    fun `every chapter from the start when the step is 1`() {
        assertTrue(ChapterImages.due(3, 3, 1))
        assertTrue(ChapterImages.due(4, 3, 1))
        assertFalse(ChapterImages.due(2, 3, 1))
    }

    @Test
    fun `a zero or negative step matches nothing`() {
        assertFalse(ChapterImages.due(1, 1, 0))
        assertFalse(ChapterImages.due(21, 1, 0))
        assertFalse(ChapterImages.due(1, 1, -20))
        assertFalse(ChapterImages.due(5, 0, 20))
        assertFalse(ChapterImages.due(5, -1, 20))
    }
}
