package dev.vtlinh.noveldownloader.sites

import dev.vtlinh.noveldownloader.Site
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/* vivutruyen2.net — Vietnamese, a short-story catalogue on WordPress.

   THE SHAPE. This host publishes short stories: ~17,900 novels in its post
   sitemaps against ~110,000 chapters in its chapter sitemaps, and across the
   captured corpus every novel runs 4 to 8 chapters, ALL of them listed on
   the novel's own page — no chapter list paginates anywhere on the site,
   which is why maxPage answers 1 without looking. The catalogue is also
   almost entirely finished work: 114 of the 116 captured pages say
   "Hoàn thành", one "Đang ra", one "Tạm ngưng".

   THE ORDER. The chapter list runs NEWEST-FIRST — "Chương 5" at the top,
   "Chương 1" at the bottom, on all 116 captured pages. Positions name files,
   so document order here is the novel backwards; listDescending is what has
   Listing.collect flip it.

   URLS. A novel is "/<slug>/" and its chapters "/<slug>/chuong-N/" — mostly.
   The corpus holds three other shapes, all real: 9 novels suffix every
   chapter with the novel's own slug ("/dieu-bi-an/chuong-1-dieu-bi-an/"),
   one prefixes chapters 3-5 as "full-chuong-N", and that same novel files
   its chapter 1 at "/<slug>/full/" — a url that says nothing about being a
   chapter at all, which is why the in-list rule trusts the list rather than
   the url. www. and plain http both 301 to the https apex (measured). */
object Vivutruyen2 : Site {

    override val name = "vivutruyen2"
    override val headingWord = "Chương"

    /* the chapter tab of the novel page's tab strip. Every captured page
       holds exactly two elements of this class — this list and the comments
       tab beside it — and the list is first in document order on all 116. */
    override val listScope = ".list"

    override val hosts = listOf("vivutruyen2.net")

    /* No novel on this host paginates its chapter list — the whole catalogue
       tops out around eight chapters and every captured page lists all of
       them on the novel page itself. */
    override val paginates = false

    /* "Chương 5" first, "Chương 1" last, on every captured page */
    override val listDescending = true

    private val urlRe = Regex("^https://(www\\.)?vivutruyen2\\.net(/|$)", RegexOption.IGNORE_CASE)

    override fun matches(url: String) = urlRe.containsMatchIn(url.trim())

    /* "/<slug>/" — the first path segment names the novel, the same shape as
       truyenfull. A chapter url resolves to its novel by dropping its own
       segment. */
    override fun normalize(url: String): Pair<String, String> {
        val u = URI(url.trim())
        val slug = u.path.orEmpty().trimStart('/').substringBefore('/')
        if (slug.isEmpty()) return Pair("", "")
        return Pair("https://${u.host}/$slug/", slug)
    }

    /* WordPress's paged shape. Never fetched — maxPage is always 1 — but the
       engine still asks what page 2 would be called. */
    override fun listPageUrl(base: String, slug: String, page: Int) = "${base}page/$page/"

    /* Strict, for the whole-document fallback only: the segment after the
       novel's own must carry "chuong-N" as a dash-separated word. Anchoring
       at the start would drop the "full-chuong-N" shape; not anchoring at a
       dash would let a slug that merely contains the word through. The
       "/full/" chapter fails this test on purpose — nothing about that url
       says chapter, and outside the site's own list nothing else vouches
       for it. */
    private val chapSeg = Regex("(^|-)chuong-\\d+(-|$)")

    override fun isChapterPath(path: String, slug: String): Boolean {
        val marker = "/$slug/"
        val i = path.indexOf(marker)
        if (i < 0) return false
        val seg = path.substring(i + marker.length).trimStart('/').substringBefore('/')
        return chapSeg.containsMatchIn(seg)
    }

    /* Loose, for links inside the site's own list: being there is the
       evidence. One captured novel files its first chapter at "/<slug>/full/"
       — no "chuong" anywhere — and a rule that insisted on the numbered shape
       would make that chapter invisible and shift every position after it.
       Only the listing's own /page/N/ links (WordPress pagination, unused
       here but cheap to refuse) and the novel's own url are kept out. */
    override fun isChapterInList(path: String, slug: String): Boolean {
        val marker = "/$slug/"
        val i = path.indexOf(marker)
        return i >= 0 && path.length > i + marker.length &&
            !Regex("/page/\\d+").containsMatchIn(path)
    }

