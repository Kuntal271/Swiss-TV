package com.swissdark.launcher.data

import com.swissdark.launcher.domain.Episode
import com.swissdark.launcher.domain.MediaType
import com.swissdark.launcher.domain.Person
import com.swissdark.launcher.domain.SearchHit
import com.swissdark.launcher.domain.TitleDetails
import com.swissdark.launcher.domain.TitleRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class OmdbException(msg: String) : IOException(msg)

/**
 * OMDb (www.omdbapi.com): IMDb data by title, id or search, plus season episode lists.
 * No trending, no artwork beyond one poster, no where-to-watch: [TitleSources] pairs it with TVmaze.
 */
class OmdbClient(private val http: OkHttpClient, private val key: () -> String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = "https://www.omdbapi.com/".toHttpUrl()

    val hasKey: Boolean get() = key().isNotBlank()

    private fun url(vararg params: Pair<String, String>): HttpUrl {
        val b = base.newBuilder().addQueryParameter("apikey", key())
        for ((n, v) in params) b.addQueryParameter(n, v)
        return b.build()
    }

    /** null = OMDb answered "not found"; throws when OMDb is unreachable or refuses the key. */
    private suspend fun get(url: HttpUrl): JsonObject? {
        if (!hasKey) throw OmdbException("No OMDb key")
        val (code, body) = http.fetch(Request.Builder().url(url).header("Accept", "application/json").build())
        if (code == 401) throw OmdbException("OMDb key refused")
        if (code !in 200..299) throw OmdbException("HTTP $code")
        val o = withContext(Dispatchers.Default) { json.parseToJsonElement(body).jsonObject }
        if (o.str("Response") == "False") {
            val err = o.str("Error").orEmpty()
            if (err.contains("key", ignoreCase = true) || err.contains("limit", ignoreCase = true)) throw OmdbException(err)
            return null
        }
        return o
    }

    suspend fun search(q: String): List<SearchHit.Title> =
        get(url("s" to q))?.let { withContext(Dispatchers.Default) { OmdbParse.search(it) } }.orEmpty()

    suspend fun byId(imdbId: String): JsonObject? = get(url("i" to imdbId, "plot" to "full"))

    suspend fun byTitle(title: String, type: MediaType?): JsonObject? = get(
        url(*listOfNotNull("t" to title, "plot" to "full", type?.let { "type" to if (it == MediaType.MOVIE) "movie" else "series" }).toTypedArray()),
    )

    suspend fun season(imdbId: String, season: Int): List<Episode> =
        get(url("i" to imdbId, "Season" to season.toString()))?.let { OmdbParse.season(it, season) }.orEmpty()
}

/** Pure JSON → domain mapping, unit-tested against saved OMDb responses. */
object OmdbParse {
    private fun JsonObject.na(k: String): String? = str(k)?.trim()?.takeIf { it.isNotEmpty() && it != "N/A" }

    fun type(o: JsonObject): MediaType? = when (o.str("Type")) {
        "movie" -> MediaType.MOVIE
        "series" -> MediaType.TV
        else -> null // "episode", "game"
    }

    /**
     * Amazon poster URLs carry a raw '@' in the path ("…MV5B…@._V1_SX300.jpg"); Coil's URI
     * parser takes it for user-info and the image never loads. Percent-encoded, it loads.
     */
    fun poster(o: JsonObject): String? = o.na("Poster")?.takeIf { it.startsWith("http") }?.replace("@", "%40")

    fun search(o: JsonObject): List<SearchHit.Title> = o.arr("Search").mapNotNull { e ->
        val h = e as? JsonObject ?: return@mapNotNull null
        val type = type(h) ?: return@mapNotNull null
        val imdb = h.na("imdbID") ?: return@mapNotNull null
        val id = TitleIds.fromImdb(imdb) ?: return@mapNotNull null
        val name = h.na("Title") ?: return@mapNotNull null
        val year = h.na("Year")?.take(4)
        val kind = if (type == MediaType.MOVIE) "Film" else "Series"
        SearchHit.Title(
            ref = TitleRef(id, type, name),
            posterUrl = poster(h),
            backdropUrl = null,
            meta = if (year != null) "$kind · $year" else kind,
            availability = null,
            kidsSafe = false,
        )
    }

    fun minutes(s: String?): Int? = s?.trim()?.substringBefore(' ')?.toIntOrNull()

    private val kidsRatings = setOf("G", "TV-Y", "TV-Y7", "TV-G", "U")

    /** Details from `?i=…&plot=full`. Where to watch and episodes come from elsewhere. */
    fun details(o: JsonObject, fallbackType: MediaType): TitleDetails? {
        val imdb = o.na("imdbID") ?: return null
        val type = type(o) ?: fallbackType
        val id = TitleIds.fromImdb(imdb) ?: return null
        val name = o.na("Title") ?: return null
        val genres = o.na("Genre")?.split(',')?.map { it.trim() }.orEmpty()
        val cast = o.na("Actors")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty().take(3)
            .map { Person(0, it, "Actor", "Acting") } +
            o.na("Director")?.split(',')?.map { it.trim() }?.take(1).orEmpty().map { Person(0, it, "Director", "Directing") }
        val rated = o.na("Rated")?.takeUnless { it == "Not Rated" || it == "Unrated" }
        return TitleDetails(
            ref = TitleRef(id, type, name),
            year = o.na("Year")?.take(4),
            runtimeMin = minutes(o.na("Runtime")),
            seasons = o.na("totalSeasons")?.toIntOrNull(),
            ageRating = rated,
            synopsis = o.na("Plot").orEmpty(),
            genres = genres,
            language = o.na("Language")?.substringBefore(',')?.trim(),
            backdropUrl = null,
            posterUrl = poster(o),
            cast = cast.take(4),
            trailerKey = null,
            availability = emptyList(),
            kidsSafe = rated in kidsRatings || genres.any { it == "Family" },
            score = o.na("imdbRating")?.let { "IMDb $it" },
            source = "OMDb",
        )
    }

    fun season(o: JsonObject, season: Int): List<Episode> = o.arr("Episodes").mapNotNull { e ->
        val ep = e as? JsonObject ?: return@mapNotNull null
        val n = ep.na("Episode")?.toIntOrNull() ?: return@mapNotNull null
        Episode(season, n, ep.na("Title") ?: "Episode $n", null, null)
    }
}
