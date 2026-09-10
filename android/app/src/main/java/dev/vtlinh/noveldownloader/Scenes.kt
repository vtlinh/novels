package dev.vtlinh.noveldownloader

/* Slack upload names and leftover scenes/ files.

   A posted chapter is {sha256}.txt — SHA-256 of the unzipped UTF-8
   bytes, nothing else. Storage still counts a scenes/ folder if one
   exists from an older build. */
object Scenes {

    const val DIR = "scenes"

    fun chapterBase(filename: String): String {
        val noGz = filename.removeSuffix(".gz")
        return if (noGz.endsWith(".txt", ignoreCase = true)) noGz.dropLast(4) else noGz
    }

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
    fun slackImageName(hash: String) = hash + ".png"
    fun imageName(filename: String) = chapterBase(filename) + ".png"

    fun isSceneFile(name: String): Boolean {
        val n = name.removeSuffix(".gz").lowercase()
        return n.endsWith(".json") || n.endsWith(".png") || n.endsWith(".jpg") ||
            n.endsWith(".jpeg") || n.endsWith(".webp")
    }
}
