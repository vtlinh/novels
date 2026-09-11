package dev.vtlinh.noveldownloader

/* Which first-level directories the compress pass may rewrite. Kept off
   Android so the empty-owned rule can be tested without SAF. */
object CompressWalk {

    /* An empty owned set means no novels are on record — not "walk
       everything". The tree is whichever folder the user picked and may
       hold their own files; a chapter-shaped name in one of those is
       still theirs. Reserved names are Documents.isReservedDir — the
       pasted-text store and Android's Documents folder — so a rename
       of DIR cannot silently start gzipping that folder as a novel. */
    fun includeNovelDir(name: String, owned: Set<String>): Boolean =
        !Documents.isReservedDir(name) && name in owned
}
