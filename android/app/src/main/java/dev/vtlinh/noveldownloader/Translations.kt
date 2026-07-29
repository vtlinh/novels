package dev.vtlinh.noveldownloader

/* What becomes of a translation when the chapter it is filed under is about to
   be deleted as a duplicate — with no Android in it, so the rule can be
   exercised directly. It needs to be: getting this wrong destroys content the
   user paid an API to produce, and there is no getting it back.

   The situation is a library that has been through a renaming scheme. An older
   build named a chapter after the number the site printed and put the title in
   the filename — "Chapter 12 - Doi thanh nguoi khac toi se tran.txt" — and a
   newer one names it after its position in the site's listing, "Chapter
   12.txt". A folder downloaded across both ends up holding every chapter
   twice, and dedupeExtras removes the copy nothing points at once its BODY
   hash proves it is the same chapter as the one being kept.

   Removing it used to take its translation with it, by base name, which is
   right for a chapter the site has dropped and wrong here: translations were
   only ever written under the OLD names, so the copy being deleted is the one
   holding the English and the copy being kept has none. The novel lost its
   translation, chapter by chapter, at exactly the moment the duplicate was
   tidied away — and the reader then offered no language to switch to, because
   there was nothing left to switch to.

   So hand it over instead. The kept file is the same chapter, proved by its
   text, so the English filed against the old name is the English for the new
   one. */
object Translations {

    /* One thing to do in translated/ */
    sealed class Move {
        /* the doomed chapter's translation becomes the kept chapter's */
        data class Rename(val from: String, val to: String) : Move()

        /* the kept chapter already has one — this is a genuine duplicate */
        data class Delete(val name: String) : Move()
    }

    /* A source and its translation compress independently, so either can be
       loose or gzipped and the pair need not agree. */
    private fun forms(base: String) = listOf(base, "$base.gz")

    /* `present` is what translated/ holds, named as it is on disk.

       Nothing to do when the doomed chapter has no translation, which is the
       ordinary case — most of a library is untranslated, and this must not
       cost a rename for every duplicate tidied away. */
    fun handover(doomedBase: String, keeperBase: String, present: Set<String>): List<Move> {
        if (doomedBase == keeperBase) return emptyList()
        val doomed = forms(doomedBase).filter { it in present }
        if (doomed.isEmpty()) return emptyList()
        /* Either form counts as the keeper being translated already. Renaming
           onto a name that exists collides — and SAF answers a collision by
           MINTING "Chapter 12.txt (1).gz", a name no pattern in this app
           matches: the English would be neither the old chapter's nor the new
           one's, and invisible to every sweep that might have noticed. */
        val keeperHas = forms(keeperBase).any { it in present }
        return if (keeperHas) {
            doomed.map { Move.Delete(it) }
        } else {
            /* keep whichever suffix the file has: loose stays loose, gzipped
               stays gzipped, so the pair goes on compressing independently */
            doomed.map { Move.Rename(it, keeperBase + it.removePrefix(doomedBase)) }
        }
    }
}
