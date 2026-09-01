package dev.vtlinh.noveldownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* Which first-level directories the compress pass may rewrite. The tree is
   a folder the user picked and can hold their own files; an empty owned set
   used to mean "walk everything", which gzipped those. */
class CompressionTest {

    @Test
    fun `an empty owned set does not open every directory`() {
        assertFalse(CompressWalk.includeNovelDir("My drafts", emptySet()))
        assertFalse(CompressWalk.includeNovelDir("A novel folder", emptySet()))
        assertFalse(CompressWalk.includeNovelDir("documents", emptySet()))
    }

    @Test
    fun `only a recorded novel folder is walked`() {
        val owned = setOf("Library of Heaven's Path")
        assertTrue(CompressWalk.includeNovelDir("Library of Heaven's Path", owned))
        assertFalse(CompressWalk.includeNovelDir("My drafts", owned))
        assertFalse(CompressWalk.includeNovelDir("Chapter 1 notes", owned))
    }

    @Test
    fun `the documents folder is never a novel target`() {
        assertFalse(CompressWalk.includeNovelDir("documents", setOf("documents")))
        assertFalse(CompressWalk.includeNovelDir("Documents", setOf("Documents")))
    }
}
