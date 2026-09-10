package dev.vtlinh.noveldownloader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.style.ReplacementSpan
import kotlin.math.roundToInt

/* Chapter picture under the heading. A plain ImageSpan is full-bleed
   and picks up the reader's 1.45 line spacing, so a 600px image gets
   ~270px of empty gap. This span draws a padded card and reports a
   line height that the multiplier stretches back to the card size. */
class ChapterImageSpan(
    private val bmp: Bitmap,
    private val maxW: Int,
    private val pad: Int,
    private val gap: Int,
    private val radius: Int,
    private val stroke: Int,
    private val bg: Int,
    private val border: Int,
    spacingMult: Float,
    spacingAdd: Float,
) : ReplacementSpan() {

    private val innerW = (maxW - pad * 2).coerceAtLeast(1)
    private val imgH = (bmp.height * (innerW.toFloat() / bmp.width.coerceAtLeast(1)))
        .toInt().coerceAtLeast(1)
    private val cardH = imgH + pad * 2
    private val visualH = cardH + gap * 2
    private val reportedH = lineHeightFor(visualH, spacingMult, spacingAdd)

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
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
        val box = (bottom - top).coerceAtLeast(visualH)
        val originY = top + (box - visualH) / 2f
        val card = RectF(
            x + stroke / 2f,
            originY + gap,
            x + maxW - stroke / 2f,
            originY + gap + cardH,
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

        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = border
            style = Paint.Style.STROKE
            strokeWidth = stroke.toFloat().coerceAtLeast(1f)
        }
        canvas.drawRoundRect(card, radius.toFloat(), radius.toFloat(), outline)
    }

    companion object {
        fun lineHeightFor(visualPx: Int, spacingMult: Float, spacingAdd: Float): Int {
            val m = if (spacingMult > 0f) spacingMult else 1f
            return ((visualPx - spacingAdd) / m).roundToInt().coerceAtLeast(1)
        }
    }
}
