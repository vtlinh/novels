package dev.vtlinh.noveldownloader

import org.junit.Assert.assertFalse
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
}
