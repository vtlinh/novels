package dev.vtlinh.noveldownloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/* App settings: the Storage card (download folder + "Compress my novels"),
   the Anthropic API key, library auto status-check interval, and reading
   options. Toggling compression starts a background pass that converts every
   novel to match; new downloads follow the same flag. The key is saved on
   focus loss and when leaving. Descriptions live behind each setting's (?). */
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

    /* Android does not deliver onFocusChange(false) when the screen is torn
       down, so the focus-loss save alone lost the key whenever it was typed
       and Back was pressed — and a missing key makes translation silently do
       nothing, with no error anywhere to explain it. */
    override fun onPause() {
        super.onPause()
        try { saveKey() } catch (e: Exception) {}
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
                Toast.makeText(this, "Pick a download folder first.", Toast.LENGTH_SHORT).show()
                prefs.edit().putBoolean("compressJobActive", false).apply()
            } else {
                try { CompressService.start(this) } catch (e: Exception) {}
            }
        }

        /* Automatic library status check: 0 = never, 1..7 = minimum days
           between foreground sweeps (StatusAutoCheck, same lifecycle as the
           self-updater). */
        val daysLabel = findViewById<TextView>(R.id.statusCheckDaysLabel)
        val daysSeek = findViewById<SeekBar>(R.id.statusCheckDaysSeek)
        fun daysCaption(d: Int) = when (d) {
            0 -> "Never"
            1 -> "1 day"
            else -> "$d days"
        }
        val initialDays = StatusAutoCheck.days(this)
        daysSeek.max = 7
        daysSeek.progress = initialDays
        daysLabel.text = daysCaption(initialDays)
        daysSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                daysLabel.text = daysCaption(progress)
                if (fromUser) StatusAutoCheck.setDays(this@SettingsActivity, progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        /* keep the reader's screen on while TTS reads aloud (off by default) */
        val keepAwakeCheck = findViewById<CheckBox>(R.id.keepAwakeCheck)
        keepAwakeCheck.isChecked = prefs.getBoolean("keepAwake", false)
        keepAwakeCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("keepAwake", checked).apply()
        }

        bindHelp(
            R.id.compressHelp, "Compress my novels",
            "Stores chapters as per-chapter gzip (~70% smaller). New downloads follow this setting; existing novels are converted in the background. Reading works either way.",
        )
        bindHelp(
            R.id.apiKeyHelp, "Anthropic API key",
            "Used for chapter translation, which costs money — each translation is billed to your Anthropic account balance. Stored only on this device.",
        )
        bindHelp(
            R.id.statusCheckHelp, "Automatic status check",
            "When you open the app, check novels for new chapters if at least this many days have passed since the last automatic check. 0 = never. Same check as the Library's Check button.",
        )
        bindHelp(
            R.id.keepAwakeHelp, "Keep screen awake",
            "Stops the screen from dimming off on the reader while text-to-speech is playing.",
        )
    }

    private fun bindHelp(id: Int, title: String, message: String) {
        findViewById<TextView>(id).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
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
