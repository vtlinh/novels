package dev.vtlinh.noveldownloader

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.text.Normalizer

/* Port of the web app's chapter extraction, including the fixes learned
   there: junk classes matched at word boundaries (wp-block-paragraph is NOT
   a lock notice), single-newline output, heading dedup, and quoting the raw
   content when extraction comes up empty. */
object Extractor {
    private val CONTENT_SELECTORS = listOf(
        "#chapter-c", ".chapter-c", "#chapter-content",
        ".chapter-content", "div[itemprop=articleBody]", ".box-chap",
    )
    private val AD_RE = Regex(
        "(CLICK\\s*ADS|quảng cáo|mở khóa|truyenfull|đọc truyện online|ủng hộ dịch giả|Vui lòng|Mời bạn|novelfull|find any errors|broken links|non-standard content|report chapter|please report)",
        RegexOption.IGNORE_CASE,
    )
    private val JUNK_CLASS_RE = Regex("(^|[^a-z])(ads?|banner|notice|lock(ed)?|unlock)([^a-z]|$)", RegexOption.IGNORE_CASE)
    private val HEADING_RE = Regex("(?:Ch[ưu]ơ?ng|Chapter)\\s*(\\d+)\\s*[:\\-–]?\\s*(.*)", RegexOption.IGNORE_CASE)
    private val HEADING_ONLY_RE = Regex("^(?:Ch[ưu]ơ?ng|Chapter)\\s*\\d+$", RegexOption.IGNORE_CASE)
    private val HEADING_START_RE = Regex("^(?:Ch[ưu]ơ?ng|Chapter)\\s*\\d+", RegexOption.IGNORE_CASE)

    fun parseHeading(text: String): Pair<Int?, String> {
        val m = HEADING_RE.find(text) ?: return Pair(null, text.trim())
        var title = m.groupValues[2].trim()
        if (HEADING_ONLY_RE.matches(title)) title = ""
        return Pair(m.groupValues[1].toIntOrNull(), title)
    }

    fun singleNewlines(s: String): String =
        s.replace("\r", "").replace(Regex("\n{2,}"), "\n").trim('\n')

    private val ENTITY_RE = Regex("&(#\\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);")

    /* Fix encoding leftovers in extracted text: HTML entities that survive
       Jsoup's one decode because the site double-encoded them ("&amp;gt;"
       decodes to "&gt;" and was saved literally as "-&gt;"), plus
       non-breaking spaces, zero-width characters, and Unicode line
       separators. Decoding repeats until stable (max 3 rounds). */
    fun cleanEncoding(s: String): String {
        var t = s
        var i = 0
        while (i++ < 3 && ENTITY_RE.containsMatchIn(t)) {
            val u = org.jsoup.parser.Parser.unescapeEntities(t, false)
            if (u == t) break
            t = u
        }
        return t.replace('\u00A0', ' ')
            .replace(Regex("[\\u200B\\u200C\\u200D\\uFEFF]"), "")
            .replace('\u2028', '\n').replace('\u2029', '\n')
    }

    /* returns (cleaned text, raw text before filtering) */
    private fun extractContent(doc: Document): Pair<String, String> {
        var div: Element? = null
        for (sel in CONTENT_SELECTORS) {
            div = doc.selectFirst(sel)
            if (div != null) break
        }
        if (div == null) {
            doc.select("script,style,nav,header,footer,form").remove()
            div = doc.select("div").maxByOrNull { it.text().length }
            if (div == null) return Pair("", "")
        }
        div.select("script,style,ins,iframe,button").remove()
        val raw = div.text().replace(Regex("\\s+"), " ").trim()
        for (el in div.select("[class*=ads],[class*=banner],[class*=lock],[class*=notice]")) {
            if (JUNK_CLASS_RE.containsMatchIn(el.attr("class"))) el.remove()
        }
        for (br in div.select("br")) br.replaceWith(TextNode("\n"))
        for (p in div.select("p")) {
            p.prependChild(TextNode("\n"))
            p.appendChild(TextNode("\n"))
        }
        val text = div.wholeText().split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !(it.length < 200 && AD_RE.containsMatchIn(it)) }
            .joinToString("\n")
        return Pair(text, raw)
    }

    /* parse a fetched chapter page into the saved file body */
    fun parseChapter(doc: Document, linkText: String, num: Int, headingWord: String): String {
        val headEl = doc.selectFirst("a.chapter-title, h2 a, h3 a, .chapter-title, .chapter-text")
        val head = parseHeading(headEl?.text()?.trim() ?: linkText)
        var title = head.second.ifEmpty { parseHeading(linkText).second }
        val extracted = extractContent(doc)
        var content = extracted.first
        val raw = extracted.second

        val nl = content.indexOf('\n')
        val firstLine = (if (nl == -1) content else content.substring(0, nl)).trim()
        if (firstLine.length < 120 && HEADING_START_RE.containsMatchIn(firstLine)) {
            val fh = parseHeading(firstLine)
            if (fh.first == num) {
                if (title.isEmpty() && fh.second.isNotEmpty()) title = fh.second
                content = if (nl == -1) "" else content.substring(nl).trimStart('\n')
            }
        }
        if (content.isBlank()) {
            throw RuntimeException(
                if (raw.isNotEmpty()) "empty content after filtering — page says: \"${raw.take(150)}\""
                else "empty content — the chapter area has no text",
            )
        }
        val heading = if (title.isNotEmpty()) "$headingWord $num: $title" else "$headingWord $num"
        return singleNewlines(cleanEncoding("$heading\n$content"))
    }

    /* diacritic-insensitive key for name comparison */
    private fun nameKey(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('đ', 'd').replace('Đ', 'D')
            .lowercase().replace(Regex("[^a-z0-9]+"), "")

    private fun stripAuthorPlain(title: String, author: String): String {
        val m = Regex("^(.*?)\\s*[-–—]\\s*([^-–—]+)$").find(title.trim()) ?: return title.trim()
        return if (nameKey(m.groupValues[2]) == nameKey(author)) m.groupValues[1].trim() else title.trim()
    }

    /* Sites often append the author to the title ("Cối Xay Gió Màu Xanh -
       Mộng Tiêu Nhị"); drop that suffix (diacritic-insensitively) so folder
       names and the novel list show the bare title. Handles the translated
       "English (Vietnamese - Author)" folder format too. */
    fun stripAuthor(title: String, author: String?): String {
        if (author.isNullOrBlank() || nameKey(author).isEmpty()) return title
        val t = title.trim()
        val paren = Regex("^(.*)\\(([^)]*)\\)\\s*$").find(t)
        if (paren != null) {
            val inner = stripAuthorPlain(paren.groupValues[2], author)
            val outer = stripAuthorPlain(paren.groupValues[1], author)
            return if (inner != paren.groupValues[2].trim() || outer != paren.groupValues[1].trim()) {
                "$outer ($inner)"
            } else t
        }
        return stripAuthorPlain(t, author)
    }

    /* fold folder names to plain ASCII, same rules as the web app */
    fun sanitize(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('đ', 'd').replace('Đ', 'D')
            .replace(":", " -").replace(Regex("[/\\\\|]"), "-")
            .replace("\"", "'").replace("<", "(").replace(">", ")")
            .replace(Regex("[?*]"), "")
            .replace(Regex("[^\\x20-\\x7E]"), " ")
            .replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ')
            .take(180)
}
