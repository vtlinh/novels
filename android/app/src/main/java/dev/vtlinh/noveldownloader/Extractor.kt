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
    private val AD_RE = Regex(
        "(CLICK\\s*ADS|quảng cáo|mở khóa|truyenfull|đọc truyện online|ủng hộ dịch giả|Vui lòng|Mời bạn|novelfull|find any errors|broken links|non-standard content|report chapter|please report)",
        RegexOption.IGNORE_CASE,
    )
    private val JUNK_CLASS_RE = Regex("(^|[^a-z])(ads?|banner|notice|lock(ed)?|unlock)([^a-z]|$)", RegexOption.IGNORE_CASE)

    /* A "you are reading at <site>" stamp spliced INTO a sentence rather than
       given a line of its own:

         …không có chút sáng sủa nào cả. Bạn đang đọc truyện tại -
         http://truyenfull.com Cuối cùng Huân Nhi lên tràng…

       The line filter below cannot touch this: the paragraph it sits in is
       real prose and well over the 200-character cut, so the choice there is
       between keeping the advert and deleting the paragraph around it. Cut
       out exactly the stamp — the phrase and the url it points at — and leave
       the sentence it interrupted. Found in the truyenfullmoi corpus, on a
       chapter of a novel the other sites also carry without it. */
    private val INLINE_JUNK_RE = Regex(
        "(bạn đang đọc truyện tại|nguồn truyện|đọc truyện tại)\\s*[-–:]?\\s*(https?://)?[\\w.-]+\\.(com|net|vn|org|info)\\S*",
        RegexOption.IGNORE_CASE,
    )

    /* The SEO keyword blob these sites park at the end of a chapter — a
       labelled list of every rival site's name, 300 characters of it, inside
       the chapter container. Too long for the 200-character rule above, and
       it is neither prose nor a heading: TTS reads it aloud and a translation
       pass pays the API to translate it. Matched on its own label at the
       start of the line, so a paragraph that merely mentions the words is
       untouched. */
    private val KEYWORD_LINE_RE = Regex("^(từ khóa tìm kiếm|tu khoa tim kiem)\\s*:", RegexOption.IGNORE_CASE)
    /* The fractional part is consumed but not captured. Without it "(\d+)"
       stopped at the decimal point and the separator is optional, so the rest
       of the number fell into the TITLE: "Chapter 1.5: Side Story" parsed as
       number 1 titled ".5: Side Story", and the rewritten heading then read
       "Chapter 1: .5: Side Story" — with the real one already stripped from
       the body, so the original was gone rather than merely duplicated. */
    private val HEADING_RE =
        Regex("(?:Ch[ưu]ơ?ng|Chapter)\\s*(\\d+)(?:\\.\\d+)?\\s*[:\\-–]?\\s*(.*)", RegexOption.IGNORE_CASE)
    private val HEADING_ONLY_RE = Regex("^(?:Ch[ưu]ơ?ng|Chapter)\\s*\\d+$", RegexOption.IGNORE_CASE)
    private val HEADING_START_RE = Regex("^(?:Ch[ưu]ơ?ng|Chapter)\\s*\\d+", RegexOption.IGNORE_CASE)
    /* a page can separate two headings with spaces rather than a break */
    private val SPACE_RUN_RE = Regex("\\s{2,}")

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
    /* the common named entities, decoded by hand so the fix never depends on
       parser behavior; "&amp;" last so "&amp;gt;" needs the next round */
    private fun decodeCommon(s: String): String = s
        .replace("&nbsp;", " ").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&#039;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&ldquo;", "\u201C").replace("&rdquo;", "\u201D")
        .replace("&lsquo;", "\u2018").replace("&rsquo;", "\u2019")
        .replace("&hellip;", "\u2026").replace("&mdash;", "\u2014").replace("&ndash;", "\u2013")
        .replace("&amp;", "&")

    fun cleanEncoding(s: String): String {
        var t = s
        var i = 0
        while (i++ < 4 && ENTITY_RE.containsMatchIn(t)) {
            var u = decodeCommon(t)
            /* anything the manual table misses (numeric refs, rarer names) */
            if (ENTITY_RE.containsMatchIn(u)) {
                u = try { org.jsoup.parser.Parser.unescapeEntities(u, false) } catch (e: Exception) { u }
            }
            if (u == t) break
            t = u
        }
        return t.replace('\u00A0', ' ')
            .replace(Regex("[\\u200B\\u200C\\u200D\\uFEFF]"), "")
            .replace('\u2028', '\n').replace('\u2029', '\n')
    }

    /* returns (cleaned text, raw text before filtering) */
    private fun extractContent(doc: Document, site: Site): Pair<String, String> {
        /* WHICH element holds the prose is the site's question. This was one
           combined list of every site's selectors, so a selector added for one
           site changed what the others extracted — and the first match won,
           which made that ordering load-bearing across sites that never knew
           about each other. */
        var div: Element? = site.chapterContent(doc)
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
            /* Squeeze the gap the cut leaves, and ONLY on a line the cut
               touched. Collapsing every run of spaces here instead destroys
               the one marker the heading strip below has to work with: a page
               that prints its heading twice inside a single line separates
               the two with a run of spaces and nothing else, and with the run
               gone the duplicate went out in the file. */
            .map {
                if (!INLINE_JUNK_RE.containsMatchIn(it)) it.trim()
                else INLINE_JUNK_RE.replace(it, " ").replace(SPACE_RUN_RE, " ").trim()
            }
            .filter {
                it.isNotEmpty() && !KEYWORD_LINE_RE.containsMatchIn(it) &&
                    !(it.length < 200 && AD_RE.containsMatchIn(it))
            }
            .joinToString("\n")
        return Pair(text, raw)
    }

    /* parse a fetched chapter page into the saved file body */
    fun parseChapter(doc: Document, linkText: String, num: Int, site: Site): String {
        val headingWord = site.headingWord
        val headEl = site.chapterHeading(doc)
        val head = parseHeading(headEl?.text()?.trim() ?: linkText)
        var title = head.second.ifEmpty { parseHeading(linkText).second }
        val extracted = extractContent(doc, site)
        var content = extracted.first
        val raw = extracted.second

        /* Take the leading heading off the body, however the page laid it
           out. Measured across every captured chapter page, three shapes
           occur: one heading line (most), the heading on two consecutive
           LINES, and the heading twice inside a SINGLE line with only a run
           of spaces between — so it is not a line at all and no line-based
           rule reaches it.

           Cutting at the first run of two-plus spaces handles all three, and
           everything after the cut is kept verbatim rather than rejoined, so
           a page this does not fire on is untouched. Bounded: past a couple
           of repeats it is the chapter, not furniture.

           What identifies a line as furniture is that it echoes the PAGE's
           heading, not that it agrees with `num` — on a renumbered novel the
           number the app was told to use is not the site's. */
        var stripped = 0
        while (stripped++ < 4) {
            val nl = content.indexOf('\n')
            val line = if (nl == -1) content else content.substring(0, nl)
            val rest = if (nl == -1) "" else content.substring(nl + 1)
            val cut = SPACE_RUN_RE.find(line)
            val lead = (if (cut != null) line.substring(0, cut.range.first) else line).trim()
            if (lead.length >= 120 || !HEADING_START_RE.containsMatchIn(lead)) break
            val fh = parseHeading(lead)
            if (fh.first != num && !(fh.first != null && fh.first == head.first)) break
            if (title.isEmpty() && fh.second.isNotEmpty()) title = fh.second
            val tail = if (cut != null) line.substring(cut.range.last + 1) else ""
            content = when {
                tail.isNotBlank() && rest.isNotEmpty() -> "$tail\n$rest"
                tail.isNotBlank() -> tail
                else -> rest
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

    /* Does a page we FOUND name the novel we were looking for?

       The status sweep does not only ask the host it has on record — it also
       guesses "https://<other host>/<slug>/", which is how a novel whose site
       moved between .today and .live gets found again. But a slug is not an
       identity: a folder-scanned novel's slug is derived from its folder
       NAME, the sites disambiguate same-titled books with a numeric suffix,
       and any transient failure of the recorded host promotes the guesses. So
       whatever answers has to say it is the same book before the check acts
       on it — the sweep renames files by listing position and rewrites the
       novel's URL, order and totals.

       Diacritic- and punctuation-insensitive, and the translated folder form
       "English (Vietnamese)" matches on either part, since which one a site
       prints depends on the site. */
    fun sameNovelTitle(pageTitle: String, expected: String): Boolean {
        val p = nameKey(pageTitle)
        if (p.isEmpty()) return false
        val parts = ArrayList<String>()
        parts.add(expected)
        Regex("^(.*)\\(([^)]*)\\)\\s*$").find(expected.trim())?.let {
            parts.add(it.groupValues[1])
            parts.add(it.groupValues[2])
        }
        return parts.any { nameKey(it) == p }
    }

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
            .replace(Regex("\\s+"), " ").trim()
            /* Truncate BEFORE trimming the tail, not after: cutting at 180
               could put a '.' or ' ' back on the end, and a FAT/exFAT provider
               silently drops those when creating the directory — so the name
               we looked for never matched the one on disk and every run made
               a fresh "(1)" folder and re-downloaded the whole novel. */
            .take(180).trimEnd('.', ' ')

    /* The folder to save a novel in. sanitize() can legitimately return "" —
       a title that is all CJK, or literally "...", reduces to nothing — and
       an empty name is one the provider refuses, so the download died with
       "could not create folder" and no way for the user to intervene, since
       the name is derived rather than entered. */
    fun folderName(title: String, slug: String): String =
        sanitize(title).ifEmpty { sanitize(slug) }.ifEmpty { "novel" }
}
