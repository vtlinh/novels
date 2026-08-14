package dev.vtlinh.noveldownloader

import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/* Personal novel ranking: full stars only, 1..MAX. Stored in the same prefs
   bag as finished marks. Tapping the star already set clears the rating. */
object NovelRating {

    const val MAX = 10

    private fun key(slug: String) = "novelRating:$slug"

    fun get(prefs: SharedPreferences, slug: String): Int =
        prefs.getInt(key(slug), 0).coerceIn(0, MAX)

    fun set(prefs: SharedPreferences, slug: String, stars: Int) {
        val v = stars.coerceIn(0, MAX)
        prefs.edit().apply {
            if (v <= 0) remove(key(slug)) else putInt(key(slug), v)
        }.apply()
    }

    /* Pure toggle rule: tap N when already N → clear; otherwise set to N. */
    fun next(current: Int, tapped: Int): Int {
        val want = tapped.coerceIn(1, MAX)
        return if (current.coerceIn(0, MAX) == want) 0 else want
    }

    /* Tap star N: set to N, or clear if it was already N. Returns the new value. */
    fun toggle(prefs: SharedPreferences, slug: String, stars: Int): Int {
        val n = next(get(prefs, slug), stars)
        set(prefs, slug, n)
        return n
    }

    /* Compact "8x★" for the library row — a count, not a row of ten stars. */
    fun bar(stars: Int): String {
        val n = stars.coerceIn(0, MAX)
        return "${n}x★"
    }
}

/* Everything the novel-info header wants, scraped from one novel page. */
data class NovelPageInfo(
    val author: String? = null,
    val altNames: String? = null,
    val genres: String? = null,
    val source: String? = null,
    val description: String? = null,
    val statusLabel: String? = null,
) {
    val isBlank: Boolean
        get() = author.isNullOrBlank() && altNames.isNullOrBlank() &&
            genres.isNullOrBlank() && source.isNullOrBlank() &&
            description.isNullOrBlank() && statusLabel.isNullOrBlank()

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun from(site: Site, doc: Document) = NovelPageInfo(
            author = site.author(doc),
            altNames = site.alternativeNames(doc),
            genres = site.genres(doc),
            source = site.source(doc),
            description = site.description(doc),
            statusLabel = site.statusLabel(doc)
                ?: if (site.isCompleted(doc)) "Completed" else null,
        )

        /* Fetch the novel page and scrape its info. Null on network / unsupported. */
        fun fetch(url: String): NovelPageInfo? {
            val site = Sites.forUrl(url) ?: return null
            val (base, _) = site.normalize(url)
            val html = try {
                client.newCall(
                    Request.Builder().url(base)
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36",
                        )
                        .header("Accept-Language", "vi,en;q=0.8")
                        .build(),
                ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            } catch (e: Exception) {
                null
            } ?: return null
            return from(site, Jsoup.parse(html, base))
        }
    }
}
