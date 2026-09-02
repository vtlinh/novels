package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/* What is stripped from a sentence before TTS speaks it.

   Google reads a leading quote+dots as "single quote dot dot dot". The
   old default was only the one-character … at column 0, and a literal
   rule for ‘ left the closing ’ standing — two different characters
   that look the same. */
class SpeechTextTest {

    private val punct = SpeechText.punctuationRules()

    private fun spoken(text: String) = SpeechText.apply(punct, text)

    /* THE DEFECT. This is the paragraph on the speech-edit test box:
       a curly (or straight) quote, three dots, the line. TTS said
       "single quote dot dot dot End of the road". */
    @Test
    fun `leading quote and dots are silenced`() {
        assertEquals(
            "End of the road, I guess.",
            spoken("'...End of the road, I guess.'"),
        )
        assertEquals(
            "End of the road, I guess.",
            spoken("\u2018...End of the road, I guess.\u2019"),
        )
        assertEquals(
            "End of the road, I guess.",
            spoken("\u2018\u2026End of the road, I guess.\u2019"),
        )
        assertEquals(
            "End of the road, I guess.",
            spoken("\u2026End of the road, I guess."),
        )
        assertEquals(
            "End of the road, I guess.",
            spoken("...End of the road, I guess."),
        )
    }

    @Test
    fun `an apostrophe inside a word is kept`() {
        assertEquals("don't", spoken("don't"))
        assertEquals("I'm sure", spoken("I'm sure"))
    }

    @Test
    fun `a mid-sentence ellipsis is silenced`() {
        assertEquals("Wait then go", spoken("Wait... then go"))
        assertEquals("Wait then go", spoken("Wait\u2026 then go"))
    }

    /* A rule typed as one quote has to hit the other quote too.
       The preview deleted the opener and left the closer, and that
       closer is what the engine then read. */
    @Test
    fun `a literal quote rule matches every quote that looks like it`() {
        val left = Regex(SpeechText.literalPattern("\u2018", false)) to ""
        val straight = Regex(SpeechText.literalPattern("'", false)) to ""
        val text = "\u2018...End of the road, I guess.\u2019"
        assertEquals("...End of the road, I guess.", SpeechText.apply(listOf(left), text))
        assertEquals("...End of the road, I guess.", SpeechText.apply(listOf(straight), text))
    }

    @Test
    fun `a literal quote rule does not eat an apostrophe`() {
        val rule = Regex(SpeechText.literalPattern("'", false)) to ""
        assertEquals("don't", SpeechText.apply(listOf(rule), "don't"))
    }

    @Test
    fun `an empty replacement deletes the match`() {
        val rule = Regex("xyz") to ""
        assertEquals("ab", SpeechText.apply(listOf(rule), "axyzb"))
    }

    @Test
    fun `a non-quote literal is still escaped`() {
        val p = SpeechText.literalPattern("a+b", false)
        assertFalse("the plus must stay a literal", Regex(p).containsMatchIn("aab"))
        assertEquals("a+b", Regex(p).find("xa+by")!!.value)
    }
}
