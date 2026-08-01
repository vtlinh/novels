package dev.vtlinh.noveldownloader

/* Which of the engine's voices belong to a language profile — with no Android
   in it, so the matching can be exercised directly.

   The reader speaks two languages, the source's and its translation's, and
   keeps a voice, rate and pitch for each. Picking the voices to offer means
   asking each of the engine's voices what language it is, and the answer
   comes back in whatever spelling the engine feels like: a Voice's locale is
   built from the engine's own data, and where one reports "vi" another
   reports "vie". Comparing against a single spelling finds nothing at all on
   a device that uses the other one — and the picker then quietly listed every
   voice it had instead, so a heading saying Tiếng Việt sat above a list of
   English voices with nothing to explain it. */
object Voices {

    /* Every code a profile answers to, ISO 639-1 and 639-2 alike. Two
       languages, because detectLang only ever returns these two. */
    fun codesFor(lang: String): Set<String> =
        if (lang == "vi") setOf("vi", "vie") else setOf("en", "eng")

    /* `voiceLanguage` is Voice.getLocale().getLanguage() — empty when a voice
       reports no locale at all, which belongs to no profile. */
    fun matches(voiceLanguage: String, lang: String): Boolean =
        voiceLanguage.isNotEmpty() && voiceLanguage.lowercase() in codesFor(lang)

    /* The name to say it by, for a reader who has to go and install it. */
    fun nameOf(lang: String): String = if (lang == "vi") "Vietnamese" else "English"

    /* Google's voice names end in how they are synthesised: "en-us-x-iob-local"
       renders on the phone, "en-us-x-iob-network" renders on Google's servers.

       The name is the SECOND test, not the first — Voice.isNetworkConnectionRequired
       is the engine's own answer and is asked before this. This catches a voice
       that says nothing while naming itself plainly, which costs a line and
       saves offering a voice that goes silent on a train. */
    fun isNetworkName(voiceName: String): Boolean =
        voiceName.endsWith("-network", ignoreCase = true)

    /* What the picker shows for one voice — locale first, because that is the
       column a reader scans down. */
    fun label(voiceLocale: String, voiceName: String): String = "$voiceLocale — $voiceName"

    /* And the order it shows them in: that same label, case-folded.

       THE DEFECT. The list was sorted by Voice.name alone, with the ordinary
       String compare, which is case-SENSITIVE. Google names its general
       voices "en-AU-language" and its specific ones "en-au-x-aua-local", so
       every uppercase name sorted ahead of every lowercase one, and the
       locale column the reader actually reads came out en_AU, en_GB, en_IN,
       en_NG, en_US, en_AU, en_AU, en_AU… A list sorted by a key nobody can
       see reads as a list that is not sorted at all.

       Ordering by the whole visible label is also the only arrangement the
       display cannot drift away from — sort on one string, show the other,
       and the next change to either puts them back out of step.

       lowercase() with no argument folds by Locale.ROOT rather than the
       device's, deliberately: on a Turkish phone the default fold turns I
       into ı, which sorts past z and would hand that reader a different
       order from everyone else. */
    fun sortKey(voiceLocale: String, voiceName: String): String =
        label(voiceLocale, voiceName).lowercase()

    private val VI_CHARS = Regex(
        "[\u0103\u00e2\u0111\u00ea\u00f4\u01a1\u01b0\u00e0\u1ea3\u00e3\u00e1\u1ea1\u1eb1\u1eb3\u1eb5\u1eaf\u1eb7\u1ea7\u1ea9\u1eab\u1ea5\u1ead\u00e8\u1ebb\u1ebd\u00e9\u1eb9\u1ec1\u1ec3\u1ec5\u1ebf\u1ec7\u00ec\u1ec9\u0129\u00ed\u1ecb\u00f2\u1ecf\u00f5\u00f3\u1ecd\u1ed3\u1ed5\u1ed7\u1ed1\u1ed9\u1edd\u1edf\u1ee1\u1edb\u1ee3\u00f9\u1ee7\u0169\u00fa\u1ee5\u1eeb\u1eed\u1eef\u1ee9\u1ef1\u1ef3\u1ef7\u1ef9\u00fd\u1ef5]",
        RegexOption.IGNORE_CASE,
    )

    /* Which language a stretch of the novel is in — Vietnamese always carries
       diacritics within a sentence or two — or NULL when there is nothing to
       judge.

       Null is the whole point. Asked "is this Vietnamese?", an empty string
       answers no, and a plain no reads as English. The TTS engine connects
       while the chapter is still loading, so the restore that runs on connect
       asked this about an EMPTY buffer, was told English, and applied the
       English profile — latching it, since the profile is only re-derived
       while it is unset. The voice sheet then said "TTS — English" over a
       Vietnamese chapter and offered English voices to a reader who wanted a
       Vietnamese one, and nothing ever put it right.

       An absence of evidence is not evidence. Say so, and let the caller wait
       for text worth reading. */
    fun detect(sample: String): String? {
        if (sample.isBlank()) return null
        return if (VI_CHARS.containsMatchIn(sample)) "vi" else "en"
    }
}
