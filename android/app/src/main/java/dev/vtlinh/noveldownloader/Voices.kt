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
       languages, because detect only ever returns these two. */
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


    /* Above this share of unaccented letters a chapter is English. Measured,
       not picked: over the 150 real chapter pages captured under the sites'
       test/resources, the unaccented share is 99.3–100% on the three English
       sites and 69.2–74.2% on the two Vietnamese ones. Nothing lands between
       74 and 99, and this sits in the middle of that gap. */
    private const val ENGLISH_PCT = 80

    /* And below this there is not enough text to be asking. About two
       sentences; a chapter is thousands of letters. */
    private const val ENOUGH = 100

    /* Which language a chapter is in, or NULL when there is nothing to judge.

       Give this a CHAPTER. The measure is a proportion, and a proportion of a
       few words is noise — "café" on its own is 75% unaccented and would come
       back Vietnamese. Over a chapter the figure is stable, which is why the
       reader hands over the whole one and decides once, rather than per
       sentence as it used to.

       Counting rather than looking for particular letters is what makes it
       stable. The old rule said Vietnamese if ANY Vietnamese character
       appeared — and é is a Vietnamese character, so an English chapter turned
       Vietnamese on the word `d'état`: the voice changed part-way down the
       page and stayed changed, because the profile was re-derived per sentence
       and only a later sentence could put it back. é is also French, and a
       translated novel is exactly the kind of text that borrows one. A
       borrowed word cannot move a proportion; a language can.

       Null is the other point. Asked "is this Vietnamese?", an empty string
       answers no, and a plain no reads as English. The TTS engine connects
       while the chapter is still loading, so the restore that runs on connect
       asked this about an EMPTY buffer, was told English, and applied the
       English profile — latching it. The voice sheet then said "TTS — English"
       over a Vietnamese chapter for the rest of the session. An absence of
       evidence is not evidence: say so, and let the caller wait for text. */
    fun detect(sample: String): String? {
        var letters = 0
        var plain = 0
        for (c in sample) {
            if (!c.isLetter()) continue
            letters++
            if (c.code < 128) plain++
        }
        if (letters < ENOUGH) return null
        return if (plain * 100 > ENGLISH_PCT * letters) "en" else "vi"
    }
}
