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
        val before = Zips.docName(cr, uri)
        DocumentsContract.renameDocument(cr, uri, newName)?.let { out ->
            /* A taken name does not fail a rename — SAF MINTS one and hands
               back a valid Uri. Reporting that as success recorded the index
               against a document called "Chapter 5.txt (1)", which matches no
               pattern in the app: invisible to the reader, the dedupe and
               every sweep, while the name it was meant to take still holds
               someone else's file.

               Put it back rather than delete it: this document IS the chapter,
               moved — not a copy — so removing it would destroy the file we
               were only trying to rename. */
            val got = Zips.docName(cr, out)
            if (got != null && Zips.isMinted(newName, got)) {
                if (before != null) {
                    try { DocumentsContract.renameDocument(cr, out, before) } catch (e: Exception) {}
                }
                null
            } else {
                DocumentsContract.getDocumentId(out)
            }
        }
    } catch (e: Exception) {
        null
    }

    fun readText(cr: ContentResolver, treeUri: Uri, docId: String): String? =
        readBytes(cr, treeUri, docId)?.toString(Charsets.UTF_8)

    /* Raw bytes, for anything that only MOVES a file's contents. Decoding to a
       String substitutes U+FFFD for anything that isn't valid UTF-8 rather
       than failing, so a round-trip through readText rewrites those bytes as
       replacement characters — and the compress pass then deletes the
       original. Chapters this app downloads are UTF-8, but a library copied
       in from elsewhere need not be. */
    fun readBytes(cr: ContentResolver, treeUri: Uri, docId: String): ByteArray? = try {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        cr.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) { null }
}
