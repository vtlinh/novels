package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
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
    fun `bar is a count times a star`() {
        assertEquals("8★", NovelRating.bar(8))
        assertEquals("0★", NovelRating.bar(0))
        assertEquals("10★", NovelRating.bar(10))
        assertEquals("1★", NovelRating.bar(1))
    }

    @Test
    fun `bar never exceeds MAX`() {
        assertEquals("10★", NovelRating.bar(99))
        assertEquals("0★", NovelRating.bar(-1))
    }

    @Test
    fun `the site's rating stands in until the user rates the novel`() {
        assertEquals(9, NovelRating.effective(0, 8.7))
        assertEquals(6, NovelRating.effective(0, 6.0))
        assertEquals(4, NovelRating.effective(4, 8.7))
        assertEquals(10, NovelRating.effective(0, 10.0))
    }

    @Test
    fun `no site rating and no own rating is no stars`() {
        assertEquals(0, NovelRating.effective(0, -1.0))
        assertEquals(0, NovelRating.effective(0, 0.0))
        assertEquals(7, NovelRating.effective(7, -1.0))
    }

    @Test
    fun `a low site rating is still one star, not none`() {
        assertEquals(1, NovelRating.effective(0, 0.3))
        assertEquals(1, NovelRating.effective(0, 1.0))
    }
}
