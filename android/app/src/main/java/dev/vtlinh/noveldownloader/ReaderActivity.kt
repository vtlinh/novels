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

        /* the reader instance currently owning TTS. Opening a new reader
           finishes the old one so two chapters never read at once — but
           merely leaving to the chapter list (see leaveReader) keeps the
           instance alive so playback continues. */
        @Volatile private var active: ReaderActivity? = null
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
        /* keep the reading notification on the current chapter */
        if (speaking && cur.heading.isNotEmpty() && cur.heading != lastNotifHeading) {
            lastNotifHeading = cur.heading
            TtsService.start(this, cur.heading, true, mediaSession?.sessionToken, intent.getStringExtra("slug"))
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

    /* ---- sleep timer + shake-to-reset ---- */
    private val sleepHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sleepRunnable = Runnable { if (speaking) pauseTts() }
    private var sensorManager: android.hardware.SensorManager? = null
    private var shakeListener: android.hardware.SensorEventListener? = null

    /* (re)arm the sleep timer for the configured minutes; 0 = off. Called on
       every play and whenever a shake resets it. */
    private fun scheduleSleepTimer() {
        sleepHandler.removeCallbacks(sleepRunnable)
        val mins = prefs.getInt("sleepMinutes", 0)
        if (mins > 0) sleepHandler.postDelayed(sleepRunnable, mins * 60_000L)
    }

    private fun cancelSleepTimer() {
        sleepHandler.removeCallbacks(sleepRunnable)
    }

    /* accelerometer watch: a shake above the threshold re-arms the sleep
       timer and flashes "shaking" for 2s. Only active while reading and
       when the option is on. */
    private fun startShakeDetection() {
        if (shakeListener != null) return
        if (!prefs.getBoolean("shakeEnabled", false)) return
        val sm = getSystemService(SENSOR_SERVICE) as? android.hardware.SensorManager ?: return
        val accel = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
        /* stored as a 1–10 level; the physical accel threshold is level / 5
           (so level 5 ≈ the old raw threshold of 1) */
        val threshold = prefs.getInt("shakeLevel", 5).coerceIn(1, 10) / 5f
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val g = Math.sqrt(
                    (e.values[0] * e.values[0] + e.values[1] * e.values[1] +
                        e.values[2] * e.values[2]).toDouble(),
                ) - android.hardware.SensorManager.GRAVITY_EARTH
                if (Math.abs(g) > threshold) onShake()
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        sm.registerListener(listener, accel, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        sensorManager = sm
        shakeListener = listener
    }

    private fun stopShakeDetection() {
        shakeListener?.let { sensorManager?.unregisterListener(it) }
        shakeListener = null
    }

    /* during reading a shake silently re-arms the sleep timer; the visible
       "shaking" feedback lives in the settings page (for calibration) */
    private fun onShake() {
        if (speaking) scheduleSleepTimer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        /* a new reader supersedes any previous one (which stops its TTS) */
        active?.let { if (it !== this) it.finish() }
        active = this
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

        /* mark as recently read + load the cover for the media notification */
        intent.getStringExtra("slug")?.let { slug ->
            lifecycleScope.launch(Dispatchers.IO) {
                try { store.setLastRead(folder, slug, System.currentTimeMillis()) } catch (e: Exception) {}
                val cf = DownloadEngine.coverFile(this@ReaderActivity, slug)
                if (cf.exists()) {
                    try { coverBitmap = android.graphics.BitmapFactory.decodeFile(cf.path) } catch (e: Exception) {}
                    if (coverBitmap != null) runOnUiThread {
                        metaChapterIdx = -1   // force a metadata push carrying the art
                        if (speaking) updateMediaSessionState()
                    }
                }
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
                /* staggered: waits out any in-flight load, then scrolls within
                   the buffer if the chapter is loaded, else rebuilds */
                goTo(pos, savedParaFor(pos))
            }
            val startIdx = ch.ordered.indexOf(start).coerceAtLeast(0)
            goTo(startIdx, savedParaFor(startIdx))
        }

        /* Chapter loading is border-driven: openAt builds the initial window,
           then crossing into the LAST loaded chapter appends LOAD_BATCH more
           and crossing into the FIRST prepends LOAD_BATCH (loadReady gates
           this until the open placement has landed). */
        scroll.setOnScrollChangeListener { _, _, sy, _, oldY ->
            if (loading) return@setOnScrollChangeListener
            updateHeader()
            maybeLoadMore(sy, oldY)
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

        findViewById<TextView>(R.id.ttsPlayBtn).setOnClickListener { playButtonAction() }
        findViewById<TextView>(R.id.ttsSettingsBtn).setOnClickListener { showTtsSettings() }

        /* the notification's Pause/Play action broadcasts back to us */
        ttsToggleReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                runOnUiThread { playButtonAction() }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, ttsToggleReceiver,
            android.content.IntentFilter(TtsService.ACTION_TOGGLE),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        /* Bluetooth headset / media-button play & pause control the TTS.
           MediaSessionCompat wires up the manifest MediaButtonReceiver so
           the buttons reach us even backgrounded / on OEM ROMs. */
        mediaSession = android.support.v4.media.session.MediaSessionCompat(this, "reader-tts").apply {
            setCallback(object : android.support.v4.media.session.MediaSessionCompat.Callback() {
                override fun onPlay() {
                    runOnUiThread { if (!speaking) playButtonAction() }
                }
                override fun onPause() {
                    runOnUiThread { if (speaking) pauseTts() }
                }
                override fun onStop() {
                    runOnUiThread { if (speaking) pauseTts() }
                }
            })
            isActive = true
        }
        updateMediaSessionState()

        findViewById<TextView>(R.id.backBtn).setOnClickListener { leaveReader() }
        findViewById<TextView>(R.id.chaptersBtn).setOnClickListener {
            drawer.openDrawer(GravityCompat.END)
        }
        /* highlight + scroll-to-current runs however the drawer opens —
           the ≡ button or an edge swipe — as soon as it starts sliding in */
        drawer.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            private var prepared = false
            override fun onDrawerSlide(view: android.view.View, slideOffset: Float) {
                if (slideOffset > 0f && !prepared) {
                    prepared = true
                    drawerAdapter?.notifyDataSetChanged()
                    drawerList.post {
                        drawerList.setSelectionFromTop(
                            currentChapterIdx, (drawerList.height * 0.2f).toInt(),
                        )
                    }
                }
                if (slideOffset == 0f) prepared = false
            }
        })
        findViewById<TextView>(R.id.settingsBtn).setOnClickListener { showReaderSettings() }
        findViewById<TextView>(R.id.speechEditsBtn).setOnClickListener {
            startActivity(android.content.Intent(this, SpeechEditsActivity::class.java))
        }
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
    private var ttsConnecting = false   // an initTts bind is in flight (avoid double-binds)

    private val GOOGLE_TTS = "com.google.android.tts"

    /* Google TTS ONLY — never fall back to another engine (a voiceless
       default engine is how the voice list went empty before). A failed
       bind is retried with a growing delay instead. */
    private fun initTts(attempt: Int = 0) {
        ttsConnecting = true
        ttsInitState = "connecting to Google TTS" +
            (if (attempt > 0) " (retry $attempt)" else "")
        val listener = android.speech.tts.TextToSpeech.OnInitListener { st ->
            if (st == android.speech.tts.TextToSpeech.SUCCESS) {
                ttsReady = true
                ttsConnecting = false
                ttsInitState = "connected to Google TTS"
                curTtsLang = ""   // re-apply the language profile on next speak
                /* the retry just succeeded — restore the saved voice as soon
                   as its data finishes loading */
                voiceRestoreTicks = 0
                ensureSavedVoice()
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
                    } else {
                        ttsConnecting = false   // gave up retrying
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

    private var voiceRestoreTicks = 0

    /* After the engine (re)connects, the user's saved voice may not be present
       in t.voices yet — voice data loads lazily, so a just-connected engine
       briefly reports only the default. applyTtsConfig would silently fall back
       to the locale default and never recover. This polls briefly and applies
       the saved voice the moment it appears, so returning to the reader (or a
       successful engine retry) restores the exact voice the user picked. */
    private fun ensureSavedVoice() {
        val t = tts ?: return
        if (!ttsReady) return
        val lang = curTtsLang.ifEmpty { detectLang(text.text.toString().take(600)) }
        val saved = prefs.getString("ttsVoice:$lang", null) ?: return  // default → nothing to restore
        val present = try { t.voices?.any { it.name == saved } == true } catch (e: Exception) { false }
        if (present) {
            applyTtsConfig(lang)   // sets curTtsLang + the saved voice
            voiceRestoreTicks = 0
            return
        }
        if (voiceRestoreTicks >= 15) return   // ~12s, then give up quietly
        voiceRestoreTicks++
        android.os.Handler(mainLooper).postDelayed({ ensureSavedVoice() }, 800)
    }

    private fun paraStartOf(off: Int): Int {
        val body = text.text.toString()
        val o = off.coerceIn(0, body.length)
        return body.lastIndexOf('\n', (o - 1).coerceAtLeast(0)) + 1
    }

    /* scroll Y that places the line at `off` a small gap below the top edge,
       so a navigated-to chapter's heading isn't jammed under the header (the
       plain top-of-line position scrolls past the text's top padding) */
    private fun navScrollY(off: Int): Int {
        val layout = text.layout ?: return 0
        val line = layout.getLineForOffset(off.coerceIn(0, text.length()))
        return (layout.getLineTop(line) + text.totalPaddingTop - dp(16)).coerceAtLeast(0)
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

    private var mediaSession: android.support.v4.media.session.MediaSessionCompat? = null
    private var ttsToggleReceiver: android.content.BroadcastReceiver? = null
    private var lastNotifHeading = ""

    /* the "Chapter N: Title" line the notification shows */
    private fun currentHeading(): String =
        loadedChapters.firstOrNull { it.idx == currentChapterIdx }?.heading
            ?: titleBar.text.toString()

    private var coverBitmap: android.graphics.Bitmap? = null
    private var metaChapterIdx = -1

    /* char span [start, end) of the loaded chapter that contains `off` */
    private fun chapterSpanAt(off: Int): Pair<Int, Int>? {
        if (off < 0) return null
        val i = loadedChapters.indexOfLast { it.start <= off }
        if (i < 0) return null
        val start = loadedChapters[i].start
        val end = loadedChapters.getOrNull(i + 1)?.let { it.start - SEP.length } ?: text.length()
        return start to end
    }

    /* the MediaStyle notification's seekbar is fed by the session: duration =
       current chapter length, position = how far TTS has read into it. */
    private fun updateMediaSessionState() {
        val session = mediaSession ?: return
        val state = if (speaking) {
            android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
        } else {
            android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
        }
        val cursor = if (resumeCursor >= 0) resumeCursor else speakCursor
        val spanIdx = loadedChapters.indexOfLast { it.start <= cursor }
        val span = chapterSpanAt(cursor)
        val dur = span?.let { (it.second - it.first).toLong() } ?: 0L
        val pos = span?.let { (cursor - it.first).coerceIn(0, (it.second - it.first)).toLong() } ?: 0L

        /* metadata (duration + cover art) only when the chapter changes —
           speed 0 so the bar sits at the reported position between sentences */
        if (spanIdx != metaChapterIdx) {
            metaChapterIdx = spanIdx
            val heading = loadedChapters.getOrNull(spanIdx)?.heading ?: currentHeading()
            val meta = android.support.v4.media.MediaMetadataCompat.Builder()
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, heading)
                .putString(
                    android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST,
                    intent.getStringExtra("title") ?: "",
                )
                .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, dur)
            coverBitmap?.let {
                meta.putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            }
            session.setMetadata(meta.build())
        }
        session.setPlaybackState(
            android.support.v4.media.session.PlaybackStateCompat.Builder()
                .setActions(
                    android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY_PAUSE,
                )
                .setState(state, pos, 0f)
                .build(),
        )
    }

    /* the play/pause behavior, shared by the footer button and headset keys */
    private fun playButtonAction() {
        if (speaking) {
            pauseTts()
            return
        }
        if (resumeCursor >= 0) {
            startTtsFrom(resumeCursor)
            return
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
                return
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

    private fun startTtsFrom(off: Int) {
        if (!ttsReady) {
            initTts()   // engine dropped — rebind Google TTS for the next tap
            return
        }
        speakCursor = paraStartOf(off)
        speaking = true
        requestAudioFocus()   // makes us the media-button session in the background
        scheduleSleepTimer()  // the timer counts from each play
        startShakeDetection()
        /* foreground service keeps reading alive with the screen off */
        lastNotifHeading = currentHeading()
        TtsService.start(this, lastNotifHeading, true, mediaSession?.sessionToken, intent.getStringExtra("slug"))
        updateKeepAwake()
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
                appendChapters(LOAD_BATCH)
            } else {
                stopTts()
            }
            return
        }
        val (s0, s1) = sent
        /* about to enter the LAST loaded chapter: hold this sentence, append
           more chapters (appends never move existing text), then resume —
           reading always keeps a runway of loaded chapters ahead */
        val lastLoaded = loadedChapters.lastOrNull()
        if (!loading && lastLoaded != null && s0 >= lastLoaded.start &&
            nextIdx < (chapters?.ordered?.size ?: 0)
        ) {
            pendingSpeakContinue = true
            appendChapters(LOAD_BATCH)
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
        updateMediaSessionState()   // advance the notification's chapter progress
        /* normalize the spoken copy (URLs/emojis silenced, abbreviations
           expanded); the on-screen text and s0/s1 offsets are untouched */
        val toSpeak = cleanForSpeech(sentence, lang).ifBlank { " " }
        t.speak(toSpeak, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "novel")
    }

    /* Speech edits (managed on the Speech-edits screen) applied to every
       English sentence before it is spoken: user rules then the built-in
       defaults. Only the copy handed to the engine is rewritten, so on-screen
       text and s0/s1 offsets are untouched. Reloaded on resume so edits made
       on that screen take effect immediately. */
    private var speechRules: List<Pair<Regex, String>> = emptyList()
    private val collapseWs = Regex("\\s+")

    private fun reloadSpeechRules() {
        speechRules = try { SpeechEdits.enabledRules(this) } catch (e: Exception) { emptyList() }
    }

    private fun cleanForSpeech(sentence: String, lang: String): String {
        if (lang != "en" || speechRules.isEmpty()) return sentence
        return try {
            var s = "$sentence "   // trailing space so end-anchored rules fire
            for ((re, rep) in speechRules) {
                /* one bad rule (e.g. a replacement with an out-of-range $group)
                   must never crash the read — skip it and keep going */
                s = try { re.replace(s, rep) } catch (e: Exception) { s }
            }
            s.replace(collapseWs, " ").trim()
        } catch (e: Exception) {
            sentence
        }
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
        cancelSleepTimer()
        stopShakeDetection()
        tts?.stop()
        /* paused: keep the notification with a Play action (wake lock off) */
        TtsService.start(this, currentHeading(), false, mediaSession?.sessionToken, intent.getStringExtra("slug"))
        if (resumeCursor >= 0) speakCursor = resumeCursor
        clearHighlight()
        updateKeepAwake()
        updatePlayBtn()
    }

    private fun stopTts() {
        speaking = false
        cancelAutoScroll()
        cancelSleepTimer()
        stopShakeDetection()
        tts?.stop()
        TtsService.stop(this)
        abandonAudioFocus()
        clearHighlight()
        updateKeepAwake()
        updatePlayBtn()
    }

    /* Hold audio focus while a TTS session is active (kept through pauses so
       the headset PLAY button still routes back to us). Without it, headset
       media keys go to whatever app last had focus once we're backgrounded. */
    private var audioFocusReq: android.media.AudioFocusRequest? = null

    private fun requestAudioFocus() {
        if (audioFocusReq != null) return
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val req = android.media.AudioFocusRequest.Builder(
            android.media.AudioManager.AUDIOFOCUS_GAIN,
        ).setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        ).setOnAudioFocusChangeListener {
            /* another app took over (call, another player) → pause */
            if (it == android.media.AudioManager.AUDIOFOCUS_LOSS && speaking) {
                runOnUiThread { pauseTts() }
            }
        }.build()
        audioFocusReq = req
        try { am.requestAudioFocus(req) } catch (e: Exception) {}
    }

    private fun abandonAudioFocus() {
        val req = audioFocusReq ?: return
        audioFocusReq = null
        try {
            (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).abandonAudioFocusRequest(req)
        } catch (e: Exception) {}
    }

    /* Hold the screen on while TTS is reading, IF the user opted in
       (Settings → Reading). Cleared the moment reading pauses or stops so
       the display can dim normally again. */
    private fun updateKeepAwake() {
        val keep = speaking && prefs.getBoolean("keepAwake", false)
        if (keep) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /* pause is drawn as two Dingbats bars \u2014 U+23F8 falls back to the emoji
       font on many devices even with the text-presentation selector, so it
       would render in a different style and color than the play triangle */
    private fun updatePlayBtn() {
        findViewById<TextView>(R.id.ttsPlayBtn)?.text =
            if (speaking) "\u275a\u275a" else "\u25b6\ufe0e"
        updateMediaSessionState()
    }

    /* While this screen is live, remember we're in the middle of reading —
       the launcher resumes straight into it. Deliberately leaving (back or
       navigation → isFinishing) clears the marker; backgrounding or a
       process kill does not. */
    override fun onResume() {
        super.onResume()
        reloadSpeechRules()   // pick up any changes made on the Speech-edits screen
        val dir = intent.getStringExtra("dir") ?: return
        val slug = intent.getStringExtra("slug") ?: return
        val o = org.json.JSONObject()
        o.put("dir", dir)
        o.put("title", intent.getStringExtra("title") ?: dir)
        o.put("slug", slug)
        prefs.edit().putString("lastReading", o.toString()).apply()

        /* coming back into the reader: recover the saved TTS voice. If the
           engine dropped while we were away, rebind it (its success path then
           restores the voice); otherwise re-apply straight away. Never rebind
           mid-playback — that would cut the current utterance. */
        voiceRestoreTicks = 0
        if (!speaking && !ttsConnecting && (tts == null || !ttsReady)) initTts() else ensureSavedVoice()
    }

    override fun onPause() {
        if (isFinishing) prefs.edit().remove("lastReading").apply()
        super.onPause()
    }

    /* Back / ← : while TTS is playing, keep this instance ALIVE (playback
       continues) and just bring the chapter list forward; otherwise finish
       normally. */
    private fun leaveReader() {
        if (speaking) {
            startActivity(
                android.content.Intent(this, ChapterListActivity::class.java)
                    .putExtra("dir", intent.getStringExtra("dir"))
                    .putExtra("title", intent.getStringExtra("title"))
                    .putExtra("slug", intent.getStringExtra("slug"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        } else {
            finish()
        }
    }

    override fun onBackPressed() {
        val drawer = findViewById<DrawerLayout>(R.id.readerDrawer)
        if (drawer.isDrawerOpen(GravityCompat.END)) {
            drawer.closeDrawer(GravityCompat.END)
            return
        }
        leaveReader()
    }

    override fun onDestroy() {
        if (active === this) active = null
        cancelSleepTimer()
        stopShakeDetection()
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
        abandonAudioFocus()
        try { mediaSession?.release() } catch (e: Exception) {}
        mediaSession = null
        try { ttsToggleReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        ttsToggleReceiver = null
        TtsService.stop(this)
        super.onDestroy()
    }

    /* ---- full-page reader settings (styled like the main app Settings) ---- */
    private fun showReaderSettings(voiceOnly: Boolean = false) {
        val ctx = this
        val dialog = android.app.Dialog(ctx, android.R.style.Theme_DeviceDefault_NoActionBar)
        val outer = ScrollView(ctx).apply {
            setBackgroundColor(getColor(R.color.bg))
            isFillViewport = true
        }
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        outer.addView(root)

        /* header: back + title */
        val header = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(ctx).apply {
                text = "←"; textSize = 24f; setTextColor(getColor(R.color.fg))
                setPadding(0, dp(2), dp(14), dp(2))
                isClickable = true; isFocusable = true
                setOnClickListener { dialog.dismiss() }
            },
        )
        header.addView(
            TextView(ctx).apply {
                text = if (voiceOnly) "Voice settings" else "Reading settings"; textSize = 22f
                setTextColor(getColor(R.color.fg)); setTypeface(null, android.graphics.Typeface.BOLD)
            },
        )
        root.addView(header)

        fun card(): android.widget.LinearLayout {
            val c = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_settings_card)
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
            root.addView(
                c,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(14) },
            )
            return c
        }
        fun cardTitle(c: android.widget.LinearLayout, t: String) = c.addView(
            TextView(ctx).apply {
                text = t; textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.fg))
            },
        )
        fun hint(c: android.widget.LinearLayout, t: String) = c.addView(
            TextView(ctx).apply {
                text = t; textSize = 12f; setTextColor(getColor(R.color.muted))
                setPadding(0, dp(6), 0, 0)
            },
        )

        /* ── Display: language + font (top menu only) ── */
        if (!voiceOnly) {
        val disp = card()
        cardTitle(disp, "Display")
        if (chapters?.translated?.isNotEmpty() == true) {
            disp.addView(
                TextView(ctx).apply {
                    text = if (english) "Switch to Tiếng Việt" else "Switch to English"
                    textSize = 15f; setTextColor(getColor(R.color.accent))
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, dp(12), 0, dp(6))
                    isClickable = true; isFocusable = true
                    setOnClickListener { dialog.dismiss(); toggleLanguage() }
                },
            )
        }
        val fontRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        fontRow.addView(
            TextView(ctx).apply {
                text = "Font size"; textSize = 15f; setTextColor(getColor(R.color.fg))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                )
            },
        )
        val fontVal = TextView(ctx).apply {
            text = fontSp.toInt().toString(); textSize = 15f; setTextColor(getColor(R.color.fg))
            minWidth = dp(36); gravity = android.view.Gravity.CENTER
        }
        /* one-line preview of the current size (ellipsized, never wrapped) */
        val fontSample = TextView(ctx).apply {
            text = "The quick brown fox jumps over the lazy dog"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
            setTextColor(getColor(R.color.muted))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(10), 0, 0)
        }
        fun fontBtn(t: String, d: Float) = TextView(ctx).apply {
            text = t; textSize = 22f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.accent)); setPadding(dp(16), dp(2), dp(16), dp(2))
            isClickable = true; isFocusable = true
            setOnClickListener {
                adjustFont(d)
                fontVal.text = fontSp.toInt().toString()
                fontSample.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
            }
        }
        fontRow.addView(fontBtn("−", -1f))
        fontRow.addView(fontVal)
        fontRow.addView(fontBtn("+", +1f))
        disp.addView(fontRow)
        disp.addView(fontSample)
        }

        /* ── Reading aloud: voice + rate + pitch (bottom / voice menu only) ── */
        if (voiceOnly) {
        val aloud = card()
        val lang = curTtsLang.ifEmpty { detectLang(text.text.toString().take(600)) }
        cardTitle(aloud, "Reading aloud — " + if (lang == "vi") "Tiếng Việt" else "English")
        aloud.addView(
            TextView(ctx).apply {
                text = "Voice"; textSize = 13f; setTextColor(getColor(R.color.muted))
                setPadding(0, dp(12), 0, dp(4))
            },
        )
        val voices = try {
            val all = tts?.voices.orEmpty()
            val forLang = all.filter { it.locale.language == lang }
            (forLang.ifEmpty { all }).sortedBy { it.name }
        } catch (e: Exception) { emptyList() }
        val spinner = android.widget.Spinner(ctx)
        spinner.adapter = android.widget.ArrayAdapter(
            ctx, android.R.layout.simple_spinner_dropdown_item,
            listOf("Default") + voices.map { "${it.locale} — ${it.name}" },
        )
        val savedVoice = prefs.getString("ttsVoice:$lang", null)
        val savedIdx = voices.indexOfFirst { it.name == savedVoice }
        spinner.setSelection(if (savedIdx >= 0) savedIdx + 1 else 0)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (pos == 0) prefs.edit().remove("ttsVoice:$lang").apply()
                else prefs.edit().putString("ttsVoice:$lang", voices[pos - 1].name).apply()
                applyTtsConfig(lang)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        aloud.addView(spinner)
        if (voices.isEmpty()) hint(aloud, "No voices found — open the ⚙ while stopped to reconnect Google TTS.")

        fun slider(title: String, key: String) {
            aloud.addView(
                TextView(ctx).apply {
                    text = title; textSize = 13f; setTextColor(getColor(R.color.muted))
                    setPadding(0, dp(12), 0, dp(4))
                },
            )
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            fun get() = prefs.getFloat(key, 1f)
            val valueTv = TextView(ctx).apply {
                textSize = 14f; setTextColor(getColor(R.color.fg)); text = "%.2f".format(get())
                minWidth = dp(46); setPadding(dp(8), 0, 0, 0)
            }
            val seek = android.widget.SeekBar(ctx).apply {
                max = 50; progress = ((get() - 0.5f) * 20f + 0.5f).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                )
            }
            fun applyVal(v: Float, fromSeek: Boolean) {
                val c = (Math.round(v * 20f) / 20f).coerceIn(0.5f, 3f)
                prefs.edit().putFloat(key, c).apply()
                valueTv.text = "%.2f".format(c)
                if (!fromSeek) seek.progress = ((c - 0.5f) * 20f + 0.5f).toInt()
                applyTtsConfig(lang)
            }
            seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, pr: Int, fromUser: Boolean) {
                    if (fromUser) applyVal(0.5f + pr / 20f, true)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
            fun step(t: String, d: Float) = TextView(ctx).apply {
                text = t; textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.accent)); setPadding(dp(12), dp(4), dp(12), dp(4))
                isClickable = true; isFocusable = true
                setOnClickListener { applyVal(get() + d, false) }
            }
            row.addView(step("<", -0.05f)); row.addView(seek)
            row.addView(step(">", +0.05f)); row.addView(valueTv)
            aloud.addView(row)
        }
        slider("Rate", "ttsRate:$lang")
        slider("Pitch", "ttsPitch:$lang")
        }

        /* saves for the timer/shake cards (no-ops on the voice menu) */
        var saveSleep: () -> Unit = {}
        var saveShake: () -> Unit = {}
        var stopShakeTest: () -> Unit = {}

        if (!voiceOnly) {
        /* ── Sleep timer ── */
        val sleep = card()
        cardTitle(sleep, "Sleep timer")
        val sleepInput = android.widget.EditText(ctx).apply {
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "0 = off"
            setTextColor(getColor(R.color.fg)); setHintTextColor(getColor(R.color.muted))
            textSize = 14f
            val cur = prefs.getInt("sleepMinutes", 0)
            if (cur > 0) setText(cur.toString())
        }
        val sleepLp = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) }
        sleep.addView(sleepInput, sleepLp)
        hint(sleep, "Stop reading after this many minutes. The timer restarts each time you press play. Blank or 0 turns it off.")
        saveSleep = {
            val mins = sleepInput.text.toString().toIntOrNull() ?: 0
            prefs.edit().putInt("sleepMinutes", mins.coerceAtLeast(0)).apply()
            if (speaking) scheduleSleepTimer()
        }

        /* ── Shake to reset ── */
        val shake = card()
        cardTitle(shake, "Shake to reset")
        val shakeCheck = android.widget.CheckBox(ctx).apply {
            text = "Reset the sleep timer when I shake the phone"
            textSize = 14f; setTextColor(getColor(R.color.fg))
            setPadding(dp(6), dp(10), 0, 0)
            isChecked = prefs.getBoolean("shakeEnabled", false)
        }
        shake.addView(shakeCheck)
        val startLevel = prefs.getInt("shakeLevel", 5).coerceIn(1, 10)
        val shakeLabel = TextView(ctx).apply {
            text = "Shake threshold: $startLevel"; textSize = 13f
            setTextColor(getColor(R.color.muted)); setPadding(0, dp(12), 0, dp(4))
        }
        shake.addView(shakeLabel)
        val shakeBar = android.widget.SeekBar(ctx).apply {
            min = 1; max = 10; progress = startLevel
            // visible notch at each whole step so the slider reads as integer 1–10
            tickMark = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.seekbar_tick)
        }
        shake.addView(
            shakeBar,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        shakeBar.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, u: Boolean) {
                    shakeLabel.text = "Shake threshold: ${p.coerceAtLeast(1)}"
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            },
        )
        hint(shake, "1 = the gentlest nudge (most sensitive), 10 = a firm shake. Shake the phone now to test — the status below flashes red when a shake passes this level.")
        /* live tester: flashes here so the threshold can be calibrated while
           this page is open (uses the slider's current level, not the saved one) */
        val shakeStatus = TextView(ctx).apply {
            text = "shaking — sleep timer reset"; textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.err)); setPadding(0, dp(12), 0, 0)
            visibility = android.view.View.INVISIBLE   // reserve space, no jump
        }
        shake.addView(shakeStatus)
        val sm = getSystemService(SENSOR_SERVICE) as? android.hardware.SensorManager
        val accel = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        if (sm != null && accel != null) {
            var shownAt = 0L
            val listener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(e: android.hardware.SensorEvent) {
                    val g = Math.sqrt(
                        (e.values[0] * e.values[0] + e.values[1] * e.values[1] +
                            e.values[2] * e.values[2]).toDouble(),
                    ) - android.hardware.SensorManager.GRAVITY_EARTH
                    val thr = shakeBar.progress.coerceAtLeast(1) / 5f
                    if (Math.abs(g) > thr) {
                        val now = System.currentTimeMillis()
                        if (now - shownAt < 2500) return
                        shownAt = now
                        shakeStatus.visibility = android.view.View.VISIBLE
                        shakeStatus.postDelayed(
                            { shakeStatus.visibility = android.view.View.INVISIBLE }, 2000,
                        )
                    }
                }
                override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
            }
            sm.registerListener(listener, accel, android.hardware.SensorManager.SENSOR_DELAY_GAME)
            stopShakeTest = { sm.unregisterListener(listener) }
        }
        saveShake = {
            prefs.edit()
                .putBoolean("shakeEnabled", shakeCheck.isChecked)
                .putInt("shakeLevel", shakeBar.progress.coerceIn(1, 10))
                .apply()
            /* re-arm with the new config if currently reading */
            if (speaking) { stopShakeDetection(); startShakeDetection() }
        }
        }

        /* persist timer + shake settings and stop the tester when the page closes */
        dialog.setOnDismissListener { saveSleep(); saveShake(); stopShakeTest() }

        dialog.setContentView(outer)
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        )
        dialog.show()
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
        /* Only a real tap on the spinner may overwrite the saved voice. A
           programmatic (re)fill selects "Default" whenever voices haven't
           loaded yet — without this guard that fill fires onItemSelected and
           WIPES ttsVoice:$lang, so the pick could never be recovered. */
        var userPicked = false
        @Suppress("ClickableViewAccessibility")
        spinner.setOnTouchListener { _, _ -> userPicked = true; false }
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
                if (!userPicked) return   // ignore programmatic fills
                userPicked = false
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
                    applyTtsConfig(lang)   // voices arrived → apply the saved voice now
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

    /* Jump within the ALREADY-LOADED buffer: scroll straight to the chapter
       (and its saved paragraph) without reloading anything. Returns false
       when the chapter isn't loaded — caller falls back to openAt. */
    private fun jumpToLoaded(pos: Int, targetPara: Int): Boolean {
        if (loading) return false
        val lc = loadedChapters.firstOrNull { it.idx == pos } ?: return false
        val layout = text.layout ?: return false
        stopTts()
        resumeCursor = -1
        clearTextSelection()
        val off = if (targetPara > 0) offsetOfPara(lc.start, targetPara) else lc.start
        val y = navScrollY(off)
        /* this programmatic jump must not itself trigger a prepend — the small
           top gap makes it look like an upward scroll into the first chapter.
           Gate maybeLoadMore off until the placement scroll has settled. */
        loadReady = false
        scroll.scrollTo(0, y.coerceAtLeast(0))
        scroll.smoothScrollBy(0, 0)   // kill any in-flight fling
        currentChapterIdx = lc.idx
        saveLastChapter(lc.idx)
        updateHeader()
        scroll.post { loadReady = true }
        return true
    }

    /* jump to a chapter: load it, append the next, prepend the previous.
       targetPara scrolls to that paragraph of the opened chapter (used by
       the language toggle to keep the reading position). */
    /* how many chapters to (pre)load in each direction / batch */
    private val LOAD_BATCH = 20

    private var gotoJob: kotlinx.coroutines.Job? = null

    /* Navigate to a chapter + paragraph and recover the scroll there, STAGGERED
       so it never fires while chapters are still being loaded: wait for any
       in-flight load to settle first, then jump within the buffer (if the
       chapter is already loaded) or rebuild the window. Used for both the
       app-restart restore and chapter-list picks (including re-picking the
       current chapter to return to where we left off). */
    private fun goTo(pos: Int, targetPara: Int) {
        /* picking the chapter TTS is already reading → keep reading; nothing
           new to load, so don't stop or scroll away from the spoken line */
        if (speaking) {
            val cursor = if (resumeCursor >= 0) resumeCursor else speakCursor
            val reading = loadedChapters.lastOrNull { it.start <= cursor }?.idx
            if (reading == pos) return
        }
        gotoJob?.cancel()
        gotoJob = lifecycleScope.launch {
            var waited = 0
            while (loading && waited < 120) { kotlinx.coroutines.delay(50); waited++ }
            if (!jumpToLoaded(pos, targetPara)) openAt(pos, targetPara)
        }
    }

    /* Open a chapter by loading it plus LOAD_BATCH chapters AHEAD (forward
       only): the opened chapter is the FIRST in the buffer, at offset 0, so the
       scroll target is just its paragraph — a small offset that needs no
       placement tricks. Previous chapters load lazily when the reader scrolls
       up into the top chapter (see maybeLoadMore). */
    private fun openAt(pos: Int, targetPara: Int = 0) {
        val ch = chapters ?: return
        if (loading || ch.ordered.isEmpty()) return
        stopTts()
        resumeCursor = -1
        loading = true
        loadReady = false
        val p = pos.coerceIn(0, ch.ordered.size - 1)
        lifecycleScope.launch {
            val lastToLoad = (p + LOAD_BATCH).coerceAtMost(ch.ordered.size - 1)
            loadedChapters.clear()
            val sb = StringBuilder()
            var targetBodyLen = 0
            for (i in p..lastToLoad) {
                val body = readAt(i) ?: continue   // skip a chapter that won't read
                if (sb.isNotEmpty()) sb.append(SEP)
                val start = sb.length
                sb.append(body)
                loadedChapters.add(LoadedChapter(i, start, headingOf(body)))
                if (i == p) targetBodyLen = body.length
            }
            firstIdx = p
            nextIdx = (loadedChapters.lastOrNull()?.idx ?: (p - 1)) + 1
            text.setText(sb, TextView.BufferType.EDITABLE)
            clearTextSelection()   // no stray insertion cursor to auto-scroll
            currentChapterIdx = p
            titleBar.text = loadedChapters.firstOrNull { it.idx == p }?.heading ?: ""
            saveLastChapter(p)

            /* the opened chapter starts at offset 0, so scroll straight to its
               paragraph once the text has laid out (a frame or two) */
            fun place(attempt: Int) {
                val layout = text.layout
                if ((layout == null || layout.text.length != text.text.length) && attempt < 10) {
                    scroll.post { place(attempt + 1) }
                    return
                }
                val targetOff =
                    if (targetPara > 0 && targetBodyLen > 0) offsetOfPara(0, targetPara) else 0
                scroll.scrollTo(0, navScrollY(targetOff))
                loading = false
                loadReady = true
                updateHeader()
                if (pendingSpeakAfterOpen) {
                    pendingSpeakAfterOpen = false
                    startTtsFrom(targetOff)
                }
            }
            scroll.post { place(0) }
        }
    }

    /* ---- border-driven chapter loading ----
       openAt loads the opened chapter + LOAD_BATCH ahead; from there scrolling
       tops the buffer up LOAD_BATCH at a time: reaching the LAST loaded chapter
       appends more, scrolling UP into the FIRST loaded chapter prepends the
       previous batch (never during speech, since prepends shift coordinates). */

    private var loadReady = false

    private fun maybeLoadMore(newY: Int, oldY: Int) {
        if (!loadReady || loading) return
        val ch = chapters ?: return
        val first = loadedChapters.firstOrNull() ?: return
        val last = loadedChapters.lastOrNull() ?: return
        val more = nextIdx < ch.ordered.size
        /* inside the last loaded chapter, or content too short to scroll */
        if (more && (currentChapterIdx >= last.idx ||
                (text.height > 0 && text.height < scroll.height * 3 / 2))
        ) {
            appendChapters(LOAD_BATCH)
            return
        }
        /* SCROLLING UP into the top loaded chapter → load the previous
           LOAD_BATCH at once. Requires an actual upward scroll (newY < oldY) so
           it never fires on open (the opened chapter starts as the first one).
           Prepends shift coordinates, so never while TTS drives the scroll. */
        if (!speaking && firstIdx > 0 && newY < oldY && currentChapterIdx <= first.idx) {
            prependChapters(LOAD_BATCH)
        }
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
            scroll.post { updateHeader() }
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
                    /* a fling still in flight targets PRE-shift coordinates;
                       left alone it yanks the viewport into the inserted
                       chapters and cascades further prepends — replace its
                       trajectory with a zero-delta scroll to kill it */
                    scroll.smoothScrollBy(0, 0)
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
