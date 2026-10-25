package com.swissdark.launcher.data

import android.util.LruCache
import com.swissdark.launcher.domain.AppId
import com.swissdark.launcher.domain.AppLauncher
import com.swissdark.launcher.domain.Availability
import com.swissdark.launcher.domain.Kind
import com.swissdark.launcher.domain.LaunchResult
import com.swissdark.launcher.domain.LauncherSettings
import com.swissdark.launcher.domain.MediaType
import com.swissdark.launcher.domain.Providers
import com.swissdark.launcher.domain.RefreshReason
import com.swissdark.launcher.domain.SearchEngine
import com.swissdark.launcher.domain.SearchHit
import com.swissdark.launcher.domain.TitleDetails
import com.swissdark.launcher.domain.TitleRef
import com.swissdark.launcher.domain.TitleRepository
import com.swissdark.launcher.platform.tv.LaunchAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

private const val DAY = 86_400_000L

/** Titles from search/trending kept for the local index (Room and memory). */
internal const val SEEN_CAP = 1_500

/** inMyPlan = provider is in the user's services list in Settings. */
fun Availability.withPlan(s: LauncherSettings): Availability {
    val key = Providers.byTmdb(providerId)?.key ?: Providers.all.firstOrNull { it.name == providerName }?.key
    // Cached details may name an old package (JioCinema): always hand out the installed one.
    val pkg = packageName?.let { Providers.resolve(it) }
        ?: Providers.byTmdb(providerId)?.let { with(Providers) { it.installedPackage } }
    return copy(inMyPlan = key != null && key in s.myServices, packageName = pkg)
}

/** On your services = something you can press Play on today without paying more. */
fun List<Availability>.onMyServices(): Availability? =
    firstOrNull { it.inMyPlan && it.kind == Kind.STREAM } ?: firstOrNull { it.kind == Kind.FREE || it.kind == Kind.ADS }

@Serializable
private data class HitDto(
    val t: String, val id: Int, val name: String, val poster: String?, val backdrop: String?,
    val meta: String, val kids: Boolean, val pop: Double,
    val person: Boolean = false, val role: String = "", val dept: String = "", val known: Int = 0,
)

private fun SearchHit.toDto(): HitDto? = when (this) {
    is SearchHit.Title -> HitDto(ref.type.name, ref.tmdbId, ref.name, posterUrl, backdropUrl, meta, kidsSafe, popularity)
    is SearchHit.PersonHit -> HitDto("P", person.id, person.name, null, null, "", false, 0.0, true, person.role, person.department, person.knownFor)
    else -> null
}

private fun HitDto.toHit(): SearchHit = if (person) SearchHit.PersonHit(com.swissdark.launcher.domain.Person(id, name, role, dept, known))
else SearchHit.Title(TitleRef(id, MediaType.valueOf(t), name), poster?.replace("@", "%40"), backdrop, meta, null, kids, pop) // rows cached before OmdbParse.poster encoded '@' 

