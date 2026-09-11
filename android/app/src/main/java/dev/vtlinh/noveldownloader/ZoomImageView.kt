package dev.vtlinh.noveldownloader

import android.content.Context
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.ImageView

/* Full-screen chapter picture. The drawable is fitted with a
   matrix — View scaleX/scaleY left the letterboxed hit box at
   1× and broke pinch after the first zoom. Pinch zooms up to
   5× around the fingers; one finger pans while zoomed. A tap
   at 1× is for the caller (dismiss). A tap while zoomed resets.
   A horizontal swipe at 1× pages.

   GestureDetector is not used. It shares the pointer stream with
   ScaleGestureDetector and ate pinches and slow swipes after the
   matrix rewrite — tap / swipe / pinch are decided here instead. */
class ZoomImageView(ctx: Context) : ImageView(ctx) {

    var onDismissTap: (() -> Unit)? = null
    var onSwipe: ((Int) -> Unit)? = null

    private var userScale = Zoom.MIN
    private var tx = 0f
    private var ty = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var pinched = false
    private var panning = false
    private var tracker: VelocityTracker? = null

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
    ).apply { isQuickScaleEnabled = false }

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
        if (tracker == null) tracker = VelocityTracker.obtain()
        tracker?.addMovement(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                pinched = false
                panning = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> pinched = true
            MotionEvent.ACTION_POINTER_UP -> {
                val i = if (event.actionIndex == 0) 1 else 0
                if (i < event.pointerCount) {
                    lastX = event.getX(i)
                    lastY = event.getY(i)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) {
                    pinched = true
                } else if (Zoom.canPan(userScale) && event.pointerCount == 1) {
                    tx += event.x - lastX
                    ty += event.y - lastY
                    panning = true
                    applyMatrix()
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (!pinched && !panning) {
                    if (Zoom.canPan(userScale)) resetZoom()
                    else pageOrDismiss(event)
                }
                recycleTracker()
            }
            MotionEvent.ACTION_CANCEL -> recycleTracker()
        }
        return true
    }

    private fun pageOrDismiss(event: MotionEvent) {
        tracker?.computeCurrentVelocity(1000)
        val vx = tracker?.xVelocity ?: 0f
        val vc = ViewConfiguration.get(context)
        val minDist = vc.scaledPagingTouchSlop.toFloat() * 2f
        val minSpeed = vc.scaledMinimumFlingVelocity.toFloat()
        val dx = event.x - downX
        val dy = event.y - downY
        val swipe = ChapterImages.swipeDelta(dx, dy, vx, minDist, minSpeed)
            ?: ChapterImages.swipeDelta(dx, dy, dx, minDist, 0f)
        if (swipe != null) onSwipe?.invoke(swipe)
        else onDismissTap?.invoke()
    }

    private fun recycleTracker() {
        tracker?.recycle()
        tracker = null
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
