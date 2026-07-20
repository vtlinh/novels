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
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        /* app opened from the launcher while a reading session was live →
           resume straight into the reader at the saved spot (the marker is
           cleared when the user backs out of reading mode) */
        if (savedInstanceState == null && intent?.action == Intent.ACTION_MAIN) {
            prefs.getString("lastReading", null)?.let { saved ->
                try {
                    val o = org.json.JSONObject(saved)
                    val slug = o.getString("slug")
                    val startCh = prefs.getString("lastCh:$slug", null)
                    if (startCh != null) {
                        startActivity(
                            Intent(this, ReaderActivity::class.java)
                                .putExtra("dir", o.getString("dir"))
                                .putExtra("title", o.getString("title"))
                                .putExtra("slug", slug)
                                .putExtra("start", startCh),
                        )
                    }
                } catch (e: Exception) {}
            }
        }

        findViewById<TextView>(R.id.versionText).text = "v" + try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }

        val urlInput = findViewById<EditText>(R.id.urlInput)
        urlInput.setText(prefs.getString("url", ""))
        handleShare(intent)

        // translation toggle (the API key itself lives in Settings)
        val translateCheck = findViewById<android.widget.CheckBox>(R.id.translateCheck)
        translateCheck.isChecked = prefs.getBoolean("translate", false)
        translateCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("translate", checked).apply()
        }

        findViewById<Button>(R.id.downloadBtn).setOnClickListener { startDownload() }

        /* navigation drawer: Home (this screen) and Novels (the list) */
        val drawer = findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawerLayout)
        findViewById<TextView>(R.id.menuBtn).setOnClickListener {
            drawer.openDrawer(androidx.core.view.GravityCompat.START)
        }
        findViewById<TextView>(R.id.navHome).setOnClickListener {
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
        }
        findViewById<TextView>(R.id.navBrowser).setOnClickListener {
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
            startActivity(Intent(this, BrowserActivity::class.java))
        }
        findViewById<TextView>(R.id.navNovels).setOnClickListener {
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
            startActivity(Intent(this, NovelListActivity::class.java))
        }
        findViewById<TextView>(R.id.navSettings).setOnClickListener {
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            (it as Button).text = "Stopping…"
            startService(Intent(this, DownloadService::class.java).setAction(DownloadService.ACTION_STOP))
        }

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
       foreground, at most once a minute. When one is found it installs
       automatically — silently where Android allows — unless a download is
       running (the install kills the process), in which case the sticky
       banner offers it for later. */
    private fun checkForUpdate() {
        if (updateFound) return
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck < 60_000) return
        lastUpdateCheck = now
        lifecycleScope.launch {
            val latest = Updater.latestVersion()
            if (latest != null && latest.first > Updater.currentVersionCode(this@MainActivity)) {
                updateFound = true
                if (DownloadService.runningFlow.value) {
                    findViewById<TextView>(R.id.updateBanner).visibility = View.VISIBLE
                } else {
                    installUpdate()
                }
            }
        }
    }

    private fun installUpdate() {
        lifecycleScope.launch {
            val statusText = findViewById<TextView>(R.id.statusText)
            statusText.text = "Downloading update…"
            val apk = Updater.downloadApk(this@MainActivity)
            if (apk != null) {
                statusText.text = "Installing update…"
                try {
                    Updater.install(this@MainActivity, apk)
                } catch (e: Exception) {
                    statusText.text = "Update failed — ${e.message}"
                    findViewById<TextView>(R.id.updateBanner).visibility = View.VISIBLE
                }
            } else {
                statusText.text = "Update download failed — will retry later."
                findViewById<TextView>(R.id.updateBanner).visibility = View.VISIBLE
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
        val apiKey = (prefs.getString("apiKey", "") ?: "").trim()
        if (translate && apiKey.isEmpty()) {
            findViewById<TextView>(R.id.statusText).text =
                "Set your Anthropic API key in Settings to translate."
            return
        }
        prefs.edit().putBoolean("translate", translate).apply()
        startForegroundService(
            Intent(this, DownloadService::class.java)
                .putExtra("url", url).putExtra("tree", tree)
                .putExtra("translate", translate).putExtra("apiKey", apiKey),
        )
    }

}
