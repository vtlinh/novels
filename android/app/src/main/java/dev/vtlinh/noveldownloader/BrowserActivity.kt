package dev.vtlinh.noveldownloader

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
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
        )
        private fun isAdHost(host: String) =
            AD_HOSTS.any { host == it || host.endsWith(".$it") || host.contains(it) }
    }

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private var currentUrl: String = "https://truyenfull.today/"

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
                    downloadBtn.isEnabled = Sites.forUrl(url) != null
                }
            }

            /* ad filtering: swallow every request to a known ad network */
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val host = request?.url?.host ?: return null
                return if (isAdHost(host)) {
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                } else {
                    null
                }
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
        web.loadUrl(start)

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

        downloadBtn.setOnClickListener {
            val site = Sites.forUrl(currentUrl) ?: return@setOnClickListener
            val novel = site.normalize(currentUrl).first
            prefs.edit().putString("url", novel).apply()
            // hand back to MainActivity, which auto-starts if a folder is set
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .setAction(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, novel),
            )
            finish()
        }

        findViewById<Button>(R.id.browseClose).setOnClickListener { finish() }
    }

    override fun onBackPressed() {
        val web = findViewById<WebView>(R.id.webview)
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
