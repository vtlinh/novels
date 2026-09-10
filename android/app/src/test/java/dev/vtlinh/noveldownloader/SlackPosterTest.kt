package dev.vtlinh.noveldownloader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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

    @Test
    fun `the asked-for hash txt matches only as txt`() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(SlackPoster.fileMatchesTxt(hash, "$hash.txt", ""))
        assertTrue(SlackPoster.fileMatchesTxt(hash, "", "$hash.txt"))
        assertFalse(SlackPoster.fileMatchesTxt(hash, "$hash.png", ""))
        assertFalse(SlackPoster.fileMatchesTxt(hash, "other.txt", ""))
        assertFalse(SlackPoster.fileMatchesHash(hash, "$hash.txt", ""))
    }

    @Test
    fun `channel history uses the message ts as the txt thread`() {
        val hash = "5dc9a2e5732e7789f3edd21debdfa4519c2c16003ba4ac3ccb800e6c2caca186"
        val first = JSONObject()
            .put("ts", "1789014134.537779")
            .put(
                "files",
                JSONArray().put(
                    JSONObject().put("id", "F0C0VAUNL8H").put("name", "$hash.txt"),
                ),
            )
        val second = JSONObject()
            .put("ts", "1789043986.891699")
            .put(
                "files",
                JSONArray().put(
                    JSONObject().put("id", "F0C0WNRSQV8").put("name", "$hash.txt"),
                ),
            )
        val other = JSONObject()
            .put("ts", "1789040000.000000")
            .put(
                "files",
                JSONArray().put(
                    JSONObject().put("name", "9dbbc0ca.txt"),
                ),
            )
        assertEquals(
            listOf("1789014134.537779", "1789043986.891699"),
            SlackPoster.historyTxtThreads(listOf(first, second, other), hash),
        )
    }

    @Test
    fun `a reply with the txt uses the parent thread ts`() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val reply = JSONObject()
            .put("ts", "1789015000.000001")
            .put("thread_ts", "1789014134.537779")
            .put(
                "files",
                JSONArray().put(JSONObject().put("name", "$hash.txt")),
            )
        assertEquals(
            listOf("1789014134.537779"),
            SlackPoster.historyTxtThreads(listOf(reply), hash),
        )
    }

    @Test
    fun `a png in history is not a posted chapter`() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val png = JSONObject()
            .put("ts", "1789015000.000001")
            .put(
                "files",
                JSONArray().put(JSONObject().put("name", "$hash.png")),
            )
        assertEquals(emptyList<String>(), SlackPoster.historyTxtThreads(listOf(png), hash))
    }
}
