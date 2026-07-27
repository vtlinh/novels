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

    /* What a page's chapter links are, and whether we had to guess.

       This is the single most consequential read in the app: the links, in
       this order, are the novel — position N names the file that holds
       chapter N, and every destructive pass compares what is on disk against
       exactly this list. It lived twice, inline and private, in the download
       and in the status check, which is how the two drifted apart. One copy,
       and a test can reach it — see RealPageTest, which runs it over real
       pages captured from both sites.

       `fellBack` means the site's own chapter-list container was not there
       (or held nothing) and these links came from reading the whole document
       instead, where the only chapter links are the "latest chapters" widget:
       a handful, in the wrong order. That is not a short listing, it is a
       different document, and nothing destructive may run against it. */
    class Found(val links: List<Pair<String, String>>, val fellBack: Boolean)

    fun collect(d: org.jsoup.nodes.Document, site: Site, slug: String): Found {
        fun scan(root: org.jsoup.nodes.Element, inList: Boolean): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            for (a in root.select("a[href]")) {
                val href = a.absUrl("href").substringBefore('#')
                if (href.isEmpty()) continue
                val path = try { java.net.URI(href).path ?: "" } catch (e: Exception) { continue }
                /* Inside the site's list, being there is what identifies a
                   chapter; outside it there is no such evidence, so the
                   stricter URL test applies. */
                val isChapter =
                    if (inList) site.isChapterInList(path, slug)
                    else site.isChapterPath(path, slug)
                if (!isChapter) continue
                out.add(href to a.text().trim())
            }
            return out
        }
        val scope = d.selectFirst(site.listScope)
        val inScope = if (scope == null) emptyList() else scan(scope, inList = true)
        if (inScope.isNotEmpty()) return Found(inScope, false)
        val loose = scan(d, inList = false)
        return Found(loose, loose.isNotEmpty())
    }


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
        /* ONE page. The allowance used to scale with the novel — lastPage/20
           — which contradicts the reasoning right above it: an over-read is
           the pagination reporting one page too many, and that is one page
           whatever the novel's size, while the scaled version wrote off seven
           pages (326 chapters) on a 140-page book. The cost is not
           symmetrical. Forgiving too little leaves the listing incomplete,
           and everything destructive is already gated on that. Forgiving too
           much is what lets a status check — which renames and deletes but
           never downloads, so nothing self-corrects — delete hundreds of
           chapters and their paid translations. */
        return if (forgivable.size <= 1) forgivable else emptyList()
    }

    /* What a fetch of a listing page actually established about it.

       The distinction that matters is between "definitely not there" and "no
       answer": forgiveness is granted on the first and must never be granted
       on the second. `gone` is what forgiveness reads, and it used only ever
       to grow — so a page that answered 404 once and then merely timed out
       stayed "definitely missing" for the rest of the run, and its chapters
       and their paid translations were deleted on the strength of an answer
       that had since been contradicted by silence. A timeout is not evidence.

       The confirmation pass is the last word before forgiveness is decided,
       and in a status check there is no retry behind it at all — that pass
       renames and deletes but never downloads, so nothing self-corrects. */
    enum class Answer { PRESENT, MISSING, NO_ANSWER }

    fun answer(html: String?, status: Int): Answer = when {
        html != null -> Answer.PRESENT
        status == 404 || status == 410 -> Answer.MISSING
        else -> Answer.NO_ANSWER
    }

    /* Apply that to the two sets the listing walk carries. Returns true when
       the page turned out to be real, so the caller can go on to read it. */
    fun record(page: Int, a: Answer, gone: MutableSet<Int>, missed: MutableSet<Int>): Boolean {
        when (a) {
            Answer.PRESENT -> { gone.remove(page); missed.remove(page) }
            Answer.MISSING -> gone.add(page)
            /* no evidence either way — it must not keep counting as proof */
            Answer.NO_ANSWER -> gone.remove(page)
        }
        return a == Answer.PRESENT
    }

    /* The first page we could not read. Chapters discovered before it are
       still at the positions they belong to; everything after it would be
       one slot early, so the run stops collecting there. */
    fun firstGap(missed: Set<Int>, lastPage: Int): Int = missed.minOrNull() ?: (lastPage + 1)
}

