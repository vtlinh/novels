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
            val source: Map<String, String>,         // name -> docId or zip ref
            val translated: Map<String, String>,     // name -> docId or zip ref
            val zip: java.io.File? = null,           // cached chapters.zip, if any
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
            zipDocId: String?,
        ): Boolean = when {
            Zips.isRef(ref) -> zipDocId != null && docExists(cr, treeUri, zipDocId)
            Zips.isGzRef(ref) -> docExists(cr, treeUri, Zips.gzDocId(ref))
            else -> docExists(cr, treeUri, ref)
        }

        /* The chapters of one novel dir, with refs for both languages —
           loose .txt files and/or entries of a chapters.zip (loose wins,
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
                        refUsable(context, cr, treeUri, firstRef, cached.zipDocId) &&
                        refUsable(context, cr, treeUri, lastRef, cached.zipDocId)
                    if (ok) {
                        val zipFile = cached.zipDocId?.let {
                            try { Zips.cached(context, cr, treeUri, it, dirName) } catch (e: Exception) { null }
                        }
                        return Chapters(cached.ordered, cached.source, cached.translated, zipFile)
                    }
                    try { store.clearChapterList(folder, slug) } catch (e: Exception) {}
                }
            }
            val dirs = Saf.children(cr, treeUri, Saf.rootId(treeUri))
            val dir = dirs.firstOrNull { it.isDir && it.name == dirName }
                ?: return Chapters(emptyList(), emptyMap(), emptyMap())
            val source = HashMap<String, String>()
            val gzSource = HashMap<String, String>()
            var translatedId: String? = null
            var zipDocId: String? = null
            for (e in Saf.children(cr, treeUri, dir.docId)) {
                if (e.isDir && e.name == "translated") translatedId = e.docId
                else if (!e.isDir && Zips.isZipName(e.name)) zipDocId = e.docId
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
            /* zipped chapters fill in anything not present as a loose file */
            val zipFile = zipDocId?.let { Zips.cached(context, cr, treeUri, it, dirName) }
            zipFile?.let { zf ->
                for (entry in Zips.entries(zf)) {
                    if (entry.startsWith("translated/")) {
                        val n = entry.removePrefix("translated/")
                        if (CHAPTER_RE.matches(n) && n !in translated) translated[n] = Zips.ref(entry)
                    } else if (CHAPTER_RE.matches(entry) && entry !in source) {
                        source[entry] = Zips.ref(entry)
                    }
                }
            }
            translated.keys.retainAll(source.keys)
            val ordered = source.keys.sortedWith(
                compareBy(
                    { siteOrder[it] ?: Int.MAX_VALUE },
                    { CHAPTER_RE.find(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE },
                    { it },
                ),
            )
            /* remember the resolved listing so the next open skips the walk */
            if (slug != null && store != null && ordered.isNotEmpty()) {
                try {
                    store.saveChapterList(
                        folder, slug,
                        CachedChapterList(ordered, source, translated, zipDocId),
                    )
                } catch (e: Exception) {}
            }
            return Chapters(ordered, source, translated, zipFile)
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
    }

    private var loadedOnce = false

    private fun load() {
        val dirName = intent.getStringExtra("dir") ?: return finish()
        val title = intent.getStringExtra("title") ?: dirName
        val status = findViewById<TextView>(R.id.statusText)
        val listView = findViewById<ListView>(R.id.chapterListView)
        val folder = getSharedPreferences("app", MODE_PRIVATE).getString("tree", null) ?: return finish()

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
                getSharedPreferences("app", MODE_PRIVATE).getString("lastCh:$it", null)
            }
            val currentPos = lastName?.let { ordered.indexOf(it) } ?: -1
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
            if (currentPos >= 0) {
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
        }
    }
}
