package com.swissdark.launcher.data

import com.swissdark.launcher.domain.Availability
import com.swissdark.launcher.domain.Episode
import com.swissdark.launcher.domain.Kind
import com.swissdark.launcher.domain.MediaType
import com.swissdark.launcher.domain.Person
import com.swissdark.launcher.domain.Providers
import com.swissdark.launcher.domain.SearchHit
import com.swissdark.launcher.domain.TitleDetails
import com.swissdark.launcher.domain.TitleRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class TmdbException(msg: String) : IOException(msg)

/**
 * TMDB REST client, region IN. Titles and artwork: TMDB. Where to watch: JustWatch (via TMDB).
 * One shared OkHttp client, HTTP/2; the connection stays warm while search is open.
 * Host api.tmdb.org: same API as api.themoviedb.org, which some Indian ISPs (Jio) block by DNS.
 * Used through [TitleSources], which falls back to OMDb + TVmaze when the key is missing or refused.
 */
class TmdbApi(private val http: OkHttpClient, private val key: () -> String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = "https://api.tmdb.org/3".toHttpUrl()

    val hasKey: Boolean get() = key().isNotBlank()

    private fun url(path: String, vararg params: Pair<String, String>): HttpUrl {
        val b = base.newBuilder().addPathSegments(path.trimStart('/'))
        val k = key()
        if (k.length <= 40) b.addQueryParameter("api_key", k)
        for ((n, v) in params) b.addQueryParameter(n, v)
        return b.build()
    }

    private suspend fun get(url: HttpUrl): JsonObject {
        if (!hasKey) throw TmdbException("No TMDB key")
        val (code, body) = http.fetch(request(url))
        if (code !in 200..299) throw TmdbException("HTTP $code")
        return withContext(Dispatchers.Default) { json.parseToJsonElement(body).jsonObject }
    }

    private fun request(url: HttpUrl): Request = Request.Builder().url(url).apply {
        val k = key()
        if (k.length > 40) header("Authorization", "Bearer $k")
        header("Accept", "application/json")
    }.build()

    /**
     * One call to /configuration: true = the key works, false = TMDB refused it (401/403).
     * Throws IOException when TMDB could not be reached at all (unknown, ask again later).
     */
    suspend fun validate(): Boolean {
        if (!hasKey) return false
        val (code, _) = http.fetch(request(url("configuration")))
        return when (code) {
            in 200..299 -> true
            401, 403 -> false
            else -> throw TmdbException("HTTP $code")
        }
    }

    suspend fun searchMulti(q: String): List<SearchHit> {
        val o = get(url("search/multi", "query" to q, "region" to "IN", "include_adult" to "false", "language" to "en-IN"))
        return o.arr("results").mapNotNull { hit(it.jsonObject) }
    }

    suspend fun trending(daily: Boolean): List<SearchHit.Title> {
        val o = get(url("trending/all/" + if (daily) "day" else "week", "language" to "en-IN"))
        return o.arr("results").mapNotNull { hit(it.jsonObject) as? SearchHit.Title }
    }

    suspend fun discover(genre: String): List<SearchHit.Title> {
        val g = Genres.byName[genre] ?: return emptyList()
        val movies = get(url("discover/movie", "with_genres" to g.first.toString(), "watch_region" to "IN", "sort_by" to "popularity.desc"))
            .arr("results").mapNotNull { hit(it.jsonObject, MediaType.MOVIE) as? SearchHit.Title }
        val tv = get(url("discover/tv", "with_genres" to g.second.toString(), "watch_region" to "IN", "sort_by" to "popularity.desc"))
            .arr("results").mapNotNull { hit(it.jsonObject, MediaType.TV) as? SearchHit.Title }
        return (movies + tv).sortedByDescending { it.popularity }
    }

    suspend fun personCredits(id: Int): List<SearchHit.Title> {
        val o = get(url("person/$id/combined_credits", "language" to "en-IN"))
        return (o.arr("cast") + o.arr("crew")).mapNotNull { hit(it.jsonObject) as? SearchHit.Title }
            .distinctBy { it.key }.sortedByDescending { it.popularity }
    }

    suspend fun providers(ref: TitleRef): List<Availability> {
        val p = if (ref.type == MediaType.MOVIE) "movie" else "tv"
        return parseProviders(get(url("$p/${ref.tmdbId}/watch/providers")))
    }

    suspend fun details(ref: TitleRef): TitleDetails {
        val p = if (ref.type == MediaType.MOVIE) "movie" else "tv"
        val extra = if (ref.type == MediaType.MOVIE) "credits,videos,watch/providers,release_dates" else "credits,videos,watch/providers,content_ratings"
        val o = get(url("$p/${ref.tmdbId}", "append_to_response" to extra, "language" to "en-IN"))
        val name = o.str("title") ?: o.str("name") ?: ref.name
        val date = o.str("release_date") ?: o.str("first_air_date")
        val runtime = o.int("runtime") ?: (o["episode_run_time"] as? JsonArray)?.firstOrNull()?.let { (it as JsonPrimitive).intOrNull }
        val cast = (o.obj("credits")?.arr("cast").orEmpty().take(3).map {
            val c = it.jsonObject
            Person(c.int("id") ?: 0, c.str("name") ?: "", c.str("character") ?: "Actor", "Acting")
        } + o.obj("credits")?.arr("crew").orEmpty().filter { it.jsonObject.str("job") == "Director" }.take(1).map {
            val c = it.jsonObject
            Person(c.int("id") ?: 0, c.str("name") ?: "", "Director", "Directing")
        }).take(4)
        val trailer = o.obj("videos")?.arr("results").orEmpty().map { it.jsonObject }
            .firstOrNull { it.str("site") == "YouTube" && it.str("type") == "Trailer" }?.str("key")
            ?: o.obj("videos")?.arr("results").orEmpty().map { it.jsonObject }.firstOrNull { it.str("site") == "YouTube" }?.str("key")
        val rating = if (ref.type == MediaType.MOVIE) {
            o.obj("release_dates")?.arr("results").orEmpty().map { it.jsonObject }
                .firstOrNull { it.str("iso_3166_1") == "IN" }?.arr("release_dates").orEmpty()
                .map { it.jsonObject.str("certification") }.firstOrNull { !it.isNullOrBlank() }
        } else {
            o.obj("content_ratings")?.arr("results").orEmpty().map { it.jsonObject }
                .firstOrNull { it.str("iso_3166_1") == "IN" }?.str("rating")
        }
        val genres = o.arr("genres").mapNotNull { it.jsonObject.str("name") }
        val genreIds = o.arr("genres").mapNotNull { it.jsonObject.int("id") }
        val lang = o.str("original_language")
        val nextEps = if (ref.type == MediaType.TV) {
            val last = o.obj("last_episode_to_air")
            val s = last?.int("season_number") ?: 1
            try { season(ref.tmdbId, s) } catch (_: Exception) { emptyList() }
        } else emptyList()
        return TitleDetails(
            ref = ref.copy(name = name),
            year = date?.take(4)?.takeIf { it.isNotBlank() },
            runtimeMin = runtime,
            seasons = o.int("number_of_seasons"),
            ageRating = rating,
            synopsis = o.str("overview").orEmpty(),
            genres = genres,
            language = Languages.name(lang),
            backdropUrl = o.str("backdrop_path")?.let { img("w1280", it) },
            posterUrl = o.str("poster_path")?.let { img("w342", it) },
            cast = cast,
            trailerKey = trailer,
            availability = o.obj("watch/providers")?.let { parseProviders(it) }.orEmpty(),
            nextEpisodes = nextEps,
            kidsSafe = (o["adult"] as? JsonPrimitive)?.booleanOrNull != true && genreIds.any { it in Genres.kids },
        )
    }

    suspend fun season(tvId: Int, season: Int): List<Episode> {
        val o = get(url("tv/$tvId/season/$season", "language" to "en-IN"))
        return o.arr("episodes").map {
            val e = it.jsonObject
            Episode(season, e.int("episode_number") ?: 0, e.str("name") ?: "", e.int("runtime"), e.str("still_path")?.let { p -> img("w780", p) })
        }
    }

    private fun parseProviders(o: JsonObject): List<Availability> {
        val inn = o.obj("results")?.obj("IN") ?: return emptyList()
        val out = ArrayList<Availability>()
        fun add(field: String, kind: Kind) {
            for (e in inn.arr(field)) {
                val p = e.jsonObject
                val id = p.int("provider_id") ?: continue
                val known = Providers.byTmdb(id)
                val pkg = known?.packages?.firstOrNull()
                out.add(Availability(id, known?.name ?: p.str("provider_name") ?: "?", kind, false, null, pkg, known?.color ?: 0xFF1C1C1E))
            }
        }
        add("flatrate", Kind.STREAM); add("free", Kind.FREE); add("ads", Kind.ADS); add("rent", Kind.RENT); add("buy", Kind.BUY)
        // One row per provider and kind; the same service under two TMDB ids collapses to one.
        return out.distinctBy { it.providerName to it.kind }
    }

    private fun hit(o: JsonObject, forced: MediaType? = null): SearchHit? {
        val mt = forced ?: when (o.str("media_type")) {
            "movie" -> MediaType.MOVIE
            "tv" -> MediaType.TV
            "person" -> return SearchHit.PersonHit(
                Person(
                    o.int("id") ?: return null, o.str("name") ?: return null,
                    when (o.str("known_for_department")) { "Directing" -> "Director"; "Writing" -> "Writer"; else -> "Actor" },
                    o.str("known_for_department") ?: "Acting",
                    o.arr("known_for").size,
                ),
            )
            else -> return null
        }
        val id = o.int("id") ?: return null
        val name = o.str("title") ?: o.str("name") ?: return null
        val year = (o.str("release_date") ?: o.str("first_air_date"))?.take(4)
        val lang = Languages.name(o.str("original_language"))
        val kind = if (mt == MediaType.MOVIE) "Film" else "Series"
        val genreIds = o.arr("genre_ids").mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        val meta = when {
            genreIds.contains(99) -> "Documentary"
            lang != null && lang != "English" -> "$kind · $lang"
            !year.isNullOrBlank() -> "$kind · $year"
            else -> kind
        }
        return SearchHit.Title(
            ref = TitleRef(id, mt, name),
            posterUrl = o.str("poster_path")?.let { img("w342", it) },
            backdropUrl = o.str("backdrop_path")?.let { img("w780", it) },
            meta = meta,
            availability = null,
            kidsSafe = (o["adult"] as? JsonPrimitive)?.booleanOrNull != true && genreIds.any { it in Genres.kids },
            popularity = (o["popularity"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
        )
    }

    companion object {
        fun img(size: String, path: String) = "https://image.tmdb.org/t/p/$size$path"
    }
}

object Genres {
    /** Chip name → (movie genre id, tv genre id). */
    val byName = mapOf(
        "Thriller" to (53 to 9648),
        "Comedy" to (35 to 35),
        "Drama" to (18 to 18),
        "Crime" to (80 to 80),
        "Documentary" to (99 to 99),
        "Kids" to (10751 to 10762),
    )
    val kids = setOf(10751, 16, 10762)
}

object Languages {
    private val names = mapOf(
        "en" to "English", "hi" to "Hindi", "ta" to "Tamil", "te" to "Telugu", "ml" to "Malayalam",
        "kn" to "Kannada", "bn" to "Bengali", "mr" to "Marathi", "pa" to "Punjabi", "ko" to "Korean",
        "ja" to "Japanese", "es" to "Spanish", "fr" to "French", "de" to "German", "zh" to "Chinese", "it" to "Italian",
    )

    fun name(code: String?): String? = code?.let { names[it] ?: it.uppercase() }
}

internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
internal fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
internal fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray).orEmpty()
