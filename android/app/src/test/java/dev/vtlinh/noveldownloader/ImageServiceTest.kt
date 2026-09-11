package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/* Chapter pictures ride the read-aloud notification. They used to
   post their own; that channel is deleted so an existing install
   does not keep a leftover Chapter pictures tile. */
class ImageServiceTest {

    @Test
    fun `chapter pictures drop their old notification instead of posting a new one`() {
        assertEquals("chapter_images", ImageService.LEGACY_CHANNEL)
        assertEquals(4, ImageService.LEGACY_NOTIF_ID)
        /* The player notification is a different slot — canceling
           the leftover picture tile must not clear read aloud. */
        assertNotEquals(TtsService.NOTIF_ID, ImageService.LEGACY_NOTIF_ID)
    }
}
