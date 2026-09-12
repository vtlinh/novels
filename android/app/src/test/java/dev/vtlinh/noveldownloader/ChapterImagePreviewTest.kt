package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* The chapter-picture dialog fades on its own only when it opened
   itself. A tap on the card, or opening it from the menu button,
   leaves close to the reader (outside tap or Back). */
class ChapterImagePreviewTest {

    @Test
    fun `the menu button is only for a chapter that has a picture`() {
        assertTrue(ChapterImagePreview.shouldShowButton(true))
        assertFalse(ChapterImagePreview.shouldShowButton(false))
    }

    /* THE DEFECT. A focusable picture button on the chapter row made
       ListView drop the tap, so a pictured chapter could not be opened. */
    @Test
    fun `the picture button does not take focus from the chapter row`() {
        assertFalse(ChapterImagePreview.pictureButtonFocusable())
    }

    @Test
    fun `opening a pictured chapter shows the dialog once`() {
        assertTrue(ChapterImagePreview.shouldAutoOpen(true, true, false))
        assertFalse(ChapterImagePreview.shouldAutoOpen(true, true, true))
        assertFalse(ChapterImagePreview.shouldAutoOpen(true, false, false))
        assertFalse(ChapterImagePreview.shouldAutoOpen(false, true, false))
    }

    @Test
    fun `a self-opened dialog fades and a held one does not`() {
        assertEquals(ChapterImagePreview.Mode.AUTO, ChapterImagePreview.startMode(true))
        assertEquals(ChapterImagePreview.Mode.HOLD, ChapterImagePreview.startMode(false))
        assertTrue(ChapterImagePreview.shouldFade(ChapterImagePreview.Mode.AUTO))
        assertFalse(ChapterImagePreview.shouldFade(ChapterImagePreview.Mode.HOLD))
        assertEquals(
            ChapterImagePreview.Mode.HOLD,
            ChapterImagePreview.afterInteract(ChapterImagePreview.Mode.AUTO),
        )
        assertFalse(
            ChapterImagePreview.shouldFade(
                ChapterImagePreview.afterInteract(ChapterImagePreview.Mode.AUTO),
            ),
        )
        assertEquals(15_000L, ChapterImagePreview.AUTO_MS)
    }
}
