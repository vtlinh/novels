package dev.vtlinh.noveldownloader.sites

import dev.vtlinh.noveldownloader.SiteContract
import dev.vtlinh.noveldownloader.Sites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    /* .net serves the same application and carries novels .com's catalogue
       does not, so both hosts are this one site. normalize keeps whichever
       host the url came from — a novel found on .net must not be re-addressed
       to .com, where it may not exist. */
    @Test
    fun `both hosts are recognised and a novel stays on the one it came from`() {
        assertTrue(site.matches("https://novelfull.com/the-mech-touch.html"))
        assertTrue(site.matches("https://novelfull.net/the-mech-touch.html"))
        val (base, slug) = site.normalize("https://novelfull.net/the-mech-touch/chapter-1-ren.html")
        assertEquals("the-mech-touch", slug)
        assertTrue("a .net novel must stay on .net, got $base", base.startsWith("https://novelfull.net/"))
    }

    /* .com serves no og:title at all, so the shared title chain fell through to
       h3.title and happened to give a clean name. .net DOES serve one, wrapped
       in furniture — and the folder a novel lives in is recorded once and
       never recomputed, so taking it would have named every .net novel
       "Read ... - NovelFull" for good.

       There is no hand-written HTML here on purpose. This used to assert
       against a document I typed out from memory of the real one, which is
       worth very little: it only ever proves the parser handles the shape I
       imagined. The corpus now holds real .net pages, and the contract's own
       branding and length checks run over them like any other — so this only
       has to confirm the corpus actually covers the host that has the trap. */
    @Test
    fun `the host whose title carries furniture is actually in the corpus`() {
        val net = novels.filter { it[2].contains("novelfull.net") }
        assertTrue(
            "no novelfull.net pages captured — the og:title trap is on that host, " +
                "so without them nothing here can see it",
            net.size >= 5,
        )
        /* and those pages really do carry the trap, or their presence proves
           nothing either */
        val wrapped = net.count { r ->
            org.jsoup.Jsoup.parse(page(r[1]), r[3])
                .selectFirst("meta[property=og:title]")
                ?.attr("content").orEmpty()
                .lowercase().contains("novelfull")
        }
        assertTrue(
            "captured .net pages carry no furniture-wrapped og:title, so the " +
                "branding check above is not exercised by them",
            wrapped >= 5,
        )
    }

    @Test
    fun `a title-named chapter counts inside the listing but not outside it`() {
        assertTrue(site.isChapterInList("/the-mech-touch/1-ren.html", "the-mech-touch"))
        assertFalse(site.isChapterPath("/the-mech-touch/1-ren.html", "the-mech-touch"))
        assertTrue(site.isChapterPath("/the-mech-touch/chapter-1-ren.html", "the-mech-touch"))
    }
}
