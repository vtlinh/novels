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

        /* new downloads compress themselves when this is on (default) */
        val zipCheck = findViewById<android.widget.CheckBox>(R.id.zipDownloadsCheck)
        zipCheck.isChecked = prefs.getBoolean("zipDownloads", true)
        zipCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("zipDownloads", checked).apply()
        }
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
                        val ok = if (compress) {
                            Zips.compressDir(this@SettingsActivity, cr, treeUri, d)
                        } else {
                            Zips.uncompressDir(this@SettingsActivity, cr, treeUri, d)
                        }
                        if (ok) changed++
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
