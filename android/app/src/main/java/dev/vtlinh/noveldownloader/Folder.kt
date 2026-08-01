package dev.vtlinh.noveldownloader

/* What a novel's folder holds, and what to make of it — with no Android in
   it, so the rules can be exercised directly.

   These rules decide which chapters the reader can see, which text each one
   serves, and what order they come in. Three defects in as many releases came
   out of reasoning about them in place: a 0-byte file read as an empty
   chapter; then the fix for that dropping the chapter's paid-for translation
   with it; then the fix for THAT making the cached listing fail its own
   validity check on every open. None of them were visible without a folder to
   run against, and SAF cannot be faked off-device — ContentResolver's readers
   are all final. So the folder comes in as a list instead. */
object Folder {

    /* One entry as the walk sees it. `ref` is the provider's document id.
       `size` is -1 when the provider reports none, which is not the same as
       zero and must not be treated as one. */
    data class Item(
        val name: String,
        val ref: String,
        val isDir: Boolean = false,
        val size: Long = -1L,
    )

    /* `truncated` is the chapters that exist but hold nothing readable. They
       stay in `ordered` deliberately: the reader answers a name with no source
       by saying the chapter can't be read, which is true and useful, whereas
       dropping the name loses the chapter's translation as well. */
    class Contents(
        val ordered: List<String>,
        val source: Map<String, String>,
        val translated: Map<String, String>,
        val truncated: Set<String>,
    )

    private val RE = ChapterName.RE

    private fun number(name: String) = RE.find(name)?.groupValues?.get(1)?.toIntOrNull()

    /* A file the provider reports as zero bytes. Only a KNOWN zero — an
       unreported size is -1, and treating that as empty would empty the
       library on any provider that doesn't count. */
    private fun empty(e: Item) = e.size == 0L

    fun resolve(
        main: List<Item>,
        translatedDir: List<Item>,
        siteOrder: Map<String, Int> = emptyMap(),
    ): Contents {
        val source = HashMap<String, String>()
        val gzSource = HashMap<String, String>()
        val truncated = HashSet<String>()
        for (e in main) {
            if (e.isDir) continue
            if (Zips.isGzName(e.name)) {
                val n = e.name.removeSuffix(".gz")
                if (!RE.matches(n)) continue
                if (empty(e)) truncated.add(n) else gzSource[n] = Zips.gzRef(e.ref)
            } else if (RE.matches(e.name)) {
                if (empty(e)) truncated.add(e.name) else source[e.name] = e.ref
            }
        }
        val translated = HashMap<String, String>()
        val gzTranslated = HashMap<String, String>()
        for (e in translatedDir) {
            if (e.isDir || empty(e)) continue
            if (Zips.isGzName(e.name)) {
                val n = e.name.removeSuffix(".gz")
                if (RE.matches(n)) gzTranslated[n] = Zips.gzRef(e.ref)
            } else if (RE.matches(e.name)) {
                translated[e.name] = e.ref
            }
        }
        /* compressed chapters fill in behind loose .txt files — a chapter
           downloaded after a compress pass is loose, and it is the newer one */
        for ((n, r) in gzSource) if (n !in source) source[n] = r
        for ((n, r) in gzTranslated) if (n !in translated) translated[n] = r
        /* a good copy under either name settles it — only a chapter with
           nothing readable left stays on the truncated list */
        truncated.removeAll(source.keys)
        /* An English file with no chapter behind it at all is not a chapter;
           one whose source is merely unreadable still is, and the reader can
           still show it. */
        translated.keys.retainAll(source.keys + truncated)

        /* The site's listing sequence is the order, and it has to be: a site
           can name chapters after their titles rather than number them, so the
           number in a filename is only as good as what could be parsed out of
           a title.

           A chapter the listing doesn't cover — added since the order was
           recorded, or left by an older build — still has to land in the right
           place rather than at the end, so slot it just after the last listed
           chapter that precedes it by number. With no recorded order at all
           every chapter takes that path, which leaves a plain numeric sort. */
        val ordinalByNumber = java.util.TreeMap<Int, Int>()
        for ((fn, ord) in siteOrder) {
            val n = number(fn) ?: continue
            val prev = ordinalByNumber[n]
            if (prev == null || ord < prev) ordinalByNumber[n] = ord
        }
        val listable = source.keys + truncated
        /* (slot, tie-break), computed once rather than on every compare. A
           listed chapter ties at 0, so an unlisted one sharing its slot
           follows it and both stay ahead of the next listed chapter. */
        val rank = HashMap<String, Pair<Int, Int>>(listable.size)
        for (name in listable) {
            val listed = siteOrder[name]
            rank[name] = if (listed != null) {
                Pair(listed, 0)
            } else {
                val n = number(name)
                if (n == null) Pair(Int.MAX_VALUE, Int.MAX_VALUE)
                else Pair(ordinalByNumber.floorEntry(n)?.value ?: -1, n)
            }
        }
        val ordered = listable.sortedWith(
            compareBy(
                { rank[it]?.first ?: Int.MAX_VALUE },
                { rank[it]?.second ?: Int.MAX_VALUE },
                { number(it) ?: Int.MAX_VALUE },
                { it },
            ),
        )
        return Contents(ordered, source, translated, truncated)
    }

