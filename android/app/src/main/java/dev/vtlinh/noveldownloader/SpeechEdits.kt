package dev.vtlinh.noveldownloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/* A single text-normalization rule applied to a sentence before TTS speaks it
   (a "speech edit"): a regex pattern → replacement, optionally case-insensitive
   and/or whole-word. Default rules are built in and toggle-only; user rules are
   fully editable and persisted as JSON. */
data class SpeechEdit(
    val id: String,
    var title: String,
    var caseInsensitive: Boolean,
    var wholeWord: Boolean,
    var pattern: String,
    var replace: String,
    var enabled: Boolean,
    val isDefault: Boolean,
) {
    /* compiled (regex, replacement) or null if the pattern doesn't compile */
    fun compiled(): Pair<Regex, String>? = try {
        var p = pattern
        if (wholeWord) p = "\\b(?:$p)\\b"
        val opts = if (caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()
        Regex(p, opts) to replace
    } catch (e: Exception) {
        null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("ci", caseInsensitive)
        put("ww", wholeWord)
        put("pattern", pattern)
        put("replace", replace)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject) = SpeechEdit(
            id = o.optString("id"),
            title = o.optString("title"),
            caseInsensitive = o.optBoolean("ci", true),
            wholeWord = o.optBoolean("ww", false),
            pattern = o.optString("pattern"),
            replace = o.optString("replace"),
            enabled = o.optBoolean("enabled", true),
            isDefault = false,
        )
    }
}

object SpeechEdits {
    private const val PREFS = "speechEdits"
    private const val USER_KEY = "user"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val EMOJI =
        "([\\u00A9\\u00AE\\u200D\\u203C\\u2049\\u2122\\u2139\\u2194-\\u21AA\\u231A-\\u231B" +
            "\\u2328\\u23CF\\u23E9-\\u23FA\\u24C2\\u25AA\\u25FE\\u2600-\\u27BF\\u2934-\\u2935" +
            "\\u2B05-\\u2B07\\u2B1B-\\u2B1C\\u2B50\\u2B55\\u3030\\u303D\\u3297\\u3299\\uFE0F]|" +
            "\\uD83C[\\uDC00-\\uDFFF]|\\uD83D[\\uDC00-\\uDFFF]|\\uD83E[\\uDC00-\\uDEFF]|" +
            "\\uDB40[\\uDC20-\\uDC7F])+"

    /* built-in English speech edits (order matters — applied top to bottom) */
    val defaults: List<SpeechEdit> by lazy {
        var n = 0
        fun d(title: String, ci: Boolean, pattern: String, replace: String) =
            SpeechEdit("def${n++}", title, ci, false, pattern, replace, true, true)
        listOf(
            d("Silence links", false, "(([\\w-]+://?|www[.])[^\\s()<>]+(?:\\([\\w\\d]+\\)|([^\\p{Punct}\\s]|/)))", ""),
            d("Silence emojis", false, EMOJI, ""),
            d("Remove ellipsis at start", false, "^\\s*\\u2026", ""),
            d("", true, "\\b(no\\.)(\\s+[0-9])", "number$2"),
            d("", true, "\\b((no)\\.)(\\s+[^0-9]|\\s*$)", "$2; $3"),
            d("", false, "^\\s*\\u2026", ""),
            d("", false, "\\bMr\\.\\s", "Mister "),
            d("", false, "\\bMrs\\.", "Mrs"),
            d("", false, "\\b[Ee]q\\.\\s+(\\d+)\\b", "equation $1"),
            d("", false, "\\bMs\\.\\s", "Mizz "),
            d("", false, "\\bDr\\.\\s", "Doctor "),
            d("", false, "\\bFig\\.\\s", "Figure "),
            d("", false, "\\bProf\\.\\s", "Professor "),
            d("", false, "\\bGen\\.\\s", "General "),
            d("", false, "\\b([A-Z])\\.(?=\\s)", "$1"),
            d("", false, "\\bSt\\.\\s+([A-Z][a-z]+)\\b", "Saint $1"),
        )
    }

    fun user(ctx: Context): MutableList<SpeechEdit> {
        val raw = prefs(ctx).getString(USER_KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { SpeechEdit.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveUser(ctx: Context, list: List<SpeechEdit>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(USER_KEY, arr.toString()).apply()
    }

    fun newId(): String = "u" + System.nanoTime()

    /* compiled enabled rules for the reader: user rules (toggleable) first,
       then ALL built-in defaults. The defaults are hard-coded and always
       applied — they can't be disabled. */
    fun enabledRules(ctx: Context): List<Pair<Regex, String>> {
        val out = ArrayList<Pair<Regex, String>>()
        user(ctx).filter { it.enabled }.forEach { e -> e.compiled()?.let { out.add(it) } }
        defaults.forEach { e -> e.compiled()?.let { out.add(it) } }
        return out
    }
}
