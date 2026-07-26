package dev.vtlinh.noveldownloader

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/* Chapters are stored compressed one file at a time — "Chapter N.txt.gz",
   translations under "translated/" — which cuts the storage footprint
   (~70% for text) and stays incremental: a chapter downloaded later is
   compressed on the next pass without touching anything else. */
object Zips {

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

    private fun docUri(treeUri: Uri, docId: String) =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    /* Compress a novel ONE CHAPTER AT A TIME: each loose "Chapter N.txt"
       (main and translated/) becomes "Chapter N.txt.gz" and the original is
       deleted only after the compressed copy is written. Incremental by
       nature — chapters downloaded later just get compressed on the next
       pass without touching anything else. Returns true when the dir
       changed. */
    fun compressDir(context: Context, cr: ContentResolver, treeUri: Uri, d: Saf.Entry): Boolean {
        val re = ChapterListActivity.CHAPTER_RE
        var changed = false

        /* gz every loose chapter file directly under parentDocId */
        fun gzChildren(parentDocId: String) {
            val kids = Saf.children(cr, treeUri, parentDocId)
            val byName = kids.associateBy { it.name }
            val parentUri = docUri(treeUri, parentDocId)
            for (f in kids) {
                if (f.isDir || isGzName(f.name) || !re.matches(f.name)) continue
                val target = f.name + ".gz"
                val existing = byName[target]
                /* a VALID compressed copy must exist before the loose original
                   is deleted; a partial .gz from an interrupted run is rewritten
                   from the still-present original, never trusted */
                val valid = existing != null && readGz(cr, treeUri, existing.docId) != null
                if (!valid) {
                    val text = Saf.readText(cr, treeUri, f.docId) ?: continue
                    existing?.let {
                        try { DocumentsContract.deleteDocument(cr, docUri(treeUri, it.docId)) } catch (e: Exception) {}
                    }
                    if (!writeGz(cr, parentUri, target, text)) continue
                }
                /* compressed copy verified — the loose original can go */
                try { DocumentsContract.deleteDocument(cr, docUri(treeUri, f.docId)) } catch (e: Exception) { continue }
                changed = true
            }
        }
        gzChildren(d.docId)
        val kids = Saf.children(cr, treeUri, d.docId)
        var tDocId = kids.firstOrNull { it.isDir && it.name == "translated" }?.docId
        tDocId?.let { gzChildren(it) }

        return changed
    }

    /* the reverse: each "Chapter N.txt.gz" back to a plain .txt, one
       chapter at a time */
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

        return changed
    }
}
