package dev.vtlinh.noveldownloader

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {

    @Test
    fun `the update notice ranks above downloads`() {
        assertEquals("updates_high", Updater.UPDATE_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, Updater.UPDATE_IMPORTANCE)
        assertTrue(Updater.UPDATE_IMPORTANCE > NotificationManager.IMPORTANCE_LOW)
        assertTrue(Updater.UPDATE_IMPORTANCE > NotificationManager.IMPORTANCE_MIN)
        assertTrue(Updater.UPDATE_IMPORTANCE > NotificationManager.IMPORTANCE_DEFAULT)
    }
}
