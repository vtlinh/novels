package dev.vtlinh.noveldownloader

/* Chapter scene: a short summary plus the visual notes an image needs.

   The Cursor Cloud Agents API has no chat-completions endpoint, so both
   halves launch an agent. The cheap Composer id writes this record; a
   stronger image-capable model is used only when the reader asks for a
   picture. Parsing and model picking live here with no Android in them
   so the tests can judge a real reply without standing up an agent. */
object Scenes {

    const val DIR = "scenes"
    const val KIND_SUMMARY = "summary"
    const val KIND_IMAGE = "image"
    /* Documented Cloud Agents ids. GET /v1/models is preferred at runtime. */
    const val DEFAULT_SUMMARY_MODEL = "composer-2"
    const val DEFAULT_IMAGE_MODEL = "gemini-3-pro-image-preview"
    const val CHAPTER_CHAR_BUDGET = 60_000

    data class Scene(
        val summary: String,
        val characters: String,
        val background: String,
    ) {
        fun ready() = summary.isNotBlank() || characters.isNotBlank() || background.isNotBlank()
    }

    data class Work(val kind: String, val agentId: String, val runId: String)

    fun chapterBase(filename: String): String {
        val noGz = filename.removeSuffix(".gz")
        return if (noGz.endsWith(".txt", ignoreCase = true)) noGz.dropLast(4) else noGz
    }

    fun jsonName(filename: String) = chapterBase(filename) + ".json"
    fun imageName(filename: String) = chapterBase(filename) + ".png"
    fun workName(filename: String) = chapterBase(filename) + ".work.json"

