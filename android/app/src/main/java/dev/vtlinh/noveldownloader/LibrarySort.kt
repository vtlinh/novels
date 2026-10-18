package dev.vtlinh.noveldownloader

/* ALL NOVELS order: unread first, then read (the user mark) at the
   bottom. Within each group: more stars first; equal stars, most recently
   updated first. "Updated" is the newest of last download, last read, or
   first start — so a chapter arriving and opening the reader both count,
   and a legacy row that only has `started` still has a place. Recently-read
   pinning is separate (the three latest unread stay above this list).
   The Library's Read tab shows the read group, Novels the rest. */
object LibrarySort {

    fun updatedAt(lastDl: Long, lastRead: Long, started: Long): Long =
        maxOf(lastDl, lastRead, started)

    fun <T> comparator(
        starsOf: (T) -> Int,
        lastDlOf: (T) -> Long,
        lastReadOf: (T) -> Long,
        startedOf: (T) -> Long,
        readOf: (T) -> Boolean,
    ): Comparator<T> =
        compareBy<T> { readOf(it) }
            .thenByDescending(starsOf)
            .thenByDescending { updatedAt(lastDlOf(it), lastReadOf(it), startedOf(it)) }

    /* The three latest reads pin above ALL NOVELS. Novels marked as
       read stay in that list (sorted to its bottom) rather than being
       pinned here. */
    fun <T> recentlyRead(
        items: List<T>,
        lastReadOf: (T) -> Long,
        readOf: (T) -> Boolean,
        n: Int = 3,
    ): List<T> =
        items.filter { lastReadOf(it) > 0 && !readOf(it) }
            .sortedByDescending(lastReadOf)
            .take(n)
}
