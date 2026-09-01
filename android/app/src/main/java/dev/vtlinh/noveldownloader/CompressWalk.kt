package dev.vtlinh.noveldownloader

/* Which first-level directories the compress pass may rewrite. Kept off
   Android so the empty-owned rule can be tested without SAF. */
object CompressWalk {

    /* An empty owned set means no novels are on record — not "walk
       everything". The tree is whichever folder the user picked and may
       hold their own files; a chapter-shaped name in one of those is
       still theirs. The reserved-name check is the same rule as
       Documents.isReservedDir (`documents`, any case). */
    fun includeNovelDir(name: String, owned: Set<String>): Boolean =
        !name.equals("documents", ignoreCase = true) && name in owned
}
