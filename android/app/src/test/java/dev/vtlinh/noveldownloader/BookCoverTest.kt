package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/* Cover lookup for a book added from a file. The picture inside the file
   is used when it has one. An ISBN, then the title and author, are what a
   search is allowed to ask with — and a hit for a different book is not
   a cover. */
class BookCoverTest {

    private val title = "The Left Hand of Darkness"
    private val author = "Ursula K. Le Guin"
    private val isbn = "9780441478125"

    @Test
    fun `an imported novel is looked up and a site novel is not`() {
        assertTrue(BookCover.needsLookup("import:epub", "import-dune", "Dune"))
        assertTrue(BookCover.needsLookup("", "import-dune", "Dune"))
        assertFalse(BookCover.needsLookup("https://novelfull.com/dune.html", "dune", "Dune"))
        assertFalse(BookCover.needsLookup("import:pdf", "import-scan", "scan"))
        assertFalse(BookCover.needsLookup("import:pdf", "import-x", ""))
    }

    @Test
    fun `isbn check digits accept a real isbn and reject a neighbor`() {
        assertEquals(isbn, BookCover.isbnOf("978-0-441-47812-5"))
        assertEquals(isbn, BookCover.isbnOf("urn:isbn:9780441478125"))
        assertEquals("0306406152", BookCover.isbnOf("ISBN 0-306-40615-2"))
        assertNull(BookCover.isbnOf("9780441478126"))
        assertNull(BookCover.isbnOf("not a number"))
        assertEquals(isbn, BookCover.isbnOf("ISBN-10 0306406152 and 9780441478125"))
    }

    @Test
    fun `queries ask for the isbn edition before the title`() {
        val qs = BookCover.queries(BookCover.Identity(title, author, isbn))
        assertEquals(BookCover.QueryKind.IMAGE, qs.first().kind)
        assertTrue(qs.first().url.contains(isbn))
        assertTrue(qs.first().url.contains("default=false"))
        val search = qs.first { it.kind == BookCover.QueryKind.OPEN_LIBRARY }
        assertTrue(search.url.contains("The+Left+Hand+of+Darkness"))
        assertTrue(search.url.contains("Le+Guin"))
        assertTrue(BookCover.queries(BookCover.Identity("It", "", null)).isEmpty())
        assertTrue(BookCover.queries(BookCover.Identity("It", "Stephen King", null)).isNotEmpty())
    }

    @Test
    fun `a different book is not a cover match`() {
        val json = """
            {"docs":[
              {"title":"Dune Messiah","author_name":["Frank Herbert"],"cover_i":1},
              {"title":"Dune","author_name":["Frank Herbert"],"cover_i":99},
              {"title":"Dune","author_name":["Someone Else"],"cover_i":7}
            ]}
        """.trimIndent()
        val id = BookCover.Identity("Dune", "Frank Herbert", null)
        assertEquals(
            "https://covers.openlibrary.org/b/id/99-L.jpg?default=false",
            BookCover.openLibraryCover(json, id),
        )
        val subtitle = """{"docs":[{"title":"Dune (Dune Chronicles, #1)","author_name":["Frank Herbert"],"cover_i":5}]}"""
        assertTrue(BookCover.openLibraryCover(subtitle, id)!!.contains("/b/id/5-"))
        val messiah = """{"docs":[{"title":"Dune Messiah","author_name":["Frank Herbert"],"cover_i":1}]}"""
        assertNull(BookCover.openLibraryCover(messiah, id))
    }

    @Test
    fun `google thumbnails are asked for at a larger size`() {
        val json = """
            {"items":[{"volumeInfo":{
              "title":"The Left Hand of Darkness",
              "authors":["Ursula K. Le Guin"],
              "imageLinks":{"thumbnail":"http://books.google.com/books/content?id=abc&zoom=1&edge=curl&source=gbs_api"}
            }}]}
        """.trimIndent()
        val urls = BookCover.googleCovers(json, BookCover.Identity(title, author, null))
        assertEquals(
            "https://books.google.com/books/content?id=abc&zoom=3&edge=none&source=gbs_api",
            urls[0],
        )
        assertTrue(urls[1].contains("zoom=1"))
        val other = """
            {"items":[{"volumeInfo":{
              "title":"The Dispossessed",
              "authors":["Ursula K. Le Guin"],
              "imageLinks":{"thumbnail":"https://example.com/nope.jpg"}
            }}]}
        """.trimIndent()
        assertTrue(BookCover.googleCovers(other, BookCover.Identity(title, author, null)).isEmpty())
    }

