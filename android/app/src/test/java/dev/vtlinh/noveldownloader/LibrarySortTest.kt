package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortTest {

    private data class Item(
        val stars: Int,
        val lastDl: Long = 0,
        val lastRead: Long = 0,
        val started: Long = 0,
    )

    private fun order(vararg items: Item): List<Item> =
        items.toList().sortedWith(
            LibrarySort.comparator(
                { it.stars }, { it.lastDl }, { it.lastRead }, { it.started },
            ),
        )

    @Test
    fun `more stars come first`() {
        val sorted = order(Item(3), Item(10), Item(0), Item(8))
        assertEquals(listOf(10, 8, 3, 0), sorted.map { it.stars })
    }

    @Test
    fun `equal stars prefer the newest download`() {
        val a = Item(stars = 8, lastDl = 10)
        val b = Item(stars = 8, lastDl = 50)
        assertEquals(listOf(b, a), order(a, b))
    }

    @Test
    fun `a more recent read beats an older download at the same stars`() {
        val downloaded = Item(stars = 10, lastDl = 20, lastRead = 5)
        val read = Item(stars = 10, lastDl = 8, lastRead = 40)
        assertEquals(listOf(read, downloaded), order(downloaded, read))
    }

    @Test
    fun `started is the fallback when nothing else has a time`() {
        val older = Item(stars = 5, started = 1)
        val newer = Item(stars = 5, started = 9)
        assertEquals(listOf(newer, older), order(older, newer))
    }

    @Test
    fun `stars outrank recency`() {
        val low = Item(stars = 2, lastDl = 100)
        val high = Item(stars = 9, lastDl = 1)
        assertEquals(listOf(high, low), order(low, high))
    }

    @Test
    fun `updatedAt is the newest of download, read, and start`() {
        assertEquals(40L, LibrarySort.updatedAt(10, 40, 2))
        assertEquals(10L, LibrarySort.updatedAt(10, 0, 2))
        assertEquals(0L, LibrarySort.updatedAt(0, 0, 0))
    }
}
