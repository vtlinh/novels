package dev.vtlinh.noveldownloader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/* App-private SQLite index of downloaded chapters, keyed by (folder, slug).
   It's a fast cache, NOT the source of truth — the folder on disk is. Each
   row stores the chapter's document URI so existence can be checked in O(1)
   (no directory listing). On a new device / reinstall / copied folder the
   index is simply empty and DownloadEngine rebuilds it from one listing. */
class DownloadStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "downloads.db", null, 2) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS chapters")
        db.execSQL("DROP TABLE IF EXISTS names")
        onCreate(db)
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
