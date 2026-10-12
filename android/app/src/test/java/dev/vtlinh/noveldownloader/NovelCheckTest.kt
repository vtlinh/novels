package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Test

/* Which addresses a status check may try for a novel. Recording one that
   belongs to another site re-downloads the whole novel from it. */
class NovelCheckTest {

    @Test
    fun `a novel from a site with one host is only checked there`() {
        val url = "https://truyenfullmoi.com/tien-nghich.1666/"
        assertEquals(listOf(url), NovelCheck.candidates(url, "tien-nghich"))
    }

    @Test
    fun `a truyenfull novel is checked on both truyenfull hosts`() {
        assertEquals(
            listOf("https://truyenfull.today/tien-nghich/", "https://truyenfull.live/tien-nghich/"),
            NovelCheck.candidates("https://truyenfull.today/tien-nghich/", "tien-nghich"),
        )
    }

    @Test
    fun `a novelfull novel is not checked on truyenfull`() {
        assertEquals(
            listOf("https://novelfull.net/martial-peak.html", "https://novelfull.com/martial-peak.html"),
            NovelCheck.candidates("https://novelfull.net/martial-peak.html", "martial-peak"),
        )
    }

    @Test
    fun `a novel with no recorded address is tried everywhere`() {
        assertEquals(
            listOf(
                "https://truyenfull.today/tien-nghich/",
                "https://novelfull.com/tien-nghich.html",
                "https://truyenfull.live/tien-nghich/",
            ),
            NovelCheck.candidates("", "tien-nghich"),
        )
    }
}
