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
            saveLastChapter(cur.idx)
        }
        /* remember the exact paragraph too, so reopening this chapter
           returns to where we left off (paragraphs map 1:1 across EN/VI) */
        if (off != lastProbeOff) {
            lastProbeOff = off
            intent.getStringExtra("slug")?.let { slug ->
                chapters?.ordered?.getOrNull(cur.idx)?.let { name ->
                    val para = text.text.subSequence(cur.start, off.coerceAtLeast(cur.start))
                        .count { it == '\n' }
                    prefs.edit().putString("readPos:$slug", "$name|$para").apply()
                }
            }
        }
    }

    private var lastProbeOff = -1

    /* the saved paragraph for a chapter — 0 unless it's the chapter we
       last left off in */
    private fun savedParaFor(idx: Int): Int {
        val slug = intent.getStringExtra("slug") ?: return 0
        val saved = prefs.getString("readPos:$slug", null) ?: return 0
        val name = chapters?.ordered?.getOrNull(idx) ?: return 0
        if (saved.substringBefore('|') != name) return 0
        return saved.substringAfter('|').toIntOrNull() ?: 0
    }

    /* remember the chapter being read, per novel — the chapter list reopens
       scrolled to (and highlighting) it */
    private fun saveLastChapter(idx: Int) {
        intent.getStringExtra("slug")?.let { slug ->
            chapters?.ordered?.getOrNull(idx)?.let { name ->
                prefs.edit().putString("lastCh:$slug", name).apply()
            }
        }
    }
    @Volatile private var loading = false
    private var english = true
    private var fontSp = 16f

    /* ---- text-to-speech ---- */
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var speakCursor = 0        // char offset where the NEXT sentence starts
    private var resumeCursor = -1      // start of the sentence being/last spoken
    private var pendingSpeakContinue = false
    private var pendingSpeakAfterOpen = false
    private var curTtsLang = ""        // language profile currently applied ("en"/"vi")
    private var curSentStart = -1
    private var curSentEnd = -1
    private val highlightSpan = android.text.style.BackgroundColorSpan(0x554F8CFF.toInt())

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
                ChapterListActivity.chapterNames(this@ReaderActivity, treeUri!!, dirName, order)
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
                openAt(pos, savedParaFor(pos))
            }
            val startIdx = ch.ordered.indexOf(start).coerceAtLeast(0)
            openAt(startIdx, savedParaFor(startIdx))
        }

        /* Chapter loading is border-driven: crossing into the LAST loaded
           chapter appends 2 more, crossing into the FIRST prepends 2 — and
           nothing loads during the first 5 seconds after opening a chapter
           (loadReady), so the open placement is never disturbed. */
        scroll.setOnScrollChangeListener { _, _, _, _, _ ->
            updateHeader()
            maybeLoadMore()
        }

        /* TTS: double-tap anywhere in the text starts reading from there.
           Google TTS only — no other engine is ever used. */
        initTts()
        /* Double tap = start TTS, and ONLY that: the second tap is swallowed
           so the selectable TextView never runs its own double-tap
           word-selection. Long-press text selection is untouched. */
        var swallowTap = false
        val doubleTap = android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    val layout = text.layout ?: return false
                    val line = layout.getLineForVertical(e.y.toInt() - text.totalPaddingTop)
                    val off = layout.getOffsetForHorizontal(line, e.x - text.totalPaddingLeft)
                    startTtsFrom(off)
                    swallowTap = true
                    return true
                }
            },
        )
        text.setOnTouchListener { _, ev ->
            doubleTap.onTouchEvent(ev)
            val consume = swallowTap
            if (ev.actionMasked == android.view.MotionEvent.ACTION_UP ||
                ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL
            ) swallowTap = false
            consume
        }

        findViewById<TextView>(R.id.ttsPlayBtn).setOnClickListener {
            if (speaking) {
                pauseTts()
                return@setOnClickListener
            }
            if (resumeCursor >= 0) {
                startTtsFrom(resumeCursor)
                return@setOnClickListener
            }
            /* saved position from a previous session: continue from it */
            val slugX = intent.getStringExtra("slug")
            val saved = slugX?.let { prefs.getString("ttsPos:$it", null) }
            val ord = chapters?.ordered
            if (saved != null && ord != null) {
                val name = saved.substringBefore('|')
                val para = saved.substringAfter('|').toIntOrNull() ?: 0
                val idx = ord.indexOf(name)
                /* the saved TTS spot only applies when the user is ON that
                   chapter — picking a different chapter and pressing play
                   reads from where they are, not where TTS once stopped */
                if (idx >= 0 && idx == currentChapterIdx) {
                    val lc = loadedChapters.firstOrNull { it.idx == idx }
                    if (lc != null) {
                        startTtsFrom(offsetOfPara(lc.start, para))
                    } else {
                        pendingSpeakAfterOpen = true
                        openAt(idx, para)
                    }
                    return@setOnClickListener
                }
            }
            val layout = text.layout
            val off = if (layout != null) {
                layout.getLineStart(
                    layout.getLineForVertical((scroll.scrollY - text.totalPaddingTop).coerceAtLeast(0)),
                )
            } else 0
            startTtsFrom(off)
        }
        findViewById<TextView>(R.id.ttsSettingsBtn).setOnClickListener { showTtsSettings() }

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
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

    /* ---- TTS engine ---- */

    /* live connection state, shown by the settings sheet's diagnostic line */
    private var ttsInitState = "starting"

    private val GOOGLE_TTS = "com.google.android.tts"

    /* Google TTS ONLY — never fall back to another engine (a voiceless
       default engine is how the voice list went empty before). A failed
       bind is retried with a growing delay instead. */
    private fun initTts(attempt: Int = 0) {
        ttsInitState = "connecting to Google TTS" +
            (if (attempt > 0) " (retry $attempt)" else "")
        val listener = android.speech.tts.TextToSpeech.OnInitListener { st ->
            if (st == android.speech.tts.TextToSpeech.SUCCESS) {
                ttsReady = true
                ttsInitState = "connected to Google TTS"
                curTtsLang = ""   // re-apply the language profile on next speak
            } else {
                ttsReady = false
                ttsInitState = "FAILED to bind Google TTS — is it installed and enabled?"
                /* always post: the failure callback may fire synchronously
                   inside the constructor, before `tts = t` below runs */
                android.os.Handler(mainLooper).post {
                    tts?.shutdown()
                    if (attempt < 4) {
                        android.os.Handler(mainLooper).postDelayed(
                            { initTts(attempt + 1) },
                            1000L * (attempt + 1),
                        )
                    }
                }
            }
        }
        val t = android.speech.tts.TextToSpeech(this, listener, GOOGLE_TTS)
        t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread { if (speaking) speakNext() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { if (speaking) speakNext() }
            }
        })
        tts = t
    }

    private val viCharsRe = Regex(
        "[\u0103\u00e2\u0111\u00ea\u00f4\u01a1\u01b0\u00e0\u1ea3\u00e3\u00e1\u1ea1\u1eb1\u1eb3\u1eb5\u1eaf\u1eb7\u1ea7\u1ea9\u1eab\u1ea5\u1ead\u00e8\u1ebb\u1ebd\u00e9\u1eb9\u1ec1\u1ec3\u1ec5\u1ebf\u1ec7\u00ec\u1ec9\u0129\u00ed\u1ecb\u00f2\u1ecf\u00f5\u00f3\u1ecd\u1ed3\u1ed5\u1ed7\u1ed1\u1ed9\u1edd\u1edf\u1ee1\u1edb\u1ee3\u00f9\u1ee7\u0169\u00fa\u1ee5\u1eeb\u1eed\u1eef\u1ee9\u1ef1\u1ef3\u1ef7\u1ef9\u00fd\u1ef5]",
        RegexOption.IGNORE_CASE,
    )

    /* Vietnamese text always carries diacritics within a sentence or two */
    private fun detectLang(sentence: String) = if (viCharsRe.containsMatchIn(sentence)) "vi" else "en"

    /* voice/rate/pitch are stored PER LANGUAGE ("ttsRate:en", ...) */
    private fun applyTtsConfig(lang: String) {
        val t = tts ?: return
        curTtsLang = lang
        t.setSpeechRate(prefs.getFloat("ttsRate:$lang", 1f))
        t.setPitch(prefs.getFloat("ttsPitch:$lang", 1f))
        val saved = prefs.getString("ttsVoice:$lang", null)
        val v = saved?.let { name ->
            try { t.voices?.firstOrNull { it.name == name } } catch (e: Exception) { null }
        }
        if (v != null) {
            t.voice = v
        } else {
            t.language = if (lang == "vi") java.util.Locale("vi", "VN") else java.util.Locale.US
        }
    }

    private fun paraStartOf(off: Int): Int {
        val body = text.text.toString()
        val o = off.coerceIn(0, body.length)
        return body.lastIndexOf('\n', (o - 1).coerceAtLeast(0)) + 1
    }

    private fun offsetOfPara(chapterStart: Int, para: Int): Int {
        val body = text.text.toString()
        var off = chapterStart
        var n = 0
        while (n < para) {
            val i = body.indexOf('\n', off)
            if (i == -1) break
            off = i + 1
            n++
        }
        return off
    }

    /* next sentence at/after `from`: bounded by paragraph breaks, split on
       terminator punctuation followed by a space (so "3.5" stays intact) */
    private fun nextSentence(body: String, from: Int): Pair<Int, Int>? {
        var i = from.coerceAtLeast(0)
        while (i < body.length && (body[i] == '\n' || body[i] == ' ' || body[i] == '\u2042')) i++
        if (i >= body.length) return null
        var j = i
        while (j < body.length) {
            val c = body[j]
            if (c == '\n') break
            if (c == '.' || c == '!' || c == '?' || c == '\u2026') {
                var k = j + 1
                while (k < body.length && (body[k] == '"' || body[k] == '\u201d' || body[k] == '\u2019' || body[k] == ')' || body[k] == '\u3011' || body[k] == '\u300f')) k++
                if (k >= body.length || body[k] == ' ' || body[k] == '\n') { j = k; break }
            }
            j++
        }
        return Pair(i, j.coerceAtMost(body.length))
    }

    private fun startTtsFrom(off: Int) {
        if (!ttsReady) {
            initTts()   // engine dropped — rebind Google TTS for the next tap
            return
        }
        speakCursor = paraStartOf(off)
        speaking = true
        /* foreground service keeps reading alive with the screen off */
        TtsService.start(this, intent.getStringExtra("title") ?: "")
        updatePlayBtn()
        speakNext()
    }

    /* speak the next sentence: auto-detect its language (switching the whole
       voice profile when it changes), highlight it, and remember the spot */
    private fun speakNext() {
        val t = tts ?: return
        val body = text.text.toString()
        val sent = nextSentence(body, speakCursor)
        if (sent == null) {
            clearHighlight()
            if (nextIdx < (chapters?.ordered?.size ?: 0)) {
                pendingSpeakContinue = true
                appendChapters(2)
            } else {
                stopTts()
            }
            return
        }
        val (s0, s1) = sent
        /* about to enter the LAST loaded chapter: hold this sentence, append
           2 more chapters (appends never move existing text), then resume —
           reading always has at least 2 chapters of runway ahead */
        val lastLoaded = loadedChapters.lastOrNull()
        if (!loading && lastLoaded != null && s0 >= lastLoaded.start &&
            nextIdx < (chapters?.ordered?.size ?: 0)
        ) {
            pendingSpeakContinue = true
            appendChapters(2)
            return
        }
        resumeCursor = s0
        speakCursor = s1
        val sentence = body.substring(s0, s1)
        val lang = detectLang(sentence)
        if (lang != curTtsLang) applyTtsConfig(lang)
        setHighlight(s0, s1)
        scrollToSpoken(s0)
        saveTtsPos(s0)
        t.speak(sentence, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "novel")
    }

    /* Keep the line being read vertically centered while TTS plays. Posted a
       frame so the layout reflects any chapter text just appended; without
       that, centering right after a chapter append computes against the old
       layout and jumps to the buffer end. */
    private fun scrollToSpoken(off: Int) {
        scroll.post {
            if (!speaking) return@post   // paused since this was queued
            /* a prepend may have shifted offsets since the call — resumeCursor
               is kept shifted, so prefer it over the captured value */
            val target = if (resumeCursor >= 0) resumeCursor else off
            val layout = text.layout ?: return@post
            val line = layout.getLineForOffset(target.coerceIn(0, text.length()))
            val y = layout.getLineTop(line) + text.totalPaddingTop
            /* keep the spoken line at ~20% of the viewport height */
            scroll.smoothScrollTo(0, (y - scroll.height / 5).coerceAtLeast(0))
        }
    }

    /* freeze the page where it is: a zero-delta smooth scroll replaces any
       in-flight centering animation so pausing doesn't keep gliding */
    private fun cancelAutoScroll() {
        scroll.smoothScrollBy(0, 0)
    }

    private fun setHighlight(s0: Int, s1: Int) {
        curSentStart = s0
        curSentEnd = s1
        val sp = text.text as? android.text.Spannable ?: return
        sp.removeSpan(highlightSpan)
        if (s1 > s0 && s1 <= sp.length) {
            sp.setSpan(highlightSpan, s0, s1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun clearHighlight() {
        curSentStart = -1
        curSentEnd = -1
        (text.text as? android.text.Spannable)?.removeSpan(highlightSpan)
    }

    /* persist "chapter file | paragraph in chapter" so the next session's
       play button continues from here (paragraphs map 1:1 across EN/VI) */
    private fun saveTtsPos(off: Int) {
        val slug = intent.getStringExtra("slug") ?: return
        val ch = loadedChapters.lastOrNull { it.start <= off } ?: return
        val name = chapters?.ordered?.getOrNull(ch.idx) ?: return
        val para = text.text.subSequence(ch.start, off.coerceAtLeast(ch.start)).count { it == '\n' }
        prefs.edit().putString("ttsPos:$slug", "$name|$para").apply()
    }

    /* pause replays the interrupted sentence on resume */
    private fun pauseTts() {
        speaking = false
        cancelAutoScroll()
        tts?.stop()
        TtsService.stop(this)
        if (resumeCursor >= 0) speakCursor = resumeCursor
        clearHighlight()
        updatePlayBtn()
    }

    private fun stopTts() {
        speaking = false
        cancelAutoScroll()
        tts?.stop()
        TtsService.stop(this)
        clearHighlight()
        updatePlayBtn()
    }

    /* pause is drawn as two Dingbats bars \u2014 U+23F8 falls back to the emoji
       font on many devices even with the text-presentation selector, so it
       would render in a different style and color than the play triangle */
    private fun updatePlayBtn() {
        findViewById<TextView>(R.id.ttsPlayBtn)?.text =
            if (speaking) "\u275a\u275a" else "\u25b6\ufe0e"
    }

    /* While this screen is live, remember we're in the middle of reading —
       the launcher resumes straight into it. Deliberately leaving (back or
       navigation → isFinishing) clears the marker; backgrounding or a
       process kill does not. */
    override fun onResume() {
        super.onResume()
        val dir = intent.getStringExtra("dir") ?: return
        val slug = intent.getStringExtra("slug") ?: return
        val o = org.json.JSONObject()
        o.put("dir", dir)
        o.put("title", intent.getStringExtra("title") ?: dir)
        o.put("slug", slug)
        prefs.edit().putString("lastReading", o.toString()).apply()
    }

    override fun onPause() {
        if (isFinishing) prefs.edit().remove("lastReading").apply()
        super.onPause()
    }

    override fun onDestroy() {
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
        TtsService.stop(this)
        super.onDestroy()
    }

    /* bottom sheet: voice picker + rate/pitch sliders (0.5–3, step 0.1,
       with </> nudge buttons) */
    private fun showTtsSettings() {
        val ctx = this
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
            setBackgroundColor(getColor(R.color.card))
        }
        fun label(txt: String) = TextView(ctx).apply {
            text = txt
            textSize = 13f
            setTextColor(getColor(R.color.muted))
            setPadding(0, dp(12), 0, dp(4))
        }

        /* settings are per language; edit the profile of what's being read.
           The language is named once in the header, not on every row. */
        val lang = curTtsLang.ifEmpty { detectLang(text.text.toString().take(600)) }
        root.addView(
            TextView(ctx).apply {
                text = "TTS — " + if (lang == "vi") "Tiếng Việt" else "English"
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.fg))
                setPadding(0, 0, 0, dp(4))
            },
        )

        root.addView(label("Voice"))
        fun voiceList(): List<android.speech.tts.Voice> = try {
            val all = tts?.voices.orEmpty()
            val forLang = all.filter { it.locale.language == lang }
            /* nothing for this language → show everything rather than nothing */
            (forLang.ifEmpty { all }).sortedBy { it.name }
        } catch (e: Exception) { emptyList() }
        var voices = voiceList()
        val spinner = android.widget.Spinner(ctx)
        fun fillSpinner() {
            spinner.adapter = android.widget.ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("Default") + voices.map { "${it.locale} — ${it.name}" },
            )
            val savedVoice = prefs.getString("ttsVoice:$lang", null)
            val savedIdx = voices.indexOfFirst { it.name == savedVoice }
            spinner.setSelection(if (savedIdx >= 0) savedIdx + 1 else 0)
        }
        fillSpinner()
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                pos: Int,
                id: Long,
            ) {
                if (pos == 0) prefs.edit().remove("ttsVoice:$lang").apply()
                else prefs.edit().putString("ttsVoice:$lang", voices[pos - 1].name).apply()
                applyTtsConfig(lang)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        root.addView(spinner)
        if (voices.isEmpty()) {
            /* Engine dropped, still connecting, or genuinely broken (e.g. a
               crashed Google TTS). Keep polling while the sheet is open,
               cycling through every installed engine, with a live status
               line and a shortcut into Android's own TTS settings. */
            val diag = label("No voices yet…")
            root.addView(diag)
            root.addView(
                TextView(ctx).apply {
                    text = "Open Android TTS settings"
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(getColor(R.color.accent))
                    setPadding(0, dp(10), 0, dp(4))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        try {
                            startActivity(
                                android.content.Intent("com.android.settings.TTS_SETTINGS")
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (e: Exception) { diag.text = "Couldn't open system TTS settings." }
                    }
                },
            )
            root.addView(
                TextView(ctx).apply {
                    text = "Reset Google TTS (reinstall voice data)"
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(getColor(R.color.accent))
                    setPadding(0, dp(4), 0, dp(4))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        /* closest in-app equivalent of the system-settings
                           reset: drop our connection and launch the engine's
                           own voice-data (re)install flow; the poll loop
                           rebinds when we come back */
                        try { tts?.shutdown() } catch (e: Exception) {}
                        tts = null
                        ttsReady = false
                        try {
                            startActivity(
                                android.content.Intent(
                                    android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA,
                                ).setPackage(GOOGLE_TTS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (e: Exception) { diag.text = "Couldn't launch the Google TTS data installer." }
                    }
                },
            )
            root.addView(
                TextView(ctx).apply {
                    text = "Update Google TTS on Play Store"
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(getColor(R.color.accent))
                    setPadding(0, dp(4), 0, dp(4))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        try {
                            startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("market://details?id=$GOOGLE_TTS"),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (e: Exception) { diag.text = "Couldn't open the Play Store." }
                    }
                },
            )
            fun googleInstalled(): Boolean =
                try { tts?.engines?.any { it.name == GOOGLE_TTS } == true } catch (e: Exception) { false }
            var tick = 0
            fun refresh() {
                if (!sheet.isShowing) return
                voices = voiceList()
                if (voices.isNotEmpty()) {
                    fillSpinner()
                    diag.text = ""
                    return
                }
                tick++
                /* every ~4s of no voices, rebind Google TTS (never another engine) */
                if (tick % 5 == 0 && !speaking) initTts()
                diag.text = "No voices — $ttsInitState" +
                    if (!googleInstalled()) "\nGoogle TTS is not visible on this device" else ""
                spinner.postDelayed({ refresh() }, 800)
            }
            if (!speaking) initTts()
            spinner.postDelayed({ refresh() }, 800)
        }

        fun sliderRow(title: String, get: () -> Float, set: (Float) -> Unit) {
            root.addView(label(title))
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            /* 0.05 steps: SeekBar progress p <-> value 0.5 + p/20 */
            val valueTv = TextView(ctx).apply {
                textSize = 14f
                setTextColor(getColor(R.color.fg))
                text = "%.2f".format(get())
                minWidth = dp(46)
                setPadding(dp(8), 0, 0, 0)
            }
            val seek = android.widget.SeekBar(ctx).apply {
                max = 50
                progress = ((get() - 0.5f) * 20f + 0.5f).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                )
            }
            fun applyValue(v: Float, fromSeek: Boolean) {
                val clamped = (Math.round(v * 20f) / 20f).coerceIn(0.5f, 3f)
                set(clamped)
                valueTv.text = "%.2f".format(clamped)
                if (!fromSeek) seek.progress = ((clamped - 0.5f) * 20f + 0.5f).toInt()
                applyTtsConfig(lang)
            }
            seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, pr: Int, fromUser: Boolean) {
                    if (fromUser) applyValue(0.5f + pr / 20f, true)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
            fun stepBtn(txt: String, delta: Float) = TextView(ctx).apply {
                text = txt
                textSize = 20f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.accent))
                setPadding(dp(12), dp(4), dp(12), dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener { applyValue(get() + delta, false) }
            }
            row.addView(stepBtn("<", -0.05f))
            row.addView(seek)
            row.addView(stepBtn(">", +0.05f))
            row.addView(valueTv)
            root.addView(row)
        }
        sliderRow("Rate", { prefs.getFloat("ttsRate:$lang", 1f) }) {
            prefs.edit().putFloat("ttsRate:$lang", it).apply()
        }
        sliderRow("Pitch", { prefs.getFloat("ttsPitch:$lang", 1f) }) {
            prefs.edit().putFloat("ttsPitch:$lang", it).apply()
        }

        sheet.setContentView(root)
        sheet.show()
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
        val ref = (if (english) ch.translated[name] ?: ch.source[name] else ch.source[name])
            ?: return null
        return withContext(Dispatchers.IO) {
            when {
                Zips.isRef(ref) -> ch.zip?.let { Zips.read(it, Zips.entryOf(ref)) }
                Zips.isGzRef(ref) -> Zips.readGz(contentResolver, treeUri!!, Zips.gzDocId(ref))
                else -> Saf.readText(contentResolver, treeUri!!, ref)
            }
        }
    }

    /* jump to a chapter: load it, append the next, prepend the previous.
       targetPara scrolls to that paragraph of the opened chapter (used by
       the language toggle to keep the reading position). */
    private fun openAt(pos: Int, targetPara: Int = 0) {
        val ch = chapters ?: return
        if (loading || ch.ordered.isEmpty()) return
        stopTts()
        resumeCursor = -1
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
                currentChapterIdx = p
                saveLastChapter(p)
            }
            readAt(p + 1)?.let {
                loadedChapters.add(LoadedChapter(p + 1, text.text.length + SEP.length, headingOf(it)))
                text.append(SEP + it)
                nextIdx = p + 2
            }
            /* Place the viewport only once the layout reflects the FINAL
               text. On a cold start the first posted frame can still see the
               "Loading…" layout — computing the restore position against it
               landed near the top of the buffer. Retry frame by frame until
               the layout catches up (or give up and place best-effort). */
            fun place(attempt: Int) {
                val layout = text.layout
                if ((layout == null || layout.text.length != text.text.length) && attempt < 20) {
                    scroll.post { place(attempt + 1) }
                    return
                }
                var y = 0
                var targetOff = 0
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
                    targetOff = off
                    y = layout.getLineTop(layout.getLineForOffset(off)) + text.totalPaddingTop
                }
                scroll.scrollTo(0, y)
                scroll.visibility = android.view.View.VISIBLE
                loading = false
                if (pendingSpeakAfterOpen) {
                    pendingSpeakAfterOpen = false
                    startTtsFrom(targetOff)
                }
                /* no immediate prepend/append: the 5-second quiet window
                   keeps the just-placed position undisturbed */
                armLoadTimer()
            }
            /* hide the page until the restore lands — the frames spent
               waiting for the layout otherwise flash the chapter top before
               snapping to the saved paragraph */
            if (targetPara > 0) scroll.visibility = android.view.View.INVISIBLE
            scroll.post { place(0) }
        }
    }

    /* ---- border-driven chapter loading ----
       Nothing loads for the first 5 seconds after a chapter is opened
       (loadReady), loads always come 2 chapters at a time, and the triggers
       are chapter borders: viewport inside the LAST loaded chapter → append,
       inside the FIRST → prepend (never during speech). */

    private var loadReady = false
    private var loadJob: kotlinx.coroutines.Job? = null

    /* (re)arm the 5-second quiet window after every chapter open. When it
       expires, stock up 2 chapters in BOTH directions; only after that do
       the chapter-border triggers (maybeLoadMore) take over. */
    private fun armLoadTimer() {
        loadReady = false
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(5000)
            appendChapters(2)
            while (loading) kotlinx.coroutines.delay(50)
            if (!speaking) prependChapters(2)   // prepends shift text — not under TTS
            while (loading) kotlinx.coroutines.delay(50)
            loadReady = true
        }
    }

    private fun maybeLoadMore() {
        if (!loadReady || loading) return
        val ch = chapters ?: return
        val first = loadedChapters.firstOrNull() ?: return
        val last = loadedChapters.lastOrNull() ?: return
        val more = nextIdx < ch.ordered.size
        /* inside the last loaded chapter, or content too short to scroll */
        if (more && (currentChapterIdx >= last.idx ||
                (text.height > 0 && text.height < scroll.height * 3 / 2))
        ) {
            appendChapters(2)
            return
        }
        /* inside the first loaded chapter; prepends shift coordinates, so
           never while TTS drives the scroll */
        if (!speaking && currentChapterIdx <= first.idx && firstIdx > 0) prependChapters(2)
    }

    /* A selectable TextView keeps a cursor where the user last tapped
       (including the TTS double-tap); appending text to it can auto-scroll
       back to that cursor. Drop selection and focus before changing text. */
    private fun clearTextSelection() {
        (text.text as? android.text.Spannable)?.let { android.text.Selection.removeSelection(it) }
        if (text.isFocused) text.clearFocus()
    }

    private fun appendChapters(n: Int) {
        val ch = chapters ?: return
        if (loading || nextIdx >= ch.ordered.size) return
        loading = true
        lifecycleScope.launch {
            val keepY = scroll.scrollY
            clearTextSelection()
            var added = 0
            while (added < n && nextIdx < ch.ordered.size) {
                val idx = nextIdx
                val body = readAt(idx)
                if (body != null) {
                    val start = if (text.text.isEmpty()) 0 else text.text.length + SEP.length
                    loadedChapters.add(LoadedChapter(idx, start, headingOf(body)))
                    text.append(if (text.text.isEmpty()) body else SEP + body)
                }
                nextIdx = idx + 1
                added++
            }
            loading = false
            /* TTS paused at the border waiting for this — resume reading */
            if (pendingSpeakContinue) {
                pendingSpeakContinue = false
                if (speaking) speakNext()
            }
            scroll.post {
                /* appends never move existing text, so a big unprompted move
                   across this append is the cursor auto-scroll — undo it
                   (a real fling can't cover half a screen in these frames) */
                if (!speaking && kotlin.math.abs(scroll.scrollY - keepY) > scroll.height / 2) {
                    scroll.scrollTo(0, keepY)
                }
                updateHeader()
            }
        }
    }

    /* load up to n chapters above the current content in ONE text update,
       keeping the reader's place (single anchor compensation) */
    private fun prependChapters(n: Int) {
        val ch = chapters ?: return
        if (loading || firstIdx <= 0) return
        loading = true
        lifecycleScope.launch {
            /* gather the chapters directly above, kept in ascending order */
            val bodies = ArrayList<Pair<Int, String>>()
            var idx = firstIdx - 1
            while (idx >= 0 && bodies.size < n) {
                val b = readAt(idx) ?: break
                bodies.add(0, Pair(idx, b))
                idx--
            }
            if (bodies.isEmpty()) {
                /* chapter above unreadable (not downloaded) — skip past it */
                firstIdx = (firstIdx - 1).coerceAtLeast(0)
                loading = false
                return@launch
            }
            /* Anchor the viewport by CHARACTER offset, not pixels: with a
               line-spacing multiplier the text height isn't additive, so a
               pixel delta lands slightly off. */
            val pad = text.totalPaddingTop
            var anchorOff = 0
            var withinLine = 0
            text.layout?.let { l ->
                val y = (scroll.scrollY - pad).coerceAtLeast(0)
                val line = l.getLineForVertical(y)
                anchorOff = l.getLineStart(line)
                withinLine = y - l.getLineTop(line)
            }
            clearTextSelection()
            val block = bodies.joinToString(SEP) { it.second }
            val shift = block.length + SEP.length
            for (l in loadedChapters) l.start += shift
            speakCursor += shift
            if (resumeCursor >= 0) resumeCursor += shift
            var acc = 0
            val newLoaded = ArrayList<LoadedChapter>()
            for ((chapterIdx, body) in bodies) {
                newLoaded.add(LoadedChapter(chapterIdx, acc, headingOf(body)))
                acc += body.length + SEP.length
            }
            loadedChapters.addAll(0, newLoaded)
            text.setText(block + SEP + text.text.toString(), TextView.BufferType.EDITABLE)
            firstIdx = bodies.first().first
            if (speaking && curSentStart >= 0) {
                setHighlight(curSentStart + shift, curSentEnd + shift)
            }
            /* Re-anchor only once the layout includes the prepended text.
               Anchoring against a stale layout clamps the scroll near the
               top, which retriggers prepend — cascading several chapters
               upward from where the user actually opened. */
            fun anchor(attempt: Int) {
                val l = text.layout
                if ((l == null || l.text.length != text.text.length) && attempt < 20) {
                    scroll.post { anchor(attempt + 1) }
                    return
                }
                l?.let {
                    val line = it.getLineForOffset(anchorOff + shift)
                    scroll.scrollTo(0, it.getLineTop(line) + withinLine + pad)
                }
                loading = false
                updateHeader()
                /* a smooth scroll in flight when the prepend landed still
                   animates toward pre-shift coordinates — restart it against
                   the new layout */
                if (speaking && resumeCursor >= 0) scrollToSpoken(resumeCursor)
            }
            scroll.post { anchor(0) }
        }
    }
}
