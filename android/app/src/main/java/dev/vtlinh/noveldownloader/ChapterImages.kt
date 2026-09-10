package dev.vtlinh.noveldownloader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/* One chapter image: post {hash}.txt, remember that chapter so Generate
   image stays off, then poll Slack for {hash}.png in that thread and
   save it under scenes/. Prefs hold the wait list so a kill mid-poll
   resumes on the next foreground. After an hour the wait is dropped —
   but only after one last look at Slack, or a png that arrived while
   the app was closed would be thrown away.

   Auto-generate (Settings): when enabled, opening a novel posts every
   chapter N ≥ from where (N − from) is a multiple of every. A chapter
   already posted is not posted again. */
object ChapterImages {

    private const val WAIT_KEY = "slackImageWait"
    const val GIVE_UP_MS = 60L * 60L * 1000L
    const val AUTO_EVERY_DEFAULT = 20
    const val AUTO_FROM_DEFAULT = 1
    private val inflight = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val autoLock = Any()

    fun postedKey(slug: String, chapter: String) = "imgPosted:$slug:$chapter"

    fun autoTriedKey(slug: String, chapter: String) = "imgAuto:$slug:$chapter"

    fun expired(startedAt: Long, now: Long = System.currentTimeMillis()) =
        now - startedAt >= GIVE_UP_MS

    /* A stale wait is dropped only after Slack has been checked once
       more. Dropping first loses a png that landed while the app was
       closed (generated at 30 min, app opened at 2 h). */
    fun mayDrop(startedAt: Long, looked: Boolean, now: Long = System.currentTimeMillis()) =
        expired(startedAt, now) && looked

    /* Chapter N is due when it is at or after `from` and lands on the
       every-th step from there. Defaults (from 1, every 20) → 1, 21, 41. */
    fun due(n: Int, from: Int, every: Int): Boolean {
        if (every < 1 || from < 1 || n < from) return false
        return (n - from) % every == 0
    }

    fun autoEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean("autoImage", false)

    fun autoEvery(ctx: Context): Int =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getInt("autoImageEvery", AUTO_EVERY_DEFAULT).coerceAtLeast(1)

    fun autoFrom(ctx: Context): Int =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getInt("autoImageFrom", AUTO_FROM_DEFAULT).coerceAtLeast(1)

    private fun slackReady(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        val token = (prefs.getString("slackBotToken", "") ?: "").trim()
        val channel = (prefs.getString("slackChannelId", "") ?: "").trim()
        return token.isNotEmpty() && channel.isNotEmpty()
    }

    private fun autoTried(ctx: Context, slug: String, chapter: String): Boolean =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getBoolean(autoTriedKey(slug, chapter), false)

    private fun markAutoTried(ctx: Context, slug: String, chapter: String) {
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
            .putBoolean(autoTriedKey(slug, chapter), true)
            .apply()
    }

    /* Opening a novel (list or reader) asks Slack for each due chapter
       that has no local picture and has not already been posted. One
       post at a time so a long book does not burst Slack. */
    fun autoSweep(
        ctx: Context,
        folder: String,
        dirName: String,
        slug: String,
        chapters: List<String>,
        scope: CoroutineScope,
    ) {
        if (!autoEnabled(ctx) || !slackReady(ctx)) return
        if (folder.isEmpty() || dirName.isEmpty() || slug.isEmpty()) return
        val every = autoEvery(ctx)
        val from = autoFrom(ctx)
        val app = ctx.applicationContext
        scope.launch(Dispatchers.IO) {
            for (chapter in chapters) {
                val n = Scenes.chapterNumber(chapter) ?: continue
                if (!due(n, from, every)) continue
                if (hasLocalImage(app, folder, dirName, chapter)) continue
                if (alreadyRequested(app, slug, chapter)) {
                    markAutoTried(app, slug, chapter)
                    continue
                }
                if (autoTried(app, slug, chapter)) continue
                markAutoTried(app, slug, chapter)
                synchronized(autoLock) {
                    try { request(app, folder, dirName, slug, chapter) } catch (e: Exception) {}
                }
            }
        }
    }

    fun alreadyRequested(ctx: Context, slug: String, chapter: String): Boolean {
        val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        return !prefs.getString(postedKey(slug, chapter), null).isNullOrEmpty()
    }

    fun markRequested(ctx: Context, slug: String, chapter: String, hash: String) {
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
            .putString(postedKey(slug, chapter), hash)
            .apply()
    }

