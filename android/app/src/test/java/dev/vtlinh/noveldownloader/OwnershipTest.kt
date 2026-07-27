package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* Which folder a novel writes into. There is no way to get this wrong
   cheaply: one direction overwrites another book's chapters, the other
   re-downloads and re-translates a book that is already on disk. */
class OwnershipTest {

    private fun choose(
        slug: String = "tu-tien",
        wanted: String = "Tu Tien",
        owner: String? = null,
        recordedDir: String? = null,
        myChapters: Int = 0,
        indexKnowsOthers: Boolean = false,
        recordedDirOnDisk: Boolean = false,
        wantedOccupied: Boolean = false,
    ) = Ownership.choose(
        slug = slug,
        wanted = wanted,
        alt = "$wanted ($slug)",
        owner = owner,
        recordedDir = recordedDir,
        myChapters = myChapters,
        indexKnowsOthers = indexKnowsOthers,
        recordedDirOnDisk = { recordedDirOnDisk },
        wantedOccupied = { wantedOccupied },
    )

    /* A first download: nothing recorded anywhere, nothing on disk. */
    @Test
    fun `a brand new novel takes the name its title computes to`() {
        assertEquals("Tu Tien", choose().name)
        assertFalse(choose().stepAside)
    }

    @Test
    fun `a novel that claimed the name keeps it`() {
        val c = choose(owner = "tu-tien", wantedOccupied = true)
        assertEquals("Tu Tien", c.name)
        assertFalse(c.stepAside)
    }

    /* Two Vietnamese titles routinely sanitise to one name. The second novel
       must not move into the first one's folder and rename its chapters. */
    @Test
    fun `a name another novel claimed is stepped around`() {
        val c = choose(owner = "than-y-a", slug = "than-y-b", wanted = "Than Y")
        assertEquals("Than Y (than-y-b)", c.name)
        assertTrue(c.stepAside)
    }

    /* Same collision on a library from before names were claimed: nobody owns
       the name on record, but there is a full folder sitting under it and the
       index knows the other novel. Not ours. */
    @Test
    fun `an unclaimed but occupied folder belonging to a known other novel is stepped around`() {
        val c = choose(
            slug = "than-y-b",
            wanted = "Than Y",
            indexKnowsOthers = true,
            wantedOccupied = true,
        )
        assertEquals("Than Y (than-y-b)", c.name)
        assertTrue(c.stepAside)
    }

    /* THE REINSTALL. App reinstalled, or its data cleared, on top of a novel
       folder that is still there: no owner, no recorded directory, no
       chapters in the index — and no OTHER novel in the index either, so
       nobody else can be the folder's owner. Treating this as somebody
       else's work built "Tu Tien (tu-tien)" beside the real folder and
       re-downloaded, and re-translated at the API's price, the whole book. */
    @Test
    fun `a restored folder is adopted after a reinstall rather than duplicated`() {
        val c = choose(wantedOccupied = true, indexKnowsOthers = false)
        assertEquals("Tu Tien", c.name)
        assertFalse("a reinstall must not fork the folder", c.stepAside)
    }

    /* The library that predates the recorded directory: our own chapters are
       in the index, the folder is where it has always been. */
    @Test
    fun `an existing library stays in the folder it has always used`() {
        val c = choose(myChapters = 400, indexKnowsOthers = true, wantedOccupied = true)
        assertEquals("Tu Tien", c.name)
        assertFalse(c.stepAside)
    }

    /* Recorded name wins over the computed one: a novel pushed onto a
       suffixed folder recomputes the UNSUFFIXED name from its title every
       run, which is the other novel's. */
    @Test
    fun `a novel goes back to the folder it is recorded as using`() {
        val c = choose(
            slug = "than-y-b",
            wanted = "Than Y",
            recordedDir = "Than Y (than-y-b)",
            recordedDirOnDisk = true,
            myChapters = 400,
        )
        assertEquals("Than Y (than-y-b)", c.name)
        assertTrue(c.recorded)
        assertFalse(c.stepAside)
    }

