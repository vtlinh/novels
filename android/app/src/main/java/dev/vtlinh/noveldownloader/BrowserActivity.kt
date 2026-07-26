package dev.vtlinh.noveldownloader

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/* In-app site browser. Native WebView loads the novel sites directly — no
   proxy or CORS to work around — and a sticky header keeps a Download button
   on top. Download normalizes whatever page is open (novel, chapter, or
   listing) to its novel URL, hands it back to MainActivity, and finishes. */
class BrowserActivity : AppCompatActivity() {

    companion object {
        /* well-known ad/tracking networks; requests to them are answered with
           an empty body so the ad never loads */
        private val AD_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "googletagmanager.com", "googletagservices.com", "google-analytics.com",
            "adnxs.com", "adsterra", "hilltopads", "propellerads", "popads.net",
            "popcash.net", "adcash", "exoclick", "juicyads", "trafficjunky",
            "mgid.com", "taboola.com", "outbrain.com", "criteo", "zedo.com",
            "adform.net", "smartadserver", "openx.net", "rubiconproject",
            "pubmatic.com", "onclickads", "clickadu", "galaksion", "adskeeper",
            /* Vietnamese networks the novel sites actually use */
            "admicro", "adtima", "eclick", "ants.vn", "ambientplatform",
            "blueseed", "yomedia", "adsota", "novanet", "adsplay", "netlink.vn",
        )
        private fun isAdHost(host: String) =
            AD_HOSTS.any { host == it || host.endsWith(".$it") || host.contains(it) }

