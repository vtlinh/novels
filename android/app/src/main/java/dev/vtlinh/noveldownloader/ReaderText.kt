package dev.vtlinh.noveldownloader

/* Sentence, paragraph and chapter walking over a window of chapter
   bodies. Each body is its own row — there is no concatenated buffer
   and no chapter-separator character to skip.

   The reader used to join loaded chapters with "\\n\\n⁂\\n\\n" and
   treat the result as one string. A tap, a spoken sentence and a
   resume spot were all offsets into that blob, so inserting a chapter
   above shifted every one of them. Walking chapter-by-chapter keeps
   those offsets local: a prepend only changes which row they sit in. */
object ReaderText {

    /* Start of the paragraph containing `off`. */
    fun paraStartOf(body: CharSequence, off: Int): Int {
        val o = off.coerceIn(0, body.length)
        return lastIndexOf(body, '\n', (o - 1).coerceAtLeast(0)) + 1
    }

    /* How many newlines sit before `off` in this chapter — the same
       count the reader stores as the listening paragraph. */
    fun paragraphIndex(body: CharSequence, off: Int): Int {
        val end = off.coerceIn(0, body.length)
        var n = 0
        for (i in 0 until end) if (body[i] == '\n') n++
        return n
    }

    /* Next sentence at/after `from`: bounded by paragraph breaks, split
       on terminator punctuation followed by a space (so "3.5" stays
       intact). A period that is an abbreviation does not split — "Mt. Tai"
       was highlighted through "Mt." and "Tai" was spoken on its own, and
       the same cut falls on Mr., Dr., Mrs. and the rest of that class.
       Leading newlines, spaces, the old ⁂ mark and the picture placeholder
       are skipped so a tap on one of those still starts on real words. */
    fun nextSentence(body: CharSequence, from: Int): Pair<Int, Int>? {
        var i = from.coerceAtLeast(0)
        while (i < body.length &&
            (body[i] == '\n' || body[i] == ' ' || body[i] == '\u2042' || body[i] == '\uFFFC')
        ) {
            i++
        }
        if (i >= body.length) return null
        var j = i
        while (j < body.length) {
            val c = body[j]
            if (c == '\n') break
            if (c == '.' || c == '!' || c == '?' || c == '\u2026') {
                var k = j + 1
                while (k < body.length &&
                    (body[k] == '"' || body[k] == '\u201d' || body[k] == '\u2019' ||
                        body[k] == ')' || body[k] == '\u3011' || body[k] == '\u300f')
                ) {
                    k++
                }
                if (k >= body.length || body[k] == ' ' || body[k] == '\n') {
                    if (c != '.' || endsSentence(body, j, k)) {
                        j = k
                        break
                    }
                }
            }
            j++
        }
        return Pair(i, j.coerceAtMost(body.length))
    }

    /* True when the period at `period` really ends the sentence. `after`
       is the first character past any closing quote stuck to that period. */
    private fun endsSentence(body: CharSequence, period: Int, after: Int): Boolean {
        if (isNumberAbbrev(body, period, after)) return false
        /* "etc. and", "vs. magic", "\"Yes.\" he said" — a sentence does
           not continue in lowercase, so this period is inside the sentence. */
        if (followedByLowercase(body, after)) return false
        if (isNumericUnit(body, period)) return false
        val word = abbrevAt(body, period) ?: return true
        if (isMonthAbbrev(body, word, after)) return false
        if (isTitleAbbrev(word)) return false
        if (isInitial(body, word, after)) return false
        return true
    }

    /* The abbreviation "No." followed by a digit. The speech-edit that
       rewrites it to "number 12" has to see both halves in one sentence.
       "No. She left" is a real sentence end and is not matched here. */
    private fun isNumberAbbrev(body: CharSequence, period: Int, after: Int): Boolean {
        if (period < 2) return false
        if (!body[period - 1].equals('o', ignoreCase = true)) return false
        if (!body[period - 2].equals('n', ignoreCase = true)) return false
        if (period >= 3) {
            val prev = body[period - 3]
            if (prev.isLetterOrDigit() || prev == '_') return false
        }
        var i = after
        while (i < body.length && (body[i] == ' ' || body[i] == '\n')) i++
        return i < body.length && body[i] in '0'..'9'
    }

