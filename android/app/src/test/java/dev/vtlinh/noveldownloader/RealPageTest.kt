package dev.vtlinh.noveldownloader

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* The listing read, run over REAL pages captured from both sites.

   Every other test here works on values we made up, which is fine for the
   arithmetic and useless for the thing that actually breaks: what these
   sites' HTML really contains. The failures this file exists to catch have
   all shipped at least once —

     - the pagination block sits INSIDE the chapter-list container, and its
       "page 1" link is the novel's own URL, so it was collected as a chapter
       and pushed every chapter after it a position out of place;
     - the container went missing and the collector fell back to the whole
       document, where the only chapter links are the "latest chapters"
       widget: five links in the wrong order, which the dedupe then treated
       as the whole novel and deleted the rest against;
     - a chapter-URL test that was right for one site and quietly wrong for
       the other.

   Positions name files, so any of these renames or deletes real chapters.

   The fixtures under src/test/resources/pages are unedited pages, chosen to
   span finished and ongoing novels and books of 20 chapters up to ~6,700
   (134 listing pages). manifest.tsv records what each page contains as
   measured at capture time by a separate script — not by this app's parser —
   so the assertions below are a cross-check rather than an echo.

   novelfull.com answers a direct curl with a JS challenge, so these were
   fetched through the dev Worker (tools/README.md), which reaches it fine.
   Three short novels are the exception: .com's catalogue does not carry them
   at all, so those came from novelfull.net, which serves the same application
   (same #list-chapter container, same /slug/chapter-name.html links, same
   ?page=N pagination). The manifest records where each fixture came from and
   which URL the app itself would use. */
class RealPageTest {

    private class Fixture(
        val site: Site,
        val file: String,
        val captureUrl: String,
        val url: String,          // the URL the app itself uses
        val kind: String,
        val completed: Boolean?,
        val chapters: Int,
        val maxPage: Int?,
    ) {
        val slug: String get() = site.normalize(url).second
        override fun toString() = file
    }

