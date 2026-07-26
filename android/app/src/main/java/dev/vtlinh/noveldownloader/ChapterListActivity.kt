package dev.vtlinh.noveldownloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* Reading mode, screen 2: the chapters of one novel, in order. Tapping a
   chapter opens the reader there. */
class ChapterListActivity : AppCompatActivity() {

    companion object {
        /* accepts "Chapter 70.txt", "Chapter 70-71.txt" AND legacy names
           with a title suffix like "Chapter 70 - Hoan chinh van.txt" */
        val CHAPTER_RE = Regex("Chapter (\\d+)(?:-(\\d+))?.*\\.txt")

        class Chapters(
            val ordered: List<String>,               // chapter filenames in order
            val source: Map<String, String>,         // name -> docId or gz ref
            val translated: Map<String, String>,     // name -> docId or gz ref
        )

        /* does this docId still resolve? one single-row query — the cheap
           validity check for the cached listing */
        private fun docExists(
            cr: android.content.ContentResolver,
            treeUri: Uri,
            docId: String,
        ): Boolean = try {
            cr.query(
                android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null,
            )?.use { it.moveToFirst() } == true
        } catch (e: Exception) {
            false
        }

        /* is this cached ref still backed by something on disk? */
        private fun refUsable(
            context: android.content.Context,
            cr: android.content.ContentResolver,
            treeUri: Uri,
            ref: String,
        ): Boolean = when {
            Zips.isGzRef(ref) -> docExists(cr, treeUri, Zips.gzDocId(ref))
            else -> docExists(cr, treeUri, ref)
        }

        /* The chapters of one novel dir, with refs for both languages —
           loose .txt files and/or their compressed .gz form (loose wins,
           so chapters downloaded after compressing still show up).
           Ordered by the site's own listing sequence when known.

           When `slug` is given, the resolved listing is CACHED in the DB
           (chlist) and reused — spot-checked with two single-row queries —
           so a 7k-chapter novel opens without re-listing its folder. The
           cache is invalidated whenever chapters/order change or the
           compress pass rewrites refs. */
        fun chapterNames(
            context: android.content.Context,
            treeUri: Uri,
            dirName: String,
            siteOrder: Map<String, Int> = emptyMap(),
            slug: String? = null,
        ): Chapters {
            val cr = context.contentResolver
            val folder = treeUri.toString()
            val store = if (slug != null) DownloadStore(context) else null
            if (slug != null && store != null) {
                val cached = try { store.getChapterList(folder, slug) } catch (e: Exception) { null }
                if (cached != null) {
                    val firstRef = cached.source[cached.ordered.first()]
                    val lastRef = cached.source[cached.ordered.last()]
                    val ok = firstRef != null && lastRef != null &&
                        refUsable(context, cr, treeUri, firstRef) &&
                        refUsable(context, cr, treeUri, lastRef)
                    if (ok) return Chapters(cached.ordered, cached.source, cached.translated)
                    try { store.clearChapterList(folder, slug) } catch (e: Exception) {}
                }
            }
            val dirs = Saf.children(cr, treeUri, Saf.rootId(treeUri))
            val dir = dirs.firstOrNull { it.isDir && it.name == dirName }
                ?: return Chapters(emptyList(), emptyMap(), emptyMap())
            val source = HashMap<String, String>()
            val gzSource = HashMap<String, String>()
            var translatedId: String? = null
            for (e in Saf.children(cr, treeUri, dir.docId)) {
                if (e.isDir && e.name == "translated") translatedId = e.docId
                else if (!e.isDir && Zips.isGzName(e.name)) {
                    val n = e.name.removeSuffix(".gz")
                    if (CHAPTER_RE.matches(n)) gzSource[n] = Zips.gzRef(e.docId)
                } else if (!e.isDir && CHAPTER_RE.matches(e.name)) source[e.name] = e.docId
            }
            val translated = HashMap<String, String>()
            val gzTranslated = HashMap<String, String>()
            translatedId?.let {
                for (e in Saf.children(cr, treeUri, it)) {
                    if (e.isDir) continue
                    if (Zips.isGzName(e.name)) {
                        val n = e.name.removeSuffix(".gz")
                        if (CHAPTER_RE.matches(n)) gzTranslated[n] = Zips.gzRef(e.docId)
                    } else if (CHAPTER_RE.matches(e.name)) translated[e.name] = e.docId
                }
            }
            /* compressed chapters fill in behind loose .txt files */
            for ((n, r) in gzSource) if (n !in source) source[n] = r
            for ((n, r) in gzTranslated) if (n !in translated) translated[n] = r
            translated.keys.retainAll(source.keys)
            /* The site's listing sequence is the order, and it has to be: a
               site can name chapters after their titles rather than number
               them, so the number in a filename is only as good as what
               could be parsed out of a title. The listing is what the site
               itself presents.

               A chapter the listing doesn't cover — added since the order was
               recorded, or left by an older build — still has to land in the
               right place rather than at the end, so slot it just after the
               last listed chapter that precedes it by number. With no
               recorded order at all every chapter takes that path, which
               leaves a plain numeric sort. */
            val numberOf = { n: String ->
                CHAPTER_RE.find(n)?.groupValues?.get(1)?.toIntOrNull()
            }
            val ordinalByNumber = java.util.TreeMap<Int, Int>()
            for ((fn, ord) in siteOrder) {
                val n = numberOf(fn) ?: continue
                val prev = ordinalByNumber[n]
                if (prev == null || ord < prev) ordinalByNumber[n] = ord
            }
            /* (slot, tie-break), computed once rather than on every compare.
               A listed chapter ties at 0, so an unlisted one sharing its slot
               follows it and both stay ahead of the next listed chapter. */
            val rank = HashMap<String, Pair<Int, Int>>(source.size)
            for (name in source.keys) {
                val listed = siteOrder[name]
                rank[name] = if (listed != null) {
                    Pair(listed, 0)
                } else {
                    val n = numberOf(name)
                    if (n == null) Pair(Int.MAX_VALUE, Int.MAX_VALUE)
                    else Pair(ordinalByNumber.floorEntry(n)?.value ?: -1, n)
                }
            }
            val ordered = source.keys.sortedWith(
                compareBy(
                    { rank[it]?.first ?: Int.MAX_VALUE },
                    { rank[it]?.second ?: Int.MAX_VALUE },
                    { numberOf(it) ?: Int.MAX_VALUE },
                    { it },
                ),
            )
            /* remember the resolved listing so the next open skips the walk */
            if (slug != null && store != null && ordered.isNotEmpty()) {
                try {
                    store.saveChapterList(
                        folder, slug,
                        CachedChapterList(ordered, source, translated),
                    )
                } catch (e: Exception) {}
            }
            return Chapters(ordered, source, translated)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chapters)
        val dirName = intent.getStringExtra("dir") ?: return finish()
        val title = intent.getStringExtra("title") ?: dirName
        findViewById<TextView>(R.id.chapterTitle).text = title

        /* same navigation drawer as the other list screens */
        val drawer = findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawerLayout)
        findViewById<TextView>(R.id.menuBtn).setOnClickListener {
            drawer.openDrawer(androidx.core.view.GravityCompat.START)
        }
        findViewById<TextView>(R.id.navHome).setOnClickListener {
            /* REORDER_TO_FRONT, not CLEAR_TOP: clearing would destroy the
               activities above home — including a reader that's reading
               aloud. Reordering brings home forward and leaves TTS alive. */
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            finish()
        }
        findViewById<TextView>(R.id.navBrowser).setOnClickListener {
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
            startActivity(Intent(this, BrowserActivity::class.java))
        }
        findViewById<TextView>(R.id.navNovels).setOnClickListener {
            startActivity(Intent(this, NovelListActivity::class.java))
            finish()
        }
        findViewById<TextView>(R.id.navSettings).setOnClickListener {
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.navAbout).setOnClickListener {
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    /* (re)load on every return to this screen — onResume also follows
       onCreate, so the first load happens here too — keeping the
       current-chapter highlight in sync with what was just read */
    override fun onResume() {
        super.onResume()
        load()
        startLiveRefresh()
    }

    override fun onPause() {
        super.onPause()
        liveJob?.cancel()
        liveJob = null
    }

    private var loadedOnce = false
    private var loading = false
    private var liveJob: kotlinx.coroutines.Job? = null

    /* While this novel is downloading, fold newly saved chapters in as they
       land. Every saved chapter invalidates the listing cache, so a refresh
       re-walks the folder — hence the interval, the skip while one is still
       running, and one final pass once the download stops. */
    private fun startLiveRefresh() {
        val slug = intent.getStringExtra("slug") ?: return
        val key = slug.lowercase().filter { it.isLetterOrDigit() }
        liveJob?.cancel()
        liveJob = lifecycleScope.launch {
            var wasBusy = DownloadService.isBusy(key)
            while (true) {
                kotlinx.coroutines.delay(5_000)
                val busy = DownloadService.isBusy(key)
                if (busy || wasBusy) load(preserveScroll = true)
                wasBusy = busy
            }
        }
    }

    private fun load(preserveScroll: Boolean = false) {
        val dirName = intent.getStringExtra("dir") ?: return finish()
        val title = intent.getStringExtra("title") ?: dirName
        val status = findViewById<TextView>(R.id.statusText)
        val listView = findViewById<ListView>(R.id.chapterListView)
        val folder = getSharedPreferences("app", MODE_PRIVATE).getString("tree", null) ?: return finish()

        /* a live refresh landing on top of an in-flight one would just walk
           the folder twice — let the running one finish */
        if (loading) return
        loading = true
        if (!loadedOnce) status.text = "Loading…"
        loadedOnce = true
        val slug = intent.getStringExtra("slug")
        /* ⇅ flips between reading order and newest-first (kept per novel) */
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        val descKey = "chSortDesc:${slug ?: dirName}"
        findViewById<TextView>(R.id.sortBtn).setOnClickListener {
            prefs.edit().putBoolean(descKey, !prefs.getBoolean(descKey, false)).apply()
            load()
        }
        lifecycleScope.launch {
          try {
            val chapters = withContext(Dispatchers.IO) {
                val order = slug?.let {
                    try { DownloadStore(this@ChapterListActivity).getChapterOrder(folder, it) } catch (e: Exception) { null }
                } ?: emptyMap()
                chapterNames(this@ChapterListActivity, Uri.parse(folder), dirName, order, slug)
            }
            val ordered = chapters.ordered.let {
                if (prefs.getBoolean(descKey, false)) it.reversed() else it
            }
            if (ordered.isEmpty()) {
                status.text = "No chapters found in \"$dirName\"."
                return@launch
            }
            status.text = "${ordered.size} chapter(s)"
            val labels = ordered.map { it.removeSuffix(".txt") }
            /* the chapter currently being read: highlighted and scrolled into
               view (at ~20% of the list height) */
            val lastName = slug?.let {
                ReaderActivity.resumeChapter(this@ChapterListActivity, it)
            }
            val currentPos = lastName?.let { ordered.indexOf(it) } ?: -1
            /* swapping the adapter drops the scroll position; note where the
               list is sitting so a live refresh can put it back */
            val keepPos = listView.firstVisiblePosition
            val keepTop = listView.getChildAt(0)?.top ?: 0
            listView.adapter = object : ArrayAdapter<String>(
                this@ChapterListActivity, android.R.layout.simple_list_item_1, labels,
            ) {
                override fun getView(
                    position: Int,
                    convertView: android.view.View?,
                    parent: android.view.ViewGroup,
                ): android.view.View {
                    val v = super.getView(position, convertView, parent) as TextView
                    if (position == currentPos) {
                        v.setTextColor(getColor(R.color.accent))
                        v.setTypeface(null, android.graphics.Typeface.BOLD)
                    } else {
                        v.setTextColor(getColor(R.color.fg))
                        v.setTypeface(null, android.graphics.Typeface.NORMAL)
                    }
                    return v
                }
            }
            if (preserveScroll) {
                /* chapters arriving underneath shouldn't move the reader's
                   place in the list */
                listView.setSelectionFromTop(keepPos, keepTop)
            } else if (currentPos >= 0) {
                listView.post {
                    listView.setSelectionFromTop(currentPos, (listView.height * 0.2f).toInt())
                }
            }
            listView.setOnItemClickListener { _, _, pos, _ ->
                /* REORDER_TO_FRONT: if the reader for this novel is still
                   alive behind us (e.g. reading aloud), bring THAT instance
                   forward (it gets onNewIntent and jumps to the chapter)
                   instead of building a new reader over it */
                startActivity(
                    Intent(this@ChapterListActivity, ReaderActivity::class.java)
                        .putExtra("dir", dirName)
                        .putExtra("title", title)
                        .putExtra("slug", intent.getStringExtra("slug"))
                        .putExtra("start", ordered[pos])
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }
          } finally {
            loading = false
          }
        }
    }
}
