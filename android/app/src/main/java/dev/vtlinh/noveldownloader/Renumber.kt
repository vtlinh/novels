package dev.vtlinh.noveldownloader

/* The two decisions that decide whether chapters survive a run, kept apart
   from the I/O that surrounds them so they can be tested directly.

   Both used to live inline in DownloadEngine, wrapped in SAF calls, HTTP and
   a Context — which meant the only way to check them was to reason about
   them, and repeated rounds of reasoning kept getting them wrong in ways that
   deleted or misfiled chapters. They are pure functions over plain data here:
   same logic, same names, no Android. See RenumberTest / ListingTest. */

/* What counts as a chapter file. This lived in an Activity's companion, which
   meant touching it loaded the Activity and pulled in appcompat — so the one
   pattern that decides whether a file is visible to the reader, the dedupe
   and the surplus sweep could not be unit-tested at all. It is plain Kotlin
   here; ChapterListActivity.CHAPTER_RE still points at it. */
object ChapterName {

    /* accepts "Chapter 70.txt", "Chapter 70-71.txt" AND legacy names with a
       title suffix like "Chapter 70 - Hoan chinh van.txt". Deliberately does
       NOT accept a ".part" name — a half-written file must stay invisible. */
    val RE = Regex("Chapter (\\d+)(?:-(\\d+))?.*\\.txt")
}

object Listing {

    /* Pages that reported "not there" and can be written off as the page
       count over-reading the pagination, rather than as holes in the listing.

       Everything forgiven is a page whose chapters are then treated as never
       having existed — and the dedupe deletes their files — so all three
       conditions matter:
         - nothing real after it, so it can only be past the end;
         - a contiguous run back to the last page that did load, so a 404
           island inside the book is never swallowed;
         - few enough to be a miscount. A tail of eleven 404s on a ninety-page
           novel is not an off-by-one, it is 550 chapters going quiet.

       `loaded` is the set of pages that actually returned content; page 1 is
       the novel page itself and is always considered loaded. Returns the
       pages that may be dropped from `missed` — empty means forgive nothing. */
    fun forgivableTailPages(
        missed: Set<Int>,
        gone: Set<Int>,
        loaded: Set<Int>,
        lastPage: Int,
    ): List<Int> {
        val lastReal = loaded.maxOrNull() ?: 1
        val forgivable = missed.filter { p ->
            p in gone && p > lastReal && ((lastReal + 1) until p).all { it in gone }
        }
        if (forgivable.isEmpty()) return emptyList()
        return if (forgivable.size <= maxOf(1, lastPage / 20)) forgivable else emptyList()
    }

    /* The first page we could not read. Chapters discovered before it are
       still at the positions they belong to; everything after it would be
       one slot early, so the run stops collecting there. */
    fun firstGap(missed: Set<Int>, lastPage: Int): Int = missed.minOrNull() ?: (lastPage + 1)
}

object Renumber {

    /* One entry in the site's listing: the page it came from, and the name
       its POSITION gives it. `want` is null for an entry with no usable name. */
    data class Slot(val url: String, val want: String?)

    /* from -> to moves, plus the pages to record for files that were matched
       by guesswork rather than by a recorded page. `linkNow` is the files
       already in the right place whose page simply wasn't recorded yet. */
    data class Plan(
        val pending: LinkedHashMap<String, String>,
        val claimedUrl: Map<String, String>,
        val linkNow: List<Pair<String, String>>,
    )

    /* Work out what has to move.

       `byUrl` is page -> filename for every file whose page we recorded;
       `onRecord` is every filename the index knows; `legacy` is page ->
       the name the SITE's own chapter numbering would have given it, which
       is how files saved before pages were recorded get recognised.

       Walked last chapter first, so a shift upwards frees each target before
       the chapter below it needs it. */
    fun plan(
        inSiteOrder: List<Slot>,
        byUrl: Map<String, String>,
        onRecord: Set<String>,
        legacy: Map<String, String>,
    ): Plan {
        val pending = LinkedHashMap<String, String>()
        /* Files that already answer for a chapter. The legacy fallback guesses
           a name from the site's numbering, and the two schemes share one
           namespace — so where the site's number and the listing position
           differ, that guess can land on a file another chapter has already
           proved is its own. A recorded page beats a guess. */
        val spokenFor = byUrl.values.toHashSet()
        val claimedUrl = HashMap<String, String>()
        val linkNow = ArrayList<Pair<String, String>>()
        for (ch in inSiteOrder.asReversed()) {
            val want = ch.want ?: continue
            val have = byUrl[ch.url]
                ?: legacy[ch.url]?.takeIf { it in onRecord && it !in spokenFor }
                ?: continue
            if (have != want) {
                pending[have] = want
                if (ch.url !in byUrl) claimedUrl[have] = ch.url
            } else if (ch.url !in byUrl) {
                linkNow.add(have to ch.url)
            }
        }
        return Plan(pending, claimedUrl, linkNow)
    }
}
