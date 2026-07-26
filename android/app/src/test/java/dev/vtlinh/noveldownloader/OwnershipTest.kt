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
}
