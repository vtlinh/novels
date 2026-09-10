package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/* Auto-generate picks chapters N ≥ from where (N − from) is a
   multiple of every, at most one every 15 minutes. A wait older
   than an hour is dropped only after Slack has been looked at
   once more. A posted chapter shows Poll image instead of Generate. */
class ChapterImagesTest {

    @Test
    fun `a wait is stale after one hour`() {
        val start = 1_000_000L
        assertFalse(ChapterImages.expired(start, start))
        assertFalse(ChapterImages.expired(start, start + ChapterImages.GIVE_UP_MS - 1))
        assertTrue(ChapterImages.expired(start, start + ChapterImages.GIVE_UP_MS))
        assertTrue(ChapterImages.expired(start, start + ChapterImages.GIVE_UP_MS + 60_000L))
        assertFalse(ChapterImages.expired(0L, ChapterImages.GIVE_UP_MS - 1))
        assertTrue(ChapterImages.expired(0L, ChapterImages.GIVE_UP_MS))
    }

    @Test
    fun `an expired wait is not dropped until Slack has been looked at`() {
        val start = 1_000_000L
        val twoHours = start + 2 * ChapterImages.GIVE_UP_MS
        assertFalse(ChapterImages.mayDrop(start, looked = false, twoHours))
        assertTrue(ChapterImages.mayDrop(start, looked = true, twoHours))
        assertFalse(ChapterImages.mayDrop(start, looked = true, start + ChapterImages.GIVE_UP_MS - 1))
        assertFalse(ChapterImages.mayDrop(start, looked = false, start))
    }

    @Test
    fun `auto-generate waits 15 minutes between chapters`() {
        val start = 1_000_000L
        assertEquals("autoImageLastAt", ChapterImages.autoLastKey())
        assertEquals(15L * 60L * 1000L, ChapterImages.AUTO_GAP_MS)
        assertTrue(ChapterImages.autoReady(0L, start))
        assertFalse(ChapterImages.autoReady(start, start))
        assertFalse(ChapterImages.autoReady(start, start + ChapterImages.AUTO_GAP_MS - 1))
        assertTrue(ChapterImages.autoReady(start, start + ChapterImages.AUTO_GAP_MS))
        assertEquals(0L, ChapterImages.autoWaitMs(0L, start))
        assertEquals(ChapterImages.AUTO_GAP_MS, ChapterImages.autoWaitMs(start, start))
        assertEquals(0L, ChapterImages.autoWaitMs(start, start + ChapterImages.AUTO_GAP_MS))
    }

    @Test
    fun `auto-generate prefs are keyed by novel slug`() {
        assertEquals("autoImage:than-y", ChapterImages.autoEnabledKey("than-y"))
        assertEquals("autoImageEvery:than-y", ChapterImages.autoEveryKey("than-y"))
        assertEquals("autoImageFrom:than-y", ChapterImages.autoFromKey("than-y"))
        assertNotEquals(ChapterImages.autoEnabledKey("a"), ChapterImages.autoEnabledKey("b"))
        assertNotEquals(ChapterImages.autoEveryKey("a"), ChapterImages.autoEveryKey("b"))
        assertNotEquals(ChapterImages.autoFromKey("a"), ChapterImages.autoFromKey("b"))
    }

    @Test
    fun `defaults pick chapter 1 then every 20`() {
        val from = ChapterImages.AUTO_FROM_DEFAULT
        val every = ChapterImages.AUTO_EVERY_DEFAULT
        assertTrue(ChapterImages.due(1, from, every))
        assertFalse(ChapterImages.due(2, from, every))
        assertFalse(ChapterImages.due(20, from, every))
        assertTrue(ChapterImages.due(21, from, every))
        assertFalse(ChapterImages.due(40, from, every))
        assertTrue(ChapterImages.due(41, from, every))
    }

    @Test
    fun `a later start skips earlier chapters`() {
        assertFalse(ChapterImages.due(1, 5, 10))
        assertFalse(ChapterImages.due(4, 5, 10))
        assertTrue(ChapterImages.due(5, 5, 10))
        assertFalse(ChapterImages.due(6, 5, 10))
        assertFalse(ChapterImages.due(10, 5, 10))
        assertTrue(ChapterImages.due(15, 5, 10))
        assertTrue(ChapterImages.due(25, 5, 10))
    }

