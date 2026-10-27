package com.swissdark.launcher.data

import android.content.Context
import android.provider.Settings
import com.swissdark.launcher.domain.Cities
import com.swissdark.launcher.domain.DeviceStatus
import com.swissdark.launcher.domain.RefreshReason
import com.swissdark.launcher.platform.tv.BannerArt
import com.swissdark.launcher.platform.tv.HomeRoleAdapter
import com.swissdark.launcher.platform.tv.InputsAdapter
import com.swissdark.launcher.platform.tv.LaunchAdapter
import com.swissdark.launcher.platform.tv.NetworkAdapter
import com.swissdark.launcher.platform.tv.PackagesAdapter
import com.swissdark.launcher.platform.tv.PowerAdmin
import com.swissdark.launcher.platform.tv.TclSettingsAdapter
import com.swissdark.launcher.platform.tv.UsageAdapter
import com.swissdark.launcher.platform.tv.VoiceAdapter
import com.swissdark.launcher.platform.tv.WatchNextAdapter
import com.swissdark.launcher.core.perf.traced
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Hand-wired object graph. Everything is lazy so cold start touches only what Home draws. */
class DataGraph(val context: Context, private val bakedTmdbKey: String) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings by lazy { SettingsStore(context, scope) }
    val packages by lazy { PackagesAdapter(context) }
    val launchAdapter by lazy { LaunchAdapter(context, packages) }
    val usage by lazy { UsageAdapter(context) }
    /** Power off from the D-pad (device admin, activated by setup.sh). */
    val power by lazy { PowerAdmin(context) }
    val watchNext by lazy { WatchNextAdapter(context) }
    /** Weather for Home's clock line (open-meteo, no key). Silent when offline. */
    val weather by lazy {
        WeatherStore(scope, http, java.io.File(context.filesDir, "weather.json")) {
            Cities.byKey(settings.settings.value.weatherCity) ?: Cities.all.first()
        }
    }
    val homeRole by lazy { HomeRoleAdapter(context) }
    val network by lazy { NetworkAdapter(context) }
    val voice by lazy { VoiceAdapter(context) }
    val inputs by lazy { InputsAdapter(context) }
    val quickSettings by lazy { TclSettingsAdapter(context, launchAdapter) }
    private val artLazy = lazy { BannerArt(context) }
    val art by artLazy
    val habits by lazy { HabitStore(context) }
    val snapshots by lazy { SnapshotStore(context, scope) }
    val db by lazy { LauncherDb.open(context) }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .cache(okhttp3.Cache(File(context.cacheDir, "http"), 20L * 1024 * 1024))
            .build()
    }
    /** Title provider chain: TMDB when its key works, else OMDb + TVmaze (see TitleSources). */
    val tmdb by lazy {
        TitleSources(context, http, { settings.settings.value.tmdbKey.ifBlank { bakedTmdbKey } }, { settings.settings.value.omdbKey })
    }

    val apps by lazy { AppsRepositoryImpl(packages, settings, scope) }
    val watched by lazy { WatchedStore(context, scope, tmdb, launchAdapter, http) { refresh(RefreshReason.WATCH_NEXT_CHANGED) } }
    val home by lazy { HomeRepositoryImpl(context, scope, settings, apps, habits, usage, watchNext, db.dao(), snapshots, watched) }
    private val wallpapersLazy = lazy { WallpaperSchedulerImpl(context, scope, settings) }
    val wallpapers by wallpapersLazy
    /** Home's focus backdrop: art for the focused Continue watching card (see BackdropArt). */
    private val backdropLazy = lazy { BackdropArt(context, tmdb, http, art) { wallpapers.current.value.dim } }
    val backdropArt by backdropLazy
    /** OTA self-update from GitHub Releases (see Updates). */
    val updates by lazy { Updates(context, http, scope) }
    val search by lazy { SearchEngineImpl(scope, db.dao(), tmdb, apps, settings) }
    val titles by lazy { TitleRepositoryImpl(scope, db.dao(), tmdb, settings) { refresh(RefreshReason.WATCH_NEXT_CHANGED) } }
    val launcher by lazy { AppLauncherImpl(scope, launchAdapter, habits, settings, apps) { refresh(it) }.also { it.watchedOpener = { u -> watched.open(u) } } }

    val status: DeviceStatus by lazy {
        object : DeviceStatus {
            override val deviceName: String =
                (Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: android.os.Build.MODEL).trim()
            override val network: StateFlow<String> get() = this@DataGraph.network.status
            override val networkName: StateFlow<String> get() = this@DataGraph.network.name
            private val home = MutableStateFlow(true)
            override val isHomeApp: StateFlow<Boolean> = home
            override fun checkHome() {
                home.value = homeRole.isHome()
            }
        }
    }

    /**
     * Cold start: build what the first Home frame reads (settings, the L2 snapshot, the mapped
     * wallpaper, app banners for Up next) on two background threads while the activity and
     * Compose are still being created. The lazies are synchronized, so if the first composition
     * gets there first it simply waits for the remainder instead of doing it all on main.
     */
    fun prewarm(warmImages: (urls: List<String>) -> Unit = {}) {
        Thread({
            runCatching { traced("startup.prewarm.wallpaper") { wallpapers.current.value } }
        }, "prewarm-wallpaper").start()
        Thread({
            runCatching { traced("startup.prewarm.home") {
                val snap = home.snapshot.value
                status.deviceName
                val keys = snap.upNext.map { it.artKey }
                // Banners at the Up next card's decode size (PosterCard default 480 x 270).
                keys.filter { it.startsWith("banner:") }.forEach { art.banner(it.removePrefix("banner:"), 480, 270) }
                warmImages(keys.filterNot { it.startsWith("banner:") })
                warmTiles(snap.apps.map { it.id.component })
            } }
        }, "prewarm-home").start()
    }

    /** Installed ADW / Nova icon packs, package to label (PackageManager query: call off the hot path). */
    fun iconPacks(): List<Pair<String, String>> =
        com.swissdark.launcher.platform.tv.IconPacks.installed(context).map { it.packageName to it.label }

    /** Tile art (banner or logo on colour) for these components, at the size tiles decode at. */
    private fun warmTiles(components: List<String>) {
        val pack = settings.settings.value.iconPack
        for (c in components) runCatching { art.tile(c, TILE_W, TILE_H, pack) }
    }

    /** The launcher went to the background or the system is short of memory: drop caches. */
    fun trimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // Keep what Home draws, so coming back from an app never waits on a re-decode.
            if (artLazy.isInitialized()) runCatching {
                val snap = home.snapshot.value
                val comps = snap.apps.mapTo(HashSet()) { it.id.component }
                val pkgs = comps.mapTo(HashSet()) { it.substringBefore('/') }
                snap.upNext.forEach { if (it.artKey.startsWith("banner:")) pkgs += it.artKey.removePrefix("banner:") }
                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) art.trim() else art.trimExcept(comps, pkgs)
            }
            if (wallpapersLazy.isInitialized()) wallpapers.trim()
            if (backdropLazy.isInitialized()) backdropArt.trim()
        }
    }

    /** TV off / boot: empty Continue watching (LauncherSettings.clearContinueOnOff). */
    val continueCleaner by lazy {
        ContinueCleaner({ settings.settings.value.clearContinueOnOff }, object : ContinueCleaner.Ops {
            override suspend fun deleteWatched() = watched.clearAll()
            override suspend fun deleteHistory() = db.dao().clearHistory()
            override fun markCleared(at: Long) = settings.update { it.copy(continueClearedAt = at) }
            override suspend fun rebuild() = home.rebuildNow()
        })
    }

    fun clearContinue(reason: String, force: Boolean = false, done: () -> Unit = {}) {
        scope.launch {
            try { continueCleaner.clear(reason, force) } finally { done() }
        }
    }

    private val powerWatched = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Idempotent. Called from every long-lived entry point (first Home frame, the media listener
     * binding, boot), so it is registered even when MainActivity never started after boot.
     */
    fun watchPower() {
        if (!powerWatched.compareAndSet(false, true)) return
        PowerEvents(
            onOff = { reason, done ->
                if (settings.settings.value.clearContinueOnOff) {
                    watched.recording = false
                    clearContinue(reason, done = done)
                } else done()
            },
            onOn = { watched.recording = true },
        ).register(context.applicationContext)
    }

    fun refresh(reason: RefreshReason) {
        scope.launch { home.refresh(reason) }
    }

    /** Only after the first frame: refreshers, receivers, image cache warm-up. */
    /**
     * Continue watching cards that show only the app's banner (and Crunchyroll's): look the show or
     * episode up once, in the background, and give the card its art.
     */
    private fun watchCardArt() {
        val tried = HashSet<String>()
        scope.launch {
            home.snapshot.collect { snap ->
                for (item in snap.upNext) {
                    if (!item.key.startsWith("w:") || item.key.endsWith("|" + WatchedStore.APP_GROUP)) continue
                    // Crunchyroll and episode-named rows (JioHotstar's "S2 E14 …"): the episode's
                    // own TVmaze still. Other banner-only cards: the show's art.
                    val episodeStyle = item.service.packageName == WatchedStore.CRUNCHYROLL ||
                        (item.title == null && item.season != null && item.episode != null)
                    if (!episodeStyle && !item.artKey.startsWith("banner:")) continue
                    if (!tried.add(item.key + "|" + item.name)) continue
                    launch {
                        val key = item.key.removePrefix("w:")
                        val art = backdropArt.cardArt(item, episodeStyle)
                        if (art == null) {
                            // Nothing confident: forget any art a name-collision once stored.
                            if (episodeStyle) watched.clearShowArt(key)
                            return@launch
                        }
                        // A resolved show ("VisionQuest" behind "No Strings") renames the row, so
                        // the card and the hero read the series with its episode, not just the name.
                        if (!art.showName.isNullOrBlank() && !art.showName.equals(item.name, ignoreCase = true)) {
                            watched.setShowIdentity(key, art.showName, item.name, art.url, art.season, art.episode)
                        } else {
                            watched.setShowArt(key, art.url)
                            // Already renamed: the numbers may still be missing (Crunchyroll).
                            if (art.season != null && art.episode != null) watched.setEpisodeNumbers(key, art.season!!, art.episode!!)
                        }
                    }
                }
            }
        }
    }

    fun startBackground() {
        network.start()
        watchPower()
        packages.watch {
            if (artLazy.isInitialized()) art.forgetMisses()
            refresh(RefreshReason.PACKAGES_CHANGED)
            search.rebuildIndex()
        }
        watchNext.observe { refresh(RefreshReason.WATCH_NEXT_CHANGED) }
        // All apps' tile art, a little after start-up, so the grid opens with every logo cached.
        scope.launch {
            kotlinx.coroutines.delay(3_000)
            warmTiles(apps.apps.value.map { it.id.component })
        }
        scope.launch {
            var last = settings.settings.value.iconPack
            settings.settings.collect { s ->
                if (s.iconPack != last) { last = s.iconPack; warmTiles(apps.apps.value.map { it.id.component }) }
            }
        }
        watched.start()      // media sessions → Up next; a no-op until setup.sh enables the listener
        watchCardArt()
        wallpapers.start()
        weather.start()
        updates.maybeAutoCheck()
        refresh(RefreshReason.COLD_START)
        search.rebuildIndex()
        status.checkHome()
        DailyRefreshWorker.schedule(context)
        // Keep the wallpaper in step with its settings (choice and dim per slot),
        // and fetch the new city's weather the moment the owner picks it.
        scope.launch {
            var last = settings.settings.value.let { it.wallpaperBySlot to it.dimBySlot }
            var lastCity = settings.settings.value.weatherCity
            settings.settings.collect { s ->
                val now = s.wallpaperBySlot to s.dimBySlot
                if (now != last) { last = now; wallpapers.refresh() }
                if (s.weatherCity != lastCity) { lastCity = s.weatherCity; weather.refreshNow() }
            }
        }
    }

    companion object {
        @Volatile var instance: DataGraph? = null
        // Same as core.design TILE_ART_W / TILE_ART_H (data does not depend on the design module).
        const val TILE_W = 320
        const val TILE_H = 180
    }
}
