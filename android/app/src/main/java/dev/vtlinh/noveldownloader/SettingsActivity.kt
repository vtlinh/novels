package dev.vtlinh.noveldownloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/* App settings: the download folder and the Anthropic API key (moved off
   the home screen). The key is saved on focus loss and when leaving. */
class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                prefs.edit().putString("tree", uri.toString()).apply()
                updateFolderLabel()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.folderBtn).setOnClickListener { pickFolder.launch(null) }

        val key = findViewById<EditText>(R.id.apiKeyInput)
        key.setText(prefs.getString("apiKey", ""))
        key.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveKey()
        }
        updateFolderLabel()

        findViewById<android.widget.Button>(R.id.compressBtn).setOnClickListener { processNovels(true) }
        findViewById<android.widget.Button>(R.id.uncompressBtn).setOnClickListener { processNovels(false) }
    }

    /* ---- chapter compression ----
       Compress: each novel's loose "Chapter N.txt" files (plus translated/)
       are folded into one chapters.zip and the originals deleted.
       Uncompress: entries are extracted back to files and the zip removed.
       The reader handles both formats, including mixed. */

    private fun setZipStatus(msg: String) {
        runOnUiThread { findViewById<TextView>(R.id.zipStatus).text = msg }
    }

    private fun processNovels(compress: Boolean) {
        val folder = prefs.getString("tree", null)
        if (folder == null) {
            setZipStatus("Pick a download folder first.")
            return
        }
        val btns = listOf<android.widget.Button>(
            findViewById(R.id.compressBtn), findViewById(R.id.uncompressBtn),
        )
        btns.forEach { it.isEnabled = false }
        lifecycleScope.launch(Dispatchers.IO) {
            val treeUri = Uri.parse(folder)
            val cr = contentResolver
            var changed = 0
            var failed = 0
            try {
                val dirs = Saf.children(cr, treeUri, Saf.rootId(treeUri)).filter { it.isDir }
                for ((i, d) in dirs.withIndex()) {
                    setZipStatus(
                        (if (compress) "Compressing" else "Uncompressing") +
                            " ${i + 1}/${dirs.size}: ${d.name}",
                    )
                    try {
                        if (if (compress) compressDir(cr, treeUri, d) else uncompressDir(cr, treeUri, d)) changed++
                    } catch (e: Exception) { failed++ }
                }
                setZipStatus(
                    "Done — $changed novel(s) ${if (compress) "compressed" else "uncompressed"}" +
                        (if (failed > 0) ", $failed failed" else "") + ".",
                )
            } catch (e: Exception) {
                setZipStatus("Error: ${e.message}")
            }
            runOnUiThread { btns.forEach { it.isEnabled = true } }
        }
    }

    private fun docUri(treeUri: Uri, docId: String) =
        android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun compressDir(cr: android.content.ContentResolver, treeUri: Uri, d: Saf.Entry): Boolean {
        val re = ChapterListActivity.CHAPTER_RE
        val kids = Saf.children(cr, treeUri, d.docId)
        val loose = kids.filter { !it.isDir && re.matches(it.name) }
        val tdir = kids.firstOrNull { it.isDir && it.name == "translated" }
        val tloose = tdir?.let { t ->
            Saf.children(cr, treeUri, t.docId).filter { !it.isDir && re.matches(it.name) }
        } ?: emptyList()
        if (loose.isEmpty() && tloose.isEmpty()) return false
        val oldZip = kids.firstOrNull { !it.isDir && Zips.isZipName(it.name) }
        val oldCached = oldZip?.let { Zips.cached(this, cr, treeUri, it.docId, d.name) }

        /* write the new zip beside the old one, then swap */
        val newUri = android.provider.DocumentsContract.createDocument(
            cr, docUri(treeUri, d.docId), "application/zip", "chapters-new.zip",
        ) ?: return false
        var count = 0
        val written = HashSet<String>()
        cr.openOutputStream(newUri)?.use { os ->
            java.util.zip.ZipOutputStream(os).use { z ->
                fun put(entry: String, bytes: ByteArray) {
                    if (written.add(entry)) {
                        z.putNextEntry(java.util.zip.ZipEntry(entry))
                        z.write(bytes)
                        z.closeEntry()
                        count++
                    }
                }
                for (f in loose) {
                    Saf.readText(cr, treeUri, f.docId)?.let { put(f.name, it.toByteArray()) }
                }
                for (f in tloose) {
                    Saf.readText(cr, treeUri, f.docId)?.let { put("translated/" + f.name, it.toByteArray()) }
                }
                /* carry over anything already zipped that wasn't re-added */
                oldCached?.let { oc ->
                    for (e in Zips.entries(oc)) {
                        if (e !in written) Zips.read(oc, e)?.let { put(e, it.toByteArray()) }
                    }
                }
            }
        } ?: return false
        if (count == 0) {
            try { android.provider.DocumentsContract.deleteDocument(cr, newUri) } catch (e: Exception) {}
            return false
        }
        /* the new zip is safely written: remove originals and rename it */
        oldZip?.let {
            try { android.provider.DocumentsContract.deleteDocument(cr, docUri(treeUri, it.docId)) } catch (e: Exception) {}
        }
        for (f in loose + tloose) {
            try { android.provider.DocumentsContract.deleteDocument(cr, docUri(treeUri, f.docId)) } catch (e: Exception) {}
        }
        try {
            android.provider.DocumentsContract.renameDocument(cr, newUri, Zips.NAME)
        } catch (e: Exception) { /* "chapters-new.zip" is also recognized */ }
        return true
    }

    private fun uncompressDir(cr: android.content.ContentResolver, treeUri: Uri, d: Saf.Entry): Boolean {
        val kids = Saf.children(cr, treeUri, d.docId)
        val zipEnt = kids.firstOrNull { !it.isDir && Zips.isZipName(it.name) } ?: return false
        val zip = Zips.cached(this, cr, treeUri, zipEnt.docId, d.name) ?: return false
        val looseNames = kids.filter { !it.isDir }.map { it.name }.toHashSet()
        var tDocId = kids.firstOrNull { it.isDir && it.name == "translated" }?.docId
        val tNames = tDocId?.let { t ->
            Saf.children(cr, treeUri, t).map { it.name }.toHashSet()
        } ?: hashSetOf()
        for (e in Zips.entries(zip)) {
            val content = Zips.read(zip, e) ?: continue
            if (e.startsWith("translated/")) {
                val n = e.removePrefix("translated/")
                if (n in tNames) continue
                if (tDocId == null) {
                    tDocId = android.provider.DocumentsContract.createDocument(
                        cr, docUri(treeUri, d.docId),
                        android.provider.DocumentsContract.Document.MIME_TYPE_DIR, "translated",
                    )?.let { android.provider.DocumentsContract.getDocumentId(it) }
                }
                val t = tDocId ?: continue
                android.provider.DocumentsContract.createDocument(cr, docUri(treeUri, t), "text/plain", n)
                    ?.let { u -> cr.openOutputStream(u)?.use { it.write(content.toByteArray()) } }
            } else {
                if (e in looseNames) continue
                android.provider.DocumentsContract.createDocument(cr, docUri(treeUri, d.docId), "text/plain", e)
                    ?.let { u -> cr.openOutputStream(u)?.use { it.write(content.toByteArray()) } }
            }
        }
        try {
            android.provider.DocumentsContract.deleteDocument(cr, docUri(treeUri, zipEnt.docId))
        } catch (e: Exception) { return false }
        zip.delete()
        return true
    }

    override fun onPause() {
        saveKey()
        super.onPause()
    }

    private fun saveKey() {
        val key = findViewById<EditText>(R.id.apiKeyInput).text.toString().trim()
        prefs.edit().putString("apiKey", key).apply()
    }

    private fun updateFolderLabel() {
        val tree = prefs.getString("tree", null)
        findViewById<TextView>(R.id.folderLabel).text =
            if (tree == null) "No folder selected"
            else folderDisplayName(tree)
    }

    /* "primary:Documents/Novels" -> "Novels" */
    private fun folderDisplayName(tree: String): String {
        val seg = Uri.parse(tree).lastPathSegment ?: return tree
        return seg.substringAfterLast(':').substringAfterLast('/').ifEmpty { seg }
    }
}
