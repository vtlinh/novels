package dev.vtlinh.noveldownloader

import dev.vtlinh.noveldownloader.sites.Novelfull
import dev.vtlinh.noveldownloader.sites.Truyenfull

/* The registry. One entry per supported site; everything a site knows is in
   its own class under `sites/`, and everything the app knows about sites goes
   through the Site interface. */
object Sites {

    val all: List<Site> = listOf(Truyenfull, Novelfull)

    fun forUrl(url: String): Site? = all.firstOrNull { it.matches(url) }
}
