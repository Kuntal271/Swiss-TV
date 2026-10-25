package com.swissdark.launcher.data

import com.swissdark.launcher.domain.Episode
import com.swissdark.launcher.domain.MediaType
import com.swissdark.launcher.domain.Person
import com.swissdark.launcher.domain.SearchHit
import com.swissdark.launcher.domain.TitleDetails
import com.swissdark.launcher.domain.TitleRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    private val imdbSuggest = "https://v3.sg.media-imdb.com/suggestion/x/".toHttpUrl()

    /**
     * IMDb's current poster per IMDb id, from its public suggestion endpoint (no key). OMDb's
     * Poster field is an old snapshot: for many titles its m.media-amazon.com image id no longer
     * exists and the CDN answers 404 (blank tiles), while IMDb serves the poster under a new id.
     * Empty on any failure, so OMDb's URL stays the fallback.
     */
    suspend fun imdbPosters(q: String): Map<String, String> = try {
        val u = imdbSuggest.newBuilder().addPathSegment(q.trim().lowercase() + ".json").build()
        val (code, body) = http.fetch(Request.Builder().url(u).header("Accept", "application/json").build())
        if (code !in 200..299) emptyMap()
        else withContext(Dispatchers.Default) { OmdbParse.imdbPosters(json.parseToJsonElement(body).jsonObject) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyMap()
    }

    /** [o] with its Poster replaced by IMDb's current one, when IMDb has one. */
    private suspend fun freshPoster(o: JsonObject, fresh: Map<String, String>? = null): JsonObject {
        val id = o.str("imdbID") ?: return o
        val url = (fresh ?: imdbPosters(id))[id] ?: return o
        return JsonObject(o + ("Poster" to JsonPrimitive(url)))
    }

    suspend fun search(q: String): List<SearchHit.Title> = coroutineScope {
        val fresh = async { imdbPosters(q) }
        val hits = get(url("s" to q))?.let { withContext(Dispatchers.Default) { OmdbParse.search(it) } }.orEmpty()
        if (hits.isEmpty()) {
            fresh.cancel()
            return@coroutineScope hits
        }
        val p = fresh.await()
        // Titles outside the query's top IMDb suggestions: ask IMDb by id, a few in parallel.
        // OMDb's own poster URL is often dead (grey tile), so it is only the last resort.
        val missing = hits.mapNotNull { TitleIds.imdb(it.ref.tmdbId) }.filter { it !in p }.take(PER_ID_POSTERS)
        val byId = missing.map { id -> async { imdbPosters(id)[id]?.let { id to it } } }.mapNotNull { it.await() }.toMap()
        val all = p + byId
        hits.map { h -> TitleIds.imdb(h.ref.tmdbId)?.let(all::get)?.let { h.copy(posterUrl = OmdbParse.encode(it)) } ?: h }
    }

    private companion object {
        const val PER_ID_POSTERS = 10
    }

    /** [posters] = false skips the IMDb poster refresh (callers that only read ids). */
    suspend fun byId(imdbId: String, posters: Boolean = true): JsonObject? = coroutineScope {
        val fresh = if (posters) async { imdbPosters(imdbId) } else null
        val o = get(url("i" to imdbId, "plot" to "full"))
        if (o == null) {
            fresh?.cancel()
            null
        } else if (fresh != null) freshPoster(o, fresh.await()) else o
    }

    /** Raw `s=` search restricted to episodes (used by BackdropArt to find the show of an episode name). */
    suspend fun searchEpisodes(q: String): JsonObject? = get(url("s" to q, "type" to "episode"))

    suspend fun byTitle(title: String, type: MediaType?): JsonObject? = get(
        url(*listOfNotNull("t" to title, "plot" to "full", type?.let { "type" to if (it == MediaType.MOVIE) "movie" else "series" }).toTypedArray()),
    )?.let { freshPoster(it) }

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

    /** Amazon poster URLs carry a raw '@' in the path ("…MV5B…@._V1_SX300.jpg"); kept percent-encoded (the CDN accepts both). */
    fun encode(url: String): String = url.replace("@", "%40")

    fun poster(o: JsonObject): String? = o.na("Poster")?.takeIf { it.startsWith("http") }?.let(::encode)

    /**
     * IMDb suggestion JSON (`{"d":[{"id":"tt…","i":{"imageUrl":"…@._V1_.jpg"}}]}`) → IMDb id to
     * poster URL. IMDb returns the full-size original; `._V1_SX400` asks the CDN for 400 px wide.
     */
    fun imdbPosters(o: JsonObject): Map<String, String> = o.arr("d").mapNotNull { e ->
        val h = e as? JsonObject ?: return@mapNotNull null
        val id = h.str("id")?.takeIf { it.startsWith("tt") } ?: return@mapNotNull null
        val url = h.obj("i")?.str("imageUrl")?.takeIf { it.startsWith("https://m.media-amazon.com/") } ?: return@mapNotNull null
        id to if ("._V1" in url) url.substringBefore("._V1") + "._V1_SX400.jpg" else url
    }.toMap()

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
