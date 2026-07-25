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
   only held while speaking. The notification uses MediaStyle so the
   play/pause control is centered and the media template stays expanded, with
   the chapter as the only title and no status line. */
class TtsService : Service() {

    companion object {
        private const val CHANNEL = "tts"
        private const val NOTIF_ID = 2

        /* notification action → ReaderActivity's in-app receiver */
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

    /* the last notification we posted, so a media-button delivery (which
       carries none of these extras) can re-post the SAME notification instead
       of clobbering the chapter title and play/pause state */
    private var lastTitle: String? = null
    private var lastSpeaking = true
    private var lastToken: MediaSessionCompat.Token? = null
    private var lastSlug: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Read aloud", NotificationManager.IMPORTANCE_LOW),
        )

        /* A headset/Bluetooth button arrives here (MediaButtonReceiver forwards
           it to the service advertising ACTION_MEDIA_BUTTON). We were started
           with startForegroundService, so we must post a notification right
           away — re-post the current one — then act on the key. */
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            postNotification(lastTitle, lastSpeaking, lastToken, lastSlug)
            handleMediaButton(intent)
            return START_NOT_STICKY
        }

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

    /* Translate a media key into the reader's toggle broadcast. PLAY and PAUSE
       are explicit so an earbud's dedicated key can't do the opposite of what
       it says; PLAY_PAUSE and the single-button HEADSETHOOK toggle. */
    private fun handleMediaButton(intent: Intent) {
        @Suppress("DEPRECATION")
        val ev = intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        /* one dispatch per press: ignore the UP half and auto-repeats */
        if (ev.action != android.view.KeyEvent.ACTION_DOWN || ev.repeatCount > 0) return
        val want = when (ev.keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> "play"
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
            android.view.KeyEvent.KEYCODE_MEDIA_STOP,
            -> "pause"
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            android.view.KeyEvent.KEYCODE_HEADSETHOOK,
            -> "toggle"
            else -> return
        }
        sendBroadcast(
            Intent(ACTION_TOGGLE).setPackage(packageName).putExtra("want", want),
        )
    }

    private fun postNotification(
        title: String?,
        speaking: Boolean,
        token: MediaSessionCompat.Token?,
        slug: String?,
    ) {
        lastTitle = title
        lastSpeaking = speaking
        lastToken = token
        lastSlug = slug
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
