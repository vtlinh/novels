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

    /* THE DEFECT. "Mt. Tai" was split, and the half the engine did see
       was not "Mount". The title abbreviations are expanded the same way
       Mr. and Dr. already were, now that the splitter leaves them whole. */
    @Test
    fun `Mt-dot is spoken as Mount`() {
        assertEquals(
            "a pebble and Mount Tai!",
            spoken("a pebble and Mt. Tai!"),
        )
        assertEquals("Mount Elgin", spoken("Mt. Elgin"))
        assertEquals("Mount Tai", spoken("mt. Tai"))
    }

    @Test
    fun `a title and the name after it are both spoken`() {
        assertEquals("Mister Liang, the car is ready.", spoken("Mr. Liang, the car is ready."))
        assertEquals("Doctor Watson narrated it.", spoken("Dr. Watson narrated it."))
        assertEquals("Mrs Li paused.", spoken("Mrs. Li paused."))
        assertEquals("Saint John cycle.", spoken("St. John cycle."))
        assertEquals("Captain Knox's journal.", spoken("Capt. Knox's journal."))
        assertEquals("Colonel Grangerford was a gentleman.", spoken("Col. Grangerford was a gentleman."))
        assertEquals("Reverend Allen thought so.", spoken("Rev. Allen thought so."))
        assertEquals("Messieurs Chaplin and Walker.", spoken("Messrs. Chaplin and Walker."))
        assertEquals("versus magic casters.", spoken("vs. magic casters."))
        assertEquals("77 feet wide.", spoken("77 ft. wide."))
        assertEquals("Fort Worth", spoken("Ft. Worth"))
        assertEquals("6:00 a m sharp.", spoken("6:00 a.m. sharp."))
        assertEquals("in the U S Army.", spoken("in the U.S. Army."))
        assertEquals("Volume 93, September.", spoken("Vol. 93, September."))
        assertEquals("Boston, Jan 7th, 1839.", spoken("Boston, Jan. 7th, 1839."))
        assertEquals("etcetera and more.", spoken("etc. and more."))
        assertEquals("Figure 1 is tied.", spoken("Fig. 1 is tied."))
        assertEquals(
            "a freshly cracked fig. For a long time.",
            spoken("a freshly cracked fig. For a long time."),
        )
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

    /* THE DEFECT. Match whole words was checked on a regular expression,
       and the test line "Cooldown: 30 se" still became "Cooldown: 30
       secondse". The "s" is the start of a word, not a word of its own. */
    @Test
    fun `a whole-word regex does not match inside a longer word`() {
        val rule = regexRule("u", "(\\d+) s", "$1 seconds").copy(wholeWord = true)
        fun spoken(text: String) = SpeechText.apply(listOf(rule.compiled()!!), text)
        assertEquals("Cooldown: 30 se", spoken("Cooldown: 30 se"))
        assertEquals("Cooldown: 30 seconds", spoken("Cooldown: 30 seconds"))
        assertEquals("x30 s", spoken("x30 s"))
        assertEquals("Cooldown: 30 seconds", spoken("Cooldown: 30 s"))
        assertEquals("Cooldown: 30 seconds.", spoken("Cooldown: 30 s."))
        assertEquals("Cooldown: 30 seconds left", spoken("Cooldown: 30 s left"))
        assertEquals("5 seconds and 10 seconds", spoken("5 s and 10 s"))
    }

    @Test
    fun `the same regex without whole words still matches a prefix`() {
        val rule = regexRule("u", "(\\d+) s", "$1 seconds")
        assertEquals(
            "Cooldown: 30 secondse",
            SpeechText.apply(listOf(rule.compiled()!!), "Cooldown: 30 se"),
        )
    }

    /* Export bakes the boundaries into the pattern and import reads the
       flag back as off. The spoken result has to survive that round trip,
       because a backup is how these rules are restored. */
    @Test
    fun `a whole-word regex matches the same after a backup round-trip`() {
        val rule = regexRule("u", "(\\d+) s", "$1 seconds").copy(wholeWord = true)
        val imported = SpeechEdits.parse(SpeechEdits.export(listOf(rule))).single()
        val samples = listOf("Cooldown: 30 se", "Cooldown: 30 s", "Cooldown: 30 seconds")
        for (sample in samples) {
            assertEquals(
                SpeechText.apply(listOf(rule.compiled()!!), sample),
                SpeechText.apply(listOf(imported.compiled()!!), sample),
            )
        }
    }

    @Test
    fun `a whole-word literal still matches only the whole word`() {
        val rule = SpeechEdit("u", "", 1, true, "se", "seconds", true, false)
        assertEquals("30 seconds", SpeechText.apply(listOf(rule.compiled()!!), "30 se"))
        assertEquals("30 seconds", SpeechText.apply(listOf(rule.compiled()!!), "30 seconds"))
    }
}
