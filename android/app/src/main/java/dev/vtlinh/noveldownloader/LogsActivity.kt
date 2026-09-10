package dev.vtlinh.noveldownloader

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/* Download, status-check, and Slack image lines. The library no longer
   prints them inline — open this screen from the drawer. */
class LogsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)
        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }

        val text = findViewById<TextView>(R.id.logsText)
        val scroll = findViewById<ScrollView>(R.id.logsScroll)
        val status = findViewById<TextView>(R.id.logsStatus)
        val stop = findViewById<Button>(R.id.logsStopBtn)

        stop.setOnClickListener {
            stop.text = "Stopping…"
            startService(
                Intent(this, DownloadService::class.java)
                    .setAction(DownloadService.ACTION_STOP),
            )
        }

        fun render(lines: List<String>) {
            text.text = if (lines.isEmpty()) {
                "Nothing logged yet."
            } else {
                lines.joinToString("\n")
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }

        render(DownloadService.logFlow.value)
        status.text = DownloadService.statusFlow.value
        stop.visibility =
            if (DownloadService.runningFlow.value) View.VISIBLE else View.GONE

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    DownloadService.logFlow.collectLatest { render(it) }
                }
                launch {
                    DownloadService.statusFlow.collectLatest { status.text = it }
                }
                launch {
                    DownloadService.runningFlow.collectLatest { running ->
                        stop.visibility = if (running) View.VISIBLE else View.GONE
                        if (running) stop.text = "Stop"
                    }
                }
            }
        }
    }
}
