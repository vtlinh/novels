package dev.vtlinh.noveldownloader

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/* Self-update against the fixed "android-latest" GitHub release: CI uploads
   the APK plus a version.json carrying the build's versionCode. On app start
   and each return to the foreground the two are compared; if the release is
   newer the APK is downloaded and committed through a PackageInstaller
   session with USER_ACTION_NOT_REQUIRED. Once this app is the installer of
   record of itself (true from the first self-performed update on), Android
   12+ installs the update silently — before that the system shows its
   one-tap confirmation (via InstallReceiver). Every CI build is signed with
   the same committed key, so updates always install over the existing app. */
object Updater {
    private const val BASE = "https://github.com/vtlinh/novels/releases/download/android-latest"
    private const val VERSION_URL = "$BASE/version.json"
    private const val APK_URL = "$BASE/app-release.apk"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile private var lastAutoCheck = 0L

    /* Runs whenever the app is brought to the foreground (from ANY screen —
       ProcessLifecycleOwner), throttled to once a minute. Downloads and
       installs a newer build, but never while a download or a reading
       session is running, so it can't interrupt them. The system may still
       show its one-tap confirmation until this app is its own installer of
       record. */
    fun autoCheck(context: Context, scope: kotlinx.coroutines.CoroutineScope) {
        val now = System.currentTimeMillis()
        if (now - lastAutoCheck < 60_000) return
        lastAutoCheck = now
        if (DownloadService.runningFlow.value || TtsService.isRunning) return
        scope.launch {
            val latest = latestVersion() ?: return@launch
            if (latest.first <= currentVersionCode(context)) return@launch
            /* no install permission → don't download or trip the OS block;
               the About screen guides the user to grant it */
            if (!canInstall(context)) return@launch
            /* re-check the guards after the network round-trip */
            if (DownloadService.runningFlow.value || TtsService.isRunning) return@launch
            val apk = ensureApk(context, latest.first) ?: return@launch
            try {
                abandonSessions(context)
                install(context, apk)
            } catch (e: Exception) {}
        }
    }

    /* Android requires a per-app "Install unknown apps" grant before an app
       can install packages. Without it, PackageInstaller is blocked with
       "Prevented … from installing another app". */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /* deep-link straight to this app's "Install unknown apps" toggle */
    fun openInstallPermission(context: Context) {
        try {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            try {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e2: Exception) {}
        }
    }

    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info)
    }

    suspend fun latestVersion(): Pair<Long, String>? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(VERSION_URL).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val body = r.body?.string() ?: return@withContext null
                val code = Regex("\"versionCode\"\\s*:\\s*(\\d+)")
                    .find(body)?.groupValues?.get(1)?.toLongOrNull() ?: return@withContext null
                val name = Regex("\"versionName\"\\s*:\\s*\"([^\"]+)\"")
                    .find(body)?.groupValues?.get(1) ?: code.toString()
                Pair(code, name)
            }
        } catch (e: Exception) {
            null
        }
    }

    private const val APK_NAME = "update.apk"
    private const val CACHED_VERSION_KEY = "cachedApkVersion"

    private fun apkFile(context: Context) = File(context.cacheDir, APK_NAME)
    private fun prefs(context: Context) =
        context.getSharedPreferences("app", Context.MODE_PRIVATE)

    /* Return a ready-to-install APK for `versionCode`, downloading it ONLY if
       we don't already have that exact version cached. Every install (and every
       retry, across process restarts) reuses this single file until a newer
       version supersedes it. */
    suspend fun ensureApk(context: Context, versionCode: Long): File? = withContext(Dispatchers.IO) {
        val f = apkFile(context)
        if (f.exists() && f.length() > 0 &&
            prefs(context).getLong(CACHED_VERSION_KEY, -1L) == versionCode
        ) {
            return@withContext f   // already downloaded this version
        }
        /* download to a .part file and rename on completion, so a kill
           mid-transfer can never leave a truncated file sitting where a
           complete APK belongs */
        val part = File(context.cacheDir, "$APK_NAME.part")
        try {
            prefs(context).edit().remove(CACHED_VERSION_KEY).apply()
            client.newCall(Request.Builder().url(APK_URL).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val stream = r.body?.byteStream() ?: return@withContext null
                stream.use { input -> part.outputStream().use { out -> input.copyTo(out) } }
            }
            if (part.length() <= 0L) return@withContext null
            try { f.delete() } catch (e: Exception) {}
            if (!part.renameTo(f)) {
                part.copyTo(f, overwrite = true)
                part.delete()
            }
            prefs(context).edit().putLong(CACHED_VERSION_KEY, versionCode).apply()
            f
        } catch (e: Exception) {
            /* a partial download is useless — drop it so we start clean */
            try { part.delete() } catch (e2: Exception) {}
            try { f.delete() } catch (e2: Exception) {}
            prefs(context).edit().remove(CACHED_VERSION_KEY).apply()
            null
        }
    }

    /* delete the cached APK and its version marker */
    fun cleanupApk(context: Context) {
        try { apkFile(context).delete() } catch (e: Exception) {}
        prefs(context).edit().remove(CACHED_VERSION_KEY).apply()
    }

    /* Called on app start: if the running version is already at (or past) the
       cached APK's version, the update went through (or is moot) — clean it up.
       Otherwise the cached APK is kept for a retry. */
    fun cleanupIfInstalled(context: Context) {
        val cached = prefs(context).getLong(CACHED_VERSION_KEY, -1L)
        if (cached >= 0 && cached <= currentVersionCode(context)) cleanupApk(context)
    }

    /* Abandon any leftover PackageInstaller sessions for our own updates.
       A stalled "Installing…" leaves a committed session that never resolves;
       clearing it lets a fresh commit start clean (and stops sessions piling
       up across retries). */
    fun abandonSessions(context: Context) {
        try {
            val pi = context.packageManager.packageInstaller
            for (s in pi.mySessions) {
                try { pi.abandonSession(s.sessionId) } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }

    /* Commit the update through PackageInstaller. Silent when permitted
       (Android 12+, this app is its own installer of record); otherwise the
       system posts its confirmation UI via InstallReceiver. The process is
       killed by the system as the update applies. */
    fun install(context: Context, apk: File) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        /* Android 14+ only honors USER_ACTION_NOT_REQUIRED silently when this
           app is the package's registered "update owner". Claim it here so
           that from the first ownership-claiming update onward, every future
           self-update installs with no banner tap. (That very first claim
           still shows one confirmation; there's no way around the initial
           handshake.) */
        if (Build.VERSION.SDK_INT >= 34) {
            params.setRequestUpdateOwnership(true)
        }
        val sessionId = pi.createSession(params)
        pi.openSession(sessionId).use { session ->
            session.openWrite("app.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                context, 0, Intent(context, InstallReceiver::class.java), flags,
            )
            session.commit(pending.intentSender)
        }
    }
}
