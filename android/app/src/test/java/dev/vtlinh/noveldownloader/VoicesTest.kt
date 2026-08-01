package dev.vtlinh.noveldownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* Which of the engine's voices belong to a language profile.

   There is no way to ask a real TTS engine from a test, and the answer it
   gives is the whole difficulty: the spelling of a Voice's language code is
   the engine's choice, not ours. */
class VoicesTest {

    /* THE DEFECT. A Voice's locale is built from the engine's own data, and
       engines disagree about which ISO code to use. Comparing against "vi"
       alone matched nothing on a device reporting "vie" — and the picker then
       quietly listed every voice it had instead, so a card headed Tiếng Việt
       offered en_US, en_IN, en_NG and nothing to explain why. */
    @Test
    fun `a language is matched by either of its codes`() {
        assertTrue(Voices.matches("vi", "vi"))
        assertTrue("the three-letter code is the same language", Voices.matches("vie", "vi"))
        assertTrue(Voices.matches("en", "en"))
        assertTrue(Voices.matches("eng", "en"))
    }

    /* However the engine cases it. */
    @Test
    fun `the case the engine chose does not matter`() {
        assertTrue(Voices.matches("VI", "vi"))
        assertTrue(Voices.matches("Vie", "vi"))
    }

    @Test
    fun `the two languages do not answer for each other`() {
        assertFalse(Voices.matches("en", "vi"))
        assertFalse(Voices.matches("eng", "vi"))
        assertFalse(Voices.matches("vi", "en"))
        assertFalse(Voices.matches("vie", "en"))
    }

    /* A voice reporting no locale belongs to no profile — offering it under
       one would be a guess, and the reader has two languages to keep apart. */
    @Test
    fun `a voice with no language belongs to nothing`() {
        assertFalse(Voices.matches("", "vi"))
        assertFalse(Voices.matches("", "en"))
    }

    /* Nothing else is one of ours, however plausible it looks. */
    @Test
    fun `another language is not a near miss`() {
        assertFalse(Voices.matches("es", "en"))
        assertFalse(Voices.matches("cmn", "vi"))
        assertFalse("a prefix is not a match", Voices.matches("v", "vi"))
    }

    /* The name is what a reader sent to install voice data has to look for. */
    @Test
    fun `each profile has a name to go and find`() {
        assertTrue(Voices.nameOf("vi") == "Vietnamese")
        assertTrue(Voices.nameOf("en") == "English")
    }

    /* ---- which language a stretch of the novel is in ---- */

    /* THE OTHER DEFECT, and why this answer is nullable. Asked "is this
       Vietnamese?", an empty string answers no, and a plain no reads as
       English. The engine connects while the chapter is still loading, so
       the restore that runs on connect judged an EMPTY buffer, was told
       English, and LATCHED the English profile — the voice sheet then said
       "TTS — English" over a Vietnamese chapter, and offered English voices
       to a reader who wanted a Vietnamese one, for the rest of the session.

       An absence of evidence is not evidence of English. */
    @Test
    fun `nothing to judge is not English`() {
        assertNull("an empty buffer says nothing at all", Voices.detect(""))
        assertNull("nor does one holding only layout", Voices.detect("   \n\n  "))
    }

    @Test
    fun `Vietnamese is known by its diacritics`() {
        assertTrue(Voices.detect("Chương 1: Không chỉ thích một người") == "vi")
        assertTrue("one marked word in a line is enough", Voices.detect("A B c ngoại") == "vi")
    }

    @Test
    fun `unmarked latin text is English`() {
        assertTrue(Voices.detect("Chapter 1: He did not like anyone") == "en")
    }
}
