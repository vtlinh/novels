package dev.vtlinh.noveldownloader

import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* Full-screen chapter picture. Used from the chapter-list synopsis
   grid and from a tap on the picture in the reader. The image is
   MATCH_PARENT and owns every gesture. Pinch zooms the drawable
   matrix. Back or a tap at 1× dismisses. Swipe left / right at 1×
   steps to the next / previous saved picture — no wrap. A zoomed
   picture pans instead. A missing decode is a no-op so a broken
   file cannot crash the page. */
object PictureGallery {

    fun show(
        activity: AppCompatActivity,
        items: List<Pair<ChapterImages.Saved, android.graphics.Bitmap?>>,
        start: Int,
    ) {
        if (start !in items.indices) return
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
            val dialog = android.app.Dialog(
                activity,
                android.R.style.Theme_DeviceDefault_NoActionBar,
            )
            var index = start
            var loadGen = 0
            val img = ZoomImageView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                setImageBitmap(shown)
            }
            val caption = TextView(activity).apply {
                textSize = 15f
                setTextColor(activity.getColor(R.color.fg))
                setLineSpacing(0f, 1.25f)
                val pad = (24 * activity.resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 3, pad, pad)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
                )
            }
            fun bindCaption(item: ChapterImages.Saved) {
                img.contentDescription =
                    if (ChapterImages.showAlt(item.alt)) item.alt else item.label
                if (ChapterImages.showAlt(item.alt)) {
                    caption.text = item.alt.trim()
                    caption.visibility = android.view.View.VISIBLE
                } else {
                    caption.visibility = android.view.View.GONE
                }
            }
            fun loadSharp(item: ChapterImages.Saved) {
                val gen = ++loadGen
                activity.lifecycleScope.launch {
                    val sharper = withContext(Dispatchers.IO) {
                        ChapterImages.thumb(activity, item.uri, edge)
                    }
                    if (gen == loadGen && dialog.isShowing && sharper != null) {
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
            img.onDismissTap = { dialog.dismiss() }
            img.onSwipe = { go(it) }
            bindCaption(first.first)
            val root = FrameLayout(activity).apply {
                setBackgroundColor(activity.getColor(R.color.bg))
                clipChildren = false
            }
            /* Caption sits on the picture so the image can be MATCH_PARENT.
               Touches there must reach the image — a sibling TextView
               would otherwise eat pinch and swipe at the bottom. */
            caption.setOnTouchListener { v, ev ->
                ev.offsetLocation(v.left.toFloat(), v.top.toFloat())
                img.dispatchTouchEvent(ev)
            }
            root.addView(img)
            root.addView(caption)
            val backPad = (16 * activity.resources.displayMetrics.density).toInt()
            root.addView(
                TextView(activity).apply {
                    text = "←"
                    textSize = 24f
                    setTextColor(activity.getColor(R.color.fg))
                    setPadding(backPad, backPad, backPad, backPad)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { dialog.dismiss() }
                },
            )
            dialog.setContentView(root)
            dialog.setCancelable(true)
            dialog.setCanceledOnTouchOutside(true)
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            dialog.window?.setBackgroundDrawable(
                ColorDrawable(activity.getColor(R.color.bg)),
            )
            dialog.show()
            if (first.second != null) loadSharp(first.first)
        }
    }
}
