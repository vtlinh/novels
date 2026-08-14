package dev.vtlinh.noveldownloader

/* How much of the download folder is novels. The tree can be a folder the
   user also keeps other things in, so this is not "size of the tree": it
   walks each immediate subdirectory, counts chapter files (loose, gzipped,
   in-progress, and translations), and ignores everything else.

   SAF cannot be faked off-device, so the walk arrives as lists — the same
   shape Folder uses. Both a loose chapter and its .gz occupy space until
   the compress pass finishes; counting only one of them would under-report
   the thing this number is for. A size of -1 means the provider did not
   report one, which is not zero. */
object Storage {

    data class Total(val bytes: Long, val files: Int, val unknown: Int)

    fun isCounted(name: String): Boolean {
        if (Zips.isPartName(name)) return true
        val base = if (Zips.isGzName(name)) name.removeSuffix(".gz") else name
        return ChapterName.RE.matches(base)
    }

    fun total(
        root: List<Folder.Item>,
        childrenOf: (String) -> List<Folder.Item>,
    ): Total {
        var bytes = 0L
        var files = 0
        var unknown = 0
        fun add(items: List<Folder.Item>) {
            for (e in items) {
                if (e.isDir || !isCounted(e.name)) continue
                files++
                if (e.size < 0L) unknown++ else bytes += e.size
            }
        }
        for (dir in root) {
            if (!dir.isDir) continue
            val kids = childrenOf(dir.ref)
            add(kids)
            val translated = kids.firstOrNull { it.isDir && it.name == "translated" }
            if (translated != null) add(childrenOf(translated.ref))
        }
        return Total(bytes, files, unknown)
    }

    fun label(t: Total): String {
        if (t.files == 0) return "0 B"
        if (t.files == t.unknown) return "Unknown"
        return format(t.bytes)
    }

    fun format(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.lastIndex) {
            v /= 1024.0
            i++
        }
        var tenths = kotlin.math.round(v * 10.0).toLong()
        if (tenths >= 10240L && i < units.lastIndex) {
            tenths = 10L
            i++
        }
        return if (tenths % 10L == 0L) "${tenths / 10} ${units[i]}"
        else "${tenths / 10}.${tenths % 10} ${units[i]}"
    }
}