    /* A recorded directory that is no longer on disk (the user deleted or
       moved it) still must not send us into a folder somebody else holds. */
    @Test
    fun `a recorded folder that is gone does not become a licence to take another`() {
        val c = choose(
            slug = "than-y-b",
            wanted = "Than Y",
            recordedDir = "Than Y (moved away)",
            recordedDirOnDisk = false,
            myChapters = 400,
            indexKnowsOthers = true,
            wantedOccupied = true,
        )
        assertEquals("Than Y (than-y-b)", c.name)
        assertTrue(c.stepAside)
    }

    /* An empty directory under the name is not somebody's work — it is what a
       failed run leaves behind, and forking off it strands the novel in a new
       folder for good. */
    @Test
    fun `an empty folder under the name is used rather than forked from`() {
        val c = choose(indexKnowsOthers = true, wantedOccupied = false)
        assertEquals("Tu Tien", c.name)
        assertFalse(c.stepAside)
    }

    /* Listing a folder of several thousand chapters over SAF takes seconds.
       The ordinary path — the folder is plainly ours — must not pay it. */
    @Test
    fun `the folder is not listed when it is plainly ours`() {
        var listed = false
        val c = Ownership.choose(
            slug = "tu-tien",
            wanted = "Tu Tien",
            alt = "Tu Tien (tu-tien)",
            owner = "tu-tien",
            recordedDir = "Tu Tien",
            myChapters = 400,
            indexKnowsOthers = true,
            recordedDirOnDisk = { throw AssertionError("should not look on disk") },
            wantedOccupied = { listed = true; true },
        )
        assertEquals("Tu Tien", c.name)
        assertFalse("no SAF listing on the ordinary path", listed)
    }

    /* `ours` on its own, since the engine's claim and record calls hang off
       the same question. */
    @Test
    fun `ownership does not depend on the name when a directory is recorded`() {
        assertFalse(
            "a recorded directory is the whole answer — a different computed name is not ours",
            Ownership.ours("b", "Than Y", null, "Than Y (b)", 400, true),
        )
        assertTrue(Ownership.ours("b", "Than Y (b)", null, "Than Y (b)", 400, true))
    }

    /* THE CLAIM WE NEVER RELEASED. A novel downloaded untranslated claims its
       Vietnamese folder; translating it renames the directory to the English
       title and claims that too — and nothing releases the first claim. So on
       any later run that computes the Vietnamese name back (translation
       switched off, a title batch that failed) the name is still owned by us.

       Answering "ours" there sent the run into a folder that no longer
       exists: a new EMPTY one was created and recorded, the run downloaded
       nothing because the index still resolved into the real folder, the
       library pointed at the empty directory — and a translation pass then
       found nothing done and re-submitted the entire novel to the API. The
       recorded directory has to be consulted first. */
    @Test
    fun `a claim on the old name does not outrank the folder we are recorded in`() {
        val c = choose(
            slug = "tu-tien",
            wanted = "Tu Tien",                 // the Vietnamese name, computed again
            owner = "tu-tien",                  // ...still claimed by us
            recordedDir = "Cultivation",        // but this is where the chapters are
            recordedDirOnDisk = true,
            myChapters = 400,
        )
        assertEquals("Cultivation", c.name)
        assertTrue(c.recorded)
        assertFalse(c.stepAside)
    }

    /* ...and when that recorded directory really is gone, our own claim is the
       best evidence left. Stepping aside here would build a second folder
       beside our own and download the book into it again. */
    @Test
    fun `our own claim still counts when the recorded folder is gone`() {
        val c = choose(
            slug = "tu-tien",
            wanted = "Tu Tien",
            owner = "tu-tien",
            recordedDir = "Cultivation",
            recordedDirOnDisk = false,
            myChapters = 400,
            indexKnowsOthers = true,
            wantedOccupied = true,
        )
        assertEquals("Tu Tien", c.name)
        assertFalse(c.stepAside)
    }

    /* ---- which folder a translated novel writes into ---- */