    /* Each page is its own zip: whole third-party pages, so keeping them
       compressed keeps them out of repository search and off every clone's
       disk at a fifth of the size — and one archive per page means the one
       you are debugging can be extracted on its own rather than unpacking
       the whole corpus. The manifest stays plain text: it is the index that
       makes the set reviewable, and it holds no page content. */
    private fun read(path: String): String {
        val ins = javaClass.getResourceAsStream("/pages/$path.zip")
            ?: throw AssertionError("missing test resource: pages/$path.zip")
        ins.use { raw ->
            java.util.zip.ZipInputStream(raw).use { zip ->
                val entry = zip.nextEntry ?: throw AssertionError("empty archive: pages/$path.zip")
                if (entry.isDirectory) throw AssertionError("expected one page in pages/$path.zip")
                return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
    }

    private fun readPlain(path: String): String =
        javaClass.getResourceAsStream("/pages/$path")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw AssertionError("missing test resource: pages/$path")

    private val fixtures: List<Fixture> by lazy {
        readPlain("manifest.tsv").lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val c = line.split("\t")
                Fixture(
                    site = Sites.all.first { it.name == c[0] },
                    file = c[1],
                    captureUrl = c[2],
                    url = c[3],
                    kind = c[4],
                    completed = c[5].ifEmpty { null }?.toBoolean(),
                    chapters = c[6].toInt(),
                    maxPage = c[7].ifEmpty { null }?.toInt(),
                )
            }.toList()
    }

    private fun doc(f: Fixture) = Jsoup.parse(read(f.file), f.url)

    @Test
    fun `the fixtures cover both sites, finished and ongoing, short and long`() {
        val novels = fixtures.filter { it.kind == "novel" }
        for (name in listOf("truyenfull", "novelfull")) {
            val mine = novels.filter { it.site.name == name }
            assertEquals("100 novel pages captured for $name", 100, mine.size)
            assertTrue("$name: some finished", mine.any { it.completed == true })
            assertTrue("$name: some ongoing", mine.any { it.completed == false })
            assertTrue("$name: some short", mine.any { (it.maxPage ?: 1) <= 3 })
            assertTrue("$name: some very long", mine.any { (it.maxPage ?: 1) >= 30 })
        }
    }

    /* The one that matters most: on a real page the site's own chapter-list
       container is there and is used. If this fails, the collector is reading
       the whole document — and a run that does that must not rename or delete
       anything, which is what `fellBack` now enforces. */
    @Test
    fun `every real page is read from the site's own chapter list`() {
        for (f in fixtures) {
            val found = Listing.collect(doc(f), f.site, f.slug)
            assertFalse(
                "${f.file}: fell back to the whole document — that is the widget, not the listing",
                found.fellBack,
            )
            assertTrue("${f.file}: no chapters found at all", found.links.isNotEmpty())
        }
    }

    /* ...and it finds exactly what is in that container — no more (the
       pagination links live inside it) and no fewer. */
    @Test
    fun `the collector finds the chapters the page actually holds`() {
        for (f in fixtures) {
            val found = Listing.collect(doc(f), f.site, f.slug)
            assertEquals(
                "${f.file}: chapter count disagrees with what the page holds",
                f.chapters,
                found.links.map { it.first }.distinct().size,
            )
        }
    }

    /* The bug that shifted every chapter after it: the "page 1" pagination
       link is the novel's own canonical URL, sitting inside the chapter list. */
    @Test
    fun `the novel's own url and its page links are never collected as chapters`() {
        for (f in fixtures) {
            val links = Listing.collect(doc(f), f.site, f.slug).links.map { it.first }
            /* by PATH: these pages carry absolute links to the site's other
               host, so comparing whole URLs would pass without proving
               anything */
            fun path(u: String) = try { java.net.URI(u).path.orEmpty().trimEnd('/') } catch (e: Exception) { "" }
            val basePath = path(f.site.normalize(f.url).first)
            assertFalse(
                "${f.file}: collected the novel's own URL as a chapter",
                links.any { path(it) == basePath },
            )
            for (l in links) {
                assertFalse(
                    "${f.file}: collected a pagination link as a chapter: $l",
                    Regex("/trang-\\d+").containsMatchIn(l) || Regex("[?&]page=\\d+").containsMatchIn(l),
                )
            }
        }
    }

    /* Discovery order IS the site's order, and the site's order is what names
       every file — so page 1 has to start at chapter 1 and go up. Checked on
       truyenfull, whose URLs carry the number; novelfull's are title slugs. */
    @Test
    fun `page one lists chapters in ascending site order`() {
        for (f in fixtures.filter { it.kind == "novel" && it.site.name == "truyenfull" }) {
            val nums = Listing.collect(doc(f), f.site, f.slug).links
                .mapNotNull { f.site.chapterNumFromUrl(it.first) }
            assertTrue("${f.file}: no numbered chapters", nums.size > 5)
            assertEquals("${f.file}: page 1 does not start at chapter 1", 1, nums.first())
            /* Broadly ascending, not strictly. A real page in this corpus
               lists ...40, 42, 41, 43... — the site's own order does not
               always agree with the numbers it prints, which is exactly why
               a file is named by its POSITION in the listing and not by the
               number in its heading. Assert the shape (the listing runs
               forwards) without asserting a sortedness the site does not
               promise. */
            val inversions = nums.zipWithNext().count { it.first > it.second }
            assertTrue(
                "${f.file}: listing is not broadly ascending ($inversions inversions in ${nums.size})",
                inversions <= maxOf(1, nums.size / 25),
            )
        }
    }

    @Test
    fun `the page count is read from the pagination`() {
        for (f in fixtures.filter { it.kind == "novel" }) {
            assertEquals(
                "${f.file}: page count disagrees with the pagination on the page",
                f.maxPage,
                f.site.maxPage(doc(f), f.slug),
            )
        }
    }

    /* Drives the Complete tag and stops a finished novel being swept forever. */
    @Test
    fun `finished and ongoing novels are told apart`() {
        for (f in fixtures.filter { it.kind == "novel" }) {
            assertEquals(
                "${f.file}: finished/ongoing read wrongly",
                f.completed,
                f.site.isCompleted(doc(f)),
            )
        }
    }

    /* A URL has to survive the round trip, or the novel's identity changes
       under it — the slug keys the index and the base builds every page URL. */
    @Test
    fun `urls normalise back to the novel they came from`() {
        for (f in fixtures) {
            val (base, slug) = f.site.normalize(f.url)
            assertTrue("${f.file}: slug lost", slug.isNotEmpty())
            assertEquals("${f.file}: base is not stable", base, f.site.normalize(base).first)
            assertTrue("${f.file}: the adapter should claim its own URL", f.site.matches(f.url))
            assertEquals("${f.file}: a different adapter claims this URL", f.site.name, Sites.forUrl(f.url)?.name)
        }
    }

    /* The last listing page of a long novel is the shape that keeps going
       wrong — it is where a soft 404 lands and where the count over-reads. */
    @Test
    fun `a last listing page is a short page, not an empty one`() {
        val last = fixtures.filter { it.file.contains("last") }
        assertTrue("no last-page fixtures captured", last.isNotEmpty())
        for (f in last) {
            val found = Listing.collect(doc(f), f.site, f.slug)
            assertFalse("${f.file}: read as a fallback", found.fellBack)
            assertTrue("${f.file}: a real last page still holds chapters", found.links.isNotEmpty())
        }
    }
}

/* The chapter parser, run over REAL chapter pages.

   parseChapter decides the CONTENTS of every file the app saves — the heading
   it writes, which of the page is prose and which is navigation, adverts and
   "report a problem" boxes. Nothing else here covered it, and a mistake in it
   is not visible as a crash: the chapter simply saves short, or saves the ad
   text, or saves with the wrong heading, and the reader shows exactly that
   forever.

   chapters.tsv records each page's heading number and the visible character
   count of the site's own chapter container, measured by a separate script.
   Counted on characters rather than <p> tags on purpose: truyenfull separates
   paragraphs with <br>, so a tag count reads 5 for a ninety-line chapter. */
class RealChapterTest {

