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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val DAY = 86_400_000L

/** inMyPlan = provider is in the user's services list in Settings. */
fun Availability.withPlan(s: LauncherSettings): Availability {
    val key = Providers.byTmdb(providerId)?.key ?: Providers.all.firstOrNull { it.name == providerName }?.key
    return copy(inMyPlan = key != null && key in s.myServices)
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
else SearchHit.Title(TitleRef(id, MediaType.valueOf(t), name), poster, backdrop, meta, null, kids, pop)

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

    /** Rebuilt off the main thread whenever apps, history or seen titles change. */
    fun rebuildIndex() {
        scope.launch(Dispatchers.Default) {
            val recent = dao.searchesNow().map { SearchHit.Recent(it.query, it.kind) }
            val titles = dao.seen().mapNotNull { runCatching { json.decodeFromString(HitDto.serializer(), it.json).toHit() as? SearchHit.Title }.getOrNull() }
            index = LocalIndex.build(apps.visible(), recent, titles)
        }
    }

    override fun local(query: String): List<SearchHit> = index.lookup(query)

    /** Wave 2: 150 ms after the last keystroke, Room's copy from the last 24 h, else TMDB. */
    override fun remote(query: String): Flow<List<SearchHit>> = flow {
        val q = LocalIndex.normalize(query)
        if (q.length < 2 || !tmdb.hasKey) return@flow
        delay(150)
        val cached = dao.query(q, System.currentTimeMillis() - DAY)
        if (cached != null) {
            emit(json.decodeFromString(listSer, cached.json).map { it.toHit() })
            return@flow
        }
        val hits = tmdb.searchMulti(query)
        emit(hits)
        dao.putQuery(QueryRow(q, json.encodeToString(listSer, hits.mapNotNull { it.toDto() }), System.currentTimeMillis()))
        remember(hits.filterIsInstance<SearchHit.Title>())
    }

    private suspend fun remember(titles: List<SearchHit.Title>) {
        val now = System.currentTimeMillis()
        dao.putSeen(titles.mapNotNull { t -> t.toDto()?.let { SeenRow(t.key, t.ref.name, json.encodeToString(HitDto.serializer(), it), now) } })
        rebuildIndex()
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

    override suspend fun trending(daily: Boolean): List<SearchHit.Title> {
        val key = if (daily) "~trending/day" else "~trending/week"
        val cached = dao.query(key, System.currentTimeMillis() - DAY)
        if (cached != null) return json.decodeFromString(listSer, cached.json).mapNotNull { it.toHit() as? SearchHit.Title }
        if (!tmdb.hasKey) return emptyList()
        val list = tmdb.trending(daily)
        dao.putQuery(QueryRow(key, json.encodeToString(listSer, list.mapNotNull { it.toDto() }), System.currentTimeMillis()))
        remember(list)
        return list
    }

    /** Opens from cache when the network is slow: any copy, however old. */
    suspend fun trendingCachedAnyAge(daily: Boolean): List<SearchHit.Title> {
        val key = if (daily) "~trending/day" else "~trending/week"
        return dao.query(key, 0)?.let { json.decodeFromString(listSer, it.json).mapNotNull { h -> h.toHit() as? SearchHit.Title } }.orEmpty()
    }

    override suspend fun byGenre(genre: String): List<SearchHit> {
        val key = "~genre/$genre"
        dao.query(key, System.currentTimeMillis() - DAY)?.let { return json.decodeFromString(listSer, it.json).map { h -> h.toHit() } }
        val list = tmdb.discover(genre)
        dao.putQuery(QueryRow(key, json.encodeToString(listSer, list.mapNotNull { it.toDto() }), System.currentTimeMillis()))
        return list
    }

    override suspend fun byPerson(personId: Int): List<SearchHit> {
        val key = "~person/$personId"
        dao.query(key, System.currentTimeMillis() - DAY)?.let { return json.decodeFromString(listSer, it.json).map { h -> h.toHit() } }
        val list = tmdb.personCredits(personId)
        dao.putQuery(QueryRow(key, json.encodeToString(listSer, list.mapNotNull { it.toDto() }), System.currentTimeMillis()))
        return list
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

    override suspend fun details(ref: TitleRef): TitleDetails? {
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
        return fresh?.copy(availability = fresh.availability.map { it.withPlan(s) })
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
        val ok = launch.launchComponent(app.component) || launch.launchPackage(app.packageName)
        if (ok) {
            scope.launch(Dispatchers.Default) {
                habits.record(app.packageName, System.currentTimeMillis())
                habits.save()
            }
        }
        return if (ok) LaunchResult.Ok else LaunchResult.Failed("Couldn’t open ${app.packageName}")
    }

    private fun acceptsSearch(pkg: String): Boolean {
        settings.settings.value.searchIntentApps[pkg]?.let { return it }
        val ok = launch.acceptsSearch(pkg)
        settings.update { it.copy(searchIntentApps = it.searchIntentApps + (pkg to ok)) }
        return ok
    }

    override fun openTitle(title: TitleRef, service: AppId?, intentUri: String?): LaunchResult {
        if (intentUri != null && launch.openUri(intentUri, service?.packageName)) return LaunchResult.Ok
        val pkg = service?.packageName ?: return LaunchResult.Failed("No service")
        if (acceptsSearch(pkg) && launch.search(pkg, title.name)) return LaunchResult.Ok
        val app = apps.apps.value.firstOrNull { it.id.packageName == pkg }
        val ok = (app != null && launch.launchComponent(app.id.component)) || launch.launchPackage(pkg)
        return if (ok) LaunchResult.OpenedAppHome else LaunchResult.Failed("$pkg is not installed")
    }

    override fun openTrailer(youtubeKey: String): LaunchResult =
        if (launch.youtube(youtubeKey)) LaunchResult.Ok else LaunchResult.Failed("YouTube not available")

    override fun openSystemSettings(): LaunchResult =
        if (launch.systemSettings()) LaunchResult.Ok else LaunchResult.Failed("No settings")

    override fun openUrl(uri: String, pkg: String?): LaunchResult =
        if (launch.openUri(uri, pkg)) LaunchResult.Ok else LaunchResult.Failed(uri)
}
