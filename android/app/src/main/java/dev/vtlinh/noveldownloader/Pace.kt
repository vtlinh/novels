package dev.vtlinh.noveldownloader

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/* One gate per site, shared by every request the app sends it: a download,
   the listing pages of another, a status sweep running three novels wide.
   Per engine would not do — two downloads of the same site would each keep
   their own pace and together send twice it.

   A site sets `Site.requestGapMs` and requests to it start no closer
   together than that. When a site answers "too many requests" the gate
   closes for as long as it asked (Retry-After), or for a minute on a paced
   site that did not say, and a paced site's gap doubles for the rest of the
   session: it was not slow enough. A site with no gap that names no wait is
   left to the engine's own slow-down, which already handles it. */
object Pace {
    private const val MAX_GAP_MS = 10_000L
    private const val MAX_WAIT_MS = 300_000L
    private const val UNSAID_WAIT_MS = 60_000L

    private class Gate(var gapMs: Long) {
        var next = 0L
        var closedUntil = 0L
    }

    private val gates = HashMap<String, Gate>()

    internal var clock: () -> Long = System::currentTimeMillis

    private fun gate(key: String, baseGapMs: Long) = gates.getOrPut(key) { Gate(baseGapMs) }

    /* 0 = go now, and the slot is taken; otherwise how long to wait before
       asking again. Polled rather than reserved ahead, so a request that is
       stopped while waiting leaves no slot behind for the next run to wait
       out. */
    @Synchronized
    internal fun claim(key: String, baseGapMs: Long): Long {
        val g = gate(key, baseGapMs)
        val now = clock()
        val at = maxOf(g.next, g.closedUntil)
        if (now < at) return at - now
        g.next = now + g.gapMs
        return 0
    }

    /* Blocks until this site may be asked; false if `stop` turned true first. */
    fun await(site: Site, stop: () -> Boolean): Boolean {
        while (true) {
            if (stop()) return false
            val wait = claim(site.name, site.requestGapMs)
            if (wait == 0L) return true
            Thread.sleep(minOf(wait, 250L))
        }
    }

    /* The site answered 429/503. Null: no pause, fail as before. Otherwise
       the pause in ms if this answer started it, 0 if one was already
       running — every request in flight sees the same refusal, and only the
       first may slow the site down further. */
    @Synchronized
    internal fun throttled(key: String, baseGapMs: Long, retryAfter: String?): Long? {
        val g = gate(key, baseGapMs)
        val said = retryAfterMs(retryAfter)
        if (said == null && baseGapMs == 0L) return null
        val now = clock()
        if (now < g.closedUntil) return 0
        val wait = (said ?: UNSAID_WAIT_MS).coerceIn(1_000L, MAX_WAIT_MS)
        if (baseGapMs > 0) g.gapMs = minOf(MAX_GAP_MS, g.gapMs * 2)
        g.closedUntil = now + wait
        return wait
    }

    fun throttled(site: Site, retryAfter: String?): Long? =
        throttled(site.name, site.requestGapMs, retryAfter)

    /* Retry-After is either delay-seconds or an HTTP date. */
    internal fun retryAfterMs(h: String?): Long? {
        val v = h?.trim()?.ifEmpty { null } ?: return null
        v.toLongOrNull()?.let { return if (it >= 0) it * 1000 else null }
        return try {
            val at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (at - clock()).coerceAtLeast(0)
        } catch (e: Exception) {
            null
        }
    }
}
