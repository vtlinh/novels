package dev.vtlinh.noveldownloader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ReplacementSpan
import kotlin.math.roundToInt

/* Chapter picture under the heading. A plain ImageSpan is full-bleed
   and picks up the reader's 1.45 line spacing, so a 600px image gets
   ~270px of empty gap. This span draws a padded card and reports a
   line height that the multiplier stretches back to the card size.
   A non-empty alt sits under the picture, inside the same card,
   in italic at a fixed size — it must not follow the reader
   font slider. Empty alt leaves the frame unchanged. */
class ChapterImageSpan(
    private val bmp: Bitmap,
    private val maxW: Int,
    private val pad: Int,
    private val gap: Int,
    private val radius: Int,
    private val stroke: Int,
    private val bg: Int,
    private val border: Int,
    private val spacingMult: Float,
    private val spacingAdd: Float,
    private val alt: String = "",
    private val captionPx: Float = 0f,
) : ReplacementSpan() {

    private val innerW = (maxW - pad * 2).coerceAtLeast(1)
    private val imgH = (bmp.height * (innerW.toFloat() / bmp.width.coerceAtLeast(1)))
        .toInt().coerceAtLeast(1)
    private val caption = alt.trim()
    private val captionGap = (pad / 2).coerceAtLeast(4)

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        val reportedH = lineHeightFor(visualH(paint), spacingMult, spacingAdd)
        if (fm != null) {
            fm.ascent = -reportedH
            fm.top = -reportedH
            fm.descent = 0
            fm.bottom = 0
        }
        return maxW
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val cap = captionLayout(paint)
        val vis = visualH(paint)
        val box = (bottom - top).coerceAtLeast(vis)
        val originY = top + (box - vis) / 2f
        val card = RectF(
            x + stroke / 2f,
            originY + gap,
            x + maxW - stroke / 2f,
            originY + gap + cardH(cap),
        )
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bg
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(card, radius.toFloat(), radius.toFloat(), fill)

        val img = RectF(
            card.left + pad,
            card.top + pad,
            card.right - pad,
            card.top + pad + imgH,
        )
        val clip = Path().apply {
            addRoundRect(img, radius.toFloat(), radius.toFloat(), Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(bmp, null, img, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()

        if (cap != null) {
            canvas.save()
            canvas.translate(img.left, img.bottom + captionGap)
            cap.draw(canvas)
            canvas.restore()
        }

        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = border
            style = Paint.Style.STROKE
            strokeWidth = stroke.toFloat().coerceAtLeast(1f)
        }
        canvas.drawRoundRect(card, radius.toFloat(), radius.toFloat(), outline)
    }

    private fun cardH(cap: StaticLayout?): Int =
        imgH + pad * 2 + extraForCaption(cap?.height ?: 0, captionGap)

    private fun visualH(paint: Paint) = cardH(captionLayout(paint)) + gap * 2

    private fun captionLayout(paint: Paint): StaticLayout? {
        if (caption.isEmpty()) return null
        val tp = captionPaint(paint, captionPx)
        return StaticLayout.Builder.obtain(caption, 0, caption.length, tp, innerW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()
    }

    companion object {
        /* Fixed caption size in sp. The body paint's textSize follows
           the reader slider and made the screenshot caption as large
           as the chapter text. */
        const val CAPTION_SP = 13f

        /* A positive captionPx wins; 0 keeps the body size. Extracted
           so the unit test can check this without constructing Paint. */
        fun captionSizePx(bodyPx: Float, captionPx: Float): Float =
            if (captionPx > 0f) captionPx else bodyPx

        fun captionPaint(body: Paint, captionPx: Float): TextPaint =
            TextPaint(body).apply {
                typeface = Typeface.create(typeface, Typeface.ITALIC)
                isAntiAlias = true
                textSize = captionSizePx(textSize, captionPx)
            }

        fun lineHeightFor(visualPx: Int, spacingMult: Float, spacingAdd: Float): Int {
            val m = if (spacingMult > 0f) spacingMult else 1f
            return ((visualPx - spacingAdd) / m).roundToInt().coerceAtLeast(1)
        }

        /* Extra card height under the picture. 0 when there is no
           caption, so an empty alt does not change the frame. */
        fun extraForCaption(captionH: Int, gap: Int): Int =
            if (captionH > 0) gap + captionH else 0
    }
}
