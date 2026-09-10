package dev.vtlinh.noveldownloader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/* One chapter image: post {hash}.txt, record that Slack thread in
   the database, poll for {hash}.png, and save it under scenes/. The
   chapter→image row is how the reader knows to draw a picture. Request
   threads stay until that save — an hour give-up only stops polling.

   Auto-generate (this novel's ⚙): when enabled, opening that novel
   posts every chapter N ≥ from where (N − from) is a multiple of
   every. A chapter already posted is not posted again. */
object ChapterImages {

    private const val WAIT_KEY = "slackImageWait"
    const val GIVE_UP_MS = 60L * 60L * 1000L
    const val AUTO_EVERY_DEFAULT = 20
    const val AUTO_FROM_DEFAULT = 1
    private val inflight = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val autoLock = Any()
    /* Polls must outlive the chapter list: opening the reader finishes
       that screen and would cancel a lifecycle-scoped wait. */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    fun autoEnabledKey(slug: String) = "autoImage:$slug"
    fun autoEveryKey(slug: String) = "autoImageEvery:$slug"
    fun autoFromKey(slug: String) = "autoImageFrom:$slug"

    fun autoEnabled(ctx: Context, slug: String): Boolean =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getBoolean(autoEnabledKey(slug), false)

    fun autoEvery(ctx: Context, slug: String): Int =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getInt(autoEveryKey(slug), AUTO_EVERY_DEFAULT).coerceAtLeast(1)

    fun autoFrom(ctx: Context, slug: String): Int =
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getInt(autoFromKey(slug), AUTO_FROM_DEFAULT).coerceAtLeast(1)