    @Test
    fun `the picture in the file is used and a blank one is not`() {
        val cover = png(48, 64)
        val calls = ArrayList<String>()
        val got = BookCover.resolve(
            BookCover.Identity(title, author, isbn),
            cover,
        ) { calls.add(it); null }
        assertTrue(cover.contentEquals(got))
        assertTrue(calls.isEmpty())

        val tiny = png(1, 1)
        val big = png(50, 70)
        val imageUrl = "https://covers.openlibrary.org/b/isbn/$isbn-L.jpg?default=false"
        val fromNet = BookCover.resolve(
            BookCover.Identity(title, author, isbn),
            tiny,
        ) { url -> if (url == imageUrl) big else null }
        assertTrue(big.contentEquals(fromNet))
        assertFalse(BookCover.isCoverImage(tiny))
        assertFalse(BookCover.isCoverImage("<html>nope</html>".toByteArray()))
    }

    @Test
    fun `an epub's title author isbn and cover come out of the package`() {
        val cover = png(60, 90)
        val opf = """
            <?xml version="1.0"?>
            <package xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
              <metadata>
                <dc:title>The Left Hand of Darkness</dc:title>
                <dc:creator opf:role="aut">Ursula K. Le Guin</dc:creator>
                <dc:identifier opf:scheme="ISBN">978-0-441-47812-5</dc:identifier>
                <meta name="cover" content="cover-img"/>
              </metadata>
              <manifest>
                <item id="art" href="images/chapter.png" media-type="image/png"/>
                <item id="cover-img" href="images/cover.png" media-type="image/png"/>
              </manifest>
            </package>
        """.trimIndent()
        val bytes = epub(opf, mapOf(
            "OEBPS/images/chapter.png" to png(40, 40),
            "OEBPS/images/cover.png" to cover,
        ))
        val id = BookCover.identity(bytes, "left-hand.epub")
        assertEquals(title, id?.title)
        assertEquals(author, id?.author)
        assertEquals(isbn, id?.isbn)
        assertTrue(cover.contentEquals(BookCover.embedded(bytes, "left-hand.epub")))
    }

    @Test
    fun `an epub 3 cover-image property is the cover`() {
        val cover = png(48, 72)
        val opf = """
            <package xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata><dc:title>Dune</dc:title><dc:creator>Frank Herbert</dc:creator></metadata>
              <manifest>
                <item id="c" href="cover.jpg" media-type="image/png" properties="cover-image"/>
              </manifest>
            </package>
        """.trimIndent()
        val bytes = epub(opf, mapOf("OEBPS/cover.jpg" to cover))
        assertTrue(cover.contentEquals(BookCover.embedded(bytes, "dune.epub")))
        assertEquals("Dune", BookCover.identity(bytes, "dune.epub")?.title)
        assertEquals("Frank Herbert", BookCover.identity(bytes, "dune.epub")?.author)
    }

