package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/* Which file becomes which chapter when the site's listing shifts.

   "Chapter N.txt" means the Nth entry in the site's listing, so inserting or
   removing a chapter renames everything after it. Getting this wrong doesn't
   fail loudly — it silently serves one chapter's text under another's name,
   and the recorded page travels with the move, so the mistake becomes the
   new truth. Every case here is one that shipped. */
class RenumberTest {

    private fun slots(vararg pairs: Pair<String, String>) =
        pairs.map { (url, want) -> Renumber.Slot(url, want) }

    /* nothing changed: no moves, and no needless work */
    @Test
    fun `a listing that matches the disk plans nothing`() {
        val plan = Renumber.plan(
            slots("u1" to "Chapter 1.txt", "u2" to "Chapter 2.txt"),
            byUrl = mapOf("u1" to "Chapter 1.txt", "u2" to "Chapter 2.txt"),
            onRecord = setOf("Chapter 1.txt", "Chapter 2.txt"),
            legacy = emptyMap(),
        )
        assertTrue(plan.pending.isEmpty())
        assertTrue(plan.linkNow.isEmpty())
    }

    /* the site inserts a chapter at the front: everything shifts up one, and
       the walk must be last-chapter-first so each target is free in turn */
    @Test
    fun `an inserted chapter shifts everything after it up`() {
        val plan = Renumber.plan(
            slots("new" to "Chapter 1.txt", "u1" to "Chapter 2.txt", "u2" to "Chapter 3.txt"),
            byUrl = mapOf("u1" to "Chapter 1.txt", "u2" to "Chapter 2.txt"),
            onRecord = setOf("Chapter 1.txt", "Chapter 2.txt"),
            legacy = emptyMap(),
        )
        assertEquals("Chapter 3.txt", plan.pending["Chapter 2.txt"])
        assertEquals("Chapter 2.txt", plan.pending["Chapter 1.txt"])
        /* highest first, so each move frees the slot the next one needs */
        assertEquals(listOf("Chapter 2.txt", "Chapter 1.txt"), plan.pending.keys.toList())
    }

    /* the site drops a chapter: everything after it shifts down */
    @Test
    fun `a removed chapter shifts everything after it down`() {
        val plan = Renumber.plan(
            slots("u1" to "Chapter 1.txt", "u3" to "Chapter 2.txt"),
            byUrl = mapOf("u1" to "Chapter 1.txt", "u2" to "Chapter 2.txt", "u3" to "Chapter 3.txt"),
            onRecord = setOf("Chapter 1.txt", "Chapter 2.txt", "Chapter 3.txt"),
            legacy = emptyMap(),
        )
        assertEquals("Chapter 2.txt", plan.pending["Chapter 3.txt"])
        /* the dropped chapter's own file is not moved by this pass — it is no
           longer listed, so it is the dedupe's business, not the renamer's */
        assertNull(plan.pending["Chapter 2.txt"])
    }

    /* A file whose page is recorded has PROVED which chapter it is. The
       legacy fallback only guesses from the site's own numbering, and the two
       schemes share one namespace — so where number and position disagree the
       guess could land on a file another chapter had already proved was its
       own, and rename it away from that chapter. */
    @Test
    fun `a legacy guess never steals a file that a recorded page owns`() {
        val plan = Renumber.plan(
            slots("uA" to "Chapter 39.txt", "uB" to "Chapter 40.txt"),
            byUrl = mapOf("uA" to "Chapter 39.txt"),      // A has proved it owns 39
            onRecord = setOf("Chapter 39.txt"),
            legacy = mapOf("uB" to "Chapter 39.txt"),     // B's guess points at the same file
            )
        assertTrue("B must not be able to claim A's proven file", plan.pending.isEmpty())
    }

    /* the legacy path is still how a pre-identity library gets recognised */
    @Test
    fun `a legacy guess is used when nothing else claims the file`() {
        val plan = Renumber.plan(
            slots("uB" to "Chapter 40.txt"),
            byUrl = emptyMap(),
            onRecord = setOf("Chapter 39.txt"),
            legacy = mapOf("uB" to "Chapter 39.txt"),
        )
        assertEquals("Chapter 40.txt", plan.pending["Chapter 39.txt"])
        /* and the page gets recorded once the move lands, so the next run
           doesn't have to guess again from the same ambiguous namespace */
        assertEquals("uB", plan.claimedUrl["Chapter 39.txt"])
    }

    /* a file already in the right place whose page was never recorded should
       be adopted in place, not moved */
    @Test
    fun `a file already in place gets its page recorded without moving`() {
        val plan = Renumber.plan(
            slots("uB" to "Chapter 39.txt"),
            byUrl = emptyMap(),
            onRecord = setOf("Chapter 39.txt"),
            legacy = mapOf("uB" to "Chapter 39.txt"),
        )
        assertTrue(plan.pending.isEmpty())
        assertEquals(listOf("Chapter 39.txt" to "uB"), plan.linkNow)
    }

