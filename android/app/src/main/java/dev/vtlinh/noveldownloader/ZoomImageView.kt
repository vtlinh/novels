package dev.vtlinh.noveldownloader

import android.content.Context
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.ImageView

/* Full-screen chapter picture. The drawable is fitted with a
   matrix — View scaleX/scaleY left the letterboxed hit box at
   1× and broke pinch after the first zoom. Pinch is two fingers
   on this view, not ScaleGestureDetector: a Dialog / overlay
   can drop a pointer and the detector then never starts.
   One finger pans while zoomed. A still tap at 1× dismisses.
   A tap while zoomed resets. A horizontal swipe at 1× pages.
   A sloppy move that is not a swipe stays put. */
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
    private var pinchSpan = 0f
    private var pinched = false
    private var panning = false
    private var tracker: VelocityTracker? = null

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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        if (tracker == null) tracker = VelocityTracker.obtain()
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                pinchSpan = 0f
                pinched = false
                panning = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> beginPinch(event)
            MotionEvent.ACTION_POINTER_UP -> {
                pinchSpan = 0f
                val keep = if (event.actionIndex == 0) 1 else 0
                if (keep < event.pointerCount) {
                    lastX = event.getX(keep)
                    lastY = event.getY(keep)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    if (pinchSpan <= 0f) beginPinch(event)
                    else applyPinch(event)
                } else if (Zoom.canPan(userScale)) {
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

    private fun beginPinch(event: MotionEvent) {
        if (event.pointerCount < 2) return
        pinched = true
        pinchSpan = Zoom.span(
            event.getX(0), event.getY(0), event.getX(1), event.getY(1),
        ).coerceAtLeast(1f)
    }

    private fun applyPinch(event: MotionEvent) {
        if (event.pointerCount < 2 || pinchSpan <= 0f) return
        val span = Zoom.span(
            event.getX(0), event.getY(0), event.getX(1), event.getY(1),
        ).coerceAtLeast(1f)
        val focusX = (event.getX(0) + event.getX(1)) / 2f
        val focusY = (event.getY(0) + event.getY(1)) / 2f
        val (next, nx, ny) = Zoom.pinch(
            userScale, tx, ty, span / pinchSpan, focusX, focusY,
        )
        pinchSpan = span
        userScale = next
        tx = nx
        ty = ny
        applyMatrix()
    }

    private fun pageOrDismiss(event: MotionEvent) {
        tracker?.computeCurrentVelocity(1000)
        val vc = ViewConfiguration.get(context)
        when (
            Zoom.lift(
                event.x - downX,
                event.y - downY,
                tracker?.xVelocity ?: 0f,
                vc.scaledTouchSlop.toFloat(),
                vc.scaledPagingTouchSlop.toFloat() * 2f,
                vc.scaledMinimumFlingVelocity.toFloat(),
            )
        ) {
            Zoom.Lift.TAP -> onDismissTap?.invoke()
            Zoom.Lift.NEXT -> onSwipe?.invoke(1)
            Zoom.Lift.PREV -> onSwipe?.invoke(-1)
            Zoom.Lift.NONE -> {}
        }
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
