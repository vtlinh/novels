package dev.vtlinh.noveldownloader

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/* A cover for a book added from a file, rather than downloaded from a site.

   The picture inside the file is that book's cover. An ISBN names one
   edition, and Open Library will return that edition's image. The title
   and author are the fallback, and the hit has to be the same book —
   "Dune" must not receive the cover of "Dune Messiah".

   Open Library answers a missing cover with a tiny blank image and a
   normal 200, so a picture only counts when it is large enough to be one. */
object BookCover {

    data class Identity(val title: String, val author: String, val isbn: String?)

    /* The cover host asked us to slow down. The caller should not record
       "this book has no cover" — the lookup simply didn't finish. */
    class Throttle : Exception()

    enum class QueryKind { IMAGE, OPEN_LIBRARY, GOOGLE }

    data class Query(val url: String, val kind: QueryKind)

    /* Remembered when a lookup finished and found nothing, so opening the
       library does not ask again for a book that has no cover. */
    const val MISS_PREFIX = "coverMiss:"

    /* Imported novels are ordinary library rows. The other half of adding a
       file marks them with this URL (and this slug prefix) so a status check
       does not go looking for them on a site. The same mark says the cover,
       if there is one, has to come from the file or from a search. */
    fun needsLookup(url: String, slug: String, title: String): Boolean =
        usableTitle(title).isNotEmpty() &&
            (url.startsWith("import:") || slug.startsWith("import-"))

    /* Title, author, and ISBN stored in the file. The file name stands in
       for a title only when the file really is one of these formats and
       its own metadata has none — a PDF often carries nothing else. */
    fun identity(bytes: ByteArray, displayName: String): Identity? = try {
        val format = formatOf(bytes, displayName) ?: return null
        val meta = readMeta(bytes, format)
        val title = meta?.title?.takeIf { it.isNotBlank() } ?: fileTitle(displayName).orEmpty()
        val isbn = meta?.isbn
        if (title.isBlank() && isbn == null) null
        else Identity(title, meta?.author.orEmpty(), isbn)
    } catch (e: Exception) {
        null
    }

    /* The cover image embedded in an EPUB or a Kindle file. */
    fun embedded(bytes: ByteArray, displayName: String): ByteArray? = try {
        when (formatOf(bytes, displayName)) {
            Format.EPUB -> epubCover(bytes)
            Format.MOBI -> mobi(bytes)?.cover
            else -> null
        }?.let { imageBytes(it) }?.takeIf { isCoverImage(it) }
    } catch (e: Exception) {
        null
    }

    /* Where to ask, best first. An ISBN is one edition. The title search
       is there for a file that has no ISBN, and as a second try when that
       edition has no picture. */
    fun queries(id: Identity): List<Query> {
        val title = usableTitle(id.title)
        val author = id.author.trim()
        val out = ArrayList<Query>()
        val isbn = id.isbn?.takeIf { validIsbn(it) }
        if (isbn != null) {
            out.add(Query(
                "https://covers.openlibrary.org/b/isbn/$isbn-L.jpg?default=false",
                QueryKind.IMAGE,
            ))
        }
        if (titleSearchable(title, author)) {
            val t = java.net.URLEncoder.encode(title.take(180), "UTF-8")
            val a = if (author.isBlank()) ""
            else "&author=" + java.net.URLEncoder.encode(author.take(120), "UTF-8")
            out.add(Query(
                "https://openlibrary.org/search.json?title=$t$a&fields=title,author_name,cover_i&limit=5",
                QueryKind.OPEN_LIBRARY,
            ))
        }
        if (isbn != null) {
            out.add(Query(
                "https://www.googleapis.com/books/v1/volumes?q=isbn:$isbn&maxResults=5",
                QueryKind.GOOGLE,
            ))
        }
        if (titleSearchable(title, author)) {
            val q = buildString {
                append("intitle:\"")
                append(title.replace("\"", "").take(180))
                append('"')
                if (author.isNotBlank()) {
                    append(" inauthor:\"")
                    append(author.replace("\"", "").take(120))
                    append('"')
                }
            }
            val enc = java.net.URLEncoder.encode(q, "UTF-8")
            out.add(Query(
                "https://www.googleapis.com/books/v1/volumes?q=$enc&maxResults=5",
                QueryKind.GOOGLE,
            ))
        }
        return out
    }

    /* The large cover URL for the one search hit that is this book, or null. */
    fun openLibraryCover(json: String, id: Identity): String? {
        val title = usableTitle(id.title)
        if (!titleSearchable(title, id.author)) return null
        val root = parseJson(json) ?: return null
        val docs = root.arr("docs") ?: return null
        var best: Hit? = null
        for (doc in docs) {
            val o = doc as? J.Obj ?: continue
            val cover = o.lng("cover_i") ?: continue
            if (cover <= 0L || cover > 2_000_000_000L) continue
            val got = o.str("title") ?: continue
            val authors = o.strings("author_name")
            if (!titleOk(title, got) || !authorOk(id.author, authors)) continue
            val hit = Hit(got, cover)
            val current = best
            if (current == null || betterTitle(got, current.title, title)) best = hit
        }
        val idNum = best?.cover ?: return null
        return "https://covers.openlibrary.org/b/id/$idNum-L.jpg?default=false"
    }

