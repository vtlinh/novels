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

   Opening a chapter loads a small window around it — LOAD_BATCH either side
   — and the buffer is topped up from there: reaching the last loaded chapter
   appends the next ones, scrolling back into the first prepends the previous
   ones (scroll position preserved), so reading is seamless in both
   directions.

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

        /* Which chapter to reopen for this novel: the one TTS stopped in when
           there is one, else the last chapter the reader showed.

           lastCh alone is not trustworthy for this. It is written from the
           chapter at the TOP OF THE VIEWPORT, and TTS parks the spoken line a
           fifth of a page below that — so pausing early in a chapter rewrites
           lastCh to the PREVIOUS one. Reopening then landed on a chapter the
           saved position doesn't belong to, and the restore found nothing. */
        fun resumeChapter(ctx: android.content.Context, slug: String): String? {
            val p = ctx.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
            val tts = p.getString("ttsPos:$slug", null)
                ?.substringBefore('|')?.takeIf { it.isNotEmpty() }
            val last = p.getString("lastCh:$slug", null)
            if (tts == null) return last
            if (last == null) return tts
            /* Whichever is more recent. Preferring the read-aloud spot
               unconditionally meant an evening of scroll-reading was thrown
               away by a TTS position from days earlier: reopening the novel
               jumped back fifty chapters, and the chapter list highlighted
               that one too, with hand-picking the only way out. */
            return if (p.getLong("lastChAt:$slug", 0L) > p.getLong("ttsPosAt:$slug", 0L)) last else tts
        }
    }

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val store by lazy { DownloadStore(this) }

    private var chapters: ChapterListActivity.Companion.Chapters? = null
    private var treeUri: Uri? = null
    private var firstIdx = 0   // first loaded chapter
    private var nextIdx = 0    // next chapter to append

    /* each loaded chapter's character offset in the text + its heading line,
       so the header can show the chapter actually being READ */
    /* `idx` is a position in the current listing, and a rename pass can move
       it under us — resyncIfRenamed carries the whole buffer across by name */
    private class LoadedChapter(var idx: Int, var start: Int, val heading: String)
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
            /* While reading aloud, "the chapter I'm on" is the one being
               SPOKEN — saveTtsPos owns lastCh then. The viewport top sits a
               fifth of a page above the spoken line (scrollToSpoken), so it
               still reports the PREVIOUS chapter for the first screenful of a
               new one; letting it write lastCh made the app reopen a chapter
               the TTS position doesn't belong to, and the restore then found
               no saved spot and stayed at the top. */
            if (!speaking) saveLastChapter(cur.idx)
        }
        /* keep the reading notification on the current chapter */
        if (speaking && cur.heading.isNotEmpty() && cur.heading != lastNotifHeading) {
            lastNotifHeading = cur.heading
            TtsService.start(this, cur.heading, true, mediaSession?.sessionToken, intent.getStringExtra("slug"))
        }
        /* Scroll position is deliberately NOT tracked: the only position worth
           returning to is where TTS stopped (saveTtsPos). Scrolling around a
           chapter no longer leaves a mark that could compete with it. */
    }

    /* the text of the paragraph containing `off`, capped — long enough to
       identify the paragraph, short enough to keep in prefs */
    private fun anchorOf(off: Int): String {
        val body = text.text
        val s = paraStartOf(off)
        val nl = body.toString().indexOf('\n', s)
        val e = if (nl == -1) body.length else nl
        return body.subSequence(s, e).toString().take(160)
    }

    /* Where to land when (re)opening chapter idx from a restart or a chapter
       pick: the spot TTS stopped at, if it stopped in THIS chapter. Any other
       chapter opens at the top — nothing else is tracked. */
    private fun restoreTargetFor(idx: Int): Pair<Int, String?> {
        val slug = intent.getStringExtra("slug")
        val name = chapters?.ordered?.getOrNull(idx)
        if (slug != null && name != null) {
            prefs.getString("ttsPos:$slug", null)?.let { saved ->
                /* Match on the chapter NUMBER, not the raw filename: the same
                   chapter can be stored as "Chapter 412.txt" or with a title
                   suffix, and an exact-string check silently yields "no saved
                   spot" — which looks identical to landing at the top. */
                if (sameChapter(saved.substringBefore('|'), name)) {
                    return Pair(
                        saved.substringAfter('|').toIntOrNull() ?: 0,
                        prefs.getString("ttsParaText:$slug", null),
                    )
                }
            }
        }
        return Pair(0, null)
    }

    /* two chapter filenames denote the same chapter when their Chapter
       numbers agree (names may carry a title suffix) */
    private fun sameChapter(a: String, b: String): Boolean {
        if (a == b) return true
        val re = ChapterListActivity.CHAPTER_RE
        val ma = re.find(a) ?: return false
        val mb = re.find(b) ?: return false
        val na = ma.groupValues.getOrNull(1) ?: return false
        if (na != mb.groupValues.getOrNull(1)) return false
        /* Number alone was too loose. The rename pass parks a chapter the site
           dropped as "Chapter 70 (unlisted).txt" right beside the listed
           "Chapter 70.txt", and merged files are "Chapter 70-71.txt" — all
           three share the number while being different text, so the saved spot
           matched a chapter it never belonged to and restored to a paragraph
           index in the wrong one. The range and any suffix have to agree too;
           only a legacy title suffix is allowed to differ. */
        if (ma.groupValues.getOrNull(2) != mb.groupValues.getOrNull(2)) return false
        fun marked(s: String) = s.contains("(unlisted")
        return marked(a) == marked(b)
    }

    /* The chapter list is read once and never reloaded, so two things can make
       the name at an index no longer the chapter it was. A rename pass moves
       every file after an inserted or dropped chapter — and it has already
       corrected the saved spot, so writing this snapshot's name back would
       undo that correction and resume in the wrong chapter. And a spot we
       couldn't find on open must not be replaced by wherever we landed
       instead; that turns "can't restore" into "lost for good". */
    private var chaptersEpoch = 0L

    /* How far either side of its old position a loaded chapter is looked for
       after a rename. A pass that inserts or drops chapters moves everything
       after that point by the same small amount; anything larger is not a
       shift we should be guessing at. */
    private val SHIFT_SEARCH = 8
    private var spotLost = false

    private fun spotWritable(): Boolean =
        !spotLost &&
            DownloadEngine.renameEpochOf(intent.getStringExtra("slug") ?: "") == chaptersEpoch

    /* Re-read the listing when a rename has moved it under us, and take the
       new epoch with it. Without this the guard above was a one-way latch:
       the first rename of this novel — a download of it, a status sweep —
       stopped the reading spot being recorded for the whole life of the
       reader, and while TTS is playing the reader is deliberately kept alive,
       so the obvious "leave and come back" gesture never happened. An hour of
       listening recorded nothing and resumed at the pre-rename chapter. */
    private var resyncing = false

    private fun resyncIfRenamed() {
        val slug = intent.getStringExtra("slug") ?: return
        val now = DownloadEngine.renameEpochOf(slug)
        if (now == chaptersEpoch) return
        /* One at a time. onResume was the only caller and could hardly
           overlap itself, but an append refused for a stale listing asks on
           every scroll event, and every ask is a folder listing. */
        if (resyncing) return
        resyncing = true
        val job = lifecycleScope.launch {
            val dir = intent.getStringExtra("dir") ?: return@launch
            val tree = treeUri ?: return@launch
            val folderKey = prefs.getString("tree", null) ?: return@launch
            val fresh = withContext(Dispatchers.IO) {
                val order = try { store.getChapterOrder(folderKey, slug) } catch (e: Exception) { null } ?: emptyMap()
                try {
                    ChapterListActivity.chapterNames(this@ReaderActivity, tree, dir, order, slug)
                } catch (e: Exception) { null }
            }
            if (fresh == null || fresh.ordered.isEmpty()) return@launch
            /* The text on screen, the drawer position and the chapter we are
               about to name a saved spot after are all POSITIONS in the old
               listing. Swapping the listing without moving them re-opens the
               write gate over a mismatch: the spot is then saved under the
               name one chapter along — overwriting the correction the rename
               pass just made — a drawer tap scrolls to a neighbour's text
               without reloading, and the next append reads a chapter already
               on screen.

               Matching the old NAMES against the new listing cannot find the
               shift: both listings are "Chapter 1.txt".."Chapter N.txt", so
               that map is the identity and passes while proving nothing. The
               heading inside the file is the chapter itself. Look for the
               shift that puts the buffer back over its own text — the ends are
               enough, and the answer is almost always 0 or ±1. */
            /* Not while a load is in flight. openAt clears the buffer and
               then suspends on every chapter it reads, so this can land in the
               middle of one — swapping the listing under indices it is still
               stamping in, and (with the buffer momentarily empty) taking the
               epoch with nothing whatsoever verified. The next onResume, or
               the next rename, comes back to it. */
            if (loading) return@launch
            val loaded = loadedChapters.toList()
            val probes = listOfNotNull(loaded.firstOrNull(), loaded.lastOrNull())
            /* Nothing loaded to check the new listing against, and the first
               open has not run yet: onCreate is still walking the folder and
               will assign ITS listing to `chapters` when it finishes. Adopting
               here installs a fresh listing that a pre-rename one overwrites
               moments later — and takes the new epoch with it, so those stale
               names are then treated as trustworthy and the reading spot is
               saved under one of them. That is exactly the confusion the epoch
               exists to prevent, and it would be reached having verified
               nothing at all. Leave the epoch stale; the next resume (or the
               next rename) comes back to it over a buffer there is something
               to measure against. */
            if (probes.isEmpty() && !openedOnce) return@launch
            var shift: Int? = 0
            if (probes.isNotEmpty()) {
                shift = null
                val order = ArrayList<Int>(2 * SHIFT_SEARCH + 1)
                order.add(0)
                for (d in 1..SHIFT_SEARCH) { order.add(d); order.add(-d) }
                for (d in order) {
                    val ok = probes.all { lc ->
                        val i = lc.idx + d
                        i >= 0 && i < fresh.ordered.size && lc.heading in headingsIn(fresh, i)
                    }
                    if (ok) { shift = d; break }
                }
            }
            /* Nothing lines up — the chapters on screen were dropped, or moved
               further than we look. The old listing at least agrees with the
               indices we hold, so keep both and leave the gate shut until an
               open rebuilds them together. */
            val d = shift ?: return@launch
            /* Re-checked HERE, not only at the top. headingsIn suspends on a
               SAF read for every candidate shift, and openAt / appendChapters
               / prependChapters all mutate the buffer from their own
               coroutines meanwhile — so a guard before the probes does not
               cover the case it was written for. If the buffer is not still
               the one we measured, element for element, the shift we worked
               out does not describe it: leave both alone and let the next
               resume do it properly. */
            if (loading ||
                loadedChapters.size != loaded.size ||
                loadedChapters.indices.any { loadedChapters[it] !== loaded[it] }
            ) {
                return@launch
            }
            chapters = fresh
            drawerAdapter?.clear()
            drawerAdapter?.addAll(fresh.ordered.map { it.removeSuffix(".txt") })
            drawerAdapter?.notifyDataSetChanged()
            for (lc in loadedChapters) lc.idx += d
            if (currentChapterIdx >= 0) currentChapterIdx += d
            loadedChapters.firstOrNull()?.let { firstIdx = it.idx }
            loadedChapters.lastOrNull()?.let { nextIdx = it.idx + 1 }
            chaptersEpoch = now
        }
        job.invokeOnCompletion { resyncing = false }
    }

    /* Asking for a resync from the LOAD path (an append refused because the
       listing moved) is not the same as asking on resume: a scroll at the end
       of the buffer produces the ask dozens of times a second, and the resync
       cannot always do anything about it — a shift it can't find leaves the
       epoch stale, so the next event asks again. Each ask is a folder listing;
       rate-limit them. onResume still asks directly. */
    private var lastLoadPathResync = 0L
    private val RESYNC_ASK_MS = 2000L

    private fun askResync() {
        val at = android.os.SystemClock.elapsedRealtime()
        if (at - lastLoadPathResync < RESYNC_ASK_MS) return
        lastLoadPathResync = at
        resyncIfRenamed()
    }

    /* remember the chapter being read, per novel — the chapter list reopens
       scrolled to (and highlighting) it */
    private fun saveLastChapter(idx: Int) {
        if (!spotWritable()) return
        intent.getStringExtra("slug")?.let { slug ->
            chapters?.ordered?.getOrNull(idx)?.let { name ->
                prefs.edit()
                    .putString("lastCh:$slug", name)
                    .putLong("lastChAt:$slug", System.currentTimeMillis())
                    .apply()
            }
        }
    }
    @Volatile private var loading = false
    /* set by the first openAt — until then the buffer is empty because nothing
       has built it yet, which is a different thing from a buffer that came out
       empty (see resyncIfRenamed) */
    private var openedOnce = false
    private var english = true
    private var fontSp = 16f

    /* ---- text-to-speech ---- */
    private var tts: android.speech.tts.TextToSpeech? = null

    /* Moves whenever what we are saying changes — a new sentence, or anything
       that throws the current one away. A report carrying an older tag is a
       report about an utterance we already discarded; see Utterance. */
    private var speechGen = 0L
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
        /* no "Loading…" placeholder — the chapter renders almost immediately,
           and a blank background reads cleaner than a flash of loading text */

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
            /* taken BEFORE the read: a rename landing during it leaves us with
               a list that is already behind, and the mismatch is what tells
               the save path to keep its hands off the corrected spot */
            chaptersEpoch = DownloadEngine.renameEpochOf(intent.getStringExtra("slug") ?: "")
            chapters = withContext(Dispatchers.IO) {
                val slug = intent.getStringExtra("slug")
                val order = slug?.let {
                    try { store.getChapterOrder(folder, it) } catch (e: Exception) { null }
                } ?: emptyMap()
                /* Listing the folder crosses a binder to the storage provider,
                   and it throws outright when the persisted grant is gone or a
                   cursor window overflows. Uncaught in a coroutine that is
                   nothing but a crash on the ordinary act of opening a novel —
                   the resync path already wraps the identical call. */
                try {
                    ChapterListActivity.chapterNames(this@ReaderActivity, treeUri!!, dirName, order, slug)
                } catch (e: Exception) { null }
            }
            val ch = chapters ?: run {
                titleBar.text = novelTitle
                android.widget.Toast.makeText(
                    this@ReaderActivity, "Could not read that novel's folder", android.widget.Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
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
                /* an explicit pick IS a new place, so start recording again */
                spotLost = false
                /* staggered: waits out any in-flight load, then scrolls within
                   the buffer if the chapter is loaded, else rebuilds. Picking
                   the chapter TTS stopped at recovers that exact spot. */
                val t = restoreTargetFor(pos)
                goTo(pos, t.first, t.second)
            }
            /* Not found means the saved chapter is gone — a dedupe removed it,
               or it was deleted outside the app. Coercing -1 to 0 opened
               chapter 1 of a 3000-chapter novel with no explanation, and then
               saved THAT as the new place, so the real one was unrecoverable.
               Try the chapter number first, and if it truly isn't here, land
               at the top without overwriting what we couldn't honour. */
            var startIdx = ch.ordered.indexOf(start)
            if (startIdx < 0) startIdx = ch.ordered.indexOfFirst { sameChapter(start, it) }
            if (startIdx < 0) {
                startIdx = 0
                spotLost = true
                android.widget.Toast.makeText(
                    this@ReaderActivity,
                    "The chapter you left off at is no longer here — opened at the start. " +
                        "Pick a chapter to set a new place.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            val t = restoreTargetFor(startIdx)
            goTo(startIdx, t.first, t.second)
        }

        /* Chapter loading is border-driven: openAt builds the initial window,
           then crossing into the LAST loaded chapter appends LOAD_BATCH more
           and crossing into the FIRST prepends LOAD_BATCH (loadReady gates
           this until the open placement has landed). */
        scroll.setOnScrollChangeListener { _, _, sy, _, oldY ->
            noteScrollActivity()   // keep the settle clock running
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
            ) {
                /* Center the tapped sentence AGAIN once the finger is off the
                   glass. speakNext already centered it, but that scroll starts
                   while the double-tap's second touch is still down — a few
                   pixels of drift past the touch slop and the ScrollView
                   intercepts the gesture and kills the animation, and nothing
                   retries until the NEXT sentence: a long one leaves the page
                   parked at the tap for its entire reading (reported from
                   device — highlight and speech moved, the page didn't).
                   Re-issuing when the gesture ends leaves no touch stream to
                   interfere. Skipped while a border append is pending:
                   resumeCursor still names the pre-tap sentence there, and
                   the append's own speakNext centers the right one. */
                if (swallowTap && speaking && !pendingSpeakContinue && resumeCursor >= 0) {
                    scrollToSpoken(resumeCursor)
                }
                swallowTap = false
            }
            consume
        }

        findViewById<TextView>(R.id.ttsPlayBtn).setOnClickListener { playButtonAction() }
        findViewById<TextView>(R.id.ttsPrevBtn).setOnClickListener { skipParagraph(forward = false) }
        findViewById<TextView>(R.id.ttsNextBtn).setOnClickListener { skipParagraph(forward = true) }
        findViewById<TextView>(R.id.ttsSettingsBtn).setOnClickListener { showTtsSettings() }
        /* the not-ready spinner doubles as a retry button once a bind gave up */
        findViewById<android.widget.ProgressBar>(R.id.ttsSpinner).setOnClickListener {
            if (!ttsReady && !ttsConnecting) initTts()
        }
        updatePlayBtn()   // engine still binding at this point → show the spinner

        /* The notification's Pause/Play action broadcasts back to us. It is
           the only sender, and a button labelled with whichever state the
           reader is not in can only mean toggle. */
        ttsToggleReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                runOnUiThread { applyMediaAction(MediaKeys.Want.TOGGLE) }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, ttsToggleReceiver,
            android.content.IntentFilter(TtsService.ACTION_TOGGLE),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        /* Bluetooth headphones disconnecting (or wired unplugging) fires the
           system's "audio becoming noisy" broadcast — stop reading right there
           instead of continuing out loud on the phone speaker. pauseTts keeps
           the position + notification, so play resumes where it stopped. */
        noisyReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                if (i?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    runOnUiThread { if (speaking) pauseTts() }
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, noisyReceiver,
            android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        /* Bluetooth headset / media-button play & pause control the TTS.

           What gets a press here is being the media-button SESSION, which
           Android grants to an app that is playing audio under its own UID —
           see Silence. Nothing else does: this app also carried a manifest
           MediaButtonReceiver for a while, credited in a note here with the
           whole job, and it was never able to deliver a press to a live
           reader at all. */
        mediaSession = android.support.v4.media.session.MediaSessionCompat(this, "reader-tts").apply {
            setCallback(object : android.support.v4.media.session.MediaSessionCompat.Callback() {
                /* Answer the key HERE rather than let MediaSessionCompat
                   decide. Its default resolves a headset press against the
                   PlaybackState it was last told — a copy of `speaking` — and
                   delivers the result a third of a second later, watching for
                   the double tap that means "skip to next". Both halves cost
                   presses, and MediaKeys says why at length: the short of it
                   is that a copy which has fallen behind picks the branch
                   that then does nothing, and nothing puts it right
                   afterwards, so the button stays dead. Taking the event
                   answers it now, from the flag that actually knows whether
                   the reader is reading. */
                override fun onMediaButtonEvent(mediaButtonEvent: android.content.Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val ev = mediaButtonEvent
                        .getParcelableExtra<android.view.KeyEvent>(android.content.Intent.EXTRA_KEY_EVENT)
                        ?: return super.onMediaButtonEvent(mediaButtonEvent)
                    val want = MediaKeys.want(ev.keyCode, ev.action, ev.repeatCount)
                        ?: return super.onMediaButtonEvent(mediaButtonEvent)
                    runOnUiThread { applyMediaAction(want) }
                    return true
                }
                /* Bluetooth also sends these as transport commands with no key
                   event behind them (AVRCP play/pause), so they still have to
                   work on their own — through the same decision. */
                override fun onPlay() {
                    runOnUiThread { applyMediaAction(MediaKeys.Want.PLAY) }
                }
                override fun onPause() {
                    runOnUiThread { applyMediaAction(MediaKeys.Want.PAUSE) }
                }
                override fun onStop() {
                    runOnUiThread { applyMediaAction(MediaKeys.Want.PAUSE) }
                }
                /* AVRCP's skip commands (a headset's double/triple tap often
                   arrives as these rather than as key events) */
                override fun onSkipToNext() {
                    runOnUiThread { applyMediaAction(MediaKeys.Want.NEXT) }
                }
                override fun onSkipToPrevious() {
                    runOnUiThread { applyMediaAction(MediaKeys.Want.PREV) }
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

    /* The chapter list opens the reader with REORDER_TO_FRONT, so picking a
       chapter of the novel ALREADY being read lands here on the live instance
       — keeping TTS and the loaded buffer — instead of stacking a fresh
       reader on top. Same novel → jump within it (goTo keeps TTS running when
       it's already reading that chapter). Different novel (or the chapter
       list isn't loaded yet) → full rebuild via recreate. */
    override fun onNewIntent(newIntent: android.content.Intent) {
        super.onNewIntent(newIntent)
        val sameNovel = newIntent.getStringExtra("slug") == intent.getStringExtra("slug")
        val start = newIntent.getStringExtra("start")
        val idx = if (sameNovel && start != null) {
            chapters?.ordered?.indexOf(start) ?: -1
        } else {
            -1
        }
        setIntent(newIntent)
        if (idx >= 0) {
            /* An explicit pick from the chapter list is a new place, just as
               one from the drawer is. Without this a reader that had given up
               on a lost spot never resumed recording — you could pick a
               chapter, read for an hour, and still reopen at chapter 1 with
               the same toast. */
            spotLost = false
            val t = restoreTargetFor(idx)
            goTo(idx, t.first, t.second)
        } else {
            recreate()
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
                /* speak as MEDIA/SPEECH so the system routes and ducks this
                   like any other player (and silences it during a call) */
                try { tts?.setAudioAttributes(ttsAudioAttributes()) } catch (e: Exception) {}
                curTtsLang = ""   // re-apply the language profile on next speak
                /* the retry just succeeded — restore the saved voice as soon
                   as its data finishes loading */
                voiceRestoreTicks = 0
                ensureSavedVoice()
                runOnUiThread {
                    updatePlayBtn()   // spinner → play triangle
                    /* a play pressed while the engine was away — carry it out
                       now rather than make the user press again */
                    val asked = pendingPlayUntil
                    pendingPlayUntil = 0L
                    if (!speaking && asked > android.os.SystemClock.elapsedRealtime()) {
                        playButtonAction()
                    }
                }
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
                runOnUiThread { if (speaking && Utterance.isCurrent(utteranceId, speechGen)) speakNext() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { if (speaking && Utterance.isCurrent(utteranceId, speechGen)) speakNext() }
            }
        })
        tts = t
    }

    /* A WHOLE chapter of the buffer, which is the unit the language is judged
       over — see Voices.detect for why a smaller sample is not worth asking
       about. Null before the chapter it is asked for has loaded.

       Not the first N characters of the buffer either: that window starts two
       chapters before the one on screen, so sampling from offset 0 asks about
       a chapter the reader may never have reached. */
    private fun chapterTextAt(off: Int): String? {
        val body = text.text.toString()
        if (body.isBlank()) return null
        val (s, e) = chapterSpanAt(off) ?: return null
        val from = s.coerceIn(0, body.length)
        val to = e.coerceIn(from, body.length)
        return body.substring(from, to).ifBlank { null }
    }

    /* The chapter on screen. */
    private fun currentChapterText(): String? =
        loadedChapters.firstOrNull { it.idx == currentChapterIdx }?.let { chapterTextAt(it.start) }

    /* Which language profile a screen is about: the one being spoken if
       there is one, otherwise whatever is on screen. Only for SHOWING and
       EDITING — it never sets curTtsLang, so a guess made before the chapter
       arrived cannot latch. English is the last resort of a reader with no
       text at all, and the next open asks again. */
    private fun profileLang(): String =
        curTtsLang.ifEmpty { currentChapterText()?.let { Voices.detect(it) } ?: "en" }

    /* The locale a language profile speaks in. One definition, because the
       voice filter, the note that explains an empty filter and the fallback
       applied when no voice is picked all have to mean the same thing by
       "vi". */
    private fun localeFor(lang: String): java.util.Locale =
        if (lang == "vi") java.util.Locale("vi", "VN") else java.util.Locale.US

    /* Is this a voice that renders on Google's servers rather than here?

       The engine's own answer first, the name as a backstop — see
       Voices.isNetworkName. */
    private fun isNetworkVoice(v: android.speech.tts.Voice): Boolean =
        try { v.isNetworkConnectionRequired } catch (e: Exception) { false } ||
            Voices.isNetworkName(v.name.orEmpty())

    /* Every voice this reader will offer, in the order the picker lists them —
       A to Z down the label a reader sees, which Voices.sortKey defines and
       explains.

       Network voices are not among them. Google ships each voice twice, once
       rendering on the phone and once on its servers, and the second kind is
       no use to a reader whose whole point is a downloaded novel: it needs a
       connection to say a word, so it goes silent on a train or a plane, and
       every sentence waits on a round trip. Dropping them also halves a list
       that was long enough to have to scroll.

       One definition, used by the pickers AND by the restore, so the voice in
       use is always one the picker would have shown. */
    private fun offerableVoices(): List<android.speech.tts.Voice> = try {
        tts?.voices.orEmpty().filter { !isNetworkVoice(it) }
            .sortedBy { Voices.sortKey(it.locale?.toString().orEmpty(), it.name.orEmpty()) }
    } catch (e: Exception) {
        emptyList()
    }

    /* Those of them in this language. Matching lives in Voices, which says why
       one spelling of the code was not enough. */
    private fun voicesFor(lang: String): List<android.speech.tts.Voice> =
        offerableVoices().filter { Voices.matches(it.locale?.language.orEmpty(), lang) }

    /* Why this language has no voice, in the engine's own words.

       The picker falls back to listing every voice it has, which is better
       than an empty box — but on its own it is baffling: the card says
       Tiếng Việt and then offers en_US. Worse, the two reasons want opposite
       things from the reader. Voice data not installed is a fix they can go
       and make; a language the engine does not speak is not. Say which. */
    private fun noVoiceNote(lang: String): String {
        val name = Voices.nameOf(lang)
        val why = when (
            try { tts?.isLanguageAvailable(localeFor(lang)) } catch (e: Exception) { null }
        ) {
            android.speech.tts.TextToSpeech.LANG_MISSING_DATA ->
                "$name voice data isn't installed — Android TTS settings below, " +
                    "then Google Text-to-speech → Install voice data → $name."
            android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED ->
                "Google TTS has no $name voice on this device."
            null -> "The engine didn't answer about $name."
            else ->
                "The engine says $name is available but offers no voice for it — " +
                    "its data may still be downloading."
        }
        /* A language whose only voices are the network ones reads as "nothing
           installed", which is true and actionable — but not the whole truth
           when we are the ones holding voices back. Say so where it applies:
           silently substituting a list is the mistake this note exists to
           correct, and it would be a poor one to repeat one line down. */
        val hidden = try {
            tts?.voices.orEmpty().any {
                isNetworkVoice(it) && Voices.matches(it.locale?.language.orEmpty(), lang)
            }
        } catch (e: Exception) { false }
        val also = if (hidden) {
            " (online-only $name voices exist but aren't offered — they need a connection to say a word.)"
        } else {
            ""
        }
        return "$why Every other voice is listed above.$also"
    }

    /* voice/rate/pitch are stored PER LANGUAGE ("ttsRate:en", ...) */
    private fun applyTtsConfig(lang: String) {
        val t = tts ?: return
        curTtsLang = lang
        t.setSpeechRate(prefs.getFloat("ttsRate:$lang", 1f))
        t.setPitch(prefs.getFloat("ttsPitch:$lang", 1f))
        /* From the offerable set, so a network voice saved before they were
           dropped cannot go on being applied behind a picker that no longer
           lists it — it simply stops resolving, and the locale default below
           takes over, which is what the picker shows. */
        val saved = prefs.getString("ttsVoice:$lang", null)
        val v = saved?.let { name -> offerableVoices().firstOrNull { it.name == name } }
        if (v != null) {
            t.voice = v
        } else {
            t.language = localeFor(lang)
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
        /* The engine connects while the chapter is still loading, and this
           runs the moment it does. Deciding the language from an empty buffer
           got "English" and applyTtsConfig then LATCHED it — the voice sheet
           said "TTS — English" over a Vietnamese chapter for the rest of the
           session. With nothing to judge there is nothing to restore yet, so
           come back when there is; the retry below is already here. */
        val lang = curTtsLang.ifEmpty { currentChapterText()?.let { Voices.detect(it) } ?: "" }
        if (lang.isEmpty()) {
            if (voiceRestoreTicks < 15) {
                voiceRestoreTicks++
                android.os.Handler(mainLooper).postDelayed({ ensureSavedVoice() }, 800)
            }
            return
        }
        val saved = prefs.getString("ttsVoice:$lang", null) ?: return  // default → nothing to restore
        val present = offerableVoices().any { it.name == saved }
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

    /* start of the SENTENCE containing `off`: walk the paragraph's sentences
       until the one that spans the tap. Reading starts exactly at the
       double-tapped sentence, not at the top of its paragraph. (For an `off`
       already at a paragraph start this returns that same position, so saved
       paragraph restores are unaffected.) */
    private fun sentStartOf(off: Int): Int {
        val body = text.text.toString()
        val o = off.coerceIn(0, body.length)
        var cursor = paraStartOf(o)
        while (true) {
            val s = nextSentence(body, cursor) ?: return cursor
            if (o < s.second || s.second <= cursor) return s.first
            cursor = s.second
        }
    }

    /* scroll Y that places the line at `off` a small gap below the top edge,
       so a navigated-to chapter's heading isn't jammed under the header (the
       plain top-of-line position scrolls past the text's top padding) */
    private fun navScrollY(off: Int, fifth: Boolean = false): Int {
        val layout = text.layout ?: return 0
        val line = layout.getLineForOffset(off.coerceIn(0, text.length()))
        val top = layout.getLineTop(line) + text.totalPaddingTop
        /* fifth: sit the line ~20% down the viewport, the same framing TTS
           uses while reading, so a resumed spot has its lead-in visible
           instead of being pinned under the header */
        return (if (fifth) top - scroll.height / 5 else top - dp(16)).coerceAtLeast(0)
    }

    /* Counting paragraphs stops at `limit`. Without a bound it walked
       newlines to the end of the BUFFER, so a paragraph index that outruns
       its chapter — a translation with different paragraph splitting, a
       chapter re-fetched shorter, a language toggle — landed somewhere in a
       LATER chapter. The reader then resolved the viewport to that chapter
       and saved the spot there, so the place the user actually left was not
       merely unreachable, it was overwritten two chapters ahead. */
    private fun offsetOfPara(chapterStart: Int, para: Int, limit: Int = -1): Int {
        val body = text.text.toString()
        val end = if (limit < 0) body.length else limit.coerceIn(chapterStart, body.length)
        var off = chapterStart
        var n = 0
        while (n < para) {
            val i = body.indexOf('\n', off)
            if (i == -1 || i + 1 >= end) break
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
    private var noisyReceiver: android.content.BroadcastReceiver? = null
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
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(state, pos, 0f)
                .build(),
        )
    }

    /* Carry out what a press asked for, whichever control it came from: the
       media session's callback (a headset key, or AVRCP's play/pause) and the
       notification's own Pause/Play button both land here, so neither can
       disagree with the other about what a press means.

       Even a press that turns out to be a no-op pushes the playback state, so
       the session's copy of `speaking` never drifts from the real one. */
    private fun applyMediaAction(want: MediaKeys.Want) {
        when (MediaKeys.act(want, speaking)) {
            MediaKeys.Act.START -> playButtonAction()
            MediaKeys.Act.PAUSE -> pauseTts()
            MediaKeys.Act.NEXT_PARA -> skipParagraph(forward = true)
            MediaKeys.Act.PREV_PARA -> skipParagraph(forward = false)
            MediaKeys.Act.NOTHING -> {
                /* A pause with nothing to pause still cancels a play that is
                   waiting on the engine to come back — otherwise the read
                   starts speaking moments after the user asked it not to. */
                if (want == MediaKeys.Want.PAUSE) pendingPlayUntil = 0L
                updateMediaSessionState()
            }
        }
    }

    /* Started with the speech and stopped with it. The one thing that puts
       this app on the list Android picks the media-button session from — see
       Silence for why nothing else did. */
    private val silence by lazy { Silence(ttsAudioAttributes()) }

    /* A play arrived while the TTS engine was unbound: speak when it comes
       back, but only if it comes back promptly. A bind normally takes well
       under a second; the watchdog goes on retrying a dead engine for as long
       as the reader is open, and a press from ten minutes ago is not a reason
       to start reading out of someone's pocket. Held as the deadline itself —
       0 for "nothing asked". */
    private var pendingPlayUntil = 0L
    private val PLAY_WAIT_MS = 30_000L

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
        /* Saved position from a previous session: continue from it. The spot
           is per NOVEL (ttsPos:$slug) — like the remembered reading chapter —
           so play returns to it even from another chapter, loading the TTS
           chapter if needed. Reading from somewhere else explicitly is what
           the double-tap is for. */
        val slugX = intent.getStringExtra("slug")
        val saved = slugX?.let { prefs.getString("ttsPos:$it", null) }
        val ord = chapters?.ordered
        if (saved != null && ord != null) {
            val name = saved.substringBefore('|')
            val para = saved.substringAfter('|').toIntOrNull() ?: 0
            /* By chapter NUMBER, as restoreTargetFor and onCreate both resolve
               the same stored name — an exact-string compare misses a chapter
               saved under one spelling and listed under another (a title
               suffix appearing or going), and the miss is not harmless: it
               falls through to reading from the top of the viewport, and the
               first spoken sentence then calls saveTtsPos, overwriting the
               only record of where the user actually stopped. */
            val idx = ord.indexOfFirst { sameChapter(name, it) }
            if (idx >= 0) {
                val lc = loadedChapters.firstOrNull { it.idx == idx }
                if (lc != null) {
                    /* Through the same restore every other path uses: bounded
                       by this chapter, and corrected by the stored paragraph
                       text when the index has drifted. Raw, it could run off
                       the end of the chapter into a later one — and this is
                       the button users press most. */
                    val next = loadedChapters.firstOrNull { it.start > lc.start }
                    val chEnd = next?.let { it.start - SEP.length } ?: text.length()
                    val anchor = slugX.let { prefs.getString("ttsParaText:$it", null) }
                    startTtsFrom(restoreOffsetIn(lc.start, chEnd, para, anchor))
                } else {
                    pendingSpeakAfterOpen = true
                    /* with the anchor, exactly as the loaded branch three
                       lines above: openAt runs the same restoreOffsetIn, and
                       without the stored paragraph text the index-drift
                       correction it exists for never runs at all */
                    openAt(idx, para, slugX.let { prefs.getString("ttsParaText:$it", null) })
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
            /* The engine drops its binding while the reader sits paused with
               the screen off, which is precisely when the next thing to
               happen is a play press from a headset. Rebinding and returning
               threw that press away: on screen there is at least a spinner to
               explain it, but from an earbud the button simply did nothing
               and the user pressed it again. Remember the ask and honour it
               when the engine comes back. */
            pendingPlayUntil = android.os.SystemClock.elapsedRealtime() + PLAY_WAIT_MS
            initTts()
            updatePlayBtn()
            return
        }
        pendingPlayUntil = 0L
        speakCursor = sentStartOf(off)
        speaking = true
        clearTextSelection()  // remove the start-tap cursor so it can't yank later
        requestAudioFocus()
        /* What actually makes us the media-button session: a player of our
           own, running. Focus does not — see Silence, and the comment that
           used to sit on this line claiming it did. */
        silence.start()
        scheduleSleepTimer()  // the timer counts from each play
        startShakeDetection()
        /* foreground service keeps reading alive with the screen off */
        lastNotifHeading = currentHeading()
        TtsService.start(this, lastNotifHeading, true, mediaSession?.sessionToken, intent.getStringExtra("slug"))
        updateKeepAwake()
        updatePlayBtn()
        speakNext()
    }

    /* The ❮/❯ footer buttons and a headset's skip keys: move the reading
       spot a paragraph at a time. ❯ goes to the next paragraph; ❮ back to
       the start of the one being read when it is mid-paragraph, and to the
       previous paragraph when it is already at a start — so tapping it
       repeatedly walks backwards, the way every player's ⏮ behaves.

       While speaking, reading continues from the new spot through the same
       startTtsFrom path the double-tap uses. Paused, the resume point moves
       and is highlighted and scrolled to, so the next play — tonight's or
       next week's — picks it up: the move is saved like a spoken sentence. */
    private fun skipParagraph(forward: Boolean) {
        val body = text.text.toString()
        if (body.isEmpty()) return
        /* the sentence being/last spoken; before any reading, the top of the
           viewport — the same place the play button would start from */
        val anchor = if (resumeCursor >= 0) {
            resumeCursor
        } else {
            val layout = text.layout ?: return
            layout.getLineStart(
                layout.getLineForVertical((scroll.scrollY - text.totalPaddingTop).coerceAtLeast(0)),
            )
        }
        /* blank lines and the ⁂ chapter separator are not paragraphs — skips
           step over them, landing on a chapter heading like any other line */
        fun isBreak(c: Char) = c == '\n' || c == ' ' || c == '⁂'
        val target: Int
        if (forward) {
            var i = body.indexOf('\n', anchor)
            if (i == -1) return   // last loaded paragraph — nowhere to go yet
            while (i < body.length && isBreak(body[i])) i++
            if (i >= body.length) return
            target = i
        } else {
            val pStart = paraStartOf(anchor)
            val firstSent = nextSentence(body, pStart)?.first ?: pStart
            if (anchor > firstSent) {
                target = firstSent   // mid-paragraph → its start
            } else {
                var i = pStart - 1
                while (i >= 0 && isBreak(body[i])) i--
                if (i < 0) return   // top of the buffer
                target = paraStartOf(i)
            }
        }
        if (speaking) {
            startTtsFrom(target)
            return
        }
        resumeCursor = sentStartOf(target)
        nextSentence(body, resumeCursor)?.let { setHighlight(it.first, it.second) }
        scroll.smoothScrollTo(0, navScrollY(resumeCursor, fifth = true))
        saveTtsPos(resumeCursor)
        updateMediaSessionState()
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
                return
            }
            /* The end of the LISTING is not necessarily the end of the novel —
               a download may have added to the folder since we read it. Check
               once, and come back through here when the answer arrives; the
               stop below then happens on the second pass, or right now if
               there is nothing left to ask.

               speechGen moves whenever the speech this call belongs to is
               thrown away (a pause, a stop, an open, a fresh play), and the
               answer arrives a folder listing later — driving speakNext from a
               read that has since been replaced would cut the sentence that
               replaced it. */
            val gen = speechGen
            /* The STOP rides on "the walk adopted nothing", not on "the walk
               was refused". With force skipping the window the walk is never
               refused, so a stop keyed on refusal never fired: every session
               reaching the end of its book looped walk-after-walk forever,
               holding audio focus, the foreground notification and keep-awake
               with nothing left to say. One walk per arrival at the end;
               silence unless it grew. */
            val n0 = chapters?.ordered?.size ?: 0
            if (!relistForNewChapters(force = true) {
                    if (speaking && speechGen == gen) {
                        if ((chapters?.ordered?.size ?: 0) > n0) speakNext() else stopTts()
                    }
                }
            ) {
                stopTts()
            }
            return
        }
        val (s0, s1) = sent
        /* Crossing the border into the LAST loaded chapter (the reader is
           within one chapter of running out): stop here — this fires BETWEEN
           sentences, so no audio is cut — load the next batch, then resume.
           Chapters are only ever loaded in this gap, never while a sentence is
           being spoken. Appends don't move existing text, so s0/s1 stay valid. */
        val lastLoaded = loadedChapters.lastOrNull()
        if (!loading && lastLoaded != null && s0 >= lastLoaded.start &&
            nextIdx < (chapters?.ordered?.size ?: 0)
        ) {
            t.stop()   // make sure nothing is mid-utterance while we load
            speechGen++   // ...and its report must not advance us either
            pendingSpeakContinue = true
            appendChapters(LOAD_BATCH)
            return
        }
        resumeCursor = s0
        speakCursor = s1
        val sentence = body.substring(s0, s1)
        /* The chapter this sentence is in, not the sentence. Judging each
           sentence on its own is what let one borrowed word — `d'état` — swap
           the voice to Vietnamese half way down an English novel and leave it
           there. A chapter is written in one language; ask about that.
           Unjudgeable (still loading, or barely any text) keeps the profile
           already in use rather than forcing one. */
        val lang = chapterTextAt(s0)?.let { Voices.detect(it) } ?: curTtsLang.ifEmpty { "en" }
        if (lang != curTtsLang) applyTtsConfig(lang)
        setHighlight(s0, s1)
        scrollToSpoken(s0)
        saveTtsPos(s0)
        updateMediaSessionState()   // advance the notification's chapter progress
        /* normalize the spoken copy (URLs/emojis silenced, abbreviations
           expanded); the on-screen text and s0/s1 offsets are untouched */
        val toSpeak = cleanForSpeech(sentence, lang).ifBlank { " " }
        /* A fresh tag per sentence: QUEUE_FLUSH discards whatever was
           mid-utterance, and that discarded one still reports — see
           Utterance. */
        t.speak(toSpeak, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, Utterance.id(++speechGen))
    }

    /* Speech edits (managed on the Speech-edits screen) applied to every
       English sentence before it is spoken: user rules then the built-in
       defaults. Only the copy handed to the engine is rewritten, so on-screen
       text and s0/s1 offsets are untouched. Reloaded on resume so edits made
       on that screen take effect immediately. */
    private var speechRules: List<Pair<Regex, String>> = emptyList()
    private val collapseWs = Regex("\\s+")

    /* generous for any sane rule on one sentence, far short of a stall the
       user would notice between two spoken lines */
    private val RULE_BUDGET_MS = 150L

    /* Latched once a rule blows its budget. Without it, reloadSpeechRules in
       onResume put the runaway straight back — a screen off/on, a call, a trip
       to the chapter list — and from then on every single sentence paid the
       timeout and abandoned another spinning thread. */
    private var rulesTooSlow = false

    private fun reloadSpeechRules() {
        if (rulesTooSlow) { speechRules = emptyList(); return }
        speechRules = try { SpeechEdits.enabledRules(this) } catch (e: Exception) { emptyList() }
    }

    /* Speech rules are arbitrary user-authored regexes, imported wholesale
       from @Voice files, and they run between sentences. A pattern with
       nested quantifiers backtracks exponentially — and that is not an
       exception, it is a hang, so no catch here can help.

       This used to hand the matcher a CharSequence that threw once a deadline
       passed. It never fired: Android's java.util.regex is ICU-backed and
       copies its input to a String before matching, so the matcher never read
       through the wrapper and the guard was inert on every version. Run the
       rules off the main thread with a budget instead, and if one blows it,
       drop the rule set for the rest of the session rather than abandon a
       fresh runaway thread on every sentence. */
    private fun cleanForSpeech(sentence: String, lang: String): String {
        if (lang != "en" || speechRules.isEmpty()) return sentence
        val rules = speechRules
        val out = SpeechEdits.within(RULE_BUDGET_MS) {
            var s = "$sentence "   // trailing space so end-anchored rules fire
            for ((re, rep) in rules) {
                /* one bad rule (e.g. a replacement with an out-of-range $group)
                   must never crash the read — skip it and keep going */
                s = try { re.replace(s, rep) } catch (e: Exception) { s }
            }
            s.replace(collapseWs, " ").trim()
        }
        if (out == null) {
            rulesTooSlow = true
            speechRules = emptyList()
            android.widget.Toast.makeText(
                this, "A speech-edit rule is too slow — rules turned off for now", android.widget.Toast.LENGTH_LONG,
            ).show()
            return sentence
        }
        return out
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
       play button continues from here (paragraphs map 1:1 across EN/VI).
       The paragraph's TEXT is stored too — the Speech-edits test box can
       offer "test against the paragraph TTS stopped at". */
    private fun saveTtsPos(off: Int) {
        val slug = intent.getStringExtra("slug") ?: return
        if (!spotWritable()) return
        val ch = loadedChapters.lastOrNull { it.start <= off } ?: return
        val name = chapters?.ordered?.getOrNull(ch.idx) ?: return
        val para = text.text.subSequence(ch.start, off.coerceAtLeast(ch.start)).count { it == '\n' }
        val body = text.text
        val pStart = paraStartOf(off)
        val nl = body.toString().indexOf('\n', pStart)
        val pEnd = if (nl == -1) body.length else nl
        /* The paragraph INDEX alone is positional and can't detect that it
           landed on the wrong line, so keep the paragraph's TEXT as an anchor
           too: the restore verifies against it and re-finds the paragraph if
           the index doesn't line up. */
        val paraText = body.subSequence(pStart, pEnd).toString()
        prefs.edit().putString("ttsPos:$slug", "$name|$para")
            .putLong("ttsPosAt:$slug", System.currentTimeMillis())
            .putString("ttsParaText:$slug", paraText)
            .putString("lastTtsPara", paraText)
            .apply()
        /* keep "where I left off" pointing at the chapter being spoken, so
           reopening the novel lands on the chapter this position belongs to */
        saveLastChapter(ch.idx)
    }

    /* Offset of the saved paragraph inside [chapterStart, chapterEnd): the
       stored index first, corrected by the stored paragraph text when the two
       disagree (the index is only as good as the buffer it was counted in). */
    private fun restoreOffsetIn(
        chapterStart: Int,
        chapterEnd: Int,
        para: Int,
        anchorText: String?,
    ): Int {
        val body = text.text.toString()
        val end = chapterEnd.coerceIn(chapterStart, body.length)
        /* bounded by the chapter: an index that outruns it must land at the
           chapter's end, never inside the next one */
        val byIndex = if (para > 0) offsetOfPara(chapterStart, para, end) else chapterStart
        val anchor = anchorText?.takeIf { it.isNotBlank() } ?: return byIndex
        if (byIndex in chapterStart until end && body.startsWith(anchor, byIndex)) {
            return byIndex   // index agrees with the anchor
        }
        /* index drifted — find the paragraph itself, preferring the occurrence
           nearest where the index pointed */
        var best = -1
        var i = body.indexOf(anchor, chapterStart)
        while (i >= 0 && i < end) {
            if (best < 0 || Math.abs(i - byIndex) < Math.abs(best - byIndex)) best = i
            i = body.indexOf(anchor, i + 1)
        }
        return if (best >= 0) best else byIndex
    }

    /* pause replays the interrupted sentence on resume */
    private fun pauseTts() {
        speaking = false
        pendingPlayUntil = 0L
        silence.stop()
        cancelAutoScroll()
        cancelSleepTimer()
        stopShakeDetection()
        tts?.stop()
        /* ...and retire its tag with it: stop() discards the utterance, and
           the discarded one still reports. */
        speechGen++
        /* paused: keep the notification with a Play action (wake lock off) */
        TtsService.start(this, currentHeading(), false, mediaSession?.sessionToken, intent.getStringExtra("slug"))
        if (resumeCursor >= 0) speakCursor = resumeCursor
        clearHighlight()
        /* drop any insertion cursor left by a tap (e.g. the start-TTS double
           tap) so it can't bringPointIntoView and yank the scroll up on stop */
        clearTextSelection()
        updateKeepAwake()
        updatePlayBtn()
    }

    private fun stopTts() {
        speaking = false
        /* nothing is waiting on the engine any more — a play remembered from
           before this stop must not start speaking when the watchdog's next
           rebind succeeds */
        pendingPlayUntil = 0L
        silence.stop()
        cancelAutoScroll()
        cancelSleepTimer()
        stopShakeDetection()
        tts?.stop()
        speechGen++
        TtsService.stop(this)
        abandonAudioFocus()
        clearHighlight()
        clearTextSelection()   // no tap cursor to yank the scroll up on stop
        updateKeepAwake()
        updatePlayBtn()
    }

    /* Hold audio focus while a TTS session is active (kept through pauses so
       the headset PLAY button still routes back to us). Without it, headset
       media keys go to whatever app last had focus once we're backgrounded.

       Losing focus in ANY form stops the read: another app starting playback
       (AUDIOFOCUS_LOSS), an incoming call or a navigation prompt
       (LOSS_TRANSIENT), or a duck request — setWillPauseWhenDucked makes the
       system deliver those as a transient loss too, so we go quiet instead of
       reading on at low volume under someone else's audio. Nothing
       auto-resumes: the user presses play when they're ready. */
    private var audioFocusReq: android.media.AudioFocusRequest? = null

    private fun buildFocusRequest(): android.media.AudioFocusRequest =
        android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(ttsAudioAttributes())
            /* keeps the SYSTEM from quietly ducking us behind a notification
               chime — with this set, Android never lowers our volume and tells
               us about the request instead (as a transient loss) */
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    /* someone took over for good (another player) → stop */
                    android.media.AudioManager.AUDIOFOCUS_LOSS ->
                        runOnUiThread { if (speaking) pauseTts() }
                    /* A short interruption. Because willPauseWhenDucked is set,
                       a notification's duck request arrives here too, looking
                       exactly like a call — so tell them apart by the audio
                       mode: a call stops the read, a notification just plays
                       over it at full volume. */
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    -> runOnUiThread { pauseIfCall(0) }
                }
            }
            .build()

    /* The audio mode can lag the focus change by a moment, so re-check a
       couple of times before concluding it wasn't a call. */
    private fun pauseIfCall(attempt: Int) {
        if (!speaking) return
        if (inPhoneCall()) {
            pauseTts()
            return
        }
        if (attempt < 3) {
            android.os.Handler(mainLooper).postDelayed({ pauseIfCall(attempt + 1) }, 250)
        }
    }

    private fun inPhoneCall(): Boolean = try {
        val mode = (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).mode
        mode == android.media.AudioManager.MODE_IN_CALL ||
            mode == android.media.AudioManager.MODE_IN_COMMUNICATION ||
            mode == android.media.AudioManager.MODE_RINGTONE
    } catch (e: Exception) {
        false
    }

    private fun ttsAudioAttributes(): android.media.AudioAttributes =
        android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    /* (Re)acquire focus on every play — after a loss the previous request is
       no longer held, and the old "already have a request object" short-circuit
       meant we'd read on without focus for the rest of the session. */
    private fun requestAudioFocus() {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val req = audioFocusReq ?: buildFocusRequest().also { audioFocusReq = it }
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
        /* engine still binding \u2192 the play button is an indeterminate spinner;
           the triangle/bars come back the moment TTS is ready */
        findViewById<android.widget.ProgressBar>(R.id.ttsSpinner)?.visibility =
            if (ttsReady) android.view.View.GONE else android.view.View.VISIBLE
        findViewById<TextView>(R.id.ttsPlayBtn)?.apply {
            visibility = if (ttsReady) android.view.View.VISIBLE else android.view.View.INVISIBLE
            text = if (speaking) "\u275a\u275a" else "\u25b6\ufe0e"
        }
        updateMediaSessionState()
    }

    /* While this screen is live, remember we're in the middle of reading —
       the launcher resumes straight into it. Deliberately leaving (back or
       navigation → isFinishing) clears the marker; backgrounding or a
       process kill does not. */
    override fun onResume() {
        super.onResume()
        reloadSpeechRules()   // pick up any changes made on the Speech-edits screen
        resyncIfRenamed()     // a rename while we were away must not latch saving off
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

    override fun onStop() {
        /* park the short-page poll chain — TTS keeps its own path */
        relistTick?.let { scroll.removeCallbacks(it) }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        /* resume a parked chain; one tick, the chain re-arms itself */
        relistTick?.let { scroll.removeCallbacks(it); scroll.postDelayed(it, 1_000) }
    }

    /* Rotation (and the other config changes declared in the manifest) is
       handled in place — recreating the activity would run onDestroy and kill
       the TTS engine mid-sentence. The text re-wraps at the new width, so
       remember WHICH character is at the top (or the line being spoken) and
       scroll back to it once the new layout lands. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val anchor = if (speaking && resumeCursor >= 0) resumeCursor else topOffset()
        val oldWidth = text.width
        loadReady = false   // the placement scroll must not trigger a load
        fun restore(attempt: Int) {
            /* wait for the re-wrap: the old layout stays until the new width
               is applied, and anchoring against it would land nowhere */
            if (text.width == oldWidth && attempt < 40) {
                scroll.postDelayed({ restore(attempt + 1) }, 16)
                return
            }
            if (speaking) scrollToSpoken(anchor) else scroll.scrollTo(0, navScrollY(anchor))
            updateHeader()
            scroll.post { loadReady = true }
        }
        scroll.postDelayed({ restore(0) }, 16)
    }

    /* character offset of the line at the top of the viewport */
    private fun topOffset(): Int {
        val layout = text.layout ?: return 0
        val y = (scroll.scrollY - text.totalPaddingTop).coerceAtLeast(0)
        return layout.getLineStart(layout.getLineForVertical(y))
    }

    /* Back / ← : always to this novel's CHAPTER LIST. Two of the reader's
       entry points skip that screen — the Library's straight-to-reader
       shortcut for a novel being listened to, and the home screen's
       resume-reading jump — and a plain finish() from those landed on the
       Library or home instead, so where back took you depended on how you
       happened to arrive. Route through the chapter list instead: an
       instance already beneath is brought forward (same end state as
       finishing onto it), and one that was skipped is created.

       While TTS is playing this instance stays ALIVE beneath it (playback
       continues); otherwise it finishes. */
    private fun leaveReader() {
        val dir = intent.getStringExtra("dir")
        if (dir != null) {
            startActivity(
                android.content.Intent(this, ChapterListActivity::class.java)
                    .putExtra("dir", dir)
                    .putExtra("title", intent.getStringExtra("title"))
                    .putExtra("slug", intent.getStringExtra("slug"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
        if (!speaking) finish()
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
        settleHandler.removeCallbacks(settleRunnable)
        stopShakeDetection()
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
        silence.stop()
        abandonAudioFocus()
        try { mediaSession?.release() } catch (e: Exception) {}
        mediaSession = null
        try { ttsToggleReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        ttsToggleReceiver = null
        try { noisyReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        noisyReceiver = null
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
        val lang = profileLang()
        cardTitle(aloud, "Reading aloud — " + if (lang == "vi") "Tiếng Việt" else "English")
        aloud.addView(
            TextView(ctx).apply {
                text = "Voice"; textSize = 13f; setTextColor(getColor(R.color.muted))
                setPadding(0, dp(12), 0, dp(4))
            },
        )
        val forLang = voicesFor(lang)
        val voices = forLang.ifEmpty { offerableVoices() }
        val spinner = android.widget.Spinner(ctx)
        spinner.adapter = android.widget.ArrayAdapter(
            ctx, android.R.layout.simple_spinner_dropdown_item,
            listOf("Default") + voices.map { Voices.label(it.locale?.toString().orEmpty(), it.name.orEmpty()) },
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
        if (voices.isEmpty()) {
            hint(aloud, "No voices found — open the ♪ menu while stopped to reconnect Google TTS.")
        } else if (forLang.isEmpty()) {
            hint(aloud, noVoiceNote(lang))
        }

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
        val lang = profileLang()
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
        /* nothing for this language → show everything rather than nothing,
           and say so underneath rather than leave the mismatch unexplained */
        fun voiceList(): List<android.speech.tts.Voice> = voicesFor(lang).ifEmpty { offerableVoices() }
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
                listOf("Default") + voices.map { Voices.label(it.locale?.toString().orEmpty(), it.name.orEmpty()) },
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
        if (voices.isNotEmpty() && voicesFor(lang).isEmpty()) root.addView(label(noVoiceNote(lang)))
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
        /* openAt refuses while a load is in flight, so flipping first left the
           preference (and the menu label) changed with the text untouched —
           and every later append then resolved the OTHER language, so the
           buffer continued in the language the user wasn't reading, and the
           switch "took effect" only on the next open. */
        /* All three of openAt's early returns, not just `loading`: flipping
           the preference for a switch that then doesn't happen leaves the
           menu label inverted and every later append resolving the other
           language. */
        val ready = chapters
        if (loading || ready == null || ready.ordered.isEmpty()) {
            android.widget.Toast.makeText(
                this, "Still loading — try again in a moment", android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val pos = currentPosition()
        english = !english
        prefs.edit().putString("readerLang", if (english) "en" else "vi").apply()
        openAt(pos?.first ?: (nextIdx - 1).coerceAtLeast(0), pos?.second ?: 0)
    }

    private fun adjustFont(delta: Float) {
        fontSp = (fontSp + delta).coerceIn(12f, 50f)
        prefs.edit().putFloat("readerFontSize", fontSp).apply()
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp)
    }

    /* First lines of the chapter at `i` — the heading, which carries the
       chapter's own number and title and so identifies it however the file has
       been renamed.

       BOTH languages. The buffer's heading was read in whichever language was
       resolvable when it loaded, and the run that renames chapters is usually
       the run that translated them — so a chapter read in Vietnamese now
       resolves to its English translation and no shift would ever match,
       leaving the reader unable to save its place for the rest of the
       session. Either heading identifies the chapter. */
    private suspend fun headingsIn(ch: ChapterListActivity.Companion.Chapters, i: Int): List<String> {
        if (i < 0 || i >= ch.ordered.size) return emptyList()
        val name = ch.ordered[i]
        val refs = listOfNotNull(ch.source[name], ch.translated[name])
        if (refs.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            refs.mapNotNull { ref ->
                try {
                    if (Zips.isGzRef(ref)) Zips.readGz(contentResolver, treeUri!!, Zips.gzDocId(ref))
                    else Saf.readText(contentResolver, treeUri!!, ref)
                } catch (e: Exception) { null }
                    ?.lineSequence()?.firstOrNull()?.trim()?.ifEmpty { null }
            }
        }
    }

    private suspend fun readAt(i: Int): String? {
        val ch = chapters ?: return null
        if (i < 0 || i >= ch.ordered.size) return null
        val name = ch.ordered[i]
        val ref = (if (english) ch.translated[name] ?: ch.source[name] else ch.source[name])
            ?: return null
        return withContext(Dispatchers.IO) {
            when {
                Zips.isGzRef(ref) -> Zips.readGz(contentResolver, treeUri!!, Zips.gzDocId(ref))
                else -> Saf.readText(contentResolver, treeUri!!, ref)
            }
        }
    }

    /* Jump within the ALREADY-LOADED buffer: scroll straight to the chapter
       (and its saved paragraph) without reloading anything. Returns false
       when the chapter isn't loaded — caller falls back to openAt. */
    private fun jumpToLoaded(pos: Int, targetPara: Int, anchor: String? = null): Boolean {
        if (loading) return false
        val lc = loadedChapters.firstOrNull { it.idx == pos } ?: return false
        val layout = text.layout ?: return false
        stopTts()
        resumeCursor = -1
        clearTextSelection()
        /* end of this chapter = start of the next, minus the separator */
        val next = loadedChapters.firstOrNull { it.start > lc.start }
        val chEnd = next?.let { it.start - SEP.length } ?: text.length()
        val off =
            if (targetPara > 0) restoreOffsetIn(lc.start, chEnd, targetPara, anchor) else lc.start
        /* this programmatic jump must not itself trigger a prepend — the small
           top gap makes it look like an upward scroll into the first chapter.
           Gate maybeLoadMore off until the placement scroll has settled. */
        loadReady = false
        prependArmed = false   // jumped to this chapter; not a scroll-up-to-top
        scroll.smoothScrollBy(0, 0)   // kill any in-flight fling
        currentChapterIdx = lc.idx
        saveLastChapter(lc.idx)
        /* placeAt, not a bare scrollTo: jumping deep into the LAST loaded
           chapter would otherwise be clamped by the page end and stay there */
        setTextFocusable(false)
        placeAt(off, fifth = targetPara > 0) {
            setTextFocusable(true)
            updateHeader()
            scroll.post { loadReady = true }
        }
        return true
    }

    /* jump to a chapter: load it, append the next, prepend the previous.
       targetPara scrolls to that paragraph of the opened chapter (used by
       the language toggle to keep the reading position). */
    /* How many chapters to (pre)load in each direction, and how many a
       top-up adds. Small on purpose: the buffer is a window on the novel, not
       a copy of it, and the border-driven loading below keeps it fed in both
       directions. */
    private val LOAD_BATCH = 2

    private var gotoJob: kotlinx.coroutines.Job? = null

    /* Navigate to a chapter + paragraph and recover the scroll there, STAGGERED
       so it never fires while chapters are still being loaded: wait for any
       in-flight load to settle first, then jump within the buffer (if the
       chapter is already loaded) or rebuild the window. Used for both the
       app-restart restore and chapter-list picks (including re-picking the
       current chapter to return to where we left off). */
    private fun goTo(pos: Int, targetPara: Int, anchor: String? = null) {
        /* picking the chapter TTS is already reading → keep reading (nothing new
           to load); just bring the spoken line back into view */
        if (speaking) {
            val cursor = if (resumeCursor >= 0) resumeCursor else speakCursor
            val reading = loadedChapters.lastOrNull { it.start <= cursor }?.idx
            if (reading == pos) {
                scrollToSpoken(cursor)
                return
            }
        }
        gotoJob?.cancel()
        gotoJob = lifecycleScope.launch {
            var waited = 0
            while (loading && waited < 120) { kotlinx.coroutines.delay(50); waited++ }
            if (!jumpToLoaded(pos, targetPara, anchor)) openAt(pos, targetPara, anchor)
        }
    }

    /* Open a chapter: LOAD_BATCH either side of it are loaded too, and only
       THEN is the scroll placed — so the page is always tall enough to reach
       the target and nothing loads afterwards to disturb it.

       Both directions, not just ahead. The opened chapter used to sit at
       offset 0 with nothing above it, so the first thing a reader who wanted
       the previous page did was scroll up into a hard stop and wait for a
       prepend to notice and fire. Loading behind as well means back is
       already there.

       That also means the opened chapter no longer starts at offset 0, so the
       place to scroll to is its own start plus the paragraph within it — the
       one thing the old "target is just its paragraph" shortcut got for
       free. */
    private fun openAt(pos: Int, targetPara: Int = 0, anchor: String? = null) {
        /* Both early returns disarm the Play flag. It is set right before the
           call at the Play button, and nothing else ever cleared it — so a
           Play pressed while a load was in flight did nothing visible and left
           the flag latched, and the NEXT open from any source (a drawer pick,
           the EN/VI toggle) started reading aloud unprompted in whatever
           chapter that was, whose first spoken sentence then overwrote the
           saved listening spot. The button is gated on ttsReady, not on
           `loading`, and the notification and media-key paths reach it
           without any UI gate at all. */
        val ch = chapters ?: run { pendingSpeakAfterOpen = false; return }
        if (loading || ch.ordered.isEmpty()) { pendingSpeakAfterOpen = false; return }
        stopTts()
        resumeCursor = -1
        /* From here on `chapters` is the listing this reader was built from —
           onCreate's walk has finished and assigned it (an open only ever runs
           after that). resyncIfRenamed reads this to know whether a swap of its
           own could still be undone by that assignment. */
        openedOnce = true
        loading = true
        loadReady = false
        prependQueued = false
        val p = pos.coerceIn(0, ch.ordered.size - 1)
        lifecycleScope.launch {
            loadedChapters.clear()
            /* Load the opened chapter AND the batch ahead BEFORE placing. A
               one-chapter page is shorter than the target needs: a ScrollView
               clamps to what fits, so a mid-chapter spot could never be
               reached, and the page could only grow after the placement had
               already given up. Placing last means nothing can disturb it. */
            val last = (p + LOAD_BATCH).coerceAtMost(ch.ordered.size - 1)
            val firstWanted = (p - LOAD_BATCH).coerceAtLeast(0)
            val sb = StringBuilder()
            /* where the opened chapter lands once the ones above it are in */
            var openStart = 0
            var targetBodyLen = 0
            for (i in firstWanted..last) {
                val b = readAt(i)
                if (b == null) {
                    /* The chapter is listed but its file won't read — a
                       download killed mid-write leaves a truncated .gz that
                       still has a name. Skipping it silently meant the header
                       resolved to the NEXT chapter and saved that as the
                       reading place, so the spot was not just unreachable but
                       overwritten; with TTS it started speaking the next
                       chapter from the top and wrote that over the saved
                       paragraph. Say so, and stop recording a place we never
                       reached — the same treatment as a chapter that is gone
                       outright, cleared as soon as one is picked. */
                    if (i == p) {
                        spotLost = true
                        android.widget.Toast.makeText(
                            this@ReaderActivity,
                            "That chapter can't be read — it may not have finished downloading. " +
                                "Re-download the novel, or pick another chapter.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    /* A hole in the buffer is otherwise invisible — the text
                       simply runs from one chapter into the one after next.
                       The log is where the download's own troubles are already
                       reported, and costs nothing to write. */
                    DownloadService.appendLog(
                        "could not read ${ch.ordered.getOrNull(i)} — skipped in the reader",
                    )
                    continue
                }
                if (sb.isNotEmpty()) sb.append(SEP)
                loadedChapters.add(LoadedChapter(i, sb.length, headingOf(b)))
                if (i == p) {
                    openStart = sb.length
                    targetBodyLen = b.length
                }
                sb.append(b)
            }
            /* the first chapter actually READ, which is not firstWanted when
               one above the target wouldn't open */
            firstIdx = loadedChapters.firstOrNull()?.idx ?: p
            nextIdx = (loadedChapters.lastOrNull()?.idx ?: (p - 1)) + 1
            setTextFocusable(false)   // nothing may scroll to the text while we place it
            text.setText(sb, TextView.BufferType.EDITABLE)
            clearTextSelection()   // no stray insertion cursor to auto-scroll
            currentChapterIdx = p
            titleBar.text = loadedChapters.firstOrNull { it.idx == p }?.heading ?: ""
            saveLastChapter(p)

            val targetOff =
                if (targetPara > 0 && targetBodyLen > 0) {
                    restoreOffsetIn(openStart, openStart + targetBodyLen, targetPara, anchor)
                } else {
                    openStart
                }
            scroll.post {
                placeAt(targetOff, fifth = targetPara > 0) {
                    setTextFocusable(true)
                    loading = false
                    loadReady = true
                    /* Landing point, not a scroll-up. maybeLoadMore arms this
                       itself on the first scroll now that an open lands PAST
                       the first loaded chapter rather than on it. */
                    prependArmed = false
                    updateHeader()
                    if (pendingSpeakAfterOpen) {
                        pendingSpeakAfterOpen = false
                        /* TTS extends its own runway from here (speakNext) */
                        startTtsFrom(targetOff)
                    }
                }
            }
        }
    }

    /* scroll so `off` sits just under the header, once the layout reflects the
       current text. Retries one FRAME apart (postDelayed, not post) — plain
       post()s drain faster than layout passes run, so they'd exhaust before
       text.layout is ready and we'd land at the top instead of the target. */
    private fun placeAt(off: Int, fifth: Boolean = false, attempt: Int = 0, then: () -> Unit) {
        val layout = text.layout
        if ((layout == null || layout.text.length != text.text.length) && attempt < 40) {
            scroll.postDelayed({ placeAt(off, fifth, attempt + 1, then) }, 16)
            return
        }
        val want = navScrollY(off, fifth)
        scroll.scrollTo(0, want)
        /* A ScrollView silently CLAMPS to what currently fits, so asking is not
           arriving: on a short page (one chapter, or one still being filled in)
           we land above the target and never hear about it. Keep retrying while
           the page grows — this is what makes deep positions, e.g. where TTS
           stopped late in a chapter, actually restore. */
        if (scroll.scrollY != want && attempt < 40) {
            scroll.postDelayed({ placeAt(off, fifth, attempt + 1, then) }, 16)
            return
        }
        then()
        holdAt(want, 0)
    }

    /* Hold the placement briefly against a LATE yank. readerText is
       textIsSelectable, so it is focusable in touch mode: setting its text
       drops a cursor at offset 0 and can bringPointIntoView it, and the
       ScrollView scrolls to show a child that takes focus — either one lands
       the page back at the very top a few frames AFTER we placed it. Re-assert
       until things settle; back off the moment the reader touches the screen so
       this can never fight a real scroll. */
    private fun holdAt(want: Int, attempt: Int) {
        if (attempt >= 8 || fingerDown) return
        if (scroll.scrollY != want) scroll.scrollTo(0, want)
        scroll.postDelayed({ holdAt(want, attempt + 1) }, 60)
    }


    /* ---- border-driven chapter loading ----
       openAt loads LOAD_BATCH either side of the opened chapter; from there
       scrolling tops the buffer up LOAD_BATCH at a time: reaching the LAST
       loaded chapter appends more, scrolling UP into the FIRST loaded chapter
       prepends the previous batch (never during speech, since prepends shift
       coordinates). */

    private var loadReady = false
    /* The prepend only arms once the reader is PAST the first loaded chapter.
       An open now lands in the middle of its window, so this arms on the first
       scroll and the queue still waits for the reader to come back down into
       the top chapter — the two conditions can't hold at once. It still earns
       its keep at the head of the novel and after a jump, where the landing
       IS the first loaded chapter and a settle scroll would otherwise load a
       batch nobody asked for. */
    private var prependArmed = false

    /* ---- touch + scroll-settle gating ----
       Loading previous chapters (a prepend) shifts every offset, so it must
       never happen mid-gesture: while the finger is on the screen or a fling
       is still gliding. maybeLoadMore only QUEUES the prepend; it runs from
       onScrollSettled, which fires once the finger is up AND the scroll has
       been idle for SETTLE_MS. */
    private var fingerDown = false
    private var prependQueued = false
    private val SETTLE_MS = 140L
    private val settleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val settleRunnable = Runnable { onScrollSettled() }

    /* every scroll change (user or programmatic) pushes the settle check out;
       when they stop arriving for SETTLE_MS the scroll has come to rest */
    private fun noteScrollActivity() {
        settleHandler.removeCallbacks(settleRunnable)
        settleHandler.postDelayed(settleRunnable, SETTLE_MS)
    }

    /* the finger is up and the scroll has stopped: a good, safe moment to run
       a queued prepend (offset-shifting load) */
    private fun onScrollSettled() {
        if (fingerDown) return   // still touching — wait for the release
        if (prependQueued) {
            prependQueued = false
            if (canPrependNow()) prependChapters(LOAD_BATCH)
        }
    }

    /* only prepend when nothing is loading, TTS isn't driving the scroll, the
       finger is off the screen, and we're still sitting on the top chapter */
    private fun canPrependNow(): Boolean {
        if (loading || speaking || fingerDown) return false
        if (firstIdx <= 0) return false
        val first = loadedChapters.firstOrNull() ?: return false
        return currentChapterIdx <= first.idx
    }

    /* global touch tap so finger down/up is known regardless of which child
       (the selectable text, the scroll view) actually handles the gesture */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> fingerDown = true
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL,
            -> {
                fingerDown = false
                /* the lift may end a fling that keeps scrolling, or may be the
                   end of a static press — either way, start the settle clock */
                noteScrollActivity()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun maybeLoadMore(newY: Int, oldY: Int) {
        if (!loadReady || loading) return
        val ch = chapters ?: return
        val first = loadedChapters.firstOrNull() ?: return
        val last = loadedChapters.lastOrNull() ?: return
        val more = nextIdx < ch.ordered.size
        /* Appending forward never shifts existing offsets, so it's safe mid
           scroll — but NOT while TTS plays: reading extends its own runway in
           speakNext (stop → load → resume between sentences), so no chapter is
           ever loaded during active playback. */
        if (!speaking && more && (currentChapterIdx >= last.idx ||
                (text.height > 0 && text.height < scroll.height * 3 / 2))
        ) {
            appendChapters(LOAD_BATCH)
            return
        }
        /* The end of the buffer IS the end of the listing — but that listing
           was read when the reader opened, and a download running behind us
           has been adding to the folder ever since. Ask once before treating
           this as the end of the novel. Deliberately no `return`: the check is
           asynchronous and touches nothing here, so the prepend arming below
           must still run on this event. */
        if (!speaking && !more && currentChapterIdx >= last.idx) {
            relistForNewChapters { if (!speaking) appendChapters(LOAD_BATCH) }
        }
        /* arm once the viewport is past the first loaded chapter */
        if (currentChapterIdx > first.idx) prependArmed = true
        /* SCROLLING UP back into the top loaded chapter (after having read past
           it) → QUEUE the previous LOAD_BATCH. A prepend shifts every offset,
           so it must not fire mid-gesture: onScrollSettled runs it once the
           finger is off the screen and the fling has come to rest. The armed
           flag keeps a fresh open/jump — which lands on the first chapter —
           from queuing it. Never while TTS drives the scroll. */
        if (prependArmed && !speaking && firstIdx > 0 && newY < oldY &&
            currentChapterIdx <= first.idx
        ) {
            prependArmed = false
            prependQueued = true
            noteScrollActivity()
        }
    }

    /* A selectable TextView keeps a cursor where the user last tapped
       (including the TTS double-tap); appending text to it can auto-scroll
       back to that cursor. Drop selection and focus before changing text. */
    private fun clearTextSelection() {
        (text.text as? android.text.Spannable)?.let { android.text.Selection.removeSelection(it) }
        if (text.isFocused) text.clearFocus()
    }

    /* While the page is being rebuilt and placed, take the selectable
       TextView out of the focus order entirely: a focusable child is what lets
       the ScrollView scroll itself to that child's top (and lets a fresh
       cursor at offset 0 be brought into view), undoing the placement. Focus
       is handed back once the scroll has landed, so long-press selection keeps
       working normally. */
    private fun setTextFocusable(on: Boolean) {
        if (!on && text.isFocused) text.clearFocus()
        text.isFocusableInTouchMode = on
        text.isFocusable = on
    }

    /* The listing is read when the reader opens and never read again, so
       chapters that finish downloading WHILE it is open do not exist as far as
       it is concerned: reading aloud stopped at the last one loaded as though
       the novel had ended there, and the scroll reader hit a floor — the only
       way to see the rest was to leave the reader and come back in, which is
       exactly what nobody does while listening.

       So when the buffer runs out, re-list the folder once and adopt the
       result only if it EXTENDS the listing we hold: the first ordered.size
       names identical, in order. Everything in flight — loadedChapters' idx,
       firstIdx, nextIdx, currentChapterIdx, the drawer's rows — is a POSITION
       in the old listing, and a pure extension is the one change that leaves
       every one of them meaning what it meant. Anything else (a rename, a
       deletion) is resyncIfRenamed's job, and here we do what we did before:
       nothing.

       Once per listing: the guard only re-arms when a longer listing is
       actually adopted, so a novel that really has ended costs one folder
       check, not one per scroll event.

       Returns true when a check was started — the caller's `onDone` then runs
       once it has finished (adopted or not) INSTEAD of the caller concluding
       now. */
    /* Time-based, NOT a once-per-listing latch. The latch never re-armed:
       the one relist typically fires while the downloader is mid-batch, finds
       nothing new YET, and latches — chapter N+1 lands thirty seconds later
       and nothing ever asks again, so the listening session was dead for the
       novel's life and every Play press replayed one sentence into silence.
       The TTS path cannot storm (it stops on a negative answer), and the
       scroll path is bounded by this timer: one folder check per interval,
       only while sitting at the end of the buffer. */
    private var lastRelistAt = 0L
    private val RELIST_MS = 30_000L
    private var relisting = false

    private fun relistForNewChapters(force: Boolean = false, onDone: () -> Unit): Boolean {
        val old = chapters ?: return false
        if (relisting || loading) return false
        /* `force` skips the shared window, for the TTS caller only: it treats
           a refusal as the end of the novel and stops, so the poll chain's
           30s stamps were reading as "nothing more" on ~9 of 10 Play
           presses — one sentence, then silence. TTS cannot storm: a negative
           answer stops it, and `relisting` still serializes the walks. */
        if (!force && android.os.SystemClock.elapsedRealtime() - lastRelistAt < RELIST_MS) return false
        val slug = intent.getStringExtra("slug") ?: return false
        val dir = intent.getStringExtra("dir") ?: return false
        val tree = treeUri ?: return false
        val folderKey = prefs.getString("tree", null) ?: return false
        /* A rename has already moved this listing under us: which chapter any
           of our indices names is exactly what is in doubt, so growing the
           buffer from either listing would be a guess. Let the resync put the
           indices back over their own text first. */
        if (DownloadEngine.renameEpochOf(slug) != chaptersEpoch) {
            askResync()
            return false
        }
        relisting = true
        lastRelistAt = android.os.SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                val order = try { store.getChapterOrder(folderKey, slug) } catch (e: Exception) { null } ?: emptyMap()
                try {
                    ChapterListActivity.chapterNames(this@ReaderActivity, tree, dir, order, slug)
                } catch (e: Exception) { null }
            }
            relisting = false
            /* Still the same listing we measured against, still nothing
               loading, and strictly longer with our whole listing as its
               prefix — otherwise leave everything exactly as it was. */
            if (fresh != null && chapters === old && !loading &&
                DownloadEngine.renameEpochOf(slug) == chaptersEpoch &&
                fresh.ordered.size > old.ordered.size &&
                old.ordered.indices.all { fresh.ordered[it] == old.ordered[it] }
            ) {
                chapters = fresh
                /* adopted: re-arm at once, so a live download is followed
                   batch after batch without waiting out the interval */
                lastRelistAt = 0L
                drawerAdapter?.clear()
                drawerAdapter?.addAll(fresh.ordered.map { it.removeSuffix(".txt") })
                drawerAdapter?.notifyDataSetChanged()
            }
            onDone()
        }
        return true
    }

    /* The short-page poll chain. Every exit that is not "the reader is
       gone" or "the download is over" RE-ARMS rather than returns: the
       first version returned on `speaking` and `loading`, and a single tick
       landing inside a TTS session or an openAt window killed the chain for
       good — the reader silently stopped following the download again, one
       Play press deep. The novel's own busy state is the only terminator;
       normKey directly, not the slugKey URL fallback, so this matches every
       other isBusy caller. */
    /* One tracked Runnable, not anonymous posts: onStop unhooks it (the
       chain only ever runs !speaking, so a backgrounded reader with no TTS
       has no business walking a 7k-file folder every 30s) and onStart puts
       it back; the single slot also collapses duplicate chains from repeated
       play/pause cycles into one. */
    private var relistTick: Runnable? = null

    private fun armShortPageRelist(n: Int) {
        if (isDestroyed) return
        fun busy() = DownloadService.isBusy(Ownership.normKey(intent.getStringExtra("slug") ?: ""))
        fun rearm() {
            if (!busy()) { relistTick = null; return }
            relistTick?.let { scroll.removeCallbacks(it) }
            val r = Runnable { armShortPageRelist(n) }
            relistTick = r
            scroll.postDelayed(r, RELIST_MS)
        }
        if (speaking || loading) { rearm(); return }
        val started = relistForNewChapters {
            if (!speaking && nextIdx < (chapters?.ordered?.size ?: 0)) appendChapters(n)
            else rearm()
        }
        if (!started) rearm()
    }

    private fun appendChapters(n: Int) {
        val ch = chapters ?: return
        if (loading || nextIdx >= ch.ordered.size) return
        /* A rename has moved the listing since we read it, so nextIdx no
           longer names the chapter it named: appending would put the WRONG
           chapter on the end of the buffer, and while reading aloud, speak it.
           Hand the job to the resync — it carries the buffer across by the
           headings inside the files — and drop this append. Reading stops the
           way the end of the book stops it: silence that can be restarted,
           rather than a chapter nobody asked for. */
        if (DownloadEngine.renameEpochOf(intent.getStringExtra("slug") ?: "") != chaptersEpoch) {
            askResync()
            /* nothing is going to arrive for TTS to continue into */
            pendingSpeakContinue = false
            if (speaking) stopTts()
            return
        }
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
                } else {
                    /* the buffer runs straight from one chapter into the one
                       after next with nothing to show for it — say so */
                    DownloadService.appendLog(
                        "could not read ${ch.ordered.getOrNull(idx)} — skipped in the reader",
                    )
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
                updateHeader()
                /* Ask again if the page still isn't tall enough to scroll.
                   Every other top-up is driven by a scroll event, and a page
                   that doesn't scroll produces none — with a batch of twenty
                   that could not happen, with a small one and short chapters
                   it can, and the reader would sit at the end of the buffer
                   with more novel behind it and no way to ask for it. */
                if (!speaking && loadReady && nextIdx < ch.ordered.size &&
                    text.height in 1 until scroll.height * 3 / 2
                ) {
                    appendChapters(n)
                } else if (!speaking && loadReady &&
                    nextIdx >= (chapters?.ordered?.size ?: 0) &&
                    text.height in 1 until scroll.height * 3 / 2
                ) {
                    /* Out of listing on a page too short to scroll: no scroll
                       event will EVER arrive — a ScrollView whose content is
                       smaller than the viewport dispatches nothing — so this
                       branch is its own event source. A check that finds
                       nothing re-asks after the window, but only while the
                       novel is actually downloading: "nothing yet" is the
                       NORMAL answer seconds after an adoption, and without
                       the retry the reader silently stopped following the
                       download it was opened to follow. A finished novel
                       stops polling the moment the service lets go. */
                    armShortPageRelist(n)
                }
            }
        }
    }

    /* load up to n chapters above the current content in ONE text update,
       keeping the reader's place (single anchor compensation) */
    private fun prependChapters(n: Int) {
        val ch = chapters ?: return
        /* same stale-index hazard the append gate blocks: after a rename,
           firstIdx - 1 names the neighbour's text. Not spoken (prepends are
           !speaking-only), but wrong text on screen is still wrong text. */
        if (DownloadEngine.renameEpochOf(intent.getStringExtra("slug") ?: "") != chaptersEpoch) {
            askResync()
            return
        }
        if (loading || firstIdx <= 0) return
        loading = true
        lifecycleScope.launch {
            /* gather the chapters directly above, kept in ascending order */
            val bodies = ArrayList<Pair<Int, String>>()
            var idx = firstIdx - 1
            while (idx >= 0 && bodies.size < n) {
                val b = readAt(idx)
                if (b == null) {
                    /* stops the prepend here, leaving a hole above with
                       nothing on screen to explain it */
                    DownloadService.appendLog(
                        "could not read ${ch.ordered.getOrNull(idx)} — skipped in the reader",
                    )
                    break
                }
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
