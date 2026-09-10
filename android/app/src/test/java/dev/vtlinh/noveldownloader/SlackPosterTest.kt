package dev.vtlinh.noveldownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* ChatGPT always names the file {hash}.png. A stub without that name
   does not match — hydrate first, then this check. */
class SlackPosterTest {

    @Test
    fun `the asked-for hash png matches`() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(SlackPoster.fileMatchesHash(hash, "$hash.png", ""))
        assertTrue(SlackPoster.fileMatchesHash(hash, "", "$hash.png"))
        assertTrue(SlackPoster.fileMatchesHash(hash.uppercase(), "$hash.png", ""))
        assertFalse(SlackPoster.fileMatchesHash(hash, "$hash.txt", ""))
        assertFalse(SlackPoster.fileMatchesHash(hash, "other.png", ""))
    }

    @Test
    fun `a generic image png does not match`() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertFalse(SlackPoster.fileMatchesHash(hash, "image.png", "generated image"))
        assertFalse(SlackPoster.fileMatchesHash(hash, "${hash}_image.png", "scene"))
        assertFalse(SlackPoster.fileMatchesHash(hash, "", ""))
    }
}
