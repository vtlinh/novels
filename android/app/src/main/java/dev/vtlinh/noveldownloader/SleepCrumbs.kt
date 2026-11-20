package dev.vtlinh.noveldownloader

/* What the sleep timer read to someone who had likely dozed off.

   The timer is armed on play and re-armed by every shake, so the chapter
   being spoken at its last arming is the last sign the listener was awake.
   When the timer stops reading, that chapter and the one it stopped in are
   kept, and the chapter list marks everything between them. A pause by hand
   leaves nothing — the listener was awake to press it — and the next play
   clears them. */
object SleepCrumbs {

    private fun fromKey(slug: String) = "sleepFrom:$slug"
    private fun toKey(slug: String) = "sleepTo:$slug"

    fun save(prefs: android.content.SharedPreferences, slug: String, from: String, to: String) {
        prefs.edit().putString(fromKey(slug), from).putString(toKey(slug), to).apply()
    }

    fun clear(prefs: android.content.SharedPreferences, slug: String) {
        prefs.edit().remove(fromKey(slug)).remove(toKey(slug)).apply()
    }

    /* (last chapter with movement, chapter it stopped in) */
    fun load(prefs: android.content.SharedPreferences, slug: String): Pair<String, String>? {
        val to = prefs.getString(toKey(slug), null)?.takeIf { it.isNotEmpty() } ?: return null
        val from = prefs.getString(fromKey(slug), null)?.takeIf { it.isNotEmpty() } ?: to
        return Pair(from, to)
    }

    /* Rows to mark in the list's own order — newest-first reverses it, so
       either end may come first. One end gone from the listing still marks
       the other; both gone marks nothing. */
    fun range(ordered: List<String>, from: String, to: String): IntRange? {
        val a = ChapterListFocus.indexIn(ordered, from)
        val b = ChapterListFocus.indexIn(ordered, to)
        return when {
            a < 0 && b < 0 -> null
            a < 0 -> b..b
            b < 0 -> a..a
            else -> minOf(a, b)..maxOf(a, b)
        }
    }

    fun note(from: String, to: String): String {
        val toLabel = to.removeSuffix(".txt")
        return if (ChapterName.same(from, to)) {
            "The sleep timer stopped reading in $toLabel."
        } else {
            "The sleep timer stopped reading. No movement from ${from.removeSuffix(".txt")} " +
                "until it stopped in $toLabel."
        }
    }
}
