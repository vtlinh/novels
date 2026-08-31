package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/* Where the EN/VI toggle reloads. Paragraphs map 1:1 across languages, so
   the question is only which chapter and paragraph the reload keeps. */
class ReaderPosTest {

    private val body = "end of 49\n\n⁂\n\nChapter 50\nThe story continues"
    private val ch50 = body.indexOf("Chapter 50")
    private val starts = listOf(0, ch50)

    /* THE BUG. TTS parks the spoken line a fifth of a page down, so early
       in chapter 50 the viewport top is still chapter 49. Reloading from
       that top wrote lastCh to 49; resumeChapter then preferred it over
       ttsPos and the restore found no spot in 49. */
    @Test
    fun `a listen spot in the new chapter wins over a viewport still in the previous one`() {
        val off = ReaderPos.reloadOffset(resumeOff = ch50, viewOff = 0)
        assertEquals(
            "the spoken chapter, not the one still at the top of the screen",
            1 to 0,
            ReaderPos.positionAt(starts, body, off),
        )
    }

    @Test
    fun `with no listen spot the viewport chapter is kept`() {
        val off = ReaderPos.reloadOffset(resumeOff = -1, viewOff = ch50)
        assertEquals(1 to 0, ReaderPos.positionAt(starts, body, off))
    }

    @Test
    fun `paragraphs are counted from the chapter start`() {
        val text = "Ch49\n\n⁂\n\nCh50\npara two\npara three"
        val start = text.indexOf("Ch50")
        assertEquals(
            1 to 2,
            ReaderPos.positionAt(listOf(0, start), text, text.indexOf("para three")),
        )
    }

    @Test
    fun `an offset before every chapter is nowhere`() {
        assertNull(ReaderPos.positionAt(listOf(10, 20), "abc", 0))
    }
}
