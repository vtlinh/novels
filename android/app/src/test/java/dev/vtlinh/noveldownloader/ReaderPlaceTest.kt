package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Test

/* Which loaded row an open lands on. */
class ReaderPlaceTest {

    @Test
    fun `the chapter asked for is the landing row`() {
        assertEquals(2, ReaderPlace.landingRow(listOf(8, 9, 10, 11, 12), 10))
    }

    /* Chapter 10's file is truncated: the window is 8, 9, 11, 12. Row 0
       is chapter 8 — two before the one picked. */
    @Test
    fun `an unreadable chapter lands on the next one that loaded`() {
        assertEquals(2, ReaderPlace.landingRow(listOf(8, 9, 11, 12), 10))
    }

    @Test
    fun `an unreadable last chapter lands on the last loaded row`() {
        assertEquals(1, ReaderPlace.landingRow(listOf(8, 9), 10))
        assertEquals(0, ReaderPlace.landingRow(emptyList(), 10))
    }
}
