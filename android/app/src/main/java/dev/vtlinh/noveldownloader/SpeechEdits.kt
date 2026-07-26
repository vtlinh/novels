package dev.vtlinh.noveldownloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/* A single text-normalization rule applied to a sentence before TTS speaks it
   (a "speech edit"). Modeled on the @Voice Aloud Reader replacement format:
     type 0 = case-insensitive literal   (no prefix)
     type 1 = case-sensitive literal     ("^" prefix)
     type 2 = regular expression         ("*" prefix)
   Default rules are built in and always applied; user rules are editable,
   persisted as JSON, and import/export in the @Voice text format. */
data class SpeechEdit(
    val id: String,
    var title: String,
    var type: Int,
    var wholeWord: Boolean,
    var pattern: String,
    var replace: String,
    var enabled: Boolean,
    val isDefault: Boolean,
) {
    /* compiled (regex, replacement) or null if the pattern doesn't compile */
    fun compiled(): Pair<Regex, String>? = try {
        var p = if (type == 2) pattern else Regex.escape(pattern)
        if (wholeWord) p = "\\b(?:$p)\\b"
        val opts = if (type == 0) setOf(RegexOption.IGNORE_CASE) else emptySet()
        Regex(p, opts) to replace
    } catch (e: Exception) {
        null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("type", type)
        put("ww", wholeWord)
        put("pattern", pattern)
        put("replace", replace)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject) = SpeechEdit(
            id = o.optString("id"),
            title = o.optString("title"),
            type = o.optInt("type", 2),
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
    private const val DISABLED = 256   // @Voice flag bit: rule is turned off

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val EMOJI =
        "([\\u00A9\\u00AE\\u200D\\u203C\\u2049\\u2122\\u2139\\u2194-\\u21AA\\u231A-\\u231B" +
            "\\u2328\\u23CF\\u23E9-\\u23FA\\u24C2\\u25AA\\u25FE\\u2600-\\u27BF\\u2934-\\u2935" +
            "\\u2B05-\\u2B07\\u2B1B-\\u2B1C\\u2B50\\u2B55\\u3030\\u303D\\u3297\\u3299\\uFE0F]|" +
            "\\uD83C[\\uDC00-\\uDFFF]|\\uD83D[\\uDC00-\\uDFFF]|\\uD83E[\\uDC00-\\uDEFF]|" +
            "\\uDB40[\\uDC20-\\uDC7F])+"

    /* built-in English speech edits (regex; order matters, applied top-down) */
    val defaults: List<SpeechEdit> by lazy {
        var n = 0
        fun d(title: String, pattern: String, replace: String) =
            SpeechEdit("def${n++}", title, 2, false, pattern, replace, true, true)
        listOf(
            d("Silence links", "(([\\w-]+://?|www[.])[^\\s()<>]+(?:\\([\\w\\d]+\\)|([^\\p{Punct}\\s]|/)))", ""),
            d("Silence emojis", EMOJI, ""),
            d("Remove ellipsis at start", "^\\s*\\u2026", ""),
            d("", "(?i)\\b(no\\.)(\\s+[0-9])", "number$2"),
            d("", "(?i)\\b((no)\\.)(\\s+[^0-9]|\\s*$)", "$2; $3"),
            d("", "\\bMr\\.\\s", "Mister "),
            d("", "\\bMrs\\.", "Mrs"),
            d("", "\\b[Ee]q\\.\\s+(\\d+)\\b", "equation $1"),
            d("", "\\bMs\\.\\s", "Mizz "),
            d("", "\\bDr\\.\\s", "Doctor "),
            d("", "\\bFig\\.\\s", "Figure "),
            d("", "\\bProf\\.\\s", "Professor "),
            d("", "\\bGen\\.\\s", "General "),
            d("", "\\b([A-Z])\\.(?=\\s)", "$1"),
            d("", "\\bSt\\.\\s+([A-Z][a-z]+)\\b", "Saint $1"),
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

    /* ── per-rule overrides of the built-in defaults ──
       The default rules stay hard-coded, but each can be EDITED: the edited
       version is stored here keyed by the default's id and shadows the
       built-in in its original slot. "Restore" just drops the override,
       falling straight back to the hard-coded rule. */

    private const val OVR_KEY = "defaultOverrides"

    fun overrides(ctx: Context): MutableMap<String, SpeechEdit> {
        val raw = prefs(ctx).getString(OVR_KEY, null) ?: return mutableMapOf()
        return try {
            val o = JSONObject(raw)
            val out = mutableMapOf<String, SpeechEdit>()
            for (k in o.keys()) {
                out[k] = SpeechEdit.fromJson(o.getJSONObject(k)).copy(isDefault = true)
            }
            out
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private fun saveOverrides(ctx: Context, map: Map<String, SpeechEdit>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v.toJson()) }
        prefs(ctx).edit().putString(OVR_KEY, o.toString()).apply()
    }

    fun saveOverride(ctx: Context, edit: SpeechEdit) {
        val m = overrides(ctx)
        m[edit.id] = edit
        saveOverrides(ctx, m)
    }

    fun clearOverride(ctx: Context, id: String) {
        val m = overrides(ctx)
        if (m.remove(id) != null) saveOverrides(ctx, m)
    }

    /* the default list with any overrides applied, in the built-in order */
    fun effectiveDefaults(ctx: Context): List<SpeechEdit> {
        val ovr = overrides(ctx)
        return defaults.map { d -> ovr[d.id] ?: d }
    }

    /* compiled enabled rules for the reader: user rules first, then the
       built-in defaults (each possibly overridden, and skippable when its
       override disables it) */
    fun enabledRules(ctx: Context): List<Pair<Regex, String>> {
        val out = ArrayList<Pair<Regex, String>>()
        user(ctx).filter { it.enabled }.forEach { e -> e.compiled()?.let { out.add(it) } }
        effectiveDefaults(ctx).filter { it.enabled }.forEach { e -> e.compiled()?.let { out.add(it) } }
        return out
    }

    /* ── @Voice Aloud Reader replacement-file format ── */

    private fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    /* serialize user rules to the @Voice text format */
    fun export(list: List<SpeechEdit>): String {
        val sb = StringBuilder(";_replModTime=0\n")
        for (e in list) {
            var regex = e.type == 2
            var pat = e.pattern
            if (e.wholeWord) {
                pat = if (regex) "\\b(?:$pat)\\b" else "\\b\\Q$pat\\E\\b"
                /* Wrapping forces this out as a regex, and a regex rule is
                   read back as case-SENSITIVE — so exporting and re-importing
                   (the documented backup route) quietly stopped a
                   case-insensitive whole-word rule from matching "MC", the
                   form that actually appears in the text. Carry the
                   insensitivity in the pattern itself, where it survives. */
                if (e.type == 0) pat = "(?i)$pat"
                regex = true
            }
            val prefix = when {
                regex -> "*"
                e.type == 1 -> "^"
                else -> ""
            }
            sb.append(prefix).append(quote(pat)).append(' ').append(quote(e.replace))
            if (!e.enabled) sb.append(" ").append(DISABLED)
            sb.append('\n')
        }
        return sb.toString()
    }

    /* parse the @Voice text format into user rules. Skips blank and ;-comment
       lines; a leading '*' means regex, '^' means case-sensitive literal, a
       trailing 256 flag means disabled. Quotes inside a field are doubled. */
    fun parse(text: String): List<SpeechEdit> {
        val out = ArrayList<SpeechEdit>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(";")) continue
            var i = 0
            var regex = false
            var caseSensitive = false
            while (i < line.length && (line[i] == '*' || line[i] == '^')) {
                if (line[i] == '*') regex = true else caseSensitive = true
                i++
            }
            if (i >= line.length || line[i] != '"') continue
            val pat = StringBuilder(); i++
            while (i < line.length) {
                val c = line[i]
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') { pat.append('"'); i += 2; continue }
                    i++; break
                }
                pat.append(c); i++
            }
            while (i < line.length && line[i] == ' ') i++
            if (i >= line.length || line[i] != '"') continue
            val rep = StringBuilder(); i++
            while (i < line.length) {
                val c = line[i]
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') { rep.append('"'); i += 2; continue }
                    i++; break
                }
                rep.append(c); i++
            }
            val rest = line.substring(i.coerceAtMost(line.length)).trim()
            val flags = rest.toIntOrNull() ?: 0
            val type = if (regex) 2 else if (caseSensitive) 1 else 0
            /* An empty pattern compiles to a regex that matches at EVERY
               position, so the replacement gets spliced between every pair of
               characters in every sentence read aloud — and the rule shows as
               a blank row, so it isn't obvious which one to delete. The editor
               already refuses one; the import path didn't. */
            if (pat.isEmpty()) continue
            out.add(
                SpeechEdit(
                    newId(), "", type, false,
                    pat.toString(), rep.toString(), (flags and DISABLED) == 0, false,
                ),
            )
        }
        return out
    }
}
