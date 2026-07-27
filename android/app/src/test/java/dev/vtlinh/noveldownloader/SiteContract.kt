package dev.vtlinh.noveldownloader

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* The contract every supported site has to satisfy, run against that site's
   own captured pages.

   Site knowledge used to be spread over four files — the adapter, a shared
   author heuristic, a title chain written out three times in the engine, and
   one combined list of every site's chapter selectors in Extractor. A
   selector added for one site changed what the others extracted, and no test
   could say which site was wrong because none of them tested a site.

   Now: one class per site, one test class per site, and this contract in
   common. A new site subclasses it, points at its own fixtures, and cannot be
   added without answering every question the engine will ask it. */
abstract class SiteContract {

    abstract val site: Site

    /* ---- fixtures for THIS site ---- */

    private fun rows(file: String): List<List<String>> =
        javaClass.getResourceAsStream("/pages/${site.name}/$file")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("site\t") }
            .map { it.split("\t") }
            .filter { it[0] == site.name }
            .toList()

    protected fun page(path: String): String {
        val ins = javaClass.getResourceAsStream("/pages/$path.zip")
            ?: throw AssertionError("missing fixture: pages/$path.zip")
        ins.use { raw ->
            java.util.zip.ZipInputStream(raw).use { zip ->
                zip.nextEntry ?: throw AssertionError("empty archive: pages/$path.zip")
                return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
    }

    protected val novels by lazy { rows("manifest.tsv").filter { it[4] == "novel" } }
    private val chapters by lazy { rows("chapters.tsv") }

    /* ---- the contract ---- */

    @Test
    fun `the site has fixtures to be judged against`() {
        assertTrue("${site.name}: no novel pages captured", novels.size >= 20)
        assertTrue("${site.name}: no chapter pages captured", chapters.isNotEmpty())
    }

    @Test
    fun `it claims its own urls and no others`() {
        for (r in novels.take(5)) assertTrue("${site.name} must match ${r[3]}", site.matches(r[3]))
        for (other in Sites.all) {
            if (other.name == site.name) continue
            for (r in novels.take(5)) {
                assertFalse(
                    "${other.name} must not claim ${site.name}'s url ${r[3]}",
                    other.matches(r[3]),
                )
            }
        }
    }

    /* The browser's start screen lists every supported site by name, so a
       site that declares a host it does not claim would put a dead entry on
       that screen, and one whose captured pages come from a host it never
       declares would be unreachable from it. */
    @Test
    fun `it declares the hosts it serves, and claims every one of them`() {
        assertTrue("${site.name}: declares no hosts", site.hosts.isNotEmpty())
        for (h in site.hosts) {
            assertFalse("${site.name}: host \"$h\" should be bare, not a url", h.contains("/"))
            assertTrue(
                "${site.name}: declares $h but does not claim https://$h/",
                site.matches("https://$h/"),
            )
        }
        /* ...and the hosts the corpus was actually captured from are among
           them, give or take the www the sites redirect between */
        for (r in novels) {
            val host = java.net.URI(r[3]).host.orEmpty().removePrefix("www.")
            assertTrue(
                "${site.name}: captured from $host, which it does not declare",
                site.hosts.any { it.removePrefix("www.") == host },
            )
        }
    }

    /* normalize is how every later step addresses the novel — a base to page
       through and a slug that names its folder for life. */
    @Test
    fun `normalize round-trips every captured novel url`() {
        for (r in novels) {
            val (base, slug) = site.normalize(r[3])
            assertTrue("${r[3]}: empty slug", slug.isNotEmpty())
            assertTrue("${r[3]}: base does not contain the slug", base.contains(slug))
            assertTrue("${r[3]}: base is not a url", base.startsWith("https://"))
            /* idempotent: normalizing the base must give the same answer, or a
               second run addresses the novel differently from the first */
            assertEquals("${r[3]}: not idempotent", Pair(base, slug), site.normalize(base))
        }
    }

    @Test
    fun `page one of the listing is addressable and distinct from page two`() {
        for (r in novels.take(10)) {
            val (base, slug) = site.normalize(r[3])
            val p2 = site.listPageUrl(base, slug, 2)
            val p3 = site.listPageUrl(base, slug, 3)
            assertTrue("${r[3]}: page 2 url does not mention the novel", p2.contains(slug))
            assertTrue("${r[3]}: page 2 and 3 are the same url", p2 != p3)
        }
    }

    /* Measured at capture time by a script that does not use this parser, so
       these are cross-checks rather than echoes.

       DISTINCT links, not the raw count. A site is free to print the same
       chapter twice in its own list, and truyenfullmoi does: three of its
       captured pages list a run of chapters a second time, one of them eight
       of them. The engine keys chapters by href and so sees the novel the
       distinct set describes — counting the repeats would hold the parser to
       a number the app never works with. */
    @Test
    fun `the chapter count in the listing container matches what was measured`() {
        for (r in novels) {
            val (_, slug) = site.normalize(r[3])
            val found = Listing.collect(Jsoup.parse(page(r[1]), r[3]), site, slug)
            assertFalse("${r[1]}: fell back to the whole document", found.fellBack)
            assertEquals(
                "${r[1]}: chapters in container",
                r[6].toInt(),
                found.links.map { it.first }.distinct().size,
            )
        }
    }

    @Test
    fun `the page count matches what was measured`() {
        for (r in novels) {
            val expected = r[7].ifEmpty { null }?.toInt() ?: continue
            val (_, slug) = site.normalize(r[3])
            assertEquals(
                "${r[1]}: max page",
                expected,
                site.maxPage(Jsoup.parse(page(r[1]), r[3]), slug),
            )
        }
    }

    @Test
    fun `finished and ongoing novels are told apart as measured`() {
        for (r in novels) {
            val expected = r[5].ifEmpty { null }?.toBoolean() ?: continue
            assertEquals(
                "${r[1]}: completed",
                expected,
                site.isCompleted(Jsoup.parse(page(r[1]), r[3])),
            )
        }
    }

    /* The title names the folder the novel lives in for good, so a site that
       cannot read one off its own page has no business being supported. */
    @Test
    fun `every captured novel page yields a title`() {
        for (r in novels) {
            val t = site.title(Jsoup.parse(page(r[1]), r[3]))
            assertTrue("${r[1]}: no title", !t.isNullOrBlank())
            assertTrue(
                "${r[1]}: title \"$t\" does not sanitise to a usable folder name",
                Extractor.sanitize(t!!).isNotEmpty(),
            )
        }
    }

    /* ...and it must be the NOVEL's name, not the page's.
       "Yields a non-empty title" was too weak to be worth much: novelfull.net
       wraps its og:title in "Read <title> novel online free - NovelFull", and
       that sanitises to something non-empty perfectly well. It would have
       become the folder name for every novel captured from that host — and
       the recorded directory is never recomputed, so permanently. I caught it
       by reading the live page, which is precisely the method this corpus
       exists to replace.
       A site's own brand appearing in a novel's title is the giveaway, and
       `name` already is that word for every adapter here. */
    @Test
    fun `the site's own branding never ends up in a novel's title`() {
        for (r in novels) {
            val t = site.title(Jsoup.parse(page(r[1]), r[3])) ?: continue
            val key = t.lowercase().filter { it.isLetterOrDigit() }
            assertFalse(
                "${r[1]}: title \"$t\" carries the site's own name — that is page " +
                    "furniture, and it would be the novel's folder name for good",
                key.contains(site.name.lowercase()),
            )
        }
    }

    /* A blunt backstop for furniture that does not happen to name the site.
       Measured across the captured corpus: novelfull sits at exactly 1.00 on
       all 100 pages and truyenfull's median is 1.00, with one genuine 2.42
       (a title carrying a parenthetical alternate name). The wrapped
       novelfull.net title scores 1.80. */
    @Test
    fun `a title is not padded out with words the slug has never heard of`() {
        for (r in novels) {
            val t = site.title(Jsoup.parse(page(r[1]), r[3])) ?: continue
            val (_, slug) = site.normalize(r[3])
            val tk = t.lowercase().filter { it.isLetterOrDigit() }.length
            val sk = slug.lowercase().filter { it.isLetterOrDigit() }.length.coerceAtLeast(1)
            assertTrue(
                "${r[1]}: title \"$t\" is ${"%.1f".format(tk.toDouble() / sk)}x the length of " +
                    "its slug — that reads as page furniture rather than a name",
                tk.toDouble() / sk <= 2.5,
            )
        }
    }

    /* ---- chapter pages ---- */

    @Test
    fun `every captured chapter page yields its prose`() {
        for (r in chapters) {
            val doc = Jsoup.parse(page(r[1]), r[2])
            val el = site.chapterContent(doc)
            assertNotNull("${r[1]}: no chapter container found", el)
            assertTrue("${r[1]}: chapter container is empty", el!!.text().trim().length > 200)
        }
    }

    @Test
    fun `every captured chapter page yields a heading`() {
        for (r in chapters) {
            val doc = Jsoup.parse(page(r[1]), r[2])
            val el = site.chapterHeading(doc)
            assertNotNull("${r[1]}: no chapter heading found", el)
            assertTrue("${r[1]}: heading is empty", el!!.text().trim().isNotEmpty())
        }
    }

    /* The saved file's heading has to carry the number the app asked for, not
       the one the page prints — a file is named by its POSITION in the listing
       and the two differ whenever a site's numbering has drifted. */
    @Test
    fun `a chapter is written under the number it was asked for`() {
        for (r in chapters) {
            val asked = (r[5].toIntOrNull() ?: 1) + 500
            val out = Extractor.parseChapter(Jsoup.parse(page(r[1]), r[2]), "", asked, site)
            assertTrue(
                "${r[1]}: heading is \"${out.lineSequence().first()}\"",
                out.lineSequence().first().startsWith("${site.headingWord} $asked"),
            )
        }
    }

    /* A chapter url that the site numbers has to read back as that number —
       it goes into the saved heading and is the sole input to the legacy
       rename guess. Sites that number nothing answer null, which is fine. */
    @Test
    fun `a chapter url either yields the printed number or nothing`() {
        var read = 0
        for (r in chapters) {
            val printed = r[5].toIntOrNull() ?: continue
            val n = site.chapterNumFromUrl(r[2]) ?: continue
            /* A SUB-CHAPTER cannot be asked of a url. novelfull prints
               "Book 2: Chapter 9.1" and renders it as ".../book-2-chapter-91-…",
               so the url says 91 and the page says 9.1 — and from the url
               alone those two are indistinguishable. One fixture in the
               corpus is like this, which is exactly why it is in the corpus.
               The engine already handles the case where a url reads back a
               number the page disagrees with: the chapter is named by its
               POSITION in the listing, not by either number. */
            val heading = site.chapterHeading(Jsoup.parse(page(r[1]), r[2]))?.text().orEmpty()
            if (Regex("\\d+\\.\\d+").containsMatchIn(heading)) continue
            read++
            assertEquals("${r[2]}: url number disagrees with the printed one", printed, n)
        }
        /* not an assertion about every url — only that the reader is wired up
           at all for a site whose urls do carry numbers */
        assertTrue("${site.name}: chapterNumFromUrl never read a single url", read > 0)
    }

    /* The listing's own pagination sits inside the chapter-list container on
       at least one supported site, and reading a page link as a chapter puts
       every chapter after it one position out. */
    @Test
    fun `the listing's own pagination is not mistaken for a chapter`() {
        for (r in novels.take(20)) {
            val (base, slug) = site.normalize(r[3])
            for (p in 2..4) {
                val path = java.net.URI(site.listPageUrl(base, slug, p)).path ?: continue
                assertFalse(
                    "${site.name}: page-$p link reads as a chapter",
                    site.isChapterInList(path, slug),
                )
            }
        }
    }
}
