package dev.vtlinh.noveldownloader

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* Full-screen chapter picture. Used from the chapter-list synopsis
   grid and from a tap on the picture in the reader.

   This is an overlay on the activity window — not a Dialog.
   Dialog is a second window; a second pointer often never reached
   the picture, so pinch did nothing and a swipe was read as a tap
   that closed the page. Overlay keeps every pointer in the same
   stream as the rest of the app.

   The picture owns the space above the caption. Pinch zooms the
   drawable matrix. Back or a still tap at 1× dismisses. Swipe
   left / right at 1× steps to the next / previous saved picture —
   no wrap. A zoomed picture pans instead. A missing decode is a
   no-op so a broken file cannot crash the page. */
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
        items: List<Pair<ChapterImages.Saved, android.graphics.Bitmap?>>,
        start: Int,
    ) {
        if (start !in items.indices) return
        val host = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        activity.lifecycleScope.launch {
            val edge = maxOf(
                activity.resources.displayMetrics.widthPixels,
                activity.resources.displayMetrics.heightPixels,
            )
            val first = items[start]
            val shown = first.second ?: withContext(Dispatchers.IO) {
                ChapterImages.thumb(activity, first.first.uri, edge)
            } ?: return@launch
            if (activity.isFinishing || activity.isDestroyed) return@launch
            close()
            var index = start
            var loadGen = 0
            val img = ZoomImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
                setImageBitmap(shown)
            }
            val caption = TextView(activity).apply {
                textSize = 15f
                setTextColor(activity.getColor(R.color.fg))
                setLineSpacing(0f, 1.25f)
                val pad = (24 * activity.resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 3, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            fun bindCaption(item: ChapterImages.Saved) {
                img.contentDescription =
                    if (ChapterImages.showAlt(item.alt)) item.alt else item.label
                if (ChapterImages.showAlt(item.alt)) {
                    caption.text = item.alt.trim()
                    caption.visibility = View.VISIBLE
                } else {
                    caption.visibility = View.GONE
                }
            }
            fun loadSharp(item: ChapterImages.Saved) {
                val gen = ++loadGen
                activity.lifecycleScope.launch {
                    val sharper = withContext(Dispatchers.IO) {
                        ChapterImages.thumb(activity, item.uri, edge)
                    }
                    if (gen == loadGen && img.isAttachedToWindow && sharper != null) {
                        img.setImageBitmap(sharper)
                    }
                }
            }
            fun go(delta: Int) {
                val next = ChapterImages.neighborSaved(index, items.size, delta) ?: return
                index = next
                val (item, preview) = items[next]
                img.resetZoom()
                bindCaption(item)
                if (preview != null) img.setImageBitmap(preview)
                loadSharp(item)
            }
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
            fun dismiss() { close() }
            img.onDismissTap = { dismiss() }
            img.onSwipe = { go(it) }
            bindCaption(first.first)
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
            column.addView(img)
            column.addView(caption)
            overlay.addView(column)
            val backPad = (16 * activity.resources.displayMetrics.density).toInt()
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
                    setOnClickListener { dismiss() }
                },
            )
            activity.onBackPressedDispatcher.addCallback(activity, back)
            shown = overlay to back
            host.addView(overlay)
            if (first.second != null) loadSharp(first.first)
        }
    }
}
