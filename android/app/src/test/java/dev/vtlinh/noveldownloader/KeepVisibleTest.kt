package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepVisibleTest {

    @Test
    fun `inserting above the visible row keeps that row and its offset`() {
        val kept = KeepVisible.Anchor(3, 40)
        assertEquals(
            KeepVisible.Anchor(8, 40),
            KeepVisible.afterInsert(kept, insertedAt = 0, inserted = 5),
        )
        assertEquals(kept, KeepVisible.afterInsert(kept, insertedAt = 4, inserted = 5))
        assertEquals(kept, KeepVisible.afterInsert(kept, insertedAt = 0, inserted = 0))
        assertEquals(200, KeepVisible.centerOffset(800, 400))
        assertEquals(0, KeepVisible.centerOffset(400, 800))
    }
}
