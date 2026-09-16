package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* Sentence and paragraph walking over a window of chapter bodies — the
   same questions the reader asks while speaking, skipping, and restoring
   a listen spot. There is no concatenated buffer here. */
class ReaderTextTest {

    private val ch49 = "Chapter 49\nThe old story ends here."
    private val ch50 = "Chapter 50\nThe story continues.\nA second paragraph."
    private val ch51 = "Chapter 51\nThe next book begins."
    private val bodies = listOf(ch49, ch50, ch51)

    @Test
    fun `a tap in the middle of a sentence starts at that sentence`() {
        val off = ch50.indexOf("continues")
        assertEquals(ch50.indexOf("The story"), ReaderText.sentStartOf(ch50, off))
    }

    @Test
    fun `a paragraph restore is already at a paragraph start`() {
        val para = ch50.indexOf("A second")
        assertEquals(para, ReaderText.sentStartOf(ch50, para))
    }

    @Test
    fun `paragraphs are counted from the chapter start`() {
        assertEquals(0, ReaderText.paragraphIndex(ch50, 0))
        assertEquals(2, ReaderText.paragraphIndex(ch50, ch50.indexOf("A second")))
    }

    @Test
    fun `an index that outruns the chapter stays inside it`() {
        assertEquals(
            ch50.indexOf("A second"),
            ReaderText.offsetOfPara(ch50, 99),
        )
    }

    @Test
    fun `a drifted paragraph index is corrected by the stored text`() {
        val off = ReaderText.restoreOffsetIn(ch50, para = 0, anchorText = "A second paragraph.")
        assertEquals(ch50.indexOf("A second"), off)
    }

    @Test
    fun `speaking walks into the next chapter when this one ends`() {
        val end = ReaderPlace(0, ch49.length)
        val (s0, s1) = ReaderText.nextSpoken(bodies, end)
            ?: throw AssertionError("expected the next chapter")
        assertEquals(ReaderPlace(1, 0), s0)
        assertEquals("Chapter 50", ch50.substring(s0.off, s1.off))
    }

    @Test
    fun `the last sentence of the window has nothing after it`() {
        assertNull(ReaderText.nextSpoken(bodies, ReaderPlace(2, ch51.length)))
    }

    @Test
    fun `a chapter crossing is a chapter pause even without the old separator`() {
        val after = ReaderPlace(0, ch49.length)
        val next = ReaderPlace(1, 0)
        val (gap, crosses) = ReaderText.pauseGap(bodies, after, next)
        assertTrue(crosses)
        assertEquals(TtsPause.Level.CHAPTER, TtsPause.level(gap, crosses))
    }

    @Test
    fun `a newline inside one chapter is still the paragraph pause`() {
        val after = ReaderPlace(1, ch50.indexOf('\n'))
        val next = ReaderPlace(1, ch50.indexOf("The story"))
        val (gap, crosses) = ReaderText.pauseGap(bodies, after, next)
        assertFalse(crosses)
        assertEquals(TtsPause.Level.PARAGRAPH, TtsPause.level(gap, crosses))
    }

    @Test
    fun `skipping forward at the last paragraph lands on the next chapter`() {
        val from = ReaderPlace(1, ch50.indexOf("A second"))
        assertEquals(ReaderPlace(2, 0), ReaderText.skipParagraph(bodies, from, forward = true))
    }

    @Test
    fun `skipping backward at a chapter top lands on the previous last paragraph`() {
        assertEquals(
            ReaderPlace(1, ch50.indexOf("A second")),
            ReaderText.skipParagraph(bodies, ReaderPlace(2, 0), forward = false),
        )
    }

    @Test
    fun `skipping backward mid-paragraph goes to that paragraph's first sentence`() {
        val mid = ch50.indexOf("continues")
        val start = ch50.indexOf("The story")
        assertEquals(
            ReaderPlace(1, start),
            ReaderText.skipParagraph(bodies, ReaderPlace(1, mid), forward = false),
        )
    }

    @Test
    fun `a chapter skip mid-chapter goes to that chapter's top`() {
        val mid = ch50.indexOf("continues")
        assertEquals(
            ReaderPlace(1, 0),
            ReaderText.skipChapter(bodies, ReaderPlace(1, mid), forward = false),
        )
    }

    @Test
    fun `a chapter skip at a top goes to the neighbour`() {
        assertEquals(
            ReaderPlace(2, 0),
            ReaderText.skipChapter(bodies, ReaderPlace(1, 0), forward = true),
        )
        assertEquals(
            ReaderPlace(0, 0),
            ReaderText.skipChapter(bodies, ReaderPlace(1, 0), forward = false),
        )
    }

    @Test
    fun `a chapter skip past the loaded window is nowhere`() {
        assertNull(ReaderText.skipChapter(bodies, ReaderPlace(2, 0), forward = true))
        assertNull(ReaderText.skipChapter(bodies, ReaderPlace(0, 0), forward = false))
    }

    @Test
    fun `prepending chapters keeps the same character in the same body`() {
        val spoken = ReaderPlace(1, 12)
        assertEquals(ReaderPlace(3, 12), spoken.afterInsert(insertedAt = 0, inserted = 2))
        assertEquals(spoken, spoken.afterInsert(insertedAt = 2, inserted = 2))
    }
}
