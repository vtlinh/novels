package dev.vtlinh.noveldownloader

/* Carrying user marks from a folder-scan slug onto the site slug when the
   Library merges them. They are the same novel; deleting the scan row
   without this drops whatever was stored under the losing slug. */
object SlugMerge {

    /* Prefs prefixes copied onto the winner when it has no value of its own.
       novelRating is the personal star ranking; dropping it made a rated
       folder-scan novel look unrated the moment the site row appeared, and
       the library now sorts by those stars. */
    val PREF_PREFIXES = listOf(
        "lastCh:", "lastChAt:", "readPos:", "readParaText:",
        "ttsPos:", "ttsPosAt:", "ttsParaText:",
        "novelRead:",
        NovelRating.PREF_PREFIX,
    )

    /* The newer last-read stamp. Zero means never opened; a winner that has
       never been opened should inherit the loser's, or the novel drops out
       of Recently Read and equal-star recency after the merge. */
    fun lastReadToCarry(winLastRead: Long, loseLastRead: Long): Long? =
        loseLastRead.takeIf { it > winLastRead }
}
