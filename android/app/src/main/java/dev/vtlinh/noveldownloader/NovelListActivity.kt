package dev.vtlinh.noveldownloader

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
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
        findViewById<TextView>(R.id.navNovels).setOnClickListener {
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
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

    private val chapterFileRe = Regex("Chapter \\d+.*\\.txt")

    /* set by rows(): how many entries each source contributed + any error,
       so an unexpectedly empty list can explain itself */
    @Volatile private var sourceInfo = ""

    private fun rows(): List<Row> {
        val folder = folderKey ?: return emptyList()
        val all = LinkedHashMap<String, NovelRec>()
        var err = ""
        try {
            for (rec in store.novels(folder)) all[rec.slug] = rec
        } catch (e: Exception) { err += " registry:${e.message}" }
        val registryN = all.size
        /* chapter index knows novels the registry may not */
        try {
            for (slug in store.chapterSlugs(folder)) {
                if (slug !in all) all[slug] = NovelRec(slug, "", slug, "", 0L, -1, false)
            }
        } catch (e: Exception) { err += " index:${e.message}" }

        /* one root listing, reused for the scan and the count fallback */
        val dirs = try {
            val root = DocumentFile.fromTreeUri(this, Uri.parse(folder))
            if (root == null || !root.canRead()) err += " folder-access-lost"
            root?.listFiles()?.filter { it.isDirectory }?.associateBy { it.name ?: "" } ?: emptyMap()
        } catch (e: Exception) { err += " folders:${e.message}"; emptyMap() }
        fun countIn(name: String): Int = try {
            dirs[name]?.listFiles()?.count { chapterFileRe.matches(it.name ?: "") } ?: 0
        } catch (e: Exception) { 0 }

        /* the on-disk folder name each known novel saves into */
        val folderName = HashMap<String, String>()
        for (rec in all.values) {
            folderName[rec.slug] = store.getTitle(folder, rec.slug) ?: Extractor.sanitize(rec.title)
        }
        /* root-folder scan: subdirectories not accounted for by any known
           novel — downloaded before the registry existed, or copied in */
        val known = folderName.values.toHashSet()
        val indexN = all.size
        try {
            for ((name, _) in dirs) {
                if (name.isEmpty() || name in known) continue
                val slug = slugify(name)
                if (slug.isEmpty() || slug in all) continue
                if (countIn(name) == 0) continue   // not a novel folder
                all[slug] = NovelRec(slug, "", name, "", 0L, -1, false)
                folderName[slug] = name
            }
        } catch (e: Exception) { err += " scan:${e.message}" }
        sourceInfo = "registry $registryN · index ${indexN - registryN} · folders ${all.size - indexN}" +
            (if (err.isNotEmpty()) " · errors:$err" else "")

        return all.values.map { rec ->
            val dbCount = try { store.chapterCount(folder, rec.slug) } catch (e: Exception) { 0 }
            Row(
                rec,
                store.getTitle(folder, rec.slug) ?: rec.title.ifEmpty { rec.slug },
                if (dbCount > 0) dbCount else countIn(folderName[rec.slug] ?: ""),
            )
        }.sortedWith(compareBy<Row> { it.rec.complete }.thenByDescending { it.rec.started })
    }

    private fun render() {
        val list = findViewById<LinearLayout>(R.id.novelList)
        val status = findViewById<TextView>(R.id.statusText)
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
            for (row in rs) {
                try { list.addView(buildRow(row)) } catch (e: Exception) {}
            }
        }
    }

    private fun buildRow(row: Row): View {
        val ctx = this
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val text = buildString {
            append(row.display)
            if (row.rec.author.isNotEmpty()) append("\n${row.rec.author}")
            if (!row.rec.complete) {
                append("\n${row.local} chapter(s)")
                if (row.rec.total > 0) append(" of ${row.rec.total}")
                if (row.rec.url.isEmpty()) append(" — tap Check status to locate")
            }
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
        } else if (row.rec.url.isNotEmpty()) {
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
            /* a complete novel (site finished + everything on disk) can never
               regress — no need to ever recheck it */
            val targets = withContext(Dispatchers.IO) { rows() }.filter { !it.rec.complete }
            if (targets.isEmpty()) {
                status.text = "Nothing to check — all novels are complete."
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
