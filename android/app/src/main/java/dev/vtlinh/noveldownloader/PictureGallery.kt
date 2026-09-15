package dev.vtlinh.noveldownloader

import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* Vertical list of chapter pictures. The reader toolbar opens this
   at the current chapter — or the closest later chapter that has a
   picture —
   and then fills in the pictures before and after. Each row is the
   picture with its title under it, centered in the list.

   This is an overlay on the activity window — not a Dialog.
   Dialog is a second window; a second pointer often never reached
   the picture, so pinch did nothing. Overlay keeps every pointer
   in the same stream as the rest of the app. */
object PictureGallery {

    private var shown: Pair<View, OnBackPressedCallback>? = null

    private fun close() {
        val cur = shown ?: return
        shown = null
        cur.second.isEnabled = false
        cur.second.remove()
        (cur.first.parent as? ViewGroup)?.removeView(cur.first)
    }

    fun show(
        activity: AppCompatActivity,
        items: List<Pair<ChapterImages.Saved, Bitmap?>>,
        start: Int,
    ) {
        if (start !in items.indices) return
        val host = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        activity.lifecycleScope.launch {
            if (activity.isFinishing || activity.isDestroyed) return@launch
            close()
            val density = activity.resources.displayMetrics.density
            val dp: (Int) -> Int = { n -> (n * density).toInt() }
            val edge = maxOf(
                activity.resources.displayMetrics.widthPixels,
                activity.resources.displayMetrics.heightPixels,
            )
            val overlay = FrameLayout(activity).apply {
                setBackgroundColor(activity.getColor(R.color.bg))
                isClickable = true
                isFocusable = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            val back = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { close() }
            }
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val pad = dp(18)
                setPadding(pad, pad, pad, pad)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            val rows = ArrayList<View>(items.size)
            for ((item, _) in items) {
                val row = makeRow(activity, item, dp)
                rows.add(row)
                column.addView(row)
            }
            val scroll = ScrollView(activity).apply {
                isFillViewport = true
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                addView(column)
            }
            overlay.addView(scroll)
            val backPad = dp(16)
            overlay.addView(
                TextView(activity).apply {
                    text = "←"
                    textSize = 24f
                    setTextColor(activity.getColor(R.color.fg))
                    setPadding(backPad, backPad, backPad, backPad)
                    isClickable = true
                    isFocusable = true
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.START,
                    )
                    setOnClickListener { close() }
                },
            )
            activity.onBackPressedDispatcher.addCallback(activity, back)
            shown = overlay to back
            host.addView(overlay)
            scroll.post {
                centerRow(scroll, rows.getOrNull(start))
            }
            loadAround(activity, items, rows, start, edge)
        }
    }

    private fun makeRow(
        activity: AppCompatActivity,
        item: ChapterImages.Saved,
        dp: (Int) -> Int,
    ): LinearLayout {
        val maxImgH = (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
        val title = if (ChapterImages.showAlt(item.alt)) item.alt.trim()
            else if (item.label.isNotEmpty()) "Chapter ${item.label}"
            else item.chapter
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val gap = dp(28)
            setPadding(0, gap, 0, gap)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            addView(
                ImageView(activity).apply {
                    tag = "img"
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = title
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                    maxHeight = maxImgH.coerceAtLeast(dp(120))
                },
            )
            addView(
                TextView(activity).apply {
                    text = title
                    textSize = 16f
                    setTextColor(activity.getColor(R.color.fg))
                    gravity = Gravity.CENTER
                    setLineSpacing(0f, 1.25f)
                    setPadding(0, dp(12), 0, 0)
                },
            )
        }
    }

    private fun centerRow(scroll: ScrollView, row: View?) {
        if (row == null) return
        val mid = row.top + row.height / 2 - scroll.height / 2
        scroll.scrollTo(0, mid.coerceAtLeast(0))
    }

    /* Decode the starting picture first, then walk outward so the
       list fills from the chapter the reader is on. */
    private fun loadAround(
        activity: AppCompatActivity,
        items: List<Pair<ChapterImages.Saved, Bitmap?>>,
        rows: List<View>,
        start: Int,
        edge: Int,
    ) {
        val order = ArrayList<Int>(items.size)
        order.add(start)
        var before = start - 1
        var after = start + 1
        while (before >= 0 || after < items.size) {
            if (after < items.size) order.add(after++)
            if (before >= 0) order.add(before--)
        }
        activity.lifecycleScope.launch {
            for (i in order) {
                if (!rows[i].isAttachedToWindow) return@launch
                val (item, preview) = items[i]
                val img = rows[i].findViewWithTag<ImageView>("img") ?: continue
                val bmp = preview ?: withContext(Dispatchers.IO) {
                    ChapterImages.thumb(activity, item.uri, edge)
                }
                if (bmp != null && img.isAttachedToWindow) img.setImageBitmap(bmp)
            }
        }
    }
}
