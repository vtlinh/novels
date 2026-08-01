package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* What a novel's folder holds, and what the reader is shown of it.

   Three defects in three consecutive releases came from these rules, each one
   introduced by the fix for the last: a 0-byte file read as an empty chapter;
   the fix for that throwing away the chapter's paid-for translation; the fix
   for THAT making the cached listing fail its own validity check on every
   open. All three were invisible without a folder to run against. */
class FolderTest {

    private fun f(name: String, ref: String = "id:$name", size: Long = 100L) =
        Folder.Item(name, ref, isDir = false, size = size)

    private fun resolve(
        main: List<Folder.Item>,
        translated: List<Folder.Item> = emptyList(),
        order: Map<String, Int> = emptyMap(),
    ) = Folder.resolve(main, translated, order)

    @Test
    fun `a plain folder of chapters reads in numeric order`() {
        val c = resolve(listOf(f("Chapter 2.txt"), f("Chapter 10.txt"), f("Chapter 1.txt")))
        assertEquals(listOf("Chapter 1.txt", "Chapter 2.txt", "Chapter 10.txt"), c.ordered)
        assertEquals("id:Chapter 1.txt", c.source["Chapter 1.txt"])
        assertTrue(c.truncated.isEmpty())
    }

    /* The site's own sequence wins over the numbers: a site can name chapters
       after their titles, so a filename's number is only as good as what could
       be parsed out of one. */
    @Test
    fun `the site's listing order beats the numbers in the names`() {
        val c = resolve(
            listOf(f("Chapter 1.txt"), f("Chapter 2.txt"), f("Chapter 3.txt")),
            order = mapOf("Chapter 1.txt" to 2, "Chapter 2.txt" to 0, "Chapter 3.txt" to 1),
        )
        assertEquals(listOf("Chapter 2.txt", "Chapter 3.txt", "Chapter 1.txt"), c.ordered)
    }

    /* A chapter the recorded order doesn't cover still has to land in the
       right place rather than at the end. */
    @Test
    fun `a chapter missing from the recorded order slots in beside its neighbours`() {
        val c = resolve(
            listOf(f("Chapter 1.txt"), f("Chapter 2.txt"), f("Chapter 3.txt")),
            order = mapOf("Chapter 1.txt" to 0, "Chapter 3.txt" to 1),
        )
        assertEquals(listOf("Chapter 1.txt", "Chapter 2.txt", "Chapter 3.txt"), c.ordered)
    }

    /* A chapter downloaded after a compress pass is loose beside its own .gz,
       and the loose one is the newer. */
    @Test
    fun `a loose chapter wins over its compressed twin`() {
        val c = resolve(listOf(f("Chapter 1.txt", "loose"), f("Chapter 1.txt.gz", "archive")))
        assertEquals("loose", c.source["Chapter 1.txt"])
        assertEquals(listOf("Chapter 1.txt"), c.ordered)
    }

    @Test
    fun `a compressed chapter with no loose copy is still a chapter`() {
        val c = resolve(listOf(f("Chapter 1.txt.gz", "archive")))
        assertEquals(listOf("Chapter 1.txt"), c.ordered)
        assertTrue(Zips.isGzRef(c.source["Chapter 1.txt"]!!))
    }

    /* DEFECT 1. A 0-byte "Chapter N.txt" — what a killed run or a full volume
       leaves — reads back as "" rather than null, so the reader's
       unreadable-chapter handling never fired. It recorded a chapter of zero
       length instead: a blank header, the next chapter's text immediately
       below it, and the empty chapter saved as the place the novel reopens
       at. */
    @Test
    fun `an empty chapter file is not offered as readable text`() {
        val c = resolve(listOf(f("Chapter 1.txt", size = 0L)))
        assertNull("an empty file must not resolve to anything", c.source["Chapter 1.txt"])
        assertEquals(setOf("Chapter 1.txt"), c.truncated)
    }

    /* ...but the chapter still EXISTS, and dropping the name is not the
       answer either — see the next test. */
    @Test
    fun `an unreadable chapter keeps its place in the listing`() {
        val c = resolve(listOf(f("Chapter 1.txt", size = 0L), f("Chapter 2.txt")))
        assertEquals(listOf("Chapter 1.txt", "Chapter 2.txt"), c.ordered)
    }

