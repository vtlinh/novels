package dev.vtlinh.noveldownloader

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.InputStream

/* Copy a PDF, EPUB, or Kindle file into the library as an ordinary novel:
   one chapter file per piece, and each picture saved on the chapter it
   belonged to. The folder is registered before the chapters are written,
   so a library scan cannot adopt it as a book that still needs a website. */
object BookImport {

    fun import(
        ctx: Context,
        tree: Uri,
        source: Uri,
        displayName: String,
        onProgress: (String) -> Unit,
    ): String {
        val cr = ctx.contentResolver
        val bytes = cr.openInputStream(source)?.use { readCapped(it) }
            ?: throw ImportedBook.CannotRead(ImportedBook.OPEN)
        val book = ImportedBook.read(displayName, bytes)
        val folder = tree.toString()
        val store = DownloadStore(ctx)
        val taken = HashSet<String>()
        for (n in store.novels(folder)) taken.add(Ownership.normKey(n.slug))
        for (s in store.chapterSlugs(folder)) taken.add(Ownership.normKey(s))
        val slug = ImportedBook.uniqueSlug(ImportedBook.slugFor(book.title, displayName), taken)
        val rootId = Saf.rootId(tree)
        val takenNames = Saf.children(cr, tree, rootId, includeSize = false).map { it.name }.toSet()
        val wanted = ImportedBook.uniqueName(Extractor.folderName(book.title, slug), takenNames)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val made = DocumentsContract.createDocument(
            cr, parent, DocumentsContract.Document.MIME_TYPE_DIR, wanted,
        ) ?: throw ImportedBook.CannotRead(ImportedBook.SAVE)
        val dirId = DocumentsContract.getDocumentId(made)
        val dirName = Zips.docName(cr, made) ?: wanted
        store.registerNovel(folder, slug, ImportedBook.urlFor(book.format), book.title, System.currentTimeMillis())
        store.setDirName(folder, slug, dirName)
        store.claimFolderName(folder, dirName, slug)
        try {
            writeChapters(ctx, tree, dirId, folder, slug, dirName, book, onProgress)
        } catch (e: Exception) {
            deleteTree(cr, tree, dirId)
            try { store.setChapterOrder(folder, slug, emptyList()) } catch (ex: Exception) {}
            try { store.clear(folder, slug) } catch (ex: Exception) {}
            try { store.removeNovel(folder, slug) } catch (ex: Exception) {}
            try { store.releaseFolderName(folder, slug) } catch (ex: Exception) {}
            if (e is ImportedBook.CannotRead) throw e
            throw ImportedBook.CannotRead(ImportedBook.SAVE)
        }
        return ImportedBook.summary(book.title, book.chapters.size, book.byPage)
    }

    private fun writeChapters(
        ctx: Context,
        tree: Uri,
        dirId: String,
        folder: String,
        slug: String,
        dirName: String,
        book: ImportedBook.Book,
        onProgress: (String) -> Unit,
    ) {
        val cr = ctx.contentResolver
        val store = DownloadStore(ctx)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
        val compress = Compression.enabled(ctx)
        val names = ArrayList<String>(book.chapters.size)
        book.chapters.forEachIndexed { i, ch ->
            onProgress("Saving ${i + 1} of ${book.chapters.size}…")
            val file = ImportedBook.chapterFileName(i, ch.title)
            val body = ch.text.ifBlank { ch.title.ifBlank { " " } }
            if (compress) {
                Zips.writeGzDoc(cr, parent, "$file.gz", body)
                    ?: throw ImportedBook.CannotRead(ImportedBook.SAVE)
            } else {
                writePlain(cr, parent, file, body)
            }
            names.add(file)
            if (ch.images.isNotEmpty()) {
                ChapterImages.saveImported(
                    ctx, folder, dirName, slug, file,
                    ch.images.map { it.bytes to it.alt },
                )
            }
        }
        store.setNovelInfo(
            folder, slug,
            author = book.author.ifBlank { null },
            description = book.description.ifBlank { null },
        )
        store.setDiskCount(folder, slug, names.size)
        store.updateNovelCheck(folder, slug, names.size, true)
        store.setChapterOrder(folder, slug, names)
    }

    private fun writePlain(cr: ContentResolver, parent: Uri, name: String, text: String) {
        val tmp = Zips.partName(name)
        val u = DocumentsContract.createDocument(cr, parent, "text/plain", tmp)
            ?: throw ImportedBook.CannotRead(ImportedBook.SAVE)
        try {
            cr.openOutputStream(u)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: throw ImportedBook.CannotRead(ImportedBook.SAVE)
            val done = DocumentsContract.renameDocument(cr, u, name)
                ?: throw ImportedBook.CannotRead(ImportedBook.SAVE)
            val got = Zips.docName(cr, done)
            if (got != null && Zips.isMinted(name, got)) {
                try { DocumentsContract.deleteDocument(cr, done) } catch (e: Exception) {}
                throw ImportedBook.CannotRead(ImportedBook.SAVE)
            }
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(cr, u) } catch (e2: Exception) {}
            throw e
        }
    }

    private fun deleteTree(cr: ContentResolver, tree: Uri, docId: String) {
        val kids = try { Saf.children(cr, tree, docId, includeSize = false) } catch (e: Exception) { emptyList() }
        for (k in kids) {
            if (k.isDir) deleteTree(cr, tree, k.docId)
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, k.docId)
            try { DocumentsContract.deleteDocument(cr, uri) } catch (e: Exception) {}
        }
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        try { DocumentsContract.deleteDocument(cr, uri) } catch (e: Exception) {}
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > ImportedBook.MAX_BYTES) throw ImportedBook.CannotRead(ImportedBook.TOO_BIG)
            out.write(buf, 0, n)
        }
        if (out.size() == 0) throw ImportedBook.CannotRead(ImportedBook.EMPTY)
        return out.toByteArray()
    }
}