    /* THE WHOLE-LIBRARY SHIFT. A library adopted by the folder scan, or
       rebuilt after a reinstall, is indexed by NAME with no page against any
       row — so `byUrl` is empty, `spokenFor` with it, and the legacy guess is
       the only thing matching anything. Where the site's own numbering runs
       one behind the listing position (a listing whose first entry is an
       unnumbered extra — a character page, a prologue the site doesn't
       number) that guess lands on the previous chapter's file for EVERY
       chapter, and the plan is a clean one-slot shift of the entire novel.

       Nothing then reports it: each file is written back with its
       neighbour's page, so the next run agrees with the corrupted mapping and
       no chapter is ever re-fetched. A file sitting at another chapter's
       position name is evidence of the position scheme, not the legacy one. */
    @Test
    fun `a legacy guess does not shift a library that is already named by position`() {
        /* four listed chapters; the site numbers them 1..3 from position 2 on */
        val plan = Renumber.plan(
            slots(
                "u0" to "Chapter 1.txt", "u1" to "Chapter 2.txt",
                "u2" to "Chapter 3.txt", "u3" to "Chapter 4.txt",
            ),
            byUrl = emptyMap(),
            onRecord = setOf("Chapter 1.txt", "Chapter 2.txt", "Chapter 3.txt", "Chapter 4.txt"),
            legacy = mapOf("u1" to "Chapter 1.txt", "u2" to "Chapter 2.txt", "u3" to "Chapter 3.txt"),
        )
        assertTrue("the whole novel must not slide one slot: ${plan.pending}", plan.pending.isEmpty())
        assertTrue("...and no file may be stamped with a neighbour's page", plan.claimedUrl.isEmpty())
    }

    /* ...without disarming the fallback where it belongs: a legacy name
       outside the position scheme's namespace is still the only evidence a
       pre-identity library offers. */
    @Test
    fun `a legacy guess outside the listing's own names still works`() {
        val plan = Renumber.plan(
            slots("uA" to "Chapter 1.txt", "uB" to "Chapter 2.txt"),
            byUrl = emptyMap(),
            onRecord = setOf("Chapter 900.txt", "Chapter 901.txt"),
            legacy = mapOf("uA" to "Chapter 900.txt", "uB" to "Chapter 901.txt"),
        )
        assertEquals("Chapter 1.txt", plan.pending["Chapter 900.txt"])
        assertEquals("Chapter 2.txt", plan.pending["Chapter 901.txt"])
    }

    /* a legacy name the index has never heard of is not evidence of anything */
    @Test
    fun `a legacy guess at a file that is not on record is ignored`() {
        val plan = Renumber.plan(
            slots("uB" to "Chapter 40.txt"),
            byUrl = emptyMap(),
            onRecord = emptySet(),
            legacy = mapOf("uB" to "Chapter 39.txt"),
        )
        assertTrue(plan.pending.isEmpty())
    }

    /* a chapter with no usable name contributes nothing rather than throwing
       off the chapters around it */
    @Test
    fun `an entry with no name is skipped`() {
        val plan = Renumber.plan(
            listOf(Renumber.Slot("u1", null), Renumber.Slot("u2", "Chapter 2.txt")),
            byUrl = mapOf("u1" to "Chapter 1.txt", "u2" to "Chapter 1.txt"),
            onRecord = setOf("Chapter 1.txt"),
            legacy = emptyMap(),
        )
        assertEquals(1, plan.pending.size)
    }

    /* A plan must never send two files to the same name — that is how one
       chapter's text ends up written over another's. */
    @Test
    fun `no two files are ever planned onto the same name`() {
        val n = 200
        val order = (1..n).map { Renumber.Slot("u$it", "Chapter ${it + 1}.txt") }
        val byUrl = (1..n).associate { "u$it" to "Chapter $it.txt" }
        val plan = Renumber.plan(order, byUrl, byUrl.values.toSet(), emptyMap())
        val targets = plan.pending.values
        assertEquals("every move must have a distinct target", targets.size, targets.toSet().size)
    }

    /* Applying a plan and re-planning must produce nothing: a pass that keeps
       finding work is a pass that walks the library a little further out of
       place on every run. */
    @Test
    fun `re-planning after applying a plan is a no-op`() {
        val n = 50
        val order = (1..n).map { Renumber.Slot("u$it", "Chapter ${it + 1}.txt") }
        val before = (1..n).associate { "u$it" to "Chapter $it.txt" }
        val plan = Renumber.plan(order, before, before.values.toSet(), emptyMap())

        // apply it: every from -> to move, highest first
        val after = before.toMutableMap()
        for ((from, to) in plan.pending) {
            val url = after.entries.first { it.value == from }.key
            after[url] = to
        }
        val again = Renumber.plan(order, after, after.values.toSet(), emptyMap())
        assertTrue("a settled library must plan no further moves", again.pending.isEmpty())
    }

    /* the whole point of the reversed walk: a shift up must not plan a move
       into a slot that is still occupied by a file moving later */
    @Test
    fun `a shift up is ordered so each target is freed before it is needed`() {
        val n = 20
        val order = (1..n).map { Renumber.Slot("u$it", "Chapter ${it + 1}.txt") }
        val byUrl = (1..n).associate { "u$it" to "Chapter $it.txt" }
        val plan = Renumber.plan(order, byUrl, byUrl.values.toSet(), emptyMap())

        val occupied = byUrl.values.toMutableSet()
        for ((from, to) in plan.pending) {
            assertFalse("$to must be free when $from moves into it", to in occupied)
            occupied.remove(from)
            occupied.add(to)
        }
    }
}
