package dev.vtlinh.noveldownloader

import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.widget.ArrayAdapter
import android.widget.ListView
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

    /* each loaded chapter's character offset in the text + its heading line,
       so the header can show the chapter actually being READ */
    private class LoadedChapter(val idx: Int, var start: Int, val heading: String)
    private val loadedChapters = ArrayList<LoadedChapter>()

    private fun headingOf(body: String): String = body.substringBefore('\n').trim()

    private var currentChapterIdx = -1
    private var drawerAdapter: ArrayAdapter<String>? = null

    /* (chapter index, paragraph within it) at the top of the viewport */
    private fun currentPosition(): Pair<Int, Int>? {
        val layout = text.layout ?: return null
        val y = (scroll.scrollY - text.totalPaddingTop).coerceAtLeast(0)
        val off = layout.getLineStart(layout.getLineForVertical(y))
        val cur = loadedChapters.lastOrNull { it.start <= off } ?: return null
        val para = text.text.subSequence(cur.start, off.coerceAtLeast(cur.start)).count { it == '\n' }
        return Pair(cur.idx, para)
    }

    /* Which chapter is at the top of the viewport right now. The probe
       point sits slightly BELOW the top edge, so a chapter whose heading is
       at (or within a line of) the top wins — boundary rounding can no
       longer resolve to the previous chapter. */
    private fun updateHeader() {
        val layout = text.layout ?: return
        val bias = (fontSp * 2f * resources.displayMetrics.scaledDensity).toInt()
        val y = (scroll.scrollY - text.totalPaddingTop + bias).coerceAtLeast(0)
        val off = layout.getLineStart(layout.getLineForVertical(y))
        val cur = loadedChapters.lastOrNull { it.start <= off } ?: return
        titleBar.text = cur.heading
        if (cur.idx != currentChapterIdx) {
            currentChapterIdx = cur.idx
            /* remember the chapter being read, per novel — the chapter list
               reopens scrolled to (and highlighting) it */
            intent.getStringExtra("slug")?.let { slug ->
                chapters?.ordered?.getOrNull(cur.idx)?.let { name ->
                    prefs.edit().putString("lastCh:$slug", name).apply()
                }
            }
        }
    }
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
            /* inline chapter list in the right drawer, current one highlighted */
            drawerAdapter = object : ArrayAdapter<String>(
                this@ReaderActivity, android.R.layout.simple_list_item_1,
                ch.ordered.map { it.removeSuffix(".txt") },
            ) {
                override fun getView(
                    position: Int,
                    convertView: android.view.View?,
                    parent: android.view.ViewGroup,
                ): android.view.View {
                    val v = super.getView(position, convertView, parent) as TextView
                    if (position == currentChapterIdx) {
                        v.setTextColor(getColor(R.color.accent))
                        v.setTypeface(null, android.graphics.Typeface.BOLD)
                    } else {
                        v.setTextColor(getColor(R.color.fg))
                        v.setTypeface(null, android.graphics.Typeface.NORMAL)
                    }
                    return v
                }
            }
            drawerList.adapter = drawerAdapter
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
            updateHeader()
        }

        findViewById<TextView>(R.id.chaptersBtn).setOnClickListener {
            drawerAdapter?.notifyDataSetChanged()
            drawer.openDrawer(GravityCompat.END)
            drawerList.post {
                drawerList.setSelectionFromTop(currentChapterIdx, (drawerList.height * 0.2f).toInt())
            }
        }
        findViewById<TextView>(R.id.settingsBtn).setOnClickListener { v -> showSettings(v) }
    }

    /* Custom settings popup (a PopupMenu can't do this): the font controls
       sit on ONE line — "Font size  −  +" — and adjusting the size keeps the
       popup open so the effect can be watched live. */
    private fun showSettings(anchor: android.view.View) {
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(getColor(R.color.card))
                setStroke(dp(1), 0xFF333A42.toInt())
            }
        }
        val popup = android.widget.PopupWindow(
            root,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        )
        popup.elevation = dp(8).toFloat()

        if (chapters?.translated?.isNotEmpty() == true) {
            root.addView(
                TextView(this).apply {
                    text = if (english) "Switch to Tiếng Việt" else "Switch to English"
                    textSize = 15f
                    setTextColor(getColor(R.color.fg))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        popup.dismiss()
                        toggleLanguage()
                    }
                },
            )
        }

        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(
            TextView(this).apply {
                text = "Font size"
                textSize = 15f
                setTextColor(getColor(R.color.fg))
                setPadding(dp(12), dp(10), dp(18), dp(10))
            },
        )
        fun sizeBtn(label: String, delta: Float) = TextView(this).apply {
            text = label
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.accent))
            setPadding(dp(16), dp(6), dp(16), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { adjustFont(delta) }   // popup stays open
        }
        row.addView(sizeBtn("−", -1f))
        row.addView(sizeBtn("+", +1f))
        root.addView(row)

        popup.showAsDropDown(anchor, 0, dp(4), android.view.Gravity.END)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /* switch language and reload at the SAME chapter and paragraph — the
       translation keeps one paragraph per line, so paragraphs map 1:1 */
    private fun toggleLanguage() {
        val pos = currentPosition()
        english = !english
        prefs.edit().putString("readerLang", if (english) "en" else "vi").apply()
        openAt(pos?.first ?: (nextIdx - 1).coerceAtLeast(0), pos?.second ?: 0)
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

    /* jump to a chapter: load it, append the next, prepend the previous.
       targetPara scrolls to that paragraph of the opened chapter (used by
       the language toggle to keep the reading position). */
    private fun openAt(pos: Int, targetPara: Int = 0) {
        val ch = chapters ?: return
        if (loading || ch.ordered.isEmpty()) return
        loading = true
        val p = pos.coerceIn(0, ch.ordered.size - 1)
        lifecycleScope.launch {
            firstIdx = p
            nextIdx = p
            text.text = ""
            loadedChapters.clear()
            var chapterLen = 0
            readAt(p)?.let {
                loadedChapters.add(LoadedChapter(p, 0, headingOf(it)))
                text.append(it)
                chapterLen = it.length
                nextIdx = p + 1
                titleBar.text = headingOf(it)
            }
            readAt(p + 1)?.let {
                loadedChapters.add(LoadedChapter(p + 1, text.text.length + SEP.length, headingOf(it)))
                text.append(SEP + it)
                nextIdx = p + 2
            }
            scroll.post {
                var y = 0
                val layout = text.layout
                if (layout != null && targetPara > 0 && chapterLen > 0) {
                    /* char offset of paragraph N within the opened chapter */
                    val body = text.text.toString()
                    var off = 0
                    var count = 0
                    while (count < targetPara && off < chapterLen) {
                        val n = body.indexOf('\n', off)
                        if (n == -1 || n >= chapterLen) break
                        off = n + 1
                        count++
                    }
                    y = layout.getLineTop(layout.getLineForOffset(off)) + text.totalPaddingTop
                }
                scroll.scrollTo(0, y)
                loading = false
                prependPrev()   // keeps the position (scroll compensated)
            }
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
                val start = if (text.text.isEmpty()) 0 else text.text.length + SEP.length
                loadedChapters.add(LoadedChapter(idx, start, headingOf(body)))
                text.append(if (text.text.isEmpty()) body else SEP + body)
            }
            nextIdx = idx + 1
            loading = false
            /* short chapters may not fill the screen — keep filling */
            scroll.post {
                if (text.height > 0 && (scroll.scrollY + scroll.height) * 2 > text.height) appendNext()
                updateHeader()
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
            /* Anchor the viewport by CHARACTER offset, not pixels: with a
               line-spacing multiplier the text height isn't additive, so a
               pixel delta lands slightly off and the header shows the wrong
               chapter after a jump. */
            val pad = text.totalPaddingTop
            var anchorOff = 0
            var withinLine = 0
            text.layout?.let { l ->
                val y = (scroll.scrollY - pad).coerceAtLeast(0)
                val line = l.getLineForVertical(y)
                anchorOff = l.getLineStart(line)
                withinLine = y - l.getLineTop(line)
            }
            val shift = body.length + SEP.length
            for (l in loadedChapters) l.start += shift
            loadedChapters.add(0, LoadedChapter(idx, 0, headingOf(body)))
            text.text = body + SEP + text.text.toString()
            firstIdx = idx
            scroll.post {
                text.layout?.let { l ->
                    val line = l.getLineForOffset(anchorOff + shift)
                    scroll.scrollTo(0, l.getLineTop(line) + withinLine + pad)
                }
                loading = false
                updateHeader()
            }
        }
    }
}