    /* Image URLs from a Google Books volumes response, best first. A larger
       picture is requested when the URL is one of their thumbnails, and the
       thumbnail itself stays as a fallback — the larger size sometimes 404s. */
    fun googleCovers(json: String, id: Identity): List<String> {
        val root = parseJson(json) ?: return emptyList()
        val items = root.arr("items") ?: return emptyList()
        val title = usableTitle(id.title)
        val loose = !titleSearchable(title, id.author)
        var bestUrl: String? = null
        var bestRank = Int.MAX_VALUE
        var bestLen = Int.MAX_VALUE
        for (item in items) {
            val info = (item as? J.Obj)?.obj("volumeInfo") ?: continue
            val got = info.str("title") ?: continue
            val authors = info.strings("authors")
            if (!loose && (!titleOk(title, got) || !authorOk(id.author, authors))) continue
            val links = info.obj("imageLinks") ?: continue
            val url = COVER_KEYS.firstNotNullOfOrNull { links.str(it) } ?: continue
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            /* An ISBN search has no title to grade the hits by. The first
               picture is that edition. */
            if (loose) {
                bestUrl = url
                break
            }
            val rank = if (norm(got) == norm(title)) 0 else 1
            if (rank < bestRank || (rank == bestRank && got.length < bestLen)) {
                bestRank = rank
                bestLen = got.length
                bestUrl = url
            }
        }
        return bestUrl?.let { enlarge(it) } ?: emptyList()
    }

    /* JPEG, PNG, GIF, or WEBP, and big enough that it isn't the blank
       placeholder Open Library returns for a missing cover. */
    fun isCoverImage(bytes: ByteArray): Boolean {
        val (w, h) = dimensions(bytes) ?: return false
        if (w < MIN_SIDE || h < MIN_SIDE) return false
        if (w > MAX_SIDE || h > MAX_SIDE) return false
        return true
    }

    /* Embedded picture first. Otherwise the queries, in order. `get` returns
       the response body, or null when that URL has nothing. It may throw
       [Throttle]. */
    fun resolve(id: Identity?, embedded: ByteArray?, get: (String) -> ByteArray?): ByteArray? {
        if (embedded != null && isCoverImage(embedded)) return embedded
        if (id == null) return null
        for (q in queries(id)) {
            val body = get(q.url) ?: continue
            when (q.kind) {
                QueryKind.IMAGE -> if (isCoverImage(body)) return body
                QueryKind.OPEN_LIBRARY -> {
                    val u = openLibraryCover(decode(body), id) ?: continue
                    val pic = get(u) ?: continue
                    if (isCoverImage(pic)) return pic
                }
                QueryKind.GOOGLE -> {
                    for (u in googleCovers(decode(body), id)) {
                        val pic = get(u) ?: continue
                        if (isCoverImage(pic)) return pic
                    }
                }
            }
        }
        return null
    }

