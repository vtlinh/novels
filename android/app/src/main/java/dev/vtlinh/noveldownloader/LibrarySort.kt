package dev.vtlinh.noveldownloader

/* ALL NOVELS order: unfinished first, then finished (the user mark) at the
   bottom. Within each group: more stars first; equal stars, most recently
   updated first. "Updated" is the newest of last download, last read, or
   first start — so a chapter arriving and opening the reader both count,
   and a legacy row that only has `started` still has a place. Recently-read
   pinning is separate (the three latest unfinished stay above this list). */
object LibrarySort {

    fun updatedAt(lastDl: Long, lastRead: Long, started: Long): Long =
        maxOf(lastDl, lastRead, started)

    fun <T> comparator(
        starsOf: (T) -> Int,
        lastDlOf: (T) -> Long,
        lastReadOf: (T) -> Long,
        startedOf: (T) -> Long,
        finishedOf: (T) -> Boolean,
    ): Comparator<T> =
        compareBy<T> { finishedOf(it) }
            .thenByDescending(starsOf)
            .thenByDescending { updatedAt(lastDlOf(it), lastReadOf(it), startedOf(it)) }

    /* The three latest reads pin above ALL NOVELS. Finished novels stay
       in that list (sorted to its bottom) rather than being pinned here. */
    fun <T> recentlyRead(
        items: List<T>,
        lastReadOf: (T) -> Long,
        finishedOf: (T) -> Boolean,
        n: Int = 3,
    ): List<T> =
        items.filter { lastReadOf(it) > 0 && !finishedOf(it) }
            .sortedByDescending(lastReadOf)
            .take(n)
}
