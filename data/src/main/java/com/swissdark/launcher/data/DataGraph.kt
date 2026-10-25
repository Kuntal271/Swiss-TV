package com.swissdark.launcher.data

import android.content.Context
import android.provider.Settings
import com.swissdark.launcher.domain.DeviceStatus
import com.swissdark.launcher.domain.RefreshReason
import com.swissdark.launcher.platform.tv.BannerArt
import com.swissdark.launcher.platform.tv.HomeRoleAdapter
import com.swissdark.launcher.platform.tv.InputsAdapter
import com.swissdark.launcher.platform.tv.LaunchAdapter
import com.swissdark.launcher.platform.tv.NetworkAdapter
import com.swissdark.launcher.platform.tv.PackagesAdapter
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
    val watchNext by lazy { WatchNextAdapter(context) }
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
    val tmdb by lazy { TmdbClient(http) { settings.settings.value.tmdbKey.ifBlank { bakedTmdbKey } } }

    val apps by lazy { AppsRepositoryImpl(packages, settings, scope) }
    val watched by lazy { WatchedStore(context, scope, tmdb, launchAdapter) { refresh(RefreshReason.WATCH_NEXT_CHANGED) } }
    val home by lazy { HomeRepositoryImpl(context, scope, settings, apps, habits, usage, watchNext, db.dao(), snapshots, watched) }
    private val wallpapersLazy = lazy { WallpaperSchedulerImpl(context, scope, settings) }
    val wallpapers by wallpapersLazy
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
            } }
        }, "prewarm-home").start()
    }

    /** The launcher went to the background or the system is short of memory: drop caches. */
    fun trimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            if (artLazy.isInitialized()) art.trim()
            if (wallpapersLazy.isInitialized()) wallpapers.trim()
        }
    }

    fun refresh(reason: RefreshReason) {
        scope.launch { home.refresh(reason) }
    }

    /** Only after the first frame: refreshers, receivers, image cache warm-up. */
    fun startBackground() {
        network.start()
        packages.watch {
            if (artLazy.isInitialized()) art.forgetMisses()
            refresh(RefreshReason.PACKAGES_CHANGED)
            search.rebuildIndex()
        }
        watchNext.observe { refresh(RefreshReason.WATCH_NEXT_CHANGED) }
        watched.start()      // media sessions → Up next; a no-op until setup.sh enables the listener
        wallpapers.start()
        refresh(RefreshReason.COLD_START)
        search.rebuildIndex()
        status.checkHome()
        DailyRefreshWorker.schedule(context)
        // Keep the wallpaper in step with its settings (choice and dim per slot).
        scope.launch {
            var last = settings.settings.value.let { it.wallpaperBySlot to it.dimBySlot }
            settings.settings.collect { s ->
                val now = s.wallpaperBySlot to s.dimBySlot
                if (now != last) { last = now; wallpapers.refresh() }
            }
        }
    }

    companion object {
        @Volatile var instance: DataGraph? = null
    }
}
