package dev.vtlinh.noveldownloader

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.ImageView

/* Full-screen chapter picture. Pinch zooms up to 5×; one finger
   pans while zoomed. A tap at 1× is for the caller (dismiss). A
   tap while zoomed resets. A horizontal swipe at 1× pages. */
class ZoomImageView(ctx: Context) : ImageView(ctx) {

    var onDismissTap: (() -> Unit)? = null
    var onSwipe: ((Int) -> Unit)? = null

    private var scale = Zoom.MIN
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var panning = false
    private var pinched = false
    private var tracker: VelocityTracker? = null

    private val scaleDetector = ScaleGestureDetector(
        ctx,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = Zoom.scale(scale, detector.scaleFactor)
                pivotX = detector.focusX
                pivotY = detector.focusY
                applyScale(next)
                pinched = true
                return true
            }
        },
    )

    init {
        scaleType = ScaleType.FIT_CENTER
        isClickable = true
    }

    fun canPan(): Boolean = Zoom.canPan(scale)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (tracker == null) tracker = VelocityTracker.obtain()
        tracker?.addMovement(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                downX = event.x
                downY = event.y
                panning = false
                pinched = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> pinched = true
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) {
                    pinched = true
                } else if (Zoom.canPan(scale) && event.pointerCount == 1) {
                    translationX += event.rawX - lastX
                    translationY += event.rawY - lastY
                    lastX = event.rawX
                    lastY = event.rawY
                    panning = true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!pinched && !panning) {
                    if (Zoom.canPan(scale)) resetZoom()
                    else {
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
                }
                tracker?.recycle()
                tracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                tracker?.recycle()
                tracker = null
            }
        }
        return true
    }

    fun resetZoom() {
        applyScale(Zoom.MIN)
        translationX = 0f
        translationY = 0f
    }

    private fun applyScale(next: Float) {
        scale = next
        scaleX = next
        scaleY = next
        if (!Zoom.canPan(scale)) {
            translationX = 0f
            translationY = 0f
        }
    }
}
