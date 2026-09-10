package dev.vtlinh.noveldownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* Slack's stored filename is often not {hash}.png. The poll has to
   recognise both the asked-for name and a thread image named image.png. */
class SlackPosterTest {

    @Test
    fun `the asked-for hash png matches`() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(SlackPoster.fileMatchesHash(hash, "$hash.png", ""))
        assertTrue(SlackPoster.fileMatchesHash(hash, "", "$hash.png"))
        assertFalse(SlackPoster.fileMatchesHash(hash, "$hash.txt", ""))
        assertFalse(SlackPoster.fileMatchesHash(hash, "other.png", ""))
    }

    @Test
    fun `a hash sitting inside a generated image name matches`() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(SlackPoster.fileMatchesHash(hash, "${hash}_image.png", "scene"))
        assertTrue(SlackPoster.fileLooksLikeImage("image.png", "", "image/png"))
        assertTrue(SlackPoster.fileLooksLikeImage("photo.JPG", "", ""))
        assertFalse(SlackPoster.fileLooksLikeImage("notes.txt", "", "text/plain"))
    }
}
