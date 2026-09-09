package dev.vtlinh.noveldownloader

/* Which chapter the novel page should land on, and when its window may grow.

   The list only holds a window around the current chapter. Growing at the
   edges is how a 7k-row novel stays cheap. The jump onto the current row
   is posted — the ListView has no height until after layout — and the
   first onScroll fires BEFORE that jump, at the top of the window. Growing
   then walks the window back to chapter 1, and the posted jump uses a
   stale index. After ≡ started opening this page instead of the in-reader
   drawer, that race is what the reader sees: the list opens at the start
   instead of the chapter TTS is on. */
object ChapterListFocus {

    /* The reader passes the chapter TTS is saying (or the one on screen if
       TTS never started). Prefs are the fallback for every other way into
       this screen. The extra wins: lastCh is the viewport top, and while
       listening that sits a fifth of a page above the spoken line — so
       prefs alone can name the previous chapter. */
    fun currentName(fromReader: String?, fromPrefs: String?): String? =
        fromReader?.takeIf { it.isNotEmpty() } ?: fromPrefs?.takeIf { it.isNotEmpty() }

    /* Exact filename first; then the same chapter under a title suffix.
       An exact miss used to leave current at -1 and open the window at
       the start of the novel. */
    fun indexIn(ordered: List<String>, name: String?): Int {
        if (name.isNullOrEmpty()) return -1
        val exact = ordered.indexOf(name)
        if (exact >= 0) return exact
        return ordered.indexOfFirst { ChapterName.same(it, name) }
    }

    /* A scroll event that arrives while the posted jump is still pending
       must not grow the window. */
    fun mayGrow(settling: Boolean): Boolean = !settling
}