    private class Chap(
        val site: Site,
        val file: String,
        val url: String,
        val novel: String,
        val which: String,
        val headingNumber: Int?,
        val contentChars: Int,
    ) {
        override fun toString() = file
    }

    private fun read(path: String): String {
        val ins = javaClass.getResourceAsStream("/pages/$path.zip")
            ?: throw AssertionError("missing test resource: pages/$path.zip")
        ins.use { raw ->
            java.util.zip.ZipInputStream(raw).use { zip ->
                zip.nextEntry ?: throw AssertionError("empty archive: pages/$path.zip")
                return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
    }

    private val chapters: List<Chap> by lazy {
        val tsv = javaClass.getResourceAsStream("/pages/chapters.tsv")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
        tsv.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val c = line.split("\t")
            Chap(
                site = Sites.all.first { it.name == c[0] },
                file = c[1], url = c[2], novel = c[3], which = c[4],
                headingNumber = c[5].toIntOrNull(), contentChars = c[6].toInt(),
            )
        }.toList()
    }

    private fun parse(c: Chap): String =
        Extractor.parseChapter(Jsoup.parse(read(c.file), c.url), "", c.headingNumber ?: 1, c.site.headingWord)

    @Test
    fun `chapter pages are captured from both sites, first and middle`() {
        assertTrue("no chapter fixtures", chapters.size >= 20)
        for (name in listOf("truyenfull", "novelfull")) {
            assertTrue("$name: no chapter pages", chapters.any { it.site.name == name })
        }
        assertTrue("no first chapters", chapters.any { it.which == "first" })
        assertTrue("no middle chapters", chapters.any { it.which == "mid" })
    }

    /* Every one has to parse. parseChapter THROWS when the content area comes
       back empty — which is the right answer for a page it cannot read, and a
       disaster if it happens to a page it should. */
    @Test
    fun `every real chapter page parses`() {
        for (c in chapters) {
            val out = try { parse(c) } catch (e: Exception) {
                throw AssertionError("${c.file}: parseChapter threw — ${e.message}")
            }
            assertTrue("${c.file}: empty result", out.isNotBlank())
        }
    }

    /* The first line is the heading, and it carries the number the app was
       told to use — not the one printed on the page. That distinction is the
       whole naming scheme: a file is named by its POSITION in the listing,
       and the heading inside it has to agree with the name. */
    @Test
    fun `the first line is the heading the app was asked for`() {
        for (c in chapters) {
            val first = parse(c).lineSequence().first()
            val n = c.headingNumber ?: 1
            assertTrue(
                "${c.file}: heading is \"$first\", expected to start \"${c.site.headingWord} $n\"",
                first.startsWith("${c.site.headingWord} $n"),
            )
        }
    }

    /* The body has to be the chapter, not a fragment of it and not the whole
       page. Measured against the site's own container: well under it means
       the extractor is dropping the chapter, well over means it is keeping
       the navigation and the adverts. */
    @Test
    fun `the body is the chapter, not a fragment and not the whole page`() {
        for (c in chapters) {
            val body = parse(c).substringAfter('\n', "")
            assertTrue("${c.file}: body is empty", body.isNotBlank())
            assertTrue(
                "${c.file}: kept ${body.length} chars of a ${c.contentChars}-char container — too little",
                body.length >= c.contentChars / 2,
            )
            assertTrue(
                "${c.file}: kept ${body.length} chars of a ${c.contentChars}-char container — too much",
                body.length <= c.contentChars + 200,
            )
        }
    }

    /* The junk these pages carry around the prose. Any of it saved into a
       chapter file is read aloud by TTS and translated at the API's price. */
    @Test
    fun `site furniture never survives into a chapter`() {
        val junk = listOf(
            "Chương trước", "Chương tiếp", "Chương sau",
            "Previous Chapter", "Next Chapter", "Report chapter",
            "Bạn đang đọc truyện tại", "truyenfull.vn",
            "function ", "googletag", "adsbygoogle", "<script",
        )
        for (c in chapters) {
            val out = parse(c)
            for (j in junk) {
                assertFalse("${c.file}: kept site furniture \"$j\"", out.contains(j, ignoreCase = true))
            }
        }
    }

    /* Blank lines between paragraphs were what the reader's paragraph indexing
       counted, so a page that arrived double-spaced moved every saved reading
       position in it. */
    @Test
    fun `paragraphs are single-spaced`() {
        for (c in chapters) {
            assertFalse("${c.file}: blank line between paragraphs", parse(c).contains("\n\n"))
        }
    }
}
