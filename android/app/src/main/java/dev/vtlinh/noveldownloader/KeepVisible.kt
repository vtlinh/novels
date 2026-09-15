package dev.vtlinh.noveldownloader

/* Where the viewport should sit after rows are inserted above
   or below. RecyclerView's scrollToPositionWithOffset uses this:
   the same row, the same pixels from the top.

   A ScrollView cannot do that — scrollY is an offset into one
   tall blob, so inserting above changes what that number means.
   The picture list uses a RecyclerView for that reason. Reading
   can use the same contract once each chapter is its own row. */
object KeepVisible {

    data class Anchor(val index: Int, val offset: Int)

    fun afterInsert(anchor: Anchor, insertedAt: Int, inserted: Int): Anchor {
        if (inserted < 1 || anchor.index < insertedAt) return anchor
        return Anchor(anchor.index + inserted, anchor.offset)
    }

    /* Center a row of rowH in a viewport of viewH. */
    fun centerOffset(viewH: Int, rowH: Int): Int =
        ((viewH - rowH) / 2).coerceAtLeast(0)

    /* A decode that finished for another row, or after this
       row was recycled, must not be drawn. The view does not
       have to be attached yet — prefetch binds before attach,
       and skipping those left empty boxes. */
    fun stillThisRow(bound: String, decoded: String): Boolean =
        bound.isNotEmpty() && bound == decoded
}
