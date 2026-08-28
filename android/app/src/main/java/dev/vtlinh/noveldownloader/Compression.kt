package dev.vtlinh.noveldownloader

import android.content.Context

object Compression {
    /* `zipDownloads` is the pre-rename preference. Keep the fallback in one
       place so legacy users retain their explicit choice. */
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("app", Context.MODE_PRIVATE)
            .let { it.getBoolean("compressNovels", it.getBoolean("zipDownloads", true)) }
}
