package dev.vtlinh.noveldownloader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/* One submitted-but-not-yet-collected Message Batch, kept so a batch orphaned
   by an app restart can be recovered on the next run (Anthropic retains
   results 29 days). `files` is the bundle's chapter filenames in order, so a
   result's `n` maps to files[n-1] — but a filename is a POSITION, and the
   listing shifts under it. `urls` is the same list by page, which does not
   move, so a batch collected after a renumber can still be filed correctly.
   Empty for rows written before it was recorded. */
data class PendingBatch(
    val batchId: String,
    val files: List<String>,
    val created: Long,
    val tries: Int,
    val wantTitle: String?,   // non-null on a title-translation batch (custom_id "title")
    val urls: List<String> = emptyList(),
)

/* App-private SQLite index of downloaded chapters, keyed by (folder, slug).
   It's a fast cache, NOT the source of truth — the folder on disk is. Each
   row stores the chapter's document URI so existence can be checked in O(1)
   (no directory listing). On a new device / reinstall / copied folder the
   index is simply empty and DownloadEngine rebuilds it from one listing. */
/* one downloaded novel, as shown on the List Novels screen */
data class NovelRec(
    val slug: String,
    val url: String,
    val title: String,
    val author: String,  // "" when unknown
    val started: Long,   // when its download was first started (0 = unknown/legacy)
    val total: Int,      // site chapter count from the last status check (-1 = never checked)
    val complete: Boolean,
    val diskCount: Int,  // chapters counted on disk at scan time (for unindexed novels)
    val lastDl: Long,    // when it last downloaded (0 = unknown/legacy)
    val lastRead: Long,  // when it was last opened in the reader (0 = never)
)

/* a cached, fully-resolved chapter listing (DownloadStore.chlist): filenames
   in reading order plus each name's source/translated ref, so opening a big
   novel doesn't re-list a 7k-file folder */
class CachedChapterList(
    val ordered: List<String>,
    val source: Map<String, String>,
    val translated: Map<String, String>,
)

class DownloadStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "downloads.db", null, Schema.VERSION) {

    companion object {
        private const val RETAIN_MS = 29L * 24 * 60 * 60 * 1000   // Anthropic keeps batch results 29 days
        /* Process-wide, because every screen builds its own helper over the
           same database — a per-instance counter would miss the clear the
           download service just did. Keyed per novel: a single counter meant
           any chapter saved anywhere in the library invalidated the walk of
           the novel you were looking at, so its listing could never be cached
           while any download ran — a full SAF walk of a 7k-file folder every
           five seconds, thrown away each time. */
        private val chlistEpochs = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val chlistEpochAll = java.util.concurrent.atomic.AtomicLong(0)

        private fun epochKey(folder: String, slug: String) = "$folder\u0000$slug"
    }

    /* The statements themselves live in Schema, which has no Android in it, so
       the migrations can be replayed against a real SQLite in a plain JVM test
       — see SchemaTest. They run once per install, on data nobody can get
       back, which is not something to leave resting on a careful reading. */
    private fun on(db: SQLiteDatabase) = object : Schema.Exec {
        override fun exec(sql: String) = db.execSQL(sql)
    }

    override fun onCreate(db: SQLiteDatabase) = Schema.create(on(db))

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        Schema.upgrade(on(db), oldVersion)

    /* ---- site chapter order (reader sorts by this, not by filename) ---- */

