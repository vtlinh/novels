package dev.vtlinh.noveldownloader

import android.annotation.SuppressLint
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/* Compact console that replaced the Home screen log: a 3-line footer while
   messages arrive, expandable to a full-screen sheet, dismissible with a
   downward swipe. New lines after a dismiss bring the footer back. */
object ConsoleFooter {

    /* How many log lines were present when the user last swiped the footer
       away. While the log stays at or below this count the footer stays
       hidden; growth (or a wipe that shrinks then grows again) re-shows it. */
    @Volatile private var dismissedAtCount = 0

    fun attach(activity: AppCompatActivity, footer: View) {
        val text = footer.findViewById<TextView>(R.id.consoleFooterText)
        var sheet: BottomSheetDialog? = null

        fun showFooter(lines: List<String>) {
            text.text = lines.takeLast(3).joinToString("\n")
            footer.visibility = View.VISIBLE
            footer.translationY = 0f
            footer.alpha = 1f
        }

        fun hideFooter() {
            footer.visibility = View.GONE
            footer.translationY = 0f
            footer.alpha = 1f
        }

        fun applyLog(lines: List<String>) {
            if (lines.size < dismissedAtCount) dismissedAtCount = 0
            if (lines.isEmpty() || lines.size <= dismissedAtCount) {
                hideFooter()
            } else {
                showFooter(lines)
            }
            sheet?.let { if (it.isShowing) refreshSheet(it) }
        }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadService.logFlow.collectLatest { applyLog(it) }
            }
        }

        enableSwipeOrTap(
            footer,
            onTap = {
                if (footer.visibility != View.VISIBLE) return@enableSwipeOrTap
                sheet?.dismiss()
                sheet = openSheet(activity).also { it.show() }
            },
            onDismiss = {
                dismissedAtCount = DownloadService.logFlow.value.size
                hideFooter()
            },
        )
    }

    private fun openSheet(activity: AppCompatActivity): BottomSheetDialog {
        val dialog = BottomSheetDialog(activity)
        val root = activity.layoutInflater.inflate(R.layout.console_sheet, null)
        dialog.setContentView(root)
        dialog.setOnShowListener {
            val bottom = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet,
            ) ?: return@setOnShowListener
            bottom.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
            val behavior = BottomSheetBehavior.from(bottom)
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        refreshSheet(dialog)
        val stop = root.findViewById<Button>(R.id.consoleStopBtn)
        stop.setOnClickListener {
            stop.text = "Stopping…"
            activity.startService(
                Intent(activity, DownloadService::class.java)
                    .setAction(DownloadService.ACTION_STOP),
            )
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadService.runningFlow.collectLatest { r ->
                    if (!dialog.isShowing) return@collectLatest
                    stop.visibility = if (r) View.VISIBLE else View.GONE
                    if (r) stop.text = "Stop"
                }
            }
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadService.statusFlow.collectLatest { s ->
                    if (!dialog.isShowing) return@collectLatest
                    root.findViewById<TextView>(R.id.consoleSheetStatus).text = s
                }
            }
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadService.logFlow.collectLatest {
                    if (!dialog.isShowing) return@collectLatest
                    refreshSheet(dialog)
                }
            }
        }
        return dialog
    }

    private fun refreshSheet(dialog: BottomSheetDialog) {
        val log = dialog.findViewById<TextView>(R.id.consoleSheetLog) ?: return
        val scroll = dialog.findViewById<ScrollView>(R.id.consoleSheetScroll)
        val status = dialog.findViewById<TextView>(R.id.consoleSheetStatus)
        val stop = dialog.findViewById<Button>(R.id.consoleStopBtn)
        log.text = DownloadService.logFlow.value.joinToString("\n")
        status?.text = DownloadService.statusFlow.value
        stop?.visibility =
            if (DownloadService.runningFlow.value) View.VISIBLE else View.GONE
        scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableSwipeOrTap(footer: View, onTap: () -> Unit, onDismiss: () -> Unit) {
        var downY = 0f
        var dragging = false
        val slop = 24f * footer.resources.displayMetrics.density
        footer.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downY
                    if (dy > slop) {
                        dragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        v.translationY = dy
                        v.alpha = (1f - dy / (v.height * 2f + 1f)).coerceIn(0.2f, 1f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val dy = ev.rawY - downY
                        if (dy > v.height * 0.45f || dy > slop * 3) {
                            onDismiss()
                        } else {
                            v.animate().translationY(0f).alpha(1f).setDuration(120).start()
                        }
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        onTap()
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }
}
