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
        /* lives in ChapterName so it can be unit-tested without loading an
           Activity; kept here because every call site already reads it here */
        val CHAPTER_RE = ChapterName.RE

        /* how many places in a cached listing to spot-check before trusting it */
        private const val CACHE_PROBES = 5

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
                    /* Has anything ARRIVED? The probes below only ask after
                       what the cache already knows about, so a translation
                       written into translated/ after the listing was cached
                       was invisible for ever — see Folder.Stamp. Two
                       single-row queries, not a second walk. */
                    val here = cached.stamp?.let { s ->
                        Folder.Stamp(
                            s.dirId,
                            Saf.modified(cr, treeUri, s.dirId),
                            s.trId,
                            /* no translated/ when this was cached: its
                               creation moves the novel folder's own mtime,
                               which the other half of the stamp catches */
                            if (s.trId.isEmpty()) 0L else Saf.modified(cr, treeUri, s.trId),
                        )
                    }
                    /* Probing only the ends missed anything removed between
                       them — a chapter deleted outside the app stayed in the
                       listing, and the reader silently skipped over it with no
                       gap shown. Spread the sample across the novel; still a
                       handful of lookups, not a walk. */
                    val ok = here != null &&
                        Folder.folderUnchanged(cached.stamp, here) &&
                        Folder.cacheValid(
                            cached.ordered, cached.source, CACHE_PROBES,
                        ) { ref -> refUsable(context, cr, treeUri, ref) }
                    if (ok) return Chapters(cached.ordered, cached.source, cached.translated)
                    try { store.clearChapterList(folder, slug) } catch (e: Exception) {}
                }
            }
            /* Taken before the walk. Listing a 7k-file folder over SAF takes
               seconds, and a chapter saved during it clears the cache before
               we finish — so writing our result afterwards would put back a
               listing that never had that chapter, and nothing clears it
               again once the download ends. */
            val epoch = if (store != null && slug != null) store.chapterListEpoch(folder, slug) else -1L
            val dirs = Saf.children(cr, treeUri, Saf.rootId(treeUri))
            val dir = dirs.firstOrNull { it.isDir && it.name == dirName }
                ?: return Chapters(emptyList(), emptyMap(), emptyMap())
            /* The rules for what a folder holds live in Folder, which has no
               Android in it — see FolderTest. Three defects in as many
               releases came out of reasoning about them in place, and SAF
               cannot be faked off-device, so the folder is handed over as a
               list. This end does the walking; that end does the deciding. */
            /* Each mtime is read BEFORE the listing it stamps. The other way
               round, a file written between the listing and the read would be
               missing from the listing and yet stamped as already seen, and
               the cache would answer for it for good. Read first and the same
               race costs one extra walk instead. */
            val dirMod = Saf.modified(cr, treeUri, dir.docId)
            val kids = Saf.children(cr, treeUri, dir.docId)
            fun items(list: List<Saf.Entry>) =
                list.map { Folder.Item(it.name, it.docId, it.isDir, it.size) }
            val translatedId = kids.firstOrNull { it.isDir && it.name == "translated" }?.docId
            val trMod = translatedId?.let { Saf.modified(cr, treeUri, it) } ?: 0L
            val got = Folder.resolve(
                items(kids),
                translatedId?.let { items(Saf.children(cr, treeUri, it)) } ?: emptyList(),
                siteOrder,
            )
            val ordered = got.ordered
            val source = got.source
            val translated = got.translated

            /* remember the resolved listing so the next open skips the walk */
            if (slug != null && store != null && ordered.isNotEmpty()) {
                try {
                    store.saveChapterList(
                        folder, slug,
                        CachedChapterList(
                            ordered, source, translated,
                            Folder.Stamp(dir.docId, dirMod, translatedId ?: "", trMod),
                        ),
                        epoch,
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

        /* This novel's own settings. Only reachable with a slug — that is what
           identifies the novel to the store, and a folder-scanned row opened
           by directory name alone has nothing to key its settings on. */
        val settingsBtn = findViewById<TextView>(R.id.novelSettingsBtn)
        val slugForSettings = intent.getStringExtra("slug")
        if (slugForSettings.isNullOrEmpty()) {
            settingsBtn.visibility = android.view.View.GONE
        } else {
            settingsBtn.setOnClickListener {
                startActivity(
                    Intent(this, NovelSettingsActivity::class.java)
                        .putExtra("slug", slugForSettings)
                        .putExtra("dir", dirName)
                        .putExtra("title", title),
                )
            }
        }

        /* same navigation drawer as the other list screens */
        val drawer = findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawerLayout)
        findViewById<TextView>(R.id.menuBtn).setOnClickListener {
            drawer.openDrawer(androidx.core.view.GravityCompat.START)
        }
        findViewById<TextView>(R.id.navBrowser).setOnClickListener {
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
            startActivity(Intent(this, BrowserActivity::class.java))
        }
        findViewById<TextView>(R.id.navNovels).setOnClickListener {
            /* REORDER_TO_FRONT, not CLEAR_TOP: clearing would destroy the
               activities above the library — including a reader that's
               reading aloud. Reordering brings the library forward and
               leaves TTS alive. */
            startActivity(
                Intent(this, NovelListActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
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

        ConsoleFooter.attach(this, findViewById(R.id.consoleFooter))
    }

    /* The reader leaves by bringing a chapter list forward with
       REORDER_TO_FRONT, and the instance that comes forward is whichever one
       is in the stack — which can be another NOVEL's, left there by earlier
       navigation. Reordering delivers the new intent here rather than
       recreating, and without adopting it the screen showed the old novel's
       chapters under the old title, with the ⚙ opening the old novel's
       settings. onResume follows this and reloads. */
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        val changed = newIntent.getStringExtra("dir") != intent.getStringExtra("dir") ||
            newIntent.getStringExtra("slug") != intent.getStringExtra("slug")
        setIntent(newIntent)
        if (changed) {
            /* a different novel: forget the old list rather than briefly
               showing it — and recreate so onCreate rewires the ⚙ button,
               whose click listener captured the OLD novel's slug */
            recreate()
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

    /* how many rows the last render put up, so a live refresh can tell how
       many chapters arrived and which way they shifted the indices */
    private var renderedCount = 0
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
                /* the provider throws when the persisted grant is gone or a
                   cursor window overflows, and this coroutine has no handler:
                   an empty list is a screen, an exception is a crash */
                try {
                    chapterNames(this@ChapterListActivity, Uri.parse(folder), dirName, order, slug)
                } catch (e: Exception) { null }
            } ?: run {
                status.text = "Could not read \"$dirName\"."
                return@launch
            }
            val ordered = chapters.ordered.let {
                if (prefs.getBoolean(descKey, false)) it.reversed() else it
            }
            if (ordered.isEmpty()) {
                status.text = "No chapters found in \"$dirName\"."
                return@launch
            }
            status.text = "${ordered.size} chapter(s)"
            val lastRenderedCount = renderedCount
            renderedCount = ordered.size
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
                /* Chapters arriving underneath shouldn't move the reader's
                   place in the list. Ascending, they land at the end and every
                   index keeps its meaning — but newest-first PREPENDS them, so
                   restoring the same index walked the list backwards by the
                   number that arrived, visibly jumping every few seconds for
                   the length of a download. Shift by how many appeared. */
                val grew = ordered.size - lastRenderedCount
                val shift = if (grew > 0 && prefs.getBoolean(descKey, false)) grew else 0
                listView.setSelectionFromTop(keepPos + shift, keepTop)
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