    /* A retail EPUB 2 package: the declared cover is not the first image
       whose name contains "cover", the cover page is HTML, and the ISBN is
       the identifier's text. The scheme attribute is the same digits. */
    @Test
    fun `the declared cover is used when an earlier image is also named cover`() {
        val earlier = png(40, 40)
        val declared = png(80, 120)
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="ISBN" version="2.0">
              <opf:metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                <dc:title>The Left Hand of Darkness</dc:title>
                <dc:creator>Ursula K. Le Guin</dc:creator>
                <dc:identifier id="ISBN" opf:scheme="9780441478125">9780441478125</dc:identifier>
                <opf:meta name="cover" content="my_cover_image" />
              </opf:metadata>
              <manifest>
                <item id="coverpage" href="cover.html" media-type="application/xhtml+xml" />
                <item id="front" href="images/Outside_Front_Cover.png" media-type="image/png" />
                <item id="my_cover_image" href="images/declared.jpg" media-type="image/jpeg" />
              </manifest>
              <guide>
                <reference type="cover" title="Cover" href="cover.html" />
              </guide>
            </package>
        """.trimIndent()
        val page = """<html><body><img src="images/Outside_Front_Cover.png"/></body></html>"""
        val bytes = epub("\uFEFF$opf", mapOf(
            "OEBPS/cover.html" to page.toByteArray(),
            "OEBPS/images/Outside_Front_Cover.png" to earlier,
            "OEBPS/images/declared.jpg" to declared,
        ))
        val id = BookCover.identity(bytes, "trilogy.epub")
        assertEquals(title, id?.title)
        assertEquals(author, id?.author)
        assertEquals(isbn, id?.isbn)
        assertTrue(declared.contentEquals(BookCover.embedded(bytes, "trilogy.epub")))
    }

    @Test
    fun `pdf metadata is read from the info dictionary and xmp wins when both exist`() {
        val info = "%PDF-1.4\n1 0 obj\n<< /Title (The Left Hand of Darkness) /Author (Ursula K. Le Guin) /ISBN (978-0-441-47812-5) >>\nendobj\n"
        val id = BookCover.identity(info.toByteArray(), "book.pdf")
        assertEquals(title, id?.title)
        assertEquals(author, id?.author)
        assertEquals(isbn, id?.isbn)

        val escaped = "%PDF-1.4\n<< /Title (Pride \\(and\\) Prejudice) /Author (Jane Austen) >>\n"
        assertEquals("Pride (and) Prejudice", BookCover.identity(escaped.toByteArray(), "a.pdf")?.title)

        val xmp = """
            %PDF-1.4
            << /Title (Wrong Title) /Author (Wrong Author) >>
            <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <rdf:Description>
               <dc:title><rdf:Alt><rdf:li xml:lang="x-default">The Left Hand of Darkness</rdf:li></rdf:Alt></dc:title>
               <dc:creator><rdf:Seq><rdf:li>Ursula K. Le Guin</rdf:li></rdf:Seq></dc:creator>
               <dc:identifier>urn:isbn:9780441478125</dc:identifier>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent()
        val fromXmp = BookCover.identity(xmp.toByteArray(), "x.pdf")
        assertEquals(title, fromXmp?.title)
        assertEquals(author, fromXmp?.author)
        assertEquals(isbn, fromXmp?.isbn)

        val hex = "%PDF-1.4\n<< /Title <FEFF00440075006E0065> /Author (Frank Herbert) >>\n"
        assertEquals("Dune", BookCover.identity(hex.toByteArray(), "dune.pdf")?.title)
        assertEquals(title, BookCover.identity("%PDF-1.4\n".toByteArray(), "The Left Hand of Darkness.pdf")?.title)
        assertNull(BookCover.identity("%PDF-1.4\n".toByteArray(), "scan.pdf"))
    }

    @Test
    fun `a kindle file yields its exth title author isbn and cover`() {
        val cover = png(80, 120)
        val bytes = mobi(
            pdb = "Short_Name",
            fullName = "Full Name Only",
            title503 = title,
            author = author,
            isbn = isbn,
            cover = cover,
        )
        val id = BookCover.identity(bytes, "left-hand.mobi")
        assertEquals(title, id?.title)
        assertEquals(author, id?.author)
        assertEquals(isbn, id?.isbn)
        val got = BookCover.embedded(bytes, "left-hand.azw3")
        assertTrue(got != null && got.size >= 8 && got[0] == 0x89.toByte())
        assertTrue(BookCover.isCoverImage(got!!))

        val named = mobi(pdb = "Ignored_Name", fullName = "Dune", title503 = null, author = "Frank Herbert", isbn = null, cover = null)
        assertEquals("Dune", BookCover.identity(named, "dune.azw")?.title)
        assertEquals("Frank Herbert", BookCover.identity(named, "dune.azw")?.author)

        val onlyPdb = mobi(pdb = "The_Dispossessed", fullName = "", title503 = null, author = "", isbn = null, cover = null)
        assertEquals("The Dispossessed", BookCover.identity(onlyPdb, "x.prc")?.title)
    }

    @Test
    fun `a file that is not a book and a broken one do not throw`() {
        val junk = ByteArray(300) { (it * 17).toByte() }
        assertNull(BookCover.identity(junk, "notes.txt"))
        assertNull(BookCover.embedded(junk, "notes.txt"))
        assertNull(BookCover.identity(junk, "book.epub"))
        assertNull(BookCover.embedded(junk, "book.mobi"))
        assertNull(BookCover.identity(ByteArray(0), "book.pdf"))
        assertNull(BookCover.openLibraryCover("not json", BookCover.Identity(title, author, null)))
        assertTrue(BookCover.googleCovers("{", BookCover.Identity(title, author, null)).isEmpty())
    }

    @Test
    fun `a search result is used only when the picture is a real cover`() {
        val id = BookCover.Identity("Dune", "Frank Herbert", null)
        val search = BookCover.queries(id).first { it.kind == BookCover.QueryKind.OPEN_LIBRARY }.url
        val json = """{"docs":[{"title":"Dune","author_name":["Frank Herbert"],"cover_i":42}]}"""
        val coverUrl = "https://covers.openlibrary.org/b/id/42-L.jpg?default=false"
        val cover = png(64, 96)
        val calls = ArrayList<String>()
        val got = BookCover.resolve(id, null) { url ->
            calls.add(url)
            when (url) {
                search -> json.toByteArray()
                coverUrl -> cover
                else -> null
            }
        }
        assertTrue(cover.contentEquals(got))
        assertEquals(listOf(search, coverUrl), calls)
    }