    /* SHA-256 of the chapter bytes we upload, nothing else — no slug,
       prefix, or comment. Slack filename is that hex + ".txt". */
    fun contentHash(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    fun slackFileName(text: String) = contentHash(text) + ".txt"

    fun isSceneFile(name: String): Boolean {
        val n = name.removeSuffix(".gz").lowercase()
        return n.endsWith(".json") || n.endsWith(".png") || n.endsWith(".jpg") ||
            n.endsWith(".jpeg") || n.endsWith(".webp")
    }

    fun clipChapter(text: String, max: Int = CHAPTER_CHAR_BUDGET): String {
        if (text.length <= max) return text
        val head = max * 5 / 6
        val tail = (max - head - 40).coerceAtLeast(0)
        return text.take(head) + "\n\n[...middle omitted...]\n\n" + text.takeLast(tail)
    }

    fun pickSummaryModel(ids: List<String>): String {
        if (ids.isEmpty()) return DEFAULT_SUMMARY_MODEL
        return ids.firstOrNull { it.contains("composer-2.5") }
            ?: ids.firstOrNull { it.startsWith("composer") }
            ?: DEFAULT_SUMMARY_MODEL
    }

    /* Prefer a native image model. Composer is the cheap text pool — do
       not spend it on the picture. */
    fun pickImageModel(ids: List<String>): String {
        if (ids.isEmpty()) return DEFAULT_IMAGE_MODEL
        return ids.firstOrNull { it.contains("image", ignoreCase = true) }
            ?: ids.firstOrNull { it.contains("gemini-3", ignoreCase = true) }
            ?: ids.firstOrNull { !it.startsWith("composer") }
            ?: DEFAULT_IMAGE_MODEL
    }

    fun summaryPrompt(title: String, chapter: String): String {
        val body = clipChapter(chapter)
        return "You are summarizing one chapter of a novel for a reader. " +
            "Do not write or edit any files. Do not open a pull request. " +
            "Do not generate an image.\n" +
            "Reply with ONLY a JSON object, no markdown fences, no other text:\n" +
            "{\"summary\":\"2-4 sentence plot summary of this chapter\"," +
            "\"characters\":\"visual descriptions of the characters who appear, enough to draw them\"," +
            "\"background\":\"visual description of the setting and atmosphere\"}\n" +
            "Novel: $title\n\nChapter:\n$body"
    }

    fun imagePrompt(title: String, scene: Scene): String {
        return "Generate ONE illustration of this novel chapter. " +
            "Do not write code and do not open a pull request.\n" +
            "Save the image as artifacts/scene.png (PNG). " +
            "When the file is in artifacts/, reply with the single word DONE.\n" +
            "Novel: $title\n" +
            "Summary: ${scene.summary}\n" +
            "Characters: ${scene.characters}\n" +
            "Background: ${scene.background}\n" +
            "Style: detailed illustration, no caption, no title text, no watermark."
    }

    fun encode(scene: Scene): String =
        "{" +
            "\"summary\":${quote(scene.summary)}," +
            "\"characters\":${quote(scene.characters)}," +
            "\"background\":${quote(scene.background)}" +
            "}"

    fun encodeWork(work: Work): String =
        "{" +
            "\"kind\":${quote(work.kind)}," +
            "\"agentId\":${quote(work.agentId)}," +
            "\"runId\":${quote(work.runId)}" +
            "}"

    fun parse(text: String): Scene? {
        extractJsonObject(text)?.let { obj ->
            val summary = jsonField(obj, "summary")
            val characters = jsonField(obj, "characters")
            val background = jsonField(obj, "background")
            if (summary != null || characters != null || background != null) {
                val scene = Scene(
                    summary.orEmpty().trim(),
                    characters.orEmpty().trim(),
                    background.orEmpty().trim(),
                )
                if (scene.ready()) return scene
            }
        }
        return parseLabeled(text)
    }

    fun parseWork(text: String): Work? {
        val obj = extractJsonObject(text) ?: return null
        val kind = jsonField(obj, "kind")?.trim().orEmpty()
        val agentId = jsonField(obj, "agentId")?.trim().orEmpty()
        val runId = jsonField(obj, "runId")?.trim().orEmpty()
        if (kind.isEmpty() || agentId.isEmpty() || runId.isEmpty()) return null
        return Work(kind, agentId, runId)
    }

    /* first top-level {…}, strings and nested objects skipped correctly */
    fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) {
                if (esc) {
                    esc = false
                    continue
                }
                if (c == '\\') {
                    esc = true
                    continue
                }
                if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun jsonField(obj: String, key: String): String? {
        val needle = "\"$key\""
        var i = obj.indexOf(needle)
        if (i < 0) return null
        i += needle.length
        while (i < obj.length && obj[i] in " \t\n\r:") i++
        if (i >= obj.length || obj[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < obj.length) {
            val c = obj[i]
            if (c == '\\' && i + 1 < obj.length) {
                val n = obj[i + 1]
                when (n) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '"', '\\', '/' -> sb.append(n)
                    'u' -> {
                        if (i + 6 <= obj.length) {
                            val hex = obj.substring(i + 2, i + 6)
                            val cp = hex.toIntOrNull(16)
                            if (cp != null) {
                                sb.append(cp.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(n)
                    }
                    else -> sb.append(n)
                }
                i += 2
                continue
            }
            if (c == '"') return sb.toString()
            sb.append(c)
            i++
        }
        return null
    }

    private fun parseLabeled(text: String): Scene? {
        val summary = labeled(text, "summary")
        val characters = labeled(text, "characters")
        val background = labeled(text, "background")
        val scene = Scene(
            summary.orEmpty().trim(),
            characters.orEmpty().trim(),
            background.orEmpty().trim(),
        )
        return if (scene.ready()) scene else null
    }

    /* "Summary:" / "## Characters" style fallback when the model ignores JSON */
    private fun labeled(text: String, key: String): String? {
        val re = Regex(
            """(?im)^(?:#{1,3}\s*)?$key\s*:?\s*\n+([\s\S]+?)(?=\n(?:#{1,3}\s*)?(?:summary|characters|background)\s*:?\s*$|\z)""",
        )
        return re.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
