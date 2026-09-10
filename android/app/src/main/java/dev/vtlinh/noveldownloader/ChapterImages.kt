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
   resumes on the next foreground. */
object ChapterImages {

    private const val WAIT_KEY = "slackImageWait"
    private val inflight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun postedKey(slug: String, chapter: String) = "imgPosted:$slug:$chapter"

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
                val png = slack.waitForImage(hash, threadTs)
                savePng(ctx, folder, dirName, slug, chapter, png)
                forgetWait(ctx, hash)
                Result.success(true)
            } finally {
                inflight.remove(hash)
            }
        } catch (e: Exception) {
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
                .put("threadTs", threadTs ?: ""),
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

    private fun waitThread(ctx: Context, hash: String): String? {
        val arr = waiting(ctx)
        for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            if (w.optString("hash") == hash) {
                return w.optString("threadTs").takeIf { it.isNotEmpty() }
            }
        }
        return null
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
