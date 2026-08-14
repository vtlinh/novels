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
        private const val OPUS = "claude-opus-5"
        private const val SONNET = "claude-sonnet-5"   // novel-title translation (short, cheap)
        private const val EFFORT = "medium"
        private const val MAX_TOKENS = 128000        // Opus 5 max output; a bundle's whole translation must fit under this
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

    /* Set while a batch-create POST is on the wire. Creating a batch is the
       one call whose outcome we cannot re-derive: if the server took it and we
       never read the id, that bundle is billed in full and no record exists
       for recovery to find. */
    @Volatile private var creating = false

    /* Abort an in-flight request so Stop doesn't wait it out — except a create.
       Stop used to kill that too, so stopping during the seconds a large
       bundle spends uploading orphaned a paid batch outright. Everything else
       here is a read, or a cancel that the server will honour anyway. */
    fun cancel() {
        if (creating) return
        try { client.dispatcher.cancelAll() } catch (e: Exception) {}
    }

    /* ---- raw HTTP with retry/backoff ----
       `ignoreStop` marks the post-Stop calls (batch cancel, results collection)
       that must complete to keep paid-for work — they never abort early. */
    private suspend fun call(
        method: String,
        path: String,
        body: JSONObject?,
        isStopped: () -> Boolean,
        ignoreStop: Boolean = false,
        /* A dropped connection tells us nothing about whether the server acted.
           That's fine to retry for a read, but re-sending a batch CREATE bills
           the whole bundle again for a batch we'll never hold an id for. */
        retryOnDrop: Boolean = true,
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
                    /* A 5xx from a gateway is exactly as ambiguous as a dropped
                       socket: the backend may already have taken the batch.
                       Retrying a create then bills the whole bundle again for
                       something we will never hold an id for. 429 is safe —
                       nothing was accepted — so it still retries. */
                    if (!retryOnDrop && r.code != 429) throw ApiException(r.code, text)
                    lastErr = ApiException(r.code, text)
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: IOException) {
                if (!ignoreStop && isStopped()) throw StoppedException()
                if (!retryOnDrop) throw e
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
        urls: List<String>,
        isStopped: () -> Boolean,
        wantTitle: String? = null,
    ): BatchRun {
        if (isStopped()) throw StoppedException()
        /* Not retried on a dropped connection. Creating a batch is not
           idempotent and the API offers no idempotency key, so a response lost
           after the server accepted it would be re-sent as a SECOND paid batch
           that nothing ever polls. One orphan is bad; three is worse. */
        val submitted = JSONObject(
            try {
                creating = true
                call("POST", "/v1/messages/batches", JSONObject().put("requests", requests), isStopped, retryOnDrop = false)
            } finally { creating = false },
        )
        val id = submitted.getString("id")
        store.addPending(folder, slug, id, files, urls, System.currentTimeMillis(), wantTitle)
        log("[$label] batch submitted (${requests.length()} request(s)); polling…")

        var cancelSent = false
        var cancelFailed = false
        while (true) {
            if (isStopped() && !cancelSent && !cancelFailed) {
                try {
                    call("POST", "/v1/messages/batches/$id/cancel", JSONObject(), isStopped, ignoreStop = true)
                    /* set only once it actually went. Marking it sent before
                       the call meant a cancel the API refused was never tried
                       again — and this loop's only other exit is the batch
                       ending on its own. */
                    cancelSent = true
                    log("[$label] batch canceled — keeping results that already finished")
                } catch (e: Exception) {
                    cancelFailed = true
                    log("[$label] cancel failed — ${e.message}")
                }
            }
            val b = JSONObject(call("GET", "/v1/messages/batches/$id", null, isStopped, ignoreStop = true))
            val c = b.optJSONObject("request_counts")
            if (c != null) {
                status("[$label] processing: ${c.optInt("processing")}  ok: ${c.optInt("succeeded")}  err: ${c.optInt("errored")}")
            }
            if (b.optString("processing_status") == "ended") break
            /* Stop has to be able to leave this. A cancel the API refuses
               leaves the batch running to completion while this polls for as
               long as that takes, pinning a foreground service with Stop
               apparently dead. A cancel that WENT is worth waiting out — the
               batch ends in moments and the results are already paid for —
               but a refused one is not: the record is on disk, so walking
               away costs nothing and the next run recovers it. */
            if (isStopped() && cancelFailed) throw StoppedException()
            sleepPoll(if (cancelSent) 2000L else POLL_MS, isStopped, ignoreStop = true)
        }

        /* The record is NOT retired here. Fetching the results is not the
           same as having them on disk: a crash between the two would lose a
           batch we have already paid for, with nothing left to recover it
           from. The caller drops it once the chapters are written. */
        return BatchRun(id, fetchResults(id, label, isStopped))
    }

    /* a submitted batch's id alongside its results, so the record can be
       retired at the right moment rather than the convenient one */
    private class BatchRun(val id: String, val results: Map<String, JSONObject>)

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

    /* A chapter is stored gzipped whenever compression is on — since the
       download now writes it that way from the start, not just after the
       compress pass — so decide by what the bytes are rather than by the
       name we happen to hold. Reading a .gz as UTF-8 would hand the API a
       chapter of binary noise. */
    private fun readText(uri: String): String? = try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { ins ->
            val bytes = ins.readBytes()
            val gzipped = bytes.size > 1 &&
                bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
            if (gzipped) {
                java.util.zip.GZIPInputStream(bytes.inputStream()).use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            } else {
                bytes.toString(Charsets.UTF_8)
            }
        }
    } catch (e: Exception) { null }

    private val compressOn: Boolean
        get() = context.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
            .let { it.getBoolean("compressNovels", it.getBoolean("zipDownloads", true)) }

    private fun writeTranslated(tdir: DocumentFile, name: String, text: String): Boolean {
        return try {
            val body = Extractor.singleNewlines(Extractor.cleanEncoding(text))
            /* same as the source chapters: written compressed rather than
               saved plain for the post-download pass to rewrite */
            if (compressOn &&
                Zips.writeGzDoc(context.contentResolver, tdir.uri, "$name.gz", body) != null
            ) {
                return true
            }
            /* Written under a name nothing adopts and renamed into place, the
               same as a source chapter. A kill mid-write left a short file
               that the next run counts as this chapter's translation and never
               re-sends — English truncated for good, at the API's price. And
               a name already taken does not fail either call: SAF mints
               "Chapter 5 (1).txt" and returns it, which reported success for a
               file the app can't see. */
            val f = tdir.createFile("text/plain", Zips.partName(name)) ?: return false
            try {
                context.contentResolver.openOutputStream(f.uri)?.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
                } ?: throw IOException("could not open $name")
                val done = android.provider.DocumentsContract.renameDocument(
                    context.contentResolver, f.uri, name,
                ) ?: throw IOException("could not name $name")
                val got = Zips.docName(context.contentResolver, done)
                if (got != null && got != name) {
                    try {
                        android.provider.DocumentsContract.deleteDocument(context.contentResolver, done)
                    } catch (e: Exception) {}
                    return false
                }
                true
            } catch (e: Exception) {
                try { f.delete() } catch (e2: Exception) {}
                false
            }
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
            /* Opus 5 thinks by default, and those tokens share MAX_TOKENS with
               the translation JSON. A packed bundle that spent the remaining
               room on thinking would truncate and be bought again. Effort is
               already medium, which is allowed with thinking off. */
            .put("thinking", JSONObject().put("type", "disabled"))
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

    /* ---- name-pair validation, ported from the web app ----
       Enforce the glossary rules even if the model slips: drop parenthetical
       notes, collapse "a/b" alternatives to the longest, and reject pairs
       that are really sentence fragments rather than names. */
    private val VI_PARTICLES = setOf(
        "là", "của", "và", "rằng", "thì", "mà", "rồi", "đã", "đang", "sẽ",
        "thật", "cũng", "đến", "không", "nhé", "ạ", "ơi", "vậy", "nữa",
    )
    private val EN_VERBS = Regex("\\b(is|are|was|were|has|have|had|will|would|did|arrived|came|come|said|went)\\b")

    private fun cleanEnglish(raw: String): String {
        var en = raw.replace(Regex("\\s*\\([^)]*\\)"), "")
        if (en.contains("/")) en = en.split("/").map { it.trim() }.maxByOrNull { it.length } ?: ""
        return en.replace(Regex("\\s+"), " ").trim()
    }

    private fun isNamePair(vi: String, en: String): Boolean {
        val viWords = vi.split(Regex("\\s+"))
        val enWords = en.split(Regex("\\s+"))
        if (viWords.size > 7 || enWords.size > 8) return false
        if (viWords.any { it in VI_PARTICLES }) return false
        if (EN_VERBS.containsMatchIn(en)) return false   // case-sensitive: a name like "Will" stays safe
        return true
    }

    /* merge model-returned name pairs into the glossary + DB (existing win),
       so the NEXT bundle's request carries every name fixed so far */
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
            val en = cleanEnglish(o.optString("english"))
            if (vi.isNotEmpty() && en.isNotEmpty() && isNamePair(vi, en) && !glossary.containsKey(vi)) {
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
        /* A cached title that is just the sanitised Vietnamese name is not a
           translation — it is the folder name a since-fixed build wrote into
           this column while claiming folder ownership. Trusting it returned
           here immediately, so the novel could never be translated again, on
           any later run, silently. Checking the value rather than deleting
           the row repairs those libraries without touching a genuine
           "English (Vietnamese)" title, which never equals this. */
        store.getTitle(folder, slug)
            ?.takeIf { it != Extractor.sanitize(vietTitle) }
            ?.let { return@withContext it }

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
            /* Still here, so the orphan gave us nothing usable — blank, or a
               batch the API no longer has. We are about to submit and pay for
               a replacement, and once that one succeeds the check at the top
               short-circuits and this row is never looked at again: it would
               sit in the table until it aged out, counted as work in flight.
               Retire it with the attempt that replaced it. */
            try { store.removePending(orphan.batchId) } catch (e: Exception) {}
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
            val submitted = JSONObject(
                try {
                    creating = true
                    call("POST", "/v1/messages/batches", JSONObject().put("requests", reqs), isStopped, retryOnDrop = false)
                } finally { creating = false },
            )
            val id = submitted.getString("id")
            store.addPending(folder, slug, id, emptyList(), emptyList(), now, vietTitle)
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

        /* "Chapter N.txt.gz" is Chapter N, translated. Matching raw names
           meant a compressed translation looked missing and was sent to the
           API again — the whole novel, every run, at cost. */
        val done = tdir.listFiles().mapNotNull { it.name?.removeSuffix(".gz") }.toHashSet()
        /* Chapters a batch is still holding. Recovery keeps that batch's
           record when it can't be collected this run, so submitting them
           again would pay for the same translation twice. */
        val inFlight = try {
            val nowNamed = store.urlMap(folder, slug)
            store.pendingFor(folder, slug, System.currentTimeMillis()).flatMap { rec ->
                /* by page where we have it: a record naming "Chapter 5.txt"
                   from before a shift would otherwise hold back whichever
                   chapter sits at 5 today and leave the real one unsent */
                rec.files.mapIndexed { i, fn ->
                    val u = rec.urls.getOrNull(i)?.takeIf { it.isNotEmpty() }
                    /* Same rule as recovery: a recorded page that no longer
                       resolves must not fall back to the submitted name. It
                       held back whichever chapter sits at that number today
                       while the one really inside the batch went unguarded —
                       and was submitted, and paid for, a second time. */
                    if (u != null) nowNamed[u] else fn
                }.filterNotNull()
            }.toHashSet()
        } catch (e: Exception) { hashSetOf<String>() }
        if (inFlight.isNotEmpty()) {
            log("${inFlight.size} chapter(s) are still with an unfinished batch — leaving them for it")
        }
        val uris = store.get(folder, slug)   // filename -> source document URI
        /* A batch can outlive the naming it was submitted under. Record the
           page each chapter came from so results collected later are filed by
           what the chapter IS, not by where it happened to sit. */
        val pageOf = try { store.fileUrls(folder, slug) } catch (e: Exception) { hashMapOf<String, String>() }
        var unreadable = 0
        val pending = filenames.filter { it !in done && it !in inFlight }.mapNotNull { fn ->
            val uri = uris[fn]?.takeIf { it.isNotEmpty() }
            if (uri == null) { unreadable++; return@mapNotNull null }
            val text = readText(uri)
            if (text == null) { unreadable++; return@mapNotNull null }
            Pair(fn, text)
        }
        /* Say so rather than calling the novel translated: a chapter we can't
           read is not a chapter that needs no work. */
        if (unreadable > 0) log("! $unreadable chapter(s) could not be read from the index — not translated")
        if (pending.isEmpty()) {
            log(
                if (unreadable > 0) "Translation: nothing translatable this run."
                else "Translation: everything already translated.",
            )
            return@withContext
        }

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
                /* A WRITE that failed, specifically — not a chapter the reply
                   never contained. Only the first is worth keeping the batch
                   record for, and the two were counted as one thing. */
                var writeFailed = 0
                status("Translating bundle $bundleNo (${bundle.size} chapter(s))…")

                val run = runBatch(
                    bundleRequest(bundle, glossary), "translate $bundleNo",
                    folder, slug, bundle.map { it.first },
                    bundle.map { pageOf[it.first] ?: "" }, isStopped,
                )
                val msg = run.results["bundle"]

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
                        /* No bundle in the results at all. That is not "this
                           batch is used up" — it is a batch that ran, was
                           billed, and whose output we failed to read, and the
                           id is the only handle on it. Keep the record and let
                           recovery re-read it; that path is bounded by its own
                           retry count, so a batch that really is empty still
                           ages out. */
                        if (msg == null) continue
                    }
                    store.removePending(run.id)   // consumed: split halves are new batches
                    continue
                }

                val parsed = parseBundle(messageText(msg))
                if (parsed == null) {
                    log("! bundle $bundleNo: could not parse response"); failed += bundle.size
                    store.removePending(run.id)   // consumed, unusable
                    continue
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
                        /* `n` is a bare index into what we submitted, and the
                           prompt has to tell the model three times not to
                           answer with the chapter number printed in the
                           heading. When it does anyway, every entry lands one
                           or more slots out — each chapter's English written
                           under a neighbour's filename, which the reader joins
                           by name alone and the translator never re-sends
                           because the name exists. Both halves carry the
                           chapter's number in their first line; if they
                           disagree, this mapping is not to be trusted. */
                        val srcNum = Extractor.parseHeading(
                            ch.second.lineSequence().firstOrNull().orEmpty(),
                        ).first
                        val outNum = Extractor.parseHeading(
                            text.lineSequence().firstOrNull().orEmpty(),
                        ).first
                        if (srcNum != null && outNum != null && srcNum != outNum) {
                            failed++
                            log("! bundle $bundleNo: reply $n is chapter $outNum, not $srcNum — not saved")
                            continue
                        }
                        if (writeTranslated(tdir, ch.first, text)) {
                            done.add(ch.first); saved++
                            log("saved  translated/${ch.first}")
                        } else {
                            failed++
                            writeFailed++
                        }
                    }
                }
                /* The loop walks the RESPONSE, so a chapter the model left out
                   of the array was never visited — neither saved nor failed.
                   Counting only what came back reported "N ok, 0 failed" on a
                   short bundle and talked the user out of the re-run that
                   would have fixed it. Reconcile against what we submitted. */
                val missing = bundle.count { it.first !in done }
                if (missing > 0) {
                    failed += missing
                    log("! bundle $bundleNo: $missing chapter(s) missing from the reply — left for a re-run")
                }
                /* written — now the record can go. Anything short of this and
                   it stays, so recovery can still collect the batch. A bundle
                   whose writes all failed (full disk, ejected card) must keep
                   its record: the batch id is the only handle on results we
                   have already paid for. */
                /* Everything accounted for, or the record stays. "At least one
                   write worked" was not enough: a bundle of ten where three
                   land and seven fail — the volume fills mid-bundle, the card
                   is pulled — retired the record and lost seven translations
                   that were already paid for, to be bought again next run. */
                /* ...but a chapter the MODEL left out is not one of those. The
                   batch has ended and its results have been read, so re-reading
                   them can only produce the same reply with the same chapter
                   missing — and while the record lives, `inFlight` withholds
                   exactly those chapters from being re-sent. So a short reply
                   bought five runs of recovery that could not possibly recover
                   anything, with the log telling the user to re-run and the
                   re-run doing nothing, before the record aged out and the
                   chapters were finally translated. Keep the record only for
                   work we have paid for and not yet written down. */
                if (writeFailed == 0 || chapters == null || chapters.length() == 0) {
                    store.removePending(run.id)
                } else {
                    log("! bundle $bundleNo: $writeFailed chapter(s) not written — keeping the batch for recovery")
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
        val done = tdir.listFiles().mapNotNull { it.name?.removeSuffix(".gz") }.toHashSet()
        /* Where each chapter lives NOW. The names in the record are where they
           lived when the batch went out, and a rename pass — from a download
           or from a plain Check status — moves every file after an inserted or
           dropped chapter. Filing the results under the old names wrote each
           chapter's English over its neighbour, which nothing downstream could
           detect: the reader keys translations by filename alone. */
        val nowNamed = try { store.urlMap(folder, slug) } catch (e: Exception) { hashMapOf<String, String>() }
        log("Found ${mine.size} unfinished batch(es) from a previous session — recovering…")
        for (rec in mine) {
            if (isStopped()) throw StoppedException()
            try {
                var ended = false
                while (!ended) {
                    val b = JSONObject(call("GET", "/v1/messages/batches/${rec.batchId}", null, isStopped, ignoreStop = true))
                    val c = b.optJSONObject("request_counts")
                    if (c != null) {
                        status("[recover] processing: ${c.optInt("processing")}  ok: ${c.optInt("succeeded")}  err: ${c.optInt("errored")}")
                    }
                    if (b.optString("processing_status") == "ended") { ended = true; break }
                    /* Stop has to be able to leave this. The poll ignored it
                       entirely, so a batch still processing pinned a
                       foreground service checking every ten seconds for as
                       long as the batch took, with Stop apparently dead. The
                       record is kept, so walking away costs nothing — the
                       next run picks it up where it left off. */
                    if (isStopped()) break
                    sleepPoll(POLL_MS, isStopped, ignoreStop = true)
                    if (isStopped()) break
                }
                if (!ended) {
                    log("Stopped — batch …${rec.batchId.takeLast(8)} left for the next run")
                    throw StoppedException()
                }
                val results = fetchResults(rec.batchId, "recover", isStopped)
                val msg = results["bundle"]
                var n = 0
                /* as in the collecting path: a WRITE that failed, which is
                   worth another run, not a chapter the reply never held */
                var recWriteFailed = 0
                if (msg != null && msg.optString("stop_reason") != "max_tokens") {
                    val parsed = parseBundle(messageText(msg))
                    if (parsed != null) {
                        mergeNames(parsed, glossary, store, folder, slug)
                        val chapters = parsed.optJSONArray("chapters")
                        if (chapters != null) {
                            for (i in 0 until chapters.length()) {
                                val o = chapters.optJSONObject(i) ?: continue
                                val idx = o.optInt("n", -1) - 1
                                val text = o.optString("text")
                                /* the page is the identity; the recorded name
                                   is only a fallback for rows written before
                                   pages were kept */
                                val url = rec.urls.getOrNull(idx)?.takeIf { it.isNotEmpty() }
                                /* A page we recorded but can no longer place
                                   is a chapter whose row has since gone — the
                                   site dropped it and the dedupe removed it.
                                   Falling back to the submitted FILENAME then
                                   wrote this chapter's English into whatever
                                   now sits at that number, and the reader
                                   joins by filename alone, so it showed the
                                   wrong text for good. The name is only a
                                   fallback for records written before pages
                                   were kept at all. */
                                val fn = if (url != null) {
                                    nowNamed[url] ?: continue
                                } else {
                                    rec.files.getOrNull(idx) ?: continue
                                }
                                if (text.isBlank() || done.contains(fn)) continue
                                /* The same check the collecting path makes.
                                   Without it, a reply whose `n` is the number
                                   printed in the heading rather than its
                                   position was rejected there, the record was
                                   kept "for recovery", and recovery then wrote
                                   exactly that bad mapping one run later —
                                   each chapter's English under a neighbour's
                                   name, which nothing afterwards can detect. */
                                /* From the SOURCE FILE's heading, not from
                                   the filename: a filename is the chapter's
                                   position in the listing, the heading is the
                                   number the site prints, and this app exists
                                   because those two are not the same. */
                                val srcNum = readHeadingNum(store, folder, slug, fn)
                                val outNum = Extractor.parseHeading(
                                    text.lineSequence().firstOrNull().orEmpty(),
                                ).first
                                if (srcNum != null && outNum != null && srcNum != outNum) {
                                    log("! recovered reply $idx is chapter $outNum, not $srcNum — not saved")
                                    continue
                                }
                                if (writeTranslated(tdir, fn, text)) { done.add(fn); n++; log("saved  translated/$fn (recovered)") }
                                else recWriteFailed++
                            }
                        }
                        log("Recovered bundle: $n chapter(s)")
                    }
                }
                /* Retiring the record throws away the batch id, and that id is
                   the only handle on results already paid for.

                   "Nothing was written" was the wrong test — the same one the
                   collecting path was fixed away from. A bundle of ten where
                   three land and seven fail (the volume fills, the card is
                   pulled) satisfied n > 0, so the row went and seven paid
                   translations were lost, to be bought again next run. And a
                   batch whose results came back unusable — no bundle in the
                   response, a reply cut off at max_tokens, unparseable JSON —
                   reached the delete with both counters at zero, silently.

                   Reconcile against what was SUBMITTED, as the collecting path
                   does: every chapter of this batch that can still be placed
                   has to be on disk. A page that no longer resolves is a
                   chapter that has since gone; it can never be written, so it
                   must not hold the record open. retryLater is bounded, so a
                   record that can never be satisfied still ages out. */
                val unaccounted = (0 until maxOf(rec.files.size, rec.urls.size)).count { i ->
                    val url = rec.urls.getOrNull(i)?.takeIf { it.isNotEmpty() }
                    val fn = if (url != null) nowNamed[url] else rec.files.getOrNull(i)
                    fn != null && fn !in done
                }
                /* ...and only when something is still RECOVERABLE. A chapter
                   the reply never contained cannot appear on a re-read — the
                   batch ended before the record was ever kept — so holding the
                   record open for it bought nothing, while `inFlight` withheld
                   that chapter from being re-sent for every one of the five
                   tries. Records written by an older build reach here too, so
                   the distinction has to be made on this side as well. */
                if (unaccounted > 0 && recWriteFailed > 0) {
                    log("! batch …${rec.batchId.takeLast(8)}: $unaccounted chapter(s) not saved — keeping it for the next run")
                    retryLater(store, rec, "could not write results")
                    continue
                }
                if (unaccounted > 0) {
                    log(
                        "! batch …${rec.batchId.takeLast(8)}: $unaccounted chapter(s) were not in the " +
                            "reply — re-sending them next run",
                    )
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

    /* The chapter number printed at the top of a saved chapter, read back off
       disk — what a translated reply's own heading has to agree with. */
    private fun readHeadingNum(store: DownloadStore, folder: String, slug: String, fn: String): Int? {
        /* `chapters.uri` holds a FULL document URI — every writer of the column
           stores `uri.toString()` — not a document id and never a "gz::" ref
           (that shape exists only inside ChapterListActivity's own listing).
           Reading it as a docId built a nonsense URI out of the tree, threw,
           and returned null for every chapter, always: the misnumbering guard
           below was dead code. A bundle the collecting path rejected for
           answering with the wrong chapter number was kept for recovery, and
           recovery — running the same check with `srcNum` permanently null —
           wrote it, so every chapter's English landed under its neighbour's
           name, for good. `readText` is the reader that gets this right, and
           it sniffs the gzip magic rather than trusting a name. */
        val uri = try { store.get(folder, slug)[fn] } catch (e: Exception) { null } ?: return null
        val text = readText(uri) ?: return null
        return Extractor.parseHeading(text.lineSequence().firstOrNull().orEmpty()).first
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
