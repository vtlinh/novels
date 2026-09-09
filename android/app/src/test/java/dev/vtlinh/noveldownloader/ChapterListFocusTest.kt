package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* Where the chapter list lands when opened from the reader, and whether
   a scroll event may grow the window before that landing has happened. */
class ChapterListFocusTest {

    /* THE DEFECT. ≡ used to open the in-reader drawer, which jumped to
       the spoken chapter from memory. The novel page reads prefs and
       posts the jump. lastCh is the viewport top — a fifth of a page
       above the spoken line — so an earlier TTS chapter in prefs must
       not beat the name the reader just handed over. */
    @Test
    fun `the chapter the reader is on beats a stale prefs name`() {
        assertEquals(
            "Chapter 80.txt",
            ChapterListFocus.currentName("Chapter 80.txt", "Chapter 50.txt"),
        )
    }

    @Test
    fun `a title suffix still finds the same chapter`() {
        val ordered = listOf(
            "Chapter 79.txt",
            "Chapter 80 - The Gate.txt",
            "Chapter 81.txt",
        )
        assertEquals(1, ChapterListFocus.indexIn(ordered, "Chapter 80.txt"))
        assertEquals(1, ChapterListFocus.indexIn(ordered, "Chapter 80 - The Gate.txt"))
        assertEquals(-1, ChapterListFocus.indexIn(ordered, "Chapter 80 (unlisted).txt"))
        assertEquals(-1, ChapterListFocus.indexIn(ordered, null))
        assertEquals(-1, ChapterListFocus.indexIn(ordered, ""))
    }

    @Test
    fun `prefs are the fallback when the reader did not name a chapter`() {
        assertEquals(
            "Chapter 50.txt",
            ChapterListFocus.currentName(null, "Chapter 50.txt"),
        )
        assertEquals(
            "Chapter 50.txt",
            ChapterListFocus.currentName("", "Chapter 50.txt"),
        )
        assertNull(ChapterListFocus.currentName(null, null))
        assertNull(ChapterListFocus.currentName("", ""))
    }

    /* THE DEFECT. The first onScroll fires at the top of the window,
       before the posted jump. Growing then walks the window back to
       chapter 1 and the jump lands on a stale index. */
    @Test
    fun `the window must not grow while the jump to the current chapter is pending`() {
        assertFalse(ChapterListFocus.mayGrow(settling = true))
        assertTrue(ChapterListFocus.mayGrow(settling = false))
    }
}
