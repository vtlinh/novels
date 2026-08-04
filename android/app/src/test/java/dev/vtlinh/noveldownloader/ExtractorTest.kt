package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/* Folder names and chapter headings, both derived from untrusted site text.
   A folder name that comes back empty kills the download; two novels folding
   onto one name makes them overwrite each other's chapters. */
class ExtractorTest {

    @Test
    fun `diacritics are folded to plain ascii`() {
        assertEquals("Dau Pha Thuong Khung", Extractor.sanitize("Đấu Phá Thương Khung"))
    }

    @Test
    fun `characters a filesystem will not take are replaced`() {
        val out = Extractor.sanitize("A/B\\C|D:E\"F<G>H?I*J")
        assertFalse(out.any { it in "/\\|:\"<>?*" })
    }

    /* Path traversal must not survive into a folder name. Note what is
       actually required: separators are what make ".." dangerous, and they
       are replaced, so the residual dots are inert — a folder literally
       named "..-..-etc-passwd" escapes nothing. The name must also not be
       one the filesystem reads as a directory reference in its own right. */
    @Test
    fun `path traversal cannot survive into a folder name`() {
        val out = Extractor.sanitize("../../etc/passwd")
        assertFalse("no path separator may survive", out.contains("/") || out.contains("\\"))
        assertFalse("the name must not be a bare directory reference", out == "." || out == "..")
    }

    /* these all reduce to nothing, and an empty name is one the provider
       refuses — the download died with no way for the user to intervene,
       since the name is derived rather than typed */
    @Test
    fun `titles that reduce to nothing fall back to the slug`() {
        for (title in listOf("...", "…", "?", "***", "一二三四五")) {
            assertEquals(
                "\"$title\" must not produce an empty folder name",
                "my-novel",
                Extractor.folderName(title, "my-novel"),
            )
        }
    }

    @Test
    fun `a folder name is never empty even when the slug is unusable too`() {
        assertTrue(Extractor.folderName("...", "…").isNotEmpty())
    }

    /* truncation used to run AFTER the tail was trimmed, so cutting could put
       a '.' or ' ' back on the end — and a FAT provider silently drops those,
       so the name we looked for never matched the one on disk: a fresh "(1)"
       folder and a full re-download, every run */
    @Test
    fun `a truncated name never ends in a dot or space`() {
        val long = "A".repeat(179) + ". tail that will be cut"
        val out = Extractor.sanitize(long)
        assertTrue(out.length <= 180)
        assertFalse("truncation must not leave a trailing dot or space", out.endsWith(".") || out.endsWith(" "))
    }

    /* the fractional part used to fall into the TITLE: "Chapter 1.5: Side
       Story" parsed as number 1 titled ".5: Side Story", and the real heading
       had already been stripped from the body, so it was gone */
    @Test
    fun `a fractional chapter number does not leak into the title`() {
        val (num, title) = Extractor.parseHeading("Chapter 1.5: Side Story")
        assertEquals(1, num)
        assertEquals("Side Story", title)
    }

    @Test
    fun `vietnamese fractional headings parse the same way`() {
        val (num, title) = Extractor.parseHeading("Chương 12.1 - Phần phụ")
        assertEquals(12, num)
        assertEquals("Phần phụ", title)
    }

    @Test
    fun `an ordinary heading keeps its title`() {
        val (num, title) = Extractor.parseHeading("Chương 5: Tiêu Viêm")
        assertEquals(5, num)
        assertEquals("Tiêu Viêm", title)
    }

    @Test
    fun `a heading with no title yields an empty title not a stray separator`() {
        val (num, title) = Extractor.parseHeading("Chapter 7")
        assertEquals(7, num)
        assertEquals("", title)
    }

    /* Two novels whose titles differ only in tone marks fold to one name.
       That is a real collision on a Vietnamese site and the reason folder
       ownership has to be recorded rather than inferred from the name. */
    @Test
    fun `titles differing only in tone marks collide and must be disambiguated`() {
        assertEquals(Extractor.sanitize("Thần Y"), Extractor.sanitize("Thân Y"))
        /* This used to append " (than-y-b)" to the right-hand side itself and
           assert the two differed — true of any implementation whatsoever,
           including one that returned a constant, so under a name promising
           coverage of the collision rule it constrained nothing.

           What is actually true is the opposite: folderName does NOT
           disambiguate. Both novels compute the same name, which is precisely
           why Ownership.choose has to step one of them aside — and that is
           where the rule is really tested (OwnershipTest). */
        assertEquals(
            Extractor.folderName("Thần Y", "than-y-a"),
            Extractor.folderName("Thân Y", "than-y-b"),
        )
    }

