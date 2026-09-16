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
}
