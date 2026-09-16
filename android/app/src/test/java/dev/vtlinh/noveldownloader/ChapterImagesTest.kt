package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/* Auto-generate picks chapters N ≥ from where (N − from) is a
   multiple of every, at most one per the wait in Settings. The next due
   N that is not downloaded yet waits — a later due chapter is
   not used in its place. A wait older than 30 days is dropped
   only after a Slack look completed and found no png. The wait
   also stops as soon as Slack confirms the chapter post itself
   is gone. A network miss is not a look. A posted chapter
   shows Poll image instead of Generate. */
class ChapterImagesTest {

    @Test
    fun `a wait is stale after 30 days`() {
        assertEquals(30L * 24L * 60L * 60L * 1000L, ChapterImages.GIVE_UP_MS)
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
        val past = start + 2 * ChapterImages.GIVE_UP_MS
        assertFalse(ChapterImages.mayDrop(start, looked = false, foundNothing = true, past))
        assertTrue(ChapterImages.mayDrop(start, looked = true, foundNothing = true, past))
        assertFalse(ChapterImages.mayDrop(start, looked = true, foundNothing = false, past))
        assertFalse(ChapterImages.mayDrop(start, looked = true, foundNothing = true, start + ChapterImages.GIVE_UP_MS - 1))
        assertFalse(ChapterImages.mayDrop(start, looked = false, foundNothing = true, start))
        assertFalse(ChapterImages.mayDrop(start, looked = true, foundNothing = false, start))
    }

    @Test
    fun `a missing Slack post stops the wait before 30 days are up`() {
        val start = 1_000_000L
        val soon = start + 60_000L
        assertTrue(
            ChapterImages.mayDrop(
                start, looked = true, foundNothing = true, soon, topLevelMissing = true,
            ),
        )
        assertFalse(
            ChapterImages.mayDrop(
                start, looked = true, foundNothing = true, soon, topLevelMissing = false,
            ),
        )
        assertTrue(
            ChapterImages.mayDrop(
                start, looked = false, foundNothing = false, soon, topLevelMissing = true,
            ),
        )
    }

    @Test
    fun `auto-generate waits 30 minutes between chapters`() {
        val start = 1_000_000L
        assertEquals("autoImageLastAt", ChapterImages.autoLastKey())
        assertEquals("autoImageGapMinutes", ChapterImages.GLOBAL_GAP_MINUTES_KEY)
        assertEquals(30, ChapterImages.AUTO_GAP_MINUTES_DEFAULT)
        assertEquals(30L * 60L * 1000L, ChapterImages.AUTO_GAP_MS)
        assertEquals(ChapterImages.AUTO_GAP_MS, ChapterImages.gapMs(30))
        assertTrue(ChapterImages.autoReady(0L, start))
        assertFalse(ChapterImages.autoReady(start, start))
        assertFalse(ChapterImages.autoReady(start, start + ChapterImages.AUTO_GAP_MS - 1))
        assertTrue(ChapterImages.autoReady(start, start + ChapterImages.AUTO_GAP_MS))
        assertEquals(0L, ChapterImages.autoWaitMs(0L, start))
        assertEquals(ChapterImages.AUTO_GAP_MS, ChapterImages.autoWaitMs(start, start))
        assertEquals(0L, ChapterImages.autoWaitMs(start, start + ChapterImages.AUTO_GAP_MS))
        assertEquals(
            ChapterImages.AUTO_GAP_MS,
            ChapterImages.backgroundWaitMs(true, posted = true, lastAt = start, now = start),
        )
        assertEquals(
            0L,
            ChapterImages.backgroundWaitMs(false, posted = false, lastAt = start, now = start),
        )
        assertEquals(
            ChapterImages.AUTO_GAP_MS,
            ChapterImages.backgroundWaitMs(true, posted = false, lastAt = start, now = start),
        )
        assertEquals(
            ChapterImages.AUTO_GAP_MS,
            ChapterImages.backgroundWaitMs(
                true, posted = false, lastAt = start,
                now = start + ChapterImages.AUTO_GAP_MS,
            ),
        )
    }