    /* Titles, ranks, and place abbreviations measured in the captured
       chapters, plus the same class the speech edits already expand.
       The period is part of the abbreviation: "Mr. Liang", "Dr. Watson",
       "Mt. Tai". "etc." is not here — a capital after it usually starts
       the next sentence, and a lowercase "etc. and" is kept by the
       lowercase rule. "ed." and "al." are not here either: "Ed." and
       "Al." end sentences. */
    private val ABBREV = setOf(
        "mr", "mrs", "ms", "dr", "drs", "prof", "rev", "hon", "gen",
        "capt", "col", "lt", "lieut", "maj", "sgt", "pvt", "cpl",
        "adm", "cmdr", "rep", "pres", "esq", "jr", "sr",
        "mme", "mlle", "messrs", "frl", "wm", "geo", "chas", "gov",
        "st", "ste", "mt", "ft", "ph", "fr",
        "vol", "nos", "eq", "chap", "ch", "pp", "op",
        "dept", "univ", "inc", "ltd", "co", "corp", "bros", "approx",
        "vs", "viz", "cf",
        "oz", "lbs", "lb",
    )

    /* "Jan." before a digit is the month. "Jan." before a word is the
       name, and that period ends the sentence. */
    private val MONTH = setOf(
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept",
        "oct", "nov", "dec",
    )

    /* A letter stuck to a number: "86m.", "18s.", "6d.". Not "1st." —
       an ordinal still ends the sentence. */
    private val UNIT = setOf(
        "m", "g", "s", "d", "l", "h",
        "km", "cm", "mm", "kg", "lb", "oz", "ft", "hr", "mg", "ml", "am", "pm",
    )
    private val ORDINAL = setOf("st", "nd", "rd", "th")

    /* The word after a single capital ("J. Snell", "W. A. Pearl", "U.S.
       Army", "e.g. Westminster") is part of the same sentence. The word
       after a roman numeral or a one-letter sentence ("War I. His",
       "a.m. He", "A. The") is the next sentence. */
    private val SENTENCE_START = setOf(
        "a", "an", "the",
        "he", "she", "it", "they", "we", "i", "you",
        "his", "her", "their", "my", "our", "your", "its",
        "him", "them", "me", "us",
        "this", "that", "these", "those",
        "there", "here",
        "but", "and", "or", "nor", "so", "yet",
        "if", "as", "at", "by", "to", "of", "in", "on", "for", "from", "with",
        "when", "where", "while", "what", "which", "who", "whom", "whose", "why", "how",
        "then", "than",
        "not", "no", "yes",
        "all", "any", "some", "each", "every", "both",
        "now", "still", "once", "later", "soon", "again",
        "after", "before", "during", "without", "within",
        "however", "although", "though", "because", "until", "unless",
        "oh", "ah", "well",
        "chapter", "part", "book", "volume", "section",
    )

    private fun followedByLowercase(body: CharSequence, after: Int): Boolean {
        var i = after
        while (i < body.length && body[i] == ' ') i++
        return i < body.length && body[i].isLowerCase()
    }

    private fun followedByDigit(body: CharSequence, after: Int): Boolean {
        var i = after
        while (i < body.length && body[i] == ' ') i++
        return i < body.length && body[i].isDigit()
    }

    /* Letters immediately before the period, when they are a word of
       their own. "1st." is an ordinal, not the abbreviation "st." */
    private fun abbrevAt(body: CharSequence, period: Int): String? {
        var i = period - 1
        if (i < 0 || !body[i].isLetter()) return null
        while (i >= 0 && body[i].isLetter()) i--
        val start = i + 1
        if (start > 0 && body[start - 1].isDigit()) return null
        return body.subSequence(start, period).toString()
    }

    private fun isNumericUnit(body: CharSequence, period: Int): Boolean {
        var i = period - 1
        while (i >= 0 && body[i].isLetter()) i--
        val start = i + 1
        if (start >= period || start == 0 || !body[start - 1].isDigit()) return false
        val suffix = body.subSequence(start, period).toString().lowercase()
        return suffix in UNIT && suffix !in ORDINAL
    }

    private fun isMonthAbbrev(body: CharSequence, word: String, after: Int): Boolean =
        word.lowercase() in MONTH && followedByDigit(body, after)

