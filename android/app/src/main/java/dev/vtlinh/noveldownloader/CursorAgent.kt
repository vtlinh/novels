package dev.vtlinh.noveldownloader

import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/* Talks to the Cursor Cloud Agents API with the user's key.

   There is no completions endpoint. A summarize call starts a no-repo
   agent on Composer; an image call starts another on an image-capable
   model and pulls the first artifacts/ picture. Both spend the same
   Ultra pools as the IDE. */
class CursorAgent(private val apiKey: String) {

    class ApiException(val code: Int, detail: String) : IOException(detail)

    companion object {
        private const val HOST = "https://api.cursor.com"
        private const val POLL_MS = 5_000L
        private const val MAX_WAIT_MS = 8L * 60_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private var summaryModel: String? = null
    private var imageModel: String? = null

    suspend fun summarize(title: String, chapter: String): Scenes.Scene {
        refreshModels()
        val result = runAgent(
            Scenes.summaryPrompt(title, chapter),
            summaryModel ?: Scenes.DEFAULT_SUMMARY_MODEL,
            "Chapter summary",
        )
        return Scenes.parse(result)
            ?: throw IOException("Cursor replied, but it was not a chapter summary.")
    }

    suspend fun illustrate(title: String, scene: Scenes.Scene): ByteArray {
        refreshModels()
        val created = createAgent(
            Scenes.imagePrompt(title, scene),
            imageModel ?: Scenes.DEFAULT_IMAGE_MODEL,
            "Chapter image",
        )
        awaitRun(created.first, created.second)
        return firstImage(created.first)
            ?: throw IOException("The agent finished but left no image in artifacts/.")
    }

    /* Resume a run started earlier (the work file on disk). */
    suspend fun finishSummary(agentId: String, runId: String): Scenes.Scene {
        val result = awaitRun(agentId, runId)
        return Scenes.parse(result)
            ?: throw IOException("Cursor replied, but it was not a chapter summary.")
    }

    suspend fun finishImage(agentId: String, runId: String): ByteArray {
        awaitRun(agentId, runId)
        return firstImage(agentId)
            ?: throw IOException("The agent finished but left no image in artifacts/.")
    }

    suspend fun startSummary(title: String, chapter: String): Scenes.Work {
        refreshModels()
        val ids = createAgent(
            Scenes.summaryPrompt(title, chapter),
            summaryModel ?: Scenes.DEFAULT_SUMMARY_MODEL,
            "Chapter summary",
        )
        return Scenes.Work(Scenes.KIND_SUMMARY, ids.first, ids.second)
    }

    suspend fun startImage(title: String, scene: Scenes.Scene): Scenes.Work {
        refreshModels()
        val ids = createAgent(
            Scenes.imagePrompt(title, scene),
            imageModel ?: Scenes.DEFAULT_IMAGE_MODEL,
            "Chapter image",
        )
        return Scenes.Work(Scenes.KIND_IMAGE, ids.first, ids.second)
    }

    private fun refreshModels() {
        if (summaryModel != null && imageModel != null) return
        val ids = try { listModelIds() } catch (e: Exception) { emptyList() }
        summaryModel = Scenes.pickSummaryModel(ids)
        imageModel = Scenes.pickImageModel(ids)
    }

    private fun listModelIds(): List<String> {
        val body = call("GET", "/v1/models")
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        val out = ArrayList<String>(items.length())
        for (i in 0 until items.length()) {
            val id = items.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isNotEmpty()) out.add(id)
        }
        return out
    }

    /* A 400 that names the model is retried without one — Cursor then
       uses the account default rather than failing the tap. */
    private fun createAgent(prompt: String, model: String, name: String): Pair<String, String> {
        fun body(withModel: Boolean): JSONObject {
            val o = JSONObject()
            o.put("name", name.take(100))
            o.put("prompt", JSONObject().put("text", prompt))
            if (withModel && model.isNotBlank()) {
                o.put("model", JSONObject().put("id", model))
            }
            return o
        }
        val raw = try {
            call("POST", "/v1/agents", body(true))
        } catch (e: ApiException) {
            if (e.code == 400 && model.isNotBlank()) call("POST", "/v1/agents", body(false))
            else throw e
        }
        val json = JSONObject(raw)
        val agentId = json.optJSONObject("agent")?.optString("id").orEmpty()
            .ifEmpty { json.optString("id") }
        val runId = json.optJSONObject("run")?.optString("id").orEmpty()
            .ifEmpty { json.optString("runId") }
        if (agentId.isEmpty() || runId.isEmpty()) {
            throw IOException("Cursor started an agent but returned no ids.")
        }
        return Pair(agentId, runId)
    }

    private suspend fun runAgent(prompt: String, model: String, name: String): String {
        val ids = createAgent(prompt, model, name)
        return awaitRun(ids.first, ids.second)
    }

    private suspend fun awaitRun(agentId: String, runId: String): String {
        val started = System.currentTimeMillis()
        while (true) {
            val raw = call("GET", "/v1/agents/$agentId/runs/$runId")
            val json = JSONObject(raw)
            when (json.optString("status").uppercase()) {
                "FINISHED" -> return json.optString("result")
                "ERROR", "CANCELLED", "CANCELED", "EXPIRED" -> {
                    val msg = json.optString("result").ifBlank { json.optString("status") }
                    throw IOException("Cursor agent ${json.optString("status").lowercase()}: $msg")
                }
            }
            if (System.currentTimeMillis() - started > MAX_WAIT_MS) {
                throw IOException("Cursor took more than 8 minutes — try again.")
            }
            delay(POLL_MS)
        }
    }

    private fun firstImage(agentId: String): ByteArray? {
        val listed = call("GET", "/v1/agents/$agentId/artifacts")
        val items = JSONObject(listed).optJSONArray("items") ?: JSONArray()
        val path = (0 until items.length())
            .mapNotNull { items.optJSONObject(it)?.optString("path") }
            .firstOrNull { p ->
                val n = p.lowercase()
                n.endsWith(".png") || n.endsWith(".jpg") ||
                    n.endsWith(".jpeg") || n.endsWith(".webp")
            } ?: return null
        val url = JSONObject(
            call(
                "GET",
                "/v1/agents/$agentId/artifacts/download",
                query = mapOf("path" to path),
            ),
        ).optString("url")
        if (url.isBlank()) return null
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, r.body?.string() ?: "")
            return r.body?.bytes()
        }
    }

    private fun call(
        method: String,
        path: String,
        json: JSONObject? = null,
        query: Map<String, String> = emptyMap(),
    ): String {
        val url = (HOST + path).toHttpUrl().newBuilder().also { b ->
            for ((k, v) in query) b.addQueryParameter(k, v)
        }.build()
        val rb = if (method == "GET") {
            null
        } else {
            (json?.toString() ?: "{}").toRequestBody(JSON)
        }
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .method(method, rb)
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (r.isSuccessful) return text
            throw ApiException(r.code, describe(r.code, text))
        }
    }

    private fun describe(code: Int, body: String): String {
        val msg = try {
            JSONObject(body).optString("message").ifBlank {
                JSONObject(body).optString("error")
            }
        } catch (e: Exception) {
            ""
        }
        val bit = msg.ifBlank { body.take(240) }
        return when (code) {
            401, 403 -> "Cursor rejected the API key. Check it in Settings."
            else -> if (bit.isBlank()) "Cursor error $code" else "Cursor error $code: $bit"
        }
    }
}
