package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/* What becomes of a translation when its chapter is deleted as a duplicate.

   This is the rule that decides whether content the user paid an API to
   produce survives a tidy-up, so it is asked here rather than reasoned about
   inside the sweep that performs it. */
class TranslationsTest {

    /* the shape a real library turned up in: an older build named chapters
       after the number the site printed and put the title in the filename, and
       translations were only ever written under those names */
    private val legacy = "Chapter 12 - Doi thanh nguoi khac toi se tran.txt"
    private val current = "Chapter 12.txt"

    /* THE DEFECT. Removing the duplicate took its translation with it, by base
       name. Translations existed only under the OLD names, so the copy being
       deleted was the one holding the English and the copy being kept had
       none — the novel lost its translation chapter by chapter, and the reader
       then offered no language to switch to because there was nothing left. */
    @Test
    fun `the kept chapter inherits the translation instead of losing it`() {
        val moves = Translations.handover(legacy, current, setOf("$legacy.gz"))
        assertEquals(
            listOf(Translations.Move.Rename("$legacy.gz", "$current.gz")),
            moves,
        )
    }

    /* A source and its translation compress independently, so the translation
       may be loose while the chapter is gzipped or the other way round. Its
       own suffix is what it keeps. */
    @Test
    fun `a loose translation stays loose`() {
        assertEquals(
            listOf(Translations.Move.Rename(legacy, current)),
            Translations.handover(legacy, current, setOf(legacy)),
        )
    }

    /* Both forms of the doomed name — what an interrupted compress pass leaves
       behind — each move to the matching form of the kept name. */
    @Test
    fun `both forms move, each keeping its own suffix`() {
        val moves = Translations.handover(legacy, current, setOf(legacy, "$legacy.gz"))
        assertTrue(Translations.Move.Rename(legacy, current) in moves)
        assertTrue(Translations.Move.Rename("$legacy.gz", "$current.gz") in moves)
        assertEquals(2, moves.size)
    }

    /* When the kept chapter is ALREADY translated the old one is a genuine
       duplicate and goes. Renaming onto a name that exists is the thing to
       avoid: SAF answers a collision by minting "Chapter 12.txt (1).gz", a
       name no pattern in this app matches, so the English would belong to
       neither chapter and be invisible to every sweep that might notice. */
    @Test
    fun `a translation the kept chapter already has is deleted, not renamed`() {
        assertEquals(
            listOf(Translations.Move.Delete("$legacy.gz")),
            Translations.handover(legacy, current, setOf("$legacy.gz", "$current.gz")),
        )
    }

    /* Either form counts as already translated — the two need not agree. */
    @Test
    fun `a loose translation on the kept name also counts as already there`() {
        assertEquals(
            listOf(Translations.Move.Delete("$legacy.gz")),
            Translations.handover(legacy, current, setOf("$legacy.gz", current)),
        )
    }

    /* Most of a library is untranslated. This must not cost a rename, or a
       thought, for every duplicate tidied away. */
    @Test
    fun `an untranslated duplicate asks for nothing`() {
        assertTrue(Translations.handover(legacy, current, emptySet()).isEmpty())
        assertTrue(
            "another chapter's translation is not this one's",
            Translations.handover(legacy, current, setOf("Chapter 9.txt.gz")).isEmpty(),
        )
    }

    @Test
    fun `a chapter cannot hand its translation to itself`() {
        assertTrue(Translations.handover(current, current, setOf("$current.gz")).isEmpty())
    }

    /* ---- rescueKeeper: the heading-title fallback for a translation whose
       body match came up empty ---- */

    @Test
    fun `a unique untranslated keeper with the same title is the rescue target`() {
        assertEquals(
            "Chapter 12.txt",
            Translations.rescueKeeper(
                "Doi thanh nguoi khac",
                mapOf("Chapter 11.txt" to "Khac", "Chapter 12.txt" to "Doi thanh nguoi khac"),
                emptySet(),
            ),
        )
    }

    /* titles repeat — "Interlude" — and handing the English to the wrong twin
       serves one chapter's translation as another's for good. Ambiguity is a
       refusal, not a coin toss. */
    @Test
    fun `a repeated title rescues nothing`() {
        assertEquals(
            null,
            Translations.rescueKeeper(
                "Interlude",
                mapOf("Chapter 3.txt" to "Interlude", "Chapter 9.txt" to "Interlude"),
                emptySet(),
            ),
        )
    }

    /* a keeper that already has English is spoken for — offering it another
       copy DELETES the incoming one as a duplicate (Translations.handover),
       which for two same-titled leftovers destroys the second one's English */
    @Test
    fun `a keeper that is already translated is not offered another copy`() {
        assertEquals(
            null,
            Translations.rescueKeeper(
                "The Duel",
                mapOf("Chapter 5.txt" to "The Duel"),
                setOf("Chapter 5.txt"),
            ),
        )
    }

    /* THE DIVERSION. The title must be the sole bearer among ALL keepers,
       not merely among the untranslated ones: a leftover whose TRUE owner is
       already translated used to resolve onto the same-titled untranslated
       neighbour, and its English was served as that chapter's for good. A
       repeated title is ambiguous identity however the translations stand. */
    @Test
    fun `a translated true owner blocks the same-titled neighbour, not diverts to it`() {
        assertEquals(
            null,
            Translations.rescueKeeper(
                "The Duel",
                mapOf("Chapter 5.txt" to "The Duel", "Chapter 6.txt" to "The Duel"),
                setOf("Chapter 5.txt"),
            ),
        )
    }

    @Test
    fun `no title rescues nothing`() {
        assertEquals(null, Translations.rescueKeeper(null, mapOf("Chapter 1.txt" to "X"), emptySet()))
        assertEquals(null, Translations.rescueKeeper("", mapOf("Chapter 1.txt" to "X"), emptySet()))
    }
}
