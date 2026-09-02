package dev.vtlinh.noveldownloader

/* The rewrite applied to a sentence before TTS speaks it, and the quote /
   ellipsis patterns that rewrite is judged against.

   Nothing here touches Android — the engine is not in a test, and the
   defect is a pattern that let "'...Hello" through as "single quote
   dot dot dot". */
object SpeechText {

    /* Quote marks Google TTS reads aloud as "single quote" / "quote".
       Opening ‘ and closing ’ are different characters; a rule that
       matches only the one that was typed leaves the other standing. */
    const val QUOTE_CHARS = "\\u2018\\u2019\\u201A\\u201B\\u0027\\u0060\\u00B4\\u201C\\u201D\\u201E\\u0022"

    /* A quote not inside a word. An apostrophe in don't sits between
       letters and is left alone. */
    const val STANDALONE_QUOTES = "(?<!\\w)[$QUOTE_CHARS]+(?!\\w)"

    /* Leading quotes then an ellipsis — the shape TTS reads as
       "single quote dot dot dot". The old rule was only the one-character
       … at column 0, so '...Hello and ‘…Hello both got through. */
    const val LEADING_ELLIPSIS = "^\\s*[$QUOTE_CHARS]*(\\u2026|\\.{2,})"

    /* Remaining ellipses TTS reads as "dot dot dot". Three ASCII dots or
       the single … character; two dots are left (a range, or ".." ). */
    const val ELLIPSIS = "\\u2026|\\.{3,}"

    private val SINGLE_QUOTE_CHARS = setOf(
        '\'', '\u2018', '\u2019', '\u201A', '\u201B', '`', '\u00B4',
    )
    private val DOUBLE_QUOTE_CHARS = setOf('"', '\u201C', '\u201D', '\u201E')
    private val SINGLE_QUOTE_CLASS = "[\\u2018\\u2019\\u201A\\u201B\\u0027\\u0060\\u00B4]"
    private val DOUBLE_QUOTE_CLASS = "[\\u201C\\u201D\\u201E\\u0022]"

    /* A literal rule whose pattern is one quote matches every quote that
       looks like it, at a word edge. Typed ‘ did not match the closing ’,
       so the preview deleted one mark and TTS still said "single quote". */
    fun literalPattern(pattern: String, wholeWord: Boolean): String {
        if (pattern.length == 1) {
            val c = pattern[0]
            if (c in SINGLE_QUOTE_CHARS) return "(?<!\\w)$SINGLE_QUOTE_CLASS+(?!\\w)"
            if (c in DOUBLE_QUOTE_CHARS) return "(?<!\\w)$DOUBLE_QUOTE_CLASS+(?!\\w)"
        }
        var p = Regex.escape(pattern)
        if (wholeWord) p = "\\b(?:$p)\\b"
        return p
    }

    private val WS = Regex("\\s+")

    /* The same rewrite the reader hands to the engine: trailing space so
       end-anchored rules fire, one bad rule skipped, whitespace collapsed. */
    fun apply(rules: List<Pair<Regex, String>>, text: String): String {
        var s = "$text "
        for ((re, rep) in rules) {
            s = try { re.replace(s, rep) } catch (e: Exception) { s }
        }
        return WS.replace(s, " ").trim()
    }

    /* The built-in punctuation silences, in the order the reader applies
       them among the English defaults. Isolated so a test can ask the
       same question the engine is asked. */
    fun punctuationRules(): List<Pair<Regex, String>> = listOf(
        Regex(LEADING_ELLIPSIS) to "",
        Regex(ELLIPSIS) to "",
        Regex(STANDALONE_QUOTES) to "",
    )
}
