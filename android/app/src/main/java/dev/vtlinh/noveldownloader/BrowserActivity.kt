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
    private var currentUrl: String = "https://truyenfull.today/"

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
                    downloadBtn.isEnabled = Sites.forUrl(url) != null
                    findViewById<android.view.View>(R.id.recentPanel).visibility = android.view.View.GONE
                    findViewById<android.view.View>(R.id.browserHeader).visibility = android.view.View.VISIBLE
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
        val history = domainHistory().entries.sortedByDescending { it.value }.map { it.key }
        if (history.isEmpty()) {
            web.loadUrl(start)
        } else {
            val panel = findViewById<android.view.View>(R.id.recentPanel)
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
            findViewById<android.view.View>(R.id.recentBack).setOnClickListener { finish() }
            /* the start screen has its own header; the browser one comes back
               once a page loads */
            findViewById<android.view.View>(R.id.browserHeader).visibility = android.view.View.GONE
            panel.visibility = android.view.View.VISIBLE
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
