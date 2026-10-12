package dev.vtlinh.noveldownloader

/* A character inside the loaded reading window. `row` is the index in
   that window (not the novel listing). `off` is a character in that
   chapter's own body.

   Prepending chapters bumps `row` and leaves `off` alone — the same
   contract KeepVisible.afterInsert uses for a RecyclerView row. */
data class ReaderPlace(val row: Int, val off: Int) {

    fun afterInsert(insertedAt: Int, inserted: Int): ReaderPlace {
        val kept = KeepVisible.afterInsert(KeepVisible.Anchor(row, off), insertedAt, inserted)
        return ReaderPlace(kept.index, kept.offset)
    }

    companion object {
        /* The row an open lands on, given the listing index of each loaded
           row. When the chapter asked for would not read it has no row; the
           window still holds the chapters around it, and row 0 is the one
           two BEFORE it — read aloud from the top, that is the wrong text.
           Land on the next chapter that did load, as an append skips it. */
        fun landingRow(loaded: List<Int>, wanted: Int): Int {
            loaded.indexOf(wanted).takeIf { it >= 0 }?.let { return it }
            loaded.indexOfFirst { it > wanted }.takeIf { it >= 0 }?.let { return it }
            return loaded.lastIndex.coerceAtLeast(0)
        }
    }
}
