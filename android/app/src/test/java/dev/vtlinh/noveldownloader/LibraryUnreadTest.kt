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
    fun `a merged file counts as the chapter it starts at`() {
        assertFalse(LibraryUnread.pastHalfway("Chapter 40-90.txt|3", 100, 100))
        assertTrue(LibraryUnread.pastHalfway("Chapter 70-71 - The Storm.txt|3", 100, 100))
    }

    @Test
    fun `a compressed chapter and a title suffix still count`() {
        assertTrue(LibraryUnread.pastHalfway("Chapter 60.txt.gz|9", 100, 40))
        assertFalse(LibraryUnread.pastHalfway("Chapter 40 - The Storm.txt|1", 100, 100))
    }

    @Test
    fun `the only chapter of a one chapter book is past halfway`() {
        assertTrue(LibraryUnread.pastHalfway("Chapter 1.txt|0", 1, 1))
        assertFalse(LibraryUnread.pastHalfway("Chapter 1.txt|0", 2, 2))
        assertTrue(LibraryUnread.pastHalfway("Chapter 2.txt|0", 2, 2))
    }

    @Test
    fun `a marker on the last chapter stays on`() {
        assertTrue(LibraryUnread.pastHalfway("Chapter 100.txt|12", 100, 100))
    }
}
