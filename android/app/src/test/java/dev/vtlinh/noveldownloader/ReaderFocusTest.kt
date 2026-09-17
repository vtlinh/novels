package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* A tap on chapter text must not move the page. */
class ReaderFocusTest {

    /* THE DEFECT. Mid-chapter the row starts above the viewport and is
       taller than the page. Bringing that focused row on screen jumps
       to its top — the chapter start. */
    @Test
    fun `a tap mid-chapter must not jump to the chapter start`() {
        val childTop = -2000
        val childH = 8000
        val viewH = 2000
        assertEquals(
            "RecyclerView would align the row top",
            -2000,
            ReaderFocus.bringIntoViewDy(childTop, childH, viewH),
        )
        assertFalse(
            "that jump is the tap-scroll",
            ReaderFocus.honorBringIntoView(childTop, childH, viewH),
        )
    }

    @Test
    fun `already at the chapter start does not need to move`() {
        assertEquals(0, ReaderFocus.bringIntoViewDy(0, 8000, 2000))
        assertTrue(ReaderFocus.honorBringIntoView(0, 8000, 2000))
    }

    @Test
    fun `a short chapter already on screen does not move`() {
        assertEquals(0, ReaderFocus.bringIntoViewDy(100, 400, 2000))
        assertTrue(ReaderFocus.honorBringIntoView(100, 400, 2000))
    }

    @Test
    fun `a short chapter hanging off the top would jump — so a tap must not honour it`() {
        assertEquals(-80, ReaderFocus.bringIntoViewDy(-80, 400, 2000))
        assertFalse(ReaderFocus.honorBringIntoView(-80, 400, 2000))
    }
}
