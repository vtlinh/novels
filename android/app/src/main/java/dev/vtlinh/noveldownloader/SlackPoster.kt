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

        /* ChatGPT always saves as {hash}.png. A thread reply often
           returns a stub with only an id — hydrate, then match this. */
        fun fileMatchesHash(hash: String, name: String, title: String) =
            fileMatchesExt(hash, "png", name, title)

        /* The chapter upload is {hash}.txt. After a failed hour we
           find every copy of that file and walk each thread. */
        fun fileMatchesTxt(hash: String, name: String, title: String) =
            fileMatchesExt(hash, "txt", name, title)

        fun fileMatchesExt(
            hash: String,
            ext: String,
            name: String,
            title: String,
        ): Boolean {
            val h = hash.lowercase()
            if (h.isEmpty() || ext.isEmpty()) return false
            val want = "$h.${ext.lowercase()}"
            return name.lowercase() == want || title.lowercase() == want
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
           in is then unknown. files.info is the second look for that
           share timestamp. */
        var ts = shareTs(done)
        if (ts.isNullOrEmpty()) ts = shareTs(fileInfo(fileId))
        return Post(hash, ts)
    }

    /* ChatGPT replies in the .txt thread as {hash}.png. Walk that
       thread first. Replies often carry a file stub (id only); ask
       files.info for the name and download URL. files.list is the
       fallback when Slack omitted the share timestamp. */
    fun waitForImage(hash: String, threadTs: String?, timeoutMs: Long = MAX_WAIT_MS): ByteArray =
        waitForImage(hash, listOfNotNull(threadTs?.takeIf { it.isNotEmpty() }), timeoutMs)

    fun waitForImage(hash: String, threads: Collection<String>, timeoutMs: Long = MAX_WAIT_MS): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            findImage(hash, threads)?.let { return it }
            if (System.currentTimeMillis() >= deadline) {
                throw IOException("No image came back from Slack.")
            }
            Thread.sleep(POLL_MS)
        }
    }

    fun findImage(hash: String, threadTs: String?): ByteArray? {
        if (!threadTs.isNullOrEmpty()) {
            try {
                pickImage(hash, replies(threadTs))?.let { bytes -> return bytes }
            } catch (e: ApiException) {
                if (e.code != "missing_scope" && e.code != "thread_not_found" &&
                    e.code != "message_not_found"
                ) {
                    throw e
                }
            }
        }
        try {
            pickImage(hash, listedFiles())?.let { bytes -> return bytes }
        } catch (e: ApiException) {
            if (e.code != "missing_scope") throw e
        }
        return null
    }

    /* Stored request threads first, then every {hash}.txt Slack still
       lists. Used on a retry tap and on the hour-end last look. */
    fun findExistingImage(hash: String, knownThreads: Collection<String> = emptyList()): ByteArray? {
        val seen = linkedSetOf<String>()
        for (ts in knownThreads) if (ts.isNotEmpty()) seen.add(ts)
        for (ts in seen) {
            try {
                pickImage(hash, replies(ts))?.let { return it }
            } catch (e: ApiException) {
                if (e.code != "missing_scope" && e.code != "thread_not_found" &&
                    e.code != "message_not_found"
                ) {
                    throw e
                }
            }
        }
        try {
            for (stub in listedFilesAll()) {
                val file = hydrate(stub)
                val name = file.optString("name")
                val title = file.optString("title")
                if (fileMatchesHash(hash, name, title)) {
                    download(file)?.let { return it }
                }
                if (fileMatchesTxt(hash, name, title)) {
                    threadTsOf(file)?.let { seen.add(it) }
                }
            }
        } catch (e: ApiException) {
            if (e.code != "missing_scope") throw e
        }
        for (ts in seen) {
            if (knownThreads.contains(ts)) continue
            try {
                pickImage(hash, replies(ts))?.let { return it }
            } catch (e: ApiException) {
                if (e.code != "missing_scope" && e.code != "thread_not_found" &&
                    e.code != "message_not_found"
                ) {
                    throw e
                }
            }
        }
        return null
    }

    fun findImage(hash: String, threads: Collection<String>): ByteArray? {
        for (ts in threads) {
            if (ts.isEmpty()) continue
            findImage(hash, ts)?.let { return it }
        }
        return findImage(hash, null)
    }

    private fun pickImage(hash: String, files: List<JSONObject>): ByteArray? {
        val seen = mutableSetOf<String>()
        for (stub in files) {
            val id = stub.optString("id")
            if (id.isNotEmpty() && !seen.add(id)) continue
            val file = hydrate(stub)
            if (!fileMatchesHash(hash, file.optString("name"), file.optString("title"))) {
                continue
            }
            download(file)?.let { return it }
        }
        return null
    }

    /* Fill name / url_private when the list or reply only had an id.
       Title alone is not enough to skip — Slack often invents a caption
       while leaving name blank. */
    private fun hydrate(file: JSONObject): JSONObject {
        val url = file.optString("url_private_download").ifEmpty {
            file.optString("url_private")
        }
        if (file.optString("name").isNotEmpty() && url.isNotEmpty()) return file
        val id = file.optString("id")
        if (id.isEmpty()) return file
        return fileInfo(id).optJSONObject("file") ?: file
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

    /* Do not filter types=images — Slack then misses some thread uploads.
       Match {hash}.png after hydrate. */
    private fun listedFiles(): List<JSONObject> {
        val json = apiForm(
            "files.list",
            FormBody.Builder()
                .add("channel", channelId)
                .add("count", "50")
                .build(),
        )
        val out = mutableListOf<JSONObject>()
        addFiles(json.optJSONArray("files"), out)
        return out
    }

    /* Every file Slack will give us in this channel — a retry after
       give-up has to see older {hash}.txt posts, not only the last 50. */
    private fun listedFilesAll(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var page = 1
        var pages = 1
        while (page <= pages && page <= 20) {
            val json = apiForm(
                "files.list",
                FormBody.Builder()
                    .add("channel", channelId)
                    .add("count", "100")
                    .add("page", page.toString())
                    .build(),
            )
            addFiles(json.optJSONArray("files"), out)
            pages = json.optJSONObject("paging")?.optInt("pages", 1) ?: 1
            if (pages < 1) break
            page++
        }
        return out
    }

    private fun threadTsOf(file: JSONObject): String? {
        shareTsOf(file)?.let { return it }
        val id = file.optString("id")
        if (id.isEmpty()) return null
        return shareTs(fileInfo(id))
    }

    /* Thread replies list `files`, sometimes a singular `file`, and
       Block Kit `file` / `file_id` blocks that carry only an id. */
    private fun collectFiles(m: JSONObject, out: MutableList<JSONObject>) {
        addFiles(m.optJSONArray("files"), out)
        m.optJSONObject("file")?.let { out.add(it) }
        val blocks = m.optJSONArray("blocks")
        if (blocks != null) {
            for (i in 0 until blocks.length()) {
                val b = blocks.optJSONObject(i) ?: continue
                b.optJSONObject("file")?.let { out.add(it) }
                b.optJSONObject("slack_file")?.let { out.add(it) }
                val id = b.optString("file_id")
                if (id.isNotEmpty()) out.add(JSONObject().put("id", id))
            }
        }
        val atts = m.optJSONArray("attachments") ?: return
        for (i in 0 until atts.length()) {
            val a = atts.optJSONObject(i) ?: continue
            addFiles(a.optJSONArray("files"), out)
            a.optJSONObject("file")?.let { out.add(it) }
        }
    }

    private fun addFiles(arr: JSONArray?, out: MutableList<JSONObject>) {
        if (arr == null) return
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
    }

    private fun download(file: JSONObject): ByteArray? {
        val url = file.optString("url_private_download").ifEmpty {
            file.optString("url_private")
        }
        if (url.isEmpty()) return null
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
