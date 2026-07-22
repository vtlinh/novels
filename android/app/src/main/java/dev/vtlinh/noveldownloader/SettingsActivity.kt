package dev.vtlinh.noveldownloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/* App settings: the Storage card (download folder + "Compress my novels"),
   the Anthropic API key, and reading options. Toggling compression starts a
   background pass that converts every novel to match; new downloads follow the
   same flag. The key is saved on focus loss and when leaving. */
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
        findViewById<TextView>(R.id.apiKeyLink).setOnClickListener {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys")),
                )
            } catch (e: Exception) {}
        }
        updateFolderLabel()

        /* single "Compress my novels" switch: on → compress every novel and
           new downloads, off → uncompress everything and download plain. The
           background service converts the library to match either way. */
        val compressCheck = findViewById<CheckBox>(R.id.compressCheck)
        compressCheck.isChecked = prefs.getBoolean("compressNovels", prefs.getBoolean("zipDownloads", true))
        compressCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit()
                .putBoolean("compressNovels", checked)
                .putBoolean("compressJobActive", true)
                .apply()
            if (prefs.getString("tree", null) == null) {
                findViewById<TextView>(R.id.zipStatus).text = "Pick a download folder first."
                prefs.edit().putBoolean("compressJobActive", false).apply()
            } else {
                try { CompressService.start(this) } catch (e: Exception) {}
            }
        }

        /* the compress/uncompress pass runs silently in the background — no
           live status; zipStatus keeps its descriptive hint */

        /* keep the reader's screen on while TTS reads aloud (off by default) */
        val keepAwakeCheck = findViewById<CheckBox>(R.id.keepAwakeCheck)
        keepAwakeCheck.isChecked = prefs.getBoolean("keepAwake", false)
        keepAwakeCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("keepAwake", checked).apply()
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
