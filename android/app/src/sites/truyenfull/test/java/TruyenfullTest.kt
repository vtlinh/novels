package dev.vtlinh.noveldownloader.sites

import dev.vtlinh.noveldownloader.SiteContract
import dev.vtlinh.noveldownloader.Sites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TruyenfullTest : SiteContract() {
    override val site = Sites.all.first { it.name == "truyenfull" }

    /* Vietnamese source, so a downloaded novel is worth translating. */
    @Test
    fun `it is not an English site`() {
        assertFalse(site.english)
        assertEquals("Chương", site.headingWord)
    }

    @Test
    fun `both hosts are recognised`() {
        assertTrue(site.matches("https://truyenfull.today/tu-tien/"))
        assertTrue(site.matches("https://truyenfull.live/tu-tien/"))
        assertEquals("tu-tien", site.normalize("https://truyenfull.live/tu-tien/chuong-5/").second)
    }
}