    fun isbnOf(raw: String): String? {
        val found = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            val s = sb.toString()
            if ((s.length == 10 || s.length == 13) && validIsbn(s)) found.add(s)
            sb.clear()
        }
        for (c in raw) {
            when {
                c.isDigit() -> sb.append(c)
                (c == 'X' || c == 'x') && sb.length == 9 -> sb.append('X')
                c == '-' || c == ' ' || c == '\u00a0' -> {}
                else -> flush()
            }
        }
        flush()
        return found.firstOrNull { it.length == 13 } ?: found.firstOrNull()
    }

    private data class Hit(val title: String, val cover: Long)

    /* An exact title beats a subtitle match. Two subtitle matches: the
       shorter catalog title is the closer one. */
    private fun betterTitle(got: String, than: String, want: String): Boolean {
        val exact = norm(got) == norm(want)
        val thanExact = norm(than) == norm(want)
        if (exact != thanExact) return exact
        return got.length < than.length
    }

    private enum class Format { EPUB, PDF, MOBI }

    private data class Meta(val title: String, val author: String, val isbn: String?)

    private class MobiBook(
        val title: String,
        val author: String,
        val isbn: String?,
        val cover: ByteArray?,
    )

    private const val MIN_SIDE = 40
    private const val MAX_SIDE = 6000
    private val COVER_KEYS = listOf(
        "extraLarge", "large", "medium", "small", "thumbnail", "smallThumbnail",
    )
    private val GENERIC = setOf(
        "document", "documents", "book", "books", "untitled", "unknown",
        "scan", "file", "download", "downloads",
    )
    private val AUTHOR_SUFFIX = setOf("jr", "sr", "ii", "iii", "iv", "phd", "md")
    private val MOBI_MAGIC = "BOOKMOBI".toByteArray(Charsets.US_ASCII)

    private fun usableTitle(title: String): String {
        val t = title.trim()
        if (t.isEmpty() || norm(t) in GENERIC) return ""
        return t
    }

    /* "It" matches too many books to search on the title alone. With an
       author, an exact title is enough. */
    private fun titleSearchable(title: String, author: String): Boolean {
        val n = norm(title)
        if (n.length >= 4) return true
        return n.length >= 2 && author.isNotBlank()
    }

    private fun formatOf(bytes: ByteArray, name: String): Format? {
        if (bytes.size >= 5 && bytes[0] == '%'.code.toByte() &&
            bytes.copyOfRange(1, 4).contentEquals("PDF".toByteArray())
        ) return Format.PDF
        if (bytes.size > 68 && bytes.copyOfRange(60, 68).contentEquals(MOBI_MAGIC)) return Format.MOBI
        val ext = name.substringAfterLast('/').substringAfterLast('\\')
            .substringAfterLast('.', "").lowercase()
        val zip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (zip && (ext == "epub" || zipHas(bytes, "META-INF/container.xml"))) return Format.EPUB
        return when (ext) {
            "pdf" -> Format.PDF
            "epub" -> Format.EPUB
            "mobi", "azw", "azw3", "prc" -> Format.MOBI
            else -> null
        }
    }

    private fun readMeta(bytes: ByteArray, format: Format): Meta? = when (format) {
        Format.EPUB -> epubMeta(bytes)
        Format.PDF -> pdfMeta(bytes)
        Format.MOBI -> mobi(bytes)?.let { Meta(it.title, it.author, it.isbn) }
    }

    private fun fileTitle(name: String): String? {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val dot = base.lastIndexOf('.')
        val stem = (if (dot > 0) base.substring(0, dot) else base).trim()
        val cleaned = stem.replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim()
        return usableTitle(cleaned).ifEmpty { null }
    }

    /* ---- matching ---- */

    private fun norm(s: String): String {
        val sb = StringBuilder()
        for (c in s.lowercase()) {
            if (c.isLetterOrDigit()) sb.append(c)
            else if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
        }
        return sb.toString().trim()
    }

    /* Equal titles match. A subtitle in parentheses, or after a colon or
       dash, matches. A longer different title does not: "Dune" is not
       "Dune Messiah". */
    private fun titleOk(want: String, got: String): Boolean {
        val w = want.trim()
        val g = got.trim()
        if (w.isEmpty() || g.isEmpty()) return false
        if (w.equals(g, ignoreCase = true) || norm(w) == norm(g)) return true
        if (g.length > w.length && g.startsWith(w, ignoreCase = true) && subtitleAfter(g, w.length)) return true
        if (w.length > g.length && w.startsWith(g, ignoreCase = true) && subtitleAfter(w, g.length)) return true
        return false
    }

    private fun subtitleAfter(longer: String, at: Int): Boolean {
        var i = at
        while (i < longer.length && longer[i].isWhitespace()) i++
        if (i >= longer.length) return true
        return longer[i] == ':' || longer[i] == '(' || longer[i] == '[' ||
            longer[i] == '-' || longer[i] == ',' || longer[i] == '—'
    }

    /* The last real word of the author has to show up in the catalog name.
       "Ursula K. Le Guin" and "Le Guin, Ursula K." both carry "guin". */
    private fun authorOk(want: String, names: List<String>): Boolean {
        if (want.isBlank()) return true
        var tokens = norm(want).split(' ').filter { it.length >= 3 && it !in AUTHOR_SUFFIX }
        if (tokens.isEmpty()) return true
        val last = tokens.last()
        return norm(names.joinToString(" ")).contains(last)
    }

    private fun enlarge(url: String): List<String> {
        var u = url.trim()
        if (u.startsWith("http://")) u = "https://" + u.removePrefix("http://")
        if (!u.startsWith("https://")) return emptyList()
        val bigger = u.replace("zoom=1", "zoom=3").replace("edge=curl", "edge=none")
        return if (bigger == u) listOf(u) else listOf(bigger, u)
    }

    /* ---- EPUB ---- */

    private fun epubMeta(bytes: ByteArray): Meta? {
        val opf = opfBytes(bytes) ?: return null
        val doc = xml(opf) ?: return null
        var title = ""
        var author = ""
        val ids = ArrayList<String>()
        for (el in doc.allElements) {
            when (local(el.tagName())) {
                "title" -> if (title.isBlank()) title = el.text().trim()
                "creator" -> if (author.isBlank()) author = el.text().trim()
                "identifier" -> el.text().trim().takeIf { it.isNotEmpty() }?.let { ids.add(it) }
            }
        }
        val isbn = ids.firstNotNullOfOrNull { isbnOf(it) }
        if (title.isBlank() && isbn == null) return null
        return Meta(title, author, isbn)
    }

    private fun epubCover(bytes: ByteArray): ByteArray? {
        val located = opfLocated(bytes) ?: return null
        val doc = xml(located.second) ?: return null
        val href = coverHref(doc, located.first) ?: return null
        return zipBytes(bytes, href, 8_000_000)
    }

    private fun coverHref(doc: org.jsoup.nodes.Document, opfPath: String): String? {
        var coverId: String? = null
        var fallback: String? = null
        for (el in doc.allElements) {
            when (local(el.tagName())) {
                "meta" -> if (el.attr("name").equals("cover", true)) {
                    coverId = el.attr("content").ifBlank { null }
                }
                "item" -> {
                    val props = el.attr("properties").split(Regex("\\s+"))
                    if ("cover-image" in props) return zipResolve(opfPath, el.attr("href"))
                    if (coverId != null && el.attr("id") == coverId && isImageRef(el.attr("href"), el.attr("media-type"))) {
                        return zipResolve(opfPath, el.attr("href"))
                    }
                    if (fallback == null && isImageRef(el.attr("href"), el.attr("media-type"))) {
                        val id = el.attr("id")
                        val leaf = el.attr("href").substringAfterLast('/')
                        if (id.contains("cover", true) || leaf.contains("cover", true)) {
                            fallback = zipResolve(opfPath, el.attr("href"))
                        }
                    }
                }
                "reference" -> if (el.attr("type").equals("cover", true) && isImageRef(el.attr("href"), "")) {
                    return zipResolve(opfPath, el.attr("href"))
                }
            }
        }
        if (coverId != null) {
            for (el in doc.allElements) {
                if (local(el.tagName()) == "item" && el.attr("id") == coverId &&
                    isImageRef(el.attr("href"), el.attr("media-type"))
                ) {
                    return zipResolve(opfPath, el.attr("href"))
                }
            }
        }
        return fallback
    }

    private fun isImageRef(href: String, media: String): Boolean {
        if (media.startsWith("image/", ignoreCase = true)) return true
        val h = href.substringBefore('?').substringBefore('#').lowercase()
        return h.endsWith(".jpg") || h.endsWith(".jpeg") || h.endsWith(".png") ||
            h.endsWith(".gif") || h.endsWith(".webp")
    }

    private fun opfBytes(bytes: ByteArray): ByteArray? = opfLocated(bytes)?.second

    private fun opfLocated(bytes: ByteArray): Pair<String, ByteArray>? {
        val container = zipBytes(bytes, "META-INF/container.xml", 1_000_000)
        val path = container?.let { opfPath(it) }
        if (path != null) {
            val opf = zipBytes(bytes, path, 2_000_000) ?: return null
            return path to opf
        }
        return zipFirst(bytes, { it.endsWith(".opf", true) }, 2_000_000)
    }

    private fun opfPath(container: ByteArray): String? {
        val doc = xml(container) ?: return null
        for (el in doc.allElements) {
            if (local(el.tagName()) != "rootfile") continue
            val p = el.attr("full-path").ifBlank { el.attr("fullpath") }
            if (p.isNotBlank()) return urlDecode(p).trim().trimStart('/')
        }
        return null
    }

    private fun zipResolve(base: String, href: String): String {
        val h = urlDecode(href).substringBefore('#').substringBefore('?').replace('\\', '/').trim()
        if (h.startsWith("/")) return h.trimStart('/')
        val parts = ArrayList<String>()
        val dir = base.replace('\\', '/').substringBeforeLast('/', "")
        if (dir.isNotEmpty()) parts.addAll(dir.split('/').filter { it.isNotEmpty() })
        for (p in h.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }

    private fun zipBytes(bytes: ByteArray, want: String, limit: Int): ByteArray? =
        zipFirst(bytes, { samePath(it, want) }, limit)?.second

    private fun zipHas(bytes: ByteArray, want: String): Boolean {
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (samePath(e.name, want)) return true
                }
            }
        } catch (e: Exception) {
            return false
        }
        return false
    }

    private fun samePath(entry: String, want: String): Boolean {
        val a = entry.replace('\\', '/').trimStart('/')
        val b = want.replace('\\', '/').trimStart('/')
        return a.equals(b, ignoreCase = true)
    }

    private fun zipFirst(
        bytes: ByteArray,
        want: (String) -> Boolean,
        limit: Int,
    ): Pair<String, ByteArray>? {
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val name = e.name.replace('\\', '/').trimStart('/')
                    if (!e.isDirectory && want(name)) {
                        val data = readCap(z, limit) ?: return null
                        return name to data
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    private fun readCap(input: java.io.InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun xml(bytes: ByteArray): org.jsoup.nodes.Document? = try {
        Jsoup.parse(decode(bytes), "", Parser.xmlParser())
    } catch (e: Exception) {
        null
    }

    private fun local(tag: String) = tag.substringAfter(':').lowercase()

    private fun urlDecode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    /* ---- PDF ---- */

    private fun pdfMeta(bytes: ByteArray): Meta? {
        val xmp = xmpPacket(bytes)
        val title = xmp?.let { xmlText(it, "title") } ?: pdfString(bytes, "Title")
        val author = xmp?.let { xmlText(it, "creator") } ?: pdfString(bytes, "Author") ?: ""
        val isbn = sequence {
            if (xmp != null) {
                xmlText(xmp, "isbn")?.let { yield(it) }
                xmlText(xmp, "identifier")?.let { yield(it) }
            }
            pdfString(bytes, "ISBN")?.let { yield(it) }
        }.firstNotNullOfOrNull { isbnOf(it) }
        if (title.isNullOrBlank() && isbn == null) return null
        return Meta(title.orEmpty(), author, isbn)
    }

    private fun xmpPacket(pdf: ByteArray): String? {
        val marks = listOf("<?xpacket begin".toByteArray(), "<x:xmpmeta".toByteArray())
        var from = 0
        var best: String? = null
        while (from < pdf.size) {
            val s = marks.map { indexOf(pdf, it, from) }.filter { it >= 0 }.minOrNull() ?: break
            val endAt = indexOf(pdf, "<?xpacket end".toByteArray(), s + 12)
            val end = if (endAt >= 0) (endAt + 80).coerceAtMost(pdf.size)
            else (s + 262144).coerceAtMost(pdf.size)
            val text = pdf.copyOfRange(s, end).toString(Charsets.UTF_8)
            if (xmlText(text, "title") != null || xmlText(text, "creator") != null ||
                xmlText(text, "isbn") != null
            ) best = text
            val next = if (endAt >= 0) endAt + 12 else end
            if (next <= from) break
            from = next
        }
        return best
    }

    /* The text of the first element with this local name. An rdf:li inside
       it is the value; the wrapper tags are not. */
    private fun xmlText(xml: String, local: String, depth: Int = 0): String? {
        if (depth > 6 || xml.isEmpty()) return null
        val open = Regex(
            "<(?:[A-Za-z0-9_-]+:)?${Regex.escape(local)}(\\s[^>]*)?>",
            RegexOption.IGNORE_CASE,
        )
        val m = open.find(xml) ?: return null
        val attrs = m.groupValues[1]
        if (attrs.trimEnd().endsWith("/")) return null
        val after = xml.substring(m.range.last + 1)
        val close = Regex(
            "</(?:[A-Za-z0-9_-]+:)?${Regex.escape(local)}\\s*>",
            RegexOption.IGNORE_CASE,
        ).find(after) ?: return null
        val inner = after.substring(0, close.range.first)
        xmlText(inner, "li", depth + 1)?.let { return it }
        val text = xmlDecode(inner.replace(Regex("<[^>]*>"), " ")).replace(Regex("\\s+"), " ").trim()
        return text.ifBlank { null }
    }

    private fun xmlDecode(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] != '&') {
                sb.append(s[i])
                i++
                continue
            }
            val semi = s.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 12) {
                sb.append('&')
                i++
                continue
            }
            val ent = s.substring(i + 1, semi)
            val ch = when (ent) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    val n = when {
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.drop(2).toIntOrNull(16)
                        ent.startsWith("#") -> ent.drop(1).toIntOrNull()
                        else -> null
                    }
                    if (n != null && n > 0 && n <= 0x10FFFF) String(Character.toChars(n)) else null
                }
            }
            if (ch == null) {
                sb.append('&')
                i++
            } else {
                sb.append(ch)
                i = semi + 1
            }
        }
        return sb.toString()
    }

    /* The last /Title (or /Author, /ISBN) in the file. The info dictionary
       sits at the end; a same-named key earlier in a page is not the one. */
    private fun pdfString(pdf: ByteArray, key: String): String? {
        val needle = "/$key".toByteArray(Charsets.US_ASCII)
        var from = 0
        var found: String? = null
        while (from < pdf.size) {
            val i = indexOf(pdf, needle, from)
            if (i < 0) break
            val after = i + needle.size
            if ((i > 0 && isNameChar(pdf[i - 1])) || (after < pdf.size && isNameChar(pdf[after]))) {
                from = after
                continue
            }
            var j = after
            while (j < pdf.size && isPdfSpace(pdf[j])) j++
            val v = when {
                j < pdf.size && pdf[j] == '('.code.toByte() -> pdfLiteral(pdf, j)
                j + 1 < pdf.size && pdf[j] == '<'.code.toByte() && pdf[j + 1] != '<'.code.toByte() ->
                    pdfHex(pdf, j)
                else -> null
            }
            if (v != null) {
                if (v.text.isNotBlank()) found = v.text
                from = v.next
            } else {
                from = after
            }
        }
        return found
    }

    private class PdfVal(val text: String, val next: Int)

    private fun pdfLiteral(pdf: ByteArray, start: Int): PdfVal? {
        val out = ByteArrayOutputStream()
        var i = start + 1
        var depth = 1
        while (i < pdf.size && depth > 0) {
            val c = pdf[i].toInt() and 0xFF
            if (c == '\\'.code) {
                i++
                if (i >= pdf.size) break
                val e = pdf[i].toInt() and 0xFF
                when (e) {
                    'n'.code -> out.write('\n'.code)
                    'r'.code -> out.write('\r'.code)
                    't'.code -> out.write('\t'.code)
                    'b'.code -> out.write(8)
                    'f'.code -> out.write(12)
                    '('.code, ')'.code, '\\'.code -> out.write(e)
                    '\n'.code -> {}
                    '\r'.code -> if (i + 1 < pdf.size && pdf[i + 1] == '\n'.code.toByte()) i++
                    in '0'.code..'7'.code -> {
                        var oct = e - '0'.code
                        var n = 1
                        while (n < 3 && i + 1 < pdf.size) {
                            val d = pdf[i + 1].toInt() and 0xFF
                            if (d !in '0'.code..'7'.code) break
                            i++
                            oct = oct * 8 + (d - '0'.code)
                            n++
                        }
                        out.write(oct and 0xFF)
                    }
                    else -> out.write(e)
                }
            } else if (c == '('.code) {
                depth++
                out.write(c)
            } else if (c == ')'.code) {
                depth--
                if (depth > 0) out.write(c)
            } else {
                out.write(c)
            }
            i++
        }
        if (depth != 0) return null
        return PdfVal(decodePdfBytes(out.toByteArray()), i)
    }

    private fun pdfHex(pdf: ByteArray, start: Int): PdfVal? {
        val nibbles = ArrayList<Int>()
        var i = start + 1
        while (i < pdf.size && pdf[i] != '>'.code.toByte()) {
            val n = hexNibble(pdf[i].toInt() and 0xFF)
            if (n >= 0) nibbles.add(n)
            i++
        }
        if (i >= pdf.size) return null
        if (nibbles.size % 2 == 1) nibbles.add(0)
        val raw = ByteArray(nibbles.size / 2)
        for (k in raw.indices) raw[k] = ((nibbles[k * 2] shl 4) or nibbles[k * 2 + 1]).toByte()
        return PdfVal(decodePdfBytes(raw), i + 1)
    }

    private fun decodePdfBytes(b: ByteArray): String {
        val text = when {
            b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() ->
                String(b, 2, b.size - 2, Charsets.UTF_16BE)
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() ->
                String(b, 2, b.size - 2, Charsets.UTF_16LE)
            else -> {
                val utf = String(b, Charsets.UTF_8)
                if (utf.toByteArray(Charsets.UTF_8).contentEquals(b)) utf
                else String(b, Charsets.ISO_8859_1)
            }
        }
        return text.trim { it <= ' ' || it == '\u0000' }
    }

    private fun isPdfSpace(b: Byte): Boolean {
        val c = b.toInt() and 0xFF
        return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
    }

    private fun isNameChar(b: Byte): Boolean {
        val c = b.toInt() and 0xFF
        if (isPdfSpace(b)) return false
        return c != '('.code && c != ')'.code && c != '<'.code && c != '>'.code &&
            c != '['.code && c != ']'.code && c != '{'.code && c != '}'.code &&
            c != '/'.code && c != '%'.code
    }

    private fun hexNibble(c: Int): Int = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        else -> -1
    }

    /* ---- Kindle (MOBI / AZW / AZW3) ----

       Record 0 holds a 16-byte PalmDOC header, then "MOBI". The offsets
       below are from the start of that record, which is how the file is
       laid out: the full name is at byte 84, the first image record number
       at byte 108. EXTH, when present, begins at 16 plus the MOBI header
       length. Cover offset 201 is added to the first image record number
       to get the record that holds the picture. The text may be encrypted;
       the header and the image records are not, so the cover is still readable. */

    private fun mobi(bytes: ByteArray): MobiBook? {
        if (bytes.size < 78 || !bytes.copyOfRange(60, 68).contentEquals(MOBI_MAGIC)) return null
        val n = u16(bytes, 76)
        if (n <= 0 || 78L + n.toLong() * 8L > bytes.size) return null
        val offsets = IntArray(n)
        var p = 78
        for (i in 0 until n) {
            val off = u32(bytes, p)
            if (off < 0 || off > bytes.size) return null
            offsets[i] = off.toInt()
            p += 8
        }
        val rec = record(bytes, offsets, 0) ?: return null
        if (rec.size < 20 || !rec.copyOfRange(16, 20).contentEquals("MOBI".toByteArray())) {
            val pdb = pdbName(bytes)
            if (pdb.isBlank()) return null
            return MobiBook(pdb, "", null, null)
        }
        val headerLen = u32(rec, 20)
        val encoding = if (rec.size >= 32) u32(rec, 28).toInt() else 1252
        var title = pdbName(bytes)
        if (rec.size >= 92) {
            val nameAt = u32(rec, 84)
            val nameLen = u32(rec, 88)
            if (nameAt >= 0 && nameLen > 0 && nameAt + nameLen <= rec.size.toLong() &&
                nameAt + nameLen <= Int.MAX_VALUE
            ) {
                val full = decodeMobi(
                    rec.copyOfRange(nameAt.toInt(), (nameAt + nameLen).toInt()),
                    encoding,
                )
                if (full.isNotEmpty()) title = full
            }
        }
        var author = ""
        var isbn: String? = null
        var coverOff = -1
        var thumbOff = -1
        val authors = ArrayList<String>()
        if (headerLen > 0L && headerLen < rec.size && 16L + headerLen + 12L <= rec.size.toLong()) {
            val exth = (16L + headerLen).toInt()
            if (exth >= 0 && exth + 12 <= rec.size &&
                rec.copyOfRange(exth, exth + 4).contentEquals("EXTH".toByteArray())
            ) {
                val exthLen = u32(rec, exth + 4)
                val count = u32(rec, exth + 8)
                val end = if (exthLen > 12L) {
                    minOf(rec.size.toLong(), exth.toLong() + exthLen).toInt()
                } else rec.size
                var curr = exth + 12
                var seen = 0
                val cap = if (count in 0L..512L) count.toInt() else 512
                while (seen < cap && curr + 8 <= end) {
                    val tag = u32(rec, curr).toInt()
                    val len = u32(rec, curr + 4)
                    if (len < 8L || len > Int.MAX_VALUE || curr.toLong() + len > end.toLong()) break
                    val data = rec.copyOfRange(curr + 8, curr + len.toInt())
                    when (tag) {
                        100 -> {
                            val s = decodeMobi(data, encoding)
                            if (s.isNotEmpty()) authors.add(s)
                        }
                        104 -> if (isbn == null) isbn = isbnOf(decodeMobi(data, encoding))
                        503 -> {
                            val s = decodeMobi(data, encoding)
                            if (s.isNotEmpty()) title = s
                        }
                        201 -> if (data.size >= 4) coverOff = u32(data, 0).toInt()
                        202 -> if (data.size >= 4) thumbOff = u32(data, 0).toInt()
                    }
                    curr += len.toInt()
                    seen++
                }
                if (authors.isNotEmpty()) author = authors.joinToString(", ")
            }
        }
        var firstImage = if (rec.size >= 112) u32(rec, 108).toInt() else -1
        if (firstImage <= 0 || firstImage >= n) firstImage = -1
        val cover = if (firstImage > 0) {
            val index = if (coverOff >= 0) firstImage.toLong() + coverOff else firstImage.toLong()
            pictureAt(bytes, offsets, n, index)
                ?: if (thumbOff >= 0) pictureAt(bytes, offsets, n, firstImage.toLong() + thumbOff) else null
        } else null
        if (title.isBlank() && isbn == null && cover == null) return null
        return MobiBook(title, author, isbn, cover)
    }

    private fun pictureAt(bytes: ByteArray, offsets: IntArray, n: Int, index: Long): ByteArray? {
        if (index < 0L || index >= n) return null
        val raw = record(bytes, offsets, index.toInt()) ?: return null
        return imageBytes(raw)?.takeIf { isCoverImage(it) }
    }

    private fun pdbName(bytes: ByteArray): String {
        var end = 0
        while (end < 32 && end < bytes.size && bytes[end] != 0.toByte()) end++
        if (end == 0) return ""
        return decodeMobi(bytes.copyOfRange(0, end), 65001).replace('_', ' ').trim()
    }

    private fun decodeMobi(b: ByteArray, encoding: Int): String {
        val cs: Charset = if (encoding == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
        return String(b, cs).trim { it <= ' ' || it == '\u0000' }
    }

    private fun record(bytes: ByteArray, offsets: IntArray, index: Int): ByteArray? {
        if (index < 0 || index >= offsets.size) return null
        val start = offsets[index]
        val end = if (index + 1 < offsets.size) offsets[index + 1] else bytes.size
        if (start < 0 || end > bytes.size || start >= end) return null
        if (end - start > 8_000_000) return null
        return bytes.copyOfRange(start, end)
    }

    /* ---- images ---- */

    private fun imageBytes(record: ByteArray): ByteArray? {
        val limit = minOf(record.size, 512)
        var i = 0
        while (i + 8 < limit) {
            val jpeg = record[i] == 0xFF.toByte() && record[i + 1] == 0xD8.toByte() && record[i + 2] == 0xFF.toByte()
            val png = record[i] == 0x89.toByte() && record[i + 1] == 'P'.code.toByte() &&
                record[i + 2] == 'N'.code.toByte() && record[i + 3] == 'G'.code.toByte()
            val gif = record[i] == 'G'.code.toByte() && record[i + 1] == 'I'.code.toByte() &&
                record[i + 2] == 'F'.code.toByte() && record[i + 3] == '8'.code.toByte()
            val webp = i + 12 < record.size &&
                record.copyOfRange(i, i + 4).contentEquals("RIFF".toByteArray()) &&
                record.copyOfRange(i + 8, i + 12).contentEquals("WEBP".toByteArray())
            if (jpeg || png || gif || webp) {
                return if (i == 0) record else record.copyOfRange(i, record.size)
            }
            i++
        }
        return null
    }

    private fun dimensions(b: ByteArray): Pair<Int, Int>? {
        if (b.size >= 24 && b[0] == 0x89.toByte() && b.copyOfRange(1, 4).contentEquals("PNG".toByteArray())) {
            return u32(b, 16).toInt() to u32(b, 20).toInt()
        }
        if (b.size >= 10 && b.copyOfRange(0, 4).contentEquals("GIF8".toByteArray())) {
            val w = (b[6].toInt() and 0xFF) or ((b[7].toInt() and 0xFF) shl 8)
            val h = (b[8].toInt() and 0xFF) or ((b[9].toInt() and 0xFF) shl 8)
            return w to h
        }
        if (b.size >= 30 && b.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            b.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) &&
            b.copyOfRange(12, 16).contentEquals("VP8X".toByteArray())
        ) {
            val w = 1 + (b[24].toInt() and 0xFF) + ((b[25].toInt() and 0xFF) shl 8) + ((b[26].toInt() and 0xFF) shl 16)
            val h = 1 + (b[27].toInt() and 0xFF) + ((b[28].toInt() and 0xFF) shl 8) + ((b[29].toInt() and 0xFF) shl 16)
            return w to h
        }
        return jpegSize(b)
    }

    private fun jpegSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 3 < b.size) {
            if (b[i] != 0xFF.toByte()) return null
            while (i < b.size && b[i] == 0xFF.toByte()) i++
            if (i >= b.size) return null
            val marker = b[i].toInt() and 0xFF
            i++
            if (marker == 0xD9 || marker == 0xDA) return null
            if (marker == 0x01 || marker in 0xD0..0xD7) continue
            if (i + 1 >= b.size) return null
            val len = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            if (len < 2 || i.toLong() + len.toLong() > b.size) return null
            val sof = marker == 0xC0 || marker == 0xC1 || marker == 0xC2 || marker == 0xC3
            if (sof && len >= 7) {
                val h = ((b[i + 3].toInt() and 0xFF) shl 8) or (b[i + 4].toInt() and 0xFF)
                val w = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                return w to h
            }
            i += len
        }
        return null
    }

    /* ---- JSON, just enough for the two cover responses ---- */

    private sealed interface J {
        data class Obj(val map: Map<String, J>) : J
        data class Arr(val list: List<J>) : J
        data class Str(val s: String) : J
        data class Num(val n: Long) : J
        data object Yes : J
        data object No : J
        data object Nil : J
    }

    private fun J.obj(key: String): J.Obj? = (this as? J.Obj)?.map?.get(key) as? J.Obj
    private fun J.arr(key: String): List<J>? = ((this as? J.Obj)?.map?.get(key) as? J.Arr)?.list
    private fun J.str(key: String): String? = ((this as? J.Obj)?.map?.get(key) as? J.Str)?.s
    private fun J.lng(key: String): Long? = when (val v = (this as? J.Obj)?.map?.get(key)) {
        is J.Num -> v.n
        is J.Str -> v.s.toLongOrNull()
        else -> null
    }
    private fun J.Obj.strings(key: String): List<String> = when (val v = map[key]) {
        is J.Arr -> v.list.mapNotNull { (it as? J.Str)?.s }
        is J.Str -> listOf(v.s)
        else -> emptyList()
    }

    private fun parseJson(s: String): J? {
        if (s.length > 2_000_000) return null
        return try {
            Js(s).parse()
        } catch (e: Exception) {
            null
        }
    }

    private class Js(val s: String) {
        var i = 0
        var depth = 0
        fun parse(): J {
            val v = value()
            return v
        }
        fun skip() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
        fun value(): J {
            skip()
            if (i >= s.length) fail()
            if (depth > 30) fail()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> J.Str(str())
                't' -> lit("true", J.Yes)
                'f' -> lit("false", J.No)
                'n' -> lit("null", J.Nil)
                else -> num()
            }
        }
        fun obj(): J.Obj {
            depth++
            i++
            val map = LinkedHashMap<String, J>()
            skip()
            if (i < s.length && s[i] == '}') {
                i++
                depth--
                return J.Obj(map)
            }
            while (i < s.length) {
                skip()
                if (i >= s.length || s[i] != '"') fail()
                val key = str()
                skip()
                if (i >= s.length || s[i] != ':') fail()
                i++
                map[key] = value()
                skip()
                if (i >= s.length) fail()
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        depth--
                        return J.Obj(map)
                    }
                    else -> fail()
                }
            }
            fail()
        }
        fun arr(): J.Arr {
            depth++
            i++
            val list = ArrayList<J>()
            skip()
            if (i < s.length && s[i] == ']') {
                i++
                depth--
                return J.Arr(list)
            }
            while (i < s.length) {
                list.add(value())
                skip()
                if (i >= s.length) fail()
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        depth--
                        return J.Arr(list)
                    }
                    else -> fail()
                }
            }
            fail()
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (c == '"') {
                    i++
                    return sb.toString()
                }
                if (c == '\\') {
                    i++
                    if (i >= s.length) fail()
                    when (val e = s[i]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 >= s.length) fail()
                            val hex = s.substring(i + 1, i + 5)
                            val n = hex.toIntOrNull(16) ?: fail()
                            sb.append(n.toChar())
                            i += 4
                        }
                        else -> fail()
                    }
                } else {
                    sb.append(c)
                }
                i++
            }
            fail()
        }
        fun lit(word: String, v: J): J {
            if (!s.startsWith(word, i)) fail()
            i += word.length
            return v
        }
        fun num(): J.Num {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length || !s[i].isDigit()) fail()
            while (i < s.length && s[i].isDigit()) i++
            val whole = s.substring(start, i)
            if (i < s.length && s[i] == '.') {
                i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            return J.Num(whole.toLongOrNull() ?: 0L)
        }
        fun fail(): Nothing = throw IllegalArgumentException("json")
    }

    private fun decode(b: ByteArray): String =
        b.toString(Charsets.UTF_8).trimStart { it.isWhitespace() || it == '\uFEFF' }

    private fun validIsbn(s: String): Boolean = when (s.length) {
        13 -> s.all { it.isDigit() } && (s.startsWith("978") || s.startsWith("979")) && isbn13(s)
        10 -> s.take(9).all { it.isDigit() } && (s[9].isDigit() || s[9] == 'X') && isbn10(s)
        else -> false
    }

    private fun isbn13(s: String): Boolean {
        var sum = 0
        for (i in 0 until 12) sum += (s[i] - '0') * (if (i % 2 == 0) 1 else 3)
        val check = (10 - (sum % 10)) % 10
        return check == s[12] - '0'
    }

    private fun isbn10(s: String): Boolean {
        var sum = 0
        for (i in 0 until 9) sum += (s[i] - '0') * (10 - i)
        val last = if (s[9] == 'X') 10 else s[9] - '0'
        return (sum + last) % 11 == 0
    }

    private fun u16(b: ByteArray, i: Int): Int {
        if (i < 0 || i + 1 >= b.size) return -1
        return ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    }

    private fun u32(b: ByteArray, i: Int): Long {
        if (i < 0 || i + 3 >= b.size) return -1
        return ((b[i].toLong() and 0xFF) shl 24) or
            ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or
            (b[i + 3].toLong() and 0xFF)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || from < 0) return -1
        val last = hay.size - needle.size
        var i = from
        while (i <= last) {
            var ok = true
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) {
                    ok = false
                    break
                }
            }
            if (ok) return i
            i++
        }
        return -1
    }
}
