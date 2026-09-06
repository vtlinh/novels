package dev.vtlinh.noveldownloader

/* ALL NOVELS order: unfinished first, then finished (the user mark) at the
   bottom. Within each group: more stars first; equal stars, most recently
   updated first. "Updated" is the newest of last download, last read, or
   first start — so a chapter arriving and opening the reader both count,
   and a legacy row that only has `started` still has a place. Recently-read
   pinning is separate (the three latest unfinished stay above this list).

   Library tabs split the same list: a short is 20 chapters or fewer. The
   site's total wins when it is known, so a long novel still downloading
   does not jump into Shorts. */
object LibrarySort {

    const val SHORT_MAX = 20

    /* Site total when a check has filled it in (total > 0); otherwise the
       chapters already on disk. total is -1 until the first check. */
    fun chapterCount(total: Int, local: Int): Int =
        if (total > 0) total else local.coerceAtLeast(0)

    fun isShort(total: Int, local: Int): Boolean =
        chapterCount(total, local) <= SHORT_MAX

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
