package dev.vtlinh.noveldownloader

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/* Self-update against the fixed "android-latest" GitHub release: CI uploads
   the APK plus a version.json carrying the build's versionCode. On app start
   the two are compared; if the release is newer the app offers to download
   the APK and hand it to the system installer. Installation works because
   every CI build is signed with the same committed key. */
object Updater {
    private const val BASE = "https://github.com/vtlinh/novels/releases/download/android-latest"
    private const val VERSION_URL = "$BASE/version.json"
    private const val APK_URL = "$BASE/app-release.apk"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

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

    suspend fun downloadApk(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val f = File(context.cacheDir, "update.apk")
            client.newCall(Request.Builder().url(APK_URL).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val stream = r.body?.byteStream() ?: return@withContext null
                stream.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
            }
            f
        } catch (e: Exception) {
            null
        }
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
}