    /* The number is read off the chapter's OWN segment — the last in the url
       — where it appears at most once: "chuong-2", "full-chuong-5", or
       "chuong-1-<novel-slug>", whose suffix repeats the novel's name, never
       another chuong-N of its own. Reading the whole url instead would trip
       on a novel whose slug carries the word ("/tieng-chuong-thu-11/").
       "/<slug>/full/" answers null, and the caller falls back to the link
       text ("Chương 1") and the chapter's position, both of which are right. */
    override fun chapterNumFromUrl(url: String): Int? {
        val path = try { URI(url.trim()).path.orEmpty() } catch (e: Exception) { url }
        val seg = path.trimEnd('/').substringAfterLast('/')
        return Regex("(^|-)chuong-(\\d+)").find(seg)?.groupValues?.get(2)?.toIntOrNull()
    }

    override fun maxPage(doc: Document, slug: String) = 1

    /* ul.info-truyen: <li><b>Trạng Thái:</b> Hoàn thành</li>. The other two
       values the corpus holds are "Đang ra" and "Tạm ngưng", neither of
       which is finished. The listings print the same fact as a "Full" badge,
       so accept both spellings. */
    override fun isCompleted(doc: Document): Boolean {
        val t = infoValue(doc, "Trạng Thái") ?: return false
        return t.contains("Hoàn", true) || t.equals("Full", true)
    }

    /* h1 > a.title-truyen carries the bare name on every captured page. The
       shared og:title chain is wrong here: this host wraps its og:title in
       " - vivutruyen2.net" site furniture, which would have become the
       novel's folder name for good. */
    override fun title(doc: Document) =
        doc.selectFirst("h1 a.title-truyen")?.text()?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null }

    /* <li><b>Tác giả:</b> …</li> — filled on 54 of the 116 captured pages
       and genuinely blank on the rest, so null is a common and correct
       answer; every page carries a description to fall back on. */
    override fun author(doc: Document) = infoValue(doc, "Tác giả")

    override fun genres(doc: Document) = infoValue(doc, "Thể Loại")

    override fun statusLabel(doc: Document) = infoValue(doc, "Trạng Thái")

    /* div.noi-dung, paragraphs kept — the synopsis on this host is often a
       whole opening scene rather than a one-line blurb. */
    override fun description(doc: Document): String? {
        val el = doc.selectFirst("div.noi-dung") ?: return null
        val paras = el.select("p").map { it.text().replace('\u00a0', ' ').trim() }
            .filter { it.isNotEmpty() }
        val t = if (paras.isNotEmpty()) {
            paras.joinToString("\n\n")
        } else {
            el.text().replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
        }
        return t.ifEmpty { null }
    }

    /* div.reading holds clean <p> prose — except its LAST paragraph, on any
       chapter with a successor: a "read on" pointer at the next chapter's
       url ("CHƯƠNG 6 – ẤN ĐỂ ĐỌC TIẾP: https://…", "ĐỌC TIẾP : https://…",
       "Đọc tiếp https://…" — all three shapes are in the corpus, 6 of the 30
       captured chapters). It sits inside the prose container, so the shared
       furniture filters never see it, TTS would read the url aloud and a
       translation pass would pay to translate it. What identifies it is the
       phrase AND the link — a sentence that merely says "đọc tiếp" keeps its
       paragraph. */
    override fun chapterContent(doc: Document): Element? {
        val el = doc.selectFirst("div.reading") ?: return null
        for (p in el.select("p")) {
            val t = p.text().lowercase()
            if (t.contains("đọc tiếp") && (t.contains("http") || p.selectFirst("a[href*=chuong-]") != null)) {
                p.remove()
            }
        }
        return el
    }

    /* the only h2 on a chapter page, in the chapter's own header block on
       all 30 captured chapters — some print it ALL CAPS ("CHƯƠNG 1"), which
       the heading parser already folds */
    override fun chapterHeading(doc: Document): Element? =
        doc.selectFirst(".chap-header h2, h2")

    /* The info block is <ul class="info-truyen"><li><b>Label:</b> value</li> —
       not the .info/h3 shape SiteHelp.infoField reads. Genre values are <a>
       links, the rest plain text after the <b>. */
    private fun infoValue(doc: Document, label: String): String? {
        val want = label.trim().trimEnd(':').lowercase()
        for (li in doc.select("ul.info-truyen li")) {
            val b = li.selectFirst("b") ?: continue
            if (b.text().trim().trimEnd(':').lowercase() != want) continue
            val links = li.select("a").map { it.text().trim() }.filter { it.isNotEmpty() }
            if (links.isNotEmpty()) return links.joinToString(", ")
            val clone = li.clone()
            clone.select("b").remove()
            val v = clone.text().trim()
            if (v.isNotEmpty()) return v
        }
        return null
    }
}
