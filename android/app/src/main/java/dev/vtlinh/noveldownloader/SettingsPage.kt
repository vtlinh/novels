package dev.vtlinh.noveldownloader

import android.app.Dialog
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/* The full-page reading settings: a header, a tab bar at the top, one tab
   per settings page. Reading is always there; Novel is there whenever the
   page is about a novel (not a document). */
object SettingsPage {

    class Tab(
        val title: String,
        val content: View,
        /* each time the tab comes into view / goes out of it */
        val shown: () -> Unit = {},
        val hidden: () -> Unit = {},
        /* once, when the page closes, whichever tab was open */
        val closed: () -> Unit = {},
    )

    const val TAB_READING = 0
    const val TAB_NOVEL = 1

    /* `slug` null or empty → Reading only. `reader` is the reader the page
       is opened from, or null from the chapter list. `setUpNovel` lets the
       caller wire the Novel tab before it is shown. */
    fun open(
        host: AppCompatActivity,
        slug: String?,
        dirName: String,
        title: String,
        reader: ReadingSettingsPanel.Reader?,
        startTab: Int,
        setUpNovel: (NovelSettingsPanel) -> Unit = {},
        onClose: () -> Unit = {},
    ): Dialog {
        val reading = ReadingSettingsPanel(host, reader)
        val tabs = mutableListOf(
            Tab("Reading", reading.view, closed = { reading.close() }),
        )
        if (!slug.isNullOrEmpty()) {
            val novel = NovelSettingsPanel(host, slug, dirName, title.ifEmpty { dirName })
            setUpNovel(novel)
            tabs += Tab("Novel", novel.view, shown = { novel.shown() }, hidden = { novel.hidden() })
        }
        val dialog = show(host, "Reading settings", tabs, startTab, onClose)
        reading.closePage = { dialog.dismiss() }
        return dialog
    }

    fun show(
        host: AppCompatActivity,
        heading: String,
        tabs: List<Tab>,
        startTab: Int,
        onClose: () -> Unit = {},
    ): Dialog {
        fun dp(v: Int): Int = (v * host.resources.displayMetrics.density).toInt()
        val dialog = Dialog(host, android.R.style.Theme_DeviceDefault_NoActionBar)
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(host.getColor(R.color.bg))
        }

        val header = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        header.addView(
            TextView(host).apply {
                text = "←"; textSize = 24f; setTextColor(host.getColor(R.color.fg))
                setPadding(0, dp(2), dp(14), dp(2))
                isClickable = true; isFocusable = true
                setOnClickListener { dialog.dismiss() }
            },
        )
        header.addView(
            TextView(host).apply {
                text = heading; textSize = 22f
                setTextColor(host.getColor(R.color.fg))
                setTypeface(null, android.graphics.Typeface.BOLD)
            },
        )
        root.addView(header)

        val bar = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = if (tabs.size > 1) View.VISIBLE else View.GONE
        }
        root.addView(bar)
        root.addView(
            View(host).apply {
                setBackgroundColor(host.getColor(R.color.muted))
                alpha = 0.3f
                visibility = bar.visibility
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1),
        )

        val pages = FrameLayout(host)
        root.addView(
            pages,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        val scrolls = tabs.map { tab ->
            ScrollView(host).apply {
                isFillViewport = true
                addView(
                    LinearLayout(host).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(14), dp(16), dp(24))
                        addView(
                            tab.content,
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                    },
                )
                visibility = View.GONE
                pages.addView(this)
            }
        }

        val labels = mutableListOf<TextView>()
        val lines = mutableListOf<View>()
        var current = -1
        fun select(i: Int) {
            if (i == current) return
            if (current >= 0) {
                tabs[current].hidden()
                scrolls[current].visibility = View.GONE
            }
            current = i
            scrolls[i].visibility = View.VISIBLE
            labels.forEachIndexed { j, tv ->
                tv.setTextColor(host.getColor(if (j == i) R.color.fg else R.color.muted))
                lines[j].visibility = if (j == i) View.VISIBLE else View.INVISIBLE
            }
            tabs[i].shown()
        }
        tabs.forEachIndexed { i, tab ->
            val cell = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true; isFocusable = true
                setOnClickListener { select(i) }
            }
            val label = TextView(host).apply {
                text = tab.title; textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(12), 0, dp(10))
            }
            val line = View(host).apply { setBackgroundColor(host.getColor(R.color.accent)) }
            cell.addView(label)
            cell.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))
            bar.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            labels += label
            lines += line
        }

        dialog.setOnDismissListener {
            if (current >= 0) tabs[current].hidden()
            tabs.forEach { it.closed() }
            onClose()
        }
        dialog.setContentView(root)
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        )
        dialog.show()
        select(startTab.coerceIn(0, tabs.size - 1))
        return dialog
    }
}