    fun request(
        ctx: Context,
        folder: String,
        dirName: String,
        slug: String,
        chapter: String,
    ): Result<Boolean> {
        val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        val token = (prefs.getString("slackBotToken", "") ?: "").trim()
        val channel = (prefs.getString("slackChannelId", "") ?: "").trim()
        if (token.isEmpty() || channel.isEmpty()) {
            return Result.failure(IOException("Set Slack in Settings."))
        }
        return try {
            if (hasLocalImage(ctx, folder, dirName, chapter)) {
                prefs.getString(postedKey(slug, chapter), null)?.let { forgetWait(ctx, it) }
                return Result.success(true)
            }
            val slack = SlackPoster(token, channel)
            var hash = prefs.getString(postedKey(slug, chapter), null)
            var threadTs: String? = null
            if (hash.isNullOrEmpty()) {
                val text = chapterText(ctx, folder, dirName, slug, chapter)
                    ?: return Result.failure(IOException("Could not read this chapter."))
                if (text.isEmpty()) {
                    return Result.failure(IOException("This chapter is empty."))
                }
                val post = slack.postChapter(text)
                hash = post.hash
                threadTs = post.threadTs
                markRequested(ctx, slug, chapter, hash)
                rememberWait(ctx, folder, dirName, slug, chapter, hash, threadTs)
            } else {
                threadTs = waitThread(ctx, hash)
            }
            if (!inflight.add(hash)) return Result.success(false)
            try {
                val started = waitStarted(ctx, hash) ?: System.currentTimeMillis()
                val remain = (started + GIVE_UP_MS - System.currentTimeMillis())
                    .coerceAtMost(SlackPoster.MAX_WAIT_MS)
                if (expired(started) || remain <= 0L) {
                    return lastLook(ctx, slack, folder, dirName, slug, chapter, hash, threadTs)
                }
                val png = slack.waitForImage(hash, threadTs, remain)
                savePng(ctx, folder, dirName, slug, chapter, png)
                forgetWait(ctx, hash)
                Result.success(true)
            } finally {
                inflight.remove(hash)
            }
        } catch (e: Exception) {
            val h = prefs.getString(postedKey(slug, chapter), null)
            if (h != null && expired(waitStarted(ctx, h) ?: 0L)) {
                val slack = SlackPoster(token, channel)
                return lastLook(
                    ctx, slack, folder, dirName, slug, chapter, h, waitThread(ctx, h),
                )
            }
            Result.failure(e)
        }
    }

    fun resumeWaiting(ctx: Context, scope: CoroutineScope) {
        val waits = waiting(ctx)
        if (waits.length() == 0) return
        val app = ctx.applicationContext
        scope.launch(Dispatchers.IO) {
            for (i in 0 until waits.length()) {
                val w = waits.optJSONObject(i) ?: continue
                val folder = w.optString("folder")
                val dir = w.optString("dir")
                val slug = w.optString("slug")
                val chapter = w.optString("chapter")
                if (folder.isEmpty() || dir.isEmpty() || slug.isEmpty() || chapter.isEmpty()) continue
                try { request(app, folder, dir, slug, chapter) } catch (e: Exception) {}
            }
        }
    }

