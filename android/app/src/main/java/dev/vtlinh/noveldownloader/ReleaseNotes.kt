package dev.vtlinh.noveldownloader

/* Release notes for the About page.

   Each PR adds one row to android/changelog/notes.tsv: a short summary,
   version column blank. Packaging (generateReleaseNotes) concatenates
   those rows under APP_VERSION_NAME and ships the result as
   assets/changelog.tsv. Rows already given a version stay under it,
   which is how previous versions keep their history after the next build.

   Stamp blank rows with the published versionName before adding a new one
   (tools/seal-changelog.py), or the next APK will swallow them into the
   new version. */
object ReleaseNotes {

    data class Entry(val version: String?, val summary: String)
    data class Section(val version: String, val summaries: List<String>)

    fun parse(tsv: String): List<Entry> {
        val out = ArrayList<Entry>()
        for (raw in tsv.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isEmpty() || line.startsWith("#")) continue
            val tab = line.indexOf('\t')
            if (tab < 0) continue
            val summary = line.substring(tab + 1).trim()
            if (summary.isEmpty()) continue
            val version = line.substring(0, tab).trim().ifEmpty { null }
            out.add(Entry(version, summary))
        }
        return out
    }

    /* Current version first, then the rest newest-first. Blank-version rows
       are this build — concatenated, in file order. */
    fun sections(entries: List<Entry>, currentVersion: String): List<Section> {
        val grouped = linkedMapOf<String, MutableList<String>>()
        for (e in entries) {
            grouped.getOrPut(e.version ?: currentVersion) { mutableListOf() }
                .add(e.summary)
        }
        return grouped.entries
            .sortedWith { a, b ->
                when {
                    a.key == currentVersion && b.key != currentVersion -> -1
                    b.key == currentVersion && a.key != currentVersion -> 1
                    else -> versionCmp(b.key, a.key)
                }
            }
            .map { Section(it.key, it.value) }
    }

    fun sections(tsv: String, currentVersion: String): List<Section> =
        sections(parse(tsv), currentVersion)

    fun render(tsv: String, currentVersion: String): String =
        sections(tsv, currentVersion).joinToString("\n\n") { sec ->
            buildString {
                append(sec.version)
                for (s in sec.summaries) {
                    append("\n• ")
                    append(s)
                }
            }
        }

    /* Missing trailing parts count as 0, so 1.1 sorts below 1.30.1. */
    internal fun versionCmp(a: String, b: String): Int {
        val ka = versionParts(a)
        val kb = versionParts(b)
        val n = maxOf(ka.size, kb.size)
        for (i in 0 until n) {
            val d = ka.getOrElse(i) { 0 }.compareTo(kb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun versionParts(v: String): List<Int> =
        v.split('.').map { it.toIntOrNull() ?: -1 }
}
