package dev.vtlinh.noveldownloader

import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* Reading mode, screen 3: the reader. Starts at the tapped chapter; once the
   reader scrolls past HALF of what's loaded, the next chapter is fetched and
   appended at the bottom for a seamless read.

   The EN/VI button (and the ⚙ menu) switches between the English translation
   and the Vietnamese source — the view reloads from the chapter currently
   being read. The ⚙ menu also grows/shrinks the font; both settings persist. */
class ReaderActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val store by lazy { DownloadStore(this) }

    private var chapters: ChapterListActivity.Companion.Chapters? = null
    private var treeUri: Uri? = null
    private var nextIdx = 0
    @Volatile private var loading = false
    private var english = true
    private var fontSp = 16f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        val dirName = intent.getStringExtra("dir") ?: return finish()
        val novelTitle = intent.getStringExtra("title") ?: dirName
        val start = intent.getStringExtra("start") ?: return finish()
        val folder = prefs.getString("tree", null) ?: return finish()
        treeUri = Uri.parse(folder)

        english = prefs.getString("readerLang", "en") == "en"
        fontSp = prefs.getFloat("readerFontSize", 16f)

        val titleBar = findViewById<TextView>(R.id.readerTitle)
        val text = findViewById<TextView>(R.id.readerText)
        val scroll = findViewById<ScrollView>(R.id.readerScroll)
        titleBar.text = novelTitle
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
        text.text = "Loading…"
        updateLangBtn()

        /* mark as recently read */
        intent.getStringExtra("slug")?.let { slug ->
            lifecycleScope.launch(Dispatchers.IO) {
                try { store.setLastRead(folder, slug, System.currentTimeMillis()) } catch (e: Exception) {}
            }
        }

        lifecycleScope.launch {
            chapters = withContext(Dispatchers.IO) {
                val order = intent.getStringExtra("slug")?.let {
                    try { store.getChapterOrder(folder, it) } catch (e: Exception) { null }
                } ?: emptyMap()
                ChapterListActivity.chapterNames(contentResolver, treeUri!!, dirName, order)
            }
            nextIdx = (chapters?.ordered?.indexOf(start) ?: 0).coerceAtLeast(0)
            text.text = ""
            appendNext()
        }

        /* past half of the loaded content -> load the next chapter */
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val content = text.height
            if (content > 0 && (scrollY + scroll.height) * 2 > content) appendNext()
        }

        findViewById<TextView>(R.id.langBtn).setOnClickListener { toggleLanguage() }
        findViewById<TextView>(R.id.settingsBtn).setOnClickListener { v ->
            val pm = PopupMenu(this, v)
            pm.menu.add(0, 1, 0, if (english) "Switch to Tiếng Việt" else "Switch to English")
            pm.menu.add(0, 2, 1, "Font size +")
            pm.menu.add(0, 3, 2, "Font size −")
            pm.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> toggleLanguage()
                    2 -> adjustFont(+1f)
                    3 -> adjustFont(-1f)
                }
                true
            }
            pm.show()
        }
    }

    private fun updateLangBtn() {
        findViewById<TextView>(R.id.langBtn).text = if (english) "EN" else "VI"
    }

    /* switch language and reload from the chapter currently being read */
    private fun toggleLanguage() {
        english = !english
        prefs.edit().putString("readerLang", if (english) "en" else "vi").apply()
        updateLangBtn()
        val text = findViewById<TextView>(R.id.readerText)
        val scroll = findViewById<ScrollView>(R.id.readerScroll)
        nextIdx = (nextIdx - 1).coerceAtLeast(0)
        text.text = ""
        scroll.scrollTo(0, 0)
        appendNext()
    }

    private fun adjustFont(delta: Float) {
        fontSp = (fontSp + delta).coerceIn(12f, 26f)
        prefs.edit().putFloat("readerFontSize", fontSp).apply()
        findViewById<TextView>(R.id.readerText).setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
    }

    private fun docIdFor(name: String): String? {
        val ch = chapters ?: return null
        return if (english) ch.translated[name] ?: ch.source[name] else ch.source[name]
    }

    private fun appendNext() {
        val ch = chapters ?: return
        if (loading || nextIdx >= ch.ordered.size) return
        loading = true
        val name = ch.ordered[nextIdx]
        val docId = docIdFor(name) ?: run { nextIdx++; loading = false; return }
        val text = findViewById<TextView>(R.id.readerText)
        val titleBar = findViewById<TextView>(R.id.readerTitle)
        val scroll = findViewById<ScrollView>(R.id.readerScroll)
        lifecycleScope.launch {
            val body = withContext(Dispatchers.IO) {
                Saf.readText(contentResolver, treeUri!!, docId)
            }
            if (body != null) {
                text.append(if (text.text.isEmpty()) body else "\n\n⁂\n\n$body")
                titleBar.text = "${intent.getStringExtra("title")} — ${name.removeSuffix(".txt")}"
            }
            nextIdx++
            loading = false
            /* short first chapters may not fill the screen — keep filling */
            scroll.post {
                if (text.height > 0 && (scroll.scrollY + scroll.height) * 2 > text.height) appendNext()
            }
        }
    }
}
