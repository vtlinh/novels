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

    private fun regexRule(id: String, pattern: String, replace: String) =
        SpeechEdit(id, "", 2, false, pattern, replace, true, false)

    /* THE DEFECT. The keyboard put a space after the typed quote, and only
       the quote that ends the sentence is followed by one. */
    @Test
    fun `a typed quote with a trailing space replaces every quote`() {
        val line = "\"Understood. Won\"t Lord Fischer have some?\""
        val asTyped = regexRule("u1", "\" ", "'").compiled()!!
        assertEquals(
            "\"Understood. Won\"t Lord Fischer have some?'",
            SpeechText.apply(listOf(asTyped), line),
        )
        val trimmed = regexRule("u1", SpeechEdits.typedPattern("\" "), "'").compiled()!!
        assertEquals(
            "'Understood. Won't Lord Fischer have some?'",
            SpeechText.apply(listOf(trimmed), line),
        )
    }

    @Test
    fun `a novel's version takes its rule's slot and nothing else`() {
        val global = listOf(regexRule("a", "x", "1"), regexRule("b", "y", "2"))
        val novel = mapOf(
            "b" to regexRule("b", "y", "two").copy(enabled = false),
            "gone" to regexRule("gone", "z", "3"),
        )
        val out = SpeechEdits.forNovel(global, novel)
        assertEquals(listOf("a", "b"), out.map { it.id })
        assertEquals(listOf("1", "two"), out.map { it.replace })
        assertEquals(listOf(true, false), out.map { it.enabled })
        assertEquals(global, SpeechEdits.forNovel(global, emptyMap()))
    }

    @Test
    fun `a rule added for one novel is skipped everywhere else`() {
        val own = regexRule("n", "x", "1").copy(novel = "novel-a")
        assertEquals(true, own.appliesTo("novel-a"))
        assertEquals(false, own.appliesTo("novel-b"))
        assertEquals(false, own.appliesTo(null))
        assertEquals(true, regexRule("g", "x", "1").appliesTo("novel-b"))
    }
}
