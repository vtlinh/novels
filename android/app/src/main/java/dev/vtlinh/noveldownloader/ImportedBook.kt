package dev.vtlinh.noveldownloader

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/* A PDF, EPUB, or Kindle file from the file picker, turned into the chapter
   files the reader already opens.

   Split on chapters when the file has them: an EPUB's own chapter files or
   headings, a Kindle book's headings, a PDF whose pages start a new chapter.
   When nothing marks a chapter, each page is its own piece, so the book can
   still be moved through. Pictures stay with the chapter they were in, in
   the order they appeared, and a chapter can have more than one. Bytes in,
   chapters out — BookImport does the saving. No Android in here. */
object ImportedBook {

    const val URL_MARK = "import:"

    const val CHOOSE = "Choose a PDF, EPUB, or Kindle file."
    const val EMPTY = "This file is empty."
    const val TOO_BIG = "This file is too big to add."
    const val LOCKED = "This file is locked, so it can't be added."
    const val NO_TEXT = "This file has no text to read. A photographed page won't work."
    const val KINDLE_UNSUPPORTED =
        "This Kindle file can't be read. An unlocked Kindle book, EPUB, or PDF will work."
    const val COULDNT = "Couldn't read the text in this file."
    const val SAVE = "Couldn't save it to the library."
    const val OPEN = "Couldn't open that file."

    /* What the system file picker should offer. A lot of Kindle files are
       labelled as a plain binary, so that type is included; the name and the
       bytes decide what is actually read. */
    val MIME_TYPES = arrayOf(
        "application/pdf",
        "application/epub+zip",
        "application/x-mobipocket-ebook",
        "application/vnd.amazon.ebook",
        "application/x-mobi8-ebook",
        "application/vnd.amazon.mobi8-ebook",
        "application/octet-stream",
    )

    const val MAX_BYTES = 80 * 1024 * 1024

    enum class Format { PDF, EPUB, KINDLE }

    /* One picture from the file, still in the format it was stored in
       (jpeg, png, gif, webp). `alt` is the caption written beside it. */
    data class Fig(val bytes: ByteArray, val alt: String)

    data class Chapter(
        val title: String,
        val text: String,
        val images: List<Fig> = emptyList(),
    )

    data class Book(
        val title: String,
        val author: String,
        val description: String,
        val format: Format,
        val chapters: List<Chapter>,
        val byPage: Boolean,
    )

    class CannotRead(message: String) : Exception(message)

    fun isImported(url: String) = url.startsWith(URL_MARK)

    fun urlFor(format: Format) = URL_MARK + when (format) {
        Format.PDF -> "pdf"
        Format.EPUB -> "epub"
        Format.KINDLE -> "kindle"
    }

    fun formatOf(name: String): Format? {
        val n = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return when {
            n.endsWith(".pdf") -> Format.PDF
            n.endsWith(".epub") -> Format.EPUB
            n.endsWith(".mobi") || n.endsWith(".prc") || n.endsWith(".azw") ||
                n.endsWith(".azw3") || n.endsWith(".azw4") || n.endsWith(".kfx") ||
                n.endsWith(".pdb") -> Format.KINDLE
            else -> null
        }
    }

    fun fallbackTitle(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        return stem.replace('_', ' ').replace('-', ' ')
            .replace(Regex("\\s+"), " ").trim()
            .ifEmpty { "Imported book" }
    }