    private fun epub(opf: String, files: Map<String, ByteArray>): ByteArray {
        val container = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()
        val entries = LinkedHashMap<String, ByteArray>()
        entries["META-INF/container.xml"] = container.toByteArray()
        entries["OEBPS/content.opf"] = opf.toByteArray()
        entries.putAll(files)
        return zip(entries)
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /* A real PNG, so the dimension check and a decoder both see a picture. */
    private fun png(w: Int, h: Int): ByteArray {
        val raw = ByteArray((1 + w * 3) * h)
        val def = Deflater()
        def.setInput(raw)
        def.finish()
        val comp = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!def.finished()) {
            val n = def.deflate(buf)
            if (n > 0) comp.write(buf, 0, n)
        }
        def.end()
        val ihdr = ByteArray(13)
        be32(w).copyInto(ihdr, 0)
        be32(h).copyInto(ihdr, 4)
        ihdr[8] = 8
        ihdr[9] = 2
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(chunk("IHDR", ihdr))
        out.write(chunk("IDAT", comp.toByteArray()))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        val out = ByteArrayOutputStream()
        out.write(be32(data.size))
        out.write(t)
        out.write(data)
        out.write(be32(crc.value.toInt()))
        return out.toByteArray()
    }

    /* MOBI record 0: PalmDOC, then MOBI at byte 16, EXTH at 16 + header
       length, full name after that. Record 1 is the cover. Offsets are
       from the start of record 0, matching the file layout. */
    private fun mobi(
        pdb: String,
        fullName: String,
        title503: String?,
        author: String,
        isbn: String?,
        cover: ByteArray?,
    ): ByteArray {
        val records = ArrayList<ByteArray>()
        fun add(type: Int, data: ByteArray) {
            val len = 8 + data.size
            val out = ByteArrayOutputStream()
            out.write(be32(type))
            out.write(be32(len))
            out.write(data)
            records.add(out.toByteArray())
        }
        if (author.isNotEmpty()) add(100, author.toByteArray(Charsets.UTF_8))
        if (isbn != null) add(104, isbn.toByteArray(Charsets.UTF_8))
        if (title503 != null) add(503, title503.toByteArray(Charsets.UTF_8))
        if (cover != null) add(201, be32(0))
        val body = ByteArrayOutputStream()
        for (r in records) body.write(r)
        val bodyBytes = body.toByteArray()
        val exth = ByteArrayOutputStream()
        exth.write("EXTH".toByteArray())
        exth.write(be32(12 + bodyBytes.size))
        exth.write(be32(records.size))
        exth.write(bodyBytes)
        val exthBytes = exth.toByteArray()
        val headerLen = 232
        val full = fullName.toByteArray(Charsets.UTF_8)
        val nameAt = 16 + headerLen + exthBytes.size
        val rec0 = ByteArray(nameAt + full.size)
        rec0[1] = 1
        "MOBI".toByteArray().copyInto(rec0, 16)
        be32(headerLen).copyInto(rec0, 20)
        be32(2).copyInto(rec0, 24)
        be32(65001).copyInto(rec0, 28)
        be32(nameAt).copyInto(rec0, 84)
        be32(full.size).copyInto(rec0, 88)
        if (cover != null) be32(1).copyInto(rec0, 108)
        be32(0x40).copyInto(rec0, 128)
        exthBytes.copyInto(rec0, 16 + headerLen)
        full.copyInto(rec0, nameAt)

        val nrec = if (cover != null) 2 else 1
        val header = 78 + nrec * 8
        val rec0At = header
        val rec1At = rec0At + rec0.size
        val fileLen = if (cover != null) rec1At + cover.size else rec0At + rec0.size
        val file = ByteArray(fileLen)
        val pdbBytes = pdb.toByteArray(Charsets.UTF_8)
        pdbBytes.copyInto(file, 0, 0, minOf(pdbBytes.size, 31))
        "BOOKMOBI".toByteArray().copyInto(file, 60)
        be16(nrec).copyInto(file, 76)
        be32(rec0At).copyInto(file, 78)
        if (cover != null) {
            be32(rec1At).copyInto(file, 86)
            cover.copyInto(file, rec1At)
        }
        rec0.copyInto(file, rec0At)
        return file
    }

    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun be32(v: Int) = byteArrayOf(
        (v ushr 24).toByte(),
        (v ushr 16).toByte(),
        (v ushr 8).toByte(),
        v.toByte(),
    )
}
