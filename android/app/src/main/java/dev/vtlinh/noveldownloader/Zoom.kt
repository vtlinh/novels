package dev.vtlinh.noveldownloader

/* Pinch / pan math for the full-screen chapter picture. 1 is
   fit-center; above that the picture can pan. The image matrix
   is fit.scale * userScale, then tx / ty. Does not wrap past
   the min/max. */
object Zoom {
    const val MIN = 1f
    const val MAX = 5f

    data class Fit(val scale: Float, val tx: Float, val ty: Float)

    fun scale(current: Float, factor: Float, min: Float = MIN, max: Float = MAX): Float =
        (current * factor).coerceIn(min, max)

    fun canPan(scale: Float, min: Float = MIN): Boolean = scale > min

    /* Center the picture in the view at the largest scale that
       still shows every pixel. */
    fun fit(viewW: Float, viewH: Float, imgW: Float, imgH: Float): Fit {
        if (viewW <= 0f || viewH <= 0f || imgW <= 0f || imgH <= 0f) {
            return Fit(1f, 0f, 0f)
        }
        val s = minOf(viewW / imgW, viewH / imgH)
        return Fit(s, (viewW - imgW * s) / 2f, (viewH - imgH * s) / 2f)
    }

    /* Keep the focus point under the fingers when the user scale
       changes. tx / ty are the current translation. */
    fun pinch(
        userScale: Float,
        tx: Float,
        ty: Float,
        factor: Float,
        focusX: Float,
        focusY: Float,
        min: Float = MIN,
        max: Float = MAX,
    ): Triple<Float, Float, Float> {
        val next = scale(userScale, factor, min, max)
        val used = if (userScale == 0f) 1f else next / userScale
        return Triple(
            next,
            focusX - (focusX - tx) * used,
            focusY - (focusY - ty) * used,
        )
    }

    enum class Lift { TAP, NEXT, PREV, NONE }

    /* Finger up at 1×: a still tap, a horizontal page, or neither.
       Movement past the tap slop that is not a swipe must not dismiss. */
    fun lift(
        dx: Float,
        dy: Float,
        vx: Float,
        tapSlop: Float,
        minDist: Float,
        minSpeed: Float,
    ): Lift {
        if (kotlin.math.abs(dx) <= tapSlop && kotlin.math.abs(dy) <= tapSlop) return Lift.TAP
        return when (
            ChapterImages.swipeDelta(dx, dy, vx, minDist, minSpeed)
                ?: ChapterImages.swipeDelta(dx, dy, dx, minDist, 0f)
        ) {
            1 -> Lift.NEXT
            -1 -> Lift.PREV
            else -> Lift.NONE
        }
    }

    fun span(x0: Float, y0: Float, x1: Float, y1: Float): Float =
        kotlin.math.hypot(x1 - x0, y1 - y0)

    /* When the drawn picture is smaller than the view, center it.
       When it is larger, keep every edge from sliding off. */
    fun clamp(
        total: Float,
        tx: Float,
        ty: Float,
        viewW: Float,
        viewH: Float,
        imgW: Float,
        imgH: Float,
    ): Pair<Float, Float> {
        val dw = imgW * total
        val dh = imgH * total
        val nx = if (dw <= viewW) (viewW - dw) / 2f else tx.coerceIn(viewW - dw, 0f)
        val ny = if (dh <= viewH) (viewH - dh) / 2f else ty.coerceIn(viewH - dh, 0f)
        return nx to ny
    }
}
