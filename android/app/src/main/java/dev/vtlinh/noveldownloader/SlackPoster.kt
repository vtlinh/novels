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

/* Posts one unzipped chapter to a Slack channel as {sha256}.txt.

   The filename is the content hash and nothing else, so a Cursor or
   ChatGPT watcher can key off it. Slack's current upload is a ticket,
   a PUT of the bytes, then completeUploadExternal to share it. */
class SlackPoster(
    private val token: String,
    private val channelId: String,
) {

    class ApiException(val code: String) : IOException(describe(code))

    companion object {
        private const val API = "https://slack.com/api"
        private val TEXT = "text/plain; charset=utf-8".toMediaType()
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun describe(code: String): String = when (code) {
            "invalid_auth", "not_authed", "token_revoked", "account_inactive" ->
                "Slack rejected the bot token. Check it in Settings."
            "channel_not_found", "invalid_channel" ->
                "That Slack channel ID was not found."
            "not_in_channel" ->
                "Invite the bot to that public channel, or add the channels:join scope."
            "missing_scope" ->
                "The Slack app needs the files:write scope."
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

    fun postChapter(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
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
        return name
    }

    /* Public channels only — the same limit Cursor's Slack trigger has.
       Missing the join scope is ordinary; the complete call then says so. */
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