/* Which directory a novel writes into. Wrong in one direction it overwrites
   another novel's chapters; wrong in the other it builds a second folder
   beside a full one and re-downloads — and re-translates, at the API's price
   — a book that is already on disk. Both have happened. */
object Ownership {

    /* `name` is the folder to use. `stepAside` means we could not have it and
       took a disambiguated one; `recorded` means we went back to the folder
       we are on record as using rather than the name the title computes to. */
    data class Choice(val name: String, val stepAside: Boolean, val recorded: Boolean)

    /* Is `wanted` this novel's own folder?

       `owner` is the slug that claimed the name, `recordedDir` the directory
       this novel is on record as using, `myChapters` how many of its chapters
       the index holds, `indexKnowsOthers` whether the index holds ANY chapter
       of any other novel in this tree.

       A recorded directory is the whole answer when there is one. Without it
       the folder is ours if the index already holds our chapters (a library
       older than the column, sitting where it always has) — or if the index
       holds nobody else's either, which is what a reinstall on top of a
       restored folder looks like and is not somebody else's work. */
    /* Slug equality has to survive punctuation drift. The folder scan derives
       a slug from the folder NAME — "Library of Heaven's Path" sanitises and
       slugifies to "library-of-heaven-s-path" — while the site's own slug is
       "library-of-heavens-path". Compared as strings those are two different
       novels, so the app treated its own book as somebody else's, stepped
       aside into "Title (slug)" and downloaded and re-translated, at the
       API's price, a library already complete on disk. Letters and digits
       only; the Library screen keys its duplicate merge the same way, and the
       two must not drift apart. */
    fun normKey(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun sameSlug(a: String?, b: String) = a != null && normKey(a) == normKey(b)

    fun ours(
        slug: String,
        wanted: String,
        owner: String?,
        recordedDir: String?,
        myChapters: Int,
        indexKnowsOthers: Boolean,
    ): Boolean {
        /* The recorded directory FIRST — "the whole answer when there is one",
           as stated above, which an `owner == slug` test in front of it made
           untrue. Owning a name and living in it are different facts: a novel
           whose folder was renamed Vietnamese → English keeps its claim on the
           old name (nothing releases it), so on any later run that computes
           the Vietnamese name back — translation switched off, a title batch
           that failed — the claim said "ours", the folder was not there any
           more, and a fresh EMPTY one was created and recorded. The run then
           downloaded nothing (the index still resolved into the real folder),
           the library pointed at the empty directory, and a translation pass
           found nothing done and re-submitted the whole novel to the API. */
        if (recordedDir != null) return recordedDir == wanted
        if (owner != null) return sameSlug(owner, slug)
        return myChapters > 0 || !indexKnowsOthers
    }

    /* Which folder a novel writes into once its English title is known.

       It PICKS one; it never renames one. Renaming the Vietnamese folder to
       the English title was a tidy idea that went wrong in every direction it
       could:

         - findFile matches on the display name alone, so the directory it
           found was not always this novel's. truyenfull serves the same title
           under two slugs, sanitising folds tone marks so "Thần Y" and
           "Thân Y" meet, and a folder the Library scan adopted is claimed
           under a slug invented from its name. Renaming somebody else's
           folder made its chapters look unclaimed, so this novel adopted
           them and paid to translate them, and the real owner was left
           pointing at a directory that no longer existed;
         - a rename that FAILED, and a rename recorded in the wrong order,
           each produced an empty folder beside a full one — the index still
           resolved into the old directory, so nothing re-downloaded while the
           translator saw an empty translated/ and re-sent the whole novel to
           the API;
         - and the check written to stop all of this ran sixty lines later,
           so it could only ever bless a rename that had already happened.

       A folder that already holds a novel keeps its name. Only a novel with
       no directory yet is given the English one, and creating a folder is not
       a rename — nothing existing is touched, so none of the above can
       happen. The cost is cosmetic: a book downloaded before translation was
       switched on keeps its Vietnamese folder name on disk. The Library and
       the reader show the English title either way; it comes from the titles
       cache, not from the directory. */
    fun translatedFolder(
        english: String,
        vietName: String,
        recordedDir: String?,
        vietIsOurs: Boolean,
        onDisk: (String) -> Boolean,
    ): String {
        /* the directory we are on record as using, while it is still there */
        if (recordedDir != null) return if (onDisk(recordedDir)) recordedDir else english
        /* no record: an earlier untranslated run's folder, if it is ours */
        if (vietIsOurs && onDisk(vietName)) return vietName
        return english
    }

    /* The full choice. `recordedDirOnDisk` and `wantedOccupied` are lambdas
       because answering them costs SAF calls — listing a folder of several
       thousand chapters is seconds — and neither is needed on the ordinary
       path where the folder is plainly ours. */
    fun choose(
        slug: String,
        wanted: String,
        alt: String,
        owner: String?,
        recordedDir: String?,
        myChapters: Int,
        indexKnowsOthers: Boolean,
        recordedDirOnDisk: () -> Boolean,
        wantedOccupied: () -> Boolean,
    ): Choice {
        if (ours(slug, wanted, owner, recordedDir, myChapters, indexKnowsOthers)) {
            return Choice(wanted, stepAside = false, recorded = false)
        }
        /* We own a folder under a different name — a suffix from a past
           collision, or the name before a translated rename. Keep using it
           rather than recomputing our way back into somebody else's. */
        if (recordedDir != null && recordedDirOnDisk()) {
            return Choice(recordedDir, stepAside = false, recorded = true)
        }
        /* Claimed by US, and the directory we are on record as using is not
           there any more. The claim is then the best evidence left, so take
           the name: stepping aside would build a second folder beside our own
           and re-download the book into it. (`ours` used to answer this one
           before it ever looked at the recorded directory — the ordering that
           has just been reversed — so the case has to be caught here instead
           of falling through to the step-aside below.) */
        if (sameSlug(owner, slug)) {
            return Choice(wanted, stepAside = false, recorded = false)
        }
        /* Claimed, or unclaimed but already full: either way somebody else's
           work. The first of two colliding novels to run must not be able to
           claim, and then be evicted from, the folder it lives in. */
        if (owner != null || wantedOccupied()) {
            return Choice(alt, stepAside = true, recorded = false)
        }
        return Choice(wanted, stepAside = false, recorded = false)
    }
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
        /* Every name the POSITION scheme gives out. `spokenFor` only protects
           files whose page we recorded, and that set is empty in the one state
           where the fallback runs on the whole book at once: a library adopted
           by the folder scan, or rebuilt after a reinstall, is indexed by name
           with no page against any row. There the guess fired on every chapter
           and quietly shifted the entire novel — one slot for a listing whose
           first entry is an unnumbered extra, twenty-six for a site that skips
           a block of numbers — and then wrote each file's NEIGHBOUR's page
           against it, so nothing could ever notice.

           A file already sitting at some other chapter's position name is
           evidence of the position scheme, not of the legacy one. Refuse the
           guess there; the slot simply goes unmatched, which costs nothing —
           an unclaimed file still counts as present, so nothing is
           re-downloaded and nothing is deleted. */
        val positionNames = inSiteOrder.mapNotNullTo(HashSet()) { it.want }
        val claimedUrl = HashMap<String, String>()
        val linkNow = ArrayList<Pair<String, String>>()
        for (ch in inSiteOrder.asReversed()) {
            val want = ch.want ?: continue
            val have = byUrl[ch.url]
                ?: legacy[ch.url]?.takeIf {
                    it in onRecord && it !in spokenFor && (it == want || it !in positionNames)
                }
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
