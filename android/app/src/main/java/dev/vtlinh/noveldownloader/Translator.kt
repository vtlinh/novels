package dev.vtlinh.noveldownloader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/* Native Vietnamese->English translation via the Anthropic Messages API.
   No proxy is needed (native apps have no CORS). Chapters are translated in
   sequential bundles by one Opus call each; the call also returns new proper-
   name renderings, which carry forward in a glossary (stored in the app DB)
   so names stay consistent across the whole novel. Translated files go into a
   "translated/" subfolder with the same filenames, so re-runs skip what's
   done. Uses the standard (non-batch) API — simpler and robust inside the
   foreground service. */
class Translator(
    private val context: Context,
    private val apiKey: String,
    private val log: (String) -> Unit,
    private val status: (String) -> Unit,
) {
    companion object {
        private const val OPUS = "claude-opus-4-8"
        private const val BUNDLE_CHARS = 40000   // ~ source chars packed per bundle
        private const val MAX_TOKENS = 64000
        private val SYSTEM =
            "You are translating a Vietnamese web novel into natural, fluent English. You receive a bundle of consecutive chapters to translate TOGETHER as one continuous work, plus a glossary of proper-name renderings already fixed earlier.\n" +
                "Decide one English rendering for every proper name and reuse the glossary exactly. Romanize Sino-Vietnamese person names in standard pinyin-style English (e.g. \"Tiêu Viêm\" -> \"Xiao Yan\"); translate meaningful place/organization/technique names.\n" +
                "Translate EVERYTHING each chapter contains, including its heading line (e.g. \"Chương 5: Tiêu Viêm\" -> \"Chapter 5: Xiao Yan\") as the first line, then the prose, one paragraph per line separated by a single newline.\n" +
                "Return ONLY a JSON object, no prose around it, of the form: {\"chapters\":[{\"n\":<the 1-based number from the \"=== CHAPTER n ===\" delimiter>,\"text\":\"<full English translation>\"}],\"names\":[{\"vietnamese\":\"<bare name>\",\"english\":\"<rendering used>\"}]}. In `names` include ONLY names NEW to the glossary; each side is a bare name only."
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    /* abort an in-flight translation request so Stop doesn't wait it out */
    fun cancel() { try { client.dispatcher.cancelAll() } catch (e: Exception) {} }

    private fun callModel(user: String): String? = try {
        val body = JSONObject()
            .put("model", OPUS)
            .put("max_tokens", MAX_TOKENS)
            .put("system", SYSTEM)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", user)),
            )
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) { log("Translate API ${r.code}: ${text.take(200)}"); return null }
            val content = JSONObject(text).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                if (b.optString("type") == "text") sb.append(b.optString("text"))
            }
            sb.toString().trim()
        }
    } catch (e: Exception) {
        log("Translate API error: ${e.message}")
        null
    }

    private fun readText(uri: String): String? = try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    } catch (e: Exception) { null }

    private fun writeTranslated(tdir: DocumentFile, name: String, text: String): Boolean = try {
        val f = tdir.createFile("text/plain", name) ?: return false
        context.contentResolver.openOutputStream(f.uri)?.use {
            it.write(Extractor.singleNewlines(text).toByteArray(Charsets.UTF_8))
        } ?: return false
        true
    } catch (e: Exception) { false }

    private fun chunk(items: List<Pair<String, String>>): List<List<Pair<String, String>>> {
        val groups = ArrayList<MutableList<Pair<String, String>>>()
        var cur = ArrayList<Pair<String, String>>()
        var n = 0
        for (it in items) {
            val t = it.second.length
            if (cur.isNotEmpty() && n + t > BUNDLE_CHARS) { groups.add(cur); cur = ArrayList(); n = 0 }
            cur.add(it); n += t
        }
        if (cur.isNotEmpty()) groups.add(cur)
        return groups
    }

    /* translate every downloaded chapter not already present in translated/ */
    suspend fun translate(
        dir: DocumentFile,
        store: DownloadStore,
        folder: String,
        slug: String,
        filenames: List<String>,
        isStopped: () -> Boolean,
    ) = withContext(Dispatchers.IO) {
        val tdir = dir.findFile("translated")?.takeIf { it.isDirectory }
            ?: dir.createDirectory("translated")
        if (tdir == null) { log("Could not create translated/ folder"); return@withContext }

        val done = tdir.listFiles().mapNotNull { it.name }.toHashSet()
        val uris = store.get(folder, slug)   // filename -> source document URI
        val pending = filenames.filter { it !in done }.mapNotNull { fn ->
            val uri = uris[fn] ?: return@mapNotNull null
            val text = readText(uri) ?: return@mapNotNull null
            Pair(fn, text)
        }
        if (pending.isEmpty()) { log("Translation: everything already translated."); return@withContext }

        val glossary = store.getNames(folder, slug)
        val bundles = chunk(pending)
        log("Translating ${pending.size} chapter(s) with $OPUS in ${bundles.size} bundle(s)")

        var saved = 0
        var failed = 0
        for ((bi, bundle) in bundles.withIndex()) {
            if (isStopped()) break
            status("Translating bundle ${bi + 1}/${bundles.size}…")
            val blob = bundle.mapIndexed { i, (_, txt) -> "=== CHAPTER ${i + 1} ===\n$txt" }
                .joinToString("\n\n")
            val glossaryJson = JSONArray().apply {
                for ((vi, en) in glossary) put(JSONArray().put(vi).put(en))
            }.toString()
            val user = "GLOSSARY of proper-name renderings already fixed, as [Vietnamese, English] pairs — reuse each EXACTLY:\n" +
                glossaryJson +
                "\n\nTranslate these ${bundle.size} chapter(s) together, one identical rendering per proper name across all of them. Set each chapter's `n` to its \"=== CHAPTER n ===\" number.\n\n" +
                blob

            val resp = callModel(user)
            if (resp == null) { failed += bundle.size; continue }
            val parsed = try {
                JSONObject(resp.substring(resp.indexOf('{'), resp.lastIndexOf('}') + 1))
            } catch (e: Exception) {
                log("bundle ${bi + 1}: could not parse response"); failed += bundle.size; continue
            }

            val names = parsed.optJSONArray("names")
            if (names != null) {
                val newPairs = ArrayList<Pair<String, String>>()
                for (i in 0 until names.length()) {
                    val o = names.optJSONObject(i) ?: continue
                    val vi = o.optString("vietnamese").trim()
                    val en = o.optString("english").trim()
                    if (vi.isNotEmpty() && en.isNotEmpty() && !glossary.containsKey(vi)) {
                        glossary[vi] = en; newPairs.add(vi to en)
                    }
                }
                if (newPairs.isNotEmpty()) store.addNames(folder, slug, newPairs)
            }

            val chapters = parsed.optJSONArray("chapters")
            if (chapters != null) {
                for (i in 0 until chapters.length()) {
                    val o = chapters.optJSONObject(i) ?: continue
                    val n = o.optInt("n", -1)
                    val text = o.optString("text")
                    val ch = bundle.getOrNull(n - 1)
                    if (ch == null || text.isBlank() || done.contains(ch.first)) continue
                    if (writeTranslated(tdir, ch.first, text)) {
                        done.add(ch.first); saved++
                        log("saved  translated/${ch.first}")
                    } else {
                        failed++
                    }
                }
            }
            log("Bundle ${bi + 1}/${bundles.size}: ${glossary.size} names known")
        }
        log("Translation ${if (isStopped()) "stopped" else "done"}: $saved ok, $failed failed" +
            (if (failed > 0 || isStopped()) " (re-run to resume)" else ""))
    }
}
