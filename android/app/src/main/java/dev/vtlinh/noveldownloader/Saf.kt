package dev.vtlinh.noveldownloader

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/* Fast Storage Access Framework helpers: one ContentResolver query per
   directory (DocumentFile's per-file metadata lookups are a query per file). */
object Saf {

    class Entry(val docId: String, val name: String, val isDir: Boolean, val size: Long = -1L)

    fun children(cr: ContentResolver, treeUri: Uri, docId: String): List<Entry> {
        val out = ArrayList<Entry>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        cr.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    Entry(
                        c.getString(0), c.getString(1) ?: "",
                        c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        if (c.isNull(3)) -1L else c.getLong(3),
                    ),
                )
            }
        }
        return out
    }

    fun rootId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

    /* rename one document in place; returns its new docId (SAF may mint a
       fresh one) or null if the provider refused */
    fun rename(cr: ContentResolver, treeUri: Uri, docId: String, newName: String): String? = try {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        DocumentsContract.renameDocument(cr, uri, newName)?.let { DocumentsContract.getDocumentId(it) }
    } catch (e: Exception) {
        null
    }

    fun readText(cr: ContentResolver, treeUri: Uri, docId: String): String? = try {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        cr.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) { null }
}
