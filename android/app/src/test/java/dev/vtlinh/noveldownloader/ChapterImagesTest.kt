package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/* Auto-generate picks chapters N ≥ from where (N − from) is a
   multiple of every, at most one every 15 minutes. The next due
   N that is not downloaded yet waits — a later due chapter is
   not used in its place. A wait older than an hour is dropped
   only after a Slack look completed and found no thread, file,
   or png. A network miss is not a look. A posted chapter
   shows Poll image instead of Generate. */
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
    fun `an expired wait is not dropped until Slack found nothing`() {
        val start = 1_000_000L
        val twoHours = start + 2 * ChapterImages.GIVE_UP_MS
        assertFalse(ChapterImages.mayDrop(start, looked = false, foundNothing = true, twoHours))
        assertTrue(ChapterImages.mayDrop(start, looked = true, foundNothing = true, twoHours))
        assertFalse(ChapterImages.mayDrop(start, looked = true, foundNothing = false, twoHours))
        assertFalse(ChapterImages.mayDrop(start, looked = true, foundNothing = true, start + ChapterImages.GIVE_UP_MS - 1))
        assertFalse(ChapterImages.mayDrop(start, looked = false, foundNothing = true, start))
        assertFalse(ChapterImages.mayDrop(start, looked = true, foundNothing = false, start))
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
    fun `auto-generate prefers the novel read most recently`() {
        val older = ChapterImages.AutoNovel(
            slug = "older", lastRead = 10, from = 1, every = 20,
            chapters = listOf("Chapter 1.txt", "Chapter 21.txt"),
        )
        val newer = ChapterImages.AutoNovel(
            slug = "newer", lastRead = 50, from = 1, every = 20,
            chapters = listOf("Chapter 1.txt", "Chapter 21.txt"),
        )
        val unread = ChapterImages.AutoNovel(
            slug = "unread", lastRead = 0, from = 1, every = 20,
            chapters = listOf("Chapter 1.txt"),
        )
        assertEquals(
            listOf("newer", "older", "unread"),
            ChapterImages.byLastRead(
                listOf(unread, older, newer),
                { it.lastRead },
                { it.slug },
            ).map { it.slug },
        )
        assertEquals(
            ChapterImages.AutoPick("newer", "Chapter 1.txt"),
            ChapterImages.nextAuto(listOf(unread, older, newer)) { _, _ -> false },
        )
        assertEquals(
            ChapterImages.AutoPick("older", "Chapter 1.txt"),
            ChapterImages.nextAuto(listOf(unread, older, newer)) { slug, _ -> slug == "newer" },
        )
        assertEquals(
            ChapterImages.AutoPick("newer", "Chapter 21.txt"),
            ChapterImages.nextAuto(listOf(older, newer)) { _, chapter ->
                chapter == "Chapter 1.txt"
            },
        )
    }

    @Test
    fun `a never-read novel waits behind every novel that has been read`() {
        val read = ChapterImages.AutoNovel(
            slug = "read", lastRead = 1, from = 5, every = 1,
            chapters = listOf("Chapter 5.txt"),
        )
        val unread = ChapterImages.AutoNovel(
            slug = "unread", lastRead = 0, from = 1, every = 1,
            chapters = listOf("Chapter 1.txt"),
        )
        assertEquals(
            ChapterImages.AutoPick("read", "Chapter 5.txt"),
            ChapterImages.nextAuto(listOf(unread, read)) { _, _ -> false },
        )
    }

    @Test
    fun `auto-generate waits when the starting chapter is not downloaded yet`() {
        val laterOnly = ChapterImages.AutoNovel(
            slug = "later", lastRead = 9, from = 1, every = 20,
            chapters = listOf("Chapter 21.txt", "Chapter 41.txt"),
        )
        assertEquals(null, ChapterImages.nextDueName(laterOnly.chapters, 1, 20) { false })
        assertEquals(null, ChapterImages.nextAuto(listOf(laterOnly)) { _, _ -> false })
        val notYet = ChapterImages.AutoNovel(
            slug = "not-yet", lastRead = 8, from = 100, every = 20,
            chapters = listOf("Chapter 1.txt", "Chapter 80.txt", "Chapter 120.txt"),
        )
        assertEquals(null, ChapterImages.nextDueName(notYet.chapters, 100, 20) { false })
        assertEquals(null, ChapterImages.nextAuto(listOf(notYet)) { _, _ -> false })
        val ready = ChapterImages.AutoNovel(
            slug = "ready", lastRead = 7, from = 100, every = 20,
            chapters = listOf("Chapter 80.txt", "Chapter 100.txt", "Chapter 120.txt"),
        )
        assertEquals("Chapter 100.txt", ChapterImages.nextDueName(ready.chapters, 100, 20) { false })
        assertEquals(
            ChapterImages.AutoPick("ready", "Chapter 100.txt"),
            ChapterImages.nextAuto(listOf(laterOnly, notYet, ready)) { _, _ -> false },
        )
        val firstDone = ChapterImages.AutoNovel(
            slug = "first-done", lastRead = 6, from = 1, every = 20,
            chapters = listOf("Chapter 1.txt", "Chapter 21.txt"),
        )
        assertEquals(
            "Chapter 21.txt",
            ChapterImages.nextDueName(firstDone.chapters, 1, 20) { it == "Chapter 1.txt" },
        )
        val waiting = ChapterImages.AutoNovel(
            slug = "waiting", lastRead = 90, from = 100, every = 20,
            chapters = listOf("Chapter 1.txt", "Chapter 50.txt"),
        )
        val available = ChapterImages.AutoNovel(
            slug = "available", lastRead = 20, from = 1, every = 20,
            chapters = listOf("Chapter 1.txt"),
        )
        assertEquals(
            ChapterImages.AutoPick("available", "Chapter 1.txt"),
            ChapterImages.nextAuto(listOf(waiting, available)) { _, _ -> false },
        )
    }

    @Test
    fun `auto-generate picks nothing when every due chapter is already done`() {
        val novel = ChapterImages.AutoNovel(
            slug = "done", lastRead = 9, from = 1, every = 20,
            chapters = listOf("Chapter 1.txt", "Chapter 2.txt", "Chapter 21.txt"),
        )
        assertEquals(
            null,
            ChapterImages.nextAuto(listOf(novel)) { _, chapter ->
                chapter == "Chapter 1.txt" || chapter == "Chapter 21.txt"
            },
        )
        assertEquals(
            null,
            ChapterImages.nextAuto(
                listOf(novel.copy(chapters = listOf("Chapter 2.txt", "notes.txt"))),
            ) { _, _ -> false },
        )
    }

    @Test
    fun `waiting downloads use the same last-read order`() {
        data class Wait(val slug: String, val chapter: String, val lastRead: Long)
        val waits = listOf(
            Wait("old", "Chapter 1.txt", 10),
            Wait("new", "Chapter 40.txt", 80),
            Wait("mid", "Chapter 2.txt", 40),
            Wait("never", "Chapter 3.txt", 0),
        )
        assertEquals(
            listOf("new", "mid", "old", "never"),
            ChapterImages.byLastRead(waits, { it.lastRead }, { it.slug }).map { it.slug },
        )
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
        assertTrue(
            ChapterImages.showAlt(
                "Prince Roland orders his officers to drive their steel " +
                    "river gunboat at full speed ahead from its compact command room.",
            ),
        )
    }

    @Test
    fun `a saved png with no caption still asks Slack`() {
        assertTrue(ChapterImages.needsAltRefresh(true, ""))
        assertTrue(ChapterImages.needsAltRefresh(true, "   "))
        assertFalse(ChapterImages.needsAltRefresh(true, "A lantern in the rain"))
        assertFalse(ChapterImages.needsAltRefresh(false, ""))
    }

    @Test
    fun `savedOf keeps a trimmed alt`() {
        val desc = "Prince Roland orders his officers to drive their steel " +
            "river gunboat at full speed ahead from its compact command room."
        assertEquals("A lantern in the rain", ChapterImages.altText("  A lantern in the rain  "))
        assertEquals(desc, ChapterImages.altText("  $desc  "))
        assertEquals("", ChapterImages.altText(""))
        assertFalse(ChapterImages.showAlt(ChapterImages.altText("")))
        assertTrue(ChapterImages.showAlt(ChapterImages.altText(desc)))
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