    /* DEFECT 2, and the reason the name stays. Dropping it took the chapter
       out of the listing, and `retainAll` then discarded its TRANSLATION with
       it — content the user paid for, which the reader was perfectly able to
       show. */
    @Test
    fun `a paid translation survives its source going empty`() {
        val c = resolve(
            listOf(f("Chapter 1.txt", size = 0L)),
            translated = listOf(f("Chapter 1.txt", "english")),
        )
        assertEquals("english", c.translated["Chapter 1.txt"])
        assertTrue("the chapter must still be reachable", "Chapter 1.txt" in c.ordered)
    }

    /* ...without keeping an English file that answers for no chapter at all. */
    @Test
    fun `a translation with no chapter behind it is dropped`() {
        val c = resolve(
            listOf(f("Chapter 1.txt")),
            translated = listOf(f("Chapter 1.txt", "en1"), f("Chapter 9.txt", "en9")),
        )
        assertEquals(setOf("Chapter 1.txt"), c.translated.keys)
    }

    /* A good copy under either name settles it — the 0-byte loose file beside
       a sound archive is just the wreckage of an interrupted uncompress. */
    @Test
    fun `an empty loose file beside a good archive is not a truncated chapter`() {
        val c = resolve(listOf(f("Chapter 1.txt", size = 0L), f("Chapter 1.txt.gz", "archive")))
        assertTrue(c.truncated.isEmpty())
        assertEquals(listOf("Chapter 1.txt"), c.ordered)
        assertTrue(Zips.isGzRef(c.source["Chapter 1.txt"]!!))
    }

    /* Only a KNOWN zero. A provider that reports no size at all gives -1, and
       treating that as empty would empty the library. */
    @Test
    fun `a file whose size the provider does not report is not treated as empty`() {
        val c = resolve(listOf(f("Chapter 1.txt", size = -1L)))
        assertEquals("id:Chapter 1.txt", c.source["Chapter 1.txt"])
        assertTrue(c.truncated.isEmpty())
    }

    /* The tree can be a folder the user keeps other things in. */
    @Test
    fun `files that are not chapters are ignored`() {
        val c = resolve(
            listOf(
                f("Chapter 1.txt"), f("notes.txt"), f("cover.jpg"),
                f("movie.mp4.part"), Folder.Item("translated", "d", isDir = true),
            ),
        )
        assertEquals(listOf("Chapter 1.txt"), c.ordered)
    }

    /* A translation is filed under its chapter's filename, whatever that
       filename is — including the "Chapter N - Title.txt" form an older build
       wrote, which a library can still be full of, and compressed on both
       sides. This is the shape a real folder turned up in. */
    @Test
    fun `a compressed translation is found under a legacy titled name`() {
        val c = resolve(
            listOf(
                f("Chapter 1 - Khong chi thich mot nguoi.txt.gz", "vi1"),
                f("Chapter 1.txt.gz", "vi1b"),
            ),
            translated = listOf(f("Chapter 1 - Khong chi thich mot nguoi.txt.gz", "en1")),
        )
        assertTrue(
            "the reader has nothing to switch to unless this is found",
            c.translated.isNotEmpty(),
        )
        assertTrue(Zips.isGzRef(c.translated["Chapter 1 - Khong chi thich mot nguoi.txt"]!!))
    }

    /* ---- the cached listing's spot-check ---- */

    @Test
    fun `a cache whose files are all there is kept`() {
        val ordered = (1..40).map { "Chapter $it.txt" }
        val source = ordered.associateWith { "id:$it" }
        assertTrue(Folder.cacheValid(ordered, source, 6) { true })
    }

    @Test
    fun `a cache with a file that has gone is thrown away`() {
        val ordered = (1..40).map { "Chapter $it.txt" }
        val source = ordered.associateWith { "id:$it" }
        assertFalse(Folder.cacheValid(ordered, source, 6) { it != "id:Chapter 40.txt" })
    }

