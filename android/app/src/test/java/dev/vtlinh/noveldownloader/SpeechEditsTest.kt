package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Test

/* Built-in speech edits applied to a sentence before TTS speaks it.

   "No. 12" is a number. Google reads the abbreviation as "no", so the
   default rule rewrites it to "number 12". The sentence splitter has
   to keep the two halves together or this rule never sees them. */
class SpeechEditsTest {

    private val defaults = SpeechEdits.defaults.mapNotNull { it.compiled() }

    private fun spoken(text: String) = SpeechText.apply(defaults, text)

    /* THE DEFECT. TTS said "no 12" for the printed "No. 12". */
    @Test
    fun `No-dot before a number is spoken as number`() {
        assertEquals("number 12 was waiting.", spoken("No. 12 was waiting."))
        assertEquals("Room number 5 is locked.", spoken("Room No. 5 is locked."))
        assertEquals("number 7", spoken("No.7"))
        assertEquals("number 3", spoken("NO. 3"))
    }

    @Test
    fun `a lone No-dot is still not read as no-period`() {
        assertEquals("No; She left.", spoken("No. She left."))
    }
}
