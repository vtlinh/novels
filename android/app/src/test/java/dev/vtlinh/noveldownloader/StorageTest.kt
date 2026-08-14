package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* What the Settings storage line reports: only novel files, both copies
   when a compress pass hasn't finished, translations included, and nothing
   from a shared folder that isn't a chapter. */
class StorageTest {

    private fun f(name: String, size: Long, ref: String = "id:$name") =
        Folder.Item(name, ref, isDir = false, size = size)

    private fun dir(name: String, ref: String = "dir:$name") =
        Folder.Item(name, ref, isDir = true)

    private fun total(
        root: List<Folder.Item>,
        children: Map<String, List<Folder.Item>> = emptyMap(),
    ) = Storage.total(root) { children[it] ?: emptyList() }

    @Test
    fun `an empty folder is zero`() {
        val t = total(emptyList())
        assertEquals(0L, t.bytes)
        assertEquals(0, t.files)
        assertEquals("0 B", Storage.label(t))
    }

    /* The download tree can hold other things. Files sitting at the root
       are not a novel, and a directory with no chapters is not one either. */
    @Test
    fun `files that are not chapters are ignored`() {
        val t = total(
            listOf(
                f("movie.mp4", 9_000_000_000L),
                f("Chapter 1.txt", 500L),
                dir("Photos"),
                dir("A Novel"),
            ),
            mapOf(
                "dir:Photos" to listOf(f("IMG_001.jpg", 4_000_000L)),
                "dir:A Novel" to listOf(f("notes.txt", 80_000L), f("cover.jpg", 200_000L)),
            ),
        )
        assertEquals(0L, t.bytes)
        assertEquals(0, t.files)
        assertEquals("0 B", Storage.label(t))
    }

    @Test
    fun `loose and gzipped chapters in a novel folder are both counted`() {
        val t = total(
            listOf(dir("Book")),
            mapOf(
                "dir:Book" to listOf(
                    f("Chapter 1.txt", 1000L),
                    f("Chapter 2.txt.gz", 400L),
                    f("Chapter 1.txt.gz", 300L),
                ),
            ),
        )
        assertEquals(1700L, t.bytes)
        assertEquals(3, t.files)
        assertEquals("1.7 KB", Storage.label(t))
    }

    @Test
    fun `translations and in-progress parts count`() {
        val t = total(
            listOf(dir("Book")),
            mapOf(
                "dir:Book" to listOf(
                    f("Chapter 1.txt", 1000L),
                    f("part~Chapter 2.txt", 200L),
                    dir("translated", "dir:tr"),
                    dir("other", "dir:other"),
                ),
                "dir:tr" to listOf(
                    f("Chapter 1.txt.gz", 500L),
                    f("notes.txt", 9_000L),
                ),
                "dir:other" to listOf(f("Chapter 99.txt", 8_000_000L)),
            ),
        )
        assertEquals(1700L, t.bytes)
        assertEquals(3, t.files)
    }

    @Test
    fun `a size the provider did not report is not treated as zero`() {
        val t = total(
            listOf(dir("Book")),
            mapOf("dir:Book" to listOf(f("Chapter 1.txt", -1L), f("Chapter 2.txt", 500L))),
        )
        assertEquals(500L, t.bytes)
        assertEquals(2, t.files)
        assertEquals(1, t.unknown)
        assertEquals("500 B", Storage.label(t))
    }

    @Test
    fun `every unreported size is Unknown rather than zero`() {
        val t = total(
            listOf(dir("Book")),
            mapOf("dir:Book" to listOf(f("Chapter 1.txt", -1L))),
        )
        assertEquals(0L, t.bytes)
        assertEquals(1, t.files)
        assertEquals(1, t.unknown)
        assertEquals("Unknown", Storage.label(t))
    }

    @Test
    fun `two novels add together`() {
        val t = total(
            listOf(dir("A"), dir("B")),
            mapOf(
                "dir:A" to listOf(f("Chapter 1.txt", 100L)),
                "dir:B" to listOf(f("Chapter 1.txt.gz", 250L)),
            ),
        )
        assertEquals(350L, t.bytes)
        assertEquals(2, t.files)
    }

    @Test
    fun `a directory's reported size is not counted`() {
        val t = Storage.of(
            listOf(
                Folder.Item("translated", "tr", isDir = true, size = 9_000_000L),
                f("Chapter 1.txt", 100L),
            ),
        )
        assertEquals(100L, t.bytes)
        assertEquals(1, t.files)
    }

    @Test
    fun `legacy titled names still count`() {
        assertTrue(Storage.isCounted("Chapter 70 - Hoan chinh van.txt"))
        assertTrue(Storage.isCounted("Chapter 70 - Hoan chinh van.txt.gz"))
        assertFalse(Storage.isCounted("movie.mp4.part"))
        assertFalse(Storage.isCounted("notes.txt"))
    }

    @Test
    fun `format uses 1024-based units and drops a trailing tenth`() {
        assertEquals("0 B", Storage.format(0))
        assertEquals("512 B", Storage.format(512))
        assertEquals("1023 B", Storage.format(1023))
        assertEquals("1 KB", Storage.format(1024))
        assertEquals("1.5 KB", Storage.format(1536))
        assertEquals("1 MB", Storage.format(1024L * 1024))
        assertEquals("1.5 GB", Storage.format((1536L * 1024 * 1024)))
    }
}
