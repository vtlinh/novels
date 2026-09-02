package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* Which pause a gap between two spoken sentences asks for, and how the
   stepper turns a tap into a duration.

   The engine is not here. The deciding is: a chapter break must not fall
   through to the paragraph duration, 0.1 must be 100 ms, and minus at
   zero must stay zero. */
class TtsPauseTest {

    @Test
    fun `a space between sentences is the sentence pause`() {
        assertEquals(TtsPause.Level.SENTENCE, TtsPause.level(" ", crossesChapter = false))
        assertEquals(TtsPause.Level.SENTENCE, TtsPause.level("", crossesChapter = false))
    }

    @Test
    fun `a newline is the paragraph pause`() {
        assertEquals(TtsPause.Level.PARAGRAPH, TtsPause.level("\n", crossesChapter = false))
        assertEquals(TtsPause.Level.PARAGRAPH, TtsPause.level(" \n\n ", crossesChapter = false))
    }

    /* THE RULE. The ⁂ chapter separator sits in a run of newlines, so a
       gap that only looked at '\n' would pick the paragraph duration and
       the chapter setting would never fire. */
    @Test
    fun `a chapter separator is the chapter pause, not the paragraph one`() {
        assertEquals(TtsPause.Level.CHAPTER, TtsPause.level("\n\n⁂\n\n", crossesChapter = false))
        assertEquals(TtsPause.Level.CHAPTER, TtsPause.level("\n", crossesChapter = true))
    }

    @Test
    fun `a chapter start between the two sentences is a chapter crossing`() {
        assertTrue(TtsPause.crossesChapter(98, 105, listOf(0, 105)))
        assertFalse(TtsPause.crossesChapter(98, 105, listOf(0)))
        assertFalse("the chapter we are already in is not a crossing", TtsPause.crossesChapter(10, 40, listOf(0)))
        assertFalse("a later chapter is not this gap", TtsPause.crossesChapter(10, 40, listOf(0, 200)))
    }

    @Test
    fun `zero is the floor and the default display`() {
        assertEquals(0f, TtsPause.step(0f, -TtsPause.STEP))
        assertEquals(0f, TtsPause.seconds(-1f))
        assertEquals("0.0", TtsPause.format(0f))
        assertEquals(0L, TtsPause.millis(0f))
    }

    @Test
    fun `a tap moves by a tenth of a second`() {
        assertEquals(0.1f, TtsPause.step(0f, TtsPause.STEP))
        assertEquals(0.2f, TtsPause.step(0.1f, TtsPause.STEP))
        assertEquals(0.1f, TtsPause.step(0.2f, -TtsPause.STEP))
        assertEquals(100L, TtsPause.millis(0.1f))
        assertEquals("0.1", TtsPause.format(0.1f))
    }

    @Test
    fun `the cap stops a long run of plus taps`() {
        assertEquals(TtsPause.MAX, TtsPause.step(TtsPause.MAX, TtsPause.STEP))
        assertEquals(TtsPause.MAX, TtsPause.seconds(99f))
    }

    @Test
    fun `each level has its own pref key`() {
        assertEquals("ttsPauseSentence", TtsPause.key(TtsPause.Level.SENTENCE))
        assertEquals("ttsPauseParagraph", TtsPause.key(TtsPause.Level.PARAGRAPH))
        assertEquals("ttsPauseChapter", TtsPause.key(TtsPause.Level.CHAPTER))
    }
}
