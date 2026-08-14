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

    data class Total(val bytes: Long, val files: Int, val unknown: Int) {
        operator fun plus(o: Total) = Total(bytes + o.bytes, files + o.files, unknown + o.unknown)
    }

    fun isCounted(name: String): Boolean {
        if (Zips.isPartName(name)) return true
        val base = if (Zips.isGzName(name)) name.removeSuffix(".gz") else name
        return ChapterName.RE.matches(base)
    }

    /* One directory listing — files only. Directory sizes are ignored even
       when the provider reports them: they are not a chapter, and some
       providers fill them with a recursive sum we must not double-count. */
    fun of(items: List<Folder.Item>): Total {
        var bytes = 0L
        var files = 0
        var unknown = 0
        for (e in items) {
            if (e.isDir || !isCounted(e.name)) continue
            files++
            if (e.size < 0L) unknown++ else bytes += e.size
        }
        return Total(bytes, files, unknown)
    }

    fun total(
        root: List<Folder.Item>,
        childrenOf: (String) -> List<Folder.Item>,
    ): Total {
        var acc = Total(0L, 0, 0)
        for (dir in root) {
            if (!dir.isDir) continue
            val kids = childrenOf(dir.ref)
            acc += of(kids)
            val translated = kids.firstOrNull { it.isDir && it.name == "translated" }
            if (translated != null) acc += of(childrenOf(translated.ref))
        }
        return acc
    }

    fun label(t: Total): String {
        if (t.files == 0) return "0 B"
        if (t.files == t.unknown) return "Unknown"
        return format(t.bytes)
    }

    /* A stored per-novel size is usable only when it was actually measured
       (not -1) AND the folder looks as it did then. Downloads, deletes,
       compress, and translations all move a directory mtime; a provider
       that reports none answers 0, which is not a timestamp — equal zeros
       are not evidence the folder is unchanged, so this refuses them.
       App writes also forget the stored size, because a provider can
       report a stale non-zero mtime just after a rewrite. */
    fun remembered(bytes: Long, stored: Folder.Stamp?, now: Folder.Stamp): Long? {
        if (bytes < 0L) return null
        if (!Folder.folderUnchanged(stored, now)) return null
        if (now.dirMod == 0L) return null
        if (now.trId.isNotEmpty() && now.trMod == 0L) return null
        return bytes
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
