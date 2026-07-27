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
        javaClass.getResourceAsStream("/pages/$file")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("site\t") }
            .map { it.split("\t") }
            .filter { it[0] == site.name }
            .toList()

    private fun page(path: String): String {
        val ins = javaClass.getResourceAsStream("/pages/$path.zip")
            ?: throw AssertionError("missing fixture: pages/$path.zip")
        ins.use { raw ->
            java.util.zip.ZipInputStream(raw).use { zip ->
                zip.nextEntry ?: throw AssertionError("empty archive: pages/$path.zip")
                return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
    }

    private val novels by lazy { rows("manifest.tsv").filter { it[4] == "novel" } }
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
       these are cross-checks rather than echoes. */
    @Test
    fun `the chapter count in the listing container matches what was measured`() {
        for (r in novels) {
            val (_, slug) = site.normalize(r[3])
            val found = Listing.collect(Jsoup.parse(page(r[1]), r[3]), site, slug)
            assertFalse("${r[1]}: fell back to the whole document", found.fellBack)
            assertEquals("${r[1]}: chapters in container", r[6].toInt(), found.links.size)
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

class TruyenfullTest : SiteContract() {
    override val site = Sites.all.first { it.name == "truyenfull" }

    /* Vietnamese source, so a downloaded novel is worth translating. */
    @Test
    fun `it is not an English site`() {
        assertFalse(site.english)
        assertEquals("Chương", site.headingWord)
    }

    @Test
    fun `both hosts are recognised`() {
        assertTrue(site.matches("https://truyenfull.today/tu-tien/"))
        assertTrue(site.matches("https://truyenfull.live/tu-tien/"))
        assertEquals("tu-tien", site.normalize("https://truyenfull.live/tu-tien/chuong-5/").second)
    }
}

class NovelfullTest : SiteContract() {
    override val site = Sites.all.first { it.name == "novelfull" }

    /* Already English — translating it would pay the API to round-trip. */
    @Test
    fun `it is an English site`() {
        assertTrue(site.english)
        assertEquals("Chapter", site.headingWord)
    }

    /* Chapters are named after their titles here, not numbered, so the strict
       url test would hide most of a novel — the loose one is what the listing
       uses. A 19-page listing once came back as a few hundred chapters and the
       download called itself finished. */
    @Test
    fun `a title-named chapter counts inside the listing but not outside it`() {
        assertTrue(site.isChapterInList("/the-mech-touch/1-ren.html", "the-mech-touch"))
        assertFalse(site.isChapterPath("/the-mech-touch/1-ren.html", "the-mech-touch"))
        assertTrue(site.isChapterPath("/the-mech-touch/chapter-1-ren.html", "the-mech-touch"))
    }
}
