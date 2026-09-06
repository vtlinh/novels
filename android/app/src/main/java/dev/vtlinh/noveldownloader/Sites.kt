package dev.vtlinh.noveldownloader

import dev.vtlinh.noveldownloader.sites.Freewebnovel
import dev.vtlinh.noveldownloader.sites.Novelfull
import dev.vtlinh.noveldownloader.sites.Readnovel
import dev.vtlinh.noveldownloader.sites.Truyenfull
import dev.vtlinh.noveldownloader.sites.Truyenfullmoi
import dev.vtlinh.noveldownloader.sites.Vivutruyen2

/* The registry. One entry per supported site; everything a site knows is in
   its own class under `sites/`, and everything the app knows about sites goes
   through the Site interface. */
object Sites {

    val all: List<Site> = listOf(Truyenfull, Truyenfullmoi, Novelfull, Readnovel, Freewebnovel, Vivutruyen2)

    fun forUrl(url: String): Site? = all.firstOrNull { it.matches(url) }

    /* Host the novel was downloaded from, for the info screen. Not the
       publisher line the site prints as "Source" — that is a different
       field, and collapsing them would hide that a book on novelfull.net
       is not the one on novelfull.com. www. is dropped so the two
       spellings of truyenfullmoi read as one site. Null when there is no
       URL (folder-scan) or it does not parse. */
    fun website(url: String): String? {
        if (url.isBlank()) return null
        return try {
            java.net.URI(url.trim()).host
                ?.lowercase()
                ?.removePrefix("www.")
                ?.ifEmpty { null }
        } catch (e: Exception) { null }
    }

    /* Normalized busy-key from a novel URL — MUST equal
       Ownership.normKey(<the slug the store records>) for the same novel,
       because these are the two halves of every "is this novel busy?" test:
       the download service publishes this key, and every guard asks with the
       record's slug.

       The site's own normalize() is the only derivation that keeps that
       promise. The last path segment is not the slug on every site:
       truyenfullmoi's novel URL ends "<slug>.<id>" — "so-13-pho-mink.1666"
       against slug "so-13-pho-mink" — so a heuristic key built from the
       segment carried the id, matched nothing, and every guard built on it
       was dead for that site: Check status renamed files under a live
       download, and the delete flows would empty a folder the engine was
       writing into. The heuristic remains only for a URL no site claims,
       where nothing can download and no guard needs the answer. */
    fun slugKey(url: String): String {
        val slug = try {
            forUrl(url)?.normalize(url)?.second?.ifEmpty { null }
        } catch (e: Exception) { null }
        return Ownership.normKey(
            slug ?: url.trimEnd('/').substringAfterLast('/').removeSuffix(".html"),
        )
    }
}
