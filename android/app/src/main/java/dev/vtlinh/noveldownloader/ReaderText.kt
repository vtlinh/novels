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
       intact). Leading newlines, spaces, the old ⁂ mark and the picture
       placeholder are skipped so a tap on one of those still starts on
       real words. */
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
                    j = k
                    break
                }
            }
            j++
        }
        return Pair(i, j.coerceAtMost(body.length))
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
