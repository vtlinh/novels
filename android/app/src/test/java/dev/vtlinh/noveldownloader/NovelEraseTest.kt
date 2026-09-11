package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/* Which prefs a failed Library delete may touch. The folder wipe is
   SAF; the decision of whether the listen spot survives is here. */
class NovelEraseTest {

    /* THE DEFECT. Confirm → SAF refuses (renamed folder, provider
       error). The toast said nothing was removed, but lastCh / ttsPos
       / novelRead had already been dropped, so Continue opened at the
       start of a novel still on the list. */
    @Test
    fun `a failed folder delete must not drop the saved listen spot`() {
        val keys = NovelErase.prefKeysIfDeleted(folderDeleted = false, slug = "foo")
        assertTrue(keys.isEmpty())
    }

    @Test
    fun `a successful delete clears the place and the finished mark`() {
        val keys = NovelErase.prefKeysIfDeleted(folderDeleted = true, slug = "foo")
        assertTrue("ttsPos:foo" in keys)
        assertTrue("lastCh:foo" in keys)
        assertTrue("novelRead:foo" in keys)
        assertFalse(keys.any { !it.endsWith("foo") })
        assertEquals(NovelErase.PREF_PREFIXES.size, keys.size)
    }
}
