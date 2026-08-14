package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelRatingTest {

    @Test
    fun `toggle sets then clears the same star`() {
        assertEquals(8, NovelRating.next(0, 8))
        assertEquals(0, NovelRating.next(8, 8))
    }

    @Test
    fun `picking a different star replaces the rating`() {
        assertEquals(7, NovelRating.next(3, 7))
        assertEquals(1, NovelRating.next(10, 1))
    }

    @Test
    fun `bar is full stars then empty stars`() {
        assertEquals("★★★★★★★★☆☆", NovelRating.bar(8))
        assertEquals("☆☆☆☆☆☆☆☆☆☆", NovelRating.bar(0))
        assertEquals("★★★★★★★★★★", NovelRating.bar(10))
    }

    @Test
    fun `bar never exceeds MAX`() {
        assertEquals(NovelRating.MAX, NovelRating.bar(99).length)
        assertEquals(NovelRating.MAX, NovelRating.bar(-1).length)
    }
}
