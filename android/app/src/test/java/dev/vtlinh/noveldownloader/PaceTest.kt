package dev.vtlinh.noveldownloader

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PaceTest {
    private var now = 1_000_000L
    private fun key() = "pace-test-${java.util.UUID.randomUUID()}"

    @Before fun fakeClock() { Pace.clock = { now } }
    @After fun realClock() { Pace.clock = System::currentTimeMillis }

    @Test
    fun `requests to a paced site start no closer than its gap`() {
        val k = key()
        assertEquals(0L, Pace.claim(k, 1_500))
        assertEquals(1_500L, Pace.claim(k, 1_500))
        now += 1_000
        assertEquals(500L, Pace.claim(k, 1_500))
        now += 500
        assertEquals(0L, Pace.claim(k, 1_500))
    }

    @Test
    fun `an unpaced site is never held`() {
        val k = key()
        repeat(20) { assertEquals(0L, Pace.claim(k, 0)) }
    }

    /* Every chapter in flight gets the same 429. The first closes the gate
       and slows the site; the rest only wait — or one burst would push the
       gap to its ceiling at once. */
    @Test
    fun `a refusal closes the gate once and doubles the gap once`() {
        val k = key()
        assertEquals(0L, Pace.claim(k, 1_500))
        assertEquals(60_000L, Pace.throttled(k, 1_500, null))
        assertEquals(0L, Pace.throttled(k, 1_500, null))
        assertEquals(0L, Pace.throttled(k, 1_500, null))
        assertEquals(60_000L, Pace.claim(k, 1_500))
        now += 60_000
        assertEquals(0L, Pace.claim(k, 1_500))
        assertEquals(3_000L, Pace.claim(k, 1_500))
    }

    @Test
    fun `the wait the site names is the wait taken`() {
        val k = key()
        assertEquals(30_000L, Pace.throttled(k, 1_500, "30"))
        assertEquals(30_000L, Pace.claim(k, 1_500))
    }

    /* A site with no pace of its own that says nothing keeps the engine's
       own slow-down; one that names a wait is waited out. */
    @Test
    fun `an unpaced site pauses only when it names a wait`() {
        assertNull(Pace.throttled(key(), 0, null))
        val k = key()
        assertEquals(5_000L, Pace.throttled(k, 0, "5"))
        now += 5_000
        assertEquals(0L, Pace.claim(k, 0))
        assertEquals(0L, Pace.claim(k, 0))
    }

    @Test
    fun `Retry-After reads as seconds or as a date`() {
        now = 1_760_000_000_000L    // 2025-10-09T08:53:20Z
        assertEquals(120_000L, Pace.retryAfterMs("120"))
        assertEquals(10_000L, Pace.retryAfterMs("Thu, 09 Oct 2025 08:53:30 GMT"))
        assertEquals(0L, Pace.retryAfterMs("Thu, 09 Oct 2025 08:00:00 GMT"))
        assertNull(Pace.retryAfterMs(null))
        assertNull(Pace.retryAfterMs(""))
        assertNull(Pace.retryAfterMs("-3"))
        assertNull(Pace.retryAfterMs("soon"))
    }

    @Test
    fun `a named wait is kept within bounds`() {
        assertEquals(1_000L, Pace.throttled(key(), 1_500, "0"))
        assertEquals(300_000L, Pace.throttled(key(), 1_500, "86400"))
    }
}
