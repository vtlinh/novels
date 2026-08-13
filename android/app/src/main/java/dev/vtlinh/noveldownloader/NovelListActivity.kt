package dev.vtlinh.noveldownloader

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.Button
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/* Library (launcher): every novel in the saved download folder, resumable with
   one tap — no URLs to remember. Rows come from three sources, deduped by slug:
   the novels registry, the chapter index, and a scan of the root folder's
   subdirectories (novels downloaded before the registry existed, or copied
   in from elsewhere). "Check status" asks each site for its chapter count,
   finished flag, and author; a finished novel with everything on disk shows
   a Complete tag instead of a Download button and sinks to the bottom.

   Cold start: resume a live reading session when one exists; otherwise open
   the Browser when this list is empty. Console output (formerly the Home
   screen log) lives in the swipe-away footer. */
class NovelListActivity : AppCompatActivity() {

    companion object {
        /* slugs (normalized: letters+digits only) the user marked as garbage.
           Share / browser download paths check this before re-downloading. */
        const val GARBAGE_KEY = "garbageSlugs"

        const val EXTRA_SHARE_URL = "shareUrl"

        /* the rule lives in Sites.slugKey — which says why it must go through
           the site's own normalize() — so it can be tested without loading an
           Activity; kept here because every call site already reads it here */
        fun slugKeyFromUrl(url: String): String = Sites.slugKey(url)
    }

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val store by lazy { DownloadStore(this) }
    private var folderKey: String? = null

