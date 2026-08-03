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
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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

        /* Which novels the service is working on, as normalized slug keys:
           the one downloading now and the ones waiting behind it. The library
           and the browser watch these to label their buttons and to refuse a
           second job for a novel that's already spoken for. */
        val activeSlugFlow = MutableStateFlow<String?>(null)
        val queuedSlugsFlow = MutableStateFlow<Set<String>>(emptySet())

        fun isActive(slugKey: String) =
            slugKey.isNotEmpty() && activeSlugFlow.value == slugKey

        fun isQueued(slugKey: String) =
            slugKey.isNotEmpty() && slugKey in queuedSlugsFlow.value

        fun isBusy(slugKey: String) = isActive(slugKey) || isQueued(slugKey)

        @Volatile var engine: DownloadEngine? = null
        @Volatile private var currentUrl: String? = null

        /* Counts download runs, so a run that is unwinding can tell whether it
           still owns the shared flags above. A cancelled run does not stop at
           once — the engine can sit in a socket read until it times out — and
           by then a newer run may have taken over. */
        private val runSeq = java.util.concurrent.atomic.AtomicLong(0)

        private fun prefs(ctx: android.content.Context) =
            ctx.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)

        private fun queueArr(ctx: android.content.Context): org.json.JSONArray =
            try { org.json.JSONArray(prefs(ctx).getString(QUEUE_KEY, "[]")) } catch (e: Exception) { org.json.JSONArray() }

        fun queueSize(ctx: android.content.Context): Int = queueArr(ctx).length()

        /* mirror the persisted queue into queuedSlugsFlow — called after
           every change to it, and on startup so a queue that outlived the
           process is reflected too */
        private fun publishQueue(ctx: android.content.Context) {
            val arr = queueArr(ctx)
            val out = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val u = arr.optJSONObject(i)?.optString("url") ?: continue
                if (u.isNotEmpty()) out.add(NovelListActivity.slugKeyFromUrl(u))
            }
            queuedSlugsFlow.value = out
        }

        /* The queue lives in prefs and is read-modify-written from two
           threads: enqueue on the main thread from onStartCommand, popQueue
           on IO from the download loop. Interleaved, one write lands on top
           of the other — a novel left sitting in the queue with nothing
           running (stuck on "Queued" until the app is backgrounded and
           reopened), or an already-popped one resurrected and downloaded
           twice. Every touch of the queue goes through this. */
        private val queueLock = Any()

        /* Assigning a StateFlow's value is atomic; reading it, appending, and
           assigning back is not — and this is called from up to fifty fetch
           coroutines at once, plus three at a time from the status sweep. Lost
           lines are only cosmetic, but they are lost precisely when the log is
           busiest, which is when it is being read. */
        fun appendLog(line: String) {
            logFlow.update { (it + line).takeLast(400) }
        }

        /* add a request to the back of the queue; false if that novel is
           already downloading or already waiting */
        private fun enqueue(
            ctx: android.content.Context,
            url: String,
            translate: Boolean,
            force: Boolean,
        ): Boolean = synchronized(queueLock) {
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
            publishQueue(ctx)
            return true
        }

        private fun popQueue(ctx: android.content.Context): org.json.JSONObject? = synchronized(queueLock) {
            val arr = queueArr(ctx)
            if (arr.length() == 0) return null
            val first = arr.optJSONObject(0) ?: return null
            val rest = org.json.JSONArray()
            for (i in 1 until arr.length()) rest.put(arr.getJSONObject(i))
            prefs(ctx).edit().putString(QUEUE_KEY, rest.toString()).apply()
            publishQueue(ctx)
            return first
        }

        /* back to the FRONT — it was next in line and nothing has run since */
        private fun unpopQueue(ctx: android.content.Context, item: org.json.JSONObject) =
            synchronized(queueLock) {
                val arr = queueArr(ctx)
                val out = org.json.JSONArray()
                out.put(item)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.put(it) }
                prefs(ctx).edit().putString(QUEUE_KEY, out.toString()).apply()
                publishQueue(ctx)
            }

        private fun clearQueue(ctx: android.content.Context) = synchronized(queueLock) {
            prefs(ctx).edit().remove(QUEUE_KEY).apply()
            publishQueue(ctx)
        }

        /* app came to the foreground with queued novels but no live download
           (e.g. the process died between queue items) → start the next one */
        fun resumeQueueIfNeeded(ctx: android.content.Context) {
            publishQueue(ctx)   // reflect a queue that outlived the process
            if (runningFlow.value) return
            val p = prefs(ctx)
            val tree = p.getString("tree", null) ?: return
            val next = popQueue(ctx) ?: return
            try {
                ctx.startForegroundService(
                    Intent(ctx, DownloadService::class.java)
                        .putExtra("url", next.optString("url"))
                        .putExtra("tree", tree)
                        .putExtra("translate", next.optBoolean("translate"))
                        .putExtra("forceTranslate", next.optBoolean("force"))
                        .putExtra("apiKey", p.getString("apiKey", "") ?: ""),
                )
            } catch (e: Exception) {
                /* Popped, then the start refused — put it back. From API 31 a
                   foreground service cannot be started from the background,
                   and this runs from the teardown of a service the system is
                   destroying: the throw was swallowed by the caller and the
                   novel was simply gone, out of the persisted queue and out
                   of the Library's "Queued" state, with nothing that would
                   ever pick it up again. The queue is the only record. */
                unpopQueue(ctx, next)
                throw e
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastNotify = 0L

    /* Does THIS novel want translating, whatever the app-wide switch says?

       Resolved here rather than at each screen that starts a download: there
       are four of them plus the persisted queue, which carries the switch's
       value from whenever the novel was queued, and a per-novel setting only
       some of them consulted would be a setting that worked or not depending
       on where you tapped Download. */
    private fun translateFor(tree: String, url: String, appWide: Boolean): Boolean = try {
        val slug = Sites.forUrl(url)?.normalize(url)?.second
        if (slug == null) appWide
        else DownloadStore(applicationContext).translateFor(tree, slug, appWide)
    } catch (e: Exception) { appWide }

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
                appendLog("Queued next: $url")
                maybeNotify(statusFlow.value, 0, 0)   // refresh the "+N queued" hint
            }
            return START_NOT_STICKY
        }

        createChannel()
        startForeground(NOTIF_ID, buildNotification("Starting…", 0, 0))
        runningFlow.value = true
        logFlow.value = emptyList()

        /* non-null copies for the coroutine — the null-check smart cast on
           url/tree doesn't carry into the lambda */
        val firstUrl: String = url
        val treePath: String = tree
        /* Which run this is. The flags below are static, and a cancelled run
           does not stop instantly — the engine can be parked in a socket read
           for up to the 30s timeout. By the time it unwinds, a NEW service
           instance may have started the next novel, and clearing the flags
           then would strand that one: the Library stops showing it as
           downloading, Stop hits a null engine so it cannot be cancelled, and
           the next start runs a second engine over the same folder instead of
           queueing. Only the newest run may clear them. */
        val myRun = runSeq.incrementAndGet()
        scope.launch {
            var curUrl = firstUrl
            var curTranslate = translate
            var curForce = forceTranslate
            var curKey = apiKey
            try {
            while (true) {
                currentUrl = curUrl
                activeSlugFlow.value = NovelListActivity.slugKeyFromUrl(curUrl)
                val eng = DownloadEngine(
                    applicationContext,
                    log = { line -> appendLog(line) },
                    status = { s ->
                        statusFlow.value = s
                        maybeNotify(s, 0, 0)
                    },
                    progress = { done, total -> maybeNotify(statusFlow.value, done, total) },
                )
                engine = eng
                try {
                    eng.run(curUrl, Uri.parse(treePath), translateFor(treePath, curUrl, curTranslate), curKey, curForce)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    /* The service is going away. Cancellation is an Exception,
                       so the catch below would swallow it and the loop would
                       carry straight on popping the queue — every novel in it
                       failing instantly against the dead job, which drained
                       the whole persisted queue in a burst of ERROR lines.
                       Leave the queue alone; the next foreground resumes it. */
                    throw e
                } catch (e: Exception) {
                    appendLog("ERROR: ${e.message}")
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
                appendLog("— next novel: $curUrl —")
            }
            } finally {
                /* Must run even when the loop leaves by cancellation. Skipping
                   it left activeSlugFlow pinned to the interrupted novel, and
                   that flag is what the whole app reads as "this one is
                   already being downloaded" — so the Library showed it stuck
                   on "Downloading…", the Browser refused to start it, and the
                   status sweep skipped it, until some OTHER download happened
                   to reassign the flag. The novel you were downloading became
                   the one novel you could not download.

                   ...but only if we are still the current run. A cancelled
                   run can take until the socket timeout to unwind, by which
                   point a newer one may own these. */
                if (runSeq.get() == myRun) {
                    currentUrl = null
                    activeSlugFlow.value = null
                    runningFlow.value = false
                    engine = null
                    /* Drop OUR foreground state here, before handing off.
                       Doing it after resumeQueueIfNeeded meant the next run
                       could already have called startForeground — and this
                       then removed ITS notification and dropped the service
                       out of the foreground, leaving a live download running
                       unprotected with no Stop action, which stopSelfResult
                       does not undo. */
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    /* Anything queued while we were winding down was accepted
                       on the strength of runningFlow, which stays true from
                       the moment Stop is pressed until the engine unwinds —
                       but a stopped run refuses to pop the queue, so that
                       novel sat there with nothing running: its button stuck
                       on "Queued", no Stop button, and only backgrounding the
                       app would ever drain it. Kick it off ourselves. */
                    try { resumeQueueIfNeeded(applicationContext) } catch (e: Exception) {}
                }
            }
            /* Pass the start id. A plain stopSelf() tears the service down
               even if a new download arrived while we were finishing — that
               one would clear `engine` (killing its Stop button), take away
               its notification, and leave it running unprotected in a service
               the system has already destroyed. With the id, a later start
               cancels the stop. */
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    /* the scope outlives onStartCommand, so a destroyed service must not
       leave a download running with nothing tracking it */
    override fun onDestroy() {
        super.onDestroy()
        /* Unconditional. Guarding on !runningFlow.value cancelled only when
           the loop had already finished and there was nothing left to cancel,
           skipping the exact case worth handling: destroyed while a download
           is live, which leaves it fetching with no foreground protection and
           no way for the user to stop it. On a normal finish the children are
           already done, so this is a no-op there. */
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelChildren()
        runningFlow.value = false
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun maybeNotify(text: String, done: Int, total: Int) {
        /* A chapter fetch is a blocking call, so progress keeps being reported
           for up to a read timeout after the service is gone — and posting
           then re-created the notification AFTER stopForeground removed it.
           That copy is ongoing, so it cannot be swiped away, and its Stop
           button reaches a null engine and does nothing: an undismissable
           progress bar for a download nothing is running. */
        if (!runningFlow.value) return
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
