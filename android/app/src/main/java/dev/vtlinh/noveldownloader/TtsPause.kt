package dev.vtlinh.noveldownloader

/* How long TTS stays silent after a sentence, before the next one.

   Three levels, one duration each: after a sentence, after a paragraph,
   after a chapter. The strongest boundary the gap actually crosses is the
   one that applies — a chapter break is also a paragraph break, and using
   the paragraph duration there would ignore the setting the user set for
   chapters.

   The durations live in prefs as seconds. Zero (the default, and the
   floor) is no extra silence: the engine's own gap between utterances is
   left alone. */
object TtsPause {

    const val SENTENCE_KEY = "ttsPauseSentence"
    const val PARAGRAPH_KEY = "ttsPauseParagraph"
    const val CHAPTER_KEY = "ttsPauseChapter"

    const val STEP = 0.1f
    const val MIN = 0f
    const val MAX = 30f

    enum class Level { SENTENCE, PARAGRAPH, CHAPTER }

    fun key(level: Level): String = when (level) {
        Level.SENTENCE -> SENTENCE_KEY
        Level.PARAGRAPH -> PARAGRAPH_KEY
        Level.CHAPTER -> CHAPTER_KEY
    }

    /* A chapter whose start sits after the sentence we just finished and
       at-or-before the next spoken start is a chapter we are about to
       enter — that is the chapter pause, not the paragraph one the
       surrounding newlines would otherwise pick. */
    fun crossesChapter(after: Int, nextStart: Int, chapterStarts: List<Int>): Boolean =
        chapterStarts.any { it > after && it <= nextStart }

    fun level(gap: CharSequence, crossesChapter: Boolean): Level = when {
        crossesChapter || gap.contains('\u2042') -> Level.CHAPTER
        gap.contains('\n') -> Level.PARAGRAPH
        else -> Level.SENTENCE
    }

    fun seconds(value: Float): Float =
        (Math.round(value * 10f) / 10f).coerceIn(MIN, MAX)

    fun step(value: Float, delta: Float): Float = seconds(value + delta)

    fun format(value: Float): String =
        "%.1f".format(java.util.Locale.US, seconds(value))

    fun millis(seconds: Float): Long {
        val s = seconds(seconds)
        return if (s <= 0f) 0L else Math.round(s * 1000f).toLong()
    }
}
