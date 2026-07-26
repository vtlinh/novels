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

    fun readGz(cr: ContentResolver, treeUri: Uri, docId: String): String? =
        readGzBytes(cr, treeUri, docId)?.toString(Charsets.UTF_8)

    /* the same, undecoded — see Saf.readBytes for why anything that only
       moves a file's contents must not go through a String */
    fun readGzBytes(cr: ContentResolver, treeUri: Uri, docId: String): ByteArray? = try {
        cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))?.use { ins ->
            java.util.zip.GZIPInputStream(ins).use { it.readBytes() }
        }
    } catch (e: Exception) { null }

    /* returns the new document's Uri, so a caller writing a chapter straight
       to disk can index it without listing the folder again */
    fun writeGzDoc(cr: ContentResolver, parentDocUri: Uri, name: String, text: String): Uri? {
        /* The document has to exist before it can be written, so every failure
           after this line leaves one behind. Left there, an empty or truncated
           .gz is worse than no file at all: the index counts it as the chapter
           (it has a length, and no page is recorded against it), so the chapter
           is never fetched again and reads blank for good. Clear it up. */
        val u = try {
            DocumentsContract.createDocument(cr, parentDocUri, "application/gzip", name)
        } catch (e: Exception) { null } ?: return null
        return try {
            /* opened inside the try and closed by it: the GZIPOutputStream
               constructor writes the header and can throw, and its close()
               finishes the deflate stream before closing the one below. */
            cr.openOutputStream(u).use { os ->
                if (os == null) throw java.io.IOException("could not open $name")
                java.util.zip.GZIPOutputStream(os).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
            u
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(cr, u) } catch (e2: Exception) {}
            null
        }
    }

    fun writeGz(cr: ContentResolver, parentDocUri: Uri, name: String, text: String): Boolean =
        writeGzDoc(cr, parentDocUri, name, text) != null

    /* gzip bytes exactly as given, for the compress pass — it is moving a
       file, not authoring one, so it must not reinterpret the encoding */
    fun writeGzBytes(cr: ContentResolver, parentDocUri: Uri, name: String, bytes: ByteArray): Boolean {
        val u = try {
            DocumentsContract.createDocument(cr, parentDocUri, "application/gzip", name)
        } catch (e: Exception) { null } ?: return false
        return try {
            cr.openOutputStream(u).use { os ->
                if (os == null) throw java.io.IOException("could not open $name")
                java.util.zip.GZIPOutputStream(os).use { it.write(bytes) }
            }
            true
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(cr, u) } catch (e2: Exception) {}
            false
        }
    }

    fun docSize(cr: ContentResolver, uri: Uri): Long = try {
        cr.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        } ?: -1L
    } catch (e: Exception) { -1L }

    private fun docUri(treeUri: Uri, docId: String) =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    /* deleteDocument reports a refusal by RETURNING false — only a missing
       file throws. Both mean "still there", and every caller here is about to
       create a file under that name, so both have to stop it. */
    private fun deleteDoc(cr: ContentResolver, uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(cr, uri)
    } catch (e: Exception) { false }

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
                val valid = existing != null && readGzBytes(cr, treeUri, existing.docId) != null
                if (!valid) {
                    val bytes = Saf.readBytes(cr, treeUri, f.docId) ?: continue
                    /* A provider can refuse a delete by RETURNING false rather
                       than throwing. Ignoring that let the create below collide
                       and mint "Chapter 5.txt (1).gz" — a name no pattern in
                       the app matches — and then the loose original, the only
                       readable copy, was deleted on the strength of it. */
                    if (existing != null && !deleteDoc(cr, docUri(treeUri, existing.docId))) continue
                    if (!writeGzBytes(cr, parentUri, target, bytes)) continue
                }
                /* compressed copy verified — the loose original can go */
                if (!deleteDoc(cr, docUri(treeUri, f.docId))) continue
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
                /* bytes, not text: decoding and re-encoding would rewrite
                   anything that isn't valid UTF-8 as replacement characters
                   and then delete the .gz that still held the real thing */
                val bytes = readGzBytes(cr, treeUri, f.docId) ?: continue   // unreadable .gz: leave it be
                /* A plain file being PRESENT is not proof it holds the
                   chapter: an interrupted run (a kill, a full volume) leaves
                   an empty or short one behind, and trusting the name alone
                   meant skipping the rewrite and then deleting the .gz — the
                   only good copy. Compare what's there against what we're
                   about to write, exactly as the compress direction verifies
                   its .gz before dropping the original. */
                val existing = kids.firstOrNull { !it.isDir && it.name == target }
                if (existing == null || existing.size != bytes.size.toLong()) {
                    /* a refused delete is reported, not thrown — going ahead
                       would create a second file under a minted name and then
                       drop the .gz that is still the only good copy */
                    if (existing != null && !deleteDoc(cr, docUri(treeUri, existing.docId))) continue
                    val u = try {
                        DocumentsContract.createDocument(cr, parentUri, "text/plain", target)
                    } catch (e: Exception) { null } ?: continue
                    val ok = try {
                        cr.openOutputStream(u).use { os ->
                            if (os == null) throw java.io.IOException("could not open $target")
                            os.write(bytes)
                        }
                        true
                    } catch (e: Exception) {
                        try { DocumentsContract.deleteDocument(cr, u) } catch (e2: Exception) {}
                        false
                    }
                    if (!ok) continue
                    names.add(target)
                }
                if (!deleteDoc(cr, docUri(treeUri, f.docId))) continue
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
