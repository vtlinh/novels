package dev.vtlinh.noveldownloader

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/* Everything the app needs to know about one novel site.

   This was a single class carrying a dozen lambdas, and the knowledge that
   belongs to a site had leaked well past it: the chapter-container selectors
   and the chapter-heading selector lived in Extractor as one combined list,
   the author selector was a shared heuristic in Sites, and the novel-title
   chain was written out three times inside DownloadEngine. Adding a site
   meant editing four files and hoping the shared lists still worked for
   everyone in them — and a selector added for one site silently changed what
   the others extracted.

   One interface, one class per site, one test class per site. The engine and
   the status check talk to this and never to a selector. */
interface Site {

    /* ---- identity ---- */

    val name: String

    /* "Chapter" / "Chương" — what a saved chapter's heading line is called */
    val headingWord: String

    /* the site publishes in English, so there is nothing to translate to */
    val english: Boolean get() = false

    fun matches(url: String): Boolean

    /* The hosts this site serves, most canonical first.

       `matches` is a predicate and cannot be enumerated, so the browser's
       start screen had no way to name a site the user had not already
       visited — it listed browsing history and nothing else, which means a
       newly supported site stayed invisible until you knew to type it in.
       This is that list, and it lives here because which hosts a site
       answers to is the site's own business. */
    val hosts: List<String>

    /* ---- urls ---- */

    /* any url of this site -> (the novel's listing base, its slug) */
    fun normalize(url: String): Pair<String, String>

    fun listPageUrl(base: String, slug: String, page: Int): String

    /* Strict: used for the WHOLE-DOCUMENT fallback, where a link's position
       vouches for nothing and the "latest chapters" widget looks the same as
       the real list. */
    fun isChapterPath(path: String, slug: String): Boolean

    /* Loose: used for links found INSIDE the chapter-list container. Sitting
       in the site's own list is the evidence that a link is a chapter, so this
       only has to keep the listing's own pagination out. It must NOT insist on
       a numbered url — a site is free to name chapters after their titles, and
       most of the novel would then be invisible. */
    fun isChapterInList(path: String, slug: String): Boolean = isChapterPath(path, slug)

    /* the number the SITE prints for this chapter, if its url says. Null is a
       fine answer — the caller falls back to the chapter's position in the
       listing, which is the site's real answer anyway. */
    fun chapterNumFromUrl(url: String): Int?

    /* ---- the novel page ---- */

    /* container of the REAL chapter list. Pages also carry a "latest chapters"
       widget whose links must not pollute the chapter order. */
    val listScope: String

    fun maxPage(doc: Document, slug: String): Int

    fun isCompleted(doc: Document): Boolean

    fun title(doc: Document): String?

    fun author(doc: Document): String?

    /* Alternative / other titles the site lists for this novel. Null when the
       page does not carry any — several hosts simply omit the field. */
    fun alternativeNames(doc: Document): String? = null

    /* Comma-separated genres / tags, as the site prints them. */
    fun genres(doc: Document): String? = null

    /* Origin / publisher / language line when the site has one ("Qidian
       International", "Chinese Novel", …). Not the host we scraped from. */
    fun source(doc: Document): String? = null

    /* The status string the site prints ("Completed", "Ongoing", "Full",
       "Đang ra", …). Distinct from isCompleted, which is the boolean the
       engine uses; this is what the novel-info screen shows. */
    fun statusLabel(doc: Document): String? = null

    /* Synopsis / description. Plain text, no site chrome. */
    fun description(doc: Document): String? = null

    /* ---- the chapter page ---- */

    /* The element holding the prose. Null lets the caller fall back to the
       largest div on the page, which is a guess and is treated as one. */
    fun chapterContent(doc: Document): Element?

    /* The chapter's own title element, for the heading line of the saved
       file. Null means take it from the link text instead. */
    fun chapterHeading(doc: Document): Element?
}

/* Shared helpers for the implementations. Not on the interface: a site is free
   to answer any of these differently, and several will. */
internal object SiteHelp {

    /* og:title first — it is the one field these sites fill consistently and
       it carries the bare title, where the h1 often carries site furniture. */
    fun metaTitle(doc: Document): String? =
        doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("h3.title")?.text()?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null }

    /* Selector order: the first SELECTOR that matches anything wins. This is
       how the content container has always been chosen. */
    fun firstOf(doc: Document, selectors: List<String>): Element? {
        for (sel in selectors) doc.selectFirst(sel)?.let { return it }
        return null
    }

    /* Document order: the first ELEMENT matching any of them wins, which is
       what one comma-separated selectFirst does and what the chapter heading
       has always used. Every captured chapter page matches four of the five
       at once, so the two rules pick different elements and the distinction
       is load-bearing rather than academic. */
    fun anyOf(doc: Document, selectors: List<String>): Element? =
        doc.selectFirst(selectors.joinToString(", "))

    /* highest N across every url on the page matching `re` */
    fun highestLink(doc: Document, re: Regex): Int {
        var max = 1
        for (a in doc.select("a[href]")) {
            val m = re.find(a.attr("href"))
            if (m != null) max = maxOf(max, m.groupValues[1].toInt())
        }
        return max
    }

    /* Text after an <h3>Label:</h3> inside the novel's .info block — the shape
       novelfull / truyenfull / truyenfullmoi all share. Labels are matched
       case-insensitively without a trailing colon. "N/A" is treated as empty
       (novelfull prints that for novels with no alternate title). */
    fun infoField(doc: Document, vararg labels: String): String? {
        val want = labels.map { it.trim().trimEnd(':').lowercase() }.toSet()
        for (h3 in doc.select(".info h3")) {
            val key = h3.text().trim().trimEnd(':').lowercase()
            if (key !in want) continue
            val parent = h3.parent() ?: continue
            val links = parent.select("a").map { it.text().trim() }.filter { it.isNotEmpty() }
            val v = if (links.isNotEmpty()) {
                links.joinToString(", ")
            } else {
                val clone = parent.clone()
                clone.select("h3").remove()
                clone.text().trim()
            }
            if (v.isNotEmpty() && !v.equals("N/A", ignoreCase = true)) return v
        }
        return null
    }

    /* Synopsis containers these sites use. Prefer <p> breaks when present so
       a multi-paragraph blurb keeps its shape; otherwise collapse whitespace.
       Strips a leading "SUMMARY" label some hosts put inside the same element. */
    fun descriptionText(doc: Document): String? {
        val el = doc.selectFirst(".desc-text")
            ?: doc.selectFirst("h4.abstract + .txt .inner")
            ?: doc.selectFirst("h4.abstract + .txt")
            ?: return null
        val paras = el.select("p").map { it.text().replace('\u00a0', ' ').trim() }
            .filter { it.isNotEmpty() }
        var t = if (paras.isNotEmpty()) {
            paras.joinToString("\n\n")
        } else {
            el.text().replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
        }
        if (t.startsWith("SUMMARY", ignoreCase = true)) {
            t = t.removePrefix("SUMMARY").removePrefix("summary").trim()
        }
        return t.ifEmpty { null }
    }
}
