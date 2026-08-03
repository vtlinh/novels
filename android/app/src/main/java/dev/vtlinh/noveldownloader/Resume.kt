package dev.vtlinh.noveldownloader

/* Reading a novel's listing again from where it ended, instead of from page 1.

   An ongoing novel is checked over and over for the handful of chapters the
   site has added since. Doing that by paging through the whole listing costs
   one request per listing page per novel per sweep — ninety pages for a novel
   whose only news is two new chapters at the end — and every one of those
   requests is a chance for a page to blip, which is what makes a check refuse
   the novel entirely.

   So record where the listing ENDED: the page that held its last chapters, and
   how many chapters came before that page. Next time, read that page and
   whatever follows it, and put the recorded prefix back in front.

   The decisions live here, with no Android and no HTTP in them, because
   getting them wrong is expensive in a way reasoning does not catch: a spliced
   listing NAMES FILES BY POSITION, exactly as a full read does. If the prefix
   and the tail do not line up, every chapter after the join is named after its
   neighbour. Refusing to resume costs one slow check; resuming wrongly costs
   a renumbered library.

   Nothing here is destructive by design. A resumed check only ever appends,
   and the passes that rename and delete stay behind the full read — see
   DownloadEngine.checkStatusFrom. That is the whole reason resuming is only
   offered for a novel that is already fully downloaded: a novel MISSING
   chapters is precisely the one whose files the full check exists to repair,
   and skipping that repair would leave it broken for good. */
object Resume {

    /* Where the last full read ended. `page` is the listing page that held
       the novel's final chapters (1 = the novel page itself), `url` is the
       URL that page was fetched from, and `before` is how many chapters came
       from the pages ahead of it. */
    data class Point(val page: Int, val url: String, val before: Int)

    /* May a check on this novel start from `point` rather than from page 1?

       `recordedSize` is how many chapters the stored listing holds, `total`
       the site count that listing was read at, `onDisk` how many chapters are
       actually here, and `complete` whether the site has finished the story.

       Every condition is about the same thing: a resumed read leaves the
       prefix untouched, so the prefix has to be worth leaving untouched.
         - a resume point that names a page and a URL, or there is nothing to
           resume from;
         - `before` STRICTLY inside the recorded listing. The resume page is
           the one that held the listing's last chapters, so the prefix ahead
           of it is always shorter than the listing; a `before` at or past the
           end is a record we cannot make sense of, and it would also leave the
           join with nothing overlapping to check;
         - a recorded listing that is the site's count, so it was read whole;
         - every chapter of it on disk. A novel with holes needs the full
           read's rename and dedupe passes, which are the only thing that
           repairs it;
         - and a story the site has not finished, which is the only kind that
           gains chapters. A finished, fully-downloaded novel is not checked
           at all. */
    fun mayResume(
        point: Point?,
        recordedSize: Int,
        total: Int,
        onDisk: Int,
        complete: Boolean,
    ): Boolean {
        if (point == null || point.page < 1 || point.url.isEmpty()) return false
        if (point.before < 0 || point.before >= recordedSize) return false
        if (recordedSize <= 0 || total != recordedSize) return false
        if (onDisk < total) return false
        return !complete
    }

    /* What a resumed read came out as. `added` is how many chapters the site
       has gained since the listing was recorded. */
    class Spliced(val urls: List<String>, val added: Int)

    /* Join the recorded prefix to the pages just read.

       `recorded` is the listing as it was last read whole: one entry per
       position, holding that chapter's page URL — or EMPTY where we never
       recorded one, which is every chapter of a library older than the column
       and every chapter that has not been downloaded. `before` is the prefix
       length, `tail` the chapter URLs just read from the resume page onward,
       in the site's order.

       Null means the two do not line up and the caller must read the whole
       listing. That is the answer for anything surprising, because the
       alternative to a slow check is a wrong one:

         - a tail that ends before the recorded listing does. Chapters have
           been REMOVED, which shortens the novel and moves everything after
           the join — the case the full read's dedupe pass exists to handle,
           and one this pass must not paper over;
         - a position where the recorded URL and the read URL disagree. The
           listing has shifted under the prefix, so the prefix no longer names
           what it used to.

       A position with no recorded URL is skipped rather than counted as a
       mismatch — an empty entry is an absence of evidence, not a
       contradiction. But the join has to rest on SOME evidence, so at least
       one overlapping position must carry a URL to compare. Without that the
       check passes on nothing at all, which is not the same as passing:

       `fileUrls` only returns chapters whose page was recorded, and a library
       adopted by the folder scan has none — it is indexed by name, with no
       page against any row. Its `chapter_order` still gets filled in by one
       full check, so it reaches this function with a listing of the right
       LENGTH and not one URL in it. Every comparison below is then skipped,
       the splice succeeds whatever the site has done, and the chapter order it
       writes names files by positions nothing verified. The reader sorts by
       exactly that order, and an auto-download then fetches the "new" chapters
       into filenames that belong to chapters already on disk.

       Reading the whole listing is the right answer there anyway: that pass
       renames and dedupes BY IDENTITY, which is what gives such a library its
       recorded pages in the first place. */
    fun splice(recorded: List<String>, before: Int, tail: List<String>): Spliced? {
        if (before < 0 || before > recorded.size) return null
        if (before + tail.size < recorded.size) return null
        /* Positions actually compared. Zero of them means the join rests on
           nothing — either because no overlapping position carries a URL, or
           because there is no overlap at all (a prefix reaching the end of the
           recorded listing, which the point and the order coming apart is the
           only way to produce). */
        var compared = 0
        for ((i, url) in tail.withIndex()) {
            val pos = before + i
            if (pos >= recorded.size) break
            val was = recorded[pos]
            if (was.isEmpty()) continue
            if (was != url) return null
            compared++
        }
        if (compared == 0) return null
        return Spliced(
            recorded.subList(0, before).toList() + tail,
            before + tail.size - recorded.size,
        )
    }

    /* The first page is read on every check whatever the resume point — it is
       the novel page, and it carries the finished flag, the author and the
       page count. Its chapter links are then free evidence about the OTHER
       end of the listing, which the splice above never looks at: a site that
       inserts a prologue moves every position by one, and the join would
       still agree with itself.

       False means the front of the listing has moved and the whole thing has
       to be read again. `head` may run PAST the recorded listing without
       objection — on a one-page novel the head is the tail, and new chapters
       at the end are the ordinary case. */
    fun headIntact(recorded: List<String>, head: List<String>): Boolean {
        for ((i, url) in head.withIndex()) {
            if (i >= recorded.size) break
            val was = recorded[i]
            if (was.isNotEmpty() && was != url) return false
        }
        return true
    }

    /* The listing as recorded, by position: the chapter order the reader
       sorts by, with each filename's page URL against it.

       `order` is filename -> position, `urls` is filename -> page URL. A
       position no filename claims, or a filename with no recorded page, comes
       back empty — which splice reads as "no evidence" rather than as a
       mismatch. */
    fun recordedListing(order: Map<String, Int>, urls: Map<String, String>): List<String> {
        if (order.isEmpty()) return emptyList()
        val size = (order.values.maxOrNull() ?: -1) + 1
        val out = MutableList(size) { "" }
        for ((name, pos) in order) {
            if (pos in 0 until size) out[pos] = urls[name].orEmpty()
        }
        return out
    }
}
