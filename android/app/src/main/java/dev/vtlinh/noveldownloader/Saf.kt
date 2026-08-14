package dev.vtlinh.noveldownloader

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/* Fast Storage Access Framework helpers: one ContentResolver query per
   directory (DocumentFile's per-file metadata lookups are a query per file). */
object Saf {

    class Entry(val docId: String, val name: String, val isDir: Boolean, val size: Long = -1L)

    /* `includeSize` is for files. Asking a provider for COLUMN_SIZE of a
       DIRECTORY can make it recursively sum every child before the cursor
       returns — a root listing of a large library then looks hung. Callers
       that only need names pass false. */
    fun children(
        cr: ContentResolver,
        treeUri: Uri,
        docId: String,
        includeSize: Boolean = true,
    ): List<Entry> {
        val out = ArrayList<Entry>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val cols = if (includeSize) arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        ) else arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        cr.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    Entry(
                        c.getString(0), c.getString(1) ?: "",
                        c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        if (!includeSize || c.isNull(3)) -1L else c.getLong(3),
                    ),
                )
            }
        }
        return out
    }

    fun rootId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

    /* One document's last-modified time, as a single-row query.

       For a DIRECTORY this moves whenever a child is added or removed, which
       is how the cached chapter listing notices files that ARRIVED rather than
       only files that went — see Folder.Stamp.

       0 both when the document is gone and when the provider reports no time
       at all. Neither is worth telling apart here: a provider that never
       reports one compares 0 against 0 and the check simply says nothing,
       while a document that has gone takes its whole listing with it anyway. */
    fun modified(cr: ContentResolver, treeUri: Uri, docId: String): Long = try {
        cr.query(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L
    } catch (e: Exception) {
        0L
    }

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
