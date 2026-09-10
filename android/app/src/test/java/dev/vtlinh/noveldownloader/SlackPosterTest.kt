package dev.vtlinh.noveldownloader

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
        val first = SlackPoster.HistoryMsg(
            "1789014134.537779",
            files = listOf(SlackPoster.NamedFile("$hash.txt")),
        )
        val second = SlackPoster.HistoryMsg(
            "1789043986.891699",
            files = listOf(SlackPoster.NamedFile("$hash.txt")),
        )
        val other = SlackPoster.HistoryMsg(
            "1789040000.000000",
            files = listOf(SlackPoster.NamedFile("9dbbc0ca.txt")),
        )
        assertEquals(
            listOf("1789014134.537779", "1789043986.891699"),
            SlackPoster.historyTxtThreads(listOf(first, second, other), hash),
        )
    }

    @Test
    fun `a reply with the txt uses the parent thread ts`() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val reply = SlackPoster.HistoryMsg(
            ts = "1789015000.000001",
            threadTs = "1789014134.537779",
            files = listOf(SlackPoster.NamedFile("$hash.txt")),
        )
        assertEquals(
            listOf("1789014134.537779"),
            SlackPoster.historyTxtThreads(listOf(reply), hash),
        )
    }

    @Test
    fun `one catalog list matches every missing hash`() {
        val a = "5dc9a2e5732e7789f3edd21debdfa4519c2c16003ba4ac3ccb800e6c2caca186"
        val b = "9dbbc0ca1896d555c9e96772c55780ca9f6722c227647bd76abab0b18a"
        val hist = listOf(
            SlackPoster.HistoryMsg("1.1", files = listOf(SlackPoster.NamedFile("$a.txt"))),
            SlackPoster.HistoryMsg("2.2", files = listOf(SlackPoster.NamedFile("$b.txt"))),
        )
        val files = listOf(
            SlackPoster.NamedFile("$a.png", threadTs = "1.1"),
            SlackPoster.NamedFile("$b.txt", threadTs = "2.2"),
        )
        val hitA = SlackPoster.catalogHit(hist, files, a)
        val hitB = SlackPoster.catalogHit(hist, files, b)
        assertEquals("$a.png", hitA.pngName)
        assertEquals(listOf("1.1"), hitA.threads)
        assertEquals(null, hitB.pngName)
        assertEquals(listOf("2.2"), hitB.threads)
    }

    @Test
    fun `a png in history is not a posted chapter`() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val png = SlackPoster.HistoryMsg(
            "1789015000.000001",
            files = listOf(SlackPoster.NamedFile("$hash.png")),
        )
        assertEquals(emptyList<String>(), SlackPoster.historyTxtThreads(listOf(png), hash))
    }

    @Test
    fun `missing_scope names the read scopes`() {
        val msg = SlackPoster.describe("missing_scope")
        assertTrue(msg.contains("files:read"))
        assertTrue(msg.contains("groups:history"))
        assertTrue(msg.contains("reinstall"))
    }

    @Test
    fun `file alt prefers alt_txt then a real title`() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(
            "A lantern in the rain",
            SlackPoster.fileAlt("A lantern in the rain", "other title", "$hash.png"),
        )
        assertEquals(
            "A lantern in the rain",
            SlackPoster.fileAlt("", "A lantern in the rain", "$hash.png"),
        )
        assertEquals("", SlackPoster.fileAlt("", "", "$hash.png"))
        assertEquals("", SlackPoster.fileAlt("$hash.png", "$hash.png", "$hash.png"))
        assertEquals("", SlackPoster.fileAlt("tedair.gif", "tedair.gif", "tedair.gif"))
        assertEquals("", SlackPoster.fileAlt("", "$hash.png", ""))
    }

    /* files.info example from docs.slack.dev — alt_txt is present
       and equals the filename when there is no Image description. */
    @Test
    fun `files info filename placeholder counts as empty`() {
        val info = JSONObject(
            """{"id":"F0S43PZDF","name":"tedair.gif","title":"tedair.gif",""" +
                """"mimetype":"image/gif","url_private":"https://files.slack.com/tedair.gif",""" +
                """"url_private_download":"https://files.slack.com/tedair.gif",""" +
                """"alt_txt":"tedair.gif"}""",
        )
        assertEquals("", SlackPoster.fileAlt(info))
        assertFalse(SlackPoster.needsFileInfo(info))
    }

    /* files.list example omits alt_txt entirely, even when the Slack
       UI shows Image description. Catalog hydrate used to stop here. */
    @Test
    fun `files list without alt_txt needs files info`() {
        val hash = "d5061ff1cf483c6cab285c9489dd3d8253ef05d6cd6733b4f49918560905f67b"
        val listed = JSONObject(
            """{"id":"F0C11BMN49G","name":"$hash.png","title":"$hash.png",""" +
                """"mimetype":"image/png",""" +
                """"url_private":"https://files.slack.com/files-pri/T/F/$hash.png",""" +
                """"url_private_download":"https://files.slack.com/files-pri/T/F/$hash.png"}""",
        )
        assertEquals("", SlackPoster.fileAlt(listed))
        assertTrue(SlackPoster.needsFileInfo(listed))
    }

    /* ChatGPT's Image description as files.info returns it — the
       field Slack's UI labels Image description. */
    @Test
    fun `files info image description is stored and shown`() {
        val hash = "d5061ff1cf483c6cab285c9489dd3d8253ef05d6cd6733b4f49918560905f67b"
        val desc = "Prince Roland orders his officers to drive their steel " +
            "river gunboat at full speed ahead from its compact command room."
        val info = JSONObject(
            """{"id":"F0C11BMN49G","name":"$hash.png","title":"$hash.png",""" +
                """"mimetype":"image/png","alt_txt":${JSONObject.quote(desc)},""" +
                """"url_private":"https://files.slack.com/files-pri/T/F/$hash.png",""" +
                """"url_private_download":"https://files.slack.com/files-pri/T/F/$hash.png"}""",
        )
        assertEquals(desc, SlackPoster.fileAlt(info))
        assertFalse(SlackPoster.needsFileInfo(info))
    }

    @Test
    fun `file alt reads alt_text when alt_txt is missing`() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val file = JSONObject()
            .put("id", "F123")
            .put("name", "$hash.png")
            .put("title", "$hash.png")
            .put("alt_text", "A lantern in the rain")
        assertEquals("A lantern in the rain", SlackPoster.fileAlt(file))
        assertFalse(SlackPoster.needsFileInfo(file))
    }

    @Test
    fun `an image block alt_text is copied onto the file stub`() {
        val m = JSONObject(
            """{"ts":"1.1","blocks":[{"type":"image","alt_text":"A lantern in the rain",""" +
                """"slack_file":{"id":"F123"}}]}""",
        )
        val files = mutableListOf<JSONObject>()
        SlackPoster.collectMessageFiles(m, files)
        assertEquals(1, files.size)
        assertEquals("A lantern in the rain", SlackPoster.fileAlt(files[0]))
        assertFalse(SlackPoster.needsFileInfo(files[0]))
    }

    @Test
    fun `a denied look is a png miss with a read error`() {
        assertTrue(SlackPoster.lookDenied(null, "missing_scope"))
        assertFalse(SlackPoster.lookDenied(ByteArray(1), "missing_scope"))
        assertFalse(SlackPoster.lookDenied(null, null))
        assertFalse(SlackPoster.lookDenied(null, ""))
    }
}