    /* "Fig." is the plate; "fig." is the fruit, and that period ends
       the sentence ("a cracked fig. For a long"). */
    private fun isTitleAbbrev(word: String): Boolean {
        if (word.equals("fig", ignoreCase = true)) return word[0].isUpperCase()
        return word.lowercase() in ABBREV
    }

    private fun isInitial(body: CharSequence, word: String, after: Int): Boolean {
        if (word.length != 1 || !word[0].isLetter()) return false
        val next = nextWord(body, after) ?: return false
        if (next[0].isDigit()) return true
        if (next.length == 1 && next[0].isLetter()) return true
        return next[0].isUpperCase() && next.lowercase() !in SENTENCE_START
    }

    private fun nextWord(body: CharSequence, after: Int): String? {
        var i = after
        while (i < body.length &&
            (body[i] == ' ' || body[i] == '"' || body[i] == '\u201c' || body[i] == '\u2018')
        ) {
            i++
        }
        if (i >= body.length || body[i] == '\n' || !body[i].isLetterOrDigit()) return null
        val start = i
        i++
        while (i < body.length &&
            (body[i].isLetter() || body[i] == '\'' || body[i] == '\u2019' || body[i] == '-')
        ) {
            i++
        }
        return body.subSequence(start, i).toString()
    }

    /* Start of the sentence containing `off`. A tap in the middle of a
       sentence starts there, not at the paragraph top. An `off` already
       at a paragraph start returns that same position, so a saved
       paragraph restore is unaffected. */
    fun sentStartOf(body: CharSequence, off: Int): Int {
        val o = off.coerceIn(0, body.length)
        var cursor = paraStartOf(body, o)
        while (true) {
            val s = nextSentence(body, cursor) ?: return cursor
            if (o < s.second || s.second <= cursor) return s.first
            cursor = s.second
        }
    }

    /* Character offset of paragraph `para`, counted from the start of
       this chapter. `limit` (when >= 0) is the exclusive end — an index
       that outruns the chapter must land at its last line, never in a
       later one. */
    fun offsetOfPara(body: CharSequence, para: Int, limit: Int = -1): Int {
        val end = if (limit < 0) body.length else limit.coerceIn(0, body.length)
        var off = 0
        var n = 0
        while (n < para) {
            val i = indexOf(body, '\n', off)
            if (i == -1 || i + 1 >= end) break
            off = i + 1
            n++
        }
        return off
    }

    /* Offset of the saved paragraph: the stored index first, corrected
       by the stored paragraph text when the two disagree. */
    fun restoreOffsetIn(body: CharSequence, para: Int, anchorText: String?): Int {
        val end = body.length
        val byIndex = if (para > 0) offsetOfPara(body, para, end) else 0
        val anchor = anchorText?.takeIf { it.isNotBlank() } ?: return byIndex
        if (byIndex in 0 until end && startsWith(body, anchor, byIndex)) {
            return byIndex
        }
        var best = -1
        var i = indexOf(body, anchor, 0)
        while (i >= 0 && i < end) {
            if (best < 0 || Math.abs(i - byIndex) < Math.abs(best - byIndex)) best = i
            i = indexOf(body, anchor, i + 1)
        }
        return if (best >= 0) best else byIndex
    }

    /* The next spoken span at or after `from`, walking into later rows
       when this chapter has no more sentences. Null only when the
       window itself is exhausted — the reader then appends more. */
    fun nextSpoken(
        bodies: List<CharSequence>,
        from: ReaderPlace,
    ): Pair<ReaderPlace, ReaderPlace>? {
        if (from.row in bodies.indices) {
            nextSentence(bodies[from.row], from.off)?.let {
                return ReaderPlace(from.row, it.first) to ReaderPlace(from.row, it.second)
            }
        }
        val start = if (from.row in bodies.indices) from.row + 1 else 0
        for (r in start until bodies.size) {
            nextSentence(bodies[r], 0)?.let {
                return ReaderPlace(r, it.first) to ReaderPlace(r, it.second)
            }
        }
        return null
    }

