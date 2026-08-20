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

/* Reading mode, screen 2: one novel. Info tab holds the synopsis (always
   fully expanded) and a sticky Continue above the tab bar; Chapters tab
   is the ordered list. Tapping a chapter opens the reader there. */
class ChapterListActivity : AppCompatActivity() {

    companion object {
        /* accepts "Chapter 70.txt", "Chapter 70-71.txt" AND legacy names
           with a title suffix like "Chapter 70 - Hoan chinh van.txt" */
        /* lives in ChapterName so it can be unit-tested without loading an
           Activity; kept here because every call site already reads it here */
        val CHAPTER_RE = ChapterName.RE

        /* how many places in a cached listing to spot-check before trusting it */
        private const val CACHE_PROBES = 5

        /* List window: open with this many chapters on each side of the
           current one, then grow by the same amount when the user scrolls
           within EXPAND_NEAR of either edge. A 7k-chapter novel otherwise
           builds a 7k-row adapter and jumps to a five-digit index. */
        private const val WINDOW_RADIUS = 50
        private const val EXPAND_BY = 50
        private const val EXPAND_NEAR = 10

        private const val STATE_ON_INFO_TAB = "onInfoTab"
        private const val STATE_TAB_SLUG = "tabSlug"

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
        Nav.bindDrawer(this, Nav.Screen.CHAPTERS)

        ConsoleFooter.attach(this, findViewById(R.id.consoleFooter))
        onInfoTab = savedInstanceState?.let { state ->
            if (state.getString(STATE_TAB_SLUG) == intent.getStringExtra("slug")) {
                state.getBoolean(STATE_ON_INFO_TAB, true)
            } else true
        } ?: true
        findViewById<android.view.View>(R.id.tabInfo).setOnClickListener { showTab(true) }
        findViewById<android.view.View>(R.id.tabChapters).setOnClickListener { showTab(false) }
        showTab(onInfoTab)
        bindNovelInfo()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ON_INFO_TAB, onInfoTab)
        outState.putString(STATE_TAB_SLUG, intent.getStringExtra("slug"))
    }

    /* Info tab first: that's where the synopsis and the sticky Continue
       live. Chapters is a tap away. Sort only applies to the list, so it
       hides with that tab. Inactive tab is INVISIBLE so the ListView still
       lays out and scroll-to-current has a real height. */
    private fun showTab(info: Boolean) {
        onInfoTab = info
        findViewById<android.view.View>(R.id.infoTab).visibility =
            if (info) android.view.View.VISIBLE else android.view.View.INVISIBLE
        findViewById<android.view.View>(R.id.chaptersTab).visibility =
            if (info) android.view.View.INVISIBLE else android.view.View.VISIBLE
        findViewById<android.view.View>(R.id.sortBtn).visibility =
            if (info) android.view.View.GONE else android.view.View.VISIBLE
        findViewById<android.view.View>(R.id.tabInfo).isSelected = info
        findViewById<android.view.View>(R.id.tabChapters).isSelected = !info
        findViewById<android.view.View>(R.id.tabInfoIndicator).setBackgroundColor(
            if (info) getColor(R.color.accent) else android.graphics.Color.TRANSPARENT,
        )
        findViewById<android.view.View>(R.id.tabChaptersIndicator).setBackgroundColor(
            if (info) android.graphics.Color.TRANSPARENT else getColor(R.color.accent),
        )
        val infoLabel = findViewById<TextView>(R.id.tabInfoLabel)
        val chaptersLabel = findViewById<TextView>(R.id.tabChaptersLabel)
        infoLabel.setTextColor(getColor(if (info) R.color.accent else R.color.muted))
        infoLabel.setTypeface(null, if (info) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        chaptersLabel.setTextColor(getColor(if (info) R.color.muted else R.color.accent))
        chaptersLabel.setTypeface(null, if (info) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
    }

    /* which tab is showing; Info is the default so Continue is on screen */
    private var onInfoTab = true

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun showContinue(show: Boolean) {
        findViewById<android.view.View>(R.id.continueBar).visibility =
            if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun openChapter(name: String) {
        val dirName = intent.getStringExtra("dir") ?: return
        val title = intent.getStringExtra("title") ?: dirName
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra("dir", dirName)
                .putExtra("title", title)
                .putExtra("slug", intent.getStringExtra("slug"))
                .putExtra("start", name)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    /* Novel-page info on the Info tab: cover, ranking, full synopsis.
       Fills from the store first; if description (or everything) is still
       blank, fetches the novel page once to fill in. */
    private fun bindNovelInfo() {
        val card = findViewById<android.widget.LinearLayout>(R.id.novelCard)
        val panel = findViewById<android.widget.LinearLayout>(R.id.novelInfo)
        val slug = intent.getStringExtra("slug")
        val folder = getSharedPreferences("app", MODE_PRIVATE).getString("tree", null)
        if (slug.isNullOrEmpty() || folder.isNullOrEmpty()) {
            card.visibility = android.view.View.GONE
            return
        }
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        /* Cover first so the card isn't a blank hole while the store answers. */
        card.visibility = android.view.View.VISIBLE
        bindCover(slug, intent.getStringExtra("title").orEmpty())
        lifecycleScope.launch {
            var rec = withContext(Dispatchers.IO) {
                try { DownloadStore(this@ChapterListActivity).novel(folder, slug) } catch (e: Exception) { null }
            }
            /* Old downloads only have author (if that). Pull the rest when the
               synopsis is still missing and we know where the novel lives. */
            if (rec != null && rec.description.isEmpty() && rec.url.isNotEmpty()) {
                val url = rec.url
                val fetched = withContext(Dispatchers.IO) {
                    try {
                        val info = NovelPageInfo.fetch(url) ?: return@withContext null
                        DownloadStore(this@ChapterListActivity).setNovelInfo(
                            folder, slug,
                            author = info.author,
                            altNames = info.altNames,
                            genres = info.genres,
                            source = info.source,
                            description = info.description,
                            statusLabel = info.statusLabel,
                        )
                        info
                    } catch (e: Exception) { null }
                }
                if (fetched != null) {
                    rec = withContext(Dispatchers.IO) {
                        try { DownloadStore(this@ChapterListActivity).novel(folder, slug) } catch (e: Exception) { rec }
                    }
                }
            }
            renderNovelInfo(card, panel, rec, slug, prefs)
        }
    }

    private fun chip(label: String, textColor: Int, background: Int): TextView =
        TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(getColor(textColor))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundResource(background)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    private fun bindCover(slug: String, title: String) {
        findViewById<android.widget.FrameLayout>(R.id.coverWrap).clipToOutline = true
        val img = findViewById<android.widget.ImageView>(R.id.coverImage)
        val letter = findViewById<TextView>(R.id.coverLetter)
        val file = DownloadEngine.coverFile(this, slug)
        val bmp = if (file.exists()) {
            try {
                android.graphics.BitmapFactory.decodeFile(
                    file.path,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 },
                )
            } catch (e: Exception) { null }
        } else null
        if (bmp != null) {
            img.setImageBitmap(bmp)
            img.visibility = android.view.View.VISIBLE
            letter.visibility = android.view.View.GONE
        } else {
            img.setImageDrawable(null)
            img.visibility = android.view.View.GONE
            letter.visibility = android.view.View.VISIBLE
            letter.text = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "·"
        }
    }

    private fun renderNovelInfo(
        card: android.widget.LinearLayout,
        panel: android.widget.LinearLayout,
        rec: NovelRec?,
        slug: String,
        prefs: android.content.SharedPreferences,
    ) {
        panel.removeAllViews()
        val author = rec?.author.orEmpty()
        val alt = rec?.altNames.orEmpty()
        val genres = rec?.genres.orEmpty()
        val source = rec?.source.orEmpty()
        /* Host we fetched from, not the publisher `source` chip. A folder
           scan has no URL, so nothing to show until Check status finds it. */
        val website = rec?.url?.let { Sites.website(it) }.orEmpty()
        val status = rec?.statusLabel.orEmpty().ifEmpty {
            when {
                rec == null -> ""
                rec.complete -> "Completed"
                rec.total > 0 -> "Ongoing"
                else -> ""
            }
        }
        val desc = rec?.description.orEmpty()
        /* Always show the card when we have a slug — the user can rate
           even before any site info has been scraped. */
        card.visibility = android.view.View.VISIBLE
        bindCover(slug, intent.getStringExtra("title").orEmpty())

        val authorView = findViewById<TextView>(R.id.heroAuthor)
        if (author.isNotEmpty()) {
            authorView.text = author
            authorView.visibility = android.view.View.VISIBLE
        } else {
            authorView.visibility = android.view.View.GONE
        }

        val genresView = findViewById<TextView>(R.id.heroGenres)
        val genreLine = genres.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" · ")
        if (genreLine.isNotEmpty()) {
            genresView.text = genreLine
            genresView.visibility = android.view.View.VISIBLE
        } else {
            genresView.visibility = android.view.View.GONE
        }

        val chips = findViewById<android.widget.LinearLayout>(R.id.chipsRow)
        chips.removeAllViews()
        if (status.isNotEmpty()) {
            val completed = status.equals("Completed", ignoreCase = true)
            chips.addView(
                chip(
                    status,
                    if (completed) R.color.ok_fg else R.color.accent,
                    if (completed) R.drawable.bg_chip_ok else R.drawable.bg_chip_accent,
                ),
            )
        }
        if (website.isNotEmpty()) {
            chips.addView(chip(website, R.color.fg, R.drawable.bg_chip))
        }
        if (source.isNotEmpty()) {
            chips.addView(chip(source, R.color.fg, R.drawable.bg_chip))
        }
        chips.visibility = android.view.View.VISIBLE
        findViewById<android.view.View>(R.id.chipsScroll).visibility =
            if (chips.childCount > 0) android.view.View.VISIBLE else android.view.View.GONE

        if (alt.isNotEmpty()) {
            panel.addView(
                TextView(this).apply {
                    text = alt
                    textSize = 12f
                    setTextColor(getColor(R.color.muted))
                    setPadding(0, dp(8), 0, 0)
                    setLineSpacing(0f, 1.25f)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
        }

        /* Personal ranking: ten full stars. Tap the current value to clear. */
        val starsRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(10), 0, dp(2))
        }
        fun paintStars(current: Int) {
            starsRow.removeAllViews()
            for (i in 1..NovelRating.MAX) {
                starsRow.addView(
                    TextView(this).apply {
                        text = "★"
                        textSize = 20f
                        setTextColor(
                            getColor(if (i <= current) R.color.star else R.color.muted),
                        )
                        setPadding(dp(3), dp(4), dp(3), dp(4))
                        setOnClickListener {
                            val next = NovelRating.toggle(prefs, slug, i)
                            paintStars(next)
                        }
                    },
                )
            }
        }
        paintStars(NovelRating.get(prefs, slug))
        panel.addView(starsRow)

        if (desc.isNotEmpty()) {
            panel.addView(
                TextView(this).apply {
                    text = desc
                    textSize = 14f
                    setTextColor(getColor(R.color.fg))
                    setLineSpacing(0f, 1.4f)
                    setPadding(0, dp(8), 0, 0)
                },
            )
        }
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

    /* how many rows the last FULL listing had, so a live refresh can tell how
       many chapters arrived and which way they shifted the indices */
    private var renderedCount = 0
    private var liveJob: kotlinx.coroutines.Job? = null

    /* Full chapter order for this novel (display order already applied). The
       ListView only holds [winStart, winEnd); scroll near an edge widens it. */
    private var allOrdered: List<String> = emptyList()
    private var winStart = 0
    private var winEnd = 0
    private var currentPos = -1
    private var expanding = false
    private var listAdapter: ArrayAdapter<String>? = null

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

    private fun labelOf(name: String) = name.removeSuffix(".txt")

    private fun windowLabels(): ArrayList<String> {
        val out = ArrayList<String>(winEnd - winStart)
        for (i in winStart until winEnd) out.add(labelOf(allOrdered[i]))
        return out
    }

    /* Center the first window on the current chapter (± WINDOW_RADIUS).
       No current chapter → start of the list. */
    private fun resetWindow(center: Int) {
        val n = allOrdered.size
        if (n == 0) {
            winStart = 0
            winEnd = 0
            return
        }
        if (center < 0) {
            winStart = 0
            winEnd = minOf(n, WINDOW_RADIUS * 2)
        } else {
            winStart = maxOf(0, center - WINDOW_RADIUS)
            winEnd = minOf(n, center + WINDOW_RADIUS + 1)
        }
    }

    private fun bindWindow(
        listView: ListView,
        preserveScroll: Boolean,
        scrollToCurrent: Boolean,
        keepPos: Int = 0,
        keepTop: Int = 0,
    ) {
        val labels = windowLabels()
        val relativeCurrent =
            if (currentPos in winStart until winEnd) currentPos - winStart else -1
        val adapter = object : ArrayAdapter<String>(
            this, R.layout.item_chapter, R.id.chapterLabel, labels,
        ) {
            override fun getView(
                position: Int,
                convertView: android.view.View?,
                parent: android.view.ViewGroup,
            ): android.view.View {
                val v = super.getView(position, convertView, parent)
                val current = currentPos >= 0 && winStart + position == currentPos
                v.setBackgroundResource(
                    if (current) R.drawable.bg_chapter_current else 0,
                )
                v.findViewById<android.view.View>(R.id.chapterAccent).visibility =
                    if (current) android.view.View.VISIBLE else android.view.View.INVISIBLE
                val label = v.findViewById<TextView>(R.id.chapterLabel)
                label.setTextColor(getColor(if (current) R.color.accent else R.color.fg))
                label.setTypeface(
                    null,
                    if (current) android.graphics.Typeface.BOLD
                    else android.graphics.Typeface.NORMAL,
                )
                v.findViewById<android.view.View>(R.id.chapterNow).visibility =
                    if (current) android.view.View.VISIBLE else android.view.View.GONE
                return v
            }
        }
        listAdapter = adapter
        listView.adapter = adapter

        if (preserveScroll) {
            listView.setSelectionFromTop(keepPos, keepTop)
        } else if (scrollToCurrent && relativeCurrent >= 0) {
            listView.post {
                listView.setSelectionFromTop(relativeCurrent, (listView.height * 0.2f).toInt())
            }
        }

        listView.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(
                view: android.widget.AbsListView?,
                scrollState: Int,
            ) {}

            override fun onScroll(
                view: android.widget.AbsListView?,
                firstVisible: Int,
                visibleCount: Int,
                totalCount: Int,
            ) {
                if (expanding || allOrdered.isEmpty() || totalCount == 0) return
                if (firstVisible <= EXPAND_NEAR && winStart > 0) {
                    expandUp(listView)
                } else if (
                    firstVisible + visibleCount >= totalCount - EXPAND_NEAR &&
                    winEnd < allOrdered.size
                ) {
                    expandDown(listView)
                }
            }
        })
    }

    private fun expandUp(listView: ListView) {
        if (expanding || winStart <= 0) return
        val adapter = listAdapter ?: return
        expanding = true
        try {
            val keepPos = listView.firstVisiblePosition
            val keepTop = listView.getChildAt(0)?.top ?: 0
            val newStart = maxOf(0, winStart - EXPAND_BY)
            val added = winStart - newStart
            if (added <= 0) return
            /* prepend without rebuilding: insert highest-index-first so
               positions stay correct as the list grows at 0 */
            adapter.setNotifyOnChange(false)
            for (i in (winStart - 1) downTo newStart) {
                adapter.insert(labelOf(allOrdered[i]), 0)
            }
            adapter.notifyDataSetChanged()
            winStart = newStart
            listView.setSelectionFromTop(keepPos + added, keepTop)
        } finally {
            expanding = false
        }
    }

    private fun expandDown(listView: ListView) {
        if (expanding || winEnd >= allOrdered.size) return
        expanding = true
        try {
            val newEnd = minOf(allOrdered.size, winEnd + EXPAND_BY)
            if (newEnd <= winEnd) return
            val adapter = listAdapter ?: return
            val toAdd = ArrayList<String>(newEnd - winEnd)
            for (i in winEnd until newEnd) toAdd.add(labelOf(allOrdered[i]))
            winEnd = newEnd
            adapter.setNotifyOnChange(false)
            adapter.addAll(toAdd)
            adapter.notifyDataSetChanged()
        } finally {
            expanding = false
        }
    }

    private fun load(preserveScroll: Boolean = false) {
        val dirName = intent.getStringExtra("dir") ?: return finish()
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
        val sortBtn = findViewById<TextView>(R.id.sortBtn)
        val newestFirst = prefs.getBoolean(descKey, false)
        sortBtn.setTextColor(getColor(if (newestFirst) R.color.accent else R.color.fg))
        sortBtn.contentDescription =
            if (newestFirst) "Show oldest first" else "Show newest first"
        sortBtn.setOnClickListener {
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
                status.text = "Could not read"
                showContinue(false)
                return@launch
            }
            val ordered = chapters.ordered.let {
                if (prefs.getBoolean(descKey, false)) it.reversed() else it
            }
            if (ordered.isEmpty()) {
                status.text = "None found"
                showContinue(false)
                allOrdered = emptyList()
                return@launch
            }
            status.text = if (ordered.size == 1) "1 chapter" else "${ordered.size} chapters"
            val lastRenderedCount = renderedCount
            renderedCount = ordered.size
            /* the chapter currently being read: highlighted and scrolled into
               view (at ~20% of the list height) */
            val lastName = slug?.let {
                ReaderActivity.resumeChapter(this@ChapterListActivity, it)
            }
            val newCurrent = lastName?.let { ordered.indexOf(it) } ?: -1
            val continueBtn = findViewById<android.widget.Button>(R.id.continueBtn)
            val startName = if (newCurrent >= 0) lastName else chapters.ordered.firstOrNull()
            if (!slug.isNullOrEmpty() && startName != null) {
                val label = labelOf(startName)
                continueBtn.text =
                    if (newCurrent >= 0) "Continue · $label" else "Start reading · $label"
                continueBtn.setOnClickListener { openChapter(startName) }
                showContinue(true)
            } else {
                showContinue(false)
            }
            /* swapping the adapter drops the scroll position; note where the
               list is sitting so a live refresh can put it back */
            val keepPos = listView.firstVisiblePosition
            val keepTop = listView.getChildAt(0)?.top ?: 0
            /* Chapters arriving underneath shouldn't move the reader's
               place in the list. Ascending, they land at the end and every
               index keeps its meaning — but newest-first PREPENDS them, so
               restoring the same index walked the list backwards by the
               number that arrived, visibly jumping every few seconds for
               the length of a download. Shift by how many appeared. */
            val grew = ordered.size - lastRenderedCount
            val shift = if (grew > 0 && prefs.getBoolean(descKey, false)) grew else 0

            allOrdered = ordered
            currentPos = newCurrent
            /* keepPos is relative to the visible window. When newest-first
               prepends, slide the window by the same shift so the relative
               position still points at the same rows. */
            val keptWindow = if (preserveScroll && listAdapter != null && winEnd > winStart) {
                winStart = (winStart + shift).coerceIn(0, ordered.size)
                winEnd = (winEnd + shift).coerceIn(winStart, ordered.size)
                if (winEnd == winStart && ordered.isNotEmpty()) {
                    resetWindow(newCurrent)
                    false
                } else true
            } else {
                resetWindow(newCurrent)
                false
            }

            listView.setOnItemClickListener { _, _, pos, _ ->
                val abs = winStart + pos
                if (abs !in allOrdered.indices) return@setOnItemClickListener
                /* REORDER_TO_FRONT: if the reader for this novel is still
                   alive behind us (e.g. reading aloud), bring THAT instance
                   forward (it gets onNewIntent and jumps to the chapter)
                   instead of building a new reader over it */
                openChapter(allOrdered[abs])
            }
            bindWindow(
                listView,
                preserveScroll = preserveScroll && keptWindow,
                scrollToCurrent = !preserveScroll || !keptWindow,
                keepPos = keepPos,
                keepTop = keepTop,
            )
          } finally {
            loading = false
          }
        }
    }
}
