package dev.vtlinh.noveldownloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat

/* Keeps the process alive (and the CPU awake between sentences) while the
   reader's TTS is speaking, so reading continues with the screen off or the
   app in the background. All actual TTS work stays in ReaderActivity — this
   service holds the foreground notification and a partial wake lock that is
   only held while speaking.

   The notification uses MediaStyle so the play/pause control is centered and
   the media template stays expanded, with the chapter as the only title and
   no status line.

   Headset buttons do not come through here. They reach the reader's media
   session directly; this service once forwarded them as a broadcast, from a
   route that could not fire while there was a reader to act on it — see the
   manifest, where the pairing that made it up has been removed. */
class TtsService : Service() {

    companion object {
        private const val CHANNEL = "tts"
        private const val NOTIF_ID = 2

        /* The notification's Pause/Play button → ReaderActivity's in-app
           receiver. Its only sender, so it carries no argument: it means
           whichever of the two the reader is not doing. */
        const val ACTION_TOGGLE = "dev.vtlinh.noveldownloader.TTS_TOGGLE"

        /* true while a reading session is active — the auto-updater checks
           this so it never kills the process mid-read */
        @Volatile var isRunning = false

        fun start(ctx: Context, title: String, speaking: Boolean, token: MediaSessionCompat.Token?, slug: String?) {
            ctx.startForegroundService(
                Intent(ctx, TtsService::class.java)
                    .putExtra("title", title)
                    .putExtra("speaking", speaking)
                    .putExtra("token", token)
                    .putExtra("slug", slug),
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TtsService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Read aloud", NotificationManager.IMPORTANCE_LOW),
        )

        val speaking = intent?.getBooleanExtra("speaking", true) ?: true
        @Suppress("DEPRECATION")
        val token = intent?.getParcelableExtra<MediaSessionCompat.Token>("token")

        postNotification(intent?.getStringExtra("title"), speaking, token, intent?.getStringExtra("slug"))
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

    private fun postNotification(
        title: String?,
        speaking: Boolean,
        token: MediaSessionCompat.Token?,
        slug: String?,
    ) {
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
        val action = NotificationCompat.Action(
            if (speaking) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (speaking) "Pause" else "Play",
            toggleIntent,
        )

        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0)
        if (token != null) mediaStyle.setMediaSession(token)

        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title?.ifEmpty { null } ?: "Reading aloud")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setStyle(mediaStyle)
            .addAction(action)
        /* the novel cover becomes the media artwork — the system renders it
           as the notification's tinted background; softly blurred here */
        coverBitmap(slug)?.let { builder.setLargeIcon(it) }
        val notif = builder.build()

        androidx.core.app.ServiceCompat.startForeground(
            this, NOTIF_ID, notif,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    /* decode the novel cover and blur it a little (cheap downscale/upscale) */
    private fun coverBitmap(slug: String?): android.graphics.Bitmap? {
        if (slug.isNullOrEmpty()) return null
        val f = DownloadEngine.coverFile(this, slug)
        if (!f.exists()) return null
        return try {
            val src = android.graphics.BitmapFactory.decodeFile(f.path) ?: return null
            val w = (src.width / 4).coerceAtLeast(1)
            val h = (src.height / 4).coerceAtLeast(1)
            val small = android.graphics.Bitmap.createScaledBitmap(src, w, h, true)
            android.graphics.Bitmap.createScaledBitmap(small, src.width, src.height, true)
        } catch (e: Exception) { null }
    }

    override fun onDestroy() {
        isRunning = false
        try { wakeLock?.release() } catch (e: Exception) {}
        wakeLock = null
        super.onDestroy()
    }
}
