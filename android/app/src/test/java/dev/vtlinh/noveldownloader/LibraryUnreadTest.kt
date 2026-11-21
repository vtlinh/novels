package dev.vtlinh.noveldownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryUnreadTest {

    @Test
    fun `a marker past halfway is on`() {
        assertTrue(LibraryUnread.pastHalfway("Chapter 51.txt|4", 100, 100))
    }

    @Test
    fun `halfway exactly is off`() {
        assertFalse(LibraryUnread.pastHalfway("Chapter 50.txt|0", 100, 80))
    }

    @Test
    fun `an odd length uses the chapter past the middle`() {
        assertFalse(LibraryUnread.pastHalfway("Chapter 50.txt|1", 101, 101))
        assertTrue(LibraryUnread.pastHalfway("Chapter 51.txt|1", 101, 101))
    }

    @Test
    fun `the site total wins over how many chapters are on disk`() {
        assertFalse(LibraryUnread.pastHalfway("Chapter 60.txt|2", 200, 80))
        assertTrue(LibraryUnread.pastHalfway("Chapter 101.txt|2", 200, 80))
    }

    @Test
    fun `chapters on disk are the total when the site has never been asked`() {
        assertFalse(LibraryUnread.pastHalfway("Chapter 40.txt|1", -1, 100))
        assertTrue(LibraryUnread.pastHalfway("Chapter 51.txt|1", -1, 100))
        assertTrue(LibraryUnread.pastHalfway("Chapter 8.txt|0", 0, 10))
    }

    @Test
    fun `no marker or no total is off`() {
        assertFalse(LibraryUnread.pastHalfway(null, 100, 100))
        assertFalse(LibraryUnread.pastHalfway("", 100, 100))
        assertFalse(LibraryUnread.pastHalfway("|4", 100, 100))
        assertFalse(LibraryUnread.pastHalfway("Chapter 80.txt|1", -1, 0))
        assertFalse(LibraryUnread.pastHalfway("Chapter 80.txt|1", 0, 0))
    }

    @Test
    fun `a name that is not a chapter is off`() {
        assertFalse(LibraryUnread.pastHalfway("notes.txt|1", 10, 10))
    }

    @Test
    fun `a merged file counts as the chapter it starts at for halfway`() {
        assertFalse(LibraryUnread.pastHalfway("Chapter 40-90.txt|3", 100, 100))
        assertTrue(LibraryUnread.pastHalfway("Chapter 70-71 - The Storm.txt|3", 100, 100))
    }

    @Test
    fun `a compressed chapter and a title suffix still count`() {
        assertTrue(LibraryUnread.pastHalfway("Chapter 60.txt.gz|9", 100, 40))
        assertFalse(LibraryUnread.pastHalfway("Chapter 40 - The Storm.txt|1", 100, 100))
    }

    @Test
    fun `the first recorded total is not new chapters`() {
        assertFalse(LibraryUnread.grew(-1, 100))
        assertFalse(LibraryUnread.grew(0, 80))
    }

    @Test
    fun `a higher total is new chapters and a same or lower one is not`() {
        assertTrue(LibraryUnread.grew(80, 100))
        assertFalse(LibraryUnread.grew(100, 100))
        assertFalse(LibraryUnread.grew(100, 90))
    }

    @Test
    fun `the dot is on when new chapters arrived after the last read`() {
        assertTrue(show("Chapter 80.txt|2", site = 100, addedAt = 50, readAt = 10))
    }

    @Test
    fun `a download after the read counts as chapters arriving`() {
        assertTrue(show("Chapter 80.txt|2", site = 100, addedAt = 0, downloadedAt = 50, readAt = 10))
        assertFalse(show("Chapter 80.txt|2", site = 100, addedAt = 0, downloadedAt = 10, readAt = 50))
    }

    @Test
    fun `the dot stays off on the last chapter`() {
        assertFalse(show("Chapter 100.txt|2", site = 100, addedAt = 50, readAt = 10))
        assertFalse(show("Chapter 99-100.txt|1", site = 100, addedAt = 50, readAt = 10))
        assertFalse(show("Chapter 100.txt|2", site = 100, addedAt = 0, downloadedAt = 50, readAt = 10))
    }

    @Test
    fun `the dot stays off when nothing arrived after the last read`() {
        assertFalse(show("Chapter 80.txt|2", site = 100, addedAt = 10, readAt = 50))
        assertFalse(show("Chapter 80.txt|2", site = 100, addedAt = 50, readAt = 50))
        assertFalse(show("Chapter 80.txt|2", site = 100, addedAt = 0, readAt = 10))
    }

    @Test
    fun `a later listen wins over an earlier play`() {
        assertFalse(
            LibraryUnread.show(
                "Chapter 80.txt|2", 100, 100,
                lastReadAt = 10, ttsPosAt = 80, lastDownloadAt = 0, chaptersAddedAt = 50,
            ),
        )
        assertTrue(
            LibraryUnread.show(
                "Chapter 80.txt|2", 100, 100,
                lastReadAt = 80, ttsPosAt = 10, lastDownloadAt = 0, chaptersAddedAt = 90,
            ),
        )
    }

    @Test
    fun `a novel that was never read stays off`() {
        assertFalse(show("Chapter 80.txt|2", site = 100, addedAt = 50, readAt = 0))
    }

    @Test
    fun `new chapters do not mark a novel still in its first half`() {
        assertFalse(show("Chapter 40.txt|1", site = 100, addedAt = 50, readAt = 10))
    }

    private fun show(
        tts: String,
        site: Int,
        addedAt: Long,
        readAt: Long,
        onDisk: Int = site,
        downloadedAt: Long = 0,
    ): Boolean = LibraryUnread.show(
        tts, site, onDisk,
        lastReadAt = readAt, ttsPosAt = 0L,
        lastDownloadAt = downloadedAt, chaptersAddedAt = addedAt,
    )
}