class SearchEngineImpl(
    private val scope: CoroutineScope,
    private val dao: LauncherDao,
    private val tmdb: TmdbClient,
    private val apps: AppsRepositoryImpl,
    private val settings: SettingsStore,
) : SearchEngine {
    private val json = Json { ignoreUnknownKeys = true }
    private val listSer = ListSerializer(HitDto.serializer())
    @Volatile private var index = LocalIndex.EMPTY
    private val providerCache = LruCache<String, List<Availability>>(400)
    private val providerGate = Semaphore(6)

    /** One rebuild at a time, in order; a burst of requests collapses into one rebuild. */
    private val indexWorker = Dispatchers.Default.limitedParallelism(1)
    private val rebuildQueued = AtomicBoolean(false)

    /**
     * Seen titles, decoded once from Room and then kept up to date in memory (oldest first,
     * capped at [SEEN_CAP]), so a rebuild after each online search does not re-read and
     * re-decode 1,500 JSON rows. Touched only on [indexWorker].
     */
    private var seenTitles: LinkedHashMap<String, SearchHit.Title>? = null

    init {
        // Installed/removed apps and hidden apps change what the index holds. The package
        // receiver rebuilds the index before the app list is rescanned, so follow the list itself.
        scope.launch {
            combine(apps.apps, settings.settings.map { it.hidden }.distinctUntilChanged()) { a, h -> a to h }
                .distinctUntilChanged()
                .collect { rebuildIndex() }
        }
    }

    /** Rebuilt off the main thread whenever apps, history or seen titles change. */
    fun rebuildIndex() {
        if (!rebuildQueued.compareAndSet(false, true)) return
        scope.launch(indexWorker) {
            rebuildQueued.set(false) // changes after this point queue another rebuild
            val recent = dao.searchesNow().map { SearchHit.Recent(it.query, it.kind) }
            val titles = seen().values.reversed() // most recent first, as before
            index = LocalIndex.build(apps.visible(), recent, titles)
        }
    }

    private suspend fun seen(): LinkedHashMap<String, SearchHit.Title> = seenTitles ?: LinkedHashMap<String, SearchHit.Title>().also { m ->
        for (r in dao.seen().asReversed()) {
            val t = runCatching { json.decodeFromString(HitDto.serializer(), r.json).toHit() as? SearchHit.Title }.getOrNull() ?: continue
            m[t.key] = t
        }
        seenTitles = m
    }

    override fun local(query: String): List<SearchHit> = index.lookup(query)

    override val titleSource: String get() = tmdb.label

    /**
     * Wave 2: 150 ms after the last keystroke, Room's copy from the last 24 h, else the title
     * sources (TMDB, or OMDb + TVmaze). Cached per source, so a working TMDB key replaces fallback lists.
     * JSON work runs on Default (flowOn), not in the collector's main-thread context.
     */
    override fun remote(query: String): Flow<List<SearchHit>> = flow {
        val q = LocalIndex.normalize(query)
        if (q.length < 2 || !tmdb.hasKey) return@flow
        delay(150)
        val ck = q + tmdb.cacheTag()
        val cached = dao.query(ck, System.currentTimeMillis() - DAY)
        if (cached != null) {
            emit(json.decodeFromString(listSer, cached.json).map { it.toHit() })
            return@flow
        }
        val hits = tmdb.searchMulti(query)
        emit(hits)
        dao.putQuery(QueryRow(ck, json.encodeToString(listSer, hits.mapNotNull { it.toDto() }), System.currentTimeMillis()))
        remember(hits.filterIsInstance<SearchHit.Title>())
    }.flowOn(Dispatchers.Default)

    private suspend fun remember(titles: List<SearchHit.Title>) {
        if (titles.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.putSeen(titles.mapNotNull { t -> t.toDto()?.let { SeenRow(t.key, t.ref.name, json.encodeToString(HitDto.serializer(), it), now) } })
        withContext(indexWorker) {
            val m = seen()
            val before = m.size
            var changed = false
            for (t in titles) {
                val old = m.remove(t.key)
                m[t.key] = t // re-insert: most recent last
                if (old == null || old.ref.name != t.ref.name) changed = true
            }
            while (m.size > SEEN_CAP) { m.remove(m.keys.first()); changed = true }
            // Same titles seen again (trending, a re-typed query): the index text is unchanged.
            if (changed || m.size != before) rebuildIndex()
        }
    }

    override fun recent(): Flow<List<SearchHit.Recent>> = dao.searches().map { rows ->
        rows.distinctBy { it.query.lowercase() }.map { SearchHit.Recent(it.query, it.kind) }
    }

    override fun remember(query: String, kind: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        scope.launch {
            dao.deleteSearch(q)
            dao.putSearch(SearchRow(q, kind, System.currentTimeMillis()))
            dao.trimSearches()
            rebuildIndex()
        }
    }

    override fun forget(query: String) {
        scope.launch { dao.deleteSearch(query); rebuildIndex() }
    }

    override suspend fun trending(daily: Boolean): List<SearchHit.Title> = withContext(Dispatchers.Default) {
        val key = (if (daily) "~trending/day" else "~trending/week") + tmdb.cacheTag()
        val cached = dao.query(key, System.currentTimeMillis() - DAY)
        if (cached != null) return@withContext json.decodeFromString(listSer, cached.json).mapNotNull { it.toHit() as? SearchHit.Title }
        if (!tmdb.hasKey) return@withContext emptyList()
        val list = tmdb.trending(daily)
        dao.putQuery(QueryRow(key, json.encodeToString(listSer, list.mapNotNull { it.toDto() }), System.currentTimeMillis()))
        remember(list)
        list
    }

    /** Opens from cache when the network is slow: any copy, however old. */
    suspend fun trendingCachedAnyAge(daily: Boolean): List<SearchHit.Title> = withContext(Dispatchers.Default) {
        val key = (if (daily) "~trending/day" else "~trending/week") + tmdb.cacheTag()
        dao.query(key, 0)?.let { json.decodeFromString(listSer, it.json).mapNotNull { h -> h.toHit() as? SearchHit.Title } }.orEmpty()
    }

    override suspend fun byGenre(genre: String): List<SearchHit> = cachedList("~genre/$genre" + tmdb.cacheTag()) { tmdb.discover(genre) }

    override suspend fun byPerson(personId: Int): List<SearchHit> = cachedList("~person/$personId") { tmdb.personCredits(personId) }

    /** Room's copy from the last 24 h, else [fetch]; JSON work off the caller's (main) thread. */
    private suspend fun cachedList(key: String, fetch: suspend () -> List<SearchHit>): List<SearchHit> = withContext(Dispatchers.Default) {
        dao.query(key, System.currentTimeMillis() - DAY)?.let { return@withContext json.decodeFromString(listSer, it.json).map { h -> h.toHit() } }
        val list = fetch()
        dao.putQuery(QueryRow(key, json.encodeToString(listSer, list.mapNotNull { it.toDto() }), System.currentTimeMillis()))
        list
    }

    /** Fetched in one batch after the list is drawn, so posters never wait for availability. */
    override suspend fun availability(hits: List<SearchHit.Title>): List<SearchHit.Title> = coroutineScope {
        val s = settings.settings.value
        hits.map { h ->
            async {
                val cached = providerCache.get(h.key)
                val list = cached ?: try {
                    providerGate.withPermit { tmdb.providers(h.ref) }.also { providerCache.put(h.key, it) }
                } catch (_: Exception) {
                    null
                }
                h.copy(availability = list?.map { it.withPlan(s) } ?: h.availability)
            }
        }.awaitAll()
    }
}

class TitleRepositoryImpl(
    private val scope: CoroutineScope,
    private val dao: LauncherDao,
    private val tmdb: TmdbClient,
    private val settings: SettingsStore,
    private val onHistoryChanged: () -> Unit,
) : TitleRepository {
    private val json = Json { ignoreUnknownKeys = true }

    /** Decoding/encoding a details blob takes milliseconds on the TV: never on the main thread. */
    override suspend fun details(ref: TitleRef): TitleDetails? = withContext(Dispatchers.Default) {
        val key = ref.type.name + ":" + ref.tmdbId
        val row = dao.title(key)
        val now = System.currentTimeMillis()
        val cached = row?.let { runCatching { json.decodeFromString(TitleDetails.serializer(), it.json) }.getOrNull() }
        // Where-to-watch changes often: refetch when older than 24 h; keep the cache if offline.
        val fresh = if (cached == null || now - row.fetchedAt > DAY) {
            try {
                tmdb.details(ref).also { dao.putTitle(TitleRow(key, it.ref.name, json.encodeToString(TitleDetails.serializer(), it), now)) }
            } catch (_: Exception) {
                cached
            }
        } else cached
        val s = settings.settings.value
        fresh?.copy(availability = fresh.availability.map { it.withPlan(s) })
    }

    override fun saveForLater(details: TitleDetails) {
        val svc = details.availability.onMyServices()?.packageName ?: details.availability.firstOrNull()?.packageName ?: "com.google.android.youtube.tv"
        scope.launch {
            dao.putHistory(
                HistoryRow(
                    details.ref.type.name + ":" + details.ref.tmdbId, details.ref.tmdbId, details.ref.type.name, details.ref.name,
                    svc, "$svc/", details.backdropUrl, System.currentTimeMillis(), true,
                ),
            )
            onHistoryChanged()
        }
    }

    override fun recordOpened(ref: TitleRef, service: AppId, artUrl: String?) {
        scope.launch {
            dao.putHistory(
                HistoryRow(
                    ref.type.name + ":" + ref.tmdbId, ref.tmdbId, ref.type.name, ref.name,
                    service.packageName, service.component, artUrl, System.currentTimeMillis(), false,
                ),
            )
            onHistoryChanged()
        }
    }

    suspend fun expire() {
        val now = System.currentTimeMillis()
        dao.expireTitles(now - 30 * DAY)
        dao.expireQueries(now - 30 * DAY)
        // One row per typed prefix: worthless after the 24 h window (trending/browse keep 30 days).
        dao.expireTypedQueries(now - 2 * DAY)
        dao.trimSeen(SEEN_CAP)
    }
}

/**
 * Launches go out in under 1 ms: the habit is recorded and the Intent posted. A title opens by
 * deep link if known, else through the app's search if it accepts one, else the app's home.
 */
class AppLauncherImpl(
    private val scope: CoroutineScope,
    private val launch: LaunchAdapter,
    private val habits: HabitStore,
    private val settings: SettingsStore,
    private val apps: AppsRepositoryImpl,
    private val onLaunched: (RefreshReason) -> Unit,
) : AppLauncher {
    override fun launch(app: AppId): LaunchResult {
        // An alias (the old JioCinema stub) opens the app it moved to.
        val to = Providers.resolve(app.packageName)
        val ok = (to != app.packageName && launch.launchPackage(to)) ||
            launch.launchComponent(app.component) || launch.launchPackage(app.packageName)
        if (ok) {
            scope.launch(Dispatchers.Default) {
                habits.record(Providers.resolve(app.packageName), System.currentTimeMillis())
                habits.save()
            }
        }
        return if (ok) LaunchResult.Ok else LaunchResult.Failed("Couldn’t open ${app.packageName}")
    }

    /**
     * True when [pkg] can be handed a title (a search it really runs). Netflix, Prime Video and
     * JioHotstar resolve search intents on this TV but just open their home: tell the user first.
     */
    fun opensTitles(pkg: String): Boolean = pkg !in HOME_ONLY && acceptsSearch(pkg)

    private fun acceptsSearch(pkg: String): Boolean {
        if (pkg in HOME_ONLY) return false
        settings.settings.value.searchIntentApps[pkg]?.let { return it }
        val ok = launch.acceptsSearch(pkg)
        settings.update { it.copy(searchIntentApps = it.searchIntentApps + (pkg to ok)) }
        return ok
    }

    /** Up next items from media sessions ("swiss-watched:…"): live session or deep link first. */
    var watchedOpener: ((String) -> Boolean)? = null

    override fun openTitle(title: TitleRef, service: AppId?, intentUri: String?): LaunchResult {
        if (intentUri != null && intentUri.startsWith(WatchedStore.PREFIX)) {
            if (watchedOpener?.invoke(intentUri) == true) return LaunchResult.Ok
        } else if (intentUri != null && launch.openUri(intentUri, service?.packageName)) return LaunchResult.Ok
        val pkg = service?.packageName ?: return LaunchResult.Failed("No service")
        if (acceptsSearch(pkg) && launch.search(pkg, title.name)) return LaunchResult.Ok
        val app = apps.apps.value.firstOrNull { it.id.packageName == pkg }
        val ok = (app != null && launch.launchComponent(app.id.component)) || launch.launchPackage(pkg)
        return if (ok) LaunchResult.OpenedAppHome else LaunchResult.Failed("$pkg is not installed")
    }

    companion object {
        /** Apps seen on the TV to ignore search intents (they open on their home screen). */
        val HOME_ONLY = setOf(
            "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar", "com.jio.media.stb.ondemand",
        )
    }

    override fun openTrailer(youtubeKey: String): LaunchResult =
        if (launch.youtube(youtubeKey)) LaunchResult.Ok else LaunchResult.Failed("YouTube not available")

    override fun openSystemSettings(): LaunchResult =
        if (launch.systemSettings()) LaunchResult.Ok else LaunchResult.Failed("No settings")

    override fun openUrl(uri: String, pkg: String?): LaunchResult =
        if (launch.openUri(uri, pkg)) LaunchResult.Ok else LaunchResult.Failed(uri)
}
