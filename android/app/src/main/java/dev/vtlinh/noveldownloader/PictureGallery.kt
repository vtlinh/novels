package dev.vtlinh.noveldownloader

import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* Vertical list of chapter pictures. The reader toolbar opens this
   at the current chapter — or the closest later chapter that has a
   picture. Each row is the picture with its title under it.

   This is a RecyclerView, not a ScrollView. Rows are bound as they
   come on screen; inserting above does not rewrite a pixel scroll
   offset. A decode that finishes before the row is attached is
   still kept, so neighbours are not left empty. The opened row
   is placed with scrollToPositionWithOffset.

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
        if (activity.isFinishing || activity.isDestroyed) return
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
        val pad = dp(18)
        val boxW = activity.resources.displayMetrics.widthPixels - pad * 2
        val slotH = ChapterImages.galleryImageSlot(
            activity.resources.displayMetrics.heightPixels,
            dp(120),
            boxW,
        )
        val layout = object : LinearLayoutManager(activity) {
            override fun calculateExtraLayoutSpace(
                state: RecyclerView.State,
                extraLayoutSpace: IntArray,
            ) {
                val extra = slotH * 2
                extraLayoutSpace[0] = extra
                extraLayoutSpace[1] = extra
            }
        }
        layout.initialPrefetchItemCount = ChapterImages.GALLERY_BATCH
        val list = RecyclerView(activity).apply {
            layoutManager = layout
            itemAnimator = null
            setItemViewCacheSize(8)
            setBackgroundColor(activity.getColor(R.color.bg))
            setPadding(pad, pad, pad, pad)
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            adapter = GalleryAdapter(activity, items, edge, dp, slotH)
        }
        overlay.addView(bar)
        overlay.addView(list)
        activity.onBackPressedDispatcher.addCallback(activity, back)
        shown = overlay to back
        host.addView(overlay)
        list.post {
            if (!list.isAttachedToWindow) return@post
            val rowH = slotH + dp(16) + dp(28)
            val offset = KeepVisible.centerOffset(list.height, rowH)
            layout.scrollToPositionWithOffset(start, offset)
        }
    }

    private class Holder(
        val img: ImageView,
        val caption: TextView,
        row: LinearLayout,
    ) : RecyclerView.ViewHolder(row) {
        var chapter: String = ""
    }

    private class GalleryAdapter(
        private val activity: AppCompatActivity,
        private val items: List<Pair<ChapterImages.Saved, Bitmap?>>,
        private val edge: Int,
        private val dp: (Int) -> Int,
        private val slotH: Int,
    ) : RecyclerView.Adapter<Holder>() {

        private val thumbs = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
                size > 40
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(16), 0, 0)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                )
            }
            val img = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(activity.getColor(R.color.card))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    slotH,
                )
            }
            val caption = TextView(activity).apply {
                textSize = 16f
                setTextColor(activity.getColor(R.color.fg))
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.25f)
                setPadding(0, dp(6), 0, 0)
            }
            row.addView(img)
            row.addView(caption)
            return Holder(img, caption, row)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (item, preview) = items[position]
            val title = if (ChapterImages.showAlt(item.alt)) item.alt.trim()
                else if (item.label.isNotEmpty()) "Chapter ${item.label}"
                else item.chapter
            holder.chapter = item.chapter
            holder.caption.text = title
            holder.img.contentDescription = title
            val ready = preview ?: thumbs[item.chapter]
            if (ready != null) {
                show(holder.img, ready)
                return
            }
            holder.img.setImageBitmap(null)
            holder.img.setBackgroundColor(activity.getColor(R.color.card))
            val chapter = item.chapter
            activity.lifecycleScope.launch {
                val bmp = thumbs[chapter] ?: withContext(Dispatchers.IO) {
                    ChapterImages.thumb(activity, item.uri, edge)
                }
                if (bmp != null) thumbs[chapter] = bmp
                if (bmp != null && KeepVisible.stillThisRow(holder.chapter, chapter)) {
                    show(holder.img, bmp)
                }
            }
        }

        override fun onViewAttachedToWindow(holder: Holder) {
            val bmp = thumbs[holder.chapter] ?: return
            show(holder.img, bmp)
        }

        override fun onViewRecycled(holder: Holder) {
            holder.chapter = ""
            holder.img.setImageBitmap(null)
        }

        private fun show(img: ImageView, bmp: Bitmap) {
            img.setImageBitmap(bmp)
            img.setBackgroundColor(activity.getColor(R.color.bg))
        }
    }
}