    fun setAuto(ctx: Context, slug: String, enabled: Boolean, every: Int, from: Int) {
        if (slug.isEmpty()) return
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
            .putBoolean(autoEnabledKey(slug), enabled)
            .putInt(autoEveryKey(slug), every.coerceAtLeast(1))
            .putInt(autoFromKey(slug), from.coerceAtLeast(1))
            .apply()
    }

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
        if (slug.isEmpty() || folder.isEmpty() || dirName.isEmpty()) return
        if (!autoEnabled(ctx, slug) || !slackReady(ctx)) return
        val every = autoEvery(ctx, slug)
        val from = autoFrom(ctx, slug)
        val app = ctx.applicationContext
        work.launch {
            for (chapter in chapters) {
                val n = Scenes.chapterNumber(chapter) ?: continue
                if (!due(n, from, every)) continue
                if (hasLocalImage(app, folder, dirName, chapter, slug)) continue
                if (alreadyRequested(app, folder, dirName, slug, chapter)) {
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

    fun alreadyRequested(ctx: Context, dirName: String, slug: String, chapter: String): Boolean {
        val folder = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getString("tree", "") ?: ""
        return alreadyRequested(ctx, folder, dirName, slug, chapter)
    }

    /* Locked while the png is on disk and in the database, or a
       request is still waiting — inside the hour, or past it before
       the last Slack look. A stale image row whose file is gone does
       not lock — Generate image can be tapped again. */
    fun alreadyRequested(
        ctx: Context,
        folder: String,
        dirName: String,
        slug: String,
        chapter: String,
    ): Boolean {
        if (folder.isEmpty() || slug.isEmpty() || chapter.isEmpty()) return false
        importLegacyWaits(ctx)
        val store = DownloadStore(ctx)
        return lockGenerate(
            hasImage = adoptDiskImage(ctx, folder, dirName, slug, chapter) != null,
            waits = store.imageReqs(folder, slug, chapter).map { it.startedAt to it.looked },
        )
    }

    fun lockGenerate(
        hasImage: Boolean,
        waits: List<Pair<Long, Boolean>>,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (hasImage) return true
        return waits.any { (startedAt, looked) -> !mayDrop(startedAt, looked, now) }
    }

    /* A chapter that already has a Slack {hash}.txt must not get another.
       "No png downloaded" is not "nothing posted" — Chapter 400 posted
       twice that way (6b1884… then 9dbbc0…) after the hour. */
    fun shouldPost(postIfMissing: Boolean, alreadyPosted: Boolean): Boolean =
        postIfMissing && !alreadyPosted

    fun markRequested(
        ctx: Context,
        folder: String,
        slug: String,
        chapter: String,
        hash: String,
        threadTs: String?,
        startedAt: Long = System.currentTimeMillis(),
    ) {
        DownloadStore(ctx).rememberImageReq(
            folder, slug, chapter, hash, threadTs.orEmpty(), startedAt,
        )
    }

    fun request(
        ctx: Context,
        folder: String,
        dirName: String,
        slug: String,
        chapter: String,
        postIfMissing: Boolean = true,
    ): Result<Boolean> {
        val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        val token = (prefs.getString("slackBotToken", "") ?: "").trim()
        val channel = (prefs.getString("slackChannelId", "") ?: "").trim()
        importLegacyWaits(ctx)
        val store = DownloadStore(ctx)
        return try {
            if (adoptDiskImage(ctx, folder, dirName, slug, chapter) != null) {
                return Result.success(true)
            }
            if (token.isEmpty() || channel.isEmpty()) {
                return Result.failure(IOException("Set Slack in Settings."))
            }
            val slack = SlackPoster(token, channel)
            val reqs = store.imageReqs(folder, slug, chapter)
            val threads = reqs.map { it.threadTs }.filter { it.isNotEmpty() }
            val text = chapterText(ctx, folder, dirName, slug, chapter)
                ?: return Result.failure(IOException("Could not read this chapter."))
            if (text.isEmpty()) {
                return Result.failure(IOException("This chapter is empty."))
            }
            var hash = reqs.firstOrNull { it.hash.isNotEmpty() }?.hash
                ?: Scenes.contentHash(text)
            val found = slack.findExisting(hash, threads)
            if (found.png != null) {
                savePng(ctx, folder, dirName, slug, chapter, found.png)
                return Result.success(true)
            }
            for (ts in found.threads) {
                if (ts.isNotEmpty()) markRequested(ctx, folder, slug, chapter, hash, ts)
            }
            val posted = reqs.isNotEmpty() || found.threads.isNotEmpty()
            val live = store.imageReqs(folder, slug, chapter).filter { !expired(it.startedAt) }
            if (live.isEmpty()) {
                if (!shouldPost(postIfMissing, posted)) {
                    return lastLook(ctx, slack, folder, dirName, slug, chapter, hash, threads + found.threads)
                }
                val post = slack.postChapter(text)
                hash = post.hash
                markRequested(ctx, folder, slug, chapter, hash, post.threadTs)
            }
            val all = store.imageReqs(folder, slug, chapter)
            val poll = all.map { it.threadTs }.filter { it.isNotEmpty() }
            val started = all.maxOfOrNull { it.startedAt }?.takeIf { it > 0L }
                ?: System.currentTimeMillis()
            if (!inflight.add(hash)) return Result.success(false)
            try {
                val remain = (started + GIVE_UP_MS - System.currentTimeMillis())
                    .coerceAtMost(SlackPoster.MAX_WAIT_MS)
                if (expired(started) || remain <= 0L) {
                    return lastLook(ctx, slack, folder, dirName, slug, chapter, hash, poll)
                }
                val png = slack.waitForImage(hash, poll, remain)
                savePng(ctx, folder, dirName, slug, chapter, png)
                Result.success(true)
            } finally {
                inflight.remove(hash)
            }
        } catch (e: Exception) {
            val reqs = store.imageReqs(folder, slug, chapter)
            val hash = reqs.firstOrNull { it.hash.isNotEmpty() }?.hash
            val started = reqs.minOfOrNull { it.startedAt } ?: 0L
            if (hash != null && expired(started)) {
                val slack = SlackPoster(token, channel)
                return lastLook(
                    ctx, slack, folder, dirName, slug, chapter, hash,
                    reqs.map { it.threadTs },
                )
            }
            Result.failure(e)
        }
    }

    fun resumeWaiting(ctx: Context, scope: CoroutineScope) {
        importLegacyWaits(ctx)
        val store = DownloadStore(ctx)
        val waiting = store.waitingImageReqs()
        if (waiting.isEmpty()) return
        val app = ctx.applicationContext
        scope.launch(Dispatchers.IO) {
            val seen = mutableSetOf<String>()
            for (w in waiting) {
                val key = "${w.folder}\u0000${w.slug}\u0000${w.chapter}"
                if (!seen.add(key)) continue
                val dir = try { store.dirNameFor(w.folder, w.slug) } catch (e: Exception) { null }
                    ?: continue
                if (dir.isEmpty()) continue
                try {
                    request(app, w.folder, dir, w.slug, w.chapter, postIfMissing = false)
                } catch (e: Exception) {}
            }
        }
    }

    /* Prefs wait list from builds before the database rows. Fold it
       in once so a kill mid-poll still resumes. */
    private fun importLegacyWaits(ctx: Context) {
        val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        val raw = prefs.getString(WAIT_KEY, "") ?: ""
        if (raw.isEmpty()) return
        val arr = try { JSONArray(raw.ifEmpty { "[]" }) } catch (e: Exception) { JSONArray() }
        val store = DownloadStore(ctx)
        for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            val folder = w.optString("folder")
            val slug = w.optString("slug")
            val chapter = w.optString("chapter")
            if (folder.isEmpty() || slug.isEmpty() || chapter.isEmpty()) continue
            val started = w.optLong("startedAt", 0L)
            store.rememberImageReq(
                folder, slug, chapter,
                w.optString("hash"), w.optString("threadTs"),
                if (started > 0L) started else System.currentTimeMillis(),
            )
        }
        prefs.edit().remove(WAIT_KEY).apply()
    }

    /* One Slack lookup. Request threads stay unless the png is saved. */
    private fun lastLook(
        ctx: Context,
        slack: SlackPoster,
        folder: String,
        dirName: String,
        slug: String,
        chapter: String,
        hash: String,
        threads: Collection<String>,
    ): Result<Boolean> {
        val found = try { slack.findExisting(hash, threads) } catch (e: Exception) {
            SlackPoster.Existing(null, emptyList())
        }
        if (found.png != null) {
            savePng(ctx, folder, dirName, slug, chapter, found.png)
            return Result.success(true)
        }
        for (ts in found.threads) {
            if (ts.isNotEmpty()) markRequested(ctx, folder, slug, chapter, hash, ts)
        }
        val store = DownloadStore(ctx)
        store.markImageReqLooked(folder, slug, chapter)
        val started = store.imageReqs(folder, slug, chapter)
            .minOfOrNull { it.startedAt } ?: 0L
        if (mayDrop(started, looked = true)) {
            return Result.failure(IOException("Gave up waiting for the image."))
        }
        return Result.failure(IOException("No image came back from Slack."))
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

    fun linkedImage(ctx: Context, folder: String, slug: String, chapter: String): String? {
        if (folder.isEmpty() || slug.isEmpty() || chapter.isEmpty()) return null
        return try { DownloadStore(ctx).chapterImage(folder, slug, chapter) } catch (e: Exception) { null }
    }

    fun hasLocalImage(
        ctx: Context,
        folder: String,
        dirName: String,
        chapter: String,
        slug: String = "",
    ): Boolean = adoptDiskImage(ctx, folder, dirName, slug, chapter) != null

    /* One document query for scenes/{chapter}.png — not a listing of
       scenes/. Generate image does this before any Slack call. A
       database row whose file is gone is dropped. */
    fun adoptDiskImage(
        ctx: Context,
        folder: String,
        dirName: String,
        slug: String,
        chapter: String,
    ): String? {
        if (folder.isEmpty() || dirName.isEmpty() || slug.isEmpty() || chapter.isEmpty()) return null
        val linked = linkedImage(ctx, folder, slug, chapter)
        if (!linked.isNullOrEmpty()) {
            if (imageOnDisk(ctx, folder, dirName, linked)) return linked
            forgetMissingImage(ctx, folder, slug, chapter)
        }
        val name = Scenes.imageName(chapter)
        if (!imageOnDisk(ctx, folder, dirName, name)) return null
        try { DownloadStore(ctx).setChapterImage(folder, slug, chapter, name) } catch (e: Exception) { return null }
        return name
    }

    fun imageOnDisk(ctx: Context, folder: String, dirName: String, image: String): Boolean {
        if (folder.isEmpty() || dirName.isEmpty() || image.isEmpty()) return false
        val tree = Uri.parse(folder)
        return try {
            Saf.exists(ctx.contentResolver, tree, resolveImageDocId(Saf.rootId(tree), dirName, image))
        } catch (e: Exception) { false }
    }

    fun imageDocId(rootId: String, dirName: String, image: String): String {
        val base = "$rootId/$dirName/${Scenes.DIR}"
        return if (image.isEmpty()) base else "$base/$image"
    }

    /* A stored value with a slash is the provider's document id from
       the write. A bare filename is the older row — guess the path. */
    fun resolveImageDocId(rootId: String, dirName: String, stored: String): String {
        if (stored.contains('/')) return stored
        return imageDocId(rootId, dirName, stored)
    }

    fun chapterUri(
        ctx: Context,
        folder: String,
        dirName: String,
        chapter: String,
        slug: String = "",
    ): Uri? {
        val name = linkedImage(ctx, folder, slug, chapter) ?: return null
        val tree = Uri.parse(folder)
        return DocumentsContract.buildDocumentUriUsingTree(
            tree, resolveImageDocId(Saf.rootId(tree), dirName, name),
        )
    }

    fun forgetMissingImage(ctx: Context, folder: String, slug: String, chapter: String) {
        try { DownloadStore(ctx).clearChapterImage(folder, slug, chapter) } catch (e: Exception) {}
    }

    fun thumb(ctx: Context, uri: Uri, edgePx: Int): android.graphics.Bitmap? {
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            }
            val w = opts.outWidth
            val h = opts.outHeight
            if (w <= 0 || h <= 0) return null
            val sample = maxOf(1, minOf(w, h) / edgePx.coerceAtLeast(1))
            val dec = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, dec)
            }
        } catch (e: Exception) { null }
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
        val name = Scenes.imageName(chapter)
        val docId = writeBytes(ctx, dir, name, "image/png", bytes)
            ?: throw IOException("Could not save the image.")
        val store = DownloadStore(ctx)
        store.setChapterImage(folder, slug, chapter, docId)
        /* Keep the Slack threads. Clearing them was why Chapter 400
           posted a second {hash}.txt after the saved png could not be
           opened — the hash was gone, so the next tap hashed new text. */
        try { store.forgetDiskBytes(folder, slug) } catch (e: Exception) {}
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
    ): String? {
        return try {
            dir.findFile(name)?.delete()
            val f = dir.createFile(mime, Zips.partName(name)) ?: return null
            try {
                ctx.contentResolver.openOutputStream(f.uri)?.use { it.write(bytes) }
                    ?: throw IOException("could not open $name")
                val done = DocumentsContract.renameDocument(ctx.contentResolver, f.uri, name)
                    ?: throw IOException("could not name $name")
                val got = Zips.docName(ctx.contentResolver, done)
                if (got != null && got != name) {
                    try { DocumentsContract.deleteDocument(ctx.contentResolver, done) } catch (e: Exception) {}
                    return null
                }
                try { DocumentsContract.getDocumentId(done) } catch (e: Exception) { name }
            } catch (e: Exception) {
                try { f.delete() } catch (e2: Exception) {}
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
