package dev.vtlinh.noveldownloader

import android.content.Context
import android.graphics.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.ImageView

/* Full-screen chapter picture. The drawable is fitted with a
   matrix — View scaleX/scaleY left the letterboxed hit box at
   1× and broke pinch after the first zoom. Pinch zooms up to
   5× around the fingers; one finger pans while zoomed. A tap
   at 1× is for the caller (dismiss). A tap while zoomed resets.
   A horizontal swipe at 1× pages. */
class ZoomImageView(ctx: Context) : ImageView(ctx) {

    var onDismissTap: (() -> Unit)? = null
    var onSwipe: ((Int) -> Unit)? = null

    private var userScale = Zoom.MIN
    private var tx = 0f
    private var ty = 0f
    private var downX = 0f
    private var downY = 0f
    private var pinched = false
    private var panning = false
    private var paged = false

    private val gestures = GestureDetector(
        ctx,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (pinched || panning || paged) return false
                if (Zoom.canPan(userScale)) resetZoom()
                else onDismissTap?.invoke()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (Zoom.canPan(userScale) || pinched || e1 == null) return false
                val vc = ViewConfiguration.get(context)
                val minDist = vc.scaledPagingTouchSlop.toFloat() * 2f
                val minSpeed = vc.scaledMinimumFlingVelocity.toFloat()
                val swipe = ChapterImages.swipeDelta(
                    e2.x - e1.x, e2.y - e1.y, velocityX, minDist, minSpeed,
                ) ?: return false
                paged = true
                onSwipe?.invoke(swipe)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (!Zoom.canPan(userScale)) return false
                tx -= distanceX
                ty -= distanceY
                panning = true
                applyMatrix()
                return true
            }
        },
    ).apply { setIsLongpressEnabled(false) }

    private val scaleDetector = ScaleGestureDetector(
        ctx,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinched = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val (next, nx, ny) = Zoom.pinch(
                    userScale, tx, ty, detector.scaleFactor,
                    detector.focusX, detector.focusY,
                )
                userScale = next
                tx = nx
                ty = ny
                applyMatrix()
                return true
            }
        },
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        isFocusable = true
    }

    fun canPan(): Boolean = Zoom.canPan(userScale)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (userScale <= Zoom.MIN) resetZoom() else applyMatrix()
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        scaleType = ScaleType.MATRIX
        if (userScale <= Zoom.MIN) resetZoom() else applyMatrix()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
            pinched = false
            panning = false
            paged = false
        }
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
            gestures.onTouchEvent(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP &&
            !pinched && !panning && !paged && !Zoom.canPan(userScale)
        ) {
            val vc = ViewConfiguration.get(context)
            val minDist = vc.scaledPagingTouchSlop.toFloat() * 2f
            val swipe = ChapterImages.swipeDelta(
                event.x - downX, event.y - downY, event.x - downX, minDist, 0f,
            )
            if (swipe != null) {
                paged = true
                onSwipe?.invoke(swipe)
            }
        }
        return true
    }

    fun resetZoom() {
        userScale = Zoom.MIN
        val fit = currentFit()
        tx = fit.tx
        ty = fit.ty
        applyMatrix()
    }

    private fun currentFit(): Zoom.Fit {
        val d = drawable ?: return Zoom.Fit(1f, 0f, 0f)
        return Zoom.fit(
            width.toFloat(), height.toFloat(),
            d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat(),
        )
    }

    private fun applyMatrix() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0) return
        val fit = currentFit()
        val total = fit.scale * userScale
        val clamped = Zoom.clamp(
            total, tx, ty,
            width.toFloat(), height.toFloat(),
            d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat(),
        )
        tx = clamped.first
        ty = clamped.second
        val m = Matrix()
        m.setScale(total, total)
        m.postTranslate(tx, ty)
        imageMatrix = m
    }
}
