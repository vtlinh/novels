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
import kotlinx.coroutines.flow.collectLatest
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

    companion object {
        /* slugs (normalized: letters+digits only) the user marked as garbage.
           MainActivity checks this before re-downloading such a novel. */
        const val GARBAGE_KEY = "garbageSlugs"

        /* normalized slug key from a novel URL: last path segment, ".html"
           stripped, letters+digits only — matches normKey(rec.slug) */
        fun slugKeyFromUrl(url: String): String =
            url.trimEnd('/').substringAfterLast('/').removeSuffix(".html")
                .lowercase().filter { it.isLetterOrDigit() }
    }

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val store by lazy { DownloadStore(this) }
    private var folderKey: String? = null

    /* ---- per-novel user marks (hot / finished / garbage) ---- */
    private fun isHot(slug: String) = prefs.getBoolean("novelHot:$slug", false)
    private fun isRead(slug: String) = prefs.getBoolean("novelRead:$slug", false)
    private fun setHot(slug: String, v: Boolean) =
        prefs.edit().putBoolean("novelHot:$slug", v).apply()
    private fun setRead(slug: String, v: Boolean) =
        prefs.edit().putBoolean("novelRead:$slug", v).apply()
    private fun garbageSet(): Set<String> =
        prefs.getStringSet(GARBAGE_KEY, emptySet()) ?: emptySet()

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
        findViewById<TextView>(R.id.navAbout).setOnClickListener {
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
            startActivity(Intent(this, AboutActivity::class.java))
        }

        /* inline download feedback: live status while a download runs, and a
           re-render when it finishes so the chapter counts refresh */
        lifecycleScope.launch {
            DownloadService.statusFlow.collectLatest { s ->
                if (DownloadService.runningFlow.value && s.isNotEmpty()) {
                    findViewById<TextView>(R.id.statusText).text = s
                }
            }
        }
        var seenRunning = false
        lifecycleScope.launch {
            DownloadService.runningFlow.collectLatest { r ->
                if (r) seenRunning = true
                else if (seenRunning) { seenRunning = false; render() }
            }
        }
        /* the buttons show which novel is downloading or queued, so re-render
           when that moves. Both flows replay their current value on collect,
           which onResume's render already covered — skip that first one. */
        var firstActive = true
        lifecycleScope.launch {
            DownloadService.activeSlugFlow.collectLatest {
                if (firstActive) firstActive = false else render()
            }
        }
        var firstQueued = true
        lifecycleScope.launch {
            DownloadService.queuedSlugsFlow.collectLatest {
                if (firstQueued) firstQueued = false else render()
            }
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

        val garbage = garbageSet()
        return byNorm.values.filter { normKey(it.slug) !in garbage }.map { rec ->
            val dbCount = try { store.chapterCount(folder, rec.slug) } catch (e: Exception) { 0 }
            /* the cached resolved listing also counts compressed chapters,
               which the index and the scan both miss */
            val listCount = try { store.chapterListCount(folder, rec.slug) } catch (e: Exception) { 0 }
            Row(
                rec,
                Extractor.stripAuthor(
                    store.getTitle(folder, rec.slug) ?: rec.title.ifEmpty { rec.slug },
                    rec.author,
                ),
                maxOf(dbCount, rec.diskCount, listCount),
            )
        }.sortedWith(
            /* finished (user-marked) novels sink to the bottom; hot novels
               float to the top of their half (so hot+finished sits above
               plain finished). Then incomplete first, most recently
               downloaded on top (first-download time as legacy fallback). */
            compareBy<Row> { isRead(it.rec.slug) }
                .thenBy { !isHot(it.rec.slug) }
                .thenBy { it.rec.complete }
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
                /* already listening to this novel? go straight back to where
                   TTS stopped — the reader restores that chapter's scroll
                   position too — instead of via the chapter list */
                val ttsChapter = prefs.getString("ttsPos:${row.rec.slug}", null)
                    ?.substringBefore('|')?.takeIf { it.isNotEmpty() }
                if (ttsChapter != null) {
                    startActivity(
                        Intent(ctx, ReaderActivity::class.java)
                            .putExtra("dir", dirName).putExtra("title", row.display)
                            .putExtra("slug", row.rec.slug)
                            .putExtra("start", ttsChapter)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    )
                    return@setOnClickListener
                }
                startActivity(
                    Intent(ctx, ChapterListActivity::class.java)
                        .putExtra("dir", dirName).putExtra("title", row.display)
                        .putExtra("slug", row.rec.slug),
                )
            }
            /* long-press: hot / finished / garbage marks */
            setOnLongClickListener { showMarkSheet(row); true }
        }
        /* "complete" is the SITE saying the story is finished — it says
           nothing about how much of it is here. Only call a novel done when
           both are true, or a finished novel missing chapters shows a
           Complete tag, hides its counts, and offers no way to fetch the
           rest. */
        val haveAll = row.rec.total > 0 && row.local >= row.rec.total
        val done = row.rec.complete && haveAll
        /* checked, still-ongoing novel with every site chapter on disk:
           nothing to download until the site adds more */
        val upToDate = !row.rec.complete && haveAll
        val text = buildString {
            if (isHot(row.rec.slug)) append("★ ")   // hot marker (monochrome)
            append(row.display)
            if (row.rec.author.isNotEmpty()) append("\n${row.rec.author}")
            if (!done) {
                /* "on-disk / site-total" — total is filled in by downloads
                   and Check status */
                if (row.rec.total > 0) append("\n${row.local}/${row.rec.total} chapters")
                else append("\n${row.local} chapter(s)")
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
        if (isRead(row.rec.slug)) {
            line.addView(
                TextView(ctx).apply {
                    this.text = "FINISHED"
                    textSize = 11f
                    setTextColor(getColor(R.color.muted))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(getColor(R.color.card))
                    }
                },
            )
        } else if (done) {
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
        } else if (row.rec.url.isNotEmpty() && !upToDate) {   // includes finished-but-incomplete
            /* already downloading (or waiting its turn) → say so and go dead,
               so a second job can't be started for the same novel */
            val key = normKey(row.rec.slug)
            val busy = DownloadService.isBusy(key)
            line.addView(
                MaterialButton(ctx).apply {
                    this.text = when {
                        DownloadService.isActive(key) -> "Downloading…"
                        DownloadService.isQueued(key) -> "Queued"
                        else -> "Download"
                    }
                    textSize = 13f
                    setTextColor(if (busy) getColor(R.color.muted) else Color.WHITE)
                    /* an explicit tint applies to every state, so a disabled
                       button would still look live — pick the colour here */
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        getColor(if (busy) R.color.btn_secondary else R.color.accent),
                    )
                    cornerRadius = dp(8)
                    isEnabled = !busy
                    if (!busy) setOnClickListener { startResume(row.rec.url) }
                },
            )
        }
        return line
    }

    /* long-press menu: hot / finished / garbage */
    private fun showMarkSheet(row: Row) {
        val slug = row.rec.slug
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
            setBackgroundColor(getColor(R.color.card))
        }
        root.addView(
            TextView(this).apply {
                text = row.display; textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.fg)); setPadding(0, 0, 0, dp(8))
            },
        )
        fun item(label: String, onTap: () -> Unit) = root.addView(
            TextView(this).apply {
                text = label; textSize = 15f; setTextColor(getColor(R.color.fg))
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true; isFocusable = true
                setOnClickListener { sheet.dismiss(); onTap() }
            },
        )
        item(if (isHot(slug)) "Unmark hot (back to normal)" else "★ Mark as hot") {
            setHot(slug, !isHot(slug)); render()
        }
        item(if (isRead(slug)) "Mark as unread" else "Mark as finished") {
            setRead(slug, !isRead(slug)); render()
        }
        item("Mark as garbage…") { confirmGarbage(row) }
        sheet.setContentView(root)
        sheet.show()
    }

    /* garbage: confirm, then remember the slug, delete the novel's folder and
       every trace of it — re-downloading later warns first (MainActivity) */
    private fun confirmGarbage(row: Row) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Mark as garbage")
            .setMessage(
                "\"${row.display}\" will be removed from this list and its downloaded " +
                    "chapters deleted from your device. Downloading it again will warn you first.",
            )
            .setPositiveButton("Mark as garbage") { _, _ -> doGarbage(row) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doGarbage(row: Row) {
        val folder = folderKey ?: return
        val slug = row.rec.slug
        val status = findViewById<TextView>(R.id.statusText)
        status.text = "Removing…"
        /* remember first, so the novel stays gone even if deletion hiccups */
        prefs.edit()
            .putStringSet(GARBAGE_KEY, garbageSet() + normKey(slug))
            .remove("novelHot:$slug").remove("novelRead:$slug")
            .remove("lastCh:$slug").remove("readPos:$slug").remove("readParaText:$slug")
            .remove("ttsPos:$slug").remove("ttsParaText:$slug")
            .apply()
        lifecycleScope.launch(Dispatchers.IO) {
            val treeUri = Uri.parse(folder)
            try {
                /* the novel's folder: match by registered title, else by slug */
                val dirName = store.getTitle(folder, slug)
                    ?: Extractor.sanitize(row.rec.title.ifEmpty { slug })
                val dir = children(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                    .firstOrNull { it.third && (it.second == dirName || slugify(it.second) == slug) }
                if (dir != null) {
                    DocumentsContract.deleteDocument(
                        contentResolver,
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, dir.first),
                    )
                }
            } catch (e: Exception) {}
            try { store.removeNovel(folder, slug) } catch (e: Exception) {}
            try { store.clear(folder, slug) } catch (e: Exception) {}
            try { store.setChapterOrder(folder, slug, emptyList()) } catch (e: Exception) {}
            try { DownloadEngine.coverFile(this@NovelListActivity, slug).delete() } catch (e: Exception) {}
            withContext(Dispatchers.Main) { render() }
        }
    }

    /* Download INLINE: stay on this page. The service queues requests made
       while another novel is downloading, so tapping several Download buttons
       just lines them up. */
    private fun startResume(url: String) {
        val tree = folderKey ?: return
        val wasRunning = DownloadService.runningFlow.value
        prefs.edit().putString("url", url).apply()
        startForegroundService(
            Intent(this, DownloadService::class.java)
                .putExtra("url", url).putExtra("tree", tree)
                .putExtra("translate", prefs.getBoolean("translate", false))
                .putExtra("apiKey", prefs.getString("apiKey", "") ?: ""),
        )
        findViewById<TextView>(R.id.statusText).text =
            if (wasRunning) "Queued for download." else "Download started…"
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
        /* Check status discarded everything the engine had to say. Its notes
           — which listed links carry no chapter number, and so can never be
           downloaded — go to the same log the home screen prints, otherwise
           the only way to see them is to run a full download. */
        val engine = DownloadEngine(
            this,
            { line -> DownloadService.logFlow.value = (DownloadService.logFlow.value + line).takeLast(400) },
            {},
            { _, _ -> },
        )
        lifecycleScope.launch {
            /* Skip only a novel that is genuinely done — the site has
               finished it AND every chapter is here. The stored flag alone
               isn't that: it can be set on a novel still missing chapters,
               which is precisely the novel a check should look at. Same test
               the rows use to decide between a Complete tag and a Download
               button, so what the list offers and what a check visits agree.
               A novel whose chapter order was never indexed is checked once
               more regardless, so the reader gets its ordering. */
            val targets = withContext(Dispatchers.IO) {
                rows().filter {
                    val done = it.rec.complete && it.rec.total > 0 && it.local >= it.rec.total
                    !done || store.chapterOrderCount(folder, it.rec.slug) == 0
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
                                    val res = try { engine.checkStatus(u, folder) } catch (e: Exception) { null } ?: continue
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
