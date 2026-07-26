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

    /* suffix a half-written chapter carries until its bytes are down; nothing
       else in the app matches a name ending in it, which is the point */
    const val PART = ".part"

    /* For a PLAIN chapter the mark has to go in FRONT of the name. SAF forces
       the extension to match the mime type, so "Chapter 5.txt.part" written as
       text/plain comes back as "Chapter 5.txt.part.txt" — which the chapter
       pattern happily matches (it ends in ".txt"), making the half-written
       file visible as a chapter: exactly what the temporary name exists to
       prevent. Nothing matches a name that doesn't begin with "Chapter". */
    const val PART_HEAD = "part~"

    fun partName(name: String) = "$PART_HEAD$name"

    /* Both tails, because SAF does not take a display name literally:
       ExternalStorageProvider runs it through buildUniqueFile, which forces
       the extension to match the mime type — ask for "Chapter 5.txt.gz.part"
       as application/gzip and the document created is
       "Chapter 5.txt.gz.part.gz". Matching only ".part" meant the sweep, the
       one thing that ever removes these, matched nothing.

       Anchored at the END, though. `contains` fixed that but deleted any file
       in a novel folder with ".part" anywhere in its name — the tree can be a
       shared folder the user also keeps other things in, and a
       "Chapter 12.part2.txt" or someone else's "movie.mp4.part" was removed
       silently and unrecoverably. We only ever create these two shapes. */
    fun isPartName(name: String) =
        name.endsWith(PART) || name.endsWith("$PART.gz") || name.startsWith(PART_HEAD)

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
        return writeGzUnder(cr, parentDocUri, name) { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /* Write under a name nothing in the app will adopt, and only rename it
       into place once the bytes are down. Cleaning up on EXCEPTION isn't
       enough: a process kill between creating the document and finishing the
       write runs no catch, and the truncated ".gz" it leaves has a length and
       no recorded page, so the index reads it as a completed chapter and never
       fetches it again — the chapter is blank for good, and the re-fetch that
       might have saved it can't overwrite the name either (SAF mints
       "Chapter 5.txt (1).gz", which matches no pattern here). A leftover
       ".part" matches nothing, so the chapter simply stays missing and the
       next run downloads it properly. */
    private fun writeGzUnder(
        cr: ContentResolver,
        parentDocUri: Uri,
        name: String,
        write: (java.io.OutputStream) -> Unit,
    ): Uri? {
        val tmp = "$name$PART"
        val u = try {
            DocumentsContract.createDocument(cr, parentDocUri, "application/gzip", tmp)
        } catch (e: Exception) { null } ?: return null
        return try {
            /* opened inside the try and closed by it: the GZIPOutputStream
               constructor writes the header and can throw, and its close()
               finishes the deflate stream before closing the one below. */
            cr.openOutputStream(u).use { os ->
                if (os == null) throw java.io.IOException("could not open $name")
                java.util.zip.GZIPOutputStream(os).use { write(it) }
            }
            val renamed = DocumentsContract.renameDocument(cr, u, name)
            renamed ?: throw java.io.IOException("could not name $name")
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(cr, u) } catch (e2: Exception) {}
            null
        }
    }

    fun writeGz(cr: ContentResolver, parentDocUri: Uri, name: String, text: String): Boolean =
        writeGzDoc(cr, parentDocUri, name, text) != null

    /* gzip bytes exactly as given, for the compress pass — it is moving a
       file, not authoring one, so it must not reinterpret the encoding */
    fun writeGzBytes(cr: ContentResolver, parentDocUri: Uri, name: String, bytes: ByteArray): Boolean =
        writeGzUnder(cr, parentDocUri, name) { it.write(bytes) } != null

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
            /* Sweep up half-written chapters from a run the system killed.
               They are invisible to everything else by design, so this is the
               only thing that would ever remove them; the chapter itself is
               absent from the index, so the next download fetches it again. */
            for (f in kids) {
                if (!f.isDir && isPartName(f.name)) deleteDoc(cr, docUri(treeUri, f.docId))
            }
            val byName = kids.associateBy { it.name }
            val parentUri = docUri(treeUri, parentDocId)
            for (f in kids) {
                if (f.isDir || isGzName(f.name) || !re.matches(f.name)) continue
                val target = f.name + ".gz"
                val existing = byName[target]
                /* a VALID compressed copy must exist before the loose original
                   is deleted; a partial .gz from an interrupted run is rewritten
                   from the still-present original, never trusted */
                /* Decodable is not the same as CURRENT. Accepting any .gz that
                   merely decompresses meant a freshly downloaded chapter,
                   written loose because the old .gz could not be deleted, was
                   thrown away in favour of the stale copy — and since the
                   index then pointed at that .gz under the right name, the
                   chapter served the wrong text forever and was never
                   re-fetched. Compare the bytes; the loose file is the one we
                   just wrote, so it wins. */
                val bytes = Saf.readBytes(cr, treeUri, f.docId) ?: continue
                val valid = existing != null &&
                    readGzBytes(cr, treeUri, existing.docId)?.contentEquals(bytes) == true
                if (!valid) {
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
