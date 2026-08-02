package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
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

    /* ---- local voices only ---- */

    /* Google ships each voice twice: one rendering on the phone, one on its
       servers. The second kind is no use to a reader whose whole point is a
       downloaded novel — it needs a connection to say a word, so it goes
       silent on a train, and every sentence waits on a round trip. */
    @Test
    fun `a voice that renders on Google's servers is known by its name`() {
        assertTrue(Voices.isNetworkName("en-us-x-iob-network"))
        assertTrue(Voices.isNetworkName("vi-vn-x-vic-network"))
    }

    @Test
    fun `the local half of the pair is kept`() {
        assertFalse(Voices.isNetworkName("en-us-x-iob-local"))
        assertFalse(Voices.isNetworkName("vi-vn-x-vic-local"))
    }

    /* The suffix, not the word. A voice is not online because its name
       happens to contain those letters somewhere. */
    @Test
    fun `the word has to end the name`() {
        assertFalse(Voices.isNetworkName("en-us-network-x-iob-local"))
        assertFalse(Voices.isNetworkName(""))
    }

    /* ---- the order the picker lists them in ---- */

    /* THE DEFECT, from a real device's list. Sorting by Voice.name with the
       ordinary String compare is case-sensitive, and Google names its general
       voices "en-AU-language" but its specific ones "en-au-x-aua-local" — so
       every uppercase name sorted ahead of every lowercase one and the locale
       column came out en_AU, en_GB, en_IN, en_NG, en_US, and then en_AU
       again. Sorted by a key nobody can see, which reads as not sorted. */
    @Test
    fun `the case a voice is named in does not decide where it goes`() {
        val asTheEngineGaveThem = listOf(
            "en_US" to "en-US-language",
            "en_AU" to "en-au-x-aua-local",
            "en_AU" to "en-AU-language",
            "en_GB" to "en-gb-x-gba-local",
            "en_IN" to "en-IN-language",
        )
        val sorted = asTheEngineGaveThem
            .sortedBy { (loc, name) -> Voices.sortKey(loc, name) }
            .map { (loc, name) -> Voices.label(loc, name) }

        assertEquals(
            listOf(
                "en_AU — en-AU-language",
                "en_AU — en-au-x-aua-local",
                "en_GB — en-gb-x-gba-local",
                "en_IN — en-IN-language",
                "en_US — en-US-language",
            ),
            sorted,
        )
    }

    /* Locale first, so the column a reader scans down is the one in order.
       Sorting on the name alone would put en_IN's "en-IN-language" ahead of
       en_GB's "en-gb-x-gba-local" — right by the hidden key, wrong on screen. */
    @Test
    fun `the locale decides the order before the name does`() {
        assertTrue(Voices.sortKey("en_GB", "en-gb-x-gba-local") < Voices.sortKey("en_IN", "en-IN-language"))
        assertTrue("and within one locale, the name breaks the tie",
            Voices.sortKey("en_AU", "en-au-x-aua-local") < Voices.sortKey("en_AU", "en-au-x-aub-local"))
    }

    /* Sort and display read the same string, so they cannot drift apart. */
    @Test
    fun `what is sorted is what is shown`() {
        assertEquals("en_AU — en-AU-language", Voices.label("en_AU", "en-AU-language"))
        assertEquals(Voices.label("en_AU", "en-AU-language").lowercase(),
            Voices.sortKey("en_AU", "en-AU-language"))
    }

    /* Folded by Locale.ROOT, not the device's. A Turkish phone's default fold
       turns I into ı, which sorts past z — that reader would get a different
       list from everyone else, for no reason they could ever see. */
    @Test
    fun `the device's own language does not change the order`() {
        val was = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("tr", "TR"))
            assertEquals("en_in — en-in-language", Voices.sortKey("en_IN", "en-IN-language"))
        } finally {
            java.util.Locale.setDefault(was)
        }
    }

    /* A voice the engine describes only partly still has a place in the list,
       rather than crashing the picker that has to draw it. */
    @Test
    fun `a voice missing a locale or a name still sorts`() {
        assertEquals(" — en-US-language", Voices.label("", "en-US-language"))
        assertEquals("en_US — ", Voices.label("en_US", ""))
    }

    /* ---- which language a chapter is in ---- */

    /* Both of these are the real opening of a real captured chapter, lifted
       out of the corpus under the sites' own test/resources rather than
       written here — the same reason nothing else in this repo is judged
       against invented text. */
    private val vietnameseChapter =
        "Ánh đèn trong đại sảnh biệt thự Lục gia phủ lên mọi vật một màu lạnh " +
            "lẽo, tang tóc. Những vòng hoa trắng xếp thành tường, lặng lẽ che đi " +
            "những ánh mắt dò xét, toan tính. Đội bảo vệ mặc vest đen đứng rải " +
            "rác, tạo thành một rào chắn vô hình, khiến bầu không khí vốn nặng " +
            "nề lại càng thêm nghẹt thở."

    private val englishChapter =
        "Chapter 1: Prince Charming Next Door (1) Translator: Nyoi-Bo Studio " +
            "Editor: Nyoi-Bo Studio To me, the perfect love is having you with me " +
            "for the rest of my life. -Ye Feiyan, Prince Charming Next Door When " +
            "I finally met Gu Yusheng after two years of waiting, I was going to " +
            "ask him why he stood me up that day."

    @Test
    fun `a chapter is read as the language it is written in`() {
        assertEquals("vi", Voices.detect(vietnameseChapter))
        assertEquals("en", Voices.detect(englishChapter))
    }

    /* THE DEFECT, off a real screen. Vietnamese used to mean "holds any
       Vietnamese character", and é is one — so an English chapter turned
       Vietnamese on the word `d'état`, and the voice changed part-way down the
       page and stayed changed. é is also French, and a translated novel is
       exactly the kind of text that borrows one.

       A borrowed word cannot move a proportion. That is the whole fix. */
    @Test
    fun `a borrowed french word does not make an english chapter vietnamese`() {
        val borrowed =
            englishChapter + " Are you completely certain they are here to " +
                "listen to a lecture on fundamental knowledge of cultivation, " +
                "not to start a coup d'état?"
        assertEquals("en", Voices.detect(borrowed))
        assertEquals("en", Voices.detect("$englishChapter They met at the café, déjà vu."))
    }

    /* Where the line sits, and how much room is either side of it. Measured
       over the 150 real chapters in the corpus: the English sites run
       99.3–100% unaccented, the Vietnamese ones 69.2–74.2%. Nothing at all
       falls between. These four pin both the rule and that margin, so moving
       the constant has to be a decision rather than a slip. */
    @Test
    fun `above nine tenths unaccented is English and below it is not`() {
        fun mix(plain: Int, marked: Int) = "a".repeat(plain) + "ố".repeat(marked)

        assertEquals("just over nine tenths", "en", Voices.detect(mix(91, 9)))
        assertEquals("exactly nine tenths is not over it", "vi", Voices.detect(mix(90, 10)))

        assertEquals("the most ascii any captured Vietnamese chapter was",
            "vi", Voices.detect(mix(74, 26)))
        assertEquals("the least ascii any captured English chapter was",
            "en", Voices.detect(mix(99, 1)))
    }

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

    /* A handful of words is not a chapter, and a proportion taken over one is
       noise: "café" alone is 75% unaccented, which would answer Vietnamese
       with real confidence. Better to say nothing and be asked again once the
       chapter has loaded — which is exactly what the reader does. */
    @Test
    fun `too little text is not judged at all`() {
        assertNull(Voices.detect("café"))
        assertNull(Voices.detect("Chapter 1: He did not like anyone"))
        assertNull("one sentence of Vietnamese is still not a chapter",
            Voices.detect("Chương 1: Không chỉ thích một người"))
    }
}
