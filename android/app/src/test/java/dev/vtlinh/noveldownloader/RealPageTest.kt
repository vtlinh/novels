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

   novelfull.com sits behind a JS challenge that neither curl nor a headless
   browser gets through from CI, so its pages were captured from novelfull.net,
   which serves the same application (same #list-chapter container, same
   /slug/chapter-name.html links, same ?page=N pagination). The manifest
   records the exact URL each fixture came from. */
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

    private fun read(path: String): String =
        javaClass.getResourceAsStream("/pages/$path")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw AssertionError("missing test resource: pages/$path")

    private val fixtures: List<Fixture> by lazy {
        read("manifest.tsv").lineSequence()
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
            assertEquals("10 novel pages captured for $name", 10, mine.size)
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
            assertEquals("${f.file}: chapters are not in ascending order", nums.sorted(), nums)
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
