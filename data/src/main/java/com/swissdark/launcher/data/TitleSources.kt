package com.swissdark.launcher.data

import android.content.Context
import android.util.LruCache
import com.swissdark.launcher.domain.Availability
import com.swissdark.launcher.domain.MediaType
import com.swissdark.launcher.domain.SearchHit
import com.swissdark.launcher.domain.TitleDetails
import com.swissdark.launcher.domain.TitleRef
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.io.IOException
import java.time.LocalDate

/**
 * Older code (Up next matching in Watched.kt) names the title provider `TmdbClient`; it is now
 * this chain, with the same method names, so it gets the fallback too.
 */
typealias TmdbClient = TitleSources

/**
 * Where titles come from, in order of preference:
 * 1. TMDB, when a key is set and TMDB accepts it (checked once with /configuration, remembered per key).
 * 2. Otherwise OMDb (when an OMDb key is set) + TVmaze (no key), merged on the IMDb id.
 * Ids from the fallback are encoded in [TitleRef.tmdbId] by [TitleIds], so a title found either
 * way opens either way.
 */
class TitleSources(
    context: Context,
    http: OkHttpClient,
    private val tmdbKey: () -> String,
    omdbKey: () -> String,
) {
    val tmdbApi = TmdbApi(http, tmdbKey)
    val omdb = OmdbClient(http, omdbKey)
    val tvmaze = TvMazeClient(http)

    private val prefs = context.getSharedPreferences("titles", Context.MODE_PRIVATE)
    private val checkLock = Mutex()
    @Volatile private var checked: Pair<String, Boolean>? =
        prefs.getString("tmdb_key_hash", null)?.let { it to prefs.getBoolean("tmdb_valid", false) }
    @Volatile private var unreachableAt = 0L

    /** TVmaze needs no key, so online titles are always available. */
    val hasKey: Boolean get() = true

    private fun hash(k: String) = k.hashCode().toString(16) + ":" + k.length

    /** Known without the network: true once TMDB has accepted the current key. */
    val tmdbActive: Boolean get() {
        val k = tmdbKey()
        if (k.isBlank()) return false
        val c = checked ?: return false
        return c.first == hash(k) && c.second
    }

    /** "TMDB", "OMDb + TVmaze" or "TVmaze": what titles come from right now. */
    val label: String get() = when {
        tmdbActive -> "TMDB"
        omdb.hasKey -> "OMDb + TVmaze"
        else -> "TVmaze"
    }

    /** Checks the TMDB key once per key (result kept across restarts); network trouble asks again a minute later. */
    suspend fun useTmdb(): Boolean {
        val k = tmdbKey()
        if (k.isBlank()) return false
        val h = hash(k)
        checked?.let { if (it.first == h) return it.second }
        if (System.currentTimeMillis() - unreachableAt < 60_000) return false
        return checkLock.withLock {
            checked?.let { if (it.first == h) return@withLock it.second }
            try {
                val ok = tmdbApi.validate()
                checked = h to ok
                prefs.edit().putString("tmdb_key_hash", h).putBoolean("tmdb_valid", ok).apply()
                ok
            } catch (_: IOException) {
                unreachableAt = System.currentTimeMillis()
                false
            }
        }
    }

    /** Cache keys for lists differ per source so a switch to TMDB does not serve fallback lists. */
    // "alt2": lists cached before OMDb posters were refreshed from IMDb carry dead poster URLs.
    suspend fun cacheTag(): String = if (useTmdb()) "" else "\u001Falt2"

    // ---- What TVmaze told us about each title: availability and show id for later lookups ----
    private val availById = LruCache<Int, List<Availability>>(600)
    private val mazeByRef = LruCache<Int, Int>(600)

    private fun note(show: JsonObject) {
        val ref = TvMazeParse.ref(show) ?: return
        availById.put(ref.tmdbId, TvMazeParse.availability(show))
        show.int("id")?.let { mazeByRef.put(ref.tmdbId, it) }
    }

    // ---- Search ----
    suspend fun searchMulti(q: String): List<SearchHit> = if (useTmdb()) tmdbApi.searchMulti(q) else fallbackSearch(q)

    private suspend fun fallbackSearch(q: String): List<SearchHit> = coroutineScope {
        val maze = async { runCatching { tvmaze.searchShows(q) } }
        val om = async { if (omdb.hasKey) runCatching { omdb.search(q) } else Result.success(emptyList()) }
        val ppl = async { runCatching { tvmaze.searchPeople(q) } }
        val mz = maze.await()
        val o = om.await()
        if (mz.isFailure && o.isFailure) throw (mz.exceptionOrNull() as? IOException ?: IOException("No title source reachable"))
        val shows = mz.getOrNull().orEmpty()
        shows.forEach { note(it.second) }
        val merged = merge(q, shows.mapNotNull { (s, show) -> TvMazeParse.hit(show)?.let { it to s } }, o.getOrNull().orEmpty())
        val people = ppl.await().getOrNull().orEmpty().filter { it.first >= 0.5 }.take(3)
            .mapNotNull { TvMazeParse.person(it.second)?.let { p -> SearchHit.PersonHit(p) } }
        merged + people
    }

    // ---- Trending: TMDB, else what is airing (TVmaze schedules) ----
    suspend fun trending(daily: Boolean): List<SearchHit.Title> = if (useTmdb()) tmdbApi.trending(daily) else airing(if (daily) 1 else 7)

    /** Shows with new episodes in the last [days] days, services you can watch in India first. */
    private suspend fun airing(days: Int): List<SearchHit.Title> = coroutineScope {
        val today = LocalDate.now()
        val calls = (0 until days).map { d ->
            val date = today.minusDays(d.toLong()).toString()
            async { runCatching { tvmaze.schedule(date, web = true, country = null) + tvmaze.schedule(date, web = false, country = "IN") } }
        }.awaitAll()
        if (calls.all { it.isFailure }) throw (calls.first().exceptionOrNull() as? IOException ?: IOException("TVmaze unreachable"))
        val shows = calls.flatMap { it.getOrNull().orEmpty() }.distinctBy { it.int("id") }
        shows.forEach { note(it) }
        rankAiring(shows).take(40)
    }

    private fun rankAiring(shows: List<JsonObject>): List<SearchHit.Title> = shows.mapNotNull { s ->
        val hit = TvMazeParse.hit(s) ?: return@mapNotNull null
        val inIndia = TvMazeParse.availability(s).isNotEmpty() || s.obj("network")?.obj("country")?.str("code") == "IN"
        val hasArt = hit.posterUrl != null
        Triple(hit, inIndia, hasArt)
    }.sortedWith(compareByDescending<Triple<SearchHit.Title, Boolean, Boolean>> { it.second }.thenByDescending { it.third }.thenByDescending { it.first.popularity })
        .map { it.first }

    // ---- Genres and people ----
    suspend fun discover(genre: String): List<SearchHit.Title> {
        if (useTmdb()) return tmdbApi.discover(genre)
        val want = when (genre) {
            "Thriller" -> setOf("Thriller", "Mystery", "Horror", "Espionage")
            "Crime" -> setOf("Crime", "Legal")
            "Kids" -> setOf("Children", "Family")
            else -> setOf(genre)
        }
        return coroutineScope {
            val today = LocalDate.now()
            (0 until 7).map { d ->
                async { runCatching { tvmaze.schedule(today.minusDays(d.toLong()).toString(), web = true, country = null) }.getOrNull().orEmpty() }
            }.awaitAll().flatten().distinctBy { it.int("id") }
                .filter { s -> if (genre == "Documentary") s.str("type") == "Documentary" else TvMazeParse.genres(s).any { it in want } }
                .also { l -> l.forEach { note(it) } }
                .let { rankAiring(it) }
        }
    }

    suspend fun personCredits(id: Int): List<SearchHit.Title> {
        if (id > 0) return tmdbApi.personCredits(id)
        return tvmaze.castCredits(-id).distinctBy { it.int("id") }.onEach { note(it) }
            .mapNotNull { TvMazeParse.hit(it) }.sortedByDescending { it.popularity }
    }

    // ---- Where to watch ----
    suspend fun providers(ref: TitleRef): List<Availability> {
        if (TitleIds.isTmdb(ref.tmdbId)) return if (useTmdb()) tmdbApi.providers(ref) else emptyList()
        availById.get(ref.tmdbId)?.let { return it }
        if (ref.type == MediaType.MOVIE) return emptyList() // TVmaze is series only; OMDb has no availability
        // The bare show (no episode/cast embeds) is enough for its channel.
        val show = TitleIds.imdb(ref.tmdbId)?.let { tvmaze.lookupImdb(it) }
            ?: (TitleIds.maze(ref.tmdbId) ?: mazeByRef.get(ref.tmdbId))?.let { tvmaze.show(it, embeds = false) }
        if (show == null) { availById.put(ref.tmdbId, emptyList()); return emptyList() }
        note(show)
        return TvMazeParse.availability(show)
    }

    private suspend fun mazeShow(ref: TitleRef): JsonObject? {
        val id = TitleIds.maze(ref.tmdbId) ?: mazeByRef.get(ref.tmdbId)
            ?: TitleIds.imdb(ref.tmdbId)?.let { tvmaze.lookupImdb(it)?.int("id") }
            ?: return null
        return tvmaze.show(id)
    }

    // ---- Details ----
    suspend fun details(ref: TitleRef): TitleDetails {
        if (TitleIds.isTmdb(ref.tmdbId)) {
            if (useTmdb()) return tmdbApi.details(ref)
            // A TMDB id from before the key stopped working: find the same title by name.
            val same = fallbackSearch(ref.name).filterIsInstance<SearchHit.Title>()
                .firstOrNull { it.ref.type == ref.type && norm(it.ref.name) == norm(ref.name) }
                ?: throw IOException("No match for ${ref.name}")
            return fallbackDetails(same.ref)
        }
        return fallbackDetails(ref)
    }

    private suspend fun fallbackDetails(ref: TitleRef): TitleDetails = coroutineScope {
        val imdb = TitleIds.imdb(ref.tmdbId)
        val om = async { if (imdb != null && omdb.hasKey) runCatching { omdb.byId(imdb) } else null }
        val mz = async { if (ref.type == MediaType.TV) runCatching { mazeShow(ref) } else null }
        val today = LocalDate.now().toString()
        val o = om.await()?.getOrNull()?.let { OmdbParse.details(it, ref.type) }
        val show = mz.await()?.getOrNull()
        show?.let { note(it) }
        val m = show?.let { TvMazeParse.details(it, today) }
        val merged = when {
            o != null && m != null -> o.copy(
                // OMDb: IMDb rating, age rating, plot. TVmaze: art, cast with ids, episodes, channel.
                ref = o.ref.copy(type = ref.type),
                synopsis = o.synopsis.ifBlank { m.synopsis },
                backdropUrl = m.backdropUrl,
                posterUrl = o.posterUrl ?: m.posterUrl,
                cast = m.cast.ifEmpty { o.cast },
                seasons = o.seasons ?: m.seasons,
                runtimeMin = o.runtimeMin ?: m.runtimeMin,
                availability = m.availability,
                nextEpisodes = m.nextEpisodes,
                kidsSafe = o.kidsSafe || m.kidsSafe,
                score = o.score ?: m.score,
                source = "OMDb + TVmaze",
            )
            o != null -> {
                val eps = if (o.ref.type == MediaType.TV && imdb != null) {
                    o.seasons?.let { s -> runCatching { omdb.season(imdb, s) }.getOrNull() }.orEmpty()
                } else emptyList()
                o.copy(ref = o.ref.copy(type = ref.type), nextEpisodes = eps)
            }
            m != null -> m
            else -> throw IOException("No details for ${ref.name}")
        }
        merged
    }

    companion object {
        fun norm(s: String) = LocalIndex.normalize(s)

        /**
         * One list from both sources, joined on the IMDb id (TVmaze shows carry it in externals).
         * Names starting with the query first, then names containing it, then the rest; within
         * a group, the best position either source gave it (OMDb lists by IMDb popularity,
         * TVmaze by relevance), then exact names, then titles with a poster.
         */
        fun merge(q: String, maze: List<Pair<SearchHit.Title, Double>>, omdb: List<SearchHit.Title>): List<SearchHit.Title> {
            val nq = norm(q)
            val hits = LinkedHashMap<String, SearchHit.Title>()
            val rank = HashMap<String, Int>()
            for ((i, p) in maze.withIndex()) {
                val h = p.first
                if (hits.putIfAbsent(h.key, h) == null) rank[h.key] = i
            }
            for ((i, h) in omdb.withIndex()) {
                val old = hits[h.key]
                if (old == null) hits[h.key] = h
                else if (old.posterUrl == null && h.posterUrl != null) hits[h.key] = old.copy(posterUrl = h.posterUrl)
                rank[h.key] = minOf(rank[h.key] ?: Int.MAX_VALUE, i)
            }
            fun group(name: String): Int {
                val n = norm(name)
                return when {
                    n.startsWith(nq) -> 0
                    n.contains(nq) -> 1
                    else -> 2
                }
            }
            return hits.values.sortedWith(
                compareBy<SearchHit.Title> { group(it.ref.name) }
                    .thenBy { rank[it.key] ?: Int.MAX_VALUE }
                    .thenBy { if (norm(it.ref.name) == nq) 0 else 1 }
                    .thenBy { if (it.posterUrl != null) 0 else 1 },
            )
        }
    }
}