    /* Novel URL waiting on a download folder being picked (share path). */
    private var pendingShareUrl: String?
        get() = prefs.getString("pendingShareUrl", null)
        set(v) {
            prefs.edit().apply {
                if (v == null) remove("pendingShareUrl") else putString("pendingShareUrl", v)
            }.apply()
        }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            val pending = pendingShareUrl
            pendingShareUrl = null
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                prefs.edit().putString("tree", uri.toString()).apply()
                folderKey = uri.toString()
                pending?.let { startShareDownload(it) }
            }
        }

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

        /* Cold start from the launcher: resume reading if a session is live,
           otherwise open the Browser when the library has nothing to show. */
        if (savedInstanceState == null && intent?.action == Intent.ACTION_MAIN) {
            if (!resumeReadingIfNeeded()) maybeOpenBrowserIfEmpty()
        }

        /* navigation drawer */
        val drawer = findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawerLayout)
        findViewById<TextView>(R.id.menuBtn).setOnClickListener {
            drawer.openDrawer(androidx.core.view.GravityCompat.START)
        }
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

        ConsoleFooter.attach(this, findViewById(R.id.consoleFooter))

        /* share target (and trampoline from the old Home activity name) */
        if (savedInstanceState == null) handleIncomingShare(intent)

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

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    /* app opened from the launcher while a reading session was live →
       resume straight into the reader at the saved spot (the marker is
       cleared when the user backs out of reading mode) */
    private fun resumeReadingIfNeeded(): Boolean {
        val saved = prefs.getString("lastReading", null) ?: return false
        return try {
            val o = org.json.JSONObject(saved)
            val slug = o.getString("slug")
            val startCh = ReaderActivity.resumeChapter(this, slug) ?: return false
            startActivity(
                Intent(this, ReaderActivity::class.java)
                    .putExtra("dir", o.getString("dir"))
                    .putExtra("title", o.getString("title"))
                    .putExtra("slug", slug)
                    .putExtra("start", startCh),
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /* No novels (and no folder yet) → Browser is the useful first screen. */
    private fun maybeOpenBrowserIfEmpty() {
        lifecycleScope.launch {
            val empty = withContext(Dispatchers.IO) {
                if (folderKey == null) true
                else try { rows().isEmpty() } catch (e: Exception) { true }
            }
            if (empty && !isFinishing) {
                startActivity(Intent(this@NovelListActivity, BrowserActivity::class.java))
            }
        }
    }

    private fun handleIncomingShare(intent: Intent?) {
        val fromExtra = intent?.getStringExtra(EXTRA_SHARE_URL)
        val fromSend = if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            Regex("https?://\\S+").find(text)?.value
        } else null
        val typed = fromExtra ?: fromSend ?: return
        startShareDownload(typed)
    }

    /* Share / deep-link download: same gates as the browser (folder, garbage,
       API key). Stays on Library so the console footer shows progress. */
    private fun startShareDownload(typed: String) {
        val site = Sites.forUrl(typed)
        if (site == null) {
            findViewById<TextView>(R.id.statusText).text =
                "Enter a novel URL from " + Sites.all.flatMap { it.hosts }.joinToString(", ")
            return
        }
        val url = try {
            val (base, slug) = site.normalize(typed)
            if (base.isEmpty() || slug.isEmpty()) {
                findViewById<TextView>(R.id.statusText).text =
                    "That link doesn't name a novel — open the novel's own page and share that."
                return
            }
            base
        } catch (e: Exception) { typed }
        prefs.edit().putString("url", url).apply()
        val slugKey = slugKeyFromUrl(url)
        val garbage = garbageSet()
        if (slugKey.isNotEmpty() && slugKey in garbage) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Marked as garbage")
                .setMessage(
                    "You previously marked this novel as garbage. " +
                        "Remove that status and download it again?",
                )
                .setPositiveButton("Re-download") { _, _ ->
                    prefs.edit().putStringSet(GARBAGE_KEY, garbage - slugKey).apply()
                    startShareDownload(url)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val tree = prefs.getString("tree", null)
        if (tree == null) {
            pendingShareUrl = url
            pickFolder.launch(null)
            return
        }
        folderKey = tree
        val translate = prefs.getBoolean("translate", false)
        val apiKey = (prefs.getString("apiKey", "") ?: "").trim()
        if (translate && apiKey.isEmpty()) {
            findViewById<TextView>(R.id.statusText).text =
                "Set your Anthropic API key in Settings to translate."
            return
        }
        startForegroundService(
            Intent(this, DownloadService::class.java)
                .putExtra("url", url).putExtra("tree", tree)
                .putExtra("translate", translate)
                .putExtra("forceTranslate", false)
                .putExtra("apiKey", apiKey),
        )
        findViewById<TextView>(R.id.statusText).text = "Download started…"
    }

    /* re-render on every return so the RECENTLY READ section reflects the
       chapter we just came back from (onResume also follows onCreate) */
    override fun onResume() {
        super.onResume()
        /* Settings is one tap away in this screen's own drawer and can change
           the download folder. Reading it once in onCreate meant coming back
           still listed the OLD folder's novels and, worse, handed the old tree
           to the service — so chapters downloaded into the folder the user had
           just stopped using. */
        val tree = prefs.getString("tree", null)
        if (tree != folderKey) {
            folderKey = tree
            if (tree == null) {
                findViewById<TextView>(R.id.statusText).text = "Pick a download folder first."
                return
            }
        }
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
       slugifies to "library-of-heaven-s-path". Letters+digits only.

       One definition, shared with Ownership — which needs the same test to
       decide whether a claimed folder is this novel's own, and had its own
       exact-string comparison until the two dropping out of step was the bug
       that sent a scan-adopted novel into a second folder. */
    private fun normKey(s: String) = Ownership.normKey(s)

    /* set by rows(): how many entries each source contributed + any error,
       so an unexpectedly empty list can explain itself */
    @Volatile private var sourceInfo = ""

    /* The list itself is pure DB reads — the root folder is listed exactly
       ONCE per picked folder (scanFolder below), its findings folded into
       the registry, and never listed again. */
    private fun rows(): List<Row> {
        val folder = folderKey ?: return emptyList()
        var err = ""
        /* After the first successful scan this screen never touches the
           filesystem again — every row comes from SQLite. So a folder the
           user deleted, an SD card that came out, or a grant revoked in
           system settings all presented as a perfectly healthy library with
           live Download buttons, and the failure only surfaced deep in a
           download log. Confirm we still hold the grant; it's one cheap
           lookup against a list the system already keeps. */
        val granted = try {
            contentResolver.persistedUriPermissions.any {
                it.isReadPermission && it.uri.toString() == folder
            }
        } catch (e: Exception) { true }   // can't tell → don't cry wolf
        if (!granted) err += " folder-access-lost"
        if (granted && !store.isScanned(folder)) err += scanFolder(folder)

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
                /* The settings travel too. They are the same novel, so the
                   loser's "never translate" or auto-download answer is the
                   winner's — set on the folder-scanned row before the site
                   row appeared, and deleting the row without carrying them
                   silently reverted the novel to the app-wide switch, which
                   for translate is a money decision. Only onto a winner that
                   has no explicit answer of its own: the newest explicit
                   choice wins. */
                try {
                    if (lose.translate != null && win.translate == null) {
                        store.setTranslate(folder, win.slug, lose.translate)
                    }
                    if (lose.autoDownload && !win.autoDownload) {
                        store.setAutoDownload(folder, win.slug, true)
                    }
                } catch (e: Exception) {}
                /* ...and the reading/read-aloud positions, which live in
                   prefs under the losing SLUG. They are the same novel, and
                   deleting the row without carrying these lost the user's
                   place: resumeChapter(winSlug) found nothing, the chapter
                   list highlighted nothing, and the spot was gone with no
                   message. Only onto a winner with no spot of its own — a
                   position the user made under the winning slug is newer
                   truth than the orphan. */
                try {
                    val all = prefs.all
                    val e = prefs.edit()
                    for (k in listOf(
                        "lastCh:", "lastChAt:", "readPos:", "readParaText:",
                        "ttsPos:", "ttsPosAt:", "ttsParaText:",
                        /* the hot/finished marks are per-slug too — dropping
                           them re-floated a finished novel up the sort */
                        "novelHot:", "novelRead:",
                    )) {
                        val v = all[k + lose.slug] ?: continue
                        if (all[k + win.slug] != null) continue
                        when (v) {
                            is String -> e.putString(k + win.slug, v)
                            is Long -> e.putLong(k + win.slug, v)
                            is Int -> e.putInt(k + win.slug, v)
                            is Float -> e.putFloat(k + win.slug, v)
                            is Boolean -> e.putBoolean(k + win.slug, v)
                        }
                    }
                    e.apply()
                } catch (e: Exception) {}
                /* Hand the folder over before the row goes. They are the same
                   novel, so the loser's directory IS the winner's — but the
                   claim lives in folder_owner keyed on the losing slug, and
                   removeNovel only deletes from `novels`. Left behind, the
                   claim named a slug with no record anywhere: the folder was
                   reserved for ever against the one novel whose chapters are
                   inside it, so every later run saw the name taken by a
                   stranger and stepped aside into "Title (slug)". */
                try {
                    val loseDir = store.dirNameFor(folder, lose.slug)
                    if (loseDir != null) {
                        /* only when the winner has no directory of its own —
                           if it downloaded into a suffixed folder, that is
                           where its chapters are and it must keep it */
                        if (store.dirNameFor(folder, win.slug) == null) {
                            store.setDirName(folder, win.slug, loseDir)
                        }
                        store.claimFolderName(folder, loseDir, win.slug)
                    }
                    store.releaseFolderName(folder, lose.slug)
                } catch (e: Exception) {}
                try { store.removeNovel(folder, lose.slug) } catch (e: Exception) {}
            }
        }
        sourceInfo = "registry $registryN · index ${all.size - registryN}" +
            (if (err.isNotEmpty()) " · errors:$err" else "")

        val garbage = garbageSet()
        return byNorm.values.filter { normKey(it.slug) !in garbage }.map { rec ->
            Row(
                rec,
                Extractor.stripAuthor(
                    store.getTitle(folder, rec.slug) ?: rec.title.ifEmpty { rec.slug },
                    rec.author,
                ),
                /* One definition, in NovelCheck — the per-novel settings
                   screen has to arrive at the same number, and "how many
                   chapters are here" is what decides whether a check may
                   resume rather than read the whole listing. */
                NovelCheck.localCount(store, folder, rec),
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
                /* We are LOOKING AT the directory, so record it. Storing the
                   name only as the title left every adopted novel on the
                   rebuild-from-title guess — which drops anything non-ASCII,
                   i.e. exactly the copied-in folders this scan exists to
                   adopt. Tapping the row then opened a directory that does
                   not exist, permanently, and the garbage delete fell back to
                   matching by slug, where two folders that slugify alike are
                   told apart by nothing but enumeration order. */
                try { store.setDirName(folder, slug, name) } catch (e: Exception) {}
                /* ...and mark the name spoken for, but only if it is free.
                   An INSERT OR REPLACE here would take the claim from the novel
                   that really owns the folder; recording nothing at all leaves
                   the folder unclaimed, unrecorded and (the scan writes no
                   chapter rows) invisible to every ownership test — so the next
                   novel whose title sanitises to the same name moves straight
                   in and adopts this book's chapters as its own. */
                try { store.claimFolderNameIfFree(folder, name, slug) } catch (e: Exception) {}
            }
            store.markScanned(folder, System.currentTimeMillis())
        } catch (e: Exception) {
            return " scan:${e.message}"
        }
        return ""
    }

    /* `finalStatus` is a message the caller wants LEFT on screen once the
       list is up — the status sweep's outcome line. Setting it before
       calling render() looked right and was unobservable: render overwrites
       the status twice in the same main-thread turn, so no frame ever
       carried the message, and the one it erased was the warning that the
       auto-downloads a check queued were refused. */
    private fun render(finalStatus: String? = null) {
        val list = findViewById<LinearLayout>(R.id.novelList)
        val status = findViewById<TextView>(R.id.statusText)
        /* onResume always follows onCreate, so the instruction set there was
           overwritten before it could be read — a first-time user got
           "No novels found. ()", empty parens and all, instead of being told
           a folder is needed. */
        if (folderKey == null) {
            list.removeAllViews()
            status.text = "Pick a download folder first."
            return
        }
        val stillGranted = try {
            contentResolver.persistedUriPermissions.any {
                it.isReadPermission && it.uri.toString() == folderKey
            }
        } catch (e: Exception) { true }
        if (!stillGranted) {
            list.removeAllViews()
            status.text = "The download folder is no longer available — pick it again in Settings."
            return
        }
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
            status.text = finalStatus ?: "${rs.size} novel(s)"
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
                val dirName = store.dirNameOrGuess(folder, row.rec.slug, row.rec.title)
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
                            /* Whichever spot is MORE RECENT — the shortcut is
                               "a novel you are listening to opens in the
                               reader", not "the read-aloud spot always wins".
                               The other two ways into the reader ask
                               resumeChapter; this one, the main way in, took
                               the TTS chapter whatever its age, and then
                               openAt wrote it back as lastCh with a fresh
                               timestamp — so an evening of scroll-reading was
                               not just skipped past but erased, with nothing
                               else holding a copy of it. */
                            .putExtra(
                                "start",
                                ReaderActivity.resumeChapter(ctx, row.rec.slug) ?: ttsChapter,
                            )
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
        /* MORE files than the site lists — something is off, and the fix is
           a download: it re-fetches the novel and clears whatever is surplus.
           Counting that as settled hides the only button that can fix it. */
        val surplus = row.rec.total > 0 && row.local > row.rec.total
        val done = row.rec.complete && haveAll && !surplus
        /* checked, still-ongoing novel with every site chapter on disk:
           nothing to download until the site adds more */
        val upToDate = !row.rec.complete && haveAll && !surplus
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
       every trace of it — re-downloading later warns first */
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

    /* Do this novel's recorded chapters still open? A sample is enough — the
       question is whether the folder is somewhere on disk under a name we
       failed to match, not how much of it survives. */
    private fun filesStillThere(treeUri: Uri, folder: String, slug: String): Boolean {
        val uris = try { store.get(folder, slug).values } catch (e: Exception) { return false }
        var checked = 0
        for (u in uris) {
            if (u.isEmpty() || Zips.isGzRef(u)) continue
            val there = try {
                DocumentFile.fromSingleUri(this, Uri.parse(u))?.exists() == true
            } catch (e: Exception) { false }
            if (there) return true
            if (++checked >= 5) break
        }
        return false
    }

    private fun doGarbage(row: Row) {
        val folder = folderKey ?: return
        val slug = row.rec.slug
        val status = findViewById<TextView>(R.id.statusText)
        /* Not while it is downloading. Everything else that touches a novel's
           files asks this first — the status sweep asks twice — because a
           rename racing a download is unacceptable; this RECURSIVELY DELETES
           the folder the engine is writing into. And a queued novel would be
           started minutes later by the queue, re-downloading in full the
           thing just thrown away, into a folder the Library now filters out
           for good. */
        if (DownloadService.isBusy(normKey(slug))) {
            Toast.makeText(
                this,
                "That novel is downloading — stop it first",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
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
            /* This recursively deletes a directory, so which one it picks has
               to be answered from the record, not rebuilt from the title. The
               name a title rebuilds is the UNSUFFIXED one — for a novel pushed
               off a colliding name, that is the OTHER novel's folder, and the
               fallback arm below (match by slug) meant which of the two got
               deleted came down to the order the provider happened to
               enumerate them in. The recorded directory settles it; the slug
               arm is only for a library older than that column. */
            var deleted = true
            try {
                val dirName = try {
                    store.dirNameOrGuess(folder, slug, row.rec.title)
                } catch (e: Exception) { Extractor.folderName(row.rec.title.ifEmpty { slug }, slug) }
                val kids = children(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                /* The recorded name first — it is the only one that can be
                   trusted when two novels' titles sanitise alike. The slug
                   match stays as a FALLBACK for when that name isn't on disk
                   (the user renamed the folder, a restore recreated it): its
                   danger was being tried alongside the recorded name, where
                   the provider's enumeration order decided which of two
                   colliding novels got deleted. */
                val dir = kids.firstOrNull { it.third && it.second == dirName }
                    ?: kids.firstOrNull { it.third && slugify(it.second) == slug }
                if (dir != null) {
                    /* a refusal is REPORTED, not thrown */
                    deleted = DocumentsContract.deleteDocument(
                        contentResolver,
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, dir.first),
                    )
                } else if (row.local > 0 && filesStillThere(treeUri, folder, slug)) {
                    /* Not found by name, but its chapters still open — so the
                       folder is out there under a name we don't know (renamed
                       in a file manager, recreated by a restore). Wiping the
                       record then left a directory of chapters nothing in the
                       app could name, list or remove, with the novel hidden
                       from the Library for good.

                       Asked of the FILES, not of the count. `disk_count` is
                       never lowered when a folder disappears from underneath
                       the app, so "the count says it has chapters" was true
                       forever for a novel the user had already deleted by
                       hand — and that row could then never be removed from
                       the Library at all. */
                    deleted = false
                }
            } catch (e: Exception) { deleted = false }
            if (!deleted) {
                /* The folder is still there. Erasing the record anyway left a
                   directory of chapters nothing in the app could name, list or
                   remove — so keep the novel, say so, and let the user try
                   again. */
                prefs.edit().putStringSet(GARBAGE_KEY, garbageSet() - normKey(slug)).apply()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@NovelListActivity,
                        "Could not delete that novel's folder — nothing was removed",
                        Toast.LENGTH_LONG,
                    ).show()
                    render()
                }
                return@launch
            }
            try { store.removeNovel(folder, slug) } catch (e: Exception) {}
            try { store.clear(folder, slug) } catch (e: Exception) {}
            try { store.setChapterOrder(folder, slug, emptyList()) } catch (e: Exception) {}
            /* let go of the folder name too, or it stays reserved for a novel
               that no longer exists and pushes the next one into a suffix */
            try { store.releaseFolderName(folder, slug) } catch (e: Exception) {}
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
           downloaded — go to the same log the console footer prints, otherwise
           the only way to see them is to run a full download. */
        val engine = DownloadEngine(
            this,
            { line -> DownloadService.appendLog(line) },
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
            /* Guarded, and released in a finally. rows() and
               chapterOrderCount both hit the database, and this is a bare
               launch with no handler — a locked or full database took the
               whole app down before the sweep had visited a single novel. The
               writes further down were wrapped for exactly this reason; the
               reads that decide WHAT to visit were left exposed, as was the
               button, which stayed dead until the screen was recreated. */
            val targets = try { withContext(Dispatchers.IO) {
                rows().filter {
                    val done = it.rec.complete && it.rec.total > 0 && it.local == it.rec.total
                    /* A check renames and dedupes. Running that over a novel
                       the service is downloading right now means two passes
                       moving the same files: the download writes a chapter,
                       the check renames it out from under the write, and the
                       counts they each save contradict each other. Leave a
                       busy novel to its download — it renames on the way out
                       anyway. */
                    !DownloadService.isBusy(normKey(it.rec.slug)) &&
                        (!done || store.chapterOrderCount(folder, it.rec.slug) == 0)
                }
            } } catch (e: Exception) {
                status.text = "Couldn't read the library — ${e.message}"
                btn.isEnabled = true
                return@launch
            }
            if (targets.isEmpty()) {
                status.text = "Nothing to check — all novels are complete and indexed."
                btn.isEnabled = true
                return@launch
            }
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            /* Novels whose own setting says to fetch whatever the check finds.
               Collected rather than started on the spot: this runs three wide
               and a download started mid-sweep gets its files renamed and
               deduped out from under the write by the very sweep that started
               it. They go to the queue once every novel has been asked. */
            val fetch = java.util.Collections.synchronizedList(ArrayList<String>())
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val sem = Semaphore(3)
                    for (row in targets) {
                        launch {
                            sem.withPermit {
                                /* The busy test at snapshot time is not enough:
                                   this sweep runs three wide for minutes, and
                                   a download started after it began gets its
                                   files renamed and deduped out from under the
                                   write. Ask again when this novel's turn
                                   actually comes. */
                                if (DownloadService.isBusy(normKey(row.rec.slug))) {
                                    done.incrementAndGet()
                                    return@withPermit
                                }
                                val res = NovelCheck.one(
                                    engine, store, folder, row.rec, row.display, row.local,
                                )
                                /* the setting as it is NOW, not as it was when
                                   the sweep snapshotted its targets — the busy
                                   test got a re-ask for the same reason, and a
                                   sweep runs for minutes: un-ticking
                                   auto-download mid-sweep must stick, because
                                   with translation pinned on it is money */
                                if (res != null && res.missing > 0 &&
                                    try {
                                        store.novel(folder, row.rec.slug)?.autoDownload == true
                                    } catch (e: Exception) { false }
                                ) {
                                    fetch.add(res.url)
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
            val started = fetch.toList().count { NovelCheck.startDownload(this@NovelListActivity, it) }
            btn.isEnabled = true
            /* through render, not before it — render overwrites the status
               twice in the same turn, so a message set here was never seen */
            render(
                "Status checked (${targets.size} novel(s))." + when {
                    fetch.isEmpty() -> ""
                    started == fetch.size -> " Downloading $started with new chapters."
                    /* from the background the service start is refused — say
                       so rather than reporting downloads that never began */
                    else -> " $started of ${fetch.size} downloads started — the rest were refused; open the app and check again."
                },
            )
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
