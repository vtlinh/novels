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
    data class Existing(val png: ByteArray?, val threads: List<String>)
    data class NamedFile(val name: String, val title: String = "")
    data class HistoryMsg(
        val ts: String,
        val threadTs: String = "",
        val files: List<NamedFile>,
    )

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

        /* Channel history is the source of truth for "did we already
           post this hash". files.list missed Chapter 374's first
           {hash}.txt and the app posted the same bytes again. The
           message ts is the thread — do not wait on shares. */
        fun historyTxtThreads(messages: Iterable<HistoryMsg>, hash: String): List<String> {
            val seen = linkedSetOf<String>()
            for (m in messages) {
                if (m.files.none { fileMatchesTxt(hash, it.name, it.title) }) continue
                val ts = m.threadTs.ifEmpty { m.ts }
                if (ts.isNotEmpty()) seen.add(ts)
            }
            return seen.toList()
        }

        fun historyMsgOf(m: JSONObject): HistoryMsg {
            val files = mutableListOf<JSONObject>()
            collectMessageFiles(m, files)
            return HistoryMsg(
                ts = m.optString("ts"),
                threadTs = m.optString("thread_ts"),
                files = files.map { NamedFile(it.optString("name"), it.optString("title")) },
            )
        }

        fun collectMessageFiles(m: JSONObject, out: MutableList<JSONObject>) {
            addMessageFiles(m.optJSONArray("files"), out)
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
                addMessageFiles(a.optJSONArray("files"), out)
                a.optJSONObject("file")?.let { out.add(it) }
            }
        }

        private fun addMessageFiles(arr: JSONArray?, out: MutableList<JSONObject>) {
            if (arr == null) return
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        }

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
                "The Slack app needs files:write, files:read, channels:join, " +
                    "and channels:history or groups:history."
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

    private fun log(msg: String) {
        DownloadService.appendLog("image: $msg")
    }

    private fun shortHash(hash: String) =
        if (hash.length <= 12) hash else hash.take(12)

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

    /* Stored request threads, then every {hash}.txt in channel
       history, then files.list. History first — files.list missed
       Chapter 374's first upload of the same hash. Threads are
       returned even when the png cannot be downloaded, so a miss
       does not post a second {hash}.txt. */
    fun findExistingImage(hash: String, knownThreads: Collection<String> = emptyList()): ByteArray? =
        findExisting(hash, knownThreads).png

    fun findExisting(hash: String, knownThreads: Collection<String> = emptyList()): Existing {
        val seen = linkedSetOf<String>()
        for (ts in knownThreads) if (ts.isNotEmpty()) seen.add(ts)
        log("look ${shortHash(hash)} known=${seen.size}")
        val hist = channelHistory()
        val fromHist = historyTxtThreads(hist.map { historyMsgOf(it) }, hash)
        for (ts in fromHist) seen.add(ts)
        log("history msgs=${hist.size} txt=${fromHist.size} threads=${seen.size}")
        var png: ByteArray? = null
        try {
            val listed = listedFilesAll()
            var listTxt = 0
            for (stub in listed) {
                val file = hydrate(stub)
                val name = file.optString("name")
                val title = file.optString("title")
                if (fileMatchesHash(hash, name, title) && png == null) {
                    png = download(file)
                    log("files.list png ${name.ifEmpty { title }} ${png?.size ?: 0}B")
                }
                if (fileMatchesTxt(hash, name, title)) {
                    listTxt++
                    threadTsOf(file)?.let { seen.add(it) }
                        ?: log("files.list txt $name no thread ts")
                }
            }
            log("files.list n=${listed.size} txt=$listTxt png=${png?.size ?: 0}B threads=${seen.size}")
        } catch (e: ApiException) {
            log("files.list ${e.code}")
            if (e.code != "missing_scope") throw e
        }
        if (png == null) {
            for (ts in seen) {
                try {
                    val files = replies(ts)
                    val names = files.map {
                        hydrate(it).optString("name").ifEmpty { it.optString("id") }
                    }
                    log("replies $ts files=${files.size} $names")
                    val got = pickImage(hash, files)
                    if (got != null) {
                        png = got
                        log("replies $ts png ${got.size}B")
                        break
                    }
                } catch (e: ApiException) {
                    log("replies $ts ${e.code}")
                    if (e.code != "missing_scope" && e.code != "thread_not_found" &&
                        e.code != "message_not_found"
                    ) {
                        throw e
                    }
                }
            }
        }
        log("look done ${shortHash(hash)} png=${png?.size ?: 0}B threads=${seen.size}")
        return Existing(png, seen.toList())
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
            val name = file.optString("name")
            val title = file.optString("title")
            if (!fileMatchesHash(hash, name, title)) {
                continue
            }
            download(file)?.let { return it }
            log("pick $id $name download empty")
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
            collectMessageFiles(m, out)
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

    /* Top-level channel messages. The png lives in the txt thread,
       so history is only used to find {hash}.txt and its ts. */
    private fun channelHistory(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        try {
            var cursor = ""
            var pages = 0
            while (pages < 10) {
                pages++
                val body = FormBody.Builder()
                    .add("channel", channelId)
                    .add("limit", "200")
                if (cursor.isNotEmpty()) body.add("cursor", cursor)
                val json = apiForm("conversations.history", body.build())
                val msgs = json.optJSONArray("messages") ?: JSONArray()
                for (i in 0 until msgs.length()) {
                    msgs.optJSONObject(i)?.let { out.add(it) }
                }
                val next = json.optJSONObject("response_metadata")
                    ?.optString("next_cursor").orEmpty()
                if (next.isEmpty()) break
                cursor = next
            }
        } catch (e: ApiException) {
            log("history ${e.code} after ${out.size} msgs")
            if (e.code != "missing_scope" && e.code != "not_in_channel" &&
                e.code != "channel_not_found" &&
                e.code != "method_not_supported_for_channel_type"
            ) {
                throw e
            }
        }
        return out
    }

    private fun addFiles(arr: JSONArray?, out: MutableList<JSONObject>) {
        if (arr == null) return
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
    }

    private fun download(file: JSONObject): ByteArray? {
        val url = file.optString("url_private_download").ifEmpty {
            file.optString("url_private")
        }
        if (url.isEmpty()) {
            log("download ${file.optString("id")} ${file.optString("name")} no url")
            return null
        }
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                log("download ${file.optString("name")} http ${r.code}")
                throw IOException("Could not download the Slack image (${r.code}).")
            }
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
            val why = if (e is ApiException) e.code else e.message
            log("files.info $fileId $why")
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
                val code = json.optString("error", "http_${r.code}")
                log("slack ${req.url.encodedPath} $code")
                throw ApiException(code)
            }
            return json
        }
    }
}
