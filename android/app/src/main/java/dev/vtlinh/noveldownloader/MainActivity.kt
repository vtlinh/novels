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

        findViewById<TextView>(R.id.versionText).text = "v" + try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }

        val urlInput = findViewById<EditText>(R.id.urlInput)
        urlInput.setText(prefs.getString("url", ""))
        handleShare(intent)

        // translation toggle: reveal the key field, remember both
        val translateCheck = findViewById<android.widget.CheckBox>(R.id.translateCheck)
        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        translateCheck.isChecked = prefs.getBoolean("translate", false)
        apiKeyInput.setText(prefs.getString("apiKey", ""))
        apiKeyInput.visibility = if (translateCheck.isChecked) View.VISIBLE else View.GONE
        translateCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("translate", checked).apply()
            apiKeyInput.visibility = if (checked) View.VISIBLE else View.GONE
        }
        apiKeyInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) prefs.edit().putString("apiKey", apiKeyInput.text.toString().trim()).apply()
        }

        findViewById<TextView>(R.id.folderBtn).setOnClickListener { pickFolder.launch(null) }

        findViewById<Button>(R.id.downloadBtn).setOnClickListener { startDownload() }

        findViewById<Button>(R.id.browserBtn).setOnClickListener {
            startActivity(Intent(this, BrowserActivity::class.java))
        }

        /* navigation drawer: Home (this screen) and Novels (the list) */
        val drawer = findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawerLayout)
        findViewById<TextView>(R.id.menuBtn).setOnClickListener {
            drawer.openDrawer(androidx.core.view.GravityCompat.START)
        }
        findViewById<TextView>(R.id.navHome).setOnClickListener {
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
        }
        findViewById<TextView>(R.id.navNovels).setOnClickListener {
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
            startActivity(Intent(this, NovelListActivity::class.java))
        }

        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            (it as Button).text = "Stopping…"
            startService(Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_STOP))
        }

        updateFolderLabel()
        maybeAutoStartFromShare()

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
                if (r) stopBtn.text = "Stop"   // reset after a previous "Stopping…"
                progress.visibility = if (r) View.VISIBLE else View.GONE
                dlBtn.isEnabled = !r
            }
        }

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        findViewById<TextView>(R.id.updateBanner).setOnClickListener { installUpdate() }
    }

    private var updateFound = false
    private var lastUpdateCheck = 0L

    override fun onResume() {
        super.onResume()
        checkForUpdate()
    }

    /* Check for a newer published version whenever the app returns to the
       foreground, at most once a minute. Once one is found, checks stop and
       the sticky banner shows; the user upgrades by tapping it. */
    private fun checkForUpdate() {
        if (updateFound) return
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck < 60_000) return
        lastUpdateCheck = now
        lifecycleScope.launch {
            val latest = Updater.latestVersion()
            if (latest != null && latest.first > Updater.currentVersionCode(this@MainActivity)) {
                updateFound = true
                findViewById<TextView>(R.id.updateBanner).visibility = View.VISIBLE
            }
        }
    }

    private fun installUpdate() {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        maybeAutoStartFromShare()
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val m = Regex("https?://\\S+").find(text) ?: return
        findViewById<EditText>(R.id.urlInput).setText(m.value)
        prefs.edit().putString("url", m.value).apply()
        sharedThisLaunch = true
    }

    private var sharedThisLaunch = false

    /* a shared URL with a folder already set starts the download at once */
    private fun maybeAutoStartFromShare() {
        if (!sharedThisLaunch) return
        sharedThisLaunch = false
        val url = findViewById<EditText>(R.id.urlInput).text.toString().trim()
        if (Sites.forUrl(url) == null || prefs.getString("tree", null) == null) return
        if (DownloadService.runningFlow.value) return
        startDownload()
    }

    private fun startDownload() {
        val url = findViewById<EditText>(R.id.urlInput).text.toString().trim()
        prefs.edit().putString("url", url).apply()
        if (Sites.forUrl(url) == null) {
            findViewById<TextView>(R.id.statusText).text =
                "Enter a novel URL from truyenfull.today, truyenfull.live, or novelfull.com"
            return
        }
        val tree = prefs.getString("tree", null)
        if (tree == null) { pickFolder.launch(null); return }
        val translate = findViewById<android.widget.CheckBox>(R.id.translateCheck).isChecked
        val apiKey = findViewById<EditText>(R.id.apiKeyInput).text.toString().trim()
        if (translate && apiKey.isEmpty()) {
            findViewById<TextView>(R.id.statusText).text = "Enter your Anthropic API key to translate."
            return
        }
        prefs.edit().putBoolean("translate", translate).putString("apiKey", apiKey).apply()
        startForegroundService(
            Intent(this, DownloadService::class.java)
                .putExtra("url", url).putExtra("tree", tree)
                .putExtra("translate", translate).putExtra("apiKey", apiKey),
        )
    }

    private fun updateFolderLabel() {
        val tree = prefs.getString("tree", null)
        findViewById<TextView>(R.id.folderLabel).text =
            if (tree == null) "No folder selected"
            else "Saved folder: " + folderDisplayName(tree)
    }

    /* "primary:Documents/Novels" -> "Novels" */
    private fun folderDisplayName(tree: String): String {
        val seg = Uri.parse(tree).lastPathSegment ?: return tree
        return seg.substringAfterLast(':').substringAfterLast('/').ifEmpty { seg }
    }
}
