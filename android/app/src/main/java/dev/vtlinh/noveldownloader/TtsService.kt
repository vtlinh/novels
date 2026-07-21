package dev.vtlinh.noveldownloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/* Keeps the process alive (and the CPU awake between sentences) while the
   reader's TTS is speaking, so reading continues with the screen off or the
   app in the background. All actual TTS work stays in ReaderActivity — this
   service holds the foreground notification (current chapter + play/pause
   action) and a partial wake lock that is only held while speaking. */
class TtsService : Service() {

    companion object {
        private const val CHANNEL = "tts"
        private const val NOTIF_ID = 2

        /* notification action → ReaderActivity's in-app receiver */
        const val ACTION_TOGGLE = "dev.vtlinh.noveldownloader.TTS_TOGGLE"

        fun start(ctx: Context, title: String, speaking: Boolean) {
            ctx.startForegroundService(
                Intent(ctx, TtsService::class.java)
                    .putExtra("title", title)
                    .putExtra("speaking", speaking),
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TtsService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Read aloud", NotificationManager.IMPORTANCE_LOW),
        )
        val speaking = intent?.getBooleanExtra("speaking", true) ?: true
        /* tapping the notification brings the existing task (the reader) back */
        val openIntent = PendingIntent.getActivity(
            this, 2,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleIntent = PendingIntent.getBroadcast(
            this, 3,
            Intent(ACTION_TOGGLE).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(intent?.getStringExtra("title")?.ifEmpty { null } ?: "Reading aloud")
            .setContentText(if (speaking) "Reading aloud" else "Paused")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                if (speaking) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (speaking) "Pause" else "Play",
                toggleIntent,
            )
            .build()
        androidx.core.app.ServiceCompat.startForeground(
            this, NOTIF_ID, notif,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        /* the CPU only needs to stay awake while actually speaking */
        if (speaking) {
            if (wakeLock == null) {
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "novels:tts")
                    .apply { acquire(4 * 60 * 60 * 1000L) }
            }
        } else {
            try { wakeLock?.release() } catch (e: Exception) {}
            wakeLock = null
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wakeLock?.release() } catch (e: Exception) {}
        wakeLock = null
        super.onDestroy()
    }
}
