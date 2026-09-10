package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* Slack filename is the content hash. Scene-file detection is only for
   leftover scenes/ folders Storage still counts. */
class ScenesTest {

    @Test
    fun `chapter files drop the txt suffix`() {
        assertEquals("Chapter 12", Scenes.chapterBase("Chapter 12.txt"))
        assertEquals("Chapter 12", Scenes.chapterBase("Chapter 12.txt.gz"))
    }

    @Test
    fun `scene files are pictures or json, not chapters`() {
        assertTrue(Scenes.isSceneFile("Chapter 1.json"))
        assertTrue(Scenes.isSceneFile("Chapter 1.png"))
        assertFalse(Scenes.isSceneFile("Chapter 1.txt"))
        assertFalse(Scenes.isSceneFile("notes.txt"))
    }

    @Test
    fun `Slack filename is SHA-256 of the chapter bytes and nothing else`() {
        /* SHA-256("abc") — known vector, proves the digest is not salted. */
        val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(abc, Scenes.contentHash("abc"))
        assertEquals("$abc.txt", Scenes.slackFileName("abc"))
        assertEquals("$abc.png", Scenes.slackImageName(abc))
        assertEquals("Chapter 12.png", Scenes.imageName("Chapter 12.txt"))
        assertEquals(Scenes.contentHash("abc"), Scenes.contentHash("abc"))
        assertTrue(Scenes.contentHash("abc") != Scenes.contentHash("abc\n"))
        assertEquals(64, Scenes.contentHash("any chapter text").length)
        assertFalse(Scenes.slackFileName("abc").contains("scene"))
        assertFalse(Scenes.slackFileName("abc").startsWith("scene-"))
    }

    @Test
    fun `scene pictures sort by the chapter number in the name`() {
        assertEquals("Chapter 12", Scenes.chapterStem("Chapter 12.png"))
        assertEquals(12, Scenes.chapterNumber("Chapter 12.png"))
        assertEquals(374, Scenes.chapterNumber("Chapter 374.jpg"))
        assertEquals(1, Scenes.chapterNumber("Chapter 1.txt.gz"))
        assertTrue((Scenes.chapterNumber("Chapter 2.png") ?: 0) < (Scenes.chapterNumber("Chapter 10.png") ?: 0))
        val names = listOf("Chapter 10.png", "Chapter 2.png", "notes.png")
        val sorted = names.sortedWith(
            compareBy<String> { Scenes.chapterNumber(it) ?: Int.MAX_VALUE }.thenBy { it },
        )
        assertEquals(listOf("Chapter 2.png", "Chapter 10.png", "notes.png"), sorted)
    }
}