    /* What the folder looked like when its listing was cached: the novel's own
       directory and its translated/ subdirectory, each as (document id, last
       modified). An absent translated/ is the empty id and 0.

       The spot-check below asks whether the chapters the cache RECORDED are
       still on disk. Nothing asked whether anything had ARRIVED — and one
       thing routinely does. translated/ is filled in after the chapters are,
       by a translation run that can finish long after the listing was cached
       (or, for a library kept in a folder the user also writes to from a
       computer, by something that is not this app at all). The cached listing
       then went on reporting no translations for ever: the English sat on disk
       beside the Vietnamese and the reader offered no language to switch to,
       because as far as it could see there was nothing to switch to.

       A directory's mtime moves when a child is added or removed, so two
       single-row queries answer "has anything appeared?" without listing a
       seven-thousand-file folder again — and not re-listing it is the only
       reason the cache exists. A provider that reports no mtime reports 0 on
       both sides, and the check says nothing rather than something wrong. */
    data class Stamp(
        val dirId: String,
        val dirMod: Long,
        val trId: String,
        val trMod: Long,
    )

    /* Does a cached listing still describe this folder?

       A cache with NO stamp is stale by definition — it was written before
       anything recorded one, so nothing can be said about what has arrived
       since. That includes every listing cached by a build that could not
       notice a translation appearing at all, which is the point: one re-walk
       repairs those libraries instead of leaving them wrong for good. */
    fun folderUnchanged(cached: Stamp?, now: Stamp): Boolean = cached != null && cached == now

    /* The stamp as the two strings the cached listing has spare to hold it
       (see DownloadStore.saveChapterList). NUL-separated: a document id is
       the provider's own string and may hold anything a path may hold, but
       not this. */
    private const val SEP = "\u0000"

    fun encodeStamp(s: Stamp): Pair<String, String> =
        Pair(s.dirId + SEP + s.dirMod, s.trId + SEP + s.trMod)

    /* Null for anything that isn't a stamp this build wrote — which is
       treated as no stamp at all, i.e. stale. */
    fun decodeStamp(dir: String, tr: String): Stamp? {
        val d = dir.split(SEP)
        val t = tr.split(SEP)
        if (d.size != 2 || t.size != 2) return null
        return Stamp(
            d[0], d[1].toLongOrNull() ?: return null,
            t[0], t[1].toLongOrNull() ?: return null,
        )
    }

    /* Which positions to spot-check a cached listing at. Probing only the ends
       missed anything removed between them — a chapter deleted outside the app
       stayed in the listing and the reader skipped it with no gap shown. */
    fun probePositions(size: Int, probes: Int): List<Int> {
        val out = LinkedHashSet<Int>()
        out.add(0)
        out.add(size - 1)
        for (k in 1 until probes) out.add(k * size / probes)
        return out.toList()
    }

    /* Is a cached listing still good? `usable` answers for one ref.

       A name with NO ref is not evidence of staleness — it is the walk having
       recorded that chapter as unreadable, which it does deliberately so the
       chapter's translation survives. Reading it as stale threw the listing
       away and re-walked the folder on every open, for ever: one probe is the
       last chapter, exactly where a file left empty by a full volume tends to
       sit, and the re-walk records the same thing again. */
    fun cacheValid(
        ordered: List<String>,
        source: Map<String, String>,
        probes: Int,
        usable: (String) -> Boolean,
    ): Boolean = probePositions(ordered.size, probes).all { i ->
        val name = ordered.getOrNull(i) ?: return@all false
        val ref = source[name] ?: return@all true
        usable(ref)
    }
}
