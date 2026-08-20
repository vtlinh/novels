package dev.vtlinh.noveldownloader

/* Pasted-text documents live in `{root}/documents`, one file each. The rules
   that decide a file's title, whether it is one of ours, and which copy wins
   when a loose .txt sits beside its .gz are here so they can be tested
   without SAF. DocumentFiles does the walking. */
object Documents {

    const val DIR = "documents"
    const val UNTITLED = "Untitled"

    const val EXTRA_DOCUMENT = "document"
    const val EXTRA_FILE = "file"

    fun isReservedDir(name: String) = name.equals(DIR, ignoreCase = true)

    fun defaultTitle(date: String) = "Document $date"

    /* Empty (or whitespace-only) title on save becomes Untitled — a nameless
       file is one the provider refuses, and a blank row in the list has
       nothing to tap. */
    fun displayTitle(raw: String): String = raw.trim().ifEmpty { UNTITLED }

    fun stem(title: String): String =
        Extractor.sanitize(displayTitle(title)).ifEmpty { UNTITLED }

    fun plainName(stem: String) = "$stem.txt"

    fun slug(plainName: String) = "doc:${stemOf(plainName) ?: plainName}"

    /* A finished document: "Title.txt" or "Title.txt.gz". Half-written parts
       and a name that is only an extension are not documents. */
    fun stemOf(name: String): String? {
        if (name.startsWith(Zips.PART_HEAD)) return null
        val base = when {
            name.endsWith(".txt.gz") -> name.removeSuffix(".txt.gz")
            name.endsWith(".txt") -> name.removeSuffix(".txt")
            else -> return null
        }
        return base.ifEmpty { null }
    }

    fun isPlain(name: String): Boolean =
        stemOf(name) != null && name.endsWith(".txt") && !name.endsWith(".gz")

    fun uniqueStem(wanted: String, taken: Set<String>): String {
        val base = wanted.ifEmpty { UNTITLED }
        if (base !in taken) return base
        var n = 2
        while ("$base ($n)" in taken) n++
        return "$base ($n)"
    }

    data class Item(
        val title: String,
        val plainName: String,
        val ref: String,
        val compressed: Boolean,
    )

    /* Same rule as a novel folder: a loose .txt is the one just written, so
       it wins over its .gz twin; a known-empty loose file does not, because
       that is the stub an interrupted uncompress leaves behind. */
    fun resolve(files: List<Folder.Item>): List<Item> {
        val loose = HashMap<String, Folder.Item>()
        val gz = HashMap<String, Folder.Item>()
        for (e in files) {
            if (e.isDir) continue
            val s = stemOf(e.name) ?: continue
            if (e.name.endsWith(".txt.gz")) gz[s] = e else loose[s] = e
        }
        fun empty(e: Folder.Item) = e.size == 0L
        return (loose.keys + gz.keys).sorted().mapNotNull { s ->
            val l = loose[s]
            val g = gz[s]
            val pick = when {
                l != null && !empty(l) -> l to false
                g != null && !empty(g) -> g to true
                l != null -> l to false
                g != null -> g to true
                else -> return@mapNotNull null
            }
            val (e, compressed) = pick
            Item(
                s,
                plainName(s),
                if (compressed) Zips.gzRef(e.ref) else e.ref,
                compressed,
            )
        }
    }
}
