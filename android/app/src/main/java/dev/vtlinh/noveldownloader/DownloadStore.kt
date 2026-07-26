package dev.vtlinh.noveldownloader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

/* One submitted-but-not-yet-collected Message Batch, kept so a batch orphaned
   by an app restart can be recovered on the next run (Anthropic retains
   results 29 days). `files` is the bundle's chapter filenames in order, so a
   result's `n` maps to files[n-1]. */
data class PendingBatch(
    val batchId: String,
    val files: List<String>,
    val created: Long,
    val tries: Int,
    val wantTitle: String?,   // non-null on a title-translation batch (custom_id "title")
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
    SQLiteOpenHelper(context.applicationContext, "downloads.db", null, 16) {

    companion object {
        private const val RETAIN_MS = 29L * 24 * 60 * 60 * 1000   // Anthropic keeps batch results 29 days
        private const val NOVELS_TABLE =
            "CREATE TABLE IF NOT EXISTS novels (" +
                "folder TEXT, slug TEXT, url TEXT, title TEXT, " +
                "started INTEGER, total INTEGER DEFAULT -1, complete INTEGER DEFAULT 0, " +
                "author TEXT DEFAULT '', disk_count INTEGER DEFAULT 0, " +
                "last_dl INTEGER DEFAULT 0, last_read INTEGER DEFAULT 0, " +
                "PRIMARY KEY(folder, slug))"
        /* folders whose one-time root scan has been folded into the registry */
        private const val SCANNED_TABLE =
            "CREATE TABLE IF NOT EXISTS scanned (folder TEXT PRIMARY KEY, at INTEGER)"
        /* the site's exact chapter order (listing-page sequence), per novel */
        private const val ORDER_TABLE =
            "CREATE TABLE IF NOT EXISTS chapter_order (" +
                "folder TEXT, slug TEXT, filename TEXT, ord INTEGER, " +
                "PRIMARY KEY(folder, slug, filename))"
        /* cached resolved chapter listing (see CachedChapterList).
           Invalidated by every write to the chapter index / order, and by
           the compress pass. */
        private const val CHLIST_TABLE =
            "CREATE TABLE IF NOT EXISTS chlist (" +
                "folder TEXT, slug TEXT, pos INTEGER, name TEXT, src TEXT, tr TEXT, " +
                "PRIMARY KEY(folder, slug, pos))"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE chapters (" +
                "folder TEXT, slug TEXT, filename TEXT, uri TEXT, url TEXT DEFAULT '', " +
                "size INTEGER DEFAULT 0, hash TEXT DEFAULT '', " +
                "PRIMARY KEY(folder, slug, filename))",
        )
        db.execSQL(
            "CREATE TABLE names (" +
                "folder TEXT, slug TEXT, vi TEXT, en TEXT, " +
                "PRIMARY KEY(folder, slug, vi))",
        )
        db.execSQL(
            "CREATE TABLE pending_batches (" +
                "batch_id TEXT PRIMARY KEY, folder TEXT, slug TEXT, " +
                "files TEXT, created INTEGER, tries INTEGER, want_title TEXT)",
        )
        db.execSQL(
            "CREATE TABLE titles (" +
                "folder TEXT, slug TEXT, english TEXT, " +
                "PRIMARY KEY(folder, slug))",
        )
        db.execSQL(NOVELS_TABLE)
        db.execSQL(SCANNED_TABLE)
        db.execSQL(ORDER_TABLE)
        db.execSQL(CHLIST_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) {
            /* pre-v4 schemas differ in place — rebuild (it's all cache/index) */
            db.execSQL("DROP TABLE IF EXISTS chapters")
            db.execSQL("DROP TABLE IF EXISTS names")
            db.execSQL("DROP TABLE IF EXISTS pending_batches")
            db.execSQL("DROP TABLE IF EXISTS titles")
            db.execSQL("DROP TABLE IF EXISTS novels")
            onCreate(db)
            return
        }
        /* v4+ upgrades are additive; keep everything else (the names
           glossary especially is not rebuildable) */
        if (oldVersion < 5) db.execSQL(NOVELS_TABLE)   // creates the full current shape
        if (oldVersion == 5) db.execSQL("ALTER TABLE novels ADD COLUMN author TEXT DEFAULT ''")
        if (oldVersion in 5..6) db.execSQL("ALTER TABLE novels ADD COLUMN disk_count INTEGER DEFAULT 0")
        if (oldVersion < 7) db.execSQL(SCANNED_TABLE)
        if (oldVersion in 5..7) db.execSQL("ALTER TABLE novels ADD COLUMN last_dl INTEGER DEFAULT 0")
        if (oldVersion in 5..8) db.execSQL("ALTER TABLE novels ADD COLUMN last_read INTEGER DEFAULT 0")
        if (oldVersion < 10) db.execSQL(ORDER_TABLE)
        /* v10 orders were polluted by the sites' "latest chapters" widget —
           purge so downloads / Check status re-index with correct scoping */
        if (oldVersion == 10) db.execSQL("DELETE FROM chapter_order")
        if (oldVersion < 12) db.execSQL(CHLIST_TABLE)
        /* v12 orders were polluted the same way v10's were: the scoped
           chapter list was consulted only for links we hadn't seen, so a
           listing page of already-seen chapters counted as empty and fell
           through to the whole document — where the "latest chapters" widget
           lives. Purge the orders and the cached listings built from them so
           both re-index cleanly. */
        if (oldVersion < 13) {
            db.execSQL("DELETE FROM chapter_order")
            db.execSQL("DELETE FROM chlist")
        }
        /* a chapter's page URL, so its file keeps the same name for life
           however the site relabels or renumbers it. Blank for everything
           downloaded before this column existed; the engine fills those in
           as it recognises them. */
        if (oldVersion < 14) {
            try { db.execSQL("ALTER TABLE chapters ADD COLUMN url TEXT DEFAULT ''") } catch (e: Exception) {}
        }
        /* size + content hash, so a duplicate left behind by an earlier
           naming scheme can be recognised as the same chapter and dropped */
        if (oldVersion < 15) {
            try { db.execSQL("ALTER TABLE chapters ADD COLUMN size INTEGER DEFAULT 0") } catch (e: Exception) {}
            try { db.execSQL("ALTER TABLE chapters ADD COLUMN hash TEXT DEFAULT ''") } catch (e: Exception) {}
        }
        /* v15 hashed the whole file, heading included, so the same chapter
           saved under two numbering schemes never matched itself. Hashes are
           of the body now — drop the old ones rather than compare across
           two meanings. */
        if (oldVersion == 15) db.execSQL("UPDATE chapters SET hash=''")
    }

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
            db.delete("chlist", "folder=? AND slug=?", arrayOf(folder, slug))   // order changed
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /* ---- cached resolved chapter listing (chlist) ---- */

    fun saveChapterList(
        folder: String,
        slug: String,
        list: CachedChapterList,
    ) {
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

    fun clearChapterList(folder: String, slug: String) {
        writableDatabase.delete("chlist", "folder=? AND slug=?", arrayOf(folder, slug))
    }

    /* the compress pass rewrites refs across the whole library */
    fun clearAllChapterLists(folder: String) {
        writableDatabase.delete("chlist", "folder=?", arrayOf(folder))
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

    /* ---- pending Message Batches (orphaned-batch recovery) ---- */

    fun addPending(
        folder: String,
        slug: String,
        batchId: String,
        files: List<String>,
        created: Long,
        wantTitle: String? = null,
    ) {
        val arr = JSONArray().apply { for (f in files) put(f) }
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO pending_batches(batch_id,folder,slug,files,created,tries,want_title) VALUES(?,?,?,?,?,0,?)",
            arrayOf(batchId, folder, slug, arr.toString(), created, wantTitle),
        )
    }

    /* pending batches for one novel whose results Anthropic still retains */
    fun pendingFor(folder: String, slug: String, now: Long): List<PendingBatch> {
        val out = ArrayList<PendingBatch>()
        readableDatabase.query(
            "pending_batches", arrayOf("batch_id", "files", "created", "tries", "want_title"),
            "folder=? AND slug=?", arrayOf(folder, slug), null, null, "created",
        ).use { c ->
            while (c.moveToNext()) {
                val created = c.getLong(2)
                if (now - created >= RETAIN_MS) continue
                val files = ArrayList<String>()
                try {
                    val arr = JSONArray(c.getString(1))
                    for (i in 0 until arr.length()) files.add(arr.getString(i))
                } catch (e: Exception) {}
                out.add(PendingBatch(c.getString(0), files, created, c.getInt(3), c.getString(4)))
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
        writableDatabase.execSQL(
            "UPDATE chapters SET size=?, hash=? WHERE folder=? AND slug=? AND filename=?",
            arrayOf(size, hash, folder, slug, filename),
        )
    }

    fun removeChapter(folder: String, slug: String, filename: String) {
        writableDatabase.delete(
            "chapters", "folder=? AND slug=? AND filename=?", arrayOf(folder, slug, filename),
        )
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
