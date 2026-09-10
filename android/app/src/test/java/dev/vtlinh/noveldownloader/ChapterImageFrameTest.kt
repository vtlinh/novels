package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterImageFrameTest {

    @Test
    fun `reported height undoes the reader line-spacing multiplier`() {
        assertEquals(100, ChapterImageSpan.lineHeightFor(145, 1.45f, 0f))
        assertEquals(100, ChapterImageSpan.lineHeightFor(155, 1.45f, 10f))
        assertEquals(1, ChapterImageSpan.lineHeightFor(0, 1.45f, 0f))
        assertEquals(80, ChapterImageSpan.lineHeightFor(80, 0f, 0f))
    }

    @Test
    fun `empty caption adds no height under the picture`() {
        assertEquals(0, ChapterImageSpan.extraForCaption(0, 8))
        assertEquals(0, ChapterImageSpan.extraForCaption(-1, 8))
        assertEquals(28, ChapterImageSpan.extraForCaption(20, 8))
    }
}
