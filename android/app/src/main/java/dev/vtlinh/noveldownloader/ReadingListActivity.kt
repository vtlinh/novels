package dev.vtlinh.noveldownloader

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* Reading mode, screen 1: the downloaded novels (title, author, status).
   Tapping one opens its chapter list. */
class ReadingListActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val store by lazy { DownloadStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reading_list)

        val drawer = findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawerLayout)
        findViewById<TextView>(R.id.menuBtn).setOnClickListener {
            drawer.openDrawer(androidx.core.view.GravityCompat.START)
        }
        findViewById<TextView>(R.id.navHome).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
        findViewById<TextView>(R.id.navNovels).setOnClickListener {
            startActivity(Intent(this, NovelListActivity::class.java))
            finish()
        }
        findViewById<TextView>(R.id.navReading).setOnClickListener {
            drawer.closeDrawer(androidx.core.view.GravityCompat.START)
        }

        render()
    }

    private fun render() {
        val list = findViewById<LinearLayout>(R.id.novelList)
        val status = findViewById<TextView>(R.id.statusText)
        val folder = prefs.getString("tree", null)
        if (folder == null) {
            status.text = "Pick a download folder first."
            return
        }
        status.text = "Loading…"
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                store.novels(folder).map { rec ->
                    val display = Extractor.stripAuthor(
                        store.getTitle(folder, rec.slug) ?: rec.title.ifEmpty { rec.slug },
                        rec.author,
                    )
                    val dirName = store.getTitle(folder, rec.slug) ?: Extractor.sanitize(rec.title)
                    Triple(rec, display, dirName)
                }
            }
            list.removeAllViews()
            if (rows.isEmpty()) {
                status.text = "No downloaded novels yet — open Novels once to scan your folder."
                return@launch
            }
            status.text = "${rows.size} novel(s)"
            /* the 3 most recently READ novels get their own section on top */
            val recent = rows.filter { it.first.lastRead > 0 }
                .sortedByDescending { it.first.lastRead }.take(3)
            val recentSlugs = recent.map { it.first.slug }.toSet()
            val others = rows.filter { it.first.slug !in recentSlugs }
                .sortedByDescending { maxOf(it.first.lastDl, it.first.started) }
            if (recent.isNotEmpty()) {
                list.addView(sectionHeader("RECENTLY READ"))
                for (r in recent) list.addView(novelRow(r))
                list.addView(sectionHeader("ALL NOVELS"))
            }
            for (r in others) list.addView(novelRow(r))
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

    private fun novelRow(row: Triple<NovelRec, String, String>): android.view.View {
        val (rec, display, dirName) = row
        run {
                val line = LinearLayout(this@ReadingListActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dp(12), 0, dp(12))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        startActivity(
                            Intent(this@ReadingListActivity, ChapterListActivity::class.java)
                                .putExtra("dir", dirName).putExtra("title", display)
                                .putExtra("slug", rec.slug),
                        )
                    }
                }
                val cover = DownloadEngine.coverFile(this@ReadingListActivity, rec.slug)
                if (cover.exists()) {
                    line.addView(
                        android.widget.ImageView(this@ReadingListActivity).apply {
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
                    TextView(this@ReadingListActivity).apply {
                        text = buildString {
                            append(display)
                            if (rec.author.isNotEmpty()) append("\n${rec.author}")
                        }
                        textSize = 15f
                        setTextColor(getColor(R.color.fg))
                        setLineSpacing(0f, 1.15f)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
                line.addView(
                    TextView(this@ReadingListActivity).apply {
                        text = if (rec.complete) "COMPLETE" else "ONGOING"
                        textSize = 11f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                        setTextColor(Color.parseColor(if (rec.complete) "#3DDC84" else "#9AA0A6"))
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = dp(6).toFloat()
                            setColor(Color.parseColor(if (rec.complete) "#1E3527" else "#252B33"))
                        }
                    },
                )
            return line
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
