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
}
