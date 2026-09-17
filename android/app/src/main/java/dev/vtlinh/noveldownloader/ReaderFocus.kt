package dev.vtlinh.noveldownloader

/* A tap on selectable chapter text focuses the row. RecyclerView then
   tries to bring that focused child on screen. A chapter is taller than
   the page, so "on screen" means aligning the row top — a jump to the
   start of the chapter. A tap must leave the page where it is.

   THE DEFECT. After each chapter became its own row, a single tap
   mid-chapter sometimes scrolled: the row was not focused yet, the tap
   focused it, and requestChildOnScreen used the full row bounds. Already
   focused (a second tap on the same chapter) did nothing — "sometimes". */
object ReaderFocus {

    /* How far RecyclerView would scroll to put the focused row's full
       bounds on screen — the requestChildOnScreen path. Same arithmetic
       as LayoutManager.getChildRectangleOnScreenScrollAmount for a rect
       that is the child itself. */
    fun bringIntoViewDy(childTop: Int, childH: Int, viewH: Int): Int {
        if (viewH <= 0) return 0
        val childBottom = childTop + childH
        val offScreenTop = minOf(0, childTop)
        val offScreenBottom = maxOf(0, childBottom - viewH)
        return if (offScreenTop != 0) offScreenTop
        else minOf(childTop, offScreenBottom)
    }

    /* Honour the bring-into-view only when it would not move the page.
       A non-zero jump is the tap-scroll bug. */
    fun honorBringIntoView(childTop: Int, childH: Int, viewH: Int): Boolean =
        bringIntoViewDy(childTop, childH, viewH) == 0
}