    fun setChapterOrder(folder: String, slug: String, orderedFilenames: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("chapter_order", "folder=? AND slug=?", arrayOf(folder, slug))
            for ((i, fn) in orderedFilenames.withIndex()) {
                db.execSQL(
                    "INSERT OR REPLACE INTO chapter_order(folder,slug,filename,ord) VALUES(?,?,?,?)",
                    arrayOf(folder, slug, fn, i),
                )
            }
            /* Bump the epoch too. Deleting the rows without it left a walk
               already in flight passing the staleness check and writing its
               listing — sorted by the order we just replaced — straight back
               over the delete, where nothing would invalidate it again. */
            db.delete("chlist", "folder=? AND slug=?", arrayOf(folder, slug))   // order changed
            bumpChlistEpoch(folder, slug)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /* ---- cached resolved chapter listing (chlist) ---- */

    /* Bumped by every clearChapterList. Walking a 7k-file folder over SAF
       takes seconds, and a chapter saved during the walk clears the cache
       BEFORE the walk finishes — so the listing written afterwards is missing
       it, and nothing clears it again once the download ends. The walker
       records this before it starts and hands it back here. */
    /* The per-novel counter PLUS a library-wide one. Bumping only the keys
       already in the map missed every novel that had never been walked in
       this process — including the one being walked right now under an
       implicit 0 — so a compress pass could finish mid-walk and the stale
       listing, full of pre-gzip document ids, was written back anyway. */
    fun chapterListEpoch(folder: String, slug: String): Long =
        (chlistEpochs[epochKey(folder, slug)] ?: 0L) + chlistEpochAll.get()

    fun saveChapterList(
        folder: String,
        slug: String,
        list: CachedChapterList,
        seenEpoch: Long = -1L,
    ) {
        /* -1 means the caller isn't tracking it (nothing changed under them) */
        if (seenEpoch >= 0 && seenEpoch != chapterListEpoch(folder, slug)) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("chlist", "folder=? AND slug=?", arrayOf(folder, slug))
            for ((i, name) in list.ordered.withIndex()) {
                db.execSQL(
                    "INSERT INTO chlist(folder,slug,pos,name,src,tr) VALUES(?,?,?,?,?,?)",
                    arrayOf(folder, slug, i, name, list.source[name] ?: "", list.translated[name] ?: ""),
                )
            }
            /* this listing is the truest on-disk count — it sees loose AND
               compressed chapters, which the scan/index counters don't — so
               persist it and the Library's x/y stays right. Set it, don't
               raise it: the old `disk_count<?` guard made this a high-water
               mark, so once dedup removed a file the Library went on counting
               it forever and the novel looked more downloaded than it was. */
            db.execSQL(
                "UPDATE novels SET disk_count=? WHERE folder=? AND slug=?",
                arrayOf(list.ordered.size, folder, slug),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /* chapters in the cached resolved listing (counts compressed ones too) */
    fun chapterListCount(folder: String, slug: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM chlist WHERE folder=? AND slug=? AND pos>=0",
            arrayOf(folder, slug),
        ).use { c -> if (c.moveToNext()) return c.getInt(0) }
        return 0
    }

    fun getChapterList(folder: String, slug: String): CachedChapterList? {
        val ordered = ArrayList<String>()
        val source = HashMap<String, String>()
        val translated = HashMap<String, String>()
        var any = false
        readableDatabase.query(
            "chlist", arrayOf("pos", "name", "src", "tr"),
            "folder=? AND slug=?", arrayOf(folder, slug), null, null, "pos",
        ).use { c ->
            while (c.moveToNext()) {
                any = true
                if (c.getInt(0) < 0) continue      // legacy meta row
                val name = c.getString(1)
                ordered.add(name)
                c.getString(2).ifEmpty { null }?.let { source[name] = it }
                c.getString(3).ifEmpty { null }?.let { translated[name] = it }
            }
        }
        if (!any || ordered.isEmpty()) return null
        return CachedChapterList(ordered, source, translated)
    }

    private fun bumpChlistEpoch(folder: String, slug: String) {
        val k = epochKey(folder, slug)
        chlistEpochs[k] = (chlistEpochs[k] ?: 0L) + 1
    }

    fun clearChapterList(folder: String, slug: String) {
        /* Bump FIRST. Bumping after the delete left a window the width of a
           database round-trip in which a walk could finish, read the old
           epoch, pass the check and restore exactly what the delete removed. */
        bumpChlistEpoch(folder, slug)
        writableDatabase.delete("chlist", "folder=? AND slug=?", arrayOf(folder, slug))
    }

    /* the compress pass rewrites refs across the whole library */
    fun clearAllChapterLists(folder: String) {
        /* One library-wide counter, added to every novel's. Bumping only the
           keys already in the map missed every novel no walk had touched yet
           in this process — including one being walked right now under an
           implicit 0 — so the compress pass could finish mid-walk and the
           stale listing, full of pre-gzip document ids, was written back.

           Bumped FIRST, for the reason clearChapterList states and this one
           had inverted: after the delete there is a window the width of a
           database round-trip in which a walk finishes, reads the old epoch,
           passes the check and writes back exactly what was just deleted —
           and here that is the whole library's worth of stale document ids,
           which is the one thing this call exists to get rid of. */
        chlistEpochAll.incrementAndGet()
        writableDatabase.delete("chlist", "folder=?", arrayOf(folder))
    }

    /* Every directory in the tree this library actually owns.
       `dir_name` is where each novel writes; `folder_owner` additionally holds
       names claimed by novels whose rows have since been merged away. The
       compress pass walks the tree the user picked, which can be a shared
       folder they keep other things in, and needs to stay inside these. */
    fun ownedDirNames(folder: String): Set<String> {
        val out = HashSet<String>()
        readableDatabase.query(
            "folder_owner", arrayOf("name"), "folder=?", arrayOf(folder), null, null, null,
        ).use { c ->
            while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        /* `dir_name` alone is not enough, and the caller's "empty set → walk
           everything" fallback does not cover the gap: v19 seeds the column
           from folder_owner, which v18 seeds from `titles` — the TRANSLATED
           title cache. So every untranslated novel from before v19 upgrades
           with no directory and no owner row, and the scan skips novels it
           already knows. Download one new novel and the set stops being empty,
           at which point every one of those older novels drops out of the
           compress pass for good: the pass reports "nothing changed",
           declares itself converged and clears its resume flag, and turning
           compression off leaves that library gzipped with nothing that will
           ever unpack it.

           So fall back per NOVEL, not for the set as a whole — the same
           chain dirNameOrGuess uses, which is the best answer available for a
           row that predates the column. */
        for (rec in novels(folder)) {
            val dir = dirNameFor(folder, rec.slug)
                ?: getTitle(folder, rec.slug)
                ?: Extractor.folderName(rec.title.ifEmpty { rec.slug }, rec.slug)
            if (dir.isNotEmpty()) out.add(dir)
        }
        return out
    }

    fun getChapterOrder(folder: String, slug: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        readableDatabase.query(
            "chapter_order", arrayOf("filename", "ord"),
            "folder=? AND slug=?", arrayOf(folder, slug), null, null, null,
        ).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getInt(1) }
        return out
    }

    fun chapterOrderCount(folder: String, slug: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM chapter_order WHERE folder=? AND slug=?", arrayOf(folder, slug),
        ).use { c -> if (c.moveToNext()) return c.getInt(0) }
        return 0
    }

    /* stamp a novel as just-opened in the reader */
    fun setLastRead(folder: String, slug: String, now: Long) {
        writableDatabase.execSQL(
            "UPDATE novels SET last_read=? WHERE folder=? AND slug=?", arrayOf(now, folder, slug),
        )
    }

    /* stamp a novel as just-downloaded (list sorts newest first) */
    fun touchNovel(folder: String, slug: String, now: Long) {
        writableDatabase.execSQL(
            "UPDATE novels SET last_dl=? WHERE folder=? AND slug=?", arrayOf(now, folder, slug),
        )
    }

    /* ---- one-time root-folder scan marker ---- */

    fun isScanned(folder: String): Boolean {
        readableDatabase.query(
            "scanned", arrayOf("at"), "folder=?", arrayOf(folder), null, null, null,
        ).use { c -> return c.moveToNext() }
    }

    fun markScanned(folder: String, now: Long) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO scanned(folder,at) VALUES(?,?)", arrayOf(folder, now),
        )
    }

    fun setDiskCount(folder: String, slug: String, n: Int) {
        writableDatabase.execSQL(
            "UPDATE novels SET disk_count=? WHERE folder=? AND slug=?", arrayOf(n, folder, slug),
        )
    }

    /* ---- novels registry (List Novels screen) ---- */

    /* record a novel the first time it downloads; url/title refresh each run
       but the original started timestamp is kept */
    fun registerNovel(folder: String, slug: String, url: String, title: String, now: Long) {
        val db = writableDatabase
        db.execSQL(
            "INSERT OR IGNORE INTO novels(folder,slug,url,title,started,total,complete) VALUES(?,?,?,?,?,-1,0)",
            arrayOf(folder, slug, url, title, now),
        )
        db.execSQL(
            "UPDATE novels SET url=?, title=? WHERE folder=? AND slug=?",
            arrayOf(url, title, folder, slug),
        )
    }

    /* Record which URL answered for a novel, WITHOUT touching its title.
       registerNovel rewrites both, and the status sweep passed it the title it
       happened to be displaying — which for a folder-scanned novel is the
       directory name, and which comes back stripped of its "- Author" suffix
       once an author is known. The stored title then stopped matching the
       folder on disk, and every screen that rebuilds a folder name from the
       title lost the novel. */
    fun recordNovelUrl(folder: String, slug: String, url: String, titleIfNew: String, now: Long) {
        val db = writableDatabase
        db.execSQL(
            "INSERT OR IGNORE INTO novels(folder,slug,url,title,started,total,complete) VALUES(?,?,?,?,?,-1,0)",
            arrayOf(folder, slug, url, titleIfNew, now),
        )
        db.execSQL("UPDATE novels SET url=? WHERE folder=? AND slug=?", arrayOf(url, folder, slug))
    }

    fun novels(folder: String): List<NovelRec> {
        val out = ArrayList<NovelRec>()
        readableDatabase.query(
            "novels",
            arrayOf("slug", "url", "title", "started", "total", "complete", "author", "disk_count", "last_dl", "last_read"),
            "folder=?", arrayOf(folder), null, null, null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    NovelRec(
                        c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "",
                        c.getString(6) ?: "",
                        c.getLong(3), c.getInt(4), c.getInt(5) != 0, c.getInt(7), c.getLong(8),
                        c.getLong(9),
                    ),
                )
            }
        }
        return out
    }

    fun removeNovel(folder: String, slug: String) {
        writableDatabase.delete("novels", "folder=? AND slug=?", arrayOf(folder, slug))
    }

    fun setAuthor(folder: String, slug: String, author: String) {
        writableDatabase.execSQL(
            "UPDATE novels SET author=? WHERE folder=? AND slug=?",
            arrayOf(author, folder, slug),
        )
    }

    fun updateNovelCheck(folder: String, slug: String, total: Int, complete: Boolean) {
        writableDatabase.execSQL(
            "UPDATE novels SET total=?, complete=? WHERE folder=? AND slug=?",
            arrayOf(total, if (complete) 1 else 0, folder, slug),
        )
    }

    /* distinct novels present in the chapter index but never registered
       (downloaded by an app version before the novels table existed) */
    fun chapterSlugs(folder: String): List<String> {
        val out = ArrayList<String>()
        readableDatabase.query(
            true, "chapters", arrayOf("slug"), "folder=?", arrayOf(folder), null, null, "slug", null,
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    fun chapterCount(folder: String, slug: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM chapters WHERE folder=? AND slug=?", arrayOf(folder, slug),
        ).use { c -> if (c.moveToNext()) return c.getInt(0) }
        return 0
    }

    /* Does the index know about any OTHER novel in this tree?

       This is what tells a reinstall apart from a collision. Both arrive at
       an existing folder that nothing in the database claims; only one of
       them is somebody else's work. If the index has never heard of any novel
       here, no other novel can be the folder's owner — the app was reinstalled
       (or its data cleared) on top of a library that is still on disk. If it
       does know other novels, an unclaimed folder under our name may well be
       one of theirs, from before names were recorded, and we step aside. */
    fun hasOtherChapters(folder: String, slug: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT EXISTS(SELECT 1 FROM chapters WHERE folder=? AND slug<>?)",
            arrayOf(folder, slug),
        ).use { c -> if (c.moveToNext()) return c.getInt(0) != 0 }
        return false
    }

    /* ---- translated novel-folder name cache: slug -> "English (Vietnamese)" ---- */

    fun getTitle(folder: String, slug: String): String? {
        readableDatabase.query(
            "titles", arrayOf("english"), "folder=? AND slug=?", arrayOf(folder, slug), null, null, null,
        ).use { c -> if (c.moveToNext()) return c.getString(0) }
        return null
    }

    fun setTitle(folder: String, slug: String, english: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO titles(folder,slug,english) VALUES(?,?,?)",
            arrayOf(folder, slug, english),
        )
    }

    /* Which novel already answers to this folder name, if any. Folder names
       come from sanitised titles, and sanitising folds away tone marks — on a
       Vietnamese site two genuinely different novels routinely reduce to the
       same name. Chapters are named by position, so the second novel writes
       Chapter 1..N straight over the first one's.

       Its own table. This started out reusing `titles`, which is the
       TRANSLATED-title cache: claiming a folder there overwrote the English
       name, so a single untranslated run pointed the whole library at an
       empty folder, discarded a paid title translation, and made
       ensureEnglishTitle short-circuit on the Vietnamese name so translation
       could never be turned on afterwards. */
    fun slugOwningName(folder: String, name: String): String? {
        readableDatabase.query(
            "folder_owner", arrayOf("slug"), "folder=? AND name=?", arrayOf(folder, name),
            null, null, null,
        ).use { c -> if (c.moveToNext()) return c.getString(0) }
        return null
    }

    /* The directory this novel actually writes into. Recorded because it
       cannot be re-derived: a novel pushed off a colliding name lives under
       "Title (slug)", and recomputing the name from the title each run gives
       the UNSUFFIXED one — which is another novel's folder. */
    fun dirNameFor(folder: String, slug: String): String? {
        readableDatabase.query(
            "novels", arrayOf("dir_name"), "folder=? AND slug=?", arrayOf(folder, slug),
            null, null, null,
        ).use { c -> if (c.moveToNext()) return c.getString(0)?.ifEmpty { null } }
        return null
    }

    /* The folder to open for a novel, for the screens that hold only a slug
       and a title. They all used to rebuild it from the title, and the title
       is the one thing that cannot answer this:

         - a novel pushed off a colliding name lives under "Title (slug)",
           while the title rebuilds the UNSUFFIXED name — another novel's
           folder, which one screen then RECURSIVELY DELETED;
         - a translated novel whose folder rename the provider refused keeps
           its Vietnamese folder while the English title is already cached, so
           the rebuilt name is a directory that does not exist;
         - the status sweep rewrites `novels.title`, and once an author is
           known it is stored stripped — so the name stops matching the folder
           on disk with no collision involved at all.

       The recorded directory is the answer to all three. The rest is only a
       fallback for a library older than the column. */
    fun dirNameOrGuess(folder: String, slug: String, title: String): String =
        dirNameFor(folder, slug)
            ?: getTitle(folder, slug)
            ?: Extractor.folderName(title.ifEmpty { slug }, slug)

    fun setDirName(folder: String, slug: String, name: String) {
        writableDatabase.execSQL(
            "UPDATE novels SET dir_name=? WHERE folder=? AND slug=?", arrayOf(name, folder, slug),
        )
    }

    fun claimFolderName(folder: String, name: String, slug: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO folder_owner(folder,name,slug) VALUES(?,?,?)",
            arrayOf(folder, name, slug),
        )
    }

    /* Claim a name only if nobody already holds it.

       The folder scan adopts directories it finds on disk and invents a slug
       from the directory name, so it must not be able to take a name from the
       novel that really owns it — that pushed the real one into a fresh folder
       and re-downloaded it. But it must still record that the folder is SPOKEN
       FOR: without any claim, `Ownership.ours` sees no owner, no recorded
       directory and no chapters in the index (the scan writes none) and hands
       the folder to the next novel whose title sanitises to the same name,
       which then adopts the other book's chapters as its own. */
    fun claimFolderNameIfFree(folder: String, name: String, slug: String) {
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO folder_owner(folder,name,slug) VALUES(?,?,?)",
            arrayOf(folder, name, slug),
        )
    }

    /* The novel is gone and its folder with it — let go of the name, or it
       stays reserved for a slug that no longer exists and the next novel that
       sanitises to it is pushed into a "Title (slug)" folder for nothing. */
    fun releaseFolderName(folder: String, slug: String) {
        writableDatabase.delete("folder_owner", "folder=? AND slug=?", arrayOf(folder, slug))
    }

    /* ---- pending Message Batches (orphaned-batch recovery) ---- */

    /* `files` is where each chapter lived when the batch was submitted, and a
       filename is a position, not an identity — the listing shifts and the
       renamer moves every file. `urls` records the page each one came from so
       a batch collected days later can be re-pointed at wherever those
       chapters live now. Rows written before this column carry no urls and
       fall back to the filenames, which is what they always did. */
    fun addPending(
        folder: String,
        slug: String,
        batchId: String,
        files: List<String>,
        urls: List<String>,
        created: Long,
        wantTitle: String? = null,
    ) {
        val arr = JSONArray().apply { for (f in files) put(f) }
        val urlArr = JSONArray().apply { for (u in urls) put(u) }
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO pending_batches(batch_id,folder,slug,files,urls,created,tries,want_title) VALUES(?,?,?,?,?,?,0,?)",
            arrayOf(batchId, folder, slug, arr.toString(), urlArr.toString(), created, wantTitle),
        )
    }

    /* pending batches for one novel whose results Anthropic still retains */
    fun pendingFor(folder: String, slug: String, now: Long): List<PendingBatch> {
        val out = ArrayList<PendingBatch>()
        readableDatabase.query(
            "pending_batches", arrayOf("batch_id", "files", "created", "tries", "want_title", "urls"),
            "folder=? AND slug=?", arrayOf(folder, slug), null, null, "created",
        ).use { c ->
            while (c.moveToNext()) {
                val created = c.getLong(2)
                if (now - created >= RETAIN_MS) continue
                fun jsonList(s: String?): ArrayList<String> {
                    val out = ArrayList<String>()
                    if (s.isNullOrEmpty()) return out
                    try {
                        val arr = JSONArray(s)
                        for (i in 0 until arr.length()) out.add(arr.getString(i))
                    } catch (e: Exception) {}
                    return out
                }
                out.add(
                    PendingBatch(
                        c.getString(0), jsonList(c.getString(1)), created,
                        c.getInt(3), c.getString(4), jsonList(c.getString(5)),
                    ),
                )
            }
        }
        return out
    }

    fun removePending(batchId: String) {
        writableDatabase.delete("pending_batches", "batch_id=?", arrayOf(batchId))
    }

    /* record a failed recovery attempt; returns the new try count */
    fun bumpPendingTries(batchId: String): Int {
        writableDatabase.execSQL(
            "UPDATE pending_batches SET tries=tries+1 WHERE batch_id=?", arrayOf(batchId),
        )
        readableDatabase.query(
            "pending_batches", arrayOf("tries"), "batch_id=?", arrayOf(batchId), null, null, null,
        ).use { c -> if (c.moveToNext()) return c.getInt(0) }
        return 0
    }

    /* startup hygiene: drop records whose results are no longer retained */
    fun prunePending(now: Long) {
        writableDatabase.delete(
            "pending_batches", "created < ?", arrayOf((now - RETAIN_MS).toString()),
        )
    }

    /* translation glossary: Vietnamese term -> fixed English rendering */
    fun getNames(folder: String, slug: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        readableDatabase.query(
            "names", arrayOf("vi", "en"),
            "folder=? AND slug=?", arrayOf(folder, slug), null, null, "vi",
        ).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getString(1) }
        return out
    }

    fun addNames(folder: String, slug: String, pairs: List<Pair<String, String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((vi, en) in pairs) {
                db.execSQL(
                    "INSERT OR IGNORE INTO names(folder,slug,vi,en) VALUES(?,?,?,?)",
                    arrayOf(folder, slug, vi, en),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /* filename -> document URI for one novel, ordered by filename */
    fun get(folder: String, slug: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        readableDatabase.query(
            "chapters", arrayOf("filename", "uri"),
            "folder=? AND slug=?", arrayOf(folder, slug), null, null, "filename",
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
        }
        return out
    }

    /* page URL -> the filename that chapter lives under. This is the novel's
       identity map: a chapter keeps its file for life, so the site is free to
       relabel or renumber it without us downloading it again under a new
       name. Rows written before the url column existed are skipped. */
    fun urlMap(folder: String, slug: String): HashMap<String, String> {
        val out = HashMap<String, String>()
        readableDatabase.query(
            "chapters", arrayOf("url", "filename"),
            "folder=? AND slug=? AND url IS NOT NULL AND url<>''", arrayOf(folder, slug),
            null, null, null,
        ).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getString(1) }
        return out
    }

    /* a chapter's file moved to a new name (the listing shifted under it) */
    fun renameChapter(folder: String, slug: String, from: String, to: String, uri: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            /* Called once per on-disk form — the plain file and its .gz — with
               the SAME base names both times. Without this guard the second
               call deletes the row the first one just migrated, taking the
               url identity with it: exactly the loss the index is meant to
               prevent, through a different door. */
            val hasFrom = db.rawQuery(
                "SELECT 1 FROM chapters WHERE folder=? AND slug=? AND filename=? LIMIT 1",
                arrayOf(folder, slug, from),
            ).use { it.moveToFirst() }
            if (hasFrom) {
                db.delete("chapters", "folder=? AND slug=? AND filename=?", arrayOf(folder, slug, to))
                db.execSQL(
                    "UPDATE chapters SET filename=?, uri=? WHERE folder=? AND slug=? AND filename=?",
                    arrayOf(to, uri, folder, slug, from),
                )
            } else {
                /* already migrated — just refresh where it lives */
                db.execSQL(
                    "UPDATE chapters SET uri=? WHERE folder=? AND slug=? AND filename=?",
                    arrayOf(uri, folder, slug, to),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        /* the resolved listing caches names AND document ids; every other
           mutator drops it, this one didn't — leaving the reader dead ids for
           every chapter that moved */
        clearChapterList(folder, slug)
    }

    /* filename -> (size, content hash) for everything we've fingerprinted.
       Hashing costs a read, so it's only ever done for files we suspect of
       being duplicates, and remembered here so a later pass never repeats
       it. */
    fun fingerprints(folder: String, slug: String): HashMap<String, Pair<Long, String>> {
        val out = HashMap<String, Pair<Long, String>>()
        readableDatabase.query(
            "chapters", arrayOf("filename", "size", "hash"),
            "folder=? AND slug=? AND hash<>''", arrayOf(folder, slug), null, null, null,
        ).use { c -> while (c.moveToNext()) out[c.getString(0)] = Pair(c.getLong(1), c.getString(2)) }
        return out
    }

    fun setFingerprint(folder: String, slug: String, filename: String, size: Long, hash: String) {
        val db = writableDatabase
        /* The files this is called for are precisely the ones with no row —
           dedupe hashes leftovers nothing has indexed. UPDATE alone matched
           nothing, so the hash was thrown away and every later sweep re-read
           and re-hashed the same files forever. Insert the row first. */
        db.execSQL(
            "INSERT OR IGNORE INTO chapters(folder,slug,filename,uri) VALUES(?,?,?,'')",
            arrayOf(folder, slug, filename),
        )
        db.execSQL(
            "UPDATE chapters SET size=?, hash=? WHERE folder=? AND slug=? AND filename=?",
            arrayOf(size, hash, folder, slug, filename),
        )
    }

    fun removeChapter(folder: String, slug: String, filename: String) {
        val gone = writableDatabase.delete(
            "chapters", "folder=? AND slug=? AND filename=?", arrayOf(folder, slug, filename),
        )
        /* One fewer file on disk. Without this the Library kept counting it:
           clearChapterList drops the resolved listing, so the row falls back
           to disk_count, which a delete never lowered.

           Only when a row actually went. A chapter present as both
           "Chapter 900.txt" and "Chapter 900.txt.gz" — what an interrupted
           compress pass leaves — is two files sharing one base name, so the
           dedupe calls this twice and the count would fall by two for one
           chapter. That undercount is the same bug as the 103/100 overcount,
           just pointing the other way. */
        if (gone > 0) {
            writableDatabase.execSQL(
                "UPDATE novels SET disk_count=disk_count-1 WHERE folder=? AND slug=? AND disk_count>0",
                arrayOf(folder, slug),
            )
        }
        clearChapterList(folder, slug)
    }

    /* adopt a file downloaded before URLs were recorded */
    fun linkUrl(folder: String, slug: String, filename: String, url: String) {
        writableDatabase.execSQL(
            "UPDATE chapters SET url=? WHERE folder=? AND slug=? AND filename=?",
            arrayOf(url, folder, slug, filename),
        )
    }

    fun add(folder: String, slug: String, filename: String, uri: String, url: String = "") {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO chapters(folder,slug,filename,uri,url) VALUES(?,?,?,?,?)",
            arrayOf(folder, slug, filename, uri, url),
        )
        clearChapterList(folder, slug)   // listing changed
    }

    fun addAll(folder: String, slug: String, items: List<Pair<String, String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((name, uri) in items) {
                /* INSERT OR REPLACE would drop the row and write a fresh one,
                   defaulting url/size/hash away — and url is this file's
                   identity, which nothing on disk can reconstruct. Insert
                   only what's missing, then update the location alone. */
                db.execSQL(
                    "INSERT OR IGNORE INTO chapters(folder,slug,filename,uri) VALUES(?,?,?,?)",
                    arrayOf(folder, slug, name, uri),
                )
                db.execSQL(
                    "UPDATE chapters SET uri=? WHERE folder=? AND slug=? AND filename=?",
                    arrayOf(uri, folder, slug, name),
                )
            }
            db.delete("chlist", "folder=? AND slug=?", arrayOf(folder, slug))   // listing changed
            bumpChlistEpoch(folder, slug)   // same reason as setChapterOrder
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /* filename -> the page it came from, for every file we can identify.
       This is the map that makes a stray file answerable: is it a chapter
       the site still lists, one it dropped, or something we've never seen? */
    fun fileUrls(folder: String, slug: String): HashMap<String, String> {
        val out = HashMap<String, String>()
        readableDatabase.query(
            "chapters", arrayOf("filename", "url"),
            "folder=? AND slug=? AND url IS NOT NULL AND url<>''", arrayOf(folder, slug),
            null, null, null,
        ).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getString(1) }
        return out
    }

    /* The folder moved, so every cached URI is stale — but WHICH chapter each
       file is hasn't changed. Drop the location, keep the identity; clear()
       would throw both away and leave the novel unidentifiable. */
    fun clearUris(folder: String, slug: String) {
        writableDatabase.execSQL(
            "UPDATE chapters SET uri='' WHERE folder=? AND slug=?", arrayOf(folder, slug),
        )
        clearChapterList(folder, slug)
    }

    fun clear(folder: String, slug: String) {
        writableDatabase.delete("chapters", "folder=? AND slug=?", arrayOf(folder, slug))
        clearChapterList(folder, slug)
    }
}
