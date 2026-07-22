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
   or the app backgrounded, with live progress in a system notification.
   Starting another novel while one is downloading QUEUES it (persisted, so a
   queue survives app restarts); each finished novel starts the next in line.
   Stop cancels the current download AND clears the queue. */
class DownloadService : Service() {

    companion object {
        const val ACTION_STOP = "dev.vtlinh.noveldownloader.STOP"
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1
        private const val QUEUE_KEY = "downloadQueue"

        val statusFlow = MutableStateFlow("")
        val logFlow = MutableStateFlow<List<String>>(emptyList())
        val runningFlow = MutableStateFlow(false)

        @Volatile var engine: DownloadEngine? = null
        @Volatile private var currentUrl: String? = null

        private fun prefs(ctx: android.content.Context) =
            ctx.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)

        private fun queueArr(ctx: android.content.Context): org.json.JSONArray =
            try { org.json.JSONArray(prefs(ctx).getString(QUEUE_KEY, "[]")) } catch (e: Exception) { org.json.JSONArray() }

        fun queueSize(ctx: android.content.Context): Int = queueArr(ctx).length()

        /* add a request to the back of the queue; false if that novel is
           already downloading or already waiting */
        private fun enqueue(
            ctx: android.content.Context,
            url: String,
            translate: Boolean,
            force: Boolean,
        ): Boolean {
            if (url == currentUrl) return false
            val arr = queueArr(ctx)
            for (i in 0 until arr.length()) {
                if (arr.optJSONObject(i)?.optString("url") == url) return false
            }
            arr.put(
                org.json.JSONObject().put("url", url)
                    .put("translate", translate).put("force", force),
            )
            prefs(ctx).edit().putString(QUEUE_KEY, arr.toString()).apply()
            return true
        }

        private fun popQueue(ctx: android.content.Context): org.json.JSONObject? {
            val arr = queueArr(ctx)
            if (arr.length() == 0) return null
            val first = arr.optJSONObject(0) ?: return null
            val rest = org.json.JSONArray()
            for (i in 1 until arr.length()) rest.put(arr.getJSONObject(i))
            prefs(ctx).edit().putString(QUEUE_KEY, rest.toString()).apply()
            return first
        }

        private fun clearQueue(ctx: android.content.Context) {
            prefs(ctx).edit().remove(QUEUE_KEY).apply()
        }

        /* app came to the foreground with queued novels but no live download
           (e.g. the process died between queue items) → start the next one */
        fun resumeQueueIfNeeded(ctx: android.content.Context) {
            if (runningFlow.value) return
            val p = prefs(ctx)
            val tree = p.getString("tree", null) ?: return
            val next = popQueue(ctx) ?: return
            ctx.startForegroundService(
                Intent(ctx, DownloadService::class.java)
                    .putExtra("url", next.optString("url"))
                    .putExtra("tree", tree)
                    .putExtra("translate", next.optBoolean("translate"))
                    .putExtra("forceTranslate", next.optBoolean("force"))
                    .putExtra("apiKey", p.getString("apiKey", "") ?: ""),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            clearQueue(this)   // Stop means everything, not just the current novel
            engine?.stop()
            statusFlow.value = "Stopping…"
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra("url")
        val tree = intent?.getStringExtra("tree")
        val translate = intent?.getBooleanExtra("translate", false) ?: false
        val forceTranslate = intent?.getBooleanExtra("forceTranslate", false) ?: false
        val apiKey = intent?.getStringExtra("apiKey") ?: ""
        if (url == null || tree == null) return START_NOT_STICKY

        /* a download is already running → line this novel up next */
        if (runningFlow.value) {
            if (enqueue(this, url, translate, forceTranslate)) {
                logFlow.value = (logFlow.value + "Queued next: $url").takeLast(400)
                maybeNotify(statusFlow.value, 0, 0)   // refresh the "+N queued" hint
            }
            return START_NOT_STICKY
        }

        createChannel()
        startForeground(NOTIF_ID, buildNotification("Starting…", 0, 0))
        runningFlow.value = true
        logFlow.value = emptyList()

        scope.launch {
            var curUrl = url
            var curTranslate = translate
            var curForce = forceTranslate
            var curKey = apiKey
            while (true) {
                currentUrl = curUrl
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
                try {
                    eng.run(curUrl, Uri.parse(tree), curTranslate, curKey, curForce)
                } catch (e: Exception) {
                    logFlow.value = logFlow.value + "ERROR: ${e.message}"
                    statusFlow.value = "Error: ${e.message}"
                }
                /* next in line — unless the user pressed Stop (which also
                   cleared the queue) */
                val next = if (eng.stopRequested) null else popQueue(applicationContext)
                if (next == null) break
                val p = prefs(applicationContext)
                curUrl = next.optString("url")
                curTranslate = next.optBoolean("translate")
                curForce = next.optBoolean("force")
                curKey = p.getString("apiKey", "") ?: ""
                logFlow.value = (logFlow.value + "— next novel: $curUrl —").takeLast(400)
            }
            currentUrl = null
            runningFlow.value = false
            engine = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
        val queued = queueSize(this)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Novel Downloader")
            .setContentText(if (queued > 0) "$text  (+$queued queued)" else text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopIntent)
        if (total > 0) b.setProgress(total, done, false)
        return b.build()
    }
}