    @Test
    fun `every chapter from the start when the step is 1`() {
        assertTrue(ChapterImages.due(3, 3, 1))
        assertTrue(ChapterImages.due(4, 3, 1))
        assertFalse(ChapterImages.due(2, 3, 1))
    }

    @Test
    fun `a waiting chapter offers Poll image instead of Generate`() {
        assertEquals(ChapterImages.ImageAction.HIDE, ChapterImages.imageAction(true, false))
        assertEquals(ChapterImages.ImageAction.HIDE, ChapterImages.imageAction(true, true))
        assertEquals(ChapterImages.ImageAction.POLL, ChapterImages.imageAction(false, true))
        assertEquals(ChapterImages.ImageAction.GENERATE, ChapterImages.imageAction(false, false))
        assertTrue(ChapterImages.lockGenerate(true, false))
        assertTrue(ChapterImages.lockGenerate(false, true))
        assertFalse(ChapterImages.lockGenerate(false, false))
    }

    @Test
    fun `do not post again when this chapter already has a Slack file`() {
        assertFalse(ChapterImages.shouldPost(postIfMissing = true, alreadyPosted = true))
        assertFalse(ChapterImages.shouldPost(postIfMissing = false, alreadyPosted = true))
        assertFalse(ChapterImages.shouldPost(postIfMissing = false, alreadyPosted = false))
        assertTrue(ChapterImages.shouldPost(postIfMissing = true, alreadyPosted = false))
    }

    @Test
    fun `a zero or negative step matches nothing`() {
        assertFalse(ChapterImages.due(1, 1, 0))
        assertFalse(ChapterImages.due(21, 1, 0))
        assertFalse(ChapterImages.due(1, 1, -20))
        assertFalse(ChapterImages.due(5, 0, 20))
        assertFalse(ChapterImages.due(5, -1, 20))
    }

    @Test
    fun `imageDocId is the tree-document id under the novel scenes folder`() {
        assertEquals(
            "primary:Novels/The Novel/scenes/Chapter 1.png",
            ChapterImages.imageDocId("primary:Novels", "The Novel", "Chapter 1.png"),
        )
        assertEquals(
            "primary:Novels/The Novel/scenes",
            ChapterImages.imageDocId("primary:Novels", "The Novel", ""),
        )
    }

    @Test
    fun `a stored document id is used as-is`() {
        assertEquals(
            "primary:Novels/The Novel/scenes/Chapter 1.png",
            ChapterImages.resolveImageDocId(
                "primary:Novels", "Other Name",
                "primary:Novels/The Novel/scenes/Chapter 1.png",
            ),
        )
        assertEquals(
            "primary:Novels/The Novel/scenes/Chapter 1.png",
            ChapterImages.resolveImageDocId("primary:Novels", "The Novel", "Chapter 1.png"),
        )
    }

    @Test
    fun `synopsis grid tap expands the picture instead of opening the chapter`() {
        assertEquals(ChapterImages.SynopsisTap.EXPAND, ChapterImages.synopsisTap())
        assertNotEquals(ChapterImages.SynopsisTap.OPEN_CHAPTER, ChapterImages.synopsisTap())
    }

    @Test
    fun `empty alt hides the caption`() {
        assertFalse(ChapterImages.showAlt(""))
        assertFalse(ChapterImages.showAlt("   "))
        assertTrue(ChapterImages.showAlt("A lantern in the rain"))
    }

    @Test
    fun `savedOf keeps a trimmed alt`() {
        assertEquals("A lantern in the rain", ChapterImages.altText("  A lantern in the rain  "))
        assertEquals("", ChapterImages.altText(""))
        assertFalse(ChapterImages.showAlt(ChapterImages.altText("")))
    }

    @Test
    fun `synopsis grid sorts pictures by chapter number`() {
        val names = listOf("Chapter 400.txt", "Chapter 374.txt", "Chapter 10.txt", "notes.txt")
        val sorted = names.sortedWith(
            compareBy<String> { Scenes.chapterNumber(it) ?: Int.MAX_VALUE }.thenBy { it },
        )
        assertEquals(
            listOf("Chapter 10.txt", "Chapter 374.txt", "Chapter 400.txt", "notes.txt"),
            sorted,
        )
    }

    @Test
    fun `Generate image looks up the chapter png by id not by listing scenes`() {
        assertEquals(
            "primary:Novels/The Novel/scenes/Chapter 12.png",
            ChapterImages.imageDocId(
                "primary:Novels", "The Novel", Scenes.imageName("Chapter 12.txt"),
            ),
        )
    }
}
