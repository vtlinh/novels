package dev.vtlinh.noveldownloader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/* Native Vietnamese->English translation via the Anthropic Message Batches API.
   No proxy is needed (native apps have no CORS). Chapters are translated in
   sequential bundles by one Opus request each; the request also returns new
   proper-name renderings, which carry forward in a glossary (stored in the app
   DB) so names stay consistent across the whole novel. Translated files go into
   a "translated/" subfolder with the same filenames, so re-runs skip what's
   done.

   Every submitted batch is recorded in the app DB before it is polled, so a
   batch orphaned by an app restart (or a Stop) is recovered on the next run
   instead of being paid for twice — Anthropic keeps batch results for 29 days. */
class Translator(
    private val context: Context,
    private val apiKey: String,
    private val log: (String) -> Unit,
    private val status: (String) -> Unit,
) {
    companion object {
        private const val OPUS = "claude-opus-4-8"
        private const val SONNET = "claude-sonnet-5"   // novel-title translation (short, cheap)
        private const val EFFORT = "medium"
        private const val MAX_TOKENS = 128000        // Opus 4.8 max output; a bundle's whole translation must fit under this
        private const val BUNDLE_TOKEN_BUDGET = 80000 // estimated output tokens packed per bundle (headroom below MAX_TOKENS)
        private const val MAX_RETRIES = 4
        private const val POLL_MS = 10_000L          // gap between batch status checks
        private const val RECOVER_MAX_TRIES = 5

        /* Vietnamese source ~2 chars/token; English output runs below this, so
           using it as the per-chapter output estimate over-counts (safe). */
        private fun estTokens(s: String) = (s.length + 1) / 2

        private val SYSTEM =
            "You are translating a Vietnamese web novel into natural, fluent English. You receive a bundle of consecutive chapters to translate TOGETHER as one continuous work — not as independent, isolated chapters — plus a glossary of proper-name renderings already fixed in earlier chapters.\n" +
                "Before writing any translation, read ALL the chapters in the bundle and decide one English rendering for every proper name that appears anywhere in them (reusing the glossary for names already in it). Then translate every chapter using that single shared set of renderings.\n" +
                "In your `chapters` output, `n` is the number from that chapter's `=== CHAPTER n ===` delimiter — its 1-based position in this bundle — NEVER the chapter number that appears inside the heading text.\n" +
                "Translation rules:\n" +
                "- Translate each labelled chapter into natural, fluent English, EVERYTHING it contains — including its first line, the chapter heading (e.g. \"Chương 5: Tiêu Viêm\" -> \"Chapter 5: Xiao Yan\"). Keep that heading as the translated chapter's first line, then the prose. Put each paragraph on its own line, separated by a SINGLE newline — never insert blank lines. Do not add or drop any heading, note, or commentary of your own — translate exactly what is given.\n" +
                "- NAME CONSISTENCY IS CRITICAL. Every proper name — characters, nicknames, places, sects/organizations, techniques, titles, brands — must use ONE identical English rendering in EVERY chapter it appears in across this bundle. If a name is in the glossary, reuse that rendering EXACTLY; for a name not in the glossary, commit to the single best rendering once and apply it identically everywhere in the bundle.\n" +
                "- Romanize person names in the conventional form for the name's likely origin: most are Sino-Vietnamese, so use the standard pinyin-style English rendering (e.g. \"Tiêu Viêm\" -> \"Xiao Yan\", \"Đấu Khí\" -> \"Dou Qi\"), not a diacritic-stripped copy. Translate meaningful place/organization/technique names.\n" +
                "Name-list rules (the `names` array):\n" +
                "- Return ONLY names that are NEW to the glossary — proper names that appear in these chapters but are NOT already listed in the glossary above. List each new name once, as a {vietnamese, english} pair whose english is exactly the rendering you used in the translation.\n" +
                "- Each pair is a NAME ONLY on both sides. The vietnamese value is the bare name exactly as written — a noun phrase, never a clause or sentence containing it. The english is the name as it appears in the translation, nothing else: no surrounding words, no parenthetical notes, and no \"/\" alternatives.\n" +
                "Return your result via the required structured JSON object: {\"chapters\":[{\"n\",\"text\"}],\"names\":[{\"vietnamese\",\"english\"}]}."

        private val BUNDLE_SCHEMA = JSONObject(
            """
            {"type":"object","properties":{
              "chapters":{"type":"array","items":{"type":"object","properties":{
                "n":{"type":"integer","description":"The number from this chapter's '=== CHAPTER n ===' delimiter — its 1-based position in the bundle. NEVER the chapter number in the heading text."},
                "text":{"type":"string","description":"This chapter's complete English translation."}
              },"required":["n","text"],"additionalProperties":false}},
              "names":{"type":"array","items":{"type":"object","properties":{
                "vietnamese":{"type":"string"},"english":{"type":"string"}
              },"required":["vietnamese","english"],"additionalProperties":false}}
            },"required":["chapters","names"],"additionalProperties":false}
            """.trimIndent(),
        )
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    private class ApiException(val code: Int, body: String) :
        Exception("Claude API $code: ${body.take(300)}")

    private class StoppedException : Exception()

    /* abort an in-flight request so Stop doesn't wait it out */
    fun cancel() { try { client.dispatcher.cancelAll() } catch (e: Exception) {} }

    /* ---- raw HTTP with retry/backoff ----
       `ignoreStop` marks the post-Stop calls (batch cancel, results collection)
       that must complete to keep paid-for work — they never abort early. */
    private suspend fun call(
        method: String,
        path: String,
        body: JSONObject?,
        isStopped: () -> Boolean,
        ignoreStop: Boolean = false,
    ): String {
        var lastErr: Exception? = null
        for (i in 0 until MAX_RETRIES) {
            if (!ignoreStop && isStopped()) throw StoppedException()
            try {
                val rb = if (method == "POST") {
                    (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType())
                } else {
                    null
                }
                val req = Request.Builder()
                    .url("https://api.anthropic.com$path")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .method(method, rb)
                    .build()
                client.newCall(req).execute().use { r ->
                    val text = r.body?.string() ?: ""
                    if (r.isSuccessful) return text
                    if (r.code != 429 && r.code < 500) throw ApiException(r.code, text) // client error — don't retry
                    lastErr = ApiException(r.code, text)
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: IOException) {
                if (!ignoreStop && isStopped()) throw StoppedException()
                lastErr = e
            }
            backoff(i, isStopped, ignoreStop)
        }
        throw lastErr ?: IOException("request failed")
    }

    private suspend fun backoff(i: Int, isStopped: () -> Boolean, ignoreStop: Boolean) {
        val total = 1000L * (1L shl i)
        var slept = 0L
        while (slept < total) {
            if (!ignoreStop && isStopped()) return
            delay(minOf(500L, total - slept))
            slept += 500L
        }
    }

    private suspend fun sleepPoll(ms: Long, isStopped: () -> Boolean, ignoreStop: Boolean) {
        var slept = 0L
        while (slept < ms) {
            if (!ignoreStop && isStopped()) return
            delay(minOf(500L, ms - slept))
            slept += 500L
        }
    }

    /* ---- Message Batches ----
       Submit a batch, record it for recovery, poll until it ends, then fetch
       results keyed by custom_id. On Stop the batch is canceled server-side and
       whatever finished first is still collected. Returns custom_id -> message. */
    private suspend fun runBatch(
        requests: JSONArray,
        label: String,
        folder: String,
        slug: String,
        files: List<String>,
        isStopped: () -> Boolean,
        wantTitle: String? = null,
    ): Map<String, JSONObject> {
        if (isStopped()) throw StoppedException()
        val submitted = JSONObject(call("POST", "/v1/messages/batches", JSONObject().put("requests", requests), isStopped))
        val id = submitted.getString("id")
        store.addPending(folder, slug, id, files, System.currentTimeMillis(), wantTitle)
        log("[$label] batch submitted (${requests.length()} request(s)); polling…")

        var cancelSent = false
        while (true) {
            if (isStopped() && !cancelSent) {
                cancelSent = true
                try {
                    call("POST", "/v1/messages/batches/$id/cancel", JSONObject(), isStopped, ignoreStop = true)
                    log("[$label] batch canceled — keeping results that already finished")
                } catch (e: Exception) {
                    log("[$label] cancel failed — ${e.message}")
                }
            }
            val b = JSONObject(call("GET", "/v1/messages/batches/$id", null, isStopped, ignoreStop = true))
            val c = b.optJSONObject("request_counts")
            if (c != null) {
                status("[$label] processing: ${c.optInt("processing")}  ok: ${c.optInt("succeeded")}  err: ${c.optInt("errored")}")
            }
            if (b.optString("processing_status") == "ended") break
            sleepPoll(if (cancelSent) 2000L else POLL_MS, isStopped, ignoreStop = true)
        }

        val results = fetchResults(id, label, isStopped)
        store.removePending(id)
        return results
    }

    private suspend fun fetchResults(
        id: String,
        label: String,
        isStopped: () -> Boolean,
    ): Map<String, JSONObject> {
        val out = HashMap<String, JSONObject>()
        val text = call("GET", "/v1/messages/batches/$id/results", null, isStopped, ignoreStop = true)
        for (line in text.split("\n")) {
            if (line.isBlank()) continue
            val item = try { JSONObject(line) } catch (e: Exception) { continue }
            val cid = item.optString("custom_id")
            val result = item.optJSONObject("result") ?: continue
            val type = result.optString("type")
            if (type == "succeeded") {
                result.optJSONObject("message")?.let { out[cid] = it }
            } else if (!(type == "canceled" && isStopped())) {
                log("[$label] $cid: $type")
            }
        }
        return out
    }

    /* concatenated text of a message's content blocks */
    private fun messageText(msg: JSONObject): String {
        val content = msg.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString().trim()
    }

    private fun parseBundle(raw: String): JSONObject? = try {
        JSONObject(raw)
    } catch (e: Exception) {
        try {
            JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
        } catch (e2: Exception) {
            null
        }
    }

    private fun readText(uri: String): String? = try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    } catch (e: Exception) { null }

    private fun writeTranslated(tdir: DocumentFile, name: String, text: String): Boolean {
        return try {
            val f = tdir.createFile("text/plain", name) ?: return false
            context.contentResolver.openOutputStream(f.uri)?.use {
                it.write(Extractor.singleNewlines(text).toByteArray(Charsets.UTF_8))
            } ?: return false
            true
        } catch (e: Exception) { false }
    }

    /* Split chapters into sequential bundles whose combined output estimate
       stays within the token budget, preserving order. A single oversized
       chapter still forms its own bundle. */
    private fun chunk(items: List<Pair<String, String>>): ArrayDeque<List<Pair<String, String>>> {
        val groups = ArrayDeque<List<Pair<String, String>>>()
        var cur = ArrayList<Pair<String, String>>()
        var tok = 0
        for (it in items) {
            val t = estTokens(it.second)
            if (cur.isNotEmpty() && tok + t > BUNDLE_TOKEN_BUDGET) {
                groups.addLast(cur); cur = ArrayList(); tok = 0
            }
            cur.add(it); tok += t
        }
        if (cur.isNotEmpty()) groups.addLast(cur)
        return groups
    }

    /* build the Opus bundle request (custom_id "bundle") */
    private fun bundleRequest(
        bundle: List<Pair<String, String>>,
        glossary: Map<String, String>,
    ): JSONArray {
        val blob = bundle.mapIndexed { i, (_, txt) -> "=== CHAPTER ${i + 1} ===\n$txt" }
            .joinToString("\n\n")
        val glossaryJson = JSONArray().apply {
            for ((vi, en) in glossary) put(JSONArray().put(vi).put(en))
        }.toString()
        val user = "GLOSSARY of proper-name renderings already fixed, as [Vietnamese, English] pairs — reuse each EXACTLY:\n" +
            glossaryJson +
            "\n\nTranslate these ${bundle.size} chapter(s) together, one identical rendering per proper name across all of them. Set each chapter's `n` to its \"=== CHAPTER n ===\" number (1-${bundle.size}).\n\n" +
            blob
        val params = JSONObject()
            .put("model", OPUS)
            .put("max_tokens", MAX_TOKENS)
            .put("system", SYSTEM)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
            .put(
                "output_config",
                JSONObject()
                    .put("effort", EFFORT)
                    .put("format", JSONObject().put("type", "json_schema").put("schema", BUNDLE_SCHEMA)),
            )
        return JSONArray().put(JSONObject().put("custom_id", "bundle").put("params", params))
    }

    /* merge model-returned name pairs into the glossary + DB (existing win) */
    private fun mergeNames(
        parsed: JSONObject,
        glossary: LinkedHashMap<String, String>,
        store: DownloadStore,
        folder: String,
        slug: String,
    ) {
        val names = parsed.optJSONArray("names") ?: return
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

    private lateinit var store: DownloadStore

    /* Render the novel-folder name "English (Vietnamese)" via a Sonnet batch,
       BEFORE the folder is created, so the download saves straight into the
       English-named folder. Cached in the DB, so it's translated once. A title
       batch orphaned by an app restart is recovered instead of resubmitted.
       Returns the sanitized folder name, or null if it couldn't be produced
       (caller falls back to the Vietnamese name). */
    suspend fun ensureEnglishTitle(
        vietTitle: String,
        store: DownloadStore,
        folder: String,
        slug: String,
        isStopped: () -> Boolean,
    ): String? = withContext(Dispatchers.IO) {
        this@Translator.store = store
        store.getTitle(folder, slug)?.let { return@withContext it }

        val now = System.currentTimeMillis()
        store.prunePending(now)

        /* recover an orphaned title batch from a previous session first */
        val orphan = store.pendingFor(folder, slug, now).firstOrNull { it.wantTitle != null }
        if (orphan != null) {
            try {
                val eng = collectTitle(orphan.batchId, isStopped)
                if (!eng.isNullOrBlank()) {
                    val english = Extractor.sanitize("$eng (${orphan.wantTitle})")
                    store.setTitle(folder, slug, english)
                    store.removePending(orphan.batchId)
                    log("Title recovered — folder \"$english\"")
                    return@withContext english
                }
            } catch (e: StoppedException) {
                return@withContext null
            } catch (e: Exception) {
                log("Title batch recovery failed — ${e.message}")
            }
        }

        /* submit a fresh title batch (Sonnet, plain text) */
        try {
            val params = JSONObject()
                .put("model", SONNET)
                .put("max_tokens", 200)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject().put("role", "user").put(
                            "content",
                            "Translate this Vietnamese novel title to natural English. Reply with ONLY the translated title, nothing else:\n" + vietTitle,
                        ),
                    ),
                )
            val reqs = JSONArray().put(JSONObject().put("custom_id", "title").put("params", params))
            val submitted = JSONObject(call("POST", "/v1/messages/batches", JSONObject().put("requests", reqs), isStopped))
            val id = submitted.getString("id")
            store.addPending(folder, slug, id, emptyList(), now, vietTitle)
            log("[title] batch submitted; translating title…")
            val eng = collectTitle(id, isStopped)
            store.removePending(id)
            if (eng.isNullOrBlank()) return@withContext null
            val english = Extractor.sanitize("$eng (${vietTitle})")
            store.setTitle(folder, slug, english)
            english
        } catch (e: StoppedException) {
            null
        } catch (e: Exception) {
            log("Title translation error — ${e.message}")
            null
        }
    }

    /* poll a one-request "title" batch to completion and return its text */
    private suspend fun collectTitle(id: String, isStopped: () -> Boolean): String? {
        while (true) {
            if (isStopped()) throw StoppedException()
            val b = JSONObject(call("GET", "/v1/messages/batches/$id", null, isStopped, ignoreStop = true))
            if (b.optString("processing_status") == "ended") break
            sleepPoll(POLL_MS, isStopped, ignoreStop = false)
        }
        val results = fetchResults(id, "title", isStopped)
        val msg = results["title"] ?: return null
        return messageText(msg).ifBlank { null }
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
        this@Translator.store = store
        val tdir = dir.findFile("translated")?.takeIf { it.isDirectory }
            ?: dir.createDirectory("translated")
        if (tdir == null) { log("Could not create translated/ folder"); return@withContext }

        val glossary = store.getNames(folder, slug)

        try {
            recoverPendingBatches(tdir, store, folder, slug, glossary, isStopped)
        } catch (e: StoppedException) {
            log("Translation stopped."); return@withContext
        } catch (e: Exception) {
            log("Batch recovery error: ${e.message}")
        }

        val done = tdir.listFiles().mapNotNull { it.name }.toHashSet()
        val uris = store.get(folder, slug)   // filename -> source document URI
        val pending = filenames.filter { it !in done }.mapNotNull { fn ->
            val uri = uris[fn] ?: return@mapNotNull null
            val text = readText(uri) ?: return@mapNotNull null
            Pair(fn, text)
        }
        if (pending.isEmpty()) { log("Translation: everything already translated."); return@withContext }

        val queue = chunk(pending)
        log("Translating ${pending.size} chapter(s) with $OPUS (Batches API) in ${queue.size} bundle(s)")

        var saved = 0
        var failed = 0
        var bundleNo = 0
        try {
            while (queue.isNotEmpty()) {
                if (isStopped()) break
                val bundle = queue.removeFirst()
                bundleNo++
                status("Translating bundle $bundleNo (${bundle.size} chapter(s))…")

                val results = runBatch(
                    bundleRequest(bundle, glossary), "translate $bundleNo",
                    folder, slug, bundle.map { it.first }, isStopped,
                )
                val msg = results["bundle"]

                /* a truncated bundle is unparseable structured JSON — split and
                   retry the halves so its total output fits under the ceiling */
                if (msg == null || msg.optString("stop_reason") == "max_tokens") {
                    if (msg != null && bundle.size > 1) {
                        val mid = (bundle.size + 1) / 2
                        queue.addFirst(bundle.subList(mid, bundle.size).toList())
                        queue.addFirst(bundle.subList(0, mid).toList())
                        log("! bundle $bundleNo: output truncated; splitting ${bundle.size} → $mid+${bundle.size - mid} and retrying")
                    } else {
                        failed += bundle.size
                        log("! bundle $bundleNo: ${if (msg != null) "truncated even for one chapter" else "no result"}; not saved")
                    }
                    continue
                }

                val parsed = parseBundle(messageText(msg))
                if (parsed == null) {
                    log("! bundle $bundleNo: could not parse response"); failed += bundle.size; continue
                }

                mergeNames(parsed, glossary, store, folder, slug)

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
                log("Bundle $bundleNo: ${glossary.size} names known")
            }
        } catch (e: StoppedException) {
            log("Translation stopped — in-flight batch left for recovery on the next run.")
        }
        log("Translation ${if (isStopped()) "stopped" else "done"}: $saved ok, $failed failed" +
            (if (failed > 0 || isStopped()) " (re-run to resume)" else ""))
    }

    /* Collect batches submitted by a previous session that were never collected
       (app killed, phone slept, crash, or a Stop): wait for each to end, fetch
       its results — Anthropic keeps them 29 days — and save them exactly as the
       original run would have, instead of paying to redo the work. */
    private suspend fun recoverPendingBatches(
        tdir: DocumentFile,
        store: DownloadStore,
        folder: String,
        slug: String,
        glossary: LinkedHashMap<String, String>,
        isStopped: () -> Boolean,
    ) {
        val now = System.currentTimeMillis()
        store.prunePending(now)
        /* title batches (wantTitle set) are recovered up front by ensureEnglishTitle */
        val mine = store.pendingFor(folder, slug, now).filter { it.wantTitle == null }
        if (mine.isEmpty()) return
        val done = tdir.listFiles().mapNotNull { it.name }.toHashSet()
        log("Found ${mine.size} unfinished batch(es) from a previous session — recovering…")
        for (rec in mine) {
            if (isStopped()) throw StoppedException()
            try {
                while (true) {
                    val b = JSONObject(call("GET", "/v1/messages/batches/${rec.batchId}", null, isStopped, ignoreStop = true))
                    val c = b.optJSONObject("request_counts")
                    if (c != null) {
                        status("[recover] processing: ${c.optInt("processing")}  ok: ${c.optInt("succeeded")}  err: ${c.optInt("errored")}")
                    }
                    if (b.optString("processing_status") == "ended") break
                    sleepPoll(POLL_MS, isStopped, ignoreStop = true)
                }
                val results = fetchResults(rec.batchId, "recover", isStopped)
                val msg = results["bundle"]
                if (msg != null && msg.optString("stop_reason") != "max_tokens") {
                    val parsed = parseBundle(messageText(msg))
                    if (parsed != null) {
                        mergeNames(parsed, glossary, store, folder, slug)
                        var n = 0
                        val chapters = parsed.optJSONArray("chapters")
                        if (chapters != null) {
                            for (i in 0 until chapters.length()) {
                                val o = chapters.optJSONObject(i) ?: continue
                                val idx = o.optInt("n", -1) - 1
                                val text = o.optString("text")
                                val fn = rec.files.getOrNull(idx) ?: continue
                                if (text.isBlank() || done.contains(fn)) continue
                                if (writeTranslated(tdir, fn, text)) { done.add(fn); n++; log("saved  translated/$fn (recovered)") }
                            }
                        }
                        log("Recovered bundle: $n chapter(s)")
                    }
                }
                store.removePending(rec.batchId)
            } catch (e: StoppedException) {
                throw e
            } catch (e: ApiException) {
                if (e.code in 400..499) {
                    log("! batch …${rec.batchId.takeLast(8)} is no longer available (${e.code}); dropping it")
                    store.removePending(rec.batchId)
                } else {
                    retryLater(store, rec, e.message)
                }
            } catch (e: Exception) {
                retryLater(store, rec, e.message)
            }
        }
    }

    private fun retryLater(store: DownloadStore, rec: PendingBatch, why: String?) {
        val tries = store.bumpPendingTries(rec.batchId)
        if (tries >= RECOVER_MAX_TRIES) {
            log("! batch …${rec.batchId.takeLast(8)} failed recovery $tries times ($why); giving up")
            store.removePending(rec.batchId)
        } else {
            log("! could not recover batch …${rec.batchId.takeLast(8)} ($why); will retry next run ($tries/$RECOVER_MAX_TRIES)")
        }
    }
}
