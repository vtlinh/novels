package dev.vtlinh.noveldownloader

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

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
        setContentView(R.layout.activity_main)

        val urlInput = findViewById<EditText>(R.id.urlInput)
        urlInput.setText(prefs.getString("url", ""))
        handleShare(intent)

        findViewById<Button>(R.id.folderBtn).setOnClickListener { pickFolder.launch(null) }

        findViewById<Button>(R.id.downloadBtn).setOnClickListener {
            val url = urlInput.text.toString().trim()
            prefs.edit().putString("url", url).apply()
            if (Sites.forUrl(url) == null) {
                findViewById<TextView>(R.id.statusText).text =
                    "Enter a novel URL from truyenfull.today, truyenfull.live, or novelfull.com"
                return@setOnClickListener
            }
            val tree = prefs.getString("tree", null)
            if (tree == null) {
                pickFolder.launch(null)
                return@setOnClickListener
            }
            val i = Intent(this, DownloadService::class.java)
                .putExtra("url", url)
                .putExtra("tree", tree)
            startForegroundService(i)
        }

        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            startService(Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_STOP))
        }

        updateFolderLabel()

        val statusText = findViewById<TextView>(R.id.statusText)
        val logText = findViewById<TextView>(R.id.logText)
        val scroll = findViewById<ScrollView>(R.id.logScroll)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val stopBtn = findViewById<Button>(R.id.stopBtn)
        val dlBtn = findViewById<Button>(R.id.downloadBtn)

        lifecycleScope.launch {
            DownloadService.statusFlow.collectLatest { statusText.text = it }
        }
        lifecycleScope.launch {
            DownloadService.logFlow.collectLatest {
                logText.text = it.joinToString("\n")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        lifecycleScope.launch {
            DownloadService.runningFlow.collectLatest { r ->
                stopBtn.visibility = if (r) View.VISIBLE else View.GONE
                progress.visibility = if (r) View.VISIBLE else View.GONE
                dlBtn.isEnabled = !r
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        checkForUpdate()
    }

    /* on every launch: compare this build's versionCode against the latest
       published release; offer to download + install when newer */
    private fun checkForUpdate() {
        lifecycleScope.launch {
            val latest = Updater.latestVersion() ?: return@launch
            if (latest.first <= Updater.currentVersionCode(this@MainActivity)) return@launch
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Update available")
                .setMessage("Version ${latest.second} is available. Download and install?")
                .setPositiveButton("Update") { _, _ ->
                    lifecycleScope.launch {
                        val statusText = findViewById<TextView>(R.id.statusText)
                        statusText.text = "Downloading update…"
                        val apk = Updater.downloadApk(this@MainActivity)
                        if (apk != null) {
                            statusText.text = "Opening installer…"
                            Updater.install(this@MainActivity, apk)
                        } else {
                            statusText.text = "Update download failed — try again later."
                        }
                    }
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val m = Regex("https?://\\S+").find(text) ?: return
        findViewById<EditText>(R.id.urlInput).setText(m.value)
        prefs.edit().putString("url", m.value).apply()
    }

    private fun updateFolderLabel() {
        val tree = prefs.getString("tree", null)
        findViewById<TextView>(R.id.folderLabel).text =
            if (tree == null) "No folder selected"
            else "Folder: " + (Uri.parse(tree).lastPathSegment ?: tree)
    }
}
