package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomTest {

    @Test
    fun `pinch scale stays between fit and five times`() {
        assertEquals(1f, Zoom.MIN)
        assertEquals(5f, Zoom.MAX)
        assertEquals(2f, Zoom.scale(1f, 2f))
        assertEquals(5f, Zoom.scale(4f, 2f))
        assertEquals(1f, Zoom.scale(1f, 0.5f))
        assertEquals(1f, Zoom.scale(2f, 0.1f))
        assertFalse(Zoom.canPan(1f))
        assertTrue(Zoom.canPan(1.01f))
        assertTrue(Zoom.canPan(5f))
    }

    @Test
    fun `fit-center uses the smaller side and sits in the middle`() {
        val square = Zoom.fit(1000f, 1000f, 500f, 500f)
        assertEquals(2f, square.scale)
        assertEquals(0f, square.tx)
        assertEquals(0f, square.ty)
        val wideView = Zoom.fit(1000f, 500f, 1000f, 1000f)
        assertEquals(0.5f, wideView.scale)
        assertEquals(250f, wideView.tx)
        assertEquals(0f, wideView.ty)
        val empty = Zoom.fit(0f, 1000f, 100f, 100f)
        assertEquals(1f, empty.scale)
        assertEquals(0f, empty.tx)
    }

    @Test
    fun `a pinch keeps the focus point under the fingers`() {
        val (scale, tx, ty) = Zoom.pinch(1f, 0f, 0f, 2f, 250f, 250f)
        assertEquals(2f, scale)
        assertEquals(-250f, tx)
        assertEquals(-250f, ty)
        /* the pixel that was at 250 stays at 250: 250 * 2 + tx */
        assertEquals(250f, 250f * scale + tx)
        val out = Zoom.pinch(2f, -250f, -250f, 0.5f, 250f, 250f)
        assertEquals(1f, out.first)
        assertEquals(0f, out.second)
        assertEquals(0f, out.third)
    }

    @Test
    fun `pan cannot slide a zoomed picture off the view`() {
        val held = Zoom.clamp(2f, -100f, -100f, 1000f, 1000f, 1000f, 1000f)
        assertEquals(-100f, held.first)
        assertEquals(-100f, held.second)
        val yanked = Zoom.clamp(2f, -5000f, 50f, 1000f, 1000f, 1000f, 1000f)
        assertEquals(-1000f, yanked.first)
        assertEquals(0f, yanked.second)
        val letterbox = Zoom.clamp(1f, 99f, 99f, 1000f, 1000f, 1000f, 500f)
        assertEquals(0f, letterbox.first)
        assertEquals(250f, letterbox.second)
    }
}
