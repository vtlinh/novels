package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentsTest {

    private fun f(name: String, ref: String = "id:$name", size: Long = 100L) =
        Folder.Item(name, ref, isDir = false, size = size)

    @Test
    fun `the default title carries the date the caller measured`() {
        assertEquals("Document 2026-08-20", Documents.defaultTitle("2026-08-20"))
    }

    @Test
    fun `an empty title becomes Untitled`() {
        assertEquals("Untitled", Documents.displayTitle(""))
        assertEquals("Untitled", Documents.displayTitle("   "))
    }

    @Test
    fun `a typed title is kept`() {
        assertEquals("My notes", Documents.displayTitle("My notes"))
        assertEquals("My notes", Documents.displayTitle("  My notes  "))
    }

    /* sanitize can reduce a title to nothing (all CJK, or "..."), and an
       empty filename is one the provider refuses. */
    @Test
    fun `a title that sanitises to nothing becomes Untitled`() {
        assertEquals("Untitled", Documents.stem("..."))
        assertEquals("Untitled", Documents.stem("一二三"))
        assertEquals("Untitled.txt", Documents.plainName(Documents.stem("")))
    }

    @Test
    fun `a taken stem is numbered rather than overwriting`() {
        val taken = setOf("Untitled", "Untitled (2)")
        assertEquals("Untitled (3)", Documents.uniqueStem("Untitled", taken))
        assertEquals("Notes", Documents.uniqueStem("Notes", taken))
    }

    @Test
    fun `the stem currently being renamed is not treated as taken`() {
        val taken = setOf("Notes (2)")
        assertEquals("Notes", Documents.uniqueStem("Notes", taken))
    }

    @Test
    fun `a loose document wins over its compressed twin`() {
        val items = Documents.resolve(
            listOf(f("Notes.txt", "loose"), f("Notes.txt.gz", "archive")),
        )
        assertEquals(1, items.size)
        assertEquals("Notes", items[0].title)
        assertEquals("Notes.txt", items[0].plainName)
        assertEquals("loose", items[0].ref)
        assertFalse(items[0].compressed)
    }

    @Test
    fun `a compressed document with no loose copy is still listed`() {
        val items = Documents.resolve(listOf(f("Notes.txt.gz", "archive")))
        assertEquals(1, items.size)
        assertTrue(items[0].compressed)
        assertTrue(Zips.isGzRef(items[0].ref))
        assertEquals("Notes.txt", items[0].plainName)
    }

    /* The stub an interrupted uncompress leaves behind must not hide the
       .gz that still holds the only copy. */
    @Test
    fun `an empty loose file does not beat a compressed copy`() {
        val items = Documents.resolve(
            listOf(f("Notes.txt", "stub", size = 0L), f("Notes.txt.gz", "archive")),
        )
        assertEquals(1, items.size)
        assertTrue(items[0].compressed)
        assertTrue(Zips.isGzRef(items[0].ref))
    }

    @Test
    fun `half-written parts and foreign files are not documents`() {
        val items = Documents.resolve(
            listOf(
                f("part~Notes.txt"),
                f("Notes.txt.gz.part"),
                f("cover.jpg"),
                f("Chapter 1.txt"),
            ),
        )
        assertEquals(listOf("Chapter 1"), items.map { it.title })
    }

    @Test
    fun `a directory named documents is reserved in any casing`() {
        assertTrue(Documents.isReservedDir("documents"))
        assertTrue(Documents.isReservedDir("Documents"))
        assertTrue(Documents.isReservedDir("DOCUMENTS"))
        assertFalse(Documents.isReservedDir("document"))
        assertFalse(Documents.isReservedDir("my documents"))
        /* CompressWalk inlines the same name so it can be tested without
           Android; a rename of DIR must fail here until that copy matches. */
        assertEquals("documents", Documents.DIR)
        assertFalse(CompressWalk.includeNovelDir(Documents.DIR, setOf(Documents.DIR)))
    }

    /* A novel whose title sanitises to our folder would otherwise write its
       chapters into the documents directory — and every pasted file would
       then look like that novel's. The novel steps aside. */
    @Test
    fun `a novel is not given the documents folder name`() {
        val name = Extractor.folderName("documents", "my-book")
        assertNotEquals("documents", name.lowercase())
        assertFalse(Documents.isReservedDir(name))
    }

    @Test
    fun `stemOf ignores parts and empty names`() {
        assertEquals("Notes", Documents.stemOf("Notes.txt"))
        assertEquals("Notes", Documents.stemOf("Notes.txt.gz"))
        assertNull(Documents.stemOf("part~Notes.txt"))
        assertNull(Documents.stemOf(".txt"))
        assertNull(Documents.stemOf("cover.jpg"))
        assertTrue(Documents.isPlain("Notes.txt"))
        assertFalse(Documents.isPlain("Notes.txt.gz"))
        assertFalse(Documents.isPlain("part~Notes.txt"))
    }

    /* The documents compress pass has to sweep OUR half-written files.
       The default matcher must still refuse them — a novel folder can sit
       in a shared tree, and part~notes.txt there is the user's. */
    @Test
    fun `document parts are only ours inside the documents matcher`() {
        assertTrue(Zips.isPartName("part~Notes.txt", Documents::isPlain))
        assertFalse(Zips.isPartName("part~Notes.txt"))
        assertFalse(Zips.isPartName("movie.mp4.part", Documents::isPlain))
    }
}
