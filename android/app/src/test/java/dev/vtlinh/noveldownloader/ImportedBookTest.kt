package dev.vtlinh.noveldownloader

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* A file from the picker, split the way the reader will store it.
   Pictures stay on the chapter they were in, and one chapter can have
   more than one. Built from the shapes a real EPUB and a real PDF use —
   a cover, a shared logo, a short part page, two pictures in one chapter —
   not from a page of this app's own HTML. */
class ImportedBookTest {

    @Test
    fun `an epub keeps each picture on the chapter that showed it`() {
        val cover = png(80)
        val logo = jpeg(40)
        val door = jpeg(50)
        val key = jpeg(60)
        val book = ImportedBook.read(
            "trilogy.epub",
            epub(
                listOf(
                    "cover.html" to html(
                        "<img src=\"images/cover.png\" alt=\"\">",
                    ),
                    "title.html" to html(
                        "<p>The Trilogy</p><img src=\"images/logo.jpg\" alt=\"publisher logo\">",
                    ),
                    "part.html" to html("<h1>PART I</h1>"),
                    "c1.html" to html(
                        "<h1>1</h1><p>$PROSE</p>" +
                            "<img src=\"images/door.jpg\" alt=\"A door\">" +
                            "<img src=\"images/key.jpg\" alt=\"A key\">",
                    ),
                    "c2.html" to html("<h1>2</h1><p>$PROSE</p>"),
                    "c3.html" to html("<h1>3</h1><p>$PROSE</p>"),
                ),
                mapOf(
                    "images/cover.png" to cover,
                    "images/logo.jpg" to logo,
                    "images/door.jpg" to door,
                    "images/key.jpg" to key,
                ),
            ),
        )
        assertEquals("The Trilogy", book.title)
        assertEquals("Ada Lovelace", book.author)
        assertFalse(book.byPage)
        assertEquals(6, book.chapters.size)
        assertEquals("Cover", book.chapters[0].title)
        assertEquals(listOf(cover.size), book.chapters[0].images.map { it.bytes.size })
        assertEquals(listOf("publisher logo"), book.chapters[1].images.map { it.alt })
        assertEquals(logo.size, book.chapters[1].images[0].bytes.size)
        assertEquals("PART I", book.chapters[2].title)
        assertTrue(book.chapters[2].images.isEmpty())
        assertEquals(listOf("A door", "A key"), book.chapters[3].images.map { it.alt })
        assertEquals(listOf(door.size, key.size), book.chapters[3].images.map { it.bytes.size })
        assertTrue(book.chapters[4].images.isEmpty())
        assertTrue(ChapterName.isStored(ImportedBook.chapterFileName(0, book.chapters[0].title)))
        assertTrue(ChapterName.isStored(ImportedBook.chapterFileName(3, book.chapters[3].title)))
    }

    @Test
    fun `a pdf page keeps every picture on that page`() {
        val a = jpeg(48)
        val b = jpeg(64)
        val text = "($PROSE) Tj"
        val book = ImportedBook.read("notes.pdf", pdfPage(text, listOf(a, b)))
        assertEquals(1, book.chapters.size)
        assertTrue(book.byPage)
        assertEquals(listOf(a.size, b.size), book.chapters[0].images.map { it.bytes.size })
    }

    private fun html(body: String) = """
        <html><head><title>The Trilogy</title></head><body>$body</body></html>
    """.trimIndent().toByteArray()

    private fun epub(docs: List<Pair<String, ByteArray>>, images: Map<String, ByteArray>): ByteArray {
        val manifest = StringBuilder()
        val spine = StringBuilder()
        docs.forEachIndexed { i, (name, _) ->
            manifest.append("""<item id="d$i" href="$name" media-type="application/xhtml+xml"/>""")
            spine.append("""<itemref idref="d$i"/>""")
        }
        images.keys.forEachIndexed { i, name ->
            val media = if (name.endsWith(".png")) "image/png" else "image/jpeg"
            manifest.append("""<item id="i$i" href="$name" media-type="$media"/>""")
        }
        val opf = """
            <package><metadata>
            <dc:title>The Trilogy</dc:title>
            <dc:creator>Ada Lovelace</dc:creator>
            </metadata><manifest>$manifest</manifest><spine>$spine</spine></package>
        """.trimIndent()
        val container = """
            <container><rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
            </rootfiles></container>
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, bytes: ByteArray) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray())
            put("META-INF/container.xml", container.toByteArray())
            put("OEBPS/content.opf", opf.toByteArray())
            for ((name, bytes) in docs) put("OEBPS/$name", bytes)
            for ((name, bytes) in images) put("OEBPS/$name", bytes)
        }
        return out.toByteArray()
    }

    /* One page, two JPEG pictures named from its resource dictionary. */
    private fun pdfPage(text: String, images: List<ByteArray>): ByteArray {
        val stream = "BT $text ET".toByteArray()
        val objs = ArrayList<String>()
        val xobj = StringBuilder()
        images.forEachIndexed { i, bytes ->
            val id = 5 + i
            xobj.append("/Im$i $id 0 R ")
            objs.add(imageObj(id, bytes))
        }
        val content = "4 0 obj\n<< /Length ${stream.size} >>\nstream\n${String(stream)}\nendstream\nendobj\n"
        val page = "3 0 obj\n<< /Type /Page /Parent 2 0 R /Contents 4 0 R " +
            "/Resources << /XObject << $xobj >> >> >>\nendobj\n"
        val pages = "2 0 obj\n<< /Type /Pages /Count 1 /Kids [3 0 R] >>\nendobj\n"
        val catalog = "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
        val body = "%PDF-1.4\n$catalog$pages$page$content${objs.joinToString("")}" +
            "trailer\n<< /Root 1 0 R >>\n%%EOF\n"
        return body.toByteArray(Charsets.ISO_8859_1)
    }

    private fun imageObj(id: Int, jpeg: ByteArray): String {
        val head = "$id 0 obj\n<< /Type /XObject /Subtype /Image /Width 1 /Height 1 " +
            "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n"
        val tail = "\nendstream\nendobj\n"
        return head + String(jpeg, Charsets.ISO_8859_1) + tail
    }

    private fun jpeg(n: Int): ByteArray {
        val b = ByteArray(n)
        b[0] = 0xFF.toByte(); b[1] = 0xD8.toByte(); b[2] = 0xFF.toByte(); b[n - 1] = 0xD9.toByte()
        return b
    }

    private fun png(n: Int): ByteArray {
        val b = ByteArray(n)
        b[0] = 0x89.toByte(); b[1] = 'P'.code.toByte(); b[2] = 'N'.code.toByte(); b[3] = 'G'.code.toByte()
        return b
    }

    private companion object {
        val PROSE = "Rain hit the roof and the road and the windows, and nobody in the house said a word about it. ".repeat(40)
    }
}
