package dev.vtlinh.noveldownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/* Which spoken sentence a TextToSpeech report belongs to.

   Every sentence goes to the engine with QUEUE_FLUSH, which throws away
   whatever was mid-utterance — and the discarded utterance still reports,
   as onDone on some engines and onError on others. It arrives after the
   replacement has started and looks exactly like the replacement finishing.

   With one fixed id for every sentence the two were indistinguishable, and
   the stale report advanced the cursor: a sentence skipped, unread and
   unmarked, on every seek, chapter jump and press of Play. */
class UtteranceTest {

    @Test
    fun `the report for the sentence being spoken is acted on`() {
        assertTrue(Utterance.isCurrent(Utterance.id(7), 7))
    }

    /* THE BUG. Speaking a new sentence flushes the old one; the old one's
       report lands afterwards carrying the tag it was given. */
    @Test
    fun `a report from a flushed sentence is ignored`() {
        val flushed = Utterance.id(7)      // spoken, then the user sought
        assertFalse("a stale report must not advance the reader", Utterance.isCurrent(flushed, 8))
    }

    /* Stopping retires the tag too, so nothing that was in flight can come
       back and start us reading again. */
    @Test
    fun `a report that arrives after a stop is ignored`() {
        var generation = 3L
        val inFlight = Utterance.id(generation)
        generation++                       // pauseTts / stopTts
        assertFalse(Utterance.isCurrent(inFlight, generation))
    }

    /* The engine may report with no id at all, and then there is nothing
       saying the report is about what we are currently saying. */
    @Test
    fun `a report with no id is not treated as current`() {
        assertFalse(Utterance.isCurrent(null, 0))
        assertFalse(Utterance.isCurrent(null, 9))
    }

    /* Ids have to be distinguishable across the whole run, not merely
       different from the immediately previous one — reports can arrive well
       out of order under load. */
    @Test
    fun `every generation gets its own id`() {
        val seen = HashSet<String>()
        for (g in 0L..500L) seen.add(Utterance.id(g))
        assertEquals(501, seen.size)
        assertNotEquals(Utterance.id(1), Utterance.id(11))
        assertFalse("no id may be a prefix of another", Utterance.isCurrent(Utterance.id(1), 11))
    }

    /* A long listening session must not wrap into a tag it used before. */
    @Test
    fun `the tag survives a long session`() {
        assertTrue(Utterance.isCurrent(Utterance.id(Long.MAX_VALUE), Long.MAX_VALUE))
        assertFalse(Utterance.isCurrent(Utterance.id(Long.MAX_VALUE - 1), Long.MAX_VALUE))
    }
}
