package dev.vtlinh.noveldownloader

/* Whether a Library row gets an unread mark.

   On when the chapter read-aloud last stopped in is past halfway
   through the book. The chapter is the file named in the read-aloud
   marker ("Chapter 51.txt|4"); a merged file counts as the chapter it
   starts at. Halfway is that number against the site's chapter count,
   the same total the row already shows, or against the chapters on
   disk when the site has never been asked.

   The mark carries no count. How many chapters are left is not
   computed, so a marker sitting on the last chapter is still past
   halfway. */
object LibraryUnread {

    fun pastHalfway(ttsPos: String?, siteTotal: Int, onDisk: Int): Boolean {
        val total = if (siteTotal > 0) siteTotal else onDisk
        if (total <= 0) return false
        val name = ttsPos?.substringBefore('|')?.trim()?.takeIf { it.isNotEmpty() }
            ?: return false
        val n = Scenes.chapterNumber(name) ?: return false
        return n.toLong() * 2 > total
    }
}
