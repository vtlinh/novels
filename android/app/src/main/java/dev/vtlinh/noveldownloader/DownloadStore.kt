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
class DownloadStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "downloads.db", null, 4) {

    companion object {
        private const val RETAIN_MS = 29L * 24 * 60 * 60 * 1000   // Anthropic keeps batch results 29 days
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE chapters (" +
                "folder TEXT, slug TEXT, filename TEXT, uri TEXT, " +
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS chapters")
        db.execSQL("DROP TABLE IF EXISTS names")
        db.execSQL("DROP TABLE IF EXISTS pending_batches")
        db.execSQL("DROP TABLE IF EXISTS titles")
        onCreate(db)
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

    fun add(folder: String, slug: String, filename: String, uri: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO chapters(folder,slug,filename,uri) VALUES(?,?,?,?)",
            arrayOf(folder, slug, filename, uri),
        )
    }

    fun addAll(folder: String, slug: String, items: List<Pair<String, String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((name, uri) in items) {
                db.execSQL(
                    "INSERT OR REPLACE INTO chapters(folder,slug,filename,uri) VALUES(?,?,?,?)",
                    arrayOf(folder, slug, name, uri),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clear(folder: String, slug: String) {
        writableDatabase.delete("chapters", "folder=? AND slug=?", arrayOf(folder, slug))
    }
}
