package dev.vtlinh.noveldownloader

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/* List Novels: every novel in the saved download folder, resumable with one
   tap — no URLs to remember. Rows come from three sources, deduped by slug:
   the novels registry, the chapter index, and a scan of the root folder's
   subdirectories (novels downloaded before the registry existed, or copied
   in from elsewhere). "Check status" asks each site for its chapter count,
   finished flag, and author; a finished novel with everything on disk shows
   a Complete tag instead of a Download button and sinks to the bottom. */
class NovelListActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val store by lazy { DownloadStore(this) }
    private var folderKey: String? = null

    /* one row of the list */
    private data class Row(
        val rec: NovelRec,
        val display: String,   // English title if known
        val local: Int,        // chapters on this device
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_novels)
        folderKey = prefs.getString("tree", null)
        if (folderKey == null) {
            findViewById<TextView>(R.id.statusText).text = "Pick a download folder first."
        }
        findViewById<Button>(R.id.checkBtn).setOnClickListener { checkStatuses() }

        /* navigation drawer: Home returns to the main screen */
        val drawer = findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawerLayout)
        findViewById<TextView>(R.id.menuBtn).setOnClickListener {
            drawer.openDrawer(androidx.core.view.GravityCompat.START)
        }
        findViewById<TextView>(R.id.navHome).setOnClickListener { finish() }
        findViewById<TextView>(R.id.navBrowser).setOnClickListener {
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
            startActivity(Intent(this, BrowserActivity::class.java))
        }
        findViewById<TextView>(R.id.navNovels).setOnClickListener {
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
        }
        findViewById<TextView>(R.id.navSettings).setOnClickListener {
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    /* re-render on every return so the RECENTLY READ section reflects the
       chapter we just came back from (onResume also follows onCreate) */
    override fun onResume() {
        super.onResume()
        render()
    }

    /* best-effort slug from a folder name: novel folders are the sanitized
       title ("English (Vietnamese)" once translated — the Vietnamese part is
       what matches the site slug) */
    private fun slugify(folderName: String): String {
        val vn = Regex("\\(([^)]+)\\)\\s*$").find(folderName)?.groupValues?.get(1) ?: folderName
        return Extractor.sanitize(vn).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }

    private val chapterFileRe = Regex("Chapter \\d+.*\\.txt(\\.gz)?")

    /* Slug equality must survive punctuation drift: "Heaven's Path" is slug
       "library-of-heavens-path" on the site but the sanitized folder name
       slugifies to "library-of-heaven-s-path". Letters+digits only. */
    private fun normKey(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /* set by rows(): how many entries each source contributed + any error,
       so an unexpectedly empty list can explain itself */
    @Volatile private var sourceInfo = ""

    /* The list itself is pure DB reads — the root folder is listed exactly
       ONCE per picked folder (scanFolder below), its findings folded into
       the registry, and never listed again. */
    private fun rows(): List<Row> {
        val folder = folderKey ?: return emptyList()
        var err = ""
        if (!store.isScanned(folder)) err += scanFolder(folder)

        val all = LinkedHashMap<String, NovelRec>()
        try {
            for (rec in store.novels(folder)) all[rec.slug] = rec
        } catch (e: Exception) { err += " registry:${e.message}" }
        val registryN = all.size
        /* chapter index knows novels the registry may not */
        try {
            for (slug in store.chapterSlugs(folder)) {
                if (slug !in all) all[slug] = NovelRec(slug, "", slug, "", 0L, -1, false, 0, 0L, 0L)
            }
        } catch (e: Exception) { err += " index:${e.message}" }

        /* merge duplicates whose slugs differ only in punctuation (folder-scan
           slug vs site slug); the richer record wins, a scan-created loser is
           deleted from the registry so the pair never reappears */
        fun score(r: NovelRec) = (if (r.url.isNotEmpty()) 4 else 0) +
            (if (r.complete) 2 else 0) + (if (r.started > 0) 1 else 0)
        val byNorm = LinkedHashMap<String, NovelRec>()
        for (rec in all.values) {
            val k = normKey(rec.slug)
            val prev = byNorm[k]
            if (prev == null) { byNorm[k] = rec; continue }
            val win = if (score(rec) >= score(prev)) rec else prev
            val lose = if (win === rec) prev else rec
            byNorm[k] = win
            if (lose.diskCount > win.diskCount) {
                try { store.setDiskCount(folder, win.slug, lose.diskCount) } catch (e: Exception) {}
            }
            if (lose.url.isEmpty()) {
                try { store.removeNovel(folder, lose.slug) } catch (e: Exception) {}
            }
        }
        sourceInfo = "registry $registryN · index ${all.size - registryN}" +
            (if (err.isNotEmpty()) " · errors:$err" else "")

        return byNorm.values.map { rec ->
            val dbCount = try { store.chapterCount(folder, rec.slug) } catch (e: Exception) { 0 }
            Row(
                rec,
                Extractor.stripAuthor(
                    store.getTitle(folder, rec.slug) ?: rec.title.ifEmpty { rec.slug },
                    rec.author,
                ),
                maxOf(dbCount, rec.diskCount),
            )
        }.sortedWith(
            /* incomplete first; within each group the most recently
               DOWNLOADED novel on top (first-download time as legacy fallback) */
            compareBy<Row> { it.rec.complete }
                .thenByDescending { maxOf(it.rec.lastDl, it.rec.started) },
        )
    }

    /* One-time root scan, single ContentResolver query per directory (all
       child names in one cursor — DocumentFile's per-file metadata queries
       took minutes on big folders). Registers every unaccounted novel folder
       and records on-disk chapter counts, then marks the folder scanned so
       this never runs again. Returns an error tag or "". */
    /* Fast SAF listing: one ContentResolver query per directory returning
       (docId, name, isDir) for every child. */
    private fun children(treeUri: Uri, docId: String): List<Triple<String, String, Boolean>> {
        val out = ArrayList<Triple<String, String, Boolean>>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    Triple(
                        c.getString(0), c.getString(1) ?: "",
                        c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    ),
                )
            }
        }
        return out
    }

    private fun scanFolder(folder: String): String {
        val treeUri = Uri.parse(folder)
        fun children(docId: String) = children(treeUri, docId)

        val dirs = try {
            children(DocumentsContract.getTreeDocumentId(treeUri))
                .filter { it.third }.associate { it.second to it.first }   // name -> docId
        } catch (e: Exception) {
            /* don't mark scanned — retry next open once access is back */
            return " folder-access-lost(${e.message})"
        }
        fun countIn(docId: String): Int = try {
            children(docId).count { !it.third && chapterFileRe.matches(it.second) }
        } catch (e: Exception) { 0 }

        try {
            val recs = store.novels(folder)
            val known = recs.associateBy { store.getTitle(folder, it.slug) ?: Extractor.sanitize(it.title) }
            /* match by punctuation-insensitive key, so "Heaven s Path" folders
               find their "heavens-path" site slug (chapter index included) */
            val knownByNorm = HashMap<String, NovelRec>()
            for (r in recs) knownByNorm[normKey(r.slug)] = r
            for (slug in store.chapterSlugs(folder)) {
                knownByNorm.putIfAbsent(normKey(slug), NovelRec(slug, "", slug, "", 0L, -1, false, 0, 0L, 0L))
            }
            for ((name, docId) in dirs) {
                if (name.isEmpty()) continue
                val slug = slugify(name)
                if (slug.isEmpty()) continue
                val rec = known[name] ?: knownByNorm[normKey(slug)]
                if (rec != null) {
                    /* known novel: refresh its on-disk count once if unindexed */
                    if (store.chapterCount(folder, rec.slug) == 0 && rec.diskCount == 0) {
                        store.setDiskCount(folder, rec.slug, countIn(docId))
                    }
                    continue
                }
                val count = countIn(docId)
                if (count == 0) continue   // not a novel folder
                store.registerNovel(folder, slug, "", name, 0L)
                store.setDiskCount(folder, slug, count)
            }
            store.markScanned(folder, System.currentTimeMillis())
        } catch (e: Exception) {
            return " scan:${e.message}"
        }
        return ""
    }

    private fun render() {
        val list = findViewById<LinearLayout>(R.id.novelList)
        val status = findViewById<TextView>(R.id.statusText)
        status.text = "Loading…"
        lifecycleScope.launch {
            val rs = try {
                withContext(Dispatchers.IO) { rows() }
            } catch (e: Exception) {
                status.text = "List error: ${e.message}"
                return@launch
            }
            list.removeAllViews()
            if (rs.isEmpty()) {
                /* explain WHICH source came up empty instead of a blank shrug */
                status.text = "No novels found. ($sourceInfo)"
                return@launch
            }
            status.text = "${rs.size} novel(s)"
            /* the 3 most recently READ novels get their own section on top */
            val recent = rs.filter { it.rec.lastRead > 0 }
                .sortedByDescending { it.rec.lastRead }.take(3)
            val recentSlugs = recent.map { it.rec.slug }.toSet()
            val others = rs.filter { it.rec.slug !in recentSlugs }
            if (recent.isNotEmpty()) {
                list.addView(sectionHeader("RECENTLY READ"))
                for (row in recent) {
                    try { list.addView(buildRow(row)) } catch (e: Exception) {}
                }
                list.addView(sectionHeader("ALL NOVELS"))
            }
            for (row in others) {
                try { list.addView(buildRow(row)) } catch (e: Exception) {}
            }
        }
    }

    private fun sectionHeader(label: String): TextView =
        TextView(this).apply {
            text = label
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.muted))
            letterSpacing = 0.08f
            setPadding(0, dp(14), 0, dp(2))
        }

    private fun buildRow(row: Row): View {
        val ctx = this
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            /* tapping the row starts (or continues) reading this novel */
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val folder = folderKey ?: return@setOnClickListener
                val dirName = store.getTitle(folder, row.rec.slug)
                    ?: Extractor.sanitize(row.rec.title.ifEmpty { row.rec.slug })
                startActivity(
                    Intent(ctx, ChapterListActivity::class.java)
                        .putExtra("dir", dirName).putExtra("title", row.display)
                        .putExtra("slug", row.rec.slug),
                )
            }
        }
        /* checked, still-ongoing novel with every site chapter on disk:
           nothing to download until the site adds more */
        val upToDate = !row.rec.complete && row.rec.total > 0 && row.local >= row.rec.total
        val text = buildString {
            append(row.display)
            if (row.rec.author.isNotEmpty()) append("\n${row.rec.author}")
            if (!row.rec.complete) {
                append("\n${row.local} chapter(s)")
                if (row.rec.total > 0) append(" of ${row.rec.total}")
                if (upToDate) append(" — up to date")
                if (row.rec.url.isEmpty()) append(" — tap Check status to locate")
            }
        }
        val cover = DownloadEngine.coverFile(ctx, row.rec.slug)
        if (cover.exists()) {
            line.addView(
                android.widget.ImageView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(60)).apply {
                        marginEnd = dp(12)
                    }
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(
                        android.graphics.BitmapFactory.decodeFile(
                            cover.path,
                            android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 },
                        ),
                    )
                },
            )
        }
        line.addView(
            TextView(ctx).apply {
                this.text = text
                textSize = 14f
                setTextColor(getColor(R.color.fg))
                setLineSpacing(0f, 1.15f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        if (row.rec.complete) {
            line.addView(
                TextView(ctx).apply {
                    this.text = "COMPLETE"
                    textSize = 11f
                    setTextColor(Color.parseColor("#3DDC84"))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(Color.parseColor("#1E3527"))
                    }
                },
            )
        } else if (row.rec.url.isNotEmpty() && !upToDate) {
            line.addView(
                MaterialButton(ctx).apply {
                    this.text = "Download"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
                    cornerRadius = dp(8)
                    setOnClickListener { startResume(row.rec.url) }
                },
            )
        }
        return line
    }

    private fun startResume(url: String) {
        val tree = folderKey ?: return
        if (DownloadService.runningFlow.value) {
            findViewById<TextView>(R.id.statusText).text = "A download is already running."
            return
        }
        prefs.edit().putString("url", url).apply()
        startForegroundService(
            Intent(this, DownloadService::class.java)
                .putExtra("url", url).putExtra("tree", tree)
                .putExtra("translate", prefs.getBoolean("translate", false))
                .putExtra("apiKey", prefs.getString("apiKey", "") ?: ""),
        )
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    /* Ask each site for its chapter count, finished flag, and author. A row
       with no URL (legacy or folder-scanned) is probed at each site's
       canonical slug URL first. A novel is complete when the site says
       finished AND every chapter is on disk. */
    private fun checkStatuses() {
        val folder = folderKey ?: return
        val btn = findViewById<Button>(R.id.checkBtn)
        val status = findViewById<TextView>(R.id.statusText)
        btn.isEnabled = false
        val engine = DownloadEngine(this, {}, {}, { _, _ -> })
        lifecycleScope.launch {
            /* A complete novel (site finished + everything on disk) can never
               regress — no need to recheck it — EXCEPT when its site chapter
               order was never indexed: those are checked once more so the
               reader gets its ordering. */
            val targets = withContext(Dispatchers.IO) {
                rows().filter {
                    !it.rec.complete || store.chapterOrderCount(folder, it.rec.slug) == 0
                }
            }
            if (targets.isEmpty()) {
                status.text = "Nothing to check — all novels are complete and indexed."
                btn.isEnabled = true
                return@launch
            }
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val sem = Semaphore(3)
                    for (row in targets) {
                        launch {
                            sem.withPermit {
                                val urls = row.rec.url.ifEmpty { null }?.let { listOf(it) }
                                    ?: listOf(
                                        "https://truyenfull.today/${row.rec.slug}/",
                                        "https://novelfull.com/${row.rec.slug}.html",
                                        "https://truyenfull.live/${row.rec.slug}/",
                                    )
                                for (u in urls) {
                                    val res = try { engine.checkStatus(u) } catch (e: Exception) { null } ?: continue
                                    if (row.rec.url.isEmpty()) {
                                        store.registerNovel(folder, row.rec.slug, u, row.display, 0L)
                                    }
                                    res.author?.let { store.setAuthor(folder, row.rec.slug, it) }
                                    /* index the site's chapter order for the reader —
                                       skipped when already indexed and unchanged */
                                    if (store.chapterOrderCount(folder, row.rec.slug) != res.orderedFilenames.size) {
                                        store.setChapterOrder(folder, row.rec.slug, res.orderedFilenames)
                                    }
                                    val complete = res.completed && row.local >= res.total
                                    store.updateNovelCheck(folder, row.rec.slug, res.total, complete)
                                    break
                                }
                                val n = done.incrementAndGet()
                                withContext(Dispatchers.Main) {
                                    status.text = "Checking… $n/${targets.size}"
                                }
                            }
                        }
                    }
                }
            }
            status.text = "Status checked (${targets.size} novel(s))."
            btn.isEnabled = true
            render()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
