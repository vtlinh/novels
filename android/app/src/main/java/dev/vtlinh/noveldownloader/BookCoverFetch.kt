package dev.vtlinh.noveldownloader

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/* Saves a cover into the file the library, the novel page, and read-aloud
   already display. The picture inside an EPUB or a Kindle file wins. Otherwise
   the title, author, and ISBN are looked up. A book that has none is
   remembered, so the library does not ask again every time it opens. */
object BookCoverFetch {

    private const val UA = "NovelDownloader cover lookup (https://github.com/vtlinh/novels)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val lock = Any()
    private val busy = HashSet<String>()
    private val tried = HashSet<String>()

    /* Call this while the file is still in hand, before it is turned into
       chapters. The picture inside the file is kept even if a search already
       saved a different one. */
    fun fromFile(context: Context, slug: String, bytes: ByteArray, displayName: String): Boolean {
        if (slug.isBlank() || bytes.isEmpty()) return false
        val dest = DownloadEngine.coverFile(context, slug)
        val embedded = BookCover.embedded(bytes, displayName)
        if (embedded != null) {
            if (!write(dest, embedded, onlyIfAbsent = false)) return false
            clearMiss(context, slug)
            noteTried(slug)
            return true
        }
        if (dest.exists() && dest.length() > 0L) return false
        val id = BookCover.identity(bytes, displayName) ?: return false
        return lookup(context, slug, dest, id)
    }

    /* A book already in the library, looked up from the title and author
       that were stored when it was added. */
    fun forNovel(context: Context, slug: String, title: String, author: String): Boolean {
        if (slug.isBlank() || title.isBlank()) return false
        val dest = DownloadEngine.coverFile(context, slug)
        if (dest.exists() && dest.length() > 0L) return false
        if (missed(context, slug)) return false
        val id = BookCover.Identity(title.trim(), author.trim(), null)
        if (BookCover.queries(id).isEmpty()) {
            markMiss(context, slug)
            return false
        }
        return lookup(context, slug, dest, id)
    }

    private fun lookup(context: Context, slug: String, dest: File, id: BookCover.Identity): Boolean {
        if (!begin(slug)) return false
        val net = Net()
        try {
            val image = try {
                BookCover.resolve(id, null, net::get)
            } catch (e: BookCover.Throttle) {
                return false
            }
            if (image != null) {
                if (!write(dest, image, onlyIfAbsent = true)) return false
                clearMiss(context, slug)
                return true
            }
            if (!net.failed) markMiss(context, slug)
            return false
        } finally {
            finish(slug)
        }
    }

    private class Net {
        var failed = false

        fun get(url: String): ByteArray? {
            if (!url.startsWith("https://")) {
                failed = true
                return null
            }
            return try {
                val req = Request.Builder().url(url).header("User-Agent", UA).build()
                client.newCall(req).execute().use { r ->
                    if (r.code == 403 || r.code == 429) throw BookCover.Throttle()
                    if (r.code == 404 || r.code == 410) return null
                    if (!r.isSuccessful) {
                        failed = true
                        return null
                    }
                    val body = r.body ?: return null
                    val bytes = readLimited(body.byteStream(), 6_000_000) ?: run {
                        failed = true
                        return null
                    }
                    if (url.contains("search.json") || url.contains("googleapis.com/books")) {
                        val head = bytes.indexOfFirst {
                            val c = it.toInt() and 0xFF
                            c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code
                        }
                        if (head < 0 || (bytes[head] != '{'.code.toByte() && bytes[head] != '['.code.toByte())) {
                            failed = true
                            return null
                        }
                    }
                    bytes
                }
            } catch (e: BookCover.Throttle) {
                throw e
            } catch (e: Exception) {
                failed = true
                null
            }
        }
    }

    private fun readLimited(input: java.io.InputStream, max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun write(dest: File, bytes: ByteArray, onlyIfAbsent: Boolean): Boolean {
        try {
            if (onlyIfAbsent && dest.exists() && dest.length() > 0L) return false
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            tmp.writeBytes(bytes)
            if (onlyIfAbsent && dest.exists() && dest.length() > 0L) {
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(dest)) {
                if (onlyIfAbsent && dest.exists() && dest.length() > 0L) {
                    tmp.delete()
                    return false
                }
                dest.writeBytes(bytes)
                tmp.delete()
            }
            return dest.length() > 0L
        } catch (e: Exception) {
            return false
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("app", Context.MODE_PRIVATE)

    private fun missed(context: Context, slug: String) =
        prefs(context).getBoolean(BookCover.MISS_PREFIX + slug, false)

    private fun markMiss(context: Context, slug: String) {
        prefs(context).edit().putBoolean(BookCover.MISS_PREFIX + slug, true).apply()
    }

    private fun clearMiss(context: Context, slug: String) {
        prefs(context).edit().remove(BookCover.MISS_PREFIX + slug).apply()
    }

    private fun begin(slug: String): Boolean = synchronized(lock) {
        if (slug in busy || slug in tried) false else busy.add(slug)
    }

    private fun finish(slug: String) = synchronized(lock) {
        busy.remove(slug)
        tried.add(slug)
    }

    private fun noteTried(slug: String) = synchronized(lock) {
        tried.add(slug)
    }
}
