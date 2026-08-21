package dev.vtlinh.noveldownloader

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/* SAF I/O for the documents folder. The naming rules live in Documents. */
object DocumentFiles {

    fun compressOn(ctx: Context): Boolean =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .let { it.getBoolean("compressNovels", it.getBoolean("zipDownloads", true)) }

    fun openReader(ctx: Context, title: String, plainName: String) {
        ctx.startActivity(
            Intent(ctx, ReaderActivity::class.java)
                .putExtra(Documents.EXTRA_DOCUMENT, true)
                .putExtra(Documents.EXTRA_FILE, plainName)
                .putExtra("dir", Documents.DIR)
                .putExtra("title", title)
                .putExtra("start", plainName)
                .putExtra("slug", Documents.slug(plainName))
                .addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
        )
    }

    fun list(ctx: Context, treeUri: Uri): List<Documents.Item> {
        val cr = ctx.contentResolver
        val dir = dirEntry(cr, treeUri, create = false) ?: return emptyList()
        val kids = try {
            Saf.children(cr, treeUri, dir.docId)
        } catch (e: Exception) {
            emptyList()
        }
        return Documents.resolve(
            kids.map { Folder.Item(it.name, it.docId, it.isDir, it.size) },
        )
    }

    fun read(ctx: Context, treeUri: Uri, plainName: String): String? {
        val item = list(ctx, treeUri).firstOrNull { it.plainName == plainName } ?: return null
        return try {
            if (Zips.isGzRef(item.ref)) {
                Zips.readGz(ctx.contentResolver, treeUri, Zips.gzDocId(item.ref))
            } else {
                Saf.readText(ctx.contentResolver, treeUri, item.ref)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun chapters(
        ctx: Context,
        treeUri: Uri,
        plainName: String,
    ): ChapterListActivity.Companion.Chapters {
        val item = list(ctx, treeUri).firstOrNull { it.plainName == plainName }
            ?: return ChapterListActivity.Companion.Chapters(
                emptyList(), emptyMap(), emptyMap(),
            )
        return ChapterListActivity.Companion.Chapters(
            listOf(item.plainName),
            mapOf(item.plainName to item.ref),
            emptyMap(),
        )
    }

    /* Returns the .txt name actually written (may be numbered if the title
       collided), or null on failure. `replacing` is the current .txt name
       when saving over an existing document — that stem is not treated as
       taken, and its files are removed once the new one is down. */
    fun write(
        ctx: Context,
        treeUri: Uri,
        title: String,
        text: String,
        replacing: String? = null,
    ): String? {
        val cr = ctx.contentResolver
        val dir = dirEntry(cr, treeUri, create = true) ?: return null
        val currentStem = replacing?.let { Documents.stemOf(it) }
        val taken = try {
            list(ctx, treeUri).map { it.title }.toSet() - setOfNotNull(currentStem)
        } catch (e: Exception) {
            emptySet()
        }
        val stem = Documents.uniqueStem(Documents.stem(title), taken)
        val name = Documents.plainName(stem)
        val compress = compressOn(ctx)
        val same = name == replacing || currentStem == stem
        return try {
            writeFile(cr, treeUri, dir, name, text, compress, replace = same)
            if (replacing != null && replacing != name) {
                deleteNamed(cr, treeUri, dir.docId, replacing)
            }
            name
        } catch (e: Exception) {
            null
        }
    }

    fun delete(ctx: Context, treeUri: Uri, plainName: String): Boolean {
        val cr = ctx.contentResolver
        val dir = dirEntry(cr, treeUri, create = false) ?: return true
        return deleteNamed(cr, treeUri, dir.docId, plainName)
    }

    /* Already gone is success — the reader is about to leave either way, and
       treating a missing file as failure stranded the user on a ghost page. */
    private fun deleteNamed(
        cr: ContentResolver,
        treeUri: Uri,
        parentId: String,
        plainName: String,
    ): Boolean {
        val kids = try {
            Saf.children(cr, treeUri, parentId, includeSize = false)
        } catch (e: Exception) {
            return false
        }
        var failed = false
        for (n in listOf(plainName, "$plainName.gz")) {
            val f = kids.firstOrNull { !it.isDir && it.name == n } ?: continue
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, f.docId)
            val ok = try { DocumentsContract.deleteDocument(cr, uri) } catch (e: Exception) { false }
            if (!ok) failed = true
        }
        return !failed
    }

    private fun dirEntry(cr: ContentResolver, treeUri: Uri, create: Boolean): Saf.Entry? {
        val kids = try {
            Saf.children(cr, treeUri, Saf.rootId(treeUri), includeSize = false)
        } catch (e: Exception) {
            emptyList()
        }
        /* Prefer the current store name. Fall back only to an exact
           "documents" leftover — not "Documents", which is the user's. */
        kids.firstOrNull { it.isDir && it.name.equals(Documents.DIR, ignoreCase = true) }?.let { return it }
        kids.firstOrNull { it.isDir && it.name == Documents.LEGACY_DIR }?.let { return it }
        if (!create) return null
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, Saf.rootId(treeUri))
        val made = try {
            DocumentsContract.createDocument(
                cr, parent, DocumentsContract.Document.MIME_TYPE_DIR, Documents.DIR,
            )
        } catch (e: Exception) { null } ?: return null
        val id = try { DocumentsContract.getDocumentId(made) } catch (e: Exception) { null }
            ?: return null
        val got = try { Zips.docName(cr, made) } catch (e: Exception) { null }
        /* A taken name is minted, not refused. If something else already
           owns "novel-documents", do not write into the minted sibling. */
        if (got != null && !got.equals(Documents.DIR, ignoreCase = true)) {
            try { DocumentsContract.deleteDocument(cr, made) } catch (e: Exception) {}
            return null
        }
        return Saf.Entry(id, got ?: Documents.DIR, true)
    }

    /* Same write-under-a-part-name-then-rename protection as a chapter.
       A kill mid-write must not leave a truncated file that the next open
       treats as the whole document. */
    private fun writeFile(
        cr: ContentResolver,
        treeUri: Uri,
        dir: Saf.Entry,
        name: String,
        text: String,
        compress: Boolean,
        replace: Boolean,
    ) {
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dir.docId)
        fun find(n: String): Saf.Entry? = try {
            Saf.children(cr, treeUri, dir.docId, includeSize = false)
                .firstOrNull { !it.isDir && it.name == n }
        } catch (e: Exception) { null }
        fun clear(n: String) {
            val f = find(n) ?: return
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, f.docId)
            val ok = try { DocumentsContract.deleteDocument(cr, uri) } catch (e: Exception) { false }
            if (ok) return
            if (find(n) != null) throw RuntimeException("could not replace $n")
        }
        if (replace) {
            clear(name)
            clear("$name.gz")
        }
        if (compress) {
            Zips.writeGzDoc(cr, parentUri, "$name.gz", text)?.let { return }
            val squatterEmpty = listOf(name, "$name.gz").any { n ->
                val f = find(n) ?: return@any false
                val bytes = try {
                    Saf.readBytes(cr, treeUri, f.docId)
                } catch (e: Exception) { null }
                bytes != null && bytes.isEmpty()
            }
            if (squatterEmpty) {
                try { clear(name); clear("$name.gz") } catch (e: Exception) {}
                Zips.writeGzDoc(cr, parentUri, "$name.gz", text)?.let { return }
            }
        }
        val tmp = Zips.partName(name)
        val u = try {
            DocumentsContract.createDocument(cr, parentUri, "text/plain", tmp)
        } catch (e: Exception) { null }
            ?: throw RuntimeException("could not create $name")
        try {
            cr.openOutputStream(u).use { os ->
                if (os == null) throw java.io.IOException("could not open $name")
                os.write(text.toByteArray(Charsets.UTF_8))
            }
            val done = DocumentsContract.renameDocument(cr, u, name)
                ?: throw RuntimeException("could not name $name")
            val got = Zips.docName(cr, done)
            if (got != null && Zips.isMinted(name, got)) {
                try { DocumentsContract.deleteDocument(cr, done) } catch (e2: Exception) {}
                throw RuntimeException("$name is taken")
            }
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(cr, u) } catch (e2: Exception) {}
            throw e
        }
    }
}
