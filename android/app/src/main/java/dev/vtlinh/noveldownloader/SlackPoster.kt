package dev.vtlinh.noveldownloader

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/* Posts one unzipped chapter to Slack as {sha256}.txt, then looks in that
   file's thread for {sha256}.png — ChatGPT replies there, not as a new
   top-level message. */
class SlackPoster(
    private val token: String,
    private val channelId: String,
) {

    class ApiException(val code: String) : IOException(describe(code))

    data class Post(val hash: String, val threadTs: String?)

    companion object {
        private const val API = "https://slack.com/api"
        private val TEXT = "text/plain; charset=utf-8".toMediaType()
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val POLL_MS = 10_000L
        const val MAX_WAIT_MS = 8L * 60_000L

        fun fileLooksLikeImage(name: String, title: String, mimetype: String): Boolean {
            val mime = mimetype.lowercase()
            if (mime.startsWith("image/")) return true
            for (n in listOf(name, title)) {
                val lower = n.lowercase()
                if (lower.endsWith(".png") || lower.endsWith(".jpg") ||
                    lower.endsWith(".jpeg") || lower.endsWith(".webp")
                ) {
                    return true
                }
            }
            return false
        }

        /* ChatGPT is asked to name the file {hash}.png, but the upload
           Slack stores is often image.png or a generated title. A hash
           in the name still counts; a thread-scoped look can take any
           image. */
        fun fileMatchesHash(hash: String, name: String, title: String): Boolean {
            val h = hash.lowercase()
            if (h.isEmpty()) return false
            val want = "$h.png"
            val n = name.lowercase()
            val t = title.lowercase()
            if (n == want || t == want) return true
            if (n.contains(h) && fileLooksLikeImage(name, title, "")) return true
            if (t.contains(h) && fileLooksLikeImage(name, title, "")) return true
            return false
        }

        fun describe(code: String): String = when (code) {
            "invalid_auth", "not_authed", "token_revoked", "account_inactive" ->
                "Slack rejected the bot token. Check it in Settings."
            "channel_not_found", "invalid_channel" ->
                "That Slack channel ID was not found."
            "not_in_channel" ->
                "Invite the bot to that public channel, or add the channels:join scope."
            "missing_scope" ->
                "The Slack app needs files:write, files:read, channels:join, and channels:history."
            "file_uploads_disabled" ->
                "This Slack workspace has file uploads turned off."
            else -> "Slack error: $code"
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    fun postChapter(text: String): Post {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val hash = Scenes.contentHash(text)
        val name = Scenes.slackFileName(text)
        val ticket = apiForm(
            "files.getUploadURLExternal",
            FormBody.Builder()
                .add("filename", name)
                .add("length", bytes.size.toString())
                .build(),
        )
        val uploadUrl = ticket.optString("upload_url")
        val fileId = ticket.optString("file_id")
        if (uploadUrl.isEmpty() || fileId.isEmpty()) {
            throw IOException("Slack did not return an upload ticket.")
        }
        val put = Request.Builder()
            .url(uploadUrl)
            .post(bytes.toRequestBody(TEXT))
            .build()
        client.newCall(put).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Slack upload failed (${r.code}).")
        }
        tryJoin()
        val files = JSONArray().put(JSONObject().put("id", fileId).put("title", name))
        val done = apiJson(
            "files.completeUploadExternal",
            JSONObject()
                .put("files", files)
                .put("channel_id", channelId),
        )
        if (!done.optBoolean("ok", true)) {
            throw ApiException(done.optString("error", "unknown"))
        }
        /* completeUpload often omits shares — the thread ChatGPT replies
           in is then unknown and we only scan the channel file list, which
           misses an image named anything but {hash}.png. files.info is the
           second look for that share timestamp. */
        var ts = shareTs(done)
        if (ts.isNullOrEmpty()) ts = shareTs(fileInfo(fileId))
        return Post(hash, ts)
    }

    /* ChatGPT replies in the .txt thread. Walk that thread first; any
       image there is the picture (the name is often not {hash}.png).
       files.list is the fallback when Slack omitted the share timestamp,
       and then the hash has to appear in the filename. */
    fun waitForImage(hash: String, threadTs: String?, timeoutMs: Long = MAX_WAIT_MS): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            findImage(hash, threadTs)?.let { return it }
            if (System.currentTimeMillis() >= deadline) {
                throw IOException("No image came back from Slack.")
            }
            Thread.sleep(POLL_MS)
        }
    }

    fun findImage(hash: String, threadTs: String?): ByteArray? {
        if (!threadTs.isNullOrEmpty()) {
            try {
                pickImage(hash, replies(threadTs), threadScoped = true)?.let { return download(it) }
            } catch (e: ApiException) {
                if (e.code != "missing_scope" && e.code != "thread_not_found" &&
                    e.code != "message_not_found"
                ) {
                    throw e
                }
            }
        }
        try {
            pickImage(hash, listedImages(), threadScoped = false)?.let { return download(it) }
        } catch (e: ApiException) {
            if (e.code != "missing_scope") throw e
        }
        return null
    }

    private fun pickImage(hash: String, files: List<JSONObject>, threadScoped: Boolean): JSONObject? {
        val hashed = files.firstOrNull {
            fileMatchesHash(hash, it.optString("name"), it.optString("title"))
        }
        if (hashed != null) return hashed
        if (!threadScoped) return null
        return files.firstOrNull {
            fileLooksLikeImage(
                it.optString("name"), it.optString("title"), it.optString("mimetype"),
            )
        }
    }

    private fun replies(threadTs: String): List<JSONObject> {
        val json = apiForm(
            "conversations.replies",
            FormBody.Builder()
                .add("channel", channelId)
                .add("ts", threadTs)
                .add("inclusive", "true")
                .add("limit", "50")
                .build(),
        )
        val out = mutableListOf<JSONObject>()
        val msgs = json.optJSONArray("messages") ?: return out
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            collectFiles(m, out)
        }
        return out
    }

    private fun listedImages(): List<JSONObject> {
        val json = apiForm(
            "files.list",
            FormBody.Builder()
                .add("channel", channelId)
                .add("types", "images")
                .add("count", "50")
                .build(),
        )
        val out = mutableListOf<JSONObject>()
        addFiles(json.optJSONArray("files"), out)
        return out
    }

    private fun collectFiles(m: JSONObject, out: MutableList<JSONObject>) {
        addFiles(m.optJSONArray("files"), out)
        val atts = m.optJSONArray("attachments") ?: return
        for (i in 0 until atts.length()) {
            addFiles(atts.optJSONObject(i)?.optJSONArray("files"), out)
        }
    }

    private fun addFiles(arr: JSONArray?, out: MutableList<JSONObject>) {
        if (arr == null) return
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
    }

    private fun download(file: JSONObject): ByteArray {
        val url = file.optString("url_private_download").ifEmpty {
            file.optString("url_private")
        }
        if (url.isEmpty()) throw IOException("Slack image had no download URL.")
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Could not download the Slack image (${r.code}).")
            return r.body?.bytes() ?: throw IOException("Slack image was empty.")
        }
    }

    private fun fileInfo(fileId: String): JSONObject {
        return try {
            apiForm(
                "files.info",
                FormBody.Builder().add("file", fileId).build(),
            )
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun shareTs(done: JSONObject): String? {
        done.optJSONObject("file")?.let { shareTsOf(it)?.let { ts -> return ts } }
        val files = done.optJSONArray("files") ?: return null
        val f = files.optJSONObject(0) ?: return null
        return shareTsOf(f)
    }

    private fun shareTsOf(f: JSONObject): String? {
        val shares = f.optJSONObject("shares") ?: return null
        for (kind in listOf("public", "private")) {
            val byChan = shares.optJSONObject(kind) ?: continue
            val arr = byChan.optJSONArray(channelId) ?: continue
            val ts = arr.optJSONObject(0)?.optString("ts").orEmpty()
            if (ts.isNotEmpty()) return ts
        }
        return null
    }

    private fun tryJoin() {
        try {
            apiForm(
                "conversations.join",
                FormBody.Builder().add("channel", channelId).build(),
            )
        } catch (e: ApiException) {
            if (e.code != "missing_scope" && e.code != "method_not_supported_for_channel_type") {
                throw e
            }
        }
    }

    private fun apiForm(method: String, body: FormBody): JSONObject {
        val req = Request.Builder()
            .url("$API/$method")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        return execute(req)
    }

    private fun apiJson(method: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("$API/$method")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return execute(req)
    }

    private fun execute(req: Request): JSONObject {
        client.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) {
                throw IOException("Slack returned a bad reply (${r.code}).")
            }
            if (!json.optBoolean("ok")) {
                throw ApiException(json.optString("error", "http_${r.code}"))
            }
            return json
        }
    }
}
