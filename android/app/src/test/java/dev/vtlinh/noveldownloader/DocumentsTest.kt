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
        assertTrue(Documents.isReservedDir(Documents.DIR))
        assertFalse(Documents.isReservedDir("document"))
        assertFalse(Documents.isReservedDir("my documents"))
    }

    /* THE DEFECT. isReservedDir is also true of Android's Documents folder,
       so the compress pass and the writer treated that directory as the
       pasted-text store. A shared download tree that already had one —
       notes, schoolwork, exports — had every .txt gzipped and the original
       deleted. The store is only the folder we create, plus an exact
       "documents" leftover from the first builds. */
    @Test
    fun `Android Documents is not the pasted-text store`() {
        assertFalse(Documents.isStoreDir("Documents"))
        assertFalse(Documents.isStoreDir("DOCUMENTS"))
        assertFalse(Documents.isStoreDir("document"))
        assertTrue(Documents.isStoreDir("documents"))
        assertTrue(Documents.isStoreDir(Documents.DIR))
        assertTrue(Documents.isStoreDir("Novel-Documents"))
        assertNotEquals(
            "the store name is not Android's Documents folder",
            "documents",
            Documents.DIR.lowercase(),
        )
    }

    /* A novel whose title sanitises to our folder would otherwise write its
       chapters into the documents directory — and every pasted file would
       then look like that novel's. The novel steps aside. Same for Android's
       Documents folder in a shared tree. */
    @Test
    fun `a novel is not given the documents folder name`() {
        val name = Extractor.folderName("documents", "my-book")
        assertNotEquals("documents", name.lowercase())
        assertFalse(Documents.isReservedDir(name))
        val androidDocs = Extractor.folderName("Documents", "my-book")
        assertFalse(Documents.isReservedDir(androidDocs))
        val store = Extractor.folderName(Documents.DIR, "my-book")
        assertFalse(Documents.isReservedDir(store))
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
