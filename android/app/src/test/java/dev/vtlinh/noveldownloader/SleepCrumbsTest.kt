package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/* Which chapter rows the list marks after the sleep timer stopped reading,
   and what the note above the list says. */
class SleepCrumbsTest {

    private val ordered = (1..10).map { "Chapter $it.txt" }

    @Test
    fun `marks every chapter from the last movement to the stop`() {
        assertEquals(2..5, SleepCrumbs.range(ordered, "Chapter 3.txt", "Chapter 6.txt"))
    }

    @Test
    fun `newest first still marks the same chapters`() {
        val desc = ordered.reversed()
        val rows = SleepCrumbs.range(desc, "Chapter 3.txt", "Chapter 6.txt")!!
        assertEquals(
            listOf("Chapter 6.txt", "Chapter 5.txt", "Chapter 4.txt", "Chapter 3.txt"),
            rows.map { desc[it] },
        )
    }

    @Test
    fun `a skip back before the stop is marked either way round`() {
        assertEquals(2..5, SleepCrumbs.range(ordered, "Chapter 6.txt", "Chapter 3.txt"))
    }

    @Test
    fun `one chapter when it stopped in the chapter the timer started in`() {
        assertEquals(4..4, SleepCrumbs.range(ordered, "Chapter 5.txt", "Chapter 5.txt"))
    }

    @Test
    fun `a renamed chapter is still found by its number`() {
        val renamed = ordered.toMutableList().apply { this[5] = "Chapter 6 - The Gate.txt" }
        assertEquals(2..5, SleepCrumbs.range(renamed, "Chapter 3.txt", "Chapter 6.txt"))
    }

    @Test
    fun `one end gone from the listing still marks the other`() {
        assertEquals(5..5, SleepCrumbs.range(ordered, "Chapter 99.txt", "Chapter 6.txt"))
    }

    @Test
    fun `nothing is marked when both ends are gone`() {
        assertNull(SleepCrumbs.range(ordered, "Chapter 98.txt", "Chapter 99.txt"))
    }

    @Test
    fun `the note names both chapters`() {
        assertEquals(
            "The sleep timer stopped reading. No movement from Chapter 3 until it stopped in Chapter 6.",
            SleepCrumbs.note("Chapter 3.txt", "Chapter 6.txt"),
        )
    }

    @Test
    fun `the note names one chapter when both ends are the same`() {
        assertEquals(
            "The sleep timer stopped reading in Chapter 6 - The Gate.",
            SleepCrumbs.note("Chapter 6.txt", "Chapter 6 - The Gate.txt"),
        )
    }
}
