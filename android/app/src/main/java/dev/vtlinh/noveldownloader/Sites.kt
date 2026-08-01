package dev.vtlinh.noveldownloader

import dev.vtlinh.noveldownloader.sites.Freewebnovel
import dev.vtlinh.noveldownloader.sites.Novelfull
import dev.vtlinh.noveldownloader.sites.Readnovel
import dev.vtlinh.noveldownloader.sites.Truyenfull
import dev.vtlinh.noveldownloader.sites.Truyenfullmoi

/* The registry. One entry per supported site; everything a site knows is in
   its own class under `sites/`, and everything the app knows about sites goes
   through the Site interface. */
object Sites {

    val all: List<Site> = listOf(Truyenfull, Truyenfullmoi, Novelfull, Readnovel, Freewebnovel)

    fun forUrl(url: String): Site? = all.firstOrNull { it.matches(url) }
}
