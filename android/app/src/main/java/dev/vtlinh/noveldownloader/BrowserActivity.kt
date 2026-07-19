package dev.vtlinh.noveldownloader

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

/* In-app site browser. Native WebView loads the novel sites directly — no
   proxy or CORS to work around — and a sticky header keeps a Download button
   on top. Download normalizes whatever page is open (novel, chapter, or
   listing) to its novel URL, hands it back to MainActivity, and finishes. */
class BrowserActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private var currentUrl: String = "https://truyenfull.today/"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        val web = findViewById<WebView>(R.id.webview)
        val urlEdit = findViewById<EditText>(R.id.browseUrl)
        val downloadBtn = findViewById<Button>(R.id.browseDownload)

        val start = prefs.getString("url", "")?.takeIf { Sites.forUrl(it) != null }
            ?: "https://truyenfull.today/"

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (url != null) {
                    currentUrl = url
                    /* don't clobber the field while the user is typing in it */
                    if (!urlEdit.hasFocus()) urlEdit.setText(url)
                    downloadBtn.isEnabled = Sites.forUrl(url) != null
                }
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
