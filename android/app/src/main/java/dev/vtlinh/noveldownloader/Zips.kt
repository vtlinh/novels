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
       silently and unrecoverably.

       Anchoring was not enough either: ".part" IS the common convention for a
       partial download, so `endsWith` still matched that same
       "movie.mp4.part" — the file the comment above was written about — and
       the sweep still deleted it. What identifies one of ours is not the mark
       alone but what the mark is attached to, so strip it and require a
       chapter name underneath. Nothing else is ours to remove. */
    fun isPartName(name: String): Boolean {
        val base = when {
            name.startsWith(PART_HEAD) -> name.removePrefix(PART_HEAD)
            name.endsWith("$PART.gz") -> name.removeSuffix("$PART.gz")
            name.endsWith(PART) -> name.removeSuffix(PART)
            else -> return false
        }
        return ChapterName.RE.matches(base.removeSuffix(".gz"))
    }

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
                ?: throw java.io.IOException("could not name $name")
            /* renameDocument runs the display name through buildUniqueFile
               exactly as createDocument does. A name already taken does NOT
               fail it — it comes back MINTED, "Chapter 5.txt (1).gz", and the
               call returns a perfectly valid Uri. Taking that as success
               recorded the minted file in the index as the chapter, while the
               reader kept serving the old file under the real name and the
               new one matched no pattern in the app: invisible to the reader,
               the dedupe and the sweep, and impossible to overwrite. Ask what
               we actually got. */
            val got = docName(cr, renamed)
            if (got != null && isMinted(name, got)) {
                try { DocumentsContract.deleteDocument(cr, renamed) } catch (e: Exception) {}
                throw java.io.IOException("$name is taken")
            }
            renamed
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

    /* Did the provider mint a UNIQUE name around ours? buildUniqueFile appends
       " (1)" to the stem on a collision.

       Any other rewrite is the provider normalising — an extension forced to
       match the mime type, which the code here relies on elsewhere. Treating
       every difference as a collision would delete the file just written and
       fail EVERY chapter on such a provider, so the test has to be this
       specific rather than "the name changed". */
    fun isMinted(want: String, got: String): Boolean {
        if (want == got) return false
        val wantStem = want.substringBeforeLast('.')
        val gotStem = got.substringBeforeLast('.')
        return Regex(".+ \\(\\d+\\)$").matches(gotStem) &&
            gotStem.substringBeforeLast(" (") == wantStem
    }

    /* What the provider actually called the document. SAF is free to rewrite
       a display name — buildUniqueFile on a collision, an extension forced to
       match the mime type — so the name we asked for is not the name we got. */
    fun docName(cr: ContentResolver, uri: Uri): String? = try {
        cr.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) { null }

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
                /* An EMPTY loose file never wins. "The loose file is the one
                   we just wrote, so it wins" holds for a chapter a download
                   just saved — not for the 0-byte stub an interrupted
                   uncompress leaves behind, and taking that as authoritative
                   deleted the .gz that still held the only copy and then
                   gzipped the stub. The chapter read blank for ever after,
                   and nothing re-fetched it: the index counts a ~20-byte
                   gzip as present. */
                if (bytes.isEmpty()) {
                    if (existing != null) deleteDoc(cr, docUri(treeUri, f.docId))
                    continue
                }
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
            val parentUri = docUri(treeUri, parentDocId)
            /* Half-written files from a killed run, swept here as the compress
               direction sweeps them — this pass is the only one that walks the
               folder when compression is off. */
            for (f in kids) {
                if (!f.isDir && isPartName(f.name)) deleteDoc(cr, docUri(treeUri, f.docId))
            }
            for (f in kids) {
                if (f.isDir || isPartName(f.name) || !isGzName(f.name)) continue
                val target = f.name.removeSuffix(".gz")
                /* OUR files only. The compress direction has always checked the
                   name against the chapter pattern; this one asked nothing but
                   ".txt.gz" — and the tree it walks is whatever folder the user
                   picked, one level down, which can be a shared folder they
                   keep other things in. Their own "notes.txt.gz" was
                   decompressed and the original deleted, silently. */
                if (!ChapterName.RE.matches(target)) continue
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
                /* Which copy is newer, not which is bigger. A loose chapter
                   beside a .gz is the shape the download itself leaves when
                   the .gz name could not be cleared: the loose file is the one
                   just written, and the compress direction says so in as many
                   words. Rewriting it from the .gz — on a SIZE test, so any
                   two chapters of equal length swapped silently — replaced
                   fresh text with stale and then deleted the .gz, losing both
                   copies of the new chapter. The index counts the file as
                   present, so nothing re-fetches it. Keep the loose one and
                   drop the archive. */
                if (existing != null && existing.size > 0L) {
                    val onDisk = Saf.readBytes(cr, treeUri, existing.docId)
                    if (onDisk != null && !onDisk.contentEquals(bytes)) {
                        if (deleteDoc(cr, docUri(treeUri, f.docId))) changed = true
                        continue
                    }
                }
                if (existing == null || existing.size != bytes.size.toLong()) {
                    /* a refused delete is reported, not thrown — going ahead
                       would create a second file under a minted name and then
                       drop the .gz that is still the only good copy */
                    if (existing != null && !deleteDoc(cr, docUri(treeUri, existing.docId))) continue
                    /* Under a name nothing adopts, then renamed into place —
                       the same protection every other writer in the app has.
                       This was the last one creating a document under its
                       final name: a kill mid-write left a stub that the next
                       compress pass then promoted over the good .gz. */
                    val u = try {
                        DocumentsContract.createDocument(cr, parentUri, "text/plain", partName(target))
                    } catch (e: Exception) { null } ?: continue
                    val ok = try {
                        cr.openOutputStream(u).use { os ->
                            if (os == null) throw java.io.IOException("could not open $target")
                            os.write(bytes)
                        }
                        val renamed = DocumentsContract.renameDocument(cr, u, target)
                            ?: throw java.io.IOException("could not name $target")
                        val got = docName(cr, renamed)
                        if (got != null && isMinted(target, got)) {
                            try { DocumentsContract.deleteDocument(cr, renamed) } catch (e: Exception) {}
                            throw java.io.IOException("$target is taken")
                        }
                        true
                    } catch (e: Exception) {
                        try { DocumentsContract.deleteDocument(cr, u) } catch (e2: Exception) {}
                        false
                    }
                    if (!ok) continue
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
