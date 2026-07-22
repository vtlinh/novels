package dev.vtlinh.noveldownloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/* Foreground service that brings every downloaded novel into line with the
   "Compress my novels" setting: per-chapter gzip when on, plain files when off.
   It reads the target fresh for each novel, so flipping the setting mid-run
   stops the current direction and applies the new one after the current novel
   (kept atomic so nothing is left half-done). A full pass that changes nothing
   means everything matches → done. The "job active" flag is persisted, so a
   run interrupted by an app kill resumes on next start; every per-chapter
   step is idempotent, so resuming never leaves partial or orphaned files. */
class CompressService : Service() {

    companion object {
        /* fresh channel id: importance can't be changed on an existing
           channel, and this one must be MIN so the pass runs invisibly */
        private const val CHANNEL = "storage_bg"
        private const val NOTIF_ID = 3

        val runningFlow = MutableStateFlow(false)

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, CompressService::class.java))
        }

        /* resume an unfinished run on app start */
        fun resumeIfNeeded(ctx: Context) {
            val p = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            if (p.getBoolean("compressJobActive", false) && p.getString("tree", null) != null) {
                start(ctx)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        /* already running → the in-flight pass will pick up the new target */
        if (runningFlow.value) return START_NOT_STICKY

        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        val treeStr = prefs.getString("tree", null)
        if (treeStr == null) {
            prefs.edit().putBoolean("compressJobActive", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        runningFlow.value = true
        prefs.edit().putBoolean("compressJobActive", true).apply()

        scope.launch {
            val cr = contentResolver
            val treeUri = Uri.parse(treeStr)
            try {
                while (true) {
                    val target = prefs.getBoolean("compressNovels", true)
                    var changedAny = false
                    val dirs = try {
                        Saf.children(cr, treeUri, Saf.rootId(treeUri)).filter { it.isDir }
                    } catch (e: Exception) {
                        emptyList()
                    }
                    for (d in dirs) {
                        val cur = prefs.getBoolean("compressNovels", true)
                        val changed = try {
                            if (cur) Zips.compressDir(this@CompressService, cr, treeUri, d)
                            else Zips.uncompressDir(this@CompressService, cr, treeUri, d)
                        } catch (e: Exception) {
                            false
                        }
                        if (changed) changedAny = true
                    }
                    /* refs changed shape (txt <-> gz) → the cached chapter
                       listings are stale across the library */
                    if (changedAny) {
                        try {
                            DownloadStore(this@CompressService).clearAllChapterLists(treeStr)
                        } catch (e: Exception) {}
                    }
                    /* converged: a whole pass changed nothing and the target
                       held steady across it → every novel already matches */
                    if (!changedAny && prefs.getBoolean("compressNovels", true) == target) break
                }
                prefs.edit().putBoolean("compressJobActive", false).apply()
            } catch (e: Exception) {
                /* silent by design — an interrupted pass just resumes on the
                   next app start (resumeIfNeeded) */
                prefs.edit().putBoolean("compressJobActive", false).apply()
            } finally {
                runningFlow.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            /* MIN importance: no status-bar icon, silently collapsed at the
               bottom of the shade — the pass is invisible in normal use */
            NotificationChannel(CHANNEL, "Storage", NotificationManager.IMPORTANCE_MIN),
        )
    }

    /* one static notification for the whole run (a foreground service must
       post one) — no per-novel progress, nothing to watch */
    private fun buildNotification(): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this, 4,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("Optimizing storage")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
