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
}
