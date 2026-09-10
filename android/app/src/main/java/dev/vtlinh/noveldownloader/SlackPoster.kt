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
    data class Existing(
        val png: ByteArray?,
        val threads: List<String>,
        val readError: String? = null,
        val alt: String = "",
    )
    /* Downloaded {hash}.png plus the Slack file's own description. */
    data class FoundPng(val bytes: ByteArray, val alt: String = "")
    data class NamedFile(
        val name: String,
        val title: String = "",
        val threadTs: String = "",
    )
    data class HistoryMsg(
        val ts: String,
        val threadTs: String = "",
        val files: List<NamedFile>,
    )
    data class CatalogHit(
        val pngName: String?,
        val threads: List<String>,
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
        /* One history + files.list answers every missing hash. */
        fun catalogHit(
            hist: Iterable<HistoryMsg>,
            files: Iterable<NamedFile>,
            hash: String,
            knownThreads: Collection<String> = emptyList(),
        ): CatalogHit {
            val threads = linkedSetOf<String>()
            for (ts in knownThreads) if (ts.isNotEmpty()) threads.add(ts)
            for (ts in historyTxtThreads(hist, hash)) threads.add(ts)
            var png: String? = null
            for (f in files) {
                if (png == null && fileMatchesHash(hash, f.name, f.title)) {
                    png = f.name.ifEmpty { f.title }
                }
                if (fileMatchesTxt(hash, f.name, f.title) && f.threadTs.isNotEmpty()) {
                    threads.add(f.threadTs)
                }
            }
            return CatalogHit(png, threads.toList())
        }

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
                    /* Image blocks store the caption as alt_text, not
                       on the slim slack_file / file stub. */
                    val blockAlt = b.optString("alt_text")
                    b.optJSONObject("file")?.let { out.add(withBlockAlt(it, blockAlt)) }
                    b.optJSONObject("slack_file")?.let { out.add(withBlockAlt(it, blockAlt)) }
                    val id = b.optString("file_id")
                    if (id.isNotEmpty()) {
                        out.add(withBlockAlt(JSONObject().put("id", id), blockAlt))
                    }
                }
            }
            val atts = m.optJSONArray("attachments") ?: return
            for (i in 0 until atts.length()) {
                val a = atts.optJSONObject(i) ?: continue
                val attAlt = a.optString("alt_text")
                addMessageFiles(a.optJSONArray("files"), out)
                a.optJSONObject("file")?.let { out.add(withBlockAlt(it, attAlt)) }
            }
        }

        /* Copy a Block Kit alt_text onto a file stub that has none. */
        fun withBlockAlt(file: JSONObject, altText: String): JSONObject {
            val t = altText.trim()
            if (t.isEmpty()) return file
            if (file.optString("alt_txt").isNotEmpty() ||
                file.optString("alt_text").isNotEmpty()
            ) return file
            return file.put("alt_text", t)
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

        /* Slack's description of the picture: alt_txt first, then
           alt_text (Block Kit / upload spelling), then title. Skip a
           value that is only the filename — Slack copies that into
           both fields when ChatGPT left no caption (files.info
           example: alt_txt == "tedair.gif"). */
        fun fileAlt(altTxt: String, title: String, name: String): String {
            fun useful(raw: String): Boolean {
                val t = raw.trim()
                if (t.isEmpty()) return false
                val n = name.trim()
                if (n.isNotEmpty() && t.equals(n, ignoreCase = true)) return false
                val dot = t.lastIndexOf('.')
                if (dot > 0) {
                    val ext = t.substring(dot + 1).lowercase()
                    if (ext in setOf("png", "jpg", "jpeg", "gif", "webp")) {
                        val stem = t.substring(0, dot)
                        if (n.isNotEmpty() &&
                            stem.equals(n.substringBeforeLast('.', n), ignoreCase = true)
                        ) return false
                        /* {sha256}.png — 64 hex chars, the name we match on */
                        if (stem.length == 64 && stem.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                            return false
                        }
                    }
                }
                return true
            }
            val alt = altTxt.trim()
            if (useful(alt)) return alt
            val tit = title.trim()
            if (useful(tit)) return tit
            return ""
        }

        fun fileAlt(file: JSONObject): String = fileAlt(
            file.optString("alt_txt").ifEmpty { file.optString("alt_text") },
            file.optString("title"),
            file.optString("name"),
        )

        /* files.list (and some message stubs) include name + url but
           omit alt_txt. Image description is on files.info. Skip a
           second look when the object already carried alt_txt /
           alt_text — even a filename placeholder. */
        fun needsFileInfo(file: JSONObject): Boolean {
            if (file.optString("id").isEmpty()) return false
            if (fileAlt(file).isNotEmpty()) return false
            if (file.has("alt_txt") || file.has("alt_text")) return false
            return true
        }

        fun describe(code: String): String = when (code) {
            "invalid_auth", "not_authed", "token_revoked", "account_inactive" ->
                "Slack rejected the bot token. Check it in Settings."
            "channel_not_found", "invalid_channel" ->
                "That Slack channel ID was not found."
            "not_in_channel" ->
                "Invite the bot to that public channel, or add the channels:join scope."
            "missing_scope" ->
                "The Slack app cannot read this channel. Add files:read and " +
                    "groups:history (private) or channels:history (public), " +
                    "reinstall the app, and paste the new token in Settings."
            "file_uploads_disabled" ->
                "This Slack workspace has file uploads turned off."
            else -> "Slack error: $code"
        }

        /* History / files.list / replies all denied — waiting will not
           produce a png. A png we already downloaded wins. */
        fun lookDenied(png: ByteArray?, readError: String?): Boolean =
            png == null && !readError.isNullOrEmpty()

        /* One history + files.list is reused for every missing hash
           in a burst. A post invalidates it so the wait sees new files. */
        const val CATALOG_TTL_MS = 2L * 60_000L
        private val catalogLock = Any()
        private var cachedChannel: String? = null
        private var cachedAt = 0L
        private var cached: Catalog? = null

        fun invalidateCatalog() {
            synchronized(catalogLock) {
                cached = null
                cachedChannel = null
                cachedAt = 0L
            }
        }
    }

    class Catalog(
        val hist: List<HistoryMsg>,
        val files: List<CatFile>,
        val readError: String? = null,
    )

    class CatFile(
        val name: String,
        val title: String,
        val threadTs: String,
        val file: JSONObject,
    )

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
        invalidateCatalog()
        return Post(hash, ts)
    }

    /* ChatGPT replies in the .txt thread as {hash}.png. Walk that
       thread first. Replies often carry a file stub (id only); ask
       files.info for the name and download URL. files.list is the
       fallback when Slack omitted the share timestamp. */
    fun waitForImage(hash: String, threadTs: String?, timeoutMs: Long = MAX_WAIT_MS): FoundPng =
        waitForImage(hash, listOfNotNull(threadTs?.takeIf { it.isNotEmpty() }), timeoutMs)

    fun waitForImage(hash: String, threads: Collection<String>, timeoutMs: Long = MAX_WAIT_MS): FoundPng {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            findPng(hash, threads)?.let { return it }
            if (System.currentTimeMillis() >= deadline) {
                throw IOException("No image came back from Slack.")
            }
            Thread.sleep(POLL_MS)
        }
    }

    fun findImage(hash: String, threadTs: String?): ByteArray? = findPng(hash, threadTs)?.bytes

    private fun findPng(hash: String, threadTs: String?): FoundPng? {
        var tried = 0
        var denied = 0
        if (!threadTs.isNullOrEmpty()) {
            tried++
            try {
                pickImage(hash, replies(threadTs))?.let { return it }
            } catch (e: ApiException) {
                if (e.code == "missing_scope") denied++
                else if (e.code != "thread_not_found" && e.code != "message_not_found") {
                    throw e
                }
            }
        }
        tried++
        try {
            pickImage(hash, listedFiles())?.let { return it }
        } catch (e: ApiException) {
            if (e.code == "missing_scope") denied++
            else throw e
        }
        if (denied > 0 && denied == tried) throw ApiException("missing_scope")
        return null
    }

    /* Stored request threads, then every {hash}.txt in channel
       history, then files.list. History first — files.list missed
       Chapter 374's first upload of the same hash. Threads are
       returned even when the png cannot be downloaded, so a miss
       does not post a second {hash}.txt. */
    fun findExistingImage(hash: String, knownThreads: Collection<String> = emptyList()): ByteArray? =
        findExisting(hash, knownThreads).png

    fun findExistingMany(
        wants: List<Pair<String, Collection<String>>>,
    ): Map<String, Existing> {
        if (wants.isEmpty()) return emptyMap()
        val cat = catalog()
        val out = linkedMapOf<String, Existing>()
        for ((hash, threads) in wants) {
            if (hash.isEmpty() || hash in out) continue
            out[hash] = findExisting(hash, threads, cat)
        }
        return out
    }

    fun findExisting(hash: String, knownThreads: Collection<String> = emptyList()): Existing =
        findExisting(hash, knownThreads, catalog())

    fun findExisting(
        hash: String,
        knownThreads: Collection<String>,
        catalog: Catalog,
    ): Existing {
        val named = catalog.files.map { NamedFile(it.name, it.title, it.threadTs) }
        val hit = catalogHit(catalog.hist, named, hash, knownThreads)
        log("look ${shortHash(hash)} known=${knownThreads.count { it.isNotEmpty() }} catalog png=${hit.pngName != null} threads=${hit.threads.size}")
        var png: ByteArray? = null
        var alt = ""
        var readError = catalog.readError
        if (hit.pngName != null) {
            val file = catalog.files.firstOrNull {
                fileMatchesHash(hash, it.name, it.title)
            }
            if (file != null) {
                val full = withDescription(file.file)
                png = download(full)
                alt = fileAlt(full)
                log("catalog png ${hit.pngName} ${png?.size ?: 0}B alt=${alt.length}c")
            }
        }
        if (png == null) {
            for (ts in hit.threads) {
                try {
                    val files = replies(ts)
                    val names = files.map {
                        hydrate(it).optString("name").ifEmpty { it.optString("id") }
                    }
                    log("replies $ts files=${files.size} $names")
                    val got = pickImage(hash, files)
                    if (got != null) {
                        png = got.bytes
                        alt = got.alt
                        log("replies $ts png ${got.bytes.size}B")
                        break
                    }
                } catch (e: ApiException) {
                    log("replies $ts ${e.code}")
                    if (readError == null &&
                        (e.code == "missing_scope" || e.code == "not_in_channel" ||
                            e.code == "channel_not_found")
                    ) {
                        readError = e.code
                    }
                    if (e.code != "missing_scope" && e.code != "thread_not_found" &&
                        e.code != "message_not_found"
                    ) {
                        throw e
                    }
                }
            }
        }
        if (lookDenied(png, readError)) {
            log("look denied $readError")
        }
        log("look done ${shortHash(hash)} png=${png?.size ?: 0}B threads=${hit.threads.size}")
        return Existing(png, hit.threads, readError, alt)
    }

    fun catalog(): Catalog {
        val now = System.currentTimeMillis()
        synchronized(catalogLock) {
            val hit = cached
            if (hit != null && cachedChannel == channelId && now - cachedAt < CATALOG_TTL_MS) {
                return hit
            }
        }
        val fresh = loadCatalog()
        synchronized(catalogLock) {
            cachedChannel = channelId
            cachedAt = System.currentTimeMillis()
            cached = fresh
        }
        return fresh
    }

    private fun loadCatalog(): Catalog {
        var readError: String? = null
        fun remember(code: String) {
            if (readError == null &&
                (code == "missing_scope" || code == "not_in_channel" ||
                    code == "channel_not_found")
            ) {
                readError = code
            }
        }
        val hist = channelHistory()
        hist.error?.let { remember(it) }
        val msgs = hist.msgs.map { historyMsgOf(it) }
        val files = mutableListOf<CatFile>()
        try {
            val listed = listedFilesAll()
            for (stub in listed) {
                val file = hydrate(stub)
                val name = file.optString("name")
                val title = file.optString("title")
                val ts = threadTsOf(file).orEmpty()
                files.add(CatFile(name, title, ts, file))
            }
            log("catalog history=${msgs.size} files=${files.size}")
        } catch (e: ApiException) {
            log("catalog files.list ${e.code}")
            remember(e.code)
            if (e.code != "missing_scope") throw e
        }
        return Catalog(msgs, files, readError)
    }

    fun findImage(hash: String, threads: Collection<String>): ByteArray? = findPng(hash, threads)?.bytes

    private fun findPng(hash: String, threads: Collection<String>): FoundPng? {
        for (ts in threads) {
            if (ts.isEmpty()) continue
            findPng(hash, ts)?.let { return it }
        }
        return findPng(hash, null)
    }

    private fun pickImage(hash: String, files: List<JSONObject>): FoundPng? {
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
            val full = withDescription(file)
            download(full)?.let { return FoundPng(it, fileAlt(full)) }
            log("pick $id $name download empty")
        }
        return null
    }

    /* Fill name / url_private when the list or reply only had an id.
       Title alone is not enough to skip — Slack often invents a caption
       while leaving name blank. Does not fetch alt_txt: files.list
       already has name+url, and catalog hydrate runs on every file. */
    private fun hydrate(file: JSONObject): JSONObject {
        val url = file.optString("url_private_download").ifEmpty {
            file.optString("url_private")
        }
        if (file.optString("name").isNotEmpty() && url.isNotEmpty()) return file
        val id = file.optString("id")
        if (id.isEmpty()) return file
        return fileInfo(id).optJSONObject("file") ?: file
    }

    /* files.list omits Image description (alt_txt). After we have
       matched {hash}.png, ask files.info for that one file. */
    private fun withDescription(file: JSONObject): JSONObject {
        if (!needsFileInfo(file)) return file
        val id = file.optString("id")
        val info = fileInfo(id).optJSONObject("file") ?: return file
        return info
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
    private data class ChannelHist(val msgs: List<JSONObject>, val error: String? = null)

    private fun channelHistory(): ChannelHist {
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
            return ChannelHist(out, e.code)
        }
        return ChannelHist(out)
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
