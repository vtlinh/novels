package dev.vtlinh.noveldownloader

import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/* Asks Slack for chapter pictures in this process: download a png
   that is already on the way, then post the next due chapter,
   novels last-read first. One picture every 15 minutes.

   It does not post a notification. While the app is open the
   process is already alive; once the screen is off, the read-aloud
   notification is what keeps it that way. Settings or a novel's
   own switch is what turns this on. */
object ImageService {
    /* Old foreground-service channel. Deleted on start so an
       existing install loses the leftover Chapter pictures
       notification and the channel in system settings. */
    const val LEGACY_CHANNEL = "chapter_images"
    const val LEGACY_NOTIF_ID = 4

    val runningFlow = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        dropLegacyNotification(app)
        synchronized(lock) {
            if (runningFlow.value) return
            runningFlow.value = true
        }
        scope.launch {
            try {
                ChapterImages.runBackground(app)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                DownloadService.appendLog("image: picture work stopped (${e.message})")
            } finally {
                runningFlow.value = false
            }
        }
    }

    fun startIfNeeded(ctx: Context) {
        if (runningFlow.value) return
        if (!ChapterImages.hasBackgroundWork(ctx)) return
        start(ctx)
    }

    fun dropLegacyNotification(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.cancel(LEGACY_NOTIF_ID)
            nm.deleteNotificationChannel(LEGACY_CHANNEL)
        } catch (e: Exception) {}
    }
}