    /* Gap between the sentence just finished and the next one, plus
       whether that next one is a different chapter. Used by TtsPause
       so a chapter crossing still gets the chapter duration after the
       ⁂ mark left the concatenated buffer. */
    fun pauseGap(
        bodies: List<CharSequence>,
        after: ReaderPlace,
        next: ReaderPlace,
    ): Pair<CharSequence, Boolean> {
        if (after.row !in bodies.indices) return "" to (after.row != next.row)
        val body = bodies[after.row]
        if (next.row == after.row) {
            val from = after.off.coerceIn(0, next.off.coerceIn(0, body.length))
            val to = next.off.coerceIn(from, body.length)
            return body.subSequence(from, to) to false
        }
        val from = after.off.coerceIn(0, body.length)
        return body.subSequence(from, body.length) to true
    }

    /* ❮ / ❯ : one paragraph. Blank lines are not paragraphs. At a
       chapter edge this steps into the neighbour row — the same move
       the concatenated buffer made by walking across the ⁂ mark. */
    fun skipParagraph(
        bodies: List<CharSequence>,
        from: ReaderPlace,
        forward: Boolean,
    ): ReaderPlace? {
        if (from.row !in bodies.indices) return null
        if (forward) {
            val body = bodies[from.row]
            var i = indexOf(body, '\n', from.off)
            if (i >= 0) {
                while (i < body.length && isBreak(body[i])) i++
                if (i < body.length) return ReaderPlace(from.row, i)
            }
            for (r in (from.row + 1) until bodies.size) {
                val first = firstContent(bodies[r]) ?: continue
                return ReaderPlace(r, first)
            }
            return null
        }
        val body = bodies[from.row]
        val pStart = paraStartOf(body, from.off)
        val firstSent = nextSentence(body, pStart)?.first ?: pStart
        if (from.off > firstSent) return ReaderPlace(from.row, firstSent)
        var i = pStart - 1
        while (i >= 0 && isBreak(body[i])) i--
        if (i >= 0) return ReaderPlace(from.row, paraStartOf(body, i))
        for (r in (from.row - 1) downTo 0) {
            val last = lastParagraph(bodies[r]) ?: continue
            return ReaderPlace(r, last)
        }
        return null
    }

    /* Holding ❮ / ❯ : one chapter. Mid-chapter back goes to this
       chapter's top; already at a top goes to the neighbour. Null
       when the neighbour is not loaded — the reader rebuilds there. */
    fun skipChapter(
        bodies: List<CharSequence>,
        from: ReaderPlace,
        forward: Boolean,
    ): ReaderPlace? {
        if (from.row !in bodies.indices) return null
        if (!forward) {
            val firstSent = nextSentence(bodies[from.row], 0)?.first ?: 0
            if (from.off > firstSent) return ReaderPlace(from.row, 0)
        }
        val neighbour = from.row + if (forward) 1 else -1
        if (neighbour !in bodies.indices) return null
        return ReaderPlace(neighbour, 0)
    }

    private fun isBreak(c: Char) = c == '\n' || c == ' ' || c == '⁂'

    private fun firstContent(body: CharSequence): Int? {
        var i = 0
        while (i < body.length && isBreak(body[i])) i++
        return if (i < body.length) i else null
    }

    private fun lastParagraph(body: CharSequence): Int? {
        if (body.isEmpty()) return null
        var k = body.length - 1
        while (k >= 0 && isBreak(body[k])) k--
        if (k < 0) return null
        return paraStartOf(body, k)
    }

    private fun indexOf(body: CharSequence, c: Char, from: Int): Int {
        for (i in from.coerceAtLeast(0) until body.length) if (body[i] == c) return i
        return -1
    }

    private fun lastIndexOf(body: CharSequence, c: Char, from: Int): Int {
        val start = from.coerceAtMost(body.length - 1)
        for (i in start downTo 0) if (body[i] == c) return i
        return -1
    }

    private fun indexOf(body: CharSequence, needle: String, from: Int): Int {
        if (needle.isEmpty()) return from.coerceIn(0, body.length)
        val last = body.length - needle.length
        var i = from.coerceAtLeast(0)
        while (i <= last) {
            if (startsWith(body, needle, i)) return i
            i++
        }
        return -1
    }

    private fun startsWith(body: CharSequence, needle: String, at: Int): Boolean {
        if (at < 0 || at + needle.length > body.length) return false
        for (i in needle.indices) if (body[at + i] != needle[i]) return false
        return true
    }
}
