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
        private const val CHANNEL = "compress"
        private const val NOTIF_ID = 3

        val statusFlow = MutableStateFlow("")
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
    private var lastNotify = 0L

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
        startForeground(NOTIF_ID, buildNotification("Preparing…"))
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
                    for ((i, d) in dirs.withIndex()) {
                        val cur = prefs.getBoolean("compressNovels", true)
                        setStatus(
                            (if (cur) "Compressing" else "Uncompressing") +
                                " ${i + 1}/${dirs.size}: ${d.name}",
                        )
                        val changed = try {
                            if (cur) Zips.compressDir(this@CompressService, cr, treeUri, d)
                            else Zips.uncompressDir(this@CompressService, cr, treeUri, d)
                        } catch (e: Exception) {
                            false
                        }
                        if (changed) changedAny = true
                    }
                    /* converged: a whole pass changed nothing and the target
                       held steady across it → every novel already matches */
                    if (!changedAny && prefs.getBoolean("compressNovels", true) == target) break
                }
                prefs.edit().putBoolean("compressJobActive", false).apply()
                setStatus(
                    if (prefs.getBoolean("compressNovels", true)) "All novels compressed."
                    else "All novels uncompressed.",
                )
            } catch (e: Exception) {
                prefs.edit().putBoolean("compressJobActive", false).apply()
                setStatus("Error: ${e.message}")
            } finally {
                runningFlow.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun setStatus(msg: String) {
        statusFlow.value = msg
        val now = System.currentTimeMillis()
        if (now - lastNotify < 500) return
        lastNotify = now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(msg))
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Compression", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(text: String): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this, 4,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("Storage")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
