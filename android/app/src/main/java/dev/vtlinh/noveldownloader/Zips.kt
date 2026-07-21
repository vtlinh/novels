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

    /* Fold a novel dir's loose chapters (+translated/) and any existing
       archive into one freshly written chapters.zip, then delete the
       originals. Write-then-swap: nothing is removed until the new zip is
       complete. Returns true when the dir changed. */
    fun compressDir(context: Context, cr: ContentResolver, treeUri: Uri, d: Saf.Entry): Boolean {
        val re = ChapterListActivity.CHAPTER_RE
        val kids = Saf.children(cr, treeUri, d.docId)
        val loose = kids.filter { !it.isDir && re.matches(it.name) }
        val tdir = kids.firstOrNull { it.isDir && it.name == "translated" }
        val tloose = tdir?.let { t ->
            Saf.children(cr, treeUri, t.docId).filter { !it.isDir && re.matches(it.name) }
        } ?: emptyList()
        val oldZip = kids.firstOrNull { !it.isDir && isZipName(it.name) }
        if (loose.isEmpty() && tloose.isEmpty()) {
            /* nothing loose; at most normalize a stray chapters-new.zip name */
            if (oldZip != null && oldZip.name != NAME) {
                try {
                    DocumentsContract.renameDocument(cr, docUri(treeUri, oldZip.docId), NAME)
                    return true
                } catch (e: Exception) {}
            }
            return false
        }
        val oldCached = oldZip?.let { cached(context, cr, treeUri, it.docId, d.name) }

        /* write the new zip beside the old one, then swap */
        val newUri = DocumentsContract.createDocument(
            cr, docUri(treeUri, d.docId), "application/zip", "chapters-new.zip",
        ) ?: return false
        var count = 0
        val written = HashSet<String>()
        val os = cr.openOutputStream(newUri) ?: return false
        java.util.zip.ZipOutputStream(os).use { z ->
            fun put(entry: String, bytes: ByteArray) {
                if (written.add(entry)) {
                    z.putNextEntry(java.util.zip.ZipEntry(entry))
                    z.write(bytes)
                    z.closeEntry()
                    count++
                }
            }
            for (f in loose) {
                Saf.readText(cr, treeUri, f.docId)?.let { put(f.name, it.toByteArray()) }
            }
            for (f in tloose) {
                Saf.readText(cr, treeUri, f.docId)?.let { put("translated/" + f.name, it.toByteArray()) }
            }
            /* carry over anything already zipped that wasn't re-added */
            oldCached?.let { oc ->
                for (e in entries(oc)) {
                    if (e !in written) read(oc, e)?.let { put(e, it.toByteArray()) }
                }
            }
        }
        if (count == 0) {
            try { DocumentsContract.deleteDocument(cr, newUri) } catch (e: Exception) {}
            return false
        }
        /* the new zip is safely written: remove originals and rename it */
        oldZip?.let {
            try { DocumentsContract.deleteDocument(cr, docUri(treeUri, it.docId)) } catch (e: Exception) {}
        }
        for (f in loose + tloose) {
            try { DocumentsContract.deleteDocument(cr, docUri(treeUri, f.docId)) } catch (e: Exception) {}
        }
        try {
            DocumentsContract.renameDocument(cr, newUri, NAME)
        } catch (e: Exception) { /* "chapters-new.zip" is also recognized */ }
        return true
    }

    /* extract a novel's chapters.zip back to loose files and remove it */
    fun uncompressDir(context: Context, cr: ContentResolver, treeUri: Uri, d: Saf.Entry): Boolean {
        val kids = Saf.children(cr, treeUri, d.docId)
        val zipEnt = kids.firstOrNull { !it.isDir && isZipName(it.name) } ?: return false
        val zip = cached(context, cr, treeUri, zipEnt.docId, d.name) ?: return false
        val looseNames = kids.filter { !it.isDir }.map { it.name }.toHashSet()
        var tDocId = kids.firstOrNull { it.isDir && it.name == "translated" }?.docId
        val tNames = tDocId?.let { t ->
            Saf.children(cr, treeUri, t).map { it.name }.toHashSet()
        } ?: hashSetOf()
        for (e in entries(zip)) {
            val content = read(zip, e) ?: continue
            if (e.startsWith("translated/")) {
                val n = e.removePrefix("translated/")
                if (n in tNames) continue
                if (tDocId == null) {
                    tDocId = DocumentsContract.createDocument(
                        cr, docUri(treeUri, d.docId),
                        DocumentsContract.Document.MIME_TYPE_DIR, "translated",
                    )?.let { DocumentsContract.getDocumentId(it) }
                }
                val t = tDocId ?: continue
                DocumentsContract.createDocument(cr, docUri(treeUri, t), "text/plain", n)
                    ?.let { u -> cr.openOutputStream(u)?.use { it.write(content.toByteArray()) } }
            } else {
                if (e in looseNames) continue
                DocumentsContract.createDocument(cr, docUri(treeUri, d.docId), "text/plain", e)
                    ?.let { u -> cr.openOutputStream(u)?.use { it.write(content.toByteArray()) } }
            }
        }
        try {
            DocumentsContract.deleteDocument(cr, docUri(treeUri, zipEnt.docId))
        } catch (e: Exception) { return false }
        zip.delete()
        return true
    }
}
