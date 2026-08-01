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
}
