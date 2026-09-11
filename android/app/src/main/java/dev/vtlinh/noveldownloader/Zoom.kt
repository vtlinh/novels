package dev.vtlinh.noveldownloader

/* Pinch scale for the full-screen chapter picture. 1 is fit-center;
   a scale above that can pan. Does not wrap past the min/max. */
object Zoom {
    const val MIN = 1f
    const val MAX = 5f

    fun scale(current: Float, factor: Float, min: Float = MIN, max: Float = MAX): Float =
        (current * factor).coerceIn(min, max)

    fun canPan(scale: Float, min: Float = MIN): Boolean = scale > min
}