    @Test
    fun `auto-generate wait minutes can be shorter or longer than the default`() {
        val start = 1_000_000L
        val five = ChapterImages.gapMs(5)
        assertEquals(5L * 60L * 1000L, five)
        assertFalse(ChapterImages.autoReady(start, start + five - 1, five))
        assertTrue(ChapterImages.autoReady(start, start + five, five))
        assertEquals(five, ChapterImages.autoWaitMs(start, start, five))
        assertEquals(
            five,
            ChapterImages.backgroundWaitMs(
                true, posted = true, lastAt = start, now = start, gapMs = five,
            ),
        )
        assertEquals(1, ChapterImages.clampGapMinutes(0))
        assertEquals(1, ChapterImages.clampGapMinutes(-3))
        assertEquals(24 * 60, ChapterImages.clampGapMinutes(10_000))
        assertEquals(ChapterImages.gapMs(1), ChapterImages.gapMs(0))
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
        assertEquals("novelRead:than-y", ChapterImages.readKey("than-y"))
        assertEquals("autoImageUnfinishedOnly", ChapterImages.GLOBAL_UNREAD_KEY)
        assertNotEquals(ChapterImages.autoEnabledKey("a"), ChapterImages.autoEnabledKey("b"))
        assertNotEquals(ChapterImages.autoEveryKey("a"), ChapterImages.autoEveryKey("b"))
        assertNotEquals(ChapterImages.autoFromKey("a"), ChapterImages.autoFromKey("b"))
    }

