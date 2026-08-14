package dev.vtlinh.noveldownloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* App settings: the Storage card (download folder, library size, and
   "Compress my novels"), the Anthropic API key, library auto status-check
   interval, and reading options. Toggling compression starts a background
   pass that converts every novel to match; new downloads follow the same
   flag. The key is saved on focus loss and when leaving. Descriptions live
   behind each setting's help icon. */
class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    /* bumped on every scan so a slower walk cannot overwrite a newer folder */
    private var storageGen = 0

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                prefs.edit().putString("tree", uri.toString()).apply()
                updateFolderLabel()
                refreshStorage()
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
        bindHelp(
            R.id.storageUsedHelp, "Used",
            "Space taken by downloaded chapters in the folder above, including compressed copies and translations. Remembered per novel until a download, translation, compress, or a file change.",
        )

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
        findViewById<View>(id).setOnClickListener {
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

    override fun onResume() {
        super.onResume()
        refreshStorage()
    }

    private fun updateFolderLabel() {
        val tree = prefs.getString("tree", null)
        findViewById<TextView>(R.id.folderLabel).text =
            if (tree == null) "No folder selected"
            else folderDisplayName(tree)
    }

    /* Off the main thread. Per-novel sizes live in the DB; a matching
       folder stamp skips the SAF walk. Forgotten sizes, or a stamp that
       no longer matches (a file arrived or left, including from outside
       the app), are remeasured. */
    private fun refreshStorage() {
        val gen = ++storageGen
        val label = findViewById<TextView>(R.id.storageUsedLabel)
        val tree = prefs.getString("tree", null)
        if (tree == null) {
            label.text = "—"
            return
        }
        val preview = try {
            val known = DownloadStore(this).diskCaches(tree).map { it.bytes }.filter { it >= 0L }
            if (known.isEmpty()) null else Storage.format(known.sum())
        } catch (e: Exception) { null }
        if (label.text.isNullOrBlank() || label.text == "—" || label.text == "…") {
            label.text = preview ?: "…"
        }
        lifecycleScope.launch {
            val text = try {
                withContext(Dispatchers.IO) {
                    measure(tree, gen) { partial ->
                        val shown = Storage.label(partial)
                        runOnUiThread {
                            if (gen == storageGen && !isDestroyed) label.text = shown
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                return@launch
            } catch (e: Exception) {
                preview ?: "—"
            }
            if (text == null || gen != storageGen) return@launch
            label.text = text
        }
    }

    private fun measure(tree: String, gen: Int, onPartial: (Storage.Total) -> Unit): String? {
        val treeUri = Uri.parse(tree)
        val store = DownloadStore(this)
        val caches = try { store.diskCaches(tree) } catch (e: Exception) { emptyList() }
        val root = Saf.children(
            contentResolver, treeUri, Saf.rootId(treeUri), includeSize = false,
        )
        val byName = HashMap<String, Saf.Entry>()
        for (e in root) if (e.isDir) byName[e.name] = e
        fun kids(docId: String) = try {
            Saf.children(contentResolver, treeUri, docId).map {
                Folder.Item(it.name, it.docId, it.isDir, it.size)
            }
        } catch (e: Exception) {
            emptyList()
        }
        var acc = Storage.Total(0L, 0, 0)
        for (row in caches) {
            if (gen != storageGen || isDestroyed) return null
            val dir = byName[row.dirName] ?: continue
            val dirMod = Saf.modified(contentResolver, treeUri, dir.docId)
            val trId = row.stamp?.trId ?: ""
            val trMod = if (trId.isEmpty()) 0L else Saf.modified(contentResolver, treeUri, trId)
            val now = Folder.Stamp(dir.docId, dirMod, trId, trMod)
            val hit = Storage.remembered(row.bytes, row.stamp, now)
            if (hit != null) {
                acc += Storage.Total(hit, if (hit == 0L) 0 else 1, 0)
                onPartial(acc)
                continue
            }
            /* mtime BEFORE the listing, same race Folder.Stamp documents */
            val listing = kids(dir.docId)
            val translated = listing.firstOrNull { it.isDir && it.name == "translated" }
            val trNow = translated?.let { Saf.modified(contentResolver, treeUri, it.ref) } ?: 0L
            val stamp = Folder.Stamp(dir.docId, dirMod, translated?.ref ?: "", trNow)
            var got = Storage.of(listing)
            if (translated != null) got += Storage.of(kids(translated.ref))
            acc += got
            if (got.files == 0 || got.files != got.unknown) {
                try { store.setDiskBytes(tree, row.slug, got.bytes, stamp) } catch (e: Exception) {}
            }
            onPartial(acc)
        }
        return Storage.label(acc)
    }

    /* "primary:Documents/Novels" -> "Novels" */
    private fun folderDisplayName(tree: String): String {
        val seg = Uri.parse(tree).lastPathSegment ?: return tree
        return seg.substringAfterLast(':').substringAfterLast('/').ifEmpty { seg }
    }
}
