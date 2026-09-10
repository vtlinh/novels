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
}
