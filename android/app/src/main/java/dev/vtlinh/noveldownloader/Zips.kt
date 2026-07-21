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
}
