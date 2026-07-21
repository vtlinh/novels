package dev.vtlinh.noveldownloader

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.zip.ZipFile

/* Chapters can live zipped in a per-novel "chapters.zip" to cut the storage
   footprint (~70% for text). Entries are the plain chapter filenames, with
   translations under "translated/". Reads go through a cached local copy so
   entry lookups are random-access instead of stream scans. */
object Zips {
    const val NAME = "chapters.zip"

    /* accept "chapters-new.zip" too: the compress swap leaves that name
       behind when a provider refuses renameDocument */
    private val ZIP_RE = Regex("chapters.*\\.zip")
    fun isZipName(name: String) = ZIP_RE.matches(name)

    private const val REF = "zip::"
    fun ref(entry: String) = REF + entry
    fun isRef(s: String) = s.startsWith(REF)
    fun entryOf(s: String) = s.removePrefix(REF)

    /* ---- per-chapter gzip (the current compression format) ---- */

    fun isGzName(name: String) = name.endsWith(".txt.gz")

    private const val GZREF = "gz::"
    fun gzRef(docId: String) = GZREF + docId
    fun isGzRef(s: String) = s.startsWith(GZREF)
    fun gzDocId(s: String) = s.removePrefix(GZREF)

    fun readGz(cr: ContentResolver, treeUri: Uri, docId: String): String? = try {
        cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))?.use { ins ->
            java.util.zip.GZIPInputStream(ins).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    } catch (e: Exception) { null }

    fun writeGz(cr: ContentResolver, parentDocUri: Uri, name: String, text: String): Boolean = try {
        val u = DocumentsContract.createDocument(cr, parentDocUri, "application/gzip", name)
        val os = u?.let { cr.openOutputStream(it) }
        if (os == null) {
            false
        } else {
            java.util.zip.GZIPOutputStream(os).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        }
    } catch (e: Exception) { false }

    fun docSize(cr: ContentResolver, uri: Uri): Long = try {
        cr.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        } ?: -1L
    } catch (e: Exception) { -1L }

    /* local random-access copy of a SAF-hosted zip, refreshed when the
       on-tree size changes */
    fun cached(context: Context, cr: ContentResolver, treeUri: Uri, zipDocId: String, key: String): File? {
        return try {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, zipDocId)
            val dir = File(context.cacheDir, "zips").apply { mkdirs() }
            val f = File(dir, key.hashCode().toString() + ".zip")
            val size = docSize(cr, uri)
            if (!f.exists() || f.length() != size) {
                cr.openInputStream(uri)?.use { ins ->
                    f.outputStream().use { ins.copyTo(it) }
                } ?: return null
            }
            f
        } catch (e: Exception) { null }
    }

    fun entries(zip: File): List<String> = try {
        ZipFile(zip).use { z ->
            z.entries().toList().filter { !it.isDirectory }.map { it.name }
        }
    } catch (e: Exception) { emptyList() }

    fun read(zip: File, entry: String): String? = try {
        ZipFile(zip).use { z ->
            z.getEntry(entry)?.let { e ->
                z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }
    } catch (e: Exception) { null }

    private fun docUri(treeUri: Uri, docId: String) =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    /* Compress a novel ONE CHAPTER AT A TIME: each loose "Chapter N.txt"
       (main and translated/) becomes "Chapter N.txt.gz" and the original is
       deleted only after the compressed copy is written. Incremental by
       nature — chapters downloaded later just get compressed on the next
       pass without touching anything else. A legacy chapters.zip is
       migrated entry-by-entry into per-chapter files, then removed.
       Returns true when the dir changed. */
    fun compressDir(context: Context, cr: ContentResolver, treeUri: Uri, d: Saf.Entry): Boolean {
        val re = ChapterListActivity.CHAPTER_RE
        var changed = false

        /* gz every loose chapter file directly under parentDocId */
        fun gzChildren(parentDocId: String) {
            val kids = Saf.children(cr, treeUri, parentDocId)
            val names = kids.map { it.name }.toHashSet()
            val parentUri = docUri(treeUri, parentDocId)
            for (f in kids) {
                if (f.isDir || isGzName(f.name) || !re.matches(f.name)) continue
                val target = f.name + ".gz"
                if (target !in names) {
                    val text = Saf.readText(cr, treeUri, f.docId) ?: continue
                    if (!writeGz(cr, parentUri, target, text)) continue
                    names.add(target)
                }
                /* compressed copy exists — the loose original can go */
                try { DocumentsContract.deleteDocument(cr, docUri(treeUri, f.docId)) } catch (e: Exception) { continue }
                changed = true
            }
        }
        gzChildren(d.docId)
        val kids = Saf.children(cr, treeUri, d.docId)
        var tDocId = kids.firstOrNull { it.isDir && it.name == "translated" }?.docId
        tDocId?.let { gzChildren(it) }

        /* migrate a legacy whole-novel archive to per-chapter files */
        val zipEnt = kids.firstOrNull { !it.isDir && isZipName(it.name) }
        if (zipEnt != null) {
            val zip = cached(context, cr, treeUri, zipEnt.docId, d.name)
            if (zip != null) {
                val mainNames = Saf.children(cr, treeUri, d.docId).map { it.name }.toHashSet()
                val tNames = tDocId?.let { t ->
                    Saf.children(cr, treeUri, t).map { it.name }.toHashSet()
                } ?: hashSetOf()
                var ok = true
                for (e in entries(zip)) {
                    val translatedEntry = e.startsWith("translated/")
                    val n = if (translatedEntry) e.removePrefix("translated/") else e
                    val have = if (translatedEntry) tNames else mainNames
                    if (n in have || "$n.gz" in have) continue
                    val content = read(zip, e)
                    if (content == null) { ok = false; continue }
                    if (translatedEntry && tDocId == null) {
                        tDocId = DocumentsContract.createDocument(
                            cr, docUri(treeUri, d.docId),
                            DocumentsContract.Document.MIME_TYPE_DIR, "translated",
                        )?.let { DocumentsContract.getDocumentId(it) }
                    }
                    val parent = if (translatedEntry) tDocId ?: continue else d.docId
                    if (writeGz(cr, docUri(treeUri, parent), "$n.gz", content)) {
                        have.add("$n.gz")
                    } else {
                        ok = false
                    }
                }
                if (ok) {
                    try {
                        DocumentsContract.deleteDocument(cr, docUri(treeUri, zipEnt.docId))
                        zip.delete()
                        changed = true
                    } catch (e: Exception) {}
                }
            }
        }
        return changed
    }

    /* the reverse: each "Chapter N.txt.gz" back to a plain .txt (and any
       legacy chapters.zip extracted), one chapter at a time */
    fun uncompressDir(context: Context, cr: ContentResolver, treeUri: Uri, d: Saf.Entry): Boolean {
        var changed = false

        fun unGzChildren(parentDocId: String) {
            val kids = Saf.children(cr, treeUri, parentDocId)
            val names = kids.map { it.name }.toHashSet()
            val parentUri = docUri(treeUri, parentDocId)
            for (f in kids) {
                if (f.isDir || !isGzName(f.name)) continue
                val target = f.name.removeSuffix(".gz")
                if (target !in names) {
                    val text = readGz(cr, treeUri, f.docId) ?: continue
                    val u = DocumentsContract.createDocument(cr, parentUri, "text/plain", target)
                        ?: continue
                    try {
                        cr.openOutputStream(u)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: continue
                    } catch (e: Exception) { continue }
                    names.add(target)
                }
                try { DocumentsContract.deleteDocument(cr, docUri(treeUri, f.docId)) } catch (e: Exception) { continue }
                changed = true
            }
        }
        unGzChildren(d.docId)
        val kids = Saf.children(cr, treeUri, d.docId)
        var tDocId = kids.firstOrNull { it.isDir && it.name == "translated" }?.docId
        tDocId?.let { unGzChildren(it) }

        /* legacy whole-novel archive → loose files */
        val zipEnt = kids.firstOrNull { !it.isDir && isZipName(it.name) }
        if (zipEnt != null) {
            val zip = cached(context, cr, treeUri, zipEnt.docId, d.name)
            if (zip != null) {
                val mainNames = Saf.children(cr, treeUri, d.docId).map { it.name }.toHashSet()
                val tNames = tDocId?.let { t ->
                    Saf.children(cr, treeUri, t).map { it.name }.toHashSet()
                } ?: hashSetOf()
                var ok = true
                for (e in entries(zip)) {
                    val translatedEntry = e.startsWith("translated/")
                    val n = if (translatedEntry) e.removePrefix("translated/") else e
                    val have = if (translatedEntry) tNames else mainNames
                    if (n in have) continue
                    val content = read(zip, e)
                    if (content == null) { ok = false; continue }
                    if (translatedEntry && tDocId == null) {
                        tDocId = DocumentsContract.createDocument(
                            cr, docUri(treeUri, d.docId),
                            DocumentsContract.Document.MIME_TYPE_DIR, "translated",
                        )?.let { DocumentsContract.getDocumentId(it) }
                    }
                    val parent = if (translatedEntry) tDocId ?: continue else d.docId
                    val u = DocumentsContract.createDocument(cr, docUri(treeUri, parent), "text/plain", n)
                    if (u == null) { ok = false; continue }
                    try {
                        cr.openOutputStream(u)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                            ?: run { ok = false }
                    } catch (e2: Exception) { ok = false }
                    have.add(n)
                }
                if (ok) {
                    try {
                        DocumentsContract.deleteDocument(cr, docUri(treeUri, zipEnt.docId))
                        zip.delete()
                        changed = true
                    } catch (e: Exception) {}
                }
            }
        }
        return changed
    }
}
