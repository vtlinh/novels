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

   Only a few neighbours are drawn at first. Each row keeps a
   fixed empty box; the picture fills that box so the list does
   not jump. Reaching the first or last picture on screen loads
   the next batch.

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
        val window = ChapterImages.galleryOpenWindow(start, items.size)
        if (window.isEmpty) return
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
            val rows = ArrayList<View>()
            for (i in window.low..window.high) {
                val row = makeRow(activity, items[i].first, dp)
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
            loadWindow(
                activity, items, rows, start, window.low, window.high,
                edge, dp, column, scroll,
            )
        }
    }

    private fun makeRow(
        activity: AppCompatActivity,
        item: ChapterImages.Saved,
        dp: (Int) -> Int,
    ): LinearLayout {
        val slotH = ChapterImages.galleryImageSlot(
            activity.resources.displayMetrics.heightPixels,
            dp(120),
        )
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
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = title
                    setBackgroundColor(activity.getColor(R.color.card))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        slotH,
                    )
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

    private fun rowOnScreen(scroll: ScrollView, row: View?): Boolean {
        if (row == null) return false
        return ChapterImages.galleryRowOnScreen(
            row.top, row.bottom, scroll.scrollY, scroll.height,
        )
    }

    /* Place the empty boxes first and put the opened one in the
       middle. Pictures then fill those boxes. Reaching the first
       or last one loads the next batch. New boxes above shift the
       list by their height so the opened one does not slide away. */
    private fun loadWindow(
        activity: AppCompatActivity,
        items: List<Pair<ChapterImages.Saved, Bitmap?>>,
        rows: ArrayList<View>,
        start: Int,
        first: Int,
        last: Int,
        edge: Int,
        dp: (Int) -> Int,
        column: LinearLayout,
        scroll: ScrollView,
    ) {
        var low = first
        var high = last
        var ready = false
        var loading = false

        fun stillOpen(): Boolean =
            rows.firstOrNull()?.isAttachedToWindow == true &&
                !activity.isFinishing && !activity.isDestroyed

        suspend fun bind(i: Int) {
            val (item, preview) = items[i]
            bindRow(activity, item, preview, rows[i - low], edge)
        }

        suspend fun extendUp() {
            val next = ChapterImages.galleryExtendUp(low)
            if (next.isEmpty) return
            val added = ArrayList<View>(next.high - next.low + 1)
            for (i in next.low..next.high) {
                if (!stillOpen()) return
                val row = makeRow(activity, items[i].first, dp)
                added.add(row)
            }
            if (!stillOpen()) return
            for ((n, row) in added.withIndex()) {
                column.addView(row, n)
                rows.add(n, row)
            }
            low = next.low
            added.lastOrNull()?.let { awaitLayout(it) }
            val shift = ChapterImages.galleryPrependShift(added.sumOf { it.height })
            if (shift != 0) scroll.scrollBy(0, shift)
            for ((n, i) in (next.low..next.high).withIndex()) {
                if (!stillOpen()) return
                val (item, preview) = items[i]
                bindRow(activity, item, preview, added[n], edge)
            }
        }

        suspend fun extendDown() {
            val next = ChapterImages.galleryExtendDown(high, items.size)
            if (next.isEmpty) return
            val added = ArrayList<View>(next.high - next.low + 1)
            for (i in next.low..next.high) {
                if (!stillOpen()) return
                val row = makeRow(activity, items[i].first, dp)
                column.addView(row)
                rows.add(row)
                added.add(row)
                high = i
            }
            added.lastOrNull()?.let { awaitLayout(it) }
            for ((n, row) in added.withIndex()) {
                if (!stillOpen()) return
                val (item, preview) = items[next.low + n]
                bindRow(activity, item, preview, row, edge)
            }
        }

        fun maybeMore() {
            if (!ready || loading || !stillOpen()) return
            val top = rowOnScreen(scroll, rows.firstOrNull())
            val bottom = rowOnScreen(scroll, rows.lastOrNull())
            val up = ChapterImages.galleryShouldExtendUp(low, top)
            val down = ChapterImages.galleryShouldExtendDown(high, items.size, bottom)
            if (!up && !down) return
            loading = true
            activity.lifecycleScope.launch {
                try {
                    if (up) extendUp() else extendDown()
                } finally {
                    loading = false
                }
                if (stillOpen()) maybeMore()
            }
        }

        scroll.setOnScrollChangeListener { _, _, _, _, _ -> maybeMore() }

        activity.lifecycleScope.launch {
            if (!stillOpen()) return@launch
            awaitLayout(rows[start - low])
            centerRow(scroll, rows[start - low])
            ready = true
            maybeMore()
            val (item, preview) = items[start]
            bindRow(activity, item, preview, rows[start - low], edge)
            for (i in low..high) {
                if (i == start) continue
                if (!stillOpen()) return@launch
                bind(i)
            }
        }
    }

    private suspend fun bindRow(
        activity: AppCompatActivity,
        item: ChapterImages.Saved,
        preview: Bitmap?,
        row: View,
        edge: Int,
    ) {
        val img = row.findViewWithTag<ImageView>("img") ?: return
        val bmp = preview ?: withContext(Dispatchers.IO) {
            ChapterImages.thumb(activity, item.uri, edge)
        }
        if (bmp != null) {
            img.setImageBitmap(bmp)
            img.setBackgroundColor(activity.getColor(R.color.bg))
        }
    }

    private suspend fun awaitLayout(view: View) =
        suspendCancellableCoroutine { cont ->
            view.post {
                if (cont.isActive) cont.resume(Unit)
            }
        }
}
