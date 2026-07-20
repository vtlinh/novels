package dev.vtlinh.noveldownloader

import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* Reading mode, screen 3: the reader.

   Opening a chapter loads it plus its neighbours (previous prepended, next
   appended). Scrolling past HALF of the loaded content appends the next
   chapter; scrolling to the top prepends the previous one (scroll position
   preserved), so reading is seamless in both directions.

   The ≡ button opens a right-side drawer with the full chapter list for
   jumping anywhere. EN/VI switches between the English translation and the
   Vietnamese source; the ⚙ menu also has the language toggle and Font size
   +/−. Language and font persist. */
class ReaderActivity : AppCompatActivity() {

    companion object {
        private const val SEP = "\n\n⁂\n\n"
    }

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val store by lazy { DownloadStore(this) }

    private var chapters: ChapterListActivity.Companion.Chapters? = null
    private var treeUri: Uri? = null
    private var firstIdx = 0   // first loaded chapter
    private var nextIdx = 0    // next chapter to append
    @Volatile private var loading = false
    private var english = true
    private var fontSp = 16f

    private lateinit var text: TextView
    private lateinit var titleBar: TextView
    private lateinit var scroll: ScrollView

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

        titleBar = findViewById(R.id.readerTitle)
        text = findViewById(R.id.readerText)
        scroll = findViewById(R.id.readerScroll)
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

        val drawer = findViewById<DrawerLayout>(R.id.readerDrawer)
        val drawerList = findViewById<ListView>(R.id.chapterDrawerList)

        lifecycleScope.launch {
            chapters = withContext(Dispatchers.IO) {
                val order = intent.getStringExtra("slug")?.let {
                    try { store.getChapterOrder(folder, it) } catch (e: Exception) { null }
                } ?: emptyMap()
                ChapterListActivity.chapterNames(contentResolver, treeUri!!, dirName, order)
            }
            val ch = chapters ?: return@launch
            /* no translation on disk -> nothing to toggle between */
            if (ch.translated.isEmpty()) {
                findViewById<TextView>(R.id.langBtn).visibility = android.view.View.GONE
            }
            /* inline chapter list in the right drawer */
            drawerList.adapter = ArrayAdapter(
                this@ReaderActivity, android.R.layout.simple_list_item_1,
                ch.ordered.map { it.removeSuffix(".txt") },
            )
            drawerList.setOnItemClickListener { _, _, pos, _ ->
                drawer.closeDrawer(GravityCompat.END)
                openAt(pos)
            }
            openAt(ch.ordered.indexOf(start).coerceAtLeast(0))
        }

        /* past half of the loaded content -> append the next chapter;
           back at the top -> prepend the previous one */
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val content = text.height
            if (content > 0 && (scrollY + scroll.height) * 2 > content) appendNext()
            if (scrollY < 300) prependPrev()
        }

        findViewById<TextView>(R.id.chaptersBtn).setOnClickListener {
            drawer.openDrawer(GravityCompat.END)
        }
        findViewById<TextView>(R.id.langBtn).setOnClickListener { toggleLanguage() }
        findViewById<TextView>(R.id.settingsBtn).setOnClickListener { v ->
            val pm = PopupMenu(this, v)
            if (chapters?.translated?.isNotEmpty() == true) {
                pm.menu.add(0, 1, 0, if (english) "Switch to Tiếng Việt" else "Switch to English")
            }
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

    /* switch language and reload around the chapter currently being read */
    private fun toggleLanguage() {
        english = !english
        prefs.edit().putString("readerLang", if (english) "en" else "vi").apply()
        updateLangBtn()
        openAt((nextIdx - 1).coerceAtLeast(0))
    }

    private fun adjustFont(delta: Float) {
        fontSp = (fontSp + delta).coerceIn(12f, 26f)
        prefs.edit().putFloat("readerFontSize", fontSp).apply()
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
    }

    private suspend fun readAt(i: Int): String? {
        val ch = chapters ?: return null
        if (i < 0 || i >= ch.ordered.size) return null
        val name = ch.ordered[i]
        val docId = (if (english) ch.translated[name] ?: ch.source[name] else ch.source[name])
            ?: return null
        return withContext(Dispatchers.IO) { Saf.readText(contentResolver, treeUri!!, docId) }
    }

    private fun chapterLabel(i: Int): String =
        chapters?.ordered?.getOrNull(i)?.removeSuffix(".txt") ?: ""

    /* jump to a chapter: load it, append the next, prepend the previous */
    private fun openAt(pos: Int) {
        val ch = chapters ?: return
        if (loading || ch.ordered.isEmpty()) return
        loading = true
        val p = pos.coerceIn(0, ch.ordered.size - 1)
        lifecycleScope.launch {
            firstIdx = p
            nextIdx = p
            text.text = ""
            readAt(p)?.let { text.append(it); nextIdx = p + 1 }
            titleBar.text = "${intent.getStringExtra("title")} — ${chapterLabel(p)}"
            readAt(p + 1)?.let { text.append(SEP + it); nextIdx = p + 2 }
            scroll.scrollTo(0, 0)
            loading = false
            prependPrev()   // lands the view back at the opened chapter's top
        }
    }

    private fun appendNext() {
        val ch = chapters ?: return
        if (loading || nextIdx >= ch.ordered.size) return
        loading = true
        val idx = nextIdx
        lifecycleScope.launch {
            val body = readAt(idx)
            if (body != null) {
                text.append(if (text.text.isEmpty()) body else SEP + body)
                titleBar.text = "${intent.getStringExtra("title")} — ${chapterLabel(idx)}"
            }
            nextIdx = idx + 1
            loading = false
            /* short chapters may not fill the screen — keep filling */
            scroll.post {
                if (text.height > 0 && (scroll.scrollY + scroll.height) * 2 > text.height) appendNext()
            }
        }
    }

    /* load the previous chapter above the current content, keeping the
       reader's place (scroll compensated by the added height) */
    private fun prependPrev() {
        val ch = chapters ?: return
        if (loading || firstIdx <= 0) return
        loading = true
        val idx = firstIdx - 1
        lifecycleScope.launch {
            val body = readAt(idx)
            if (body == null) {
                firstIdx = idx
                loading = false
                return@launch
            }
            val oldHeight = text.height
            val oldY = scroll.scrollY
            text.text = body + SEP + text.text.toString()
            firstIdx = idx
            scroll.post {
                scroll.scrollTo(0, oldY + (text.height - oldHeight))
                loading = false
            }
        }
    }
}
