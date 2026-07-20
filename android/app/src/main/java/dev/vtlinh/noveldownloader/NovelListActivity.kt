package dev.vtlinh.noveldownloader

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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

/* List Novels: every novel ever downloaded into the saved folder, resumable
   with one tap — no URLs to remember. "Check status" asks each site for its
   chapter count and Completed/Full flag; a novel whose download has
   everything a finished novel will ever have loses its Download button and
   sinks to the bottom. */
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
        render()
    }

    private fun rows(): List<Row> {
        val folder = folderKey ?: return emptyList()
        val registered = store.novels(folder).associateBy { it.slug }
        val all = LinkedHashMap<String, NovelRec>()
        for ((slug, rec) in registered) all[slug] = rec
        /* legacy novels: in the chapter index but downloaded before the
           registry existed — no URL yet; Check status locates them */
        for (slug in store.chapterSlugs(folder)) {
            if (slug !in all) all[slug] = NovelRec(slug, "", slug, 0L, -1, false)
        }
        return all.values.map { rec ->
            Row(
                rec,
                store.getTitle(folder, rec.slug) ?: rec.title.ifEmpty { rec.slug },
                store.chapterCount(folder, rec.slug),
            )
        }.sortedWith(compareBy<Row> { it.rec.complete }.thenByDescending { it.rec.started })
    }

    private fun render() {
        val list = findViewById<LinearLayout>(R.id.novelList)
        list.removeAllViews()
        val rs = rows()
        if (rs.isEmpty()) {
            findViewById<TextView>(R.id.statusText).text = "No downloaded novels yet."
            return
        }
        for (row in rs) list.addView(buildRow(row))
    }

    private fun buildRow(row: Row): View {
        val ctx = this
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val sub = buildString {
            append("${row.local} chapter(s)")
            if (row.rec.total > 0) append(" of ${row.rec.total}")
            if (row.rec.complete) append(" — complete")
            else if (row.rec.url.isEmpty()) append(" — tap Check status to locate")
        }
        line.addView(
            TextView(ctx).apply {
                text = "${row.display}\n$sub"
                textSize = 14f
                setTextColor(getColor(R.color.fg))
                setLineSpacing(0f, 1.15f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        if (!row.rec.complete && row.rec.url.isNotEmpty()) {
            line.addView(
                MaterialButton(ctx).apply {
                    text = "Download"
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

    /* Ask each site for its chapter count + finished flag. A legacy row with
       no URL is probed at each site's canonical slug URL first. A novel is
       complete when the site says finished AND every chapter is on disk. */
    private fun checkStatuses() {
        val folder = folderKey ?: return
        val btn = findViewById<Button>(R.id.checkBtn)
        val status = findViewById<TextView>(R.id.statusText)
        btn.isEnabled = false
        val engine = DownloadEngine(this, {}, {}, { _, _ -> })
        lifecycleScope.launch {
            val targets = rows()
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
                                    val complete = res.second && store.chapterCount(folder, row.rec.slug) >= res.first
                                    store.updateNovelCheck(folder, row.rec.slug, res.first, complete)
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