    fun slugFor(title: String, fileName: String): String {
        val raw = Extractor.sanitize(title).ifEmpty { Extractor.sanitize(fallbackTitle(fileName)) }
        val stem = raw.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "book" }
        return "import-$stem"
    }

    /* The library treats two slugs as one novel when only punctuation
       differs, so a second copy has to differ in letters or digits. */
    fun uniqueSlug(base: String, takenNorms: Set<String>): String {
        fun free(s: String) = Ownership.normKey(s) !in takenNorms
        if (free(base)) return base
        var n = 2
        while (!free("$base-$n")) n++
        return "$base-$n"
    }

    fun uniqueName(wanted: String, taken: Set<String>): String {
        if (wanted !in taken) return wanted
        var n = 2
        while ("$wanted ($n)" in taken) n++
        return "$wanted ($n)"
    }

    /* "Chapter 12 - The Storm.txt". The number is the reading order — that
       is what the reader sorts on — and the title rides along after it. */
    fun chapterFileName(index: Int, title: String): String {
        val n = index + 1
        var suffix = Extractor.sanitize(title).trim()
        suffix = suffix.replace(Regex("(?i)^chapter\\s+[0-9]+\\s*[-:]\\s*"), "")
        suffix = suffix.replace(Regex("(?i)^chapter\\s+[0-9]+$"), "")
        suffix = suffix.take(80).trimEnd('.', ' ', '-')
        if (suffix.isEmpty()) return "Chapter $n.txt"
        return "Chapter $n - $suffix.txt"
    }

    fun summary(title: String, count: Int, byPage: Boolean): String {
        val unit = when {
            count == 1 && byPage -> "page"
            byPage -> "pages"
            count == 1 -> "chapter"
            else -> "chapters"
        }
        return "Added \"$title\" — $count $unit."
    }

    fun read(name: String, bytes: ByteArray): Book {
        if (bytes.isEmpty()) throw CannotRead(EMPTY)
        if (bytes.size > MAX_BYTES) throw CannotRead(TOO_BIG)
        val sniffed = sniff(bytes)
        val hinted = formatOf(name)
        if (hinted == Format.KINDLE && sniffed == null && name.lowercase().endsWith(".kfx")) {
            throw CannotRead(KINDLE_UNSUPPORTED)
        }
        val format = sniffed ?: hinted ?: throw CannotRead(CHOOSE)
        try {
            return when (format) {
                Format.PDF -> readPdf(bytes, fallbackTitle(name))
                Format.EPUB -> readEpub(bytes, fallbackTitle(name))
                Format.KINDLE -> readKindle(bytes, fallbackTitle(name))
            }
        } catch (e: CannotRead) {
            throw e
        } catch (e: Exception) {
            throw CannotRead(COULDNT)
        }
    }

    private fun sniff(bytes: ByteArray): Format? {
        if (bytes.size >= 5 && String(bytes, 0, 5, ISO) == "%PDF-") return Format.PDF
        if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            return if (zipLooksEpub(bytes)) Format.EPUB else null
        }
        if (isPalmBook(bytes)) return Format.KINDLE
        return null
    }

    private fun isPalmBook(bytes: ByteArray): Boolean {
        if (bytes.size < 68) return false
        return String(bytes, 60, 4, ISO) == "BOOK" && String(bytes, 64, 4, ISO) == "MOBI"
    }

    /* ---- EPUB ---- */

    private fun readEpub(bytes: ByteArray, fallback: String): Book {
        val zip = readZip(bytes)
        val container = zipFind(zip, "META-INF/container.xml")
            ?: throw CannotRead(COULDNT)
        val cdoc = Jsoup.parse(ByteArrayInputStream(container), null, "", Parser.xmlParser())
        val opfPath = cdoc.select("rootfile").firstOrNull()?.attr("full-path")?.trim().orEmpty()
            .ifEmpty { throw CannotRead(COULDNT) }
        val opfBytes = zipFind(zip, opfPath) ?: throw CannotRead(COULDNT)
        val opf = Jsoup.parse(ByteArrayInputStream(opfBytes), null, "", Parser.xmlParser())
        val title = firstLocalText(opf, "title").ifEmpty { fallback }
        val author = allLocalText(opf, "creator").joinToString(", ")
        val description = firstLocalText(opf, "description")
        val base = opfPath.substringBeforeLast('/', "")
        val items = HashMap<String, ManifestItem>()
        for (el in opf.select("item")) {
            val id = el.attr("id")
            if (id.isEmpty()) continue
            items[id] = ManifestItem(
                href = el.attr("href"),
                media = el.attr("media-type").lowercase(),
                props = el.attr("properties").lowercase(),
            )
        }
        val docs = ArrayList<HtmlDoc>()
        for (ref in opf.select("itemref")) {
            val item = items[ref.attr("idref")] ?: continue
            if ("nav" in item.props.split(Regex("\\s+"))) continue
            if (item.media.contains("ncx") || item.href.lowercase().endsWith(".ncx")) continue
            if (!isHtmlMedia(item.media, item.href)) continue
            val path = resolvePath(base, item.href)
            val raw = zipFind(zip, path) ?: continue
            val doc = Jsoup.parse(ByteArrayInputStream(raw), null, "")
            doc.select("script, style, svg").remove()
            val text = htmlToText(doc.body())
            val fileBase = path.substringBeforeLast('/', "")
            val take: (Element) -> Fig? = { figOf(it, fileBase, zip) }
            val images = figsIn(doc.body(), take)
            if (letters(text) == 0 && images.isEmpty()) continue
            val heading = doc.body().selectFirst("h1, h2, h3")?.text()?.trim().orEmpty()
                .ifEmpty {
                    val file = path.substringAfterLast('/')
                    if (images.isNotEmpty() && file.contains("cover", ignoreCase = true)) "Cover" else ""
                }
            docs.add(HtmlDoc(text, heading, doc.body(), images, take))
        }
        if (docs.isEmpty()) throw CannotRead(NO_TEXT)
        val split = if (docs.size == 1) {
            piecesFromHtml(docs[0].body, docs[0].take)
        } else {
            /* Each spine file is a chapter the book already divided. A part
               page or a dedication is short on purpose — keep it. Drop only
               a file that has neither text nor a picture. */
            val kept = docs.filter { letters(it.text) > 0 || it.images.isNotEmpty() }
                .ifEmpty { docs }
            val byPage = spineIsPages(kept.map { it.text })
            val chapters = kept.mapIndexed { i, d ->
                val t = when {
                    byPage -> headingOf(d.text) ?: "Page ${i + 1}"
                    d.heading.isNotEmpty() -> d.heading
                    else -> ""
                }
                Chapter(t, d.text, d.images)
            }
            Split(chapters, byPage)
        }
        if (split.chapters.isEmpty()) throw CannotRead(NO_TEXT)
        if (!readable(split.chapters.joinToString("\n") { it.text })) throw CannotRead(COULDNT)
        return Book(
            cleanTitle(title), cleanAuthor(author), cleanDesc(description),
            Format.EPUB, split.chapters, split.byPage,
        )
    }

    private class ManifestItem(val href: String, val media: String, val props: String)
    private class HtmlDoc(
        val text: String,
        val heading: String,
        val body: Element,
        val images: List<Fig>,
        val take: (Element) -> Fig?,
    )

    private fun isHtmlMedia(media: String, href: String): Boolean {
        if (media.contains("html") || media.contains("xml")) return true
        val h = href.lowercase()
        return h.endsWith(".html") || h.endsWith(".xhtml") || h.endsWith(".htm")
    }

    private fun spineIsPages(texts: List<String>): Boolean {
        if (texts.size < 2) return false
        if (texts.count { headingOf(it) != null } >= 2) return false
        val lengths = texts.map { it.length }.sorted()
        return lengths[lengths.size / 2] < 1500
    }

    /* ---- HTML chapters and pages ---- */

    internal class Split(val chapters: List<Chapter>, val byPage: Boolean)

    private fun piecesFromHtml(body: Element, take: (Element) -> Fig? = { null }): Split {
        splitByTag(body, "h1", take)?.let { return Split(it, false) }
        splitByTag(body, "h2", take)?.let { return Split(it, false) }
        val pages = splitPages(body, take)
        if (pages.size >= 2) {
            return Split(
                pages.mapIndexed { i, p ->
                    Chapter(headingOf(p.text) ?: "Page ${i + 1}", p.text, p.images)
                },
                true,
            )
        }
        val text = htmlToText(body)
        val images = figsIn(body, take)
        if (letters(text) == 0 && images.isEmpty()) return Split(emptyList(), false)
        val heading = body.selectFirst("h1, h2, h3")?.text()?.trim().orEmpty()
        return Split(listOf(Chapter(heading, text, images)), false)
    }

    private fun splitByTag(body: Element, tag: String, take: (Element) -> Fig?): List<Chapter>? {
        if (body.select(tag).size < 2) return null
        val out = ArrayList<Chapter>()
        var title = ""
        val buf = StringBuilder()
        val images = ArrayList<Fig>()
        fun flush() {
            val text = cleanText(buf.toString())
            val figs = images.toList()
            if (letters(text) >= MIN_CHAPTER || figs.isNotEmpty()) out.add(Chapter(title, text, figs))
            buf.clear()
            images.clear()
        }
        fun walk(node: Node) {
            if (node is Element) {
                val name = node.normalName()
                if (name == "img" || name == "image") {
                    take(node)?.let { images.add(it) }
                    return
                }
                if (name == tag) {
                    flush()
                    title = node.text().trim()
                }
                if (name == "br") {
                    newline(buf)
                    return
                }
                val block = name in BLOCK
                if (block) newline(buf)
                for (c in node.childNodes()) walk(c)
                if (block) newline(buf)
            } else if (node is TextNode) {
                appendText(buf, node.wholeText)
            }
        }
        walk(body)
        flush()
        return out.takeIf { it.size >= 2 }
    }

    private class PagePiece(val text: String, val images: List<Fig>)

    private fun splitPages(body: Element, take: (Element) -> Fig?): List<PagePiece> {
        val paras = body.select("p")
        val styled = paras.count { hasBreakStyle(it) }
        val ignoreCss = paras.isNotEmpty() && styled * 5 >= paras.size * 4
        val out = ArrayList<PagePiece>()
        val buf = StringBuilder()
        val images = ArrayList<Fig>()
        var breaks = 0
        fun flush() {
            val text = cleanText(buf.toString())
            if (letters(text) > 0 || images.isNotEmpty()) out.add(PagePiece(text, images.toList()))
            buf.clear()
            images.clear()
        }
        fun walk(node: Node) {
            if (node is Element) {
                val name = node.normalName()
                if (name == "img" || name == "image") {
                    take(node)?.let { images.add(it) }
                    return
                }
            }
            if (node is Element && isPageBreak(node, ignoreCss)) {
                breaks++
                flush()
                if (!markerOnly(node)) {
                    for (c in node.childNodes()) walk(c)
                }
                return
            }
            if (node is Element) {
                val name = node.normalName()
                if (name == "br") {
                    newline(buf)
                    return
                }
                val block = name in BLOCK
                if (block) newline(buf)
                for (c in node.childNodes()) walk(c)
                if (block) newline(buf)
            } else if (node is TextNode) {
                appendText(buf, node.wholeText)
            }
        }
        walk(body)
        flush()
        return if (breaks >= 2) out else emptyList()
    }

    /* Every picture in the document, in the order it is shown. */
    private fun figsIn(body: Element, take: (Element) -> Fig?): List<Fig> {
        val images = ArrayList<Fig>()
        fun walk(node: Node) {
            if (node is Element) {
                val name = node.normalName()
                if (name == "img" || name == "image") {
                    take(node)?.let { images.add(it) }
                    return
                }
                for (c in node.childNodes()) walk(c)
            }
        }
        walk(body)
        return images
    }

    private fun isPageBreak(el: Element, ignoreCss: Boolean): Boolean {
        val tag = el.tagName().lowercase()
        if (tag == "pagebreak" || tag.endsWith(":pagebreak")) return true
        val role = (el.attr("epub:type") + " " + el.attr("type") + " " + el.attr("role")).lowercase()
        if (role.contains("pagebreak")) return true
        if (ignoreCss) return false
        return hasBreakStyle(el)
    }

    private fun hasBreakStyle(el: Element): Boolean {
        val style = el.attr("style").lowercase().replace(" ", "")
        return style.contains("page-break-before:always") || style.contains("break-before:page")
    }

    private fun markerOnly(el: Element): Boolean {
        val tag = el.tagName().lowercase()
        if (tag.contains("pagebreak")) return true
        val text = el.text().trim()
        return text.isEmpty() || (text.length <= 6 && text.all { it.isDigit() || it.isWhitespace() })
    }

    /* A line that starts a chapter, not a sentence that happens to contain
       the word. "Chapter 1 was the worst day" is a sentence. */
    fun headingOf(page: String): String? {
        val lines = page.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(2).toList()
        if (lines.isEmpty()) return null
        val first = lines[0]
        if (!isChapterLine(first)) return null
        val second = lines.getOrNull(1)
        if (second != null && first.length <= 24 && looksLikeTitle(second) &&
            !Regex("[:\\-—–]").containsMatchIn(first)
        ) {
            return "$first: $second"
        }
        return first
    }

    private fun isChapterLine(line: String): Boolean {
        if (line.length > 80) return false
        if (line.split(Regex("\\s+")).size > 14) return false
        if (NAMED_PART.matches(line)) return true
        if (CJK_CHAPTER.matches(line)) return true
        val m = CHAPTER_LABEL.find(line) ?: return false
        return CHAPTER_REST.matches(m.groupValues[2].trim())
    }

    private fun looksLikeTitle(line: String): Boolean {
        if (line.length > 60) return false
        if (line.endsWith(".") || line.endsWith("!") || line.endsWith("?")) return false
        return line.split(Regex("\\s+")).size in 1..12
    }

    /* Pages of text, grouped into chapters when at least two pages open a
       chapter. Otherwise each page stands alone. Pictures travel with the
       page they were on, and with the chapter that page joins. */
    internal fun fromPages(pages: List<String>): Split =
        groupPages(pages.map { PdfPage(it, emptyList()) })

    private class PdfPage(val text: String, val images: List<Fig>)

    private fun groupPages(pages: List<PdfPage>): Split {
        val usable = pages.map { PdfPage(cleanText(it.text), it.images) }
            .mapIndexed { i, p -> i to p }
            .filter { letters(it.second.text) > 0 || it.second.images.isNotEmpty() }
        if (usable.isEmpty()) return Split(emptyList(), true)
        val heads = usable.mapNotNull { (i, p) -> headingOf(p.text)?.let { i to it } }
        if (heads.size < 2) {
            return Split(
                usable.mapIndexed { n, (_, page) ->
                    Chapter("Page ${n + 1}", page.text, page.images)
                },
                true,
            )
        }
        val headAt = heads.associate { it.first to it.second }
        val out = ArrayList<Chapter>()
        var title = ""
        val buf = StringBuilder()
        val images = ArrayList<Fig>()
        fun flush() {
            val text = cleanText(buf.toString())
            val figs = images.toList()
            if (letters(text) > 0 || figs.isNotEmpty()) out.add(Chapter(title, text, figs))
            buf.clear()
            images.clear()
        }
        for ((i, page) in usable) {
            val head = headAt[i]
            if (head != null) {
                if (buf.isNotEmpty() || images.isNotEmpty()) flush()
                title = head
            }
            if (letters(page.text) > 0) {
                if (buf.isNotEmpty()) buf.append("\n\n")
                buf.append(page.text)
            }
            images.addAll(page.images)
        }
        flush()
        val chapters = out.mapIndexed { i, ch ->
            if (ch.title.isBlank() && i == 0 && out.size > 1) ch.copy(title = "Opening") else ch
        }
        return Split(chapters, false)
    }

    /* ---- PDF ---- */

    private fun readPdf(bytes: ByteArray, fallback: String): Book {
        if (indexOf(bytes, "%PDF-") < 0) throw CannotRead(COULDNT)
        if (pdfEncrypted(bytes)) throw CannotRead(LOCKED)
        val pages = pdfDocumentPages(bytes)
        if (pages.isEmpty()) throw CannotRead(NO_TEXT)
        val split = groupPages(pages)
        if (split.chapters.isEmpty()) throw CannotRead(NO_TEXT)
        if (!readable(split.chapters.joinToString("\n") { it.text })) throw CannotRead(COULDNT)
        return Book(fallback, "", "", Format.PDF, split.chapters, split.byPage)
    }

    /* Pages in reading order when the file names them, pictures included.
       A file whose pages are not objects falls back to the content streams
       alone, which still has the text and simply has no pictures. */
    private fun pdfDocumentPages(bytes: ByteArray): List<PdfPage> {
        val structured = pdfStructured(bytes)
        if (structured.any { letters(it.text) > 0 }) return structured
        return pdfPages(bytes).map { PdfPage(it, emptyList()) }
    }

    private class PdfObj(val id: Int, val dict: String, val stream: ByteArray?)

    private fun pdfStructured(bytes: ByteArray): List<PdfPage> {
        val objs = pdfObjects(bytes)
        if (objs.isEmpty()) return emptyList()
        val byId = HashMap<Int, PdfObj>()
        for (o in objs) if (o.id !in byId) byId[o.id] = o
        val pages = orderedPages(objs, byId)
        if (pages.isEmpty()) return emptyList()
        val out = ArrayList<PdfPage>()
        for (page in pages) {
            val text = pageContents(page, byId)
            val images = pageImages(page, byId)
            if (letters(text) == 0 && images.isEmpty()) continue
            out.add(PdfPage(text, images))
        }
        return out
    }

    private fun pdfObjects(bytes: ByteArray): List<PdfObj> {
        val out = ArrayList<PdfObj>()
        var i = 0
        while (i < bytes.size - 7) {
            val at = indexOfObj(bytes, i) ?: break
            val parsed = readObj(bytes, at) ?: break
            out.add(parsed.first)
            val next = parsed.second
            if (next <= i) break
            i = next
        }
        return out
    }

    private fun indexOfObj(bytes: ByteArray, from: Int): Int? {
        var i = from
        while (i < bytes.size - 6) {
            val at = indexOf(bytes, " obj", i)
            if (at < 0) return null
            val after = at + 4
            val boundary = after >= bytes.size || isWs(bytes[after])
            if (boundary && objId(bytes, at) != null) return at
            i = at + 4
        }
        return null
    }

    private fun objId(bytes: ByteArray, objAt: Int): Int? {
        var i = objAt - 1
        if (i < 0 || !isDigitByte(bytes[i])) return null
        while (i >= 0 && isDigitByte(bytes[i])) i--
        if (i < 0 || !isWs(bytes[i])) return null
        i--
        if (i < 0 || !isDigitByte(bytes[i])) return null
        val idEnd = i + 1
        while (i >= 0 && isDigitByte(bytes[i])) i--
        if (i >= 0 && !isWs(bytes[i])) return null
        return String(bytes, i + 1, idEnd - (i + 1), ISO).toIntOrNull()
    }

    private fun isDigitByte(b: Byte): Boolean {
        val c = b.toInt() and 0xFF
        return c in '0'.code..'9'.code
    }

    private fun readObj(bytes: ByteArray, objAt: Int): Pair<PdfObj, Int>? {
        val id = objId(bytes, objAt) ?: return null
        var i = objAt + 4
        while (i < bytes.size && isWs(bytes[i])) i++
        if (i + 1 >= bytes.size || bytes[i] != '<'.code.toByte() || bytes[i + 1] != '<'.code.toByte()) {
            val end = indexOf(bytes, "endobj", i)
            return PdfObj(id, "", null) to (if (end < 0) bytes.size else end + 6)
        }
        val dictEnd = dictEndForward(bytes, i)
        if (dictEnd <= i + 2) return null
        val dict = String(bytes, i, dictEnd - i, ISO)
        var j = dictEnd
        while (j < bytes.size && isWs(bytes[j])) j++
        if (j + 6 <= bytes.size && String(bytes, j, 6, ISO) == "stream" &&
            (j + 6 >= bytes.size || isWs(bytes[j + 6]))
        ) {
            var start = j + 6
            if (start < bytes.size && bytes[start] == '\r'.code.toByte()) start++
            if (start < bytes.size && bytes[start] == '\n'.code.toByte()) start++
            val len = directLength(dict)
            if (len != null && len >= 0 && start.toLong() + len <= bytes.size) {
                val stream = bytes.copyOfRange(start, start + len)
                return PdfObj(id, dict, stream) to (start + len)
            }
            val end = indexOf(bytes, "endstream", start)
            if (end < 0) return PdfObj(id, dict, null) to bytes.size
            return PdfObj(id, dict, bytes.copyOfRange(start, end)) to (end + 9)
        }
        return PdfObj(id, dict, null) to j
    }

    private fun orderedPages(objs: List<PdfObj>, byId: Map<Int, PdfObj>): List<PdfObj> {
        val out = ArrayList<PdfObj>()
        val seen = HashSet<Int>()
        fun walk(obj: PdfObj) {
            if (!seen.add(obj.id)) return
            if (isPage(obj.dict)) {
                out.add(obj)
                return
            }
            for (id in kidsOf(obj.dict)) byId[id]?.let { walk(it) }
        }
        for (obj in objs) if (isPages(obj.dict)) walk(obj)
        if (out.isNotEmpty()) return out
        return objs.filter { isPage(it.dict) }
    }

    private fun isPage(dict: String) =
        Regex("/Type\\s*/Page\\b").containsMatchIn(dict) && !isPages(dict)

    private fun isPages(dict: String) = Regex("/Type\\s*/Pages\\b").containsMatchIn(dict)

    private fun kidsOf(dict: String): List<Int> {
        val m = Regex("/Kids\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(dict) ?: return emptyList()
        return refIds(m.groupValues[1])
    }

    private fun refIds(body: String): List<Int> =
        Regex("(\\d+)\\s+\\d+\\s+R").findAll(body).map { it.groupValues[1].toInt() }.toList()

    private fun pageContents(page: PdfObj, byId: Map<Int, PdfObj>): String {
        val m = Regex("/Contents\\s+(\\[.*?]|\\d+\\s+\\d+\\s+R)", RegexOption.DOT_MATCHES_ALL)
            .find(page.dict) ?: return ""
        val buf = StringBuilder()
        for (id in refIds(m.groupValues[1])) {
            val obj = byId[id] ?: continue
            val raw = obj.stream ?: continue
            val data = decodeStream(obj.dict, raw) ?: continue
            val text = pdfText(data)
            if (text.isEmpty()) continue
            if (buf.isNotEmpty()) buf.append("\n\n")
            buf.append(text)
        }
        return buf.toString()
    }

    private fun pageImages(page: PdfObj, byId: Map<Int, PdfObj>): List<Fig> {
        val m = Regex("/XObject\\s*<<(.*?)>>", RegexOption.DOT_MATCHES_ALL).find(page.dict)
            ?: return emptyList()
        val out = ArrayList<Fig>()
        for (id in refIds(m.groupValues[1])) {
            val obj = byId[id] ?: continue
            if (!Regex("/Subtype\\s*/Image\\b").containsMatchIn(obj.dict)) continue
            val bytes = rasterOf(obj) ?: continue
            out.add(Fig(bytes, ""))
        }
        return out
    }

    private fun rasterOf(obj: PdfObj): ByteArray? {
        val raw = obj.stream ?: return null
        if (raw.size < 4 || raw.size > 6 * 1024 * 1024) return null
        val dict = obj.dict
        if (hasFilter(dict, "DCTDecode") || hasFilter(dict, "DCT")) {
            return raw.takeIf { isRaster(it) }
        }
        if (hasFilter(dict, "FlateDecode") || hasFilter(dict, "Fl")) {
            val pixels = decodeStream(dict, raw) ?: return null
            if (isRaster(pixels)) return pixels
            return pngFromRaw(dict, pixels)
        }
        if (!dict.contains("/Filter") && isRaster(raw)) return raw
        return null
    }

    private fun hasFilter(dict: String, name: String): Boolean {
        val m = Regex("/Filter\\s*(\\[.*?]|/\\w+)").find(dict) ?: return false
        return Regex("/([A-Za-z0-9]+)").findAll(m.groupValues[1]).any { it.groupValues[1] == name }
    }

    /* An uncompressed gray or colour image, wrapped so a phone can draw it.
       Anything else (a mask, 1-bit, a CMYK plate) is left out. */
    private fun pngFromRaw(dict: String, pixels: ByteArray): ByteArray? {
        val w = Regex("/Width\\s+(\\d+)").find(dict)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val h = Regex("/Height\\s+(\\d+)").find(dict)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (w !in 1..4000 || h !in 1..4000) return null
        val bits = Regex("/BitsPerComponent\\s+(\\d+)").find(dict)?.groupValues?.get(1)?.toIntOrNull() ?: 8
        if (bits != 8) return null
        val cs = when {
            dict.contains("/DeviceGray") -> 1
            dict.contains("/DeviceRGB") -> 3
            else -> return null
        }
        val stride = w * cs
        if (pixels.size < h * stride) return null
        val raw = ByteArray(h * (stride + 1))
        var o = 0
        var p = 0
        for (y in 0 until h) {
            raw[o++] = 0
            pixels.copyInto(raw, o, p, p + stride)
            o += stride
            p += stride
        }
        val zipped = deflateBytes(raw) ?: return null
        return pngWrap(w, h, cs, zipped)
    }

    private fun deflateBytes(data: ByteArray): ByteArray? {
        val def = java.util.zip.Deflater()
        return try {
            def.setInput(data)
            def.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!def.finished()) {
                val n = def.deflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
            if (!def.finished()) null else out.toByteArray()
        } finally {
            def.end()
        }
    }

    private fun pngWrap(w: Int, h: Int, cs: Int, idat: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13)
        writeInt(ihdr, 0, w)
        writeInt(ihdr, 4, h)
        ihdr[8] = 8
        ihdr[9] = if (cs == 1) 0 else 2
        pngChunk(out, "IHDR", ihdr)
        pngChunk(out, "IDAT", idat)
        pngChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun pngChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = ByteArray(4)
        writeInt(len, 0, data.size)
        out.write(len)
        val tb = type.toByteArray(Charsets.US_ASCII)
        out.write(tb)
        out.write(data)
        val crc = java.util.zip.CRC32()
        crc.update(tb)
        crc.update(data)
        val c = ByteArray(4)
        writeInt(c, 0, crc.value.toInt())
        out.write(c)
    }

    private fun writeInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte()
        b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte()
        b[o + 3] = v.toByte()
    }

    /* Content streams, in the order they sit in the file. A page's text is
       almost always its own stream, and that stream is almost never packed
       into an object stream, so this still finds the pages when the cross
       reference is itself a stream. */
    private fun pdfPages(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < bytes.size - 8) {
            val at = indexOf(bytes, "stream", i)
            if (at < 0) break
            if (at > 0 && !isWs(bytes[at - 1])) {
                i = at + 6
                continue
            }
            val dict = dictBefore(bytes, at)
            if (dict == null || skipStream(dict)) {
                i = at + 6
                continue
            }
            val len = directLength(dict)
            var start = at + 6
            if (start < bytes.size && bytes[start] == '\r'.code.toByte()) start++
            if (start < bytes.size && bytes[start] == '\n'.code.toByte()) start++
            if (len == null || len < 0 || start.toLong() + len > bytes.size) {
                i = at + 6
                continue
            }
            val raw = bytes.copyOfRange(start, start + len)
            val decoded = decodeStream(dict, raw)
            if (decoded != null && (indexOf(decoded, "Tj") >= 0 || indexOf(decoded, "TJ") >= 0)) {
                val text = pdfText(decoded)
                if (letters(text) > 0) out.add(text)
            }
            i = start + len
        }
        return out
    }

    private fun pdfEncrypted(bytes: ByteArray): Boolean {
        val start = lastIndexOf(bytes, "trailer")
        if (start < 0) return false
        val end = minOf(bytes.size, start + 2000)
        val trailer = String(bytes, start, end - start, ISO)
        return trailer.contains("/Encrypt")
    }

    private fun skipStream(dict: String): Boolean {
        if (dict.contains("/ObjStm")) return true
        if (dict.contains("/XRef")) return true
        if (dict.contains("/Image")) return true
        if (dict.contains("/Metadata")) return true
        return false
    }

    private fun directLength(dict: String): Int? {
        val m = Regex("/Length\\s+(\\d+)\\b").find(dict) ?: return null
        val after = dict.substring(m.range.last + 1).trimStart()
        if (Regex("^\\d+\\s+R").containsMatchIn(after)) return null
        return m.groupValues[1].toIntOrNull()
    }

    /* The dictionary that ends immediately before `stream`. Nested
       dictionaries count, so a /DecodeParms inside it is not mistaken for
       the dictionary itself. */
    private fun dictBefore(bytes: ByteArray, streamAt: Int): String? {
        var i = streamAt - 1
        while (i >= 0 && isWs(bytes[i])) i--
        if (i < 1 || bytes[i] != '>'.code.toByte() || bytes[i - 1] != '>'.code.toByte()) return null
        val end = i + 1
        var depth = 0
        var j = i
        val stop = maxOf(0, streamAt - 8000)
        while (j >= stop + 1) {
            if (bytes[j] == '>'.code.toByte() && bytes[j - 1] == '>'.code.toByte()) {
                depth++
                j -= 2
                continue
            }
            if (bytes[j] == '<'.code.toByte() && bytes[j - 1] == '<'.code.toByte()) {
                depth--
                if (depth == 0) return String(bytes, j - 1, end - (j - 1), ISO)
                j -= 2
                continue
            }
            j--
        }
        return null
    }

    private fun decodeStream(dict: String, raw: ByteArray): ByteArray? {
        if (Regex("/Predictor\\s+([2-9]|\\d{2,})").containsMatchIn(dict)) return null
        val filter = Regex("/Filter\\s*(\\[.*?]|/\\w+)").find(dict)?.groupValues?.get(1)
        val names = if (filter == null) emptyList()
        else Regex("/([A-Za-z0-9]+)").findAll(filter).map { it.groupValues[1] }.toList()
        var data = raw
        for (f in names.asReversed()) {
            data = when (f) {
                "FlateDecode", "Fl" -> inflate(data) ?: return null
                "ASCIIHexDecode", "AHx" -> asciiHex(data) ?: return null
                else -> return null
            }
        }
        return data
    }

    private fun inflate(data: ByteArray): ByteArray? {
        fun run(nowrap: Boolean): ByteArray? {
            val inf = Inflater(nowrap)
            inf.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            return try {
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0) {
                        if (inf.needsInput() || inf.needsDictionary()) break
                    } else {
                        if (out.size() + n > 8_000_000) return null
                        out.write(buf, 0, n)
                    }
                }
                if (out.size() == 0) null else out.toByteArray()
            } catch (e: Exception) {
                if (out.size() == 0) null else out.toByteArray()
            } finally {
                inf.end()
            }
        }
        return run(false) ?: run(true)
    }

    private fun asciiHex(data: ByteArray): ByteArray? {
        val hex = String(data, ISO).filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (hex.isEmpty()) return ByteArray(0)
        val even = if (hex.length % 2 == 0) hex else hex + "0"
        val out = ByteArray(even.length / 2)
        for (i in out.indices) {
            out[i] = even.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun pdfText(data: ByteArray): String {
        val sb = StringBuilder()
        val stack = ArrayList<Any>()
        var i = 0
        var lastY: Double? = null
        fun newline() {
            if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
        }
        fun space() {
            if (sb.isNotEmpty() && !sb.endsWith(" ") && !sb.endsWith("\n")) sb.append(' ')
        }
        fun apply(op: String) {
            when (op) {
                "Tj" -> stack.filterIsInstance<String>().lastOrNull()?.let { sb.append(it) }
                "'" -> {
                    newline()
                    stack.filterIsInstance<String>().lastOrNull()?.let { sb.append(it) }
                }
                "\"" -> {
                    newline()
                    stack.filterIsInstance<String>().lastOrNull()?.let { sb.append(it) }
                }
                "TJ" -> {
                    val arr = stack.lastOrNull { it is List<*> } as? List<*>
                    if (arr != null) {
                        for (item in arr) {
                            when (item) {
                                is String -> sb.append(item)
                                is Double -> if (item <= -200.0) space()
                            }
                        }
                    }
                }
                "T*" -> newline()
                "Td", "TD" -> {
                    val nums = stack.filterIsInstance<Double>()
                    if (nums.size >= 2) {
                        val tx = nums[nums.size - 2]
                        val ty = nums[nums.size - 1]
                        if (ty != 0.0) newline() else if (kotlin.math.abs(tx) >= 12.0) space()
                    }
                }
                "Tm" -> {
                    val nums = stack.filterIsInstance<Double>()
                    if (nums.size >= 6) {
                        val y = nums.last()
                        if (lastY != null && kotlin.math.abs(y - lastY!!) > 2.0) newline()
                        lastY = y
                    }
                }
                "BT" -> lastY = null
            }
            stack.clear()
        }
        while (i < data.size) {
            while (i < data.size && isWs(data[i])) i++
            if (i >= data.size) break
            val c = data[i].toInt() and 0xFF
            when {
                c == '('.code -> {
                    val (s, n) = readLiteral(data, i)
                    stack.add(s)
                    i = n
                }
                c == '<'.code && i + 1 < data.size && data[i + 1] == '<'.code.toByte() -> {
                    val end = dictEndForward(data, i)
                    i = if (end > i) end else i + 2
                }
                c == '<'.code -> {
                    val (s, n) = readHexString(data, i)
                    if (s != null) stack.add(s)
                    i = n
                }
                c == '['.code -> {
                    val (arr, n) = readArray(data, i)
                    stack.add(arr)
                    i = n
                }
                c == '%'.code -> {
                    while (i < data.size && data[i] != '\n'.code.toByte() && data[i] != '\r'.code.toByte()) i++
                }
                c == '/'.code -> {
                    i++
                    while (i < data.size && !isDelim(data[i])) i++
                }
                isNumStart(data, i) -> {
                    val (num, n) = readNumber(data, i)
                    stack.add(num)
                    i = if (n > i) n else i + 1
                }
                c == '\''.code || c == '"'.code -> {
                    apply(c.toChar().toString())
                    i++
                }
                else -> {
                    val (op, n) = readOp(data, i)
                    if (n == i) {
                        i++
                    } else {
                        apply(op)
                        i = n
                    }
                }
            }
        }
        return cleanText(sb.toString())
    }

    private fun readLiteral(data: ByteArray, start: Int): Pair<String, Int> {
        val raw = ByteArrayOutputStream()
        var i = start + 1
        var depth = 1
        while (i < data.size && depth > 0) {
            val c = data[i].toInt() and 0xFF
            if (c == '\\'.code && i + 1 < data.size) {
                val n = data[i + 1].toInt() and 0xFF
                when (n) {
                    'n'.code -> raw.write('\n'.code)
                    'r'.code -> raw.write('\r'.code)
                    't'.code -> raw.write('\t'.code)
                    'b'.code -> raw.write(8)
                    'f'.code -> raw.write(12)
                    '('.code, ')'.code, '\\'.code -> raw.write(n)
                    '\n'.code -> {}
                    '\r'.code -> if (i + 2 < data.size && data[i + 2] == '\n'.code.toByte()) i++
                    else -> if (n in '0'.code..'7'.code) {
                        var oct = n - '0'.code
                        var k = 1
                        while (k < 3 && i + 1 + k < data.size) {
                            val d = data[i + 1 + k].toInt() and 0xFF
                            if (d !in '0'.code..'7'.code) break
                            oct = (oct shl 3) or (d - '0'.code)
                            k++
                        }
                        raw.write(oct and 0xFF)
                        i += 1 + k
                        continue
                    } else {
                        raw.write(n)
                    }
                }
                i += 2
                continue
            }
            when (c) {
                '('.code -> {
                    depth++
                    raw.write(c)
                    i++
                }
                ')'.code -> {
                    depth--
                    if (depth == 0) {
                        i++
                        break
                    }
                    raw.write(c)
                    i++
                }
                else -> {
                    raw.write(c)
                    i++
                }
            }
        }
        return decodePdfBytes(raw.toByteArray()) to i
    }

    private fun readHexString(data: ByteArray, start: Int): Pair<String?, Int> {
        var i = start + 1
        val hex = StringBuilder()
        while (i < data.size) {
            val c = data[i].toInt().toChar()
            if (c == '>') {
                i++
                break
            }
            if (!c.isWhitespace()) hex.append(c)
            i++
        }
        val s = hex.toString()
        if (s.any { it !in "0123456789abcdefABCDEF" }) return null to i
        val even = if (s.length % 2 == 0) s else s + "0"
        val raw = ByteArray(even.length / 2)
        for (k in raw.indices) raw[k] = even.substring(k * 2, k * 2 + 2).toInt(16).toByte()
        return decodePdfBytes(raw) to i
    }

    private fun readArray(data: ByteArray, start: Int): Pair<List<Any>, Int> {
        val out = ArrayList<Any>()
        var i = start + 1
        while (i < data.size) {
            while (i < data.size && isWs(data[i])) i++
            if (i >= data.size) break
            val c = data[i].toInt() and 0xFF
            when {
                c == ']'.code -> return out to (i + 1)
                c == '('.code -> {
                    val (s, n) = readLiteral(data, i)
                    out.add(s)
                    i = n
                }
                c == '<'.code -> {
                    val (s, n) = readHexString(data, i)
                    if (s != null) out.add(s)
                    i = n
                }
                isNumStart(data, i) -> {
                    val (num, n) = readNumber(data, i)
                    out.add(num)
                    i = if (n > i) n else i + 1
                }
                else -> i++
            }
        }
        return out to i
    }

    private fun decodePdfBytes(raw: ByteArray): String {
        if (raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()) {
            return String(raw, 2, raw.size - 2, Charsets.UTF_16BE)
        }
        return String(raw, WIN)
    }

    private fun readNumber(data: ByteArray, start: Int): Pair<Double, Int> {
        var i = start
        val sb = StringBuilder()
        val c0 = data[i].toInt() and 0xFF
        if (c0 == '+'.code || c0 == '-'.code) {
            sb.append(c0.toChar())
            i++
        }
        var saw = false
        while (i < data.size) {
            val c = data[i].toInt() and 0xFF
            if (c in '0'.code..'9'.code || c == '.'.code) {
                sb.append(c.toChar())
                saw = true
                i++
            } else break
        }
        if (!saw) return 0.0 to start
        return (sb.toString().toDoubleOrNull() ?: 0.0) to i
    }

    private fun readOp(data: ByteArray, start: Int): Pair<String, Int> {
        var i = start
        val sb = StringBuilder()
        while (i < data.size) {
            val c = data[i].toInt() and 0xFF
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c == '*'.code) {
                sb.append(c.toChar())
                i++
            } else break
        }
        return sb.toString() to i
    }

    private fun dictEndForward(data: ByteArray, start: Int): Int {
        var i = start + 2
        var depth = 1
        while (i + 1 < data.size && depth > 0) {
            if (data[i] == '<'.code.toByte() && data[i + 1] == '<'.code.toByte()) {
                depth++
                i += 2
                continue
            }
            if (data[i] == '>'.code.toByte() && data[i + 1] == '>'.code.toByte()) {
                depth--
                i += 2
                continue
            }
            i++
        }
        return i
    }

    private fun isNumStart(data: ByteArray, i: Int): Boolean {
        val c = data[i].toInt() and 0xFF
        if (c in '0'.code..'9'.code) return true
        if (c == '.'.code && i + 1 < data.size) {
            val n = data[i + 1].toInt() and 0xFF
            return n in '0'.code..'9'.code
        }
        if (c == '+'.code || c == '-'.code) {
            if (i + 1 >= data.size) return false
            val n = data[i + 1].toInt() and 0xFF
            return n in '0'.code..'9'.code || n == '.'.code
        }
        return false
    }

    /* ---- Kindle ---- */

    private fun readKindle(bytes: ByteArray, fallback: String): Book {
        if (!isPalmBook(bytes)) throw CannotRead(KINDLE_UNSUPPORTED)
        val records = palmRecords(bytes) ?: throw CannotRead(COULDNT)
        val first = readPalmBook(records, 0) ?: throw CannotRead(COULDNT)
        var best = first
        if (first.kf8 >= 0) {
            val k = readPalmBook(records, first.kf8) ?: readPalmBook(records, first.kf8 + 1)
            if (k != null && !k.huff && k.html != null &&
                (best.huff || letters(k.html) > letters(best.html ?: ""))
            ) {
                best = k
            }
        }
        val html = best.html
        if (best.huff || html.isNullOrBlank()) throw CannotRead(KINDLE_UNSUPPORTED)
        val doc = Jsoup.parse(html)
        doc.select("script, style, svg").remove()
        val pictures = if (best.images.isNotEmpty()) best.images else first.images
        val split = piecesFromHtml(doc.body()) { kindleFig(it, pictures) }
        if (split.chapters.isEmpty() || letters(split.chapters.joinToString("\n") { it.text }) < 20) {
            throw CannotRead(NO_TEXT)
        }
        val title = cleanTitle(best.title).ifEmpty { fallback }
        return Book(
            title, cleanAuthor(best.author), cleanDesc(best.description),
            Format.KINDLE, split.chapters, split.byPage,
        )
    }

    private class PalmBook(
        val title: String,
        val author: String,
        val description: String,
        val html: String?,
        val kf8: Int,
        val huff: Boolean,
        val images: List<ByteArray> = emptyList(),
    )

    private fun palmRecords(bytes: ByteArray): List<ByteArray>? {
        if (bytes.size < 78) return null
        val n = u16(bytes, 76)
        if (n <= 0 || n > 200_000) return null
        if (bytes.size < 78 + n * 8) return null
        val offsets = IntArray(n) { u32(bytes, 78 + it * 8) }
        if (offsets.any { it < 0 || it > bytes.size }) return null
        return List(n) { i ->
            val start = offsets[i]
            val end = if (i + 1 < n) offsets[i + 1].coerceAtMost(bytes.size) else bytes.size
            if (end < start) return null
            bytes.copyOfRange(start, end)
        }
    }

    private fun readPalmBook(records: List<ByteArray>, index: Int): PalmBook? {
        if (index !in records.indices) return null
        val rec = records[index]
        if (rec.size < 16) return null
        val compression = u16(rec, 0)
        if (compression != 1 && compression != 2 && compression != 17480) return null
        val textLen = u32(rec, 4)
        val textRecords = u16(rec, 8)
        val encryption = u16(rec, 12)
        if (encryption != 0) throw CannotRead(LOCKED)
        if (textRecords <= 0 || index + textRecords >= records.size) return null
        var encoding = 1252
        var title = ""
        var author = ""
        var description = ""
        var kf8 = -1
        var extra = 0
        if (rec.size >= 20 && String(rec, 16, 4, ISO) == "MOBI") {
            val headerLen = u32(rec, 20)
            if (rec.size >= 32) encoding = u32(rec, 28)
            if (rec.size >= 92) {
                val off = u32(rec, 84)
                val len = u32(rec, 88)
                if (len in 1..400 && off >= 0 && off + len <= rec.size) {
                    title = decodeBytes(rec.copyOfRange(off, off + len), encoding)
                }
            }
            if (rec.size >= 132 && headerLen >= 0xE4 && rec.size >= 0xF4) {
                extra = u16(rec, 0xF2)
            }
            if (rec.size >= 132 && u32(rec, 128) and 0x40 != 0) {
                val exth = parseExth(rec, 16 + headerLen, encoding)
                if (exth.title.isNotBlank()) title = exth.title
                author = exth.author
                description = exth.description
                kf8 = exth.kf8
            }
        }
        val images = palmImages(records, rec)
        if (compression == 17480) return PalmBook(title, author, description, null, kf8, true, images)
        val parts = ArrayList<ByteArray>()
        for (r in 1..textRecords) {
            var chunk = records[index + r]
            val trail = trailSize(chunk, extra)
            if (trail in 1 until chunk.size) chunk = chunk.copyOfRange(0, chunk.size - trail)
            parts.add(if (compression == 2) palmdoc(chunk) else chunk)
        }
        var all = ByteArray(parts.sumOf { it.size })
        var p = 0
        for (part in parts) {
            part.copyInto(all, p)
            p += part.size
        }
        if (textLen in 1 until all.size) all = all.copyOf(textLen)
        return PalmBook(title, author, description, decodeBytes(all, encoding), kf8, false, images)
    }

    private class ExthInfo(val title: String, val author: String, val description: String, val kf8: Int)

    private fun parseExth(rec: ByteArray, at: Int, encoding: Int): ExthInfo {
        if (at < 0 || at + 12 > rec.size) return ExthInfo("", "", "", -1)
        if (String(rec, at, 4, ISO) != "EXTH") return ExthInfo("", "", "", -1)
        val count = u32(rec, at + 8)
        var p = at + 12
        val authors = ArrayList<String>()
        var title = ""
        var desc = ""
        var kf8 = -1
        var n = 0
        while (n < count && p + 8 <= rec.size) {
            val type = u32(rec, p)
            val len = u32(rec, p + 4)
            if (len < 8 || p + len > rec.size) break
            val data = rec.copyOfRange(p + 8, p + len)
            when (type) {
                100 -> {
                    val a = decodeBytes(data, encoding).trim()
                    if (a.isNotEmpty()) authors.add(a)
                }
                103 -> desc = decodeBytes(data, encoding)
                121 -> if (data.size >= 4) kf8 = u32(data, 0)
                503 -> title = decodeBytes(data, encoding)
            }
            p += len
            n++
        }
        return ExthInfo(title.trim(), authors.joinToString(", "), desc, kf8)
    }

    /* Trailing entries sit at the end of each text record. High flag bits
       are the entries closest to the end, so they are measured first; the
       value is the size of that entry including the bytes that store it.
       Bit 0 is the overlap byte, counted last. */
    private fun trailSize(data: ByteArray, flags: Int): Int {
        if (flags == 0 || data.isEmpty()) return 0
        var num = 0
        for (bit in 15 downTo 1) {
            if (flags and (1 shl bit) == 0) continue
            val entry = varLen(data, data.size - num)
            if (entry <= 0 || num + entry > data.size) return 0
            num += entry
        }
        if (flags and 1 != 0) {
            val idx = data.size - num - 1
            if (idx < 0) return 0
            val add = (data[idx].toInt() and 0x3) + 1
            if (num + add > data.size) return 0
            num += add
        }
        return num
    }

    private fun varLen(data: ByteArray, pos: Int): Int {
        var result = 0
        var shift = 0
        var p = pos - 1
        while (p >= 0 && shift <= 28) {
            val b = data[p].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            shift += 7
            p--
            if (b and 0x80 == 0) break
        }
        return result
    }

    private fun palmdoc(data: ByteArray): ByteArray {
        val out = Grow()
        var i = 0
        while (i < data.size) {
            val c = data[i].toInt() and 0xFF
            i++
            when {
                c == 0 || c in 9..0x7F -> out.add(c)
                c in 1..8 -> {
                    val n = minOf(c, data.size - i)
                    for (k in 0 until n) out.add(data[i + k].toInt() and 0xFF)
                    i += n
                }
                c in 0x80..0xBF -> {
                    if (i >= data.size) break
                    val c2 = data[i].toInt() and 0xFF
                    i++
                    val combined = (c shl 8) or c2
                    val distance = (combined shr 3) and 0x7FF
                    val length = (combined and 7) + 3
                    if (distance in 1..out.n) {
                        val start = out.n - distance
                        for (k in 0 until length) out.add(out.get(start + (k % distance)))
                    }
                }
                else -> {
                    out.add(' '.code)
                    out.add(c xor 0x80)
                }
            }
        }
        return out.bytes()
    }

    private class Grow {
        var a = ByteArray(4096)
        var n = 0
        fun add(b: Int) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = b.toByte()
        }
        fun get(i: Int) = a[i].toInt() and 0xFF
        fun bytes() = a.copyOf(n)
    }

    /* ---- shared text ---- */

    private val BLOCK = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote",
        "tr", "section", "article", "pre", "figcaption",
    )

    private fun htmlToText(body: Element): String {
        val buf = StringBuilder()
        fun walk(node: Node) {
            if (node is Element) {
                val name = node.normalName()
                if (name == "br") {
                    newline(buf)
                    return
                }
                val block = name in BLOCK
                if (block) newline(buf)
                for (c in node.childNodes()) walk(c)
                if (block) newline(buf)
            } else if (node is TextNode) {
                appendText(buf, node.wholeText)
            }
        }
        walk(body)
        return cleanText(buf.toString())
    }

    private fun newline(buf: StringBuilder) {
        if (buf.isNotEmpty() && !buf.endsWith("\n")) buf.append('\n')
    }

    private fun appendText(buf: StringBuilder, raw: String) {
        val t = raw.replace('\u00A0', ' ').replace(Regex("[\\t\\r\\f]+"), " ")
        if (t.isEmpty()) return
        if (t.isBlank()) {
            if (buf.isNotEmpty() && !buf.endsWith("\n") && !buf.endsWith(" ")) buf.append(' ')
            return
        }
        buf.append(t.replace(Regex(" {2,}"), " "))
    }

    private fun cleanText(s: String): String =
        s.replace('\u0000', ' ')
            .replace(Regex("[\\t\\u000B\\f\\r ]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    private fun letters(s: String?) = s?.count { it.isLetter() } ?: 0

    /* A custom font encoding comes back as symbols. Real prose is mostly
       letters; refuse the file rather than save a book of noise. */
    private fun readable(s: String): Boolean {
        val n = letters(s)
        if (n < 20) return false
        val symbols = s.count {
            !it.isLetter() && !it.isWhitespace() && !it.isDigit() &&
                it !in ".,;:!'\"?-—–()[]«»“”‘’…%&/"
        }
        return symbols * 2 < n
    }

    private fun cleanTitle(s: String) = s.replace('\u0000', ' ').replace(Regex("\\s+"), " ").trim().take(300)
    private fun cleanAuthor(s: String) = s.replace('\u0000', ' ').replace(Regex("\\s+"), " ").trim().take(200)
    private fun cleanDesc(s: String) = s.replace('\u0000', ' ').replace(Regex("\\s+"), " ").trim().take(4000)

    private fun decodeBytes(data: ByteArray, encoding: Int): String {
        val cs = when (encoding) {
            65001 -> Charsets.UTF_8
            1252 -> WIN
            else -> Charsets.UTF_8
        }
        return String(data, cs).replace("\u0000", "").trim()
    }

    /* ---- zip, paths, bytes ---- */

    private fun zipLooksEpub(bytes: ByteArray): Boolean = try {
        var yes = false
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var seen = 0
            while (seen < 30) {
                val e = zis.nextEntry ?: break
                seen++
                val name = e.name.replace('\\', '/').lowercase()
                if (name == "mimetype" || name.endsWith("container.xml") || name.endsWith(".opf")) {
                    yes = true
                    break
                }
            }
        }
        yes
    } catch (e: Exception) {
        false
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/').trimStart('/')
                val lower = name.lowercase()
                val image = isImageName(lower)
                val keep = image || lower.endsWith(".opf") || lower.endsWith(".html") ||
                    lower.endsWith(".xhtml") || lower.endsWith(".htm") ||
                    lower.endsWith(".xml") || lower.endsWith(".ncx") || lower == "mimetype"
                if (!keep) continue
                val data = if (image) readLimited(zis, 6 * 1024 * 1024) else readCap(zis, 8 * 1024 * 1024)
                if (data == null) continue
                if (image && !isRaster(data)) continue
                if (image && total + data.size > MAX_BYTES) continue
                total += data.size
                if (!image && total > MAX_BYTES) throw CannotRead(TOO_BIG)
                out[name] = data
            }
        }
        return out
    }

    private fun isImageName(name: String): Boolean =
        name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
            name.endsWith(".gif") || name.endsWith(".webp")

    /* A picture tag in a chapter file. The address is relative to that
       file, not the package. Remote pictures are left out — the book has
       to stand on its own once it is in the library. */
    private fun figOf(el: Element, base: String, files: Map<String, ByteArray>): Fig? {
        val src = el.attr("src").trim().ifEmpty {
            el.attr("xlink:href").trim().ifEmpty { el.attr("href").trim() }
        }
        if (src.isEmpty() || src.startsWith("#")) return null
        val alt = el.attr("alt").trim()
        val bytes = when {
            src.startsWith("data:image", ignoreCase = true) -> dataImage(src)
            src.startsWith("http://", ignoreCase = true) ||
                src.startsWith("https://", ignoreCase = true) -> null
            else -> zipFind(files, resolvePath(base, src))
        } ?: return null
        return figBytes(bytes, alt)
    }

    private fun dataImage(src: String): ByteArray? {
        val comma = src.indexOf(',')
        if (comma < 0) return null
        val meta = src.substring(0, comma).lowercase()
        val payload = src.substring(comma + 1)
        return try {
            if ("base64" in meta) java.util.Base64.getMimeDecoder().decode(payload)
            else java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.ISO_8859_1)
        } catch (e: Exception) {
            null
        }
    }

    private fun figBytes(bytes: ByteArray, alt: String): Fig? {
        if (bytes.size < 32 || bytes.size > 6 * 1024 * 1024) return null
        if (!isRaster(bytes)) return null
        return Fig(bytes, alt)
    }

    private fun isRaster(b: ByteArray): Boolean {
        if (b.size < 4) return false
        if (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) return true
        if (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
            b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
        ) return true
        if (b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()) return true
        if (b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
            b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()
        ) return true
        return false
    }

    /* Pictures start at the record the MOBI header names. A record that is
       not a picture is skipped, so recindex stays the count of pictures. */
    private fun palmImages(records: List<ByteArray>, header: ByteArray): List<ByteArray> {
        if (header.size < 112) return emptyList()
        val first = u32(header, 108)
        if (first <= 0 || first >= records.size) return emptyList()
        val out = ArrayList<ByteArray>()
        for (i in first until records.size) {
            val img = sniffImage(records[i]) ?: continue
            if (img.size > 6 * 1024 * 1024) continue
            out.add(img)
        }
        return out
    }

    private fun sniffImage(rec: ByteArray): ByteArray? {
        val limit = minOf(rec.size, 64)
        for (i in 0 until limit) {
            val rest = rec.copyOfRange(i, rec.size)
            if (isRaster(rest)) return rest
        }
        return null
    }

    private fun kindleFig(el: Element, images: List<ByteArray>): Fig? {
        if (images.isEmpty()) return null
        val raw = el.attr("recindex").trim().ifEmpty {
            val src = el.attr("src")
            Regex("""kindle:embed:0*(\d+)""", RegexOption.IGNORE_CASE)
                .find(src)?.groupValues?.get(1).orEmpty()
        }
        val n = raw.toIntOrNull() ?: return null
        if (n < 1 || n > images.size) return null
        return Fig(images[n - 1], el.attr("alt").trim())
    }

    private fun readLimited(input: java.io.InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun zipFind(zip: Map<String, ByteArray>, path: String): ByteArray? {
        zip[path]?.let { return it }
        val want = path.trimStart('/').lowercase()
        return zip.entries.firstOrNull { it.key.lowercase() == want }?.value
    }

    private fun resolvePath(base: String, href: String): String {
        val raw = href.substringBefore('#').substringBefore('?')
        val decoded = try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (e: Exception) {
            raw
        }
        val parts = ArrayList<String>()
        if (base.isNotEmpty()) parts.addAll(base.split('/').filter { it.isNotEmpty() && it != "." })
        for (p in decoded.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }

    private fun firstLocalText(doc: org.jsoup.nodes.Document, local: String): String {
        for (el in doc.allElements) {
            if (el.tagName().substringAfter(':').equals(local, true)) {
                val t = el.text().trim()
                if (t.isNotEmpty()) return t
            }
        }
        return ""
    }

    private fun allLocalText(doc: org.jsoup.nodes.Document, local: String): List<String> {
        val out = ArrayList<String>()
        for (el in doc.allElements) {
            if (el.tagName().substringAfter(':').equals(local, true)) {
                val t = el.text().trim()
                if (t.isNotEmpty()) out.add(t)
            }
        }
        return out
    }

    private fun readCap(input: java.io.InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw CannotRead(TOO_BIG)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun u16(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun isWs(b: Byte) : Boolean {
        val c = b.toInt() and 0xFF
        return c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code || c == 0
    }

    private fun isDelim(b: Byte): Boolean {
        val c = b.toInt() and 0xFF
        return isWs(b) || c == '/'.code || c == '['.code || c == ']'.code ||
            c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
            c == '%'.code || c == '{'.code || c == '}'.code
    }

    private fun indexOf(data: ByteArray, needle: String, from: Int = 0): Int {
        val n = needle.toByteArray(ISO)
        if (n.isEmpty() || from > data.size - n.size) return -1
        var i = from
        while (i <= data.size - n.size) {
            var ok = true
            for (k in n.indices) {
                if (data[i + k] != n[k]) {
                    ok = false
                    break
                }
            }
            if (ok) return i
            i++
        }
        return -1
    }

    private fun lastIndexOf(data: ByteArray, needle: String): Int {
        val n = needle.toByteArray(ISO)
        if (n.isEmpty() || n.size > data.size) return -1
        var i = data.size - n.size
        while (i >= 0) {
            var ok = true
            for (k in n.indices) {
                if (data[i + k] != n[k]) {
                    ok = false
                    break
                }
            }
            if (ok) return i
            i--
        }
        return -1
    }

    private val ISO: Charset = Charsets.ISO_8859_1
    private val WIN: Charset = Charset.forName("windows-1252")

    private val CHAPTER_LABEL = Regex(
        "(?i)^(chapter|chương|chuong|phần|phan|hồi|hoi|part|book|volume|vol\\.?)\\s+(.+)$",
    )
    private val CHAPTER_REST = Regex(
        "(?i)^(\\d+|[ivxlcdm]{1,8})(\\.|(\\s*[:.\\-—–]\\s+\\S.{0,60}))?$",
    )
    private val NAMED_PART = Regex(
        "(?i)^(prologue|epilogue|preface|foreword|afterword|introduction|interlude|appendix)" +
            "(\\s*[:.\\-—–]\\s+\\S.{0,60})?$",
    )
    private val CJK_CHAPTER = Regex("^第\\s*[0-9０-９]+\\s*[章回].{0,40}$")

    private const val MIN_CHAPTER = 20
}