    private fun translated(
        english: String = "Divine Doctor (Than Y)",
        vietName: String = "Than Y",
        recordedDir: String? = null,
        vietIsOurs: Boolean = true,
        onDisk: Set<String> = emptySet(),
    ) = Ownership.translatedFolder(english, vietName, recordedDir, vietIsOurs) { it in onDisk }

    /* A novel with no folder yet saves straight into the English name —
       creating a directory touches nothing that exists, so none of the
       renaming failures below can happen. */
    @Test
    fun `a new translated novel gets the English folder name`() {
        assertEquals("Divine Doctor (Than Y)", translated())
    }

    /* THE RENAME, REMOVED. This used to rename the Vietnamese folder to the
       English title, and it went wrong in every direction it could: findFile
       matches on the display name alone, so it renamed other novels' folders
       — the sites serve one title under two slugs, and sanitising folds tone
       marks — which made their chapters look unclaimed and got them adopted
       and re-translated at the API's price. A rename that failed, or one
       recorded in the wrong order, left an empty folder beside a full one and
       the whole novel was re-sent to the API. A folder that already holds a
       novel now simply keeps its name. */
    @Test
    fun `a novel that already has a folder keeps it`() {
        assertEquals("Than Y", translated(vietIsOurs = true, onDisk = setOf("Than Y")))
        assertEquals(
            "Cultivation",
            translated(recordedDir = "Cultivation", onDisk = setOf("Cultivation")),
        )
    }

    /* ...and the folder it keeps is the one it is ON RECORD as using, not the
       one its title happens to compute to. */
    @Test
    fun `the recorded folder outranks the name the title computes to`() {
        assertEquals(
            "Than Y (than-y-b)",
            translated(
                recordedDir = "Than Y (than-y-b)",
                onDisk = setOf("Than Y", "Than Y (than-y-b)"),
            ),
        )
    }

    /* A Vietnamese folder that is NOT ours is left alone — that is the case
       that used to get somebody else's book renamed and adopted. */
    @Test
    fun `another novel's folder is never taken over`() {
        assertEquals(
            "Divine Doctor (Than Y)",
            translated(vietIsOurs = false, onDisk = setOf("Than Y")),
        )
    }

    /* A recorded directory that has gone from disk is not a licence to move
       into the computed name either — that may be another novel's. */
    @Test
    fun `a recorded folder that is gone falls back to the English name`() {
        assertEquals(
            "Divine Doctor (Than Y)",
            translated(recordedDir = "Cultivation", onDisk = setOf("Than Y")),
        )
    }

    /* PUNCTUATION DRIFT. The one-time folder scan derives a slug from the
       folder NAME, so "Library of Heaven's Path" is adopted under
       "library-of-heaven-s-path" while the site's own slug for the same book
       is "library-of-heavens-path". Compared as strings those are two
       different novels — so the app treated its own library as somebody
       else's, stepped aside into "Title (slug)", and re-downloaded and
       re-translated a book already complete on disk. */
    @Test
    fun `a slug that differs only in punctuation is the same novel`() {
        assertTrue(
            Ownership.ours(
                "library-of-heavens-path", "Library of Heavens Path",
                "library-of-heaven-s-path", null, 0, true,
            ),
        )
        val c = choose(
            slug = "library-of-heavens-path",
            wanted = "Library of Heavens Path",
            owner = "library-of-heaven-s-path",
            wantedOccupied = true,
            indexKnowsOthers = true,
        )
        assertEquals("Library of Heavens Path", c.name)
        assertFalse("its own folder must not be forked", c.stepAside)
    }

    /* ...without making every slug equal to every other. The drift rule drops
       punctuation, not digits or letters — the sites disambiguate same-titled
       books with a numeric suffix, and those are genuinely different novels. */
    @Test
    fun `dropping punctuation does not merge two different novels`() {
        assertFalse(Ownership.ours("than-y-b", "Than Y", "than-y-a", null, 0, true))
        assertFalse(
            Ownership.ours("than-dao-dan-ton", "Than Dao", "than-dao-dan-ton-6060282", null, 0, true),
        )
        assertEquals("thanya", Ownership.normKey("than-y-a"))
        assertEquals("thanya", Ownership.normKey("Than_Y.A"))
    }
}