    /* DEFECT 3. A name with no ref is the walk having RECORDED that chapter as
       unreadable, not evidence the cache is stale. Reading it as stale threw
       the listing away and re-walked the folder on every open, permanently:
       one probe is the last chapter — exactly where a file left empty by a
       full volume tends to sit — and the re-walk records the same thing
       again. Listing a seven-thousand-file folder over SAF takes seconds, and
       avoiding that is the only reason the cache exists. */
    @Test
    fun `an unreadable chapter does not invalidate the cache that recorded it`() {
        val ordered = (1..40).map { "Chapter $it.txt" }
        val source = ordered.dropLast(1).associateWith { "id:$it" }   // the last one is truncated
        assertTrue(
            "a chapter recorded as unreadable must not force a re-walk on every open",
            Folder.cacheValid(ordered, source, 6) { true },
        )
    }

    /* The probes have to reach into the middle: probing only the ends missed
       anything removed between them, and the reader skipped it with no gap
       shown. */
    @Test
    fun `the spot-check samples across the novel, not just its ends`() {
        val probes = Folder.probePositions(1000, 6)
        assertTrue("must probe the first", 0 in probes)
        assertTrue("must probe the last", 999 in probes)
        assertTrue("must probe the middle", probes.any { it in 300..700 })
        assertTrue("a handful of lookups, not a walk", probes.size <= 8)
    }

    /* ---- the folder stamp ---- */

    /* DEFECT 4, and the reason the stamp exists. Every check above asks
       whether what the cache RECORDED is still there. Nothing asked whether
       anything had ARRIVED — and translated/ fills in after the chapters do,
       from a translation run that finishes long after the listing was cached
       or from a computer writing into the same folder. The listing went on
       reporting no translations for ever, so the reader offered no language to
       switch to while the English sat on disk beside the Vietnamese. */
    @Test
    fun `a translation appearing after the listing was cached invalidates it`() {
        val cached = Folder.Stamp("novel", 1000L, "tr", 2000L)
        val now = Folder.Stamp("novel", 1000L, "tr", 2500L)   // a file landed in translated/
        assertFalse(Folder.folderUnchanged(cached, now))
    }

    /* translated/ created since — the novel folder's own mtime is what moves */
    @Test
    fun `a translated folder appearing invalidates the listing`() {
        val cached = Folder.Stamp("novel", 1000L, "", 0L)
        val now = Folder.Stamp("novel", 3000L, "", 0L)
        assertFalse(Folder.folderUnchanged(cached, now))
    }

    @Test
    fun `a folder nothing has touched keeps its listing`() {
        val s = Folder.Stamp("novel", 1000L, "tr", 2000L)
        assertTrue(Folder.folderUnchanged(s, s.copy()))
    }

    /* Every listing cached by a build that could not notice a translation
       arriving carries no stamp. Trusting those would leave exactly the
       libraries this fixes still broken, so one re-walk repairs them. */
    @Test
    fun `a listing cached without a stamp is stale`() {
        assertFalse(Folder.folderUnchanged(null, Folder.Stamp("novel", 1000L, "", 0L)))
    }

    /* A provider that reports no mtime reports none on both sides. The check
       then says nothing rather than something wrong — and must not re-walk a
       seven-thousand-file folder on every open for want of an answer. */
    @Test
    fun `a provider that reports no times does not force a re-walk`() {
        val s = Folder.Stamp("novel", 0L, "tr", 0L)
        assertTrue(Folder.folderUnchanged(s, Folder.Stamp("novel", 0L, "tr", 0L)))
    }

    @Test
    fun `a stamp survives the round trip through the two columns it is kept in`() {
        val s = Folder.Stamp("primary:Novels/His Freud", 1_700_000_000_000L, "primary:x/tr", 12L)
        val (dir, tr) = Folder.encodeStamp(s)
        assertEquals(s, Folder.decodeStamp(dir, tr))
    }

    /* the meta row an older build left behind is not a stamp */
    @Test
    fun `anything that is not a stamp decodes to none`() {
        assertNull(Folder.decodeStamp("", ""))
        assertNull(Folder.decodeStamp("id", "id"))
    }

    @Test
    fun `the spot-check copes with a one-chapter novel`() {
        assertEquals(listOf(0), Folder.probePositions(1, 6).filter { it >= 0 }.distinct())
        assertTrue(Folder.cacheValid(listOf("Chapter 1.txt"), mapOf("Chapter 1.txt" to "id"), 6) { true })
    }
}