        /* legitimate third-party CDNs sites need; every OTHER third-party
           script or iframe is treated as an ad delivery vehicle */
        private val CDN_ALLOW = listOf(
            "googleapis.com", "gstatic.com", "cloudflare.com", "cloudflareinsights.com",
            "jsdelivr.net", "jquery.com", "unpkg.com", "bootstrapcdn.com",
            "fontawesome.com", "cdnjs.com",
        )
        private fun isAllowedCdn(host: String) =
            CDN_ALLOW.any { host == it || host.endsWith(".$it") }
    }

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val store by lazy { DownloadStore(this) }
    private var currentUrl: String = "https://truyenfull.today/"

    /* novel URL waiting on a download folder being picked */
    private var pendingDownload: String? = null

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            val pending = pendingDownload
            pendingDownload = null
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                prefs.edit().putString("tree", uri.toString()).apply()
                pending?.let { startDownload(it) }
            }
        }

    /* ---- recent domains (start screen) ---- */

    /* domain -> last access millis, kept to the 20 most recent */
    private fun domainHistory(): MutableMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        try {
            val o = org.json.JSONObject(prefs.getString("browserDomains", "{}") ?: "{}")
            for (k in o.keys()) out[k] = o.getLong(k)
        } catch (e: Exception) {}
        return out
    }

    private fun recordDomainVisit(host: String) {
        val map = domainHistory()
        map[host] = System.currentTimeMillis()
        val trimmed = map.entries.sortedByDescending { it.value }.take(20)
        val o = org.json.JSONObject()
        for (e in trimmed) o.put(e.key, e.value)
        prefs.edit().putString("browserDomains", o.toString()).apply()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        val web = findViewById<WebView>(R.id.webview)
        val urlEdit = findViewById<EditText>(R.id.browseUrl)
        val downloadBtn = findViewById<Button>(R.id.browseDownload)

        /* always open at the domain level: the front page of the site the
           saved URL belongs to, or the default site */
        val saved = prefs.getString("url", "") ?: ""
        val start = try {
            val u = java.net.URI(saved)
            if (Sites.forUrl(saved) != null && u.host != null) "${u.scheme}://${u.host}/"
            else "https://truyenfull.today/"
        } catch (e: Exception) { "https://truyenfull.today/" }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        /* force dark: the app theme is always dark, so darken pages too —
           algorithmic darkening on modern WebViews, legacy force-dark otherwise */
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, true)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(web.settings, WebSettingsCompat.FORCE_DARK_ON)
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (url != null) {
                    currentUrl = url
                    /* don't clobber the field while the user is typing in it */
                    if (!urlEdit.hasFocus()) urlEdit.setText(url)
                    findViewById<android.view.View>(R.id.recentPanel).visibility = android.view.View.GONE
                    syncDownloadButton(url)
                    syncTranslateBox(url)
                    syncLibraryBanner(url)
                    try {
                        java.net.URI(url).host?.let { if (it.isNotEmpty()) recordDomainVisit(it) }
                    } catch (e: Exception) {}
                }
            }

            /* Ad filtering: swallow requests to known ad networks, and — since
               ads arrive via scripts and iframes — any THIRD-party script or
               sub-frame that isn't a whitelisted CDN. */
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val host = request?.url?.host ?: return null
                val empty = { WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) }
                if (isAdHost(host)) return empty()
                val curHost = try { java.net.URI(currentUrl).host } catch (e: Exception) { null }
                val curBase = curHost?.split('.')?.takeLast(2)?.joinToString(".")
                val thirdParty = curBase != null && host != curBase && !host.endsWith(".$curBase")
                if (thirdParty && !isAllowedCdn(host)) {
                    val path = (request.url.path ?: "").lowercase()
                    val accept = request.requestHeaders?.get("Accept") ?: ""
                    if (path.endsWith(".js")) return empty()                       // foreign script
                    if (!request.isForMainFrame && accept.contains("text/html")) {
                        return empty()                                             // foreign iframe
                    }
                }
                return null
            }

            /* cosmetic sweep: interstitial overlays are fixed, huge, and
               high-z — remove them and unlock scrolling; repeated because ad
               scripts often inject after load */
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(
                    """
                    (function(){
                      function sweep(){
                        try{
                          var vw=window.innerWidth, vh=window.innerHeight;
                          var els=document.querySelectorAll('div,section,aside,ins,iframe');
                          for (var i=0;i<els.length;i++){
                            var e=els[i], s=getComputedStyle(e);
                            if ((s.position==='fixed'||s.position==='sticky') && (parseInt(s.zIndex)||0)>=1000){
                              var r=e.getBoundingClientRect();
                              if (r.width*r.height > vw*vh*0.35){
                                e.remove();
                                document.body.style.overflow='auto';
                                document.documentElement.style.overflow='auto';
                              }
                            }
                          }
                        }catch(err){}
                      }
                      sweep(); setTimeout(sweep,1500); setTimeout(sweep,4000); setTimeout(sweep,8000);
                    })();
                    """.trimIndent(),
                    null,
                )
            }

            /* popup-redirect filtering: a main-frame navigation to a different
               domain that was NOT triggered by a user tap (no gesture) is the
               classic JS ad redirect — block it. Taps navigate normally. */
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request == null || !request.isForMainFrame) return false
                val host = request.url.host ?: return false
                if (isAdHost(host)) return true
                if (request.isRedirect) return false   // server-side redirects are legitimate
                val curHost = try { java.net.URI(currentUrl).host } catch (e: Exception) { null }
                return host != curHost && !request.hasGesture()
            }
        }
        /* start screen: the last 20 domains we visited, newest first. Tapping
           one opens its front page. With no history yet, load the old default
           start page directly. */
        if (domainHistory().isEmpty()) web.loadUrl(start) else showRecentPanel()

        /* focusing the bar selects the whole URL, so typing replaces it
           outright the way a real address bar does */
        urlEdit.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) (v as EditText).selectAll()
        }

        /* typing a URL and hitting Go navigates the WebView */
        urlEdit.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                var u = v.text.toString().trim()
                if (u.isNotEmpty()) {
                    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
                    web.loadUrl(u)
                    v.clearFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(v.windowToken, 0)
                }
                true
            } else {
                false
            }
        }

        /* download runs from here — the user stays on the page they were
           reading and gets a snackbar instead of being thrown to the home
           screen; its action is the way over to the novels list */
        downloadBtn.setOnClickListener {
            val site = Sites.forUrl(currentUrl) ?: return@setOnClickListener
            val novel = site.normalize(currentUrl).first
            prefs.edit().putString("url", novel).apply()
            startDownload(novel)
        }

        /* translate-on-download, sharing the home screen's "translate" pref.
           Ticking it with no key saved asks for one there and then. */
        findViewById<CheckBox>(R.id.browseTranslate).setOnCheckedChangeListener(translateListener)

        /* follow the service so the button flips as this novel starts, gets
           queued behind another, or finishes — including the download we
           just started from here */
        lifecycleScope.launch {
            DownloadService.activeSlugFlow.collectLatest { refreshDownloadButton() }
        }
        lifecycleScope.launch {
            DownloadService.queuedSlugsFlow.collectLatest { refreshDownloadButton() }
        }

        /* header back exits straight to the domain list, and off the list
           leaves browser mode; the system back button is the one that walks
           page history */
        findViewById<android.widget.TextView>(R.id.browseBack).setOnClickListener {
            if (findViewById<android.view.View>(R.id.recentPanel).visibility == android.view.View.VISIBLE) {
                finish()
            } else {
                showRecentPanel()
            }
        }
    }

    /* show the recent-domains start screen, rebuilt fresh so just-visited
       domains appear. The address bar stays up — cleared back to its hint, so
       a URL can be typed here instead of only tapped from the list */
    private fun showRecentPanel() {
        val web = findViewById<WebView>(R.id.webview)
        val history = domainHistory().entries.sortedByDescending { it.value }.map { it.key }
        val list = findViewById<android.widget.ListView>(R.id.recentList)
        list.adapter = object : android.widget.ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, history,
        ) {
            override fun getView(
                position: Int,
                convertView: android.view.View?,
                parent: android.view.ViewGroup,
            ): android.view.View {
                val v = super.getView(position, convertView, parent) as android.widget.TextView
                v.setTextColor(getColor(R.color.fg))
                return v
            }
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            web.loadUrl("https://${history[pos]}/")
        }
        findViewById<EditText>(R.id.browseUrl).setText("")
        findViewById<Button>(R.id.browseDownload).isEnabled = false
        /* no page open, so nothing to translate or to already own */
        findViewById<View>(R.id.browseTranslate).visibility = View.GONE
        findViewById<View>(R.id.libraryBanner).visibility = View.GONE
        findViewById<android.view.View>(R.id.recentPanel).visibility = android.view.View.VISIBLE
    }

    /* ---- download button ---- */

    /* slug key of the novel the given page belongs to, "" when it isn't one */
    private fun slugKeyFor(url: String): String {
        val site = Sites.forUrl(url) ?: return ""
        return try {
            val (base, slug) = site.normalize(url)
            if (slug.isEmpty()) "" else NovelListActivity.slugKeyFromUrl(base)
        } catch (e: Exception) { "" }
    }

    /* re-sync button and banner for the page that's open; on the start
       screen there's no page, and showRecentPanel already parked both */
    private fun refreshDownloadButton() {
        if (findViewById<View>(R.id.recentPanel).visibility == View.VISIBLE) return
        syncDownloadButton(currentUrl)
        syncLibraryBanner(currentUrl)
    }

    /* the button goes dead while this novel is downloading or waiting its
       turn, so tapping it can't start a second job for the same novel */
    private fun syncDownloadButton(url: String) {
        val btn = findViewById<Button>(R.id.browseDownload)
        if (Sites.forUrl(url) == null) {
            btn.isEnabled = false
            btn.text = "↓"
            return
        }
        val busy = DownloadService.isBusy(slugKeyFor(url))
        btn.isEnabled = !busy
        btn.text = if (busy) "⋯" else "↓"
    }

    /* ---- translate toggle ---- */

    private val translateListener = CompoundButton.OnCheckedChangeListener { btn, checked ->
        if (checked && apiKey().isEmpty()) {
            /* can't translate without a key — ask for one rather than
               silently failing at download time */
            promptForApiKey(
                onSaved = { prefs.edit().putBoolean("translate", true).apply() },
                onCancel = { btn.isChecked = false },
            )
        } else {
            prefs.edit().putBoolean("translate", checked).apply()
        }
    }

    private fun apiKey() = (prefs.getString("apiKey", "") ?: "").trim()

    /* the box tracks the saved preference, except on English sites where
       there is nothing to translate — there it's off and untouchable */
    private fun syncTranslateBox(url: String) {
        val box = findViewById<CheckBox>(R.id.browseTranslate)
        /* nothing downloadable here, so nothing to offer translating */
        val site = Sites.forUrl(url)
        if (site == null) { box.visibility = View.GONE; return }
        val english = site.english
        box.visibility = View.VISIBLE
        /* detach while setting isChecked so syncing doesn't count as a tick */
        box.setOnCheckedChangeListener(null)
        box.isEnabled = !english
        box.isChecked = !english && prefs.getBoolean("translate", false)
        box.text = if (english) "Translate to English — already in English" else "Translate to English"
        box.alpha = if (english) 0.5f else 1f
        box.setOnCheckedChangeListener(translateListener)
    }

    /* key prompt, same prefs["apiKey"] the Settings screen writes */
    private fun promptForApiKey(onSaved: () -> Unit, onCancel: () -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_api_key, null)
        val input = view.findViewById<EditText>(R.id.dialogApiKey)
        input.setText(prefs.getString("apiKey", ""))
        view.findViewById<TextView>(R.id.dialogApiKeyLink).setOnClickListener {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys")),
                )
            } catch (e: Exception) {}
        }
        AlertDialog.Builder(this)
            .setTitle("Anthropic API key")
            .setMessage("Translating runs the chapters through Claude, which needs your own API key.")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val k = input.text.toString().trim()
                prefs.edit().putString("apiKey", k).apply()
                if (k.isEmpty()) onCancel() else onSaved()
            }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    /* ---- download ---- */

    /* mirrors the home screen's gates (folder, garbage mark, API key) but
       keeps the user on the page; the engine itself skips translating a
       source that's already English, so there's no language pre-check here */
    private fun startDownload(url: String) {
        /* the button is disabled while this novel is in flight; re-check
           anyway so a stale tap (or the folder-picker resume) can't queue it
           a second time */
        if (DownloadService.isBusy(NovelListActivity.slugKeyFromUrl(url))) {
            refreshDownloadButton()
            return
        }
        val tree = prefs.getString("tree", null)
        if (tree == null) {
            pendingDownload = url
            pickFolder.launch(null)
            return
        }
        val slugKey = NovelListActivity.slugKeyFromUrl(url)
        val garbage = prefs.getStringSet(NovelListActivity.GARBAGE_KEY, emptySet()) ?: emptySet()
        if (slugKey.isNotEmpty() && slugKey in garbage) {
            AlertDialog.Builder(this)
                .setTitle("Marked as garbage")
                .setMessage(
                    "You previously marked this novel as garbage. " +
                        "Remove that status and download it again?",
                )
                .setPositiveButton("Re-download") { _, _ ->
                    prefs.edit()
                        .putStringSet(NovelListActivity.GARBAGE_KEY, garbage - slugKey)
                        .apply()
                    startDownload(url)   // re-enter, now past this gate
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val box = findViewById<CheckBox>(R.id.browseTranslate)
        val translate = box.isEnabled && box.isChecked
        val key = apiKey()
        if (translate && key.isEmpty()) {
            promptForApiKey(onSaved = { startDownload(url) }, onCancel = {})
            return
        }
        startForegroundService(
            Intent(this, DownloadService::class.java)
                .putExtra("url", url).putExtra("tree", tree)
                .putExtra("translate", translate)
                .putExtra("forceTranslate", false)
                .putExtra("apiKey", key),
        )
        /* Say so in the banner straight away. The service publishes its own
           state a moment later and the flow collectors correct this if it
           landed differently; the button follows the same way, but disable it
           here so a fast second tap has nothing to hit. */
        showBusyBanner(active = !DownloadService.runningFlow.value)
        findViewById<Button>(R.id.browseDownload).let {
            it.isEnabled = false
            it.text = "⋯"
        }
    }

    /* ---- "already downloaded" banner ---- */

    /* Same banner, for a novel we're working on right now — it goes to the
       novels list, which is where the progress actually shows. */
    private fun showBusyBanner(active: Boolean) {
        val banner = findViewById<TextView>(R.id.libraryBanner)
        banner.text =
            if (active) "Downloading this novel — tap to open your novels"
            else "Queued for download — tap to open your novels"
        banner.visibility = View.VISIBLE
        banner.setOnClickListener {
            startActivity(Intent(this, NovelListActivity::class.java))
        }
    }

    /* show the green banner when this novel is already in the library, and
       point it at that novel's chapter list */
    private fun syncLibraryBanner(url: String) {
        val banner = findViewById<TextView>(R.id.libraryBanner)
        banner.visibility = View.GONE
        val site = Sites.forUrl(url) ?: return
        /* downloading right now takes precedence over "you already have it":
           it's the newer truth, and re-downloading for new chapters hits both */
        val busyKey = slugKeyFor(url)
        if (DownloadService.isBusy(busyKey)) {
            showBusyBanner(DownloadService.isActive(busyKey))
            return
        }
        val folder = prefs.getString("tree", null) ?: return
        val (base, slug) = try { site.normalize(url) } catch (e: Exception) { return }
        if (slug.isEmpty()) return                       // a listing page, not a novel
        val slugKey = NovelListActivity.slugKeyFromUrl(base)
        if (slugKey.isEmpty()) return
        /* garbage-marked novels aren't in the list, so don't advertise them */
        val garbage = prefs.getStringSet(NovelListActivity.GARBAGE_KEY, emptySet()) ?: emptySet()
        if (slugKey in garbage) return

        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                try {
                    val rec = store.novels(folder).firstOrNull {
                        it.slug.lowercase().filter { c -> c.isLetterOrDigit() } == slugKey
                    } ?: return@withContext null
                    val dir = store.getTitle(folder, rec.slug)
                        ?: Extractor.sanitize(rec.title.ifEmpty { rec.slug })
                    Pair(rec, dir)
                } catch (e: Exception) { null }
            } ?: return@launch
            /* the page may have moved on while the lookup ran */
            if (currentUrl != url) return@launch
            val (rec, dir) = found
            banner.visibility = View.VISIBLE
            banner.setOnClickListener {
                startActivity(
                    Intent(this@BrowserActivity, ChapterListActivity::class.java)
                        .putExtra("dir", dir)
                        .putExtra("title", rec.title.ifEmpty { rec.slug })
                        .putExtra("slug", rec.slug),
                )
            }
        }
    }

    override fun onBackPressed() {
        /* on the domain list the system back exits browser mode */
        if (findViewById<android.view.View>(R.id.recentPanel).visibility == android.view.View.VISIBLE) {
            super.onBackPressed()
            return
        }
        val web = findViewById<WebView>(R.id.webview)
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
