package dev.vtlinh.noveldownloader

import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

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

    /* True when the picture list was up and is now gone — Back
       should stay on the reading page, not leave the book. */
    fun closeIfOpen(): Boolean {
        if (shown == null) return false
        close()
        return true
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
            val overlay = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
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
            val bar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(activity.getColor(R.color.card))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            bar.addView(
                ImageView(activity).apply {
                    setImageResource(R.drawable.ic_back)
                    contentDescription = "Back"
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        activity.getColor(R.color.fg),
                    )
                    val box = dp(40)
                    val pad = dp(8)
                    setPadding(pad, pad, pad, pad)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    isClickable = true
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(box, box)
                    setOnClickListener { close() }
                },
            )
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val pad = dp(18)
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
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
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
                addView(column)
            }
            overlay.addView(bar)
            overlay.addView(scroll)
            activity.onBackPressedDispatcher.addCallback(activity, back)
            shown = overlay to back
            host.addView(overlay)
            loadAround(activity, items, rows, start, edge, scroll)
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

    /* Decode the opened picture first and put it in the middle.
       Neighbours then load around it. A picture above that grows
       shifts the list by the extra height so the opened one
       does not slide away. */
    private fun loadAround(
        activity: AppCompatActivity,
        items: List<Pair<ChapterImages.Saved, Bitmap?>>,
        rows: List<View>,
        start: Int,
        edge: Int,
        scroll: ScrollView,
    ) {
        activity.lifecycleScope.launch {
            bindRow(activity, items, rows, start, edge)
            if (!rows[start].isAttachedToWindow) return@launch
            awaitLayout(rows[start])
            centerRow(scroll, rows[start])
            val order = ArrayList<Int>(items.size - 1)
            var before = start - 1
            var after = start + 1
            while (before >= 0 || after < items.size) {
                if (after < items.size) order.add(after++)
                if (before >= 0) order.add(before--)
            }
            for (i in order) {
                if (!rows[i].isAttachedToWindow) return@launch
                val beforeH = rows[i].height
                bindRow(activity, items, rows, i, edge)
                awaitLayout(rows[i])
                val shift = ChapterImages.scrollShiftWhenAboveGrows(
                    i < start, beforeH, rows[i].height,
                )
                if (shift != 0) scroll.scrollBy(0, shift)
            }
        }
    }

    private suspend fun bindRow(
        activity: AppCompatActivity,
        items: List<Pair<ChapterImages.Saved, Bitmap?>>,
        rows: List<View>,
        i: Int,
        edge: Int,
    ) {
        val (item, preview) = items[i]
        val img = rows[i].findViewWithTag<ImageView>("img") ?: return
        val bmp = preview ?: withContext(Dispatchers.IO) {
            ChapterImages.thumb(activity, item.uri, edge)
        }
        if (bmp != null && img.isAttachedToWindow) img.setImageBitmap(bmp)
    }

    private suspend fun awaitLayout(view: View) =
        suspendCancellableCoroutine { cont ->
            view.post {
                if (cont.isActive) cont.resume(Unit)
            }
        }
}
