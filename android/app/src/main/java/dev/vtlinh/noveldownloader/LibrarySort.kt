package dev.vtlinh.noveldownloader

/* ALL NOVELS order: more stars first; equal stars, most recently updated
   first. "Updated" is the newest of last download, last read, or first
   start — so a chapter arriving and opening the reader both count, and a
   legacy row that only has `started` still has a place. Recently-read
   pinning is separate (the three latest stay above this list). */
object LibrarySort {

    fun updatedAt(lastDl: Long, lastRead: Long, started: Long): Long =
        maxOf(lastDl, lastRead, started)

    fun <T> comparator(
        starsOf: (T) -> Int,
        lastDlOf: (T) -> Long,
        lastReadOf: (T) -> Long,
        startedOf: (T) -> Long,
    ): Comparator<T> =
        compareByDescending<T>(starsOf)
            .thenByDescending { updatedAt(lastDlOf(it), lastReadOf(it), startedOf(it)) }
}
