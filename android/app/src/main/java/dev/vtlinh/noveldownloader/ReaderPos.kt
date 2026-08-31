package dev.vtlinh.noveldownloader

/* Where a reader reload (the EN/VI toggle) should land.

   The toggle used to take the chapter at the TOP OF THE VIEWPORT. TTS parks
   the spoken line a fifth of a page below that, so the first screenful of a
   chapter still shows the previous one at the top. Reloading there wrote
   lastCh to a chapter the saved ttsPos does not belong to — the same
   mismatch resumeChapter exists to paper over — and the restore then found
   no spot and stayed at the top of the wrong chapter.

   The listen spot wins when there is one; otherwise the viewport. */
object ReaderPos {

    fun reloadOffset(resumeOff: Int, viewOff: Int): Int =
        if (resumeOff >= 0) resumeOff else viewOff

    /* Chapter index and paragraph-within-chapter of `off` in a concatenated
       buffer. Paragraphs are newline-counted from the chapter start, the
       same count openAt restores across EN/VI. */
    fun positionAt(starts: List<Int>, body: CharSequence, off: Int): Pair<Int, Int>? {
        val i = starts.indices.lastOrNull { starts[it] <= off } ?: return null
        val start = starts[i]
        val end = off.coerceIn(start, body.length)
        val para = body.subSequence(start, end).count { it == '\n' }
        return i to para
    }
}