    private fun rememberWait(
        ctx: Context,
        folder: String,
        dirName: String,
        slug: String,
        chapter: String,
        hash: String,
        threadTs: String?,
    ) {
        val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        val arr = waiting(ctx)
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optString("hash") == hash) return
        }
        arr.put(
            JSONObject()
                .put("folder", folder)
                .put("dir", dirName)
                .put("slug", slug)
                .put("chapter", chapter)
                .put("hash", hash)
                .put("threadTs", threadTs ?: "")
                .put("startedAt", System.currentTimeMillis()),
        )
        prefs.edit().putString(WAIT_KEY, arr.toString()).apply()
    }

    private fun forgetWait(ctx: Context, hash: String) {
        val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        val arr = waiting(ctx)
        val next = JSONArray()
        for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            if (w.optString("hash") != hash) next.put(w)
        }
        prefs.edit().putString(WAIT_KEY, next.toString()).apply()
    }

    private fun waitOf(ctx: Context, hash: String): JSONObject? {
        val arr = waiting(ctx)
        for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            if (w.optString("hash") == hash) return w
        }
        return null
    }

    private fun waitThread(ctx: Context, hash: String): String? =
        waitOf(ctx, hash)?.optString("threadTs")?.takeIf { it.isNotEmpty() }

    private fun waitStarted(ctx: Context, hash: String): Long? {
        val w = waitOf(ctx, hash) ?: return null
        val at = w.optLong("startedAt", 0L)
        return if (at > 0L) at else null
    }

    /* One Slack lookup, then drop the wait if the png is still missing.
       Callers must not drop first — that is the 2-hour-closed loss. */
    private fun lastLook(
        ctx: Context,
        slack: SlackPoster,
        folder: String,
        dirName: String,
        slug: String,
        chapter: String,
        hash: String,
        threadTs: String?,
    ): Result<Boolean> {
        val png = try { slack.findImage(hash, threadTs) } catch (e: Exception) { null }
        if (png != null) {
            savePng(ctx, folder, dirName, slug, chapter, png)
            forgetWait(ctx, hash)
            return Result.success(true)
        }
        val started = waitStarted(ctx, hash) ?: 0L
        if (mayDrop(started, looked = true)) {
            giveUp(ctx, slug, chapter, hash)
            return Result.failure(IOException("Gave up waiting for the image."))
        }
        return Result.failure(IOException("No image came back from Slack."))
    }

    private fun giveUp(ctx: Context, slug: String, chapter: String, hash: String) {
        forgetWait(ctx, hash)
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
            .remove(postedKey(slug, chapter))
            .apply()
    }

    private fun waiting(ctx: Context): JSONArray {
        val raw = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getString(WAIT_KEY, "") ?: ""
        return try { JSONArray(raw.ifEmpty { "[]" }) } catch (e: Exception) { JSONArray() }
    }

    fun chapterText(
        ctx: Context,
        folder: String,
        dirName: String,
        slug: String,
        chapter: String,
    ): String? {
        val treeUri = Uri.parse(folder)
        val store = DownloadStore(ctx)
        val order = try { store.getChapterOrder(folder, slug) } catch (e: Exception) { emptyMap() }
        val ch = try {
            ChapterListActivity.chapterNames(ctx, treeUri, dirName, order, slug)
        } catch (e: Exception) { null } ?: return null
        val ref = ch.translated[chapter] ?: ch.source[chapter] ?: return null
        return try {
            if (Zips.isGzRef(ref)) Zips.readGz(ctx.contentResolver, treeUri, Zips.gzDocId(ref))
            else Saf.readText(ctx.contentResolver, treeUri, ref)
        } catch (e: Exception) { null }
    }

    fun hasLocalImage(ctx: Context, folder: String, dirName: String, chapter: String): Boolean {
        val dir = scenesDir(ctx, folder, dirName, create = false) ?: return false
        return dir.findFile(Scenes.imageName(chapter)) != null
    }

    private fun savePng(
        ctx: Context,
        folder: String,
        dirName: String,
        slug: String,
        chapter: String,
        bytes: ByteArray,
    ) {
        val dir = scenesDir(ctx, folder, dirName, create = true)
            ?: throw IOException("Could not create scenes/.")
        if (!writeBytes(ctx, dir, Scenes.imageName(chapter), "image/png", bytes)) {
            throw IOException("Could not save the image.")
        }
        try { DownloadStore(ctx).forgetDiskBytes(folder, slug) } catch (e: Exception) {}
    }

    private fun scenesDir(
        ctx: Context,
        folder: String,
        dirName: String,
        create: Boolean,
    ): DocumentFile? {
        val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(folder)) ?: return null
        val novel = tree.findFile(dirName)?.takeIf { it.isDirectory } ?: return null
        val existing = novel.findFile(Scenes.DIR)?.takeIf { it.isDirectory }
        if (existing != null || !create) return existing
        return novel.createDirectory(Scenes.DIR)
    }

    /* Write under a name nothing adopts, then rename — a kill mid-write
       otherwise leaves a short file treated as the finished image. */
    private fun writeBytes(
        ctx: Context,
        dir: DocumentFile,
        name: String,
        mime: String,
        bytes: ByteArray,
    ): Boolean {
        return try {
            dir.findFile(name)?.delete()
            val f = dir.createFile(mime, Zips.partName(name)) ?: return false
            try {
                ctx.contentResolver.openOutputStream(f.uri)?.use { it.write(bytes) }
                    ?: throw IOException("could not open $name")
                val done = DocumentsContract.renameDocument(ctx.contentResolver, f.uri, name)
                    ?: throw IOException("could not name $name")
                val got = Zips.docName(ctx.contentResolver, done)
                if (got != null && got != name) {
                    try { DocumentsContract.deleteDocument(ctx.contentResolver, done) } catch (e: Exception) {}
                    return false
                }
                true
            } catch (e: Exception) {
                try { f.delete() } catch (e2: Exception) {}
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
