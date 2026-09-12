package dev.vtlinh.noveldownloader

import android.app.Dialog
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* Chapter picture in a dialog: image plus its alt text. The reader
   toolbar and each chapter-list row that has a picture open this.
   Opening the chapter can also show it for AUTO_MS, then fade it
   away — unless a finger lands on the card, after which only a tap
   outside or Back closes it. */
object ChapterImagePreview {

    const val AUTO_MS = 15_000L
    const val FADE_MS = 400L

    enum class Mode { AUTO, HOLD }

    fun shouldShowButton(hasImage: Boolean): Boolean = hasImage

    /* False: a focusable picture button makes ListView drop the row
       tap, so a pictured chapter cannot be opened. */
    fun pictureButtonFocusable(): Boolean = false

    fun shouldAutoOpen(
        hasImage: Boolean,
        navigating: Boolean,
        alreadyAutoShown: Boolean,
    ): Boolean = hasImage && navigating && !alreadyAutoShown

    fun startMode(auto: Boolean): Mode = if (auto) Mode.AUTO else Mode.HOLD

    fun afterInteract(mode: Mode): Mode = when (mode) {
        Mode.AUTO, Mode.HOLD -> Mode.HOLD
    }

    fun shouldFade(mode: Mode): Boolean = mode == Mode.AUTO

    private var shown: Dialog? = null

    fun close() {
        val d = shown ?: return
        shown = null
        if (d.isShowing) d.dismiss()
    }

    fun show(
        activity: AppCompatActivity,
        item: ChapterImages.Saved,
        preview: Bitmap?,
        auto: Boolean,
    ) {
        val hostW = activity.resources.displayMetrics.widthPixels
        activity.lifecycleScope.launch {
            val edge = maxOf(hostW, activity.resources.displayMetrics.heightPixels)
            val bmp = preview ?: withContext(Dispatchers.IO) {
                ChapterImages.thumb(activity, item.uri, edge)
            } ?: return@launch
            if (activity.isFinishing || activity.isDestroyed) return@launch
            close()
            present(activity, item, bmp, auto)
        }
    }

    private fun present(
        activity: AppCompatActivity,
        item: ChapterImages.Saved,
        bmp: Bitmap,
        auto: Boolean,
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val handler = Handler(Looper.getMainLooper())
        var mode = startMode(auto)
        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        val fade = Runnable {
            if (!shouldFade(mode) || !dialog.isShowing) return@Runnable
            val root = dialog.window?.decorView ?: return@Runnable
            root.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                if (dialog.isShowing && shouldFade(mode)) dialog.dismiss()
            }
        }
        fun hold() {
            mode = afterInteract(mode)
            handler.removeCallbacks(fade)
            val root = dialog.window?.decorView
            root?.animate()?.cancel()
            root?.alpha = 1f
        }

        val dim = FrameLayout(activity).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            isFocusable = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setOnClickListener { dialog.dismiss() }
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(
                activity, R.drawable.bg_settings_card,
            )
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) hold()
                false
            }
        }
        val maxImgH = (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
        card.addView(
            ImageView(activity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bmp)
                contentDescription =
                    if (ChapterImages.showAlt(item.alt)) item.alt else item.label
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                maxHeight = maxImgH.coerceAtLeast(dp(120))
            },
        )
        if (ChapterImages.showAlt(item.alt)) {
            card.addView(
                TextView(activity).apply {
                    text = item.alt.trim()
                    textSize = 15f
                    setTextColor(activity.getColor(R.color.fg))
                    setLineSpacing(0f, 1.25f)
                    setPadding(0, dp(12), 0, 0)
                },
            )
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                val edge = dp(18)
                setMargins(edge, edge, edge, edge)
            }
            addView(card)
            /* A tap on the caption or a scroll is the reader taking
               over — keep the dialog up until they close it. */
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) hold()
                false
            }
        }
        dim.addView(scroll)
        dialog.setContentView(dim)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            handler.removeCallbacks(fade)
            if (shown === dialog) shown = null
        }
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        shown = dialog
        dialog.show()
        if (shouldFade(mode)) handler.postDelayed(fade, AUTO_MS)
    }
}