    /* The status sweep also GUESSES a URL at the other hosts, so whatever
       answers has to say it is the same book before the sweep renames files
       against its listing. The slug is not proof of that: a folder-scanned
       novel's slug comes from its folder NAME, and the sites tell same-titled
       books apart with a numeric suffix. */
    @Test
    fun `a page is only the novel we asked for when it says the same title`() {
        assertTrue(Extractor.sameNovelTitle("Tu Tiên", "Tu Tien"))
        assertTrue("the slug is a slugified title", Extractor.sameNovelTitle("Tu Tiên", "tu-tien"))
        assertTrue(
            "a translated folder matches on either half",
            Extractor.sameNovelTitle("Thần Y", "Divine Doctor (Than Y)"),
        )
        assertTrue(Extractor.sameNovelTitle("Divine Doctor", "Divine Doctor (Than Y)"))
        assertFalse("a different book at the same slug", Extractor.sameNovelTitle("Vũ Thần", "Tu Tien"))
        assertFalse("a page with no title is not a match", Extractor.sameNovelTitle("", "Tu Tien"))
        assertFalse(Extractor.sameNovelTitle("Tu Tien Phan 2", "Tu Tien"))
    }

    /* ---- sameHeading: does the translation beside an overwritten chapter
       survive? (see DownloadEngine.sameOnDisk) ---- */

    /* THE MIGRATION CASE. Leading unnumbered chapters used to be written as
       "Chapter 0" and are numbered by position now — exact-line equality
       deleted every such file's translation on the first refetch after the
       change, and bought it again in the same run. Same title, and a zero
       contradicts nothing. */
    @Test
    fun `a renumbered zero heading is the same chapter`() {
        assertTrue(Extractor.sameHeading("Chapter 0: The Cold Girl", "Chapter 1: The Cold Girl"))
        assertTrue(Extractor.sameHeading("Chapter 41: Death's Roulette", "Chapter 0: Death's Roulette"))
    }

    @Test
    fun `the same heading is the same chapter`() {
        assertTrue(Extractor.sameHeading("Chương 12: Thần Y", "Chương 12: Thần Y"))
    }

    /* Two different chapters can share a title — after a listing shift the
       file at a name really can hold the neighbouring chapter. Equal titles
       with contradicting nonzero numbers are different chapters, and calling
       them the same would keep the WRONG translation under the name. */
    @Test
    fun `equal titles with contradicting real numbers are different chapters`() {
        assertFalse(Extractor.sameHeading("Chapter 12: Interlude", "Chapter 13: Interlude"))
    }

    @Test
    fun `different titles are different chapters`() {
        assertFalse(Extractor.sameHeading("Chapter 5: The Duel", "Chapter 5: The Feast"))
    }

    /* a bare number has nothing but the number to identify it — the exact
       compare is all there is */
    @Test
    fun `headings with no title fall back to the exact compare`() {
        assertTrue(Extractor.sameHeading("Chương 12", "Chương 12"))
        assertFalse(Extractor.sameHeading("Chương 12", "Chương 13"))
    }

    /* For a fully unnumbered legacy novel every file says "Chapter 0" and
       the zero tolerance is the entire verdict — on a title that repeats in
       the listing ("Epilogue"), a shift then blessed the NEIGHBOUR's
       translation: wrong text served for good, never re-bought. A repeated
       title withdraws the tolerance; dropping and re-buying costs money but
       never serves the wrong chapter's English. */
    @Test
    fun `an ambiguous title withdraws the zero tolerance`() {
        assertFalse(
            Extractor.sameHeading("Chapter 0: Epilogue", "Chapter 118: Epilogue", ambiguousTitle = true),
        )
        /* the tolerance itself is untouched where the title is unique */
        assertTrue(
            Extractor.sameHeading("Chapter 0: Epilogue", "Chapter 118: Epilogue", ambiguousTitle = false),
        )
        /* and ambiguity does not weaken the EQUAL-number or exact matches */
        assertTrue(
            Extractor.sameHeading("Chapter 5: Epilogue", "Chapter 5: Epilogue", ambiguousTitle = true),
        )
    }
}
