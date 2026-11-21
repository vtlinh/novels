package dev.vtlinh.noveldownloader

/* Whether a Library row gets an unread mark.

   On when three things are all true. Read-aloud last stopped past
   halfway through the book. That chapter is not the last one. And
   chapters arrived after that read: a download ran later, or a check
   saw a higher total than the one already recorded. The first time a
   total is learned is not growth: a novel going from "never counted"
   to 100 chapters did not gain any.

   The chapter is the file named in the read-aloud marker
   ("Chapter 51.txt|4"). Halfway uses the chapter the file starts at.
   "The last chapter" uses the chapter it ends at, so a merged file
   that reaches the end counts as being there. The mark carries no
   count of chapters left. */
object LibraryUnread {

    /* When the site's chapter count last grew. Cleared with the novel. */
    const val ADDED_PREFIX = "chaptersAdded:"

    /* `previous > 0` so the first recorded total is not treated as new
       chapters arriving. */
    fun grew(previous: Int, current: Int): Boolean = previous > 0 && current > previous

    fun pastHalfway(ttsPos: String?, siteTotal: Int, onDisk: Int): Boolean {
        val total = totalOf(siteTotal, onDisk)
        if (total <= 0) return false
        val n = startingChapter(ttsPos) ?: return false
        return n.toLong() * 2 > total
    }

    /* `lastReadAt` is when read-aloud was started, `ttsPosAt` when the
       marker was last saved. The later one is the last time the novel
       was read. `lastDownloadAt` is when a download last ran;
       `chaptersAddedAt` is when the site total last grew. The later of
       those is when chapters arrived, and it has to be after the read. */
    fun show(
        ttsPos: String?,
        siteTotal: Int,
        onDisk: Int,
        lastReadAt: Long,
        ttsPosAt: Long,
        lastDownloadAt: Long,
        chaptersAddedAt: Long,
    ): Boolean {
        val readAt = maxOf(lastReadAt, ttsPosAt)
        val addedAt = maxOf(lastDownloadAt, chaptersAddedAt)
        if (addedAt <= 0L || readAt <= 0L || addedAt <= readAt) return false
        if (!pastHalfway(ttsPos, siteTotal, onDisk)) return false
        val total = totalOf(siteTotal, onDisk)
        val end = endingChapter(ttsPos) ?: return false
        return end < total
    }

    private fun totalOf(siteTotal: Int, onDisk: Int): Int =
        if (siteTotal > 0) siteTotal else onDisk

    private fun markerName(ttsPos: String?): String? =
        ttsPos?.substringBefore('|')?.trim()?.takeIf { it.isNotEmpty() }

    private fun startingChapter(ttsPos: String?): Int? {
        val name = markerName(ttsPos) ?: return null
        return Scenes.chapterNumber(name)
    }

    /* A merged file "Chapter 70-71.txt" ends at 71. Being in that file
       is being on chapter 71 for the "already on the last chapter" test. */
    private fun endingChapter(ttsPos: String?): Int? {
        val name = markerName(ttsPos) ?: return null
        val m = ChapterName.RE.find(name.removeSuffix(".gz"))
        if (m != null) {
            val start = m.groupValues[1].toIntOrNull()
            val end = m.groupValues.getOrNull(2)?.toIntOrNull()
            if (start != null) return if (end != null && end > start) end else start
        }
        return Scenes.chapterNumber(name)
    }
}
