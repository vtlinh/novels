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
   service only holds the foreground notification and a partial wake lock. */
class TtsService : Service() {

    companion object {
        private const val CHANNEL = "tts"
        private const val NOTIF_ID = 2

        fun start(ctx: Context, title: String) {
            ctx.startForegroundService(
                Intent(ctx, TtsService::class.java).putExtra("title", title),
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
        /* tapping the notification brings the existing task (the reader) back */
        val openIntent = PendingIntent.getActivity(
            this, 2,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Reading aloud")
            .setContentText(intent?.getStringExtra("title") ?: "")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
        androidx.core.app.ServiceCompat.startForeground(
            this, NOTIF_ID, notif,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "novels:tts")
                .apply { acquire(4 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wakeLock?.release() } catch (e: Exception) {}
        wakeLock = null
        super.onDestroy()
    }
}
