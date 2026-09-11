package dev.vtlinh.noveldownloader

/* Prefs a Library erase must drop — and when.

   THE DEFECT. eraseNovel removed lastCh / ttsPos / the finished mark on
   the main thread before the folder delete ran. The failure path rolled
   the garbage set back and toasted "nothing was removed", but the listen
   spot was already gone, so Continue opened chapter 1 on a novel still
   in the library. These keys are cleared only after the folder is
   actually gone. */
object NovelErase {

    val PREF_PREFIXES = listOf(
        "novelHot:",
        "novelRead:",
        "lastCh:",
        "readPos:",
        "readParaText:",
        "ttsPos:",
        "ttsParaText:",
    )

    fun prefKeys(slug: String): List<String> = PREF_PREFIXES.map { it + slug }

    /* Empty when the folder is still there — the caller must leave the
       saved place and marks alone. */
    fun prefKeysIfDeleted(folderDeleted: Boolean, slug: String): List<String> =
        if (folderDeleted) prefKeys(slug) else emptyList()
}
