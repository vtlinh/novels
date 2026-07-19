package dev.vtlinh.noveldownloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/* Foreground service hosting the download: keeps running with the screen off
   or the app backgrounded, with live progress in a system notification. */
class DownloadService : Service() {

    companion object {
        const val ACTION_STOP = "dev.vtlinh.noveldownloader.STOP"
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1

        val statusFlow = MutableStateFlow("")
        val logFlow = MutableStateFlow<List<String>>(emptyList())
        val runningFlow = MutableStateFlow(false)

        @Volatile var engine: DownloadEngine? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            engine?.stop()
            statusFlow.value = "Stopping…"
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra("url")
        val tree = intent?.getStringExtra("tree")
        val translate = intent?.getBooleanExtra("translate", false) ?: false
        val apiKey = intent?.getStringExtra("apiKey") ?: ""
        if (url == null || tree == null || runningFlow.value) return START_NOT_STICKY

        createChannel()
        startForeground(NOTIF_ID, buildNotification("Starting…", 0, 0))
        runningFlow.value = true
        logFlow.value = emptyList()

        val eng = DownloadEngine(
            applicationContext,
            log = { line -> logFlow.value = (logFlow.value + line).takeLast(400) },
            status = { s ->
                statusFlow.value = s
                maybeNotify(s, 0, 0)
            },
            progress = { done, total -> maybeNotify(statusFlow.value, done, total) },
        )
        engine = eng
        scope.launch {
            try {
                eng.run(url, Uri.parse(tree), translate, apiKey)
            } catch (e: Exception) {
                logFlow.value = logFlow.value + "ERROR: ${e.message}"
                statusFlow.value = "Error: ${e.message}"
            } finally {
                runningFlow.value = false
                engine = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun maybeNotify(text: String, done: Int, total: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 500) return
        lastNotify = now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text, done, total))
    }

    private fun buildNotification(text: String, done: Int, total: Int): android.app.Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, DownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Novel Downloader")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopIntent)
        if (total > 0) b.setProgress(total, done, false)
        return b.build()
    }
}
