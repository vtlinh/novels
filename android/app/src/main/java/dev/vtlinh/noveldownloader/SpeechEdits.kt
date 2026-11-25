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

/* Whole-word boundary around a regex. Shared by compiled() and export so a
   backup round-trip (which stores the wrap and clears the flag) matches the
   same text the rule matches now. Non-capturing, so $1 stays the user's group. */
private fun wholeWordRegex(pattern: String) = "\\b(?:$pattern)\\b"

data class SpeechEdit(
    val id: String,
    var title: String,
    var type: Int,
    var wholeWord: Boolean,
    var pattern: String,
    var replace: String,
    var enabled: Boolean,
    val isDefault: Boolean,
    /* slug of the one novel this rule belongs to; null = every novel */
    val novel: String? = null,
) {
    fun appliesTo(slug: String?) = novel == null || novel == slug

    /* compiled (regex, replacement) or null if the pattern doesn't compile.
       "Match whole words" on a regex used to be saved and exported, then
       ignored here, so `(\d+) s` still rewrote the "s" inside "seconds".
       The wrap is the same text a backup bakes in (a re-imported rule comes
       back with the flag off), and (?:) does not renumber $1. */
    fun compiled(): Pair<Regex, String>? = try {
        val p = when {
            type != 2 -> SpeechText.literalPattern(pattern, wholeWord)
            wholeWord -> wholeWordRegex(pattern)
            else -> pattern
        }
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
        if (novel != null) put("novel", novel)
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
            novel = o.optString("novel").takeIf { it.isNotEmpty() },
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
            d("Remove ellipsis at start", SpeechText.LEADING_ELLIPSIS, ""),
            d("", "(?i)\\bNo\\.\\s*(\\d+)", "number $1"),
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
            /* appended so existing default ids (and any overrides) stay put */
            d("Silence ellipsis", SpeechText.ELLIPSIS, ""),
            d("Silence standalone quotes", SpeechText.STANDALONE_QUOTES, ""),
            /* The splitter keeps these with the word after them. The period
               has to leave the spoken copy, or the voice pauses inside the
               name: "Mt. Tai" was read as "Mt." and then "Tai". */
            d("", "(?i)\\bMt\\.\\s*", "Mount "),
            d("", "\\bCapt\\.\\s", "Captain "),
            d("", "\\bCol\\.\\s", "Colonel "),
            d("", "\\bLieut\\.\\s", "Lieutenant "),
            d("", "\\bLt\\.\\s", "Lieutenant "),
            d("", "\\bMaj\\.\\s", "Major "),
            d("", "\\bSgt\\.\\s", "Sergeant "),
            d("", "\\bRev\\.\\s", "Reverend "),
            d("", "\\bHon\\.\\s", "Honorable "),
            d("", "\\bJr\\.", "Junior"),
            d("", "\\bVol\\.\\s", "Volume "),
            d("", "\\bGov\\.\\s", "Governor "),
            d("", "\\bMme\\.\\s", "Madame "),
            d("", "\\bMlle\\.\\s", "Mademoiselle "),
            d("", "\\bMessrs\\.\\s", "Messieurs "),
            d("", "\\bWm\\.\\s", "William "),
            d("", "\\bGeo\\.\\s", "George "),
            d("", "\\bChas\\.\\s", "Charles "),
            d("", "\\bDrs\\.\\s", "Doctors "),
            d("", "\\bEsq\\.", "Esquire"),
            d("", "\\bSte\\.\\s", "Sainte "),
            d("", "\\bFrl\\.\\s", "Fraulein "),
            d("", "\\bFt\\.\\s", "Fort "),
            d("", "\\bft\\.\\s", "feet "),
            d("", "(?i)\\bvs\\.\\s", "versus "),
            d("", "(?i)\\betc\\.", "etcetera"),
            d("", "(?i)\\boz\\.\\s", "ounces "),
            d("", "(?i)\\blbs\\.\\s", "pounds "),
            d("", "(?i)\\blb\\.\\s", "pounds "),
            d("", "(?i)\\bpp\\.\\s", "pages "),
            d("", "(?i)\\bNos\\.\\s", "numbers "),
            d("", "(?i)\\bApprox\\.\\s", "approximately "),
            d("", "(?i)\\b([ap])\\.m\\.", "$1 m"),
            d("", "\\bPh\\.\\s*", "P H "),
            d("", "\\b([A-Z])\\.(?=[A-Z])", "$1 "),
            d("", "(?i)(?<![A-Za-z0-9])(Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec|Sr)\\.", "$1"),
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

    /* A pattern as typed in the editor. Keyboards add a space after a
       symbol and the field doesn't show it; since every sentence is matched
       with a trailing space, a typed `"` + space replaced only the quote
       that ends the sentence. A space that matters is written as \s. */
    fun typedPattern(text: String): String = text.trim()

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

    /* ── per-novel versions ──
       Any rule, user or default, can be changed for one novel only. That
       version is stored under the novel's slug, keyed by the rule's id, and
       shadows the global rule in its slot while that novel is read. Turning
       it off drops the entry, and the rule is the global one again. An entry
       whose rule was deleted has no slot, so it is never applied.
       A rule ADDED for one novel has no global version to shadow; it is a
       user rule carrying that novel's slug, and every other novel skips it. */

    private fun novelKey(slug: String) = "novel:$slug"

    fun novelEdits(ctx: Context, slug: String?): MutableMap<String, SpeechEdit> {
        if (slug.isNullOrEmpty()) return linkedMapOf()
        val raw = prefs(ctx).getString(novelKey(slug), null) ?: return linkedMapOf()
        return try {
            val o = JSONObject(raw)
            val out = linkedMapOf<String, SpeechEdit>()
            for (k in o.keys()) out[k] = SpeechEdit.fromJson(o.getJSONObject(k))
            out
        } catch (e: Exception) {
            linkedMapOf()
        }
    }

    private fun saveNovelEdits(ctx: Context, slug: String, map: Map<String, SpeechEdit>) {
        val ed = prefs(ctx).edit()
        if (map.isEmpty()) {
            ed.remove(novelKey(slug))
        } else {
            val o = JSONObject()
            map.forEach { (k, v) -> o.put(k, v.toJson()) }
            ed.putString(novelKey(slug), o.toString())
        }
        ed.apply()
    }

    fun saveNovelEdit(ctx: Context, slug: String, edit: SpeechEdit) {
        val m = novelEdits(ctx, slug)
        m[edit.id] = edit
        saveNovelEdits(ctx, slug, m)
    }

    fun clearNovelEdit(ctx: Context, slug: String, id: String) {
        val m = novelEdits(ctx, slug)
        if (m.remove(id) != null) saveNovelEdits(ctx, slug, m)
    }

    /* the global rules with this novel's versions put in their slots */
    fun forNovel(global: List<SpeechEdit>, novel: Map<String, SpeechEdit>): List<SpeechEdit> =
        global.map { g -> novel[g.id]?.copy(isDefault = g.isDefault) ?: g }

    private fun compileEnabled(list: List<SpeechEdit>): List<Pair<Regex, String>> =
        list.filter { it.enabled }.mapNotNull { it.compiled() }

    /* compiled enabled rules for the reader: user rules first, then the
       built-in defaults (each possibly overridden, and skippable when its
       override disables it), with the novel's own versions in their slots */
    fun enabledUserRules(ctx: Context, slug: String? = null): List<Pair<Regex, String>> =
        compileEnabled(forNovel(user(ctx).filter { it.appliesTo(slug) }, novelEdits(ctx, slug)))

    fun enabledDefaultRules(ctx: Context, slug: String? = null): List<Pair<Regex, String>> =
        compileEnabled(forNovel(effectiveDefaults(ctx), novelEdits(ctx, slug)))

    /* ── @Voice Aloud Reader replacement-file format ── */

    private fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    /* serialize user rules to the @Voice text format */
    fun export(list: List<SpeechEdit>): String {
        val sb = StringBuilder(";_replModTime=0\n")
        for (e in list) {
            var regex = e.type == 2
            var pat = e.pattern
            if (e.wholeWord) {
                pat = if (regex) wholeWordRegex(pat) else "\\b\\Q$pat\\E\\b"
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

    /* Run a batch of user regexes with a wall-clock budget, off the calling
       thread. Returns null if it did not finish in time.

       The reader used to bound this by handing the matcher a CharSequence
       that threw once a deadline passed. That guard never fires on Android:
       java.util.regex here is ICU-backed and libcore materialises the input
       with toString() before matching, so the matcher never reads through
       our wrapper — the whole protection was inert, on every version. And
       catastrophic backtracking is not an exception, so no catch can help.

       A separate thread can be abandoned. The runaway keeps burning a core
       until it finishes or the process dies, which is why the callers stop
       using the rule set after a timeout rather than starting another one
       each sentence — but the reader, and the editor's live preview, stay
       responsive. */
    /* BOUNDED. A timed-out task is not cancellable — a backtracking matcher
       doesn't check for interruption — so each timeout pins a thread until it
       finishes on its own. An unbounded pool therefore leaks one spinning
       thread per call, and the editor's live preview calls this on every
       keystroke: typing "(a+)+b" one character at a time would pin a core per
       character. With a hard ceiling and no queue, the extra submissions are
       refused instead, which reads to the caller exactly like a timeout. */
    private val pool by lazy {
        java.util.concurrent.ThreadPoolExecutor(
            0, 2, 10L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.SynchronousQueue(),
        ) { r -> Thread(r, "speech-rules").apply { isDaemon = true } }
    }

    fun within(budgetMs: Long, work: () -> String): String? = try {
        pool.submit<String> { work() }.get(budgetMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
        /* timed out, or refused because both threads are still stuck on the
           last runaway — either way we have no answer for this text */
        null
    }
}