    @Test
    fun `a novel switch on overrides the global filters`() {
        assertTrue(
            ChapterImages.autoApplies(
                novelOn = true, globalOn = false,
                stars = 0, minStars = 7,
                read = true, unreadOnly = true,
            ),
        )
        assertFalse(
            ChapterImages.autoApplies(
                novelOn = false, globalOn = false,
                stars = 10, minStars = 7,
                read = false, unreadOnly = true,
            ),
        )
        assertTrue(
            ChapterImages.autoApplies(
                novelOn = false, globalOn = true,
                stars = 8, minStars = 7,
                read = false, unreadOnly = true,
            ),
        )
        assertFalse(
            ChapterImages.autoApplies(
                novelOn = false, globalOn = true,
                stars = 6, minStars = 7,
                read = false, unreadOnly = true,
            ),
        )
        assertFalse(
            ChapterImages.autoApplies(
                novelOn = false, globalOn = true,
                stars = 8, minStars = 7,
                read = true, unreadOnly = true,
            ),
        )
        assertTrue(
            ChapterImages.autoApplies(
                novelOn = false, globalOn = true,
                stars = 8, minStars = 7,
                read = true, unreadOnly = false,
            ),
        )
        assertFalse(
            ChapterImages.autoApplies(
                novelOn = false, globalOn = true,
                stars = 0, minStars = 1,
                read = false, unreadOnly = true,
            ),
        )
        assertTrue(
            ChapterImages.autoApplies(
                novelOn = false, globalOn = true,
                stars = 0, minStars = 0,
                read = false, unreadOnly = true,
            ),
        )
        assertEquals(
            ChapterImages.AutoCadence(10, 5),
            ChapterImages.autoCadence(true, 10, 5, 20, 1),
        )
        assertEquals(
            ChapterImages.AutoCadence(20, 1),
            ChapterImages.autoCadence(false, 10, 5, 20, 1),
        )
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
    fun `full-screen swipe steps to the next or previous picture`() {
        assertEquals(1, ChapterImages.neighborSaved(0, 3, 1))
        assertEquals(2, ChapterImages.neighborSaved(1, 3, 1))
        assertEquals(null, ChapterImages.neighborSaved(2, 3, 1))
        assertEquals(0, ChapterImages.neighborSaved(1, 3, -1))
        assertEquals(null, ChapterImages.neighborSaved(0, 3, -1))
        assertEquals(null, ChapterImages.neighborSaved(0, 1, 1))
        assertEquals(null, ChapterImages.neighborSaved(0, 0, 1))
        assertEquals(null, ChapterImages.neighborSaved(-1, 3, 1))
        assertEquals(1, ChapterImages.swipeDelta(-120f, 10f, -400f, 80f, 100f))
        assertEquals(-1, ChapterImages.swipeDelta(120f, -8f, 400f, 80f, 100f))
        assertEquals(null, ChapterImages.swipeDelta(-20f, 4f, -400f, 80f, 100f))
        assertEquals(null, ChapterImages.swipeDelta(-120f, 200f, -400f, 80f, 100f))
        assertEquals(null, ChapterImages.swipeDelta(-120f, 10f, -40f, 80f, 100f))
        assertEquals(1, ChapterImages.swipeDelta(-120f, 10f, -120f, 80f, 0f))
        assertEquals(-1, ChapterImages.indexOfSaved(emptyList(), "Chapter 21.txt"))
    }

    @Test
    fun `Back from the picture list stays on the reading page`() {
        assertTrue(ChapterImages.backFromPictures(true))
        assertFalse(ChapterImages.backFromPictures(false))
    }

    @Test
    fun `a picture loading above the opened one keeps the scroll on it`() {
        assertEquals(400, ChapterImages.scrollShiftWhenAboveGrows(true, 80, 480))
        assertEquals(0, ChapterImages.scrollShiftWhenAboveGrows(false, 80, 480))
        assertEquals(0, ChapterImages.scrollShiftWhenAboveGrows(true, 480, 480))
        assertEquals(-20, ChapterImages.scrollShiftWhenAboveGrows(true, 100, 80))
    }

    @Test
    fun `the picture list opens two neighbours and later loads five at a time`() {
        val mid = ChapterImages.galleryOpenWindow(10, 40)
        assertEquals(8, mid.low)
        assertEquals(12, mid.high)
        val head = ChapterImages.galleryOpenWindow(0, 40)
        assertEquals(0, head.low)
        assertEquals(2, head.high)
        val tail = ChapterImages.galleryOpenWindow(39, 40)
        assertEquals(37, tail.low)
        assertEquals(39, tail.high)
        assertTrue(ChapterImages.galleryOpenWindow(0, 0).isEmpty)
        assertTrue(ChapterImages.galleryOpenWindow(-1, 10).isEmpty)

        val up = ChapterImages.galleryExtendUp(8)
        assertEquals(3, up.low)
        assertEquals(7, up.high)
        val upNearStart = ChapterImages.galleryExtendUp(3)
        assertEquals(0, upNearStart.low)
        assertEquals(2, upNearStart.high)
        assertTrue(ChapterImages.galleryExtendUp(0).isEmpty)

        val down = ChapterImages.galleryExtendDown(12, 40)
        assertEquals(13, down.low)
        assertEquals(17, down.high)
        val downNearEnd = ChapterImages.galleryExtendDown(37, 40)
        assertEquals(38, downNearEnd.low)
        assertEquals(39, downNearEnd.high)
        assertTrue(ChapterImages.galleryExtendDown(39, 40).isEmpty)

        assertTrue(ChapterImages.galleryShouldExtendUp(8, true))
        assertFalse(ChapterImages.galleryShouldExtendUp(8, false))
        assertFalse(ChapterImages.galleryShouldExtendUp(0, true))
        assertTrue(ChapterImages.galleryShouldExtendDown(12, 40, true))
        assertFalse(ChapterImages.galleryShouldExtendDown(12, 40, false))
        assertFalse(ChapterImages.galleryShouldExtendDown(39, 40, true))

        assertTrue(ChapterImages.galleryAtListTop(0))
        assertTrue(ChapterImages.galleryAtListTop(8, 16))
        assertFalse(ChapterImages.galleryAtListTop(80, 16))
        assertTrue(ChapterImages.galleryAtListBottom(0, 800, 800))
        assertTrue(ChapterImages.galleryAtListBottom(1200, 800, 2000))
        assertFalse(ChapterImages.galleryAtListBottom(200, 800, 2000))

        assertTrue(ChapterImages.galleryRowOnScreen(800, 1400, 900, 800))
        assertFalse(ChapterImages.galleryRowOnScreen(800, 1400, 0, 800))
        assertFalse(ChapterImages.galleryRowOnScreen(800, 800, 0, 800))
        assertEquals(600, ChapterImages.galleryPrependShift(600))
        assertEquals(0, ChapterImages.galleryPrependShift(-20))
        assertEquals(1400, ChapterImages.galleryScrollAfterPrepend(800, 600))
        assertEquals(800, ChapterImages.galleryScrollAfterPrepend(800, -20))
        assertEquals(18, ChapterImages.galleryFromTop(18, 0))
        assertEquals(-200, ChapterImages.galleryFromTop(800, 1000))
        assertEquals(2000, ChapterImages.galleryAnchorScrollY(2018, 18))
        assertEquals(0, ChapterImages.galleryAnchorScrollY(10, 40))
        assertTrue(ChapterImages.galleryMayPrepend(false, true, true, true))
        assertFalse(ChapterImages.galleryMayPrepend(true, true, true, true))
        assertFalse(ChapterImages.galleryMayPrepend(false, false, true, true))
        assertFalse(ChapterImages.galleryMayPrepend(false, true, false, true))
        assertFalse(ChapterImages.galleryMayPrepend(false, true, true, false))
        assertEquals(1100, ChapterImages.galleryImageSlot(2000, 120))
        assertEquals(800, ChapterImages.galleryImageSlot(2000, 120, 800))
        assertEquals(1100, ChapterImages.galleryImageSlot(2000, 120, 2000))
        assertEquals(120, ChapterImages.galleryImageSlot(100, 120))
        assertEquals(1, ChapterImages.galleryImageSlot(0, 0))
        assertEquals(450, ChapterImages.galleryPlaceholderH(800, 1100))
        assertEquals(1100, ChapterImages.galleryPlaceholderH(2000, 1100))
        assertEquals(56, ChapterImages.galleryPlaceholderH(100, 120))
        assertEquals(1, ChapterImages.galleryPlaceholderH(0, 0))
        assertEquals(450, ChapterImages.galleryDrawnH(800, 1600, 900, 1100))
        assertEquals(800, ChapterImages.galleryDrawnH(800, 800, 800, 1100))
        assertEquals(1100, ChapterImages.galleryDrawnH(800, 400, 800, 1100))
        assertEquals(1100, ChapterImages.galleryDrawnH(0, 1600, 900, 1100))
        assertEquals(1, ChapterImages.galleryDrawnH(800, 0, 0, 0))
    }

    @Test
    fun `the picture list names the chapter then the picture`() {
        assertEquals("The oath", ChapterImages.headingTitle("Chapter 21: The oath\nOnce…"))
        assertEquals("Tiêu Viêm", ChapterImages.headingTitle("Chương 5: Tiêu Viêm"))
        assertEquals("", ChapterImages.headingTitle("Chapter 21"))
        assertEquals("", ChapterImages.headingTitle("  \n"))
        assertEquals("21: The oath", ChapterImages.galleryChapterLine(21, "The oath"))
        assertEquals("21", ChapterImages.galleryChapterLine(21, "  "))
        assertEquals("21: Chapter 21", ChapterImages.galleryChapterLine(21, "", "Chapter 21"))
        assertEquals("21", ChapterImages.galleryChapterLine(21, "", "21"))
        assertEquals("The oath", ChapterImages.galleryChapterLine(Int.MAX_VALUE, "The oath", "notes"))
        assertEquals("notes", ChapterImages.galleryChapterLine(Int.MAX_VALUE, "", "notes"))
        assertEquals(
            "21: The oath\nA moth at the window",
            ChapterImages.galleryCaption(21, "The oath", "A moth at the window"),
        )
        assertEquals("21: The oath", ChapterImages.galleryCaption(21, "The oath", "  "))
        assertEquals("A moth at the window", ChapterImages.galleryCaption(0, "", "A moth at the window"))
        assertEquals(
            "21: The oath\nA moth at the window",
            ChapterImages.galleryCaption(21, " The oath ", "  A moth at the window  ", "21"),
        )
        assertEquals(
            "21: Chapter 21\nA moth at the window",
            ChapterImages.galleryCaption(21, "", "A moth at the window", "Chapter 21"),
        )
    }

    @Test
    fun `the list opens this chapter or the closest later pictured one`() {
        val pictured = listOf("Chapter 1.txt", "Chapter 21.txt", "Chapter 41.txt")
        val ordered = (1..50).map { "Chapter $it.txt" }
        assertEquals("Chapter 21.txt", ChapterImages.startImageChapter(pictured, "Chapter 21.txt"))
        assertEquals(
            "Chapter 21.txt",
            ChapterImages.startImageChapter(ordered, pictured, "Chapter 15.txt"),
        )
        assertEquals(
            "Chapter 21.txt",
            ChapterImages.startImageChapter(ordered, pictured, "Chapter 2.txt"),
        )
        assertEquals("Chapter 1.txt", ChapterImages.startImageChapter(pictured, "Chapter 1.txt"))
        assertEquals(
            null,
            ChapterImages.startImageChapter(ordered, pictured, "Chapter 50.txt"),
        )
        assertEquals(null, ChapterImages.startImageChapter(emptyList(), "Chapter 1.txt"))
    }

    @Test
    fun `a stored Slack thread is enough to look up a missing picture`() {
        assertEquals(
            listOf("1.1"),
            ChapterImages.storedThreads("1.1", emptyList()),
        )
        assertEquals(
            listOf("1.1", "2.2"),
            ChapterImages.storedThreads("1.1", listOf("1.1", "2.2", "")),
        )
        assertEquals(emptyList<String>(), ChapterImages.storedThreads("", emptyList()))
    }

    @Test
    fun `the Slack thread is dropped only when Slack says it is gone or the picture is saved`() {
        assertTrue(ChapterImages.shouldClearSlack(threadGone = true, imageSaved = false))
        assertTrue(ChapterImages.shouldClearSlack(threadGone = false, imageSaved = true))
        assertTrue(ChapterImages.shouldClearSlack(threadGone = true, imageSaved = true))
        assertFalse(ChapterImages.shouldClearSlack(threadGone = false, imageSaved = false))
    }

    @Test
    fun `a tap on the object-replacement char is a tap on the picture`() {
        assertTrue(ChapterImages.objectReplacementAt("A\uFFFC\nmore", 1))
        assertTrue(ChapterImages.objectReplacementAt("A\uFFFC\nmore", 2))
        assertFalse(ChapterImages.objectReplacementAt("A\uFFFC\nmore", 0))
        assertFalse(ChapterImages.objectReplacementAt("A\uFFFC\nmore", 3))
        assertFalse(ChapterImages.objectReplacementAt("no picture", 2))
        assertFalse(ChapterImages.imageAt("A\uFFFC\nmore", 1))
        assertFalse(ChapterImages.hasEmbeddedPicture("A\uFFFC\nmore"))
        assertFalse(ChapterImages.hasEmbeddedPicture("no picture"))
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

    /* THE DEFECT. The chapter list waited for synopsis-grid thumbs
       before showing the picture mark. The mark is the image column
       on the chapter row — the same read as the listing. */
    @Test
    fun `picture marks come from the chapter row image column`() {
        val rows = listOf(
            ChapterImage("Chapter 1.txt", "scenes/Chapter 1.png"),
            ChapterImage("Chapter 2.txt", ""),
            ChapterImage("Chapter 21.txt", "scenes/Chapter 21.png", "A lantern"),
            ChapterImage("", "orphan.png"),
        )
        assertEquals(
            setOf("Chapter 1.txt", "Chapter 21.txt"),
            ChapterImages.picturedChapters(rows),
        )
        assertEquals(emptySet<String>(), ChapterImages.picturedChapters(emptyList()))
        assertTrue(
            ChapterImagePreview.shouldShowListButton(
                ChapterImages.picturedChapters(rows).contains("Chapter 1.txt"),
            ),
        )
        assertFalse(
            ChapterImagePreview.shouldShowListButton(
                ChapterImages.picturedChapters(rows).contains("Chapter 2.txt"),
            ),
        )
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

    @Test
    fun `picture logs name the novel and chapter`() {
        assertEquals(
            "Library of Heaven's Path, chapter 21",
            ChapterImages.describe("Library of Heaven's Path", "Chapter 21.txt"),
        )
        assertEquals(
            "Library of Heaven's Path, chapter 1",
            ChapterImages.describe("Library of Heaven's Path", "Chapter 1 - Prologue.txt"),
        )
        assertEquals("This novel, chapter 5", ChapterImages.describe("  ", "Chapter 5.txt"))
        assertEquals("My Book, notes", ChapterImages.describe("My Book", "notes.txt"))
    }

    @Test
    fun `picture logs report Slack catalog results in words`() {
        assertEquals(
            "Checking Slack for pictures already made for Library of Heaven's Path — 24 chapters",
            ChapterImages.catalogLookLine("Library of Heaven's Path", 24),
        )
        assertEquals(
            "Checking Slack for pictures already made for this novel — 1 chapter",
            ChapterImages.catalogLookLine("", 1),
        )
        assertEquals(
            "Library of Heaven's Path: Slack has no picture yet for 24 chapters",
            ChapterImages.catalogResultLine("Library of Heaven's Path", 0, 24),
        )
        assertEquals(
            "Library of Heaven's Path: Slack already had pictures for 2 chapters — saved",
            ChapterImages.catalogResultLine("Library of Heaven's Path", 2, 0),
        )
        assertEquals(
            "Library of Heaven's Path: saved 1 chapter from Slack; 23 chapters still have none",
            ChapterImages.catalogResultLine("Library of Heaven's Path", 1, 23),
        )
    }

    @Test
    fun `picture logs say how large a file is and how long a wait is`() {
        assertEquals("400 B", ChapterImages.sizeLabel(400))
        assertEquals("1 KB", ChapterImages.sizeLabel(1024))
        assertEquals("45 KB", ChapterImages.sizeLabel(45 * 1024))
        assertEquals("1.5 MB", ChapterImages.sizeLabel((1024 + 512) * 1024))
        assertEquals("about 1 minute", ChapterImages.waitLabel(20_000L))
        assertEquals("about 5 minutes", ChapterImages.waitLabel(5L * 60L * 1000L))
        assertEquals("about 50 minutes", ChapterImages.waitLabel(50L * 60L * 1000L))
        assertEquals("about 1 hour", ChapterImages.waitLabel(60L * 60L * 1000L))
        assertEquals("about 23 hours", ChapterImages.waitLabel(23L * 60L * 60L * 1000L))
        assertEquals("about 1 day", ChapterImages.waitLabel(24L * 60L * 60L * 1000L))
        assertEquals("about 30 days", ChapterImages.waitLabel(ChapterImages.GIVE_UP_MS))
    }
}
