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

    /* THE DEFECT. "No. 12" is a number. The period+space rule treated
       the abbreviation as a sentence end, so TTS was handed "No."
       alone and said "no". */
    @Test
    fun `No-dot before a number stays one sentence`() {
        val body = "No. 12 was waiting at the door."
        val s = ReaderText.nextSentence(body, 0)
            ?: throw AssertionError("expected a sentence")
        assertEquals(body, body.substring(s.first, s.second))
    }

    @Test
    fun `No-dot mid-sentence before a number stays with its sentence`() {
        val body = "Room No. 5 is locked."
        val s = ReaderText.nextSentence(body, 0)
            ?: throw AssertionError("expected a sentence")
        assertEquals(body, body.substring(s.first, s.second))
    }

    @Test
    fun `a real sentence end before a number still splits`() {
        val body = "Hello. 12 was waiting at the door."
        val s = ReaderText.nextSentence(body, 0)
            ?: throw AssertionError("expected a sentence")
        assertEquals("Hello.", body.substring(s.first, s.second).trim())
    }

    /* THE DEFECT. The highlight stopped at "Mt." and "Tai!" was the next
       sentence. The period is the abbreviation, and the name is Mount Tai. */
    @Test
    fun `Mt-dot Tai stays one sentence`() {
        val body = "and Nie Yan. It was like the difference between a pebble and Mt. Tai! Simply recalling that attack."
        assertEquals("and Nie Yan.", sentence(body))
        assertEquals(
            "It was like the difference between a pebble and Mt. Tai!",
            sentence(body, at = body.indexOf("It was")),
        )
    }

    /* The same cut, measured across the captured chapters: a title, rank,
       or place abbreviation followed by the word it belongs to. */
    @Test
    fun `a title abbreviation stays with the word after it`() {
        val titles = listOf(
            "Mr", "Mrs", "Ms", "Dr", "Drs", "Prof", "Rev", "Hon", "Gen",
            "Capt", "Col", "Lt", "Lieut", "Maj", "Sgt", "Esq", "Jr", "Sr",
            "Mme", "Mlle", "Messrs", "Frl", "Wm", "Geo", "Chas", "Gov",
            "St", "Ste", "Mt", "Ft", "Vol", "Fig", "Nos", "Eq", "Ph", "vs",
        )
        for (title in titles) {
            val body = "See $title. Name today."
            assertEquals(title, body, sentence(body))
        }
        assertEquals(
            "Hearing Mrs. Li's words, she acted innocent.",
            sentence("Hearing Mrs. Li's words, she acted innocent."),
        )
        assertEquals(
            "Increases Perception vs. magic casters by 30%.",
            sentence("Increases Perception vs. magic casters by 30%."),
        )
    }

    @Test
    fun `an initial stays with the name and a roman numeral still ends`() {
        assertEquals(
            "The Blue Envelope by Roy J. Snell is a novel.",
            sentence("The Blue Envelope by Roy J. Snell is a novel."),
        )
        assertEquals(
            "written by W. A. Pearl today.",
            sentence("written by W. A. Pearl today."),
        )
        assertEquals(
            "M. Madeleine waited at the door.",
            sentence("M. Madeleine waited at the door."),
        )
        assertEquals(
            "in the U.S. Army today.",
            sentence("in the U.S. Army today."),
        )
        assertEquals(
            "end of a choir: e.g. Westminster.",
            sentence("end of a choir: e.g. Westminster."),
        )
        assertEquals("World War I.", sentence("World War I. His friendship ended."))
        assertEquals("6:00 a.m.", sentence("6:00 a.m. He woke at once."))
        assertEquals(
            "6:00 a.m. sharp, he woke.",
            sentence("6:00 a.m. sharp, he woke."),
        )
    }

    @Test
    fun `a lowercase word after a period stays in the sentence`() {
        assertEquals(
            "hoists, tackle, rope transmissions, etc. is ever increasing.",
            sentence("hoists, tackle, rope transmissions, etc. is ever increasing."),
        )
        assertEquals(
            "the recreancy, i.e. the tolerance of the old.",
            sentence("the recreancy, i.e. the tolerance of the old."),
        )
        assertEquals(
            "twenty per cent. of the statement was true.",
            sentence("twenty per cent. of the statement was true."),
        )
        assertEquals("\"Yes.\" he said quietly.", sentence("\"Yes.\" he said quietly."))
    }

    @Test
    fun `a real sentence end after an abbreviation-shaped word still splits`() {
        assertEquals("No.", sentence("No. She left the room."))
        assertEquals("Apples, oranges, etc.", sentence("Apples, oranges, etc. The next day he left."))
        assertEquals("not worth a cent.", sentence("not worth a cent. I cleaned out the place."))
        assertEquals("a freshly cracked fig.", sentence("a freshly cracked fig. For a long time."))
        assertEquals("Ask Jan.", sentence("Ask Jan. She knows the way."))
        assertEquals("Thanks, Ed.", sentence("Thanks, Ed. She smiled."))
        assertEquals("Thanks, Al.", sentence("Thanks, Al. He waved."))
        assertEquals("on the 1st.", sentence("on the 1st. But the next row differs."))
        assertEquals("\"Yes.\"", sentence("\"Yes.\" He left the room."))
        assertEquals("Fig. 1.", sentence("Fig. 1. The knot is simple."))
        assertEquals("around 1,86m. His hair was black.", sentence("around 1,86m. His hair was black."))
        assertEquals("Boston, Jan. 7th, 1839.", sentence("Boston, Jan. 7th, 1839."))
    }

    private fun sentence(body: String, at: Int = 0): String {
        val s = ReaderText.nextSentence(body, at)
            ?: throw AssertionError("expected a sentence in: $body")
        return body.substring(s.first, s.second)
    }

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
