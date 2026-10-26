package com.swissdark.launcher.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * IMDb's public suggestion endpoint (v3.sg.media-imdb.com, no key): a fast, wide catalog that
 * covers Indian titles the other keyless sources miss. The LAST art fallback for Continue
 * watching cards; a hit must equal the name exactly — a near-miss poster is worse than the
 * app's own banner.
 */
object ImdbSuggestParse {
    data class Hit(val id: String, val title: String, val kind: String, val year: Int?, val imageUrl: String?)

    fun parse(json: String): List<Hit> {
        return try {
            val root = Json.parseToJsonElement(json) as? JsonObject ?: return emptyList()
            root.arr("d").mapNotNull { e ->
                (e as? JsonObject)?.let { o ->
                    val img = o.obj("i")
                    Hit(
                        id = o.str("id") ?: return@let null,
                        title = o.str("l") ?: return@let null,
                        kind = o.str("qid") ?: "",
                        year = o.int("y"),
                        imageUrl = img?.str("imageUrl"),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Kinds that are real films and series (never shorts, games or music videos). */
    val TITLE_KINDS = setOf("movie", "tvSeries", "tvMiniSeries", "tvMovie", "tvSpecial", "podcastSeries")

    /** The first [hits] whose normalised title equals [name] and whose kind is a real title. */
    fun best(hits: List<Hit>, name: String): Hit? {
        val q = WatchedStore.norm(name)
        if (q.length < 3) return null
        return hits.firstOrNull { WatchedStore.norm(it.title) == q && it.kind in TITLE_KINDS }
    }
}

class ImdbSuggest(private val http: okhttp3.OkHttpClient) {
    suspend fun search(name: String): List<ImdbSuggestParse.Hit> = withContext(Dispatchers.IO) {
        runCatching {
            val q = java.net.URLEncoder.encode(name.trim(), "UTF-8").replace("+", "%20")
            val req = okhttp3.Request.Builder()
                .url("https://v3.sg.media-imdb.com/suggestion/x/$q.json?includeVideos=0")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .build()
            http.newCall(req).execute().use { r ->
                if (r.isSuccessful) ImdbSuggestParse.parse(r.body?.string().orEmpty()) else emptyList()
            }
        }.getOrDefault(emptyList())
    }

    /** Card art for [name]: the exactly-matching title's poster, else null. Never throws. */
    suspend fun artUrl(name: String): String? =
        ImdbSuggestParse.best(search(name), name)?.imageUrl?.takeIf { it.startsWith("http") }
}
