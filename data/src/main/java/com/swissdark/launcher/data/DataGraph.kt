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
    val art by lazy { BannerArt(context) }
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
    val home by lazy { HomeRepositoryImpl(context, scope, settings, apps, habits, usage, watchNext, db.dao(), snapshots) }
    val wallpapers by lazy { WallpaperSchedulerImpl(context, scope, settings) }
    val search by lazy { SearchEngineImpl(scope, db.dao(), tmdb, apps, settings) }
    val titles by lazy { TitleRepositoryImpl(scope, db.dao(), tmdb, settings) { refresh(RefreshReason.WATCH_NEXT_CHANGED) } }
    val launcher by lazy { AppLauncherImpl(scope, launchAdapter, habits, settings, apps) { refresh(it) } }

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

    fun refresh(reason: RefreshReason) {
        scope.launch { home.refresh(reason) }
    }

    /** Only after the first frame: refreshers, receivers, image cache warm-up. */
    fun startBackground() {
        network.start()
        packages.watch {
            refresh(RefreshReason.PACKAGES_CHANGED)
            search.rebuildIndex()
        }
        watchNext.observe { refresh(RefreshReason.WATCH_NEXT_CHANGED) }
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
