package dev.vtlinh.noveldownloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/* Foreground service that keeps auto chapter pictures going with the
   screen off or the app in the background: download a png that is
   already on the way, then post the next due chapter, novels last-read
   first. One picture every 5 minutes. Settings or a novel's own
   switch is what turns this on; this service only keeps the
   process alive. */
class ImageService : Service() {

    companion object {
        private const val CHANNEL = "chapter_images"
        private const val NOTIF_ID = 4

        val runningFlow = MutableStateFlow(false)

        fun start(ctx: Context) {
            val app = ctx.applicationContext
            try {
                app.startForegroundService(Intent(app, ImageService::class.java))
            } catch (e: Exception) {
                /* From API 31 a foreground service cannot be started
                   from the background. The next time the app comes
                   forward, App.onStart tries again. */
                DownloadService.appendLog("image: service start refused ${e.message}")
            }
        }

        fun startIfNeeded(ctx: Context) {
            if (runningFlow.value) return
            if (!ChapterImages.hasBackgroundWork(ctx)) return
            start(ctx)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (runningFlow.value) return START_STICKY

        createChannel()
        startForeground(NOTIF_ID, buildNotification("Looking for chapter pictures"))
        runningFlow.value = true

        scope.launch {
            try {
                ChapterImages.runBackground(applicationContext) { text ->
                    notify(text)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DownloadService.appendLog("image: service fail ${e.message}")
            } finally {
                runningFlow.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelChildren()
        runningFlow.value = false
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Chapter pictures", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notify(text: String) {
        if (!runningFlow.value) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this, 5,
            Intent(this, NovelListActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Chapter pictures")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
