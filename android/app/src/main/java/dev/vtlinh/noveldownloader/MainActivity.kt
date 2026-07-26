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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                    val startCh = ReaderActivity.resumeChapter(this, slug)
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
        /* Only on a fresh start. getIntent() still returns the original
           ACTION_SEND after a recreation, so rotating once the shared novel
           had downloaded re-handled the share and re-fired the whole listing
           and rename pass. The reading-resume path three lines up already
           guards on this; the share path never did. */
        if (savedInstanceState == null) handleShare(intent)

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
        findViewById<TextView>(R.id.navAbout).setOnClickListener {
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
            startActivity(Intent(this, AboutActivity::class.java))
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
    private var latestVersionCode = 0L
    /* set while an install is in flight; a successful install kills the
       process, so if we ever come BACK to onResume with this still set the
       system's confirmation was dismissed/interrupted — recover instead of
       staying stuck on "Installing update…" */
    private var installInFlight = false

    override fun onResume() {
        super.onResume()
        if (installInFlight) {
            /* returned to the app after the install prompt was dismissed */
            installInFlight = false
            installStuckCheck?.let { installHandler.removeCallbacks(it) }
            updateFound = false
            val banner = findViewById<TextView>(R.id.updateBanner)
            banner.text = "Update ready — tap to install"
            banner.visibility = View.VISIBLE
            findViewById<TextView>(R.id.statusText).text = ""
        }
        checkForUpdate()
    }

    /* Check for a newer published version whenever the app returns to the
       foreground, at most once a minute. When one is found the APK is pulled
       down immediately, so it's already on disk by the time the user decides
       — but it is never installed for them: the sticky banner waits for a
       tap, since installing restarts the app. */
    private fun checkForUpdate() {
        if (updateFound) return
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck < 60_000) return
        lastUpdateCheck = now
        lifecycleScope.launch {
            val latest = Updater.latestVersion() ?: return@launch
            if (latest.first <= Updater.currentVersionCode(this@MainActivity)) return@launch
            updateFound = true
            latestVersionCode = latest.first
            val banner = findViewById<TextView>(R.id.updateBanner)
            banner.text = "Downloading update (v${latest.second})…"
            banner.visibility = View.VISIBLE
            /* fetch now so the tap only has to install; a tap arriving
               mid-download waits on the same lock and then installs */
            val apk = Updater.ensureApk(this@MainActivity, latest.first)
            banner.text = if (apk != null) {
                Updater.rememberPendingName(this@MainActivity, latest.second)
                "Update ready (v${latest.second}) — tap to install"
            } else {
                "Update available (v${latest.second}) — tap to retry"
            }
        }
    }

    private var installRetries = 0
    private var installStuckCheck: Runnable? = null
    private val installHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun installUpdate() {
        lifecycleScope.launch {
            val statusText = findViewById<TextView>(R.id.statusText)
            val banner = findViewById<TextView>(R.id.updateBanner)
            statusText.text = "Preparing update…"
            /* download once per version; reused for every retry, even across
               restarts, until a newer version supersedes it */
            var version = latestVersionCode
            if (version <= 0L) version = Updater.latestVersion()?.first ?: 0L
            val apk = if (version > 0L) Updater.ensureApk(this@MainActivity, version) else null
            if (apk == null) {
                updateFound = false
                statusText.text = "Update download failed — will retry later."
                banner.text = "Update ready — tap to install"
                banner.visibility = View.VISIBLE
                return@launch
            }
            installRetries = 0
            commitInstall(apk)
        }
    }

    /* Commit the already-downloaded APK. If the Installing… phase stalls — a
       silent install can leave a committed session that never resolves — abort
       the stuck session and re-commit the SAME file (no re-download), up to a
       couple of times, then fall back to the retry banner. */
    private fun commitInstall(apk: java.io.File) {
        val statusText = findViewById<TextView>(R.id.statusText)
        val banner = findViewById<TextView>(R.id.updateBanner)
        installStuckCheck?.let { installHandler.removeCallbacks(it) }
        statusText.text = "Installing update…"
        installInFlight = true
        lifecycleScope.launch {
            /* Committing streams the whole APK into the session and fsyncs it.
               On the UI thread that freezes the screen for the length of the
               copy — long enough to earn an ANR on slow storage, and the stuck
               check re-commits it up to twice more. The About screen and the
               notification receiver were both moved off-thread for this; the
               banner, which is the update path for anyone who never opens
               About, was left behind. */
            val err = withContext(Dispatchers.IO) {
                try {
                    Updater.abandonSessions(this@MainActivity)   // clear any stuck/previous session
                    Updater.install(this@MainActivity, apk)
                    null
                } catch (e: Exception) { e.message ?: "install failed" }
            }
            if (err != null) {
                installInFlight = false
                updateFound = false
                statusText.text = "Update failed — $err"
                banner.text = "Update ready — tap to install"
                banner.visibility = View.VISIBLE
                return@launch
            }
            armStuckCheck(apk)
        }
    }

    private fun armStuckCheck(apk: java.io.File) {
        val statusText = findViewById<TextView>(R.id.statusText)
        val banner = findViewById<TextView>(R.id.updateBanner)
        installStuckCheck = Runnable {
            /* a successful silent install kills the process (so this never
               runs); a shown confirmation pauses us (so we're not RESUMED).
               Still here, foreground, and installing → the install stalled. */
            if (!installInFlight) return@Runnable
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@Runnable
            if (installRetries < 2) {
                installRetries++
                commitInstall(apk)   // reuse the downloaded APK, no re-download
            } else {
                installInFlight = false
                statusText.text = "Update didn't install — tap to retry."
                banner.text = "Update ready — tap to install"
                banner.visibility = View.VISIBLE
            }
        }
        /* 4s: a healthy silent install kills the process well within this, and
           a shown confirmation pauses us (blocking the retry via the RESUMED
           check) — so still being here after 4s means the commit stalled */
        installHandler.postDelayed(installStuckCheck!!, 4_000)
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
        /* a running download no longer blocks a shared URL — the service
           queues it to download next */
        startDownload()
    }

    private fun startDownload() {
        val typed = findViewById<EditText>(R.id.urlInput).text.toString().trim()
        prefs.edit().putString("url", typed).apply()
        val site = Sites.forUrl(typed)
        if (site == null) {
            findViewById<TextView>(R.id.statusText).text =
                "Enter a novel URL from truyenfull.today, truyenfull.live, or novelfull.com"
            return
        }
        /* Reduce to the novel's own URL before anything else touches it. The
           site test only matches the host, so a chapter or listing URL —
           exactly what gets shared from a phone browser — passes straight
           through. The engine normalises internally, so the right novel still
           downloaded, but the key the service publishes came from the LAST
           path segment: "chuong-100" rather than the novel. Every guard that
           asks "is this novel busy?" then answered no — the Library offered
           Download again, and a Check status sweep would rename and dedupe
           the novel's files underneath the running download. The browser
           already normalises before starting; this path never did. */
        val url = try { site.normalize(typed).first } catch (e: Exception) { typed }
        /* the user threw this novel away before — confirm before re-downloading */
        val slugKey = NovelListActivity.slugKeyFromUrl(url)
        val garbage = prefs.getStringSet(NovelListActivity.GARBAGE_KEY, emptySet()) ?: emptySet()
        if (slugKey.isNotEmpty() && slugKey in garbage) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Marked as garbage")
                .setMessage(
                    "You previously marked this novel as garbage. " +
                        "Remove that status and download it again?",
                )
                .setPositiveButton("Re-download") { _, _ ->
                    prefs.edit()
                        .putStringSet(NovelListActivity.GARBAGE_KEY, garbage - slugKey)
                        .apply()
                    startDownload()   // re-enter, now past this gate
                }
                .setNegativeButton("Cancel", null)
                .show()
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

        /* translating an already-English novel is almost always a mistake —
           confirm before spending API calls */
        if (translate) {
            findViewById<TextView>(R.id.statusText).text = "Checking language…"
            lifecycleScope.launch {
                val english = DownloadEngine.sourceLooksEnglish(url)
                if (english == true) {
                    findViewById<TextView>(R.id.statusText).text = ""
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Already in English?")
                        .setMessage("This novel appears to be in English already. Translate it to English anyway?")
                        .setPositiveButton("Translate anyway") { _, _ ->
                            beginDownload(url, tree, translate = true, force = true, apiKey = apiKey)
                        }
                        .setNegativeButton("Download without translating") { _, _ ->
                            beginDownload(url, tree, translate = false, force = false, apiKey = apiKey)
                        }
                        .show()
                } else {
                    findViewById<TextView>(R.id.statusText).text = ""
                    beginDownload(url, tree, translate = true, force = false, apiKey = apiKey)
                }
            }
            return
        }
        beginDownload(url, tree, translate = false, force = false, apiKey = apiKey)
    }

    private fun beginDownload(url: String, tree: String, translate: Boolean, force: Boolean, apiKey: String) {
        startForegroundService(
            Intent(this, DownloadService::class.java)
                .putExtra("url", url).putExtra("tree", tree)
                .putExtra("translate", translate)
                .putExtra("forceTranslate", force)
                .putExtra("apiKey", apiKey),
        )
    }

}
