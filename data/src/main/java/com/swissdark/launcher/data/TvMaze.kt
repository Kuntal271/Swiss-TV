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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Title ids without TMDB. [TitleRef.tmdbId] stays an Int so caches, history and Up next keep
 * working unchanged: positive = TMDB id, negative = IMDb number (tt0098904 → -98904),
 * below -1e9 = TVmaze show id for shows IMDb does not know. People from TVmaze are negative too.
 */
object TitleIds {
    private const val MAZE = 1_000_000_000

    fun fromImdb(imdb: String?): Int? =
        imdb?.removePrefix("tt")?.toIntOrNull()?.takeIf { it in 1 until MAZE }?.let { -it }

    fun fromMaze(id: Int): Int = -(MAZE + id)

    fun isTmdb(id: Int) = id > 0

    fun imdb(id: Int): String? = if (id < 0 && -id < MAZE) "tt" + (-id).toString().padStart(7, '0') else null

    fun maze(id: Int): Int? = if (id < 0 && -id >= MAZE) -id - MAZE else null
}

/**
 * TVmaze (api.tvmaze.com, no key): series search, episodes, cast, artwork, airing schedules,
 * people. Its network / web channel gives a rough "where to watch" for India.
 */
class TvMazeClient(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = "https://api.tvmaze.com".toHttpUrl()

    private fun url(path: String, vararg params: Pair<String, String>): HttpUrl {
        val b = base.newBuilder().addPathSegments(path.trimStart('/'))
        for ((n, v) in params) b.addQueryParameter(n, v)
        return b.build()
    }

    /** null = 404. */
    private suspend fun get(url: HttpUrl): JsonElement? {
        val (code, body) = http.fetch(Request.Builder().url(url).header("Accept", "application/json").build())
        if (code == 404) return null
        if (code !in 200..299) throw IOException("TVmaze HTTP $code")
        return withContext(Dispatchers.Default) { json.parseToJsonElement(body) }
    }

    /** (score, show) pairs, best first. */
    suspend fun searchShows(q: String): List<Pair<Double, JsonObject>> =
        (get(url("search/shows", "q" to q)) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val show = o.obj("show") ?: return@mapNotNull null
            ((o["score"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) to show
        }

    suspend fun searchPeople(q: String): List<Pair<Double, JsonObject>> =
        (get(url("search/people", "q" to q)) as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val p = o.obj("person") ?: return@mapNotNull null
            ((o["score"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) to p
        }

    /** Show by IMDb id (TVmaze answers with a redirect to /shows/:id, which OkHttp follows). */
    suspend fun lookupImdb(imdb: String): JsonObject? = get(url("lookup/shows", "imdb" to imdb)) as? JsonObject

    /** With [embeds]: episodes, cast and images in the same response (one call for the title page). */
    suspend fun show(id: Int, embeds: Boolean = true): JsonObject? {
        val b = base.newBuilder().addPathSegments("shows/$id")
        if (embeds) b.addEncodedQueryParameter("embed[]", "episodes").addEncodedQueryParameter("embed[]", "cast")
            .addEncodedQueryParameter("embed[]", "images")
        return get(b.build()) as? JsonObject
    }

    /** Episodes airing on [date] (yyyy-MM-dd): streaming services worldwide, or TV channels in [country]. */
    suspend fun schedule(date: String, web: Boolean, country: String?): List<JsonObject> {
        val params = listOfNotNull("date" to date, country?.let { "country" to it }).toTypedArray()
        val arr = get(url(if (web) "schedule/web" else "schedule", *params)) as? JsonArray
        return TvMazeParse.scheduleShows(arr ?: JsonArray(emptyList()))
    }

    suspend fun castCredits(personId: Int): List<JsonObject> =
        (get(url("people/$personId/castcredits", "embed" to "show")) as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.obj("_embedded")?.obj("show") }
}

/** Pure JSON → domain mapping, unit-tested against saved TVmaze responses. */
object TvMazeParse {

    fun ref(show: JsonObject): TitleRef? {
        val id = show.int("id") ?: return null
        val name = show.str("name") ?: return null
        val imdb = show.obj("externals")?.str("imdb")
        return TitleRef(TitleIds.fromImdb(imdb) ?: TitleIds.fromMaze(id), MediaType.TV, name)
    }

    fun imdb(show: JsonObject): String? = show.obj("externals")?.str("imdb")

    fun genres(show: JsonObject): List<String> = show.arr("genres").mapNotNull { (it as? JsonPrimitive)?.content }

    private val notForKids = setOf("Crime", "Horror", "Thriller", "Adult", "War", "Espionage", "Legal", "Medical", "Supernatural")
    private val kidsTypes = setOf("Scripted", "Animation")

    /**
     * Strict kids flag without an age rating: TVmaze's Children genre; or Family on a scripted or
     * animated show with no adult genre and more than just Drama (reality, talk shows and family
     * dramas are tagged Family too, but are not for children).
     */
    fun kids(show: JsonObject): Boolean {
        val g = genres(show)
        if ("Children" in g) return g.none { it in notForKids }
        if ("Family" !in g) return false
        if (show.str("type") !in kidsTypes) return false
        if (g.any { it in notForKids }) return false
        val rest = g - "Family"
        return rest.isNotEmpty() && rest != listOf("Drama") || show.str("type") == "Animation"
    }

    fun hit(show: JsonObject): SearchHit.Title? {
        val ref = ref(show) ?: return null
        val lang = show.str("language")
        val year = show.str("premiered")?.take(4)
        val genres = genres(show)
        val meta = when {
            show.str("type") == "Documentary" -> "Documentary"
            "Anime" in genres -> "Anime" + (year?.let { " · $it" } ?: "")
            lang != null && lang != "English" -> "Series · $lang"
            year != null -> "Series · $year"
            else -> "Series"
        }
        return SearchHit.Title(
            ref = ref,
            posterUrl = show.obj("image")?.str("medium") ?: show.obj("image")?.str("original"),
            backdropUrl = null,
            meta = meta,
            availability = null,
            kidsSafe = kids(show),
            popularity = show.int("weight")?.toDouble() ?: 0.0,
        )
    }

    /** The show's streaming service or channel, mapped to an app on this TV where we know one. */
    fun availability(show: JsonObject): List<Availability> {
        val names = listOfNotNull(show.obj("webChannel")?.str("name"), show.obj("network")?.str("name"))
        return names.mapNotNull { n -> provider(n) }.distinctBy { it.key }.map { p ->
            Availability(p.tmdbIds.first(), p.name, Kind.STREAM, false, null, p.packages.firstOrNull(), p.color)
        }
    }

    /** Studio/channel → the service that streams it in India. Rough, and labelled as such in the UI. */
    fun provider(network: String): Providers.Provider? {
        val n = network.lowercase()
        val key = when {
            "minitv" in n -> null
            "netflix" in n -> "netflix"
            "prime video" in n || n.startsWith("amazon") -> "prime"
            "hotstar" in n || "jiocinema" in n || n.startsWith("disney") || n.startsWith("star ") || n == "star plus" ||
                n.startsWith("colors") || n == "hbo" || n == "hbo max" || n == "max" || n == "hulu" || n == "fx" -> "jiohotstar"
            n.startsWith("sony") -> "sonyliv"
            n.startsWith("zee") -> "zee5"
            "apple tv" in n -> "appletv"
            "youtube" in n -> "youtube"
            "crunchyroll" in n -> "crunchyroll"
            else -> null
        }
        return key?.let { Providers.byKey(it) }
    }

    fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()

    /** Episodes of the latest season that has started airing (by airstamp/airdate vs [today]). */
    fun latestSeason(show: JsonObject, today: String): List<Episode> {
        val eps = show.obj("_embedded")?.arr("episodes").orEmpty().mapNotNull { it as? JsonObject }
            .filter { it.int("number") != null && it.int("season") != null }
        if (eps.isEmpty()) return emptyList()
        val aired = eps.filter { (it.str("airdate") ?: "9999").let { d -> d.isNotEmpty() && d <= today } }
        val season = (aired.ifEmpty { eps }).maxOf { it.int("season")!! }
        return eps.filter { it.int("season") == season }.map { e ->
            Episode(season, e.int("number")!!, e.str("name") ?: "", e.int("runtime"), e.obj("image")?.str("medium"))
        }
    }

    fun backdrop(show: JsonObject): String? {
        val imgs = show.obj("_embedded")?.arr("images").orEmpty().mapNotNull { it as? JsonObject }.filter { it.str("type") == "background" }
        val best = imgs.firstOrNull { (it["main"] as? JsonPrimitive)?.content == "true" } ?: imgs.firstOrNull()
        return best?.obj("resolutions")?.obj("original")?.str("url")
    }

    fun details(show: JsonObject, today: String): TitleDetails? {
        val ref = ref(show) ?: return null
        val genres = genres(show)
        val eps = show.obj("_embedded")?.arr("episodes").orEmpty().mapNotNull { it as? JsonObject }
        val cast = show.obj("_embedded")?.arr("cast").orEmpty().mapNotNull { it as? JsonObject }.take(4).mapNotNull { c ->
            val p = c.obj("person") ?: return@mapNotNull null
            Person(-(p.int("id") ?: return@mapNotNull null), p.str("name") ?: return@mapNotNull null, c.obj("character")?.str("name") ?: "Actor", "Acting")
        }
        val rating = (show.obj("rating")?.get("average") as? JsonPrimitive)?.doubleOrNull
        return TitleDetails(
            ref = ref,
            year = show.str("premiered")?.take(4),
            runtimeMin = show.int("averageRuntime") ?: show.int("runtime"),
            seasons = eps.mapNotNull { it.int("season") }.distinct().size.takeIf { it > 0 },
            ageRating = null,
            synopsis = show.str("summary")?.let { stripHtml(it) }.orEmpty(),
            genres = genres,
            language = show.str("language"),
            backdropUrl = backdrop(show),
            posterUrl = show.obj("image")?.str("original") ?: show.obj("image")?.str("medium"),
            cast = cast,
            trailerKey = null,
            availability = availability(show),
            nextEpisodes = latestSeason(show, today),
            kidsSafe = kids(show),
            score = rating?.let { "TVmaze $it" },
            source = "TVmaze",
        )
    }

    /** /schedule/web nests the show under _embedded; /schedule puts it at top level. One entry per show. */
    fun scheduleShows(arr: JsonArray): List<JsonObject> = arr.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        o.obj("show") ?: o.obj("_embedded")?.obj("show")
    }.distinctBy { it.int("id") }

    fun person(p: JsonObject): Person? {
        val id = p.int("id") ?: return null
        return Person(-id, p.str("name") ?: return null, "Actor", "Acting")
    }
}
