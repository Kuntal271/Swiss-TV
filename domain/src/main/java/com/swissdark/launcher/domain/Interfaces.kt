package com.swissdark.launcher.domain

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime

enum class RefreshReason { COLD_START, RESUMED, PACKAGES_CHANGED, WATCH_NEXT_CHANGED, SETTINGS_CHANGED, SLOT_CHANGED }

sealed interface LaunchResult {
    data object Ok : LaunchResult
    data object OpenedAppHome : LaunchResult
    data class Failed(val reason: String) : LaunchResult
}

// :domain — what the UI is allowed to see
interface HomeRepository {
    val snapshot: StateFlow<HomeSnapshot>        // never empty after first frame
    suspend fun refresh(reason: RefreshReason)
    fun removeFromUpNext(item: ContinueItem)
}

interface AppsRepository {
    val apps: StateFlow<List<AppEntry>>          // every launchable app, hidden included
    fun setPinned(id: AppId, pinned: Boolean)
    fun setHidden(id: AppId, hidden: Boolean)
    fun move(id: AppId, delta: Int)
}

interface AppLauncher {
    fun launch(app: AppId): LaunchResult           // main thread, < 1 ms to post intent
    fun openTitle(title: TitleRef, service: AppId?, intentUri: String? = null): LaunchResult
    fun openTrailer(youtubeKey: String): LaunchResult
    fun openSystemSettings(): LaunchResult
    fun openUrl(uri: String, pkg: String? = null): LaunchResult
}

class PreparedWallpaper(
    val slot: TimeSlot,
    val wallpaper: Wallpaper?,
    val bitmap: Bitmap?,                          // hardware bitmap, already darkened, 1920x1080
    val dim: Float,
)

interface WallpaperScheduler {
    val current: StateFlow<PreparedWallpaper>      // already decoded at 1920x1080
    val catalog: StateFlow<List<Wallpaper>>
    fun slotFor(time: LocalTime): TimeSlot
    fun loadThumbnail(wallpaper: Wallpaper, width: Int, height: Int): Bitmap?
    fun loadUndimmed(): Bitmap?
}

interface SearchEngine {
    fun local(query: String): List<SearchHit>      // synchronous, < 5 ms
    fun remote(query: String): Flow<List<SearchHit>>
    fun recent(): Flow<List<SearchHit.Recent>>
    fun remember(query: String, kind: String)
    fun forget(query: String)
    suspend fun trending(daily: Boolean): List<SearchHit.Title>
    suspend fun byGenre(genre: String): List<SearchHit>
    suspend fun byPerson(personId: Int): List<SearchHit>
    suspend fun availability(hits: List<SearchHit.Title>): List<SearchHit.Title>
    /** Where online titles come from right now: "TMDB", "OMDb + TVmaze" or "TVmaze". */
    val titleSource: String get() = "TMDB"
}

interface TitleRepository {
    suspend fun details(ref: TitleRef): TitleDetails?
    fun saveForLater(details: TitleDetails)
    fun recordOpened(ref: TitleRef, service: AppId, artUrl: String?)
}

interface SettingsRepository {
    val settings: StateFlow<LauncherSettings>
    fun update(transform: (LauncherSettings) -> LauncherSettings)
}

interface InputsRepository {
    fun inputs(): List<TvInput>
    fun switchTo(input: TvInput): LaunchResult
}

/** Status line data for every screen's header. */
interface DeviceStatus {
    val deviceName: String
    val network: StateFlow<String>                 // "Wi-Fi · Connected"
    val networkName: StateFlow<String>             // "Wi-Fi · MyNetwork"
    val isHomeApp: StateFlow<Boolean>
    fun checkHome()
}

enum class PictureMode { MOVIE, STANDARD, VIVID, GAME }
enum class SoundMode { STANDARD, DIALOGUE, NIGHT }

interface QuickSettingsController {
    /** TCL keeps picture and sound modes private; each call falls back to opening TCL's own page. */
    fun setPicture(mode: PictureMode): LaunchResult
    fun setSound(mode: SoundMode): LaunchResult
    fun setSleep(minutes: Int): LaunchResult
    fun pictureOff(): LaunchResult
    val pictureMode: StateFlow<PictureMode?>
    val soundMode: StateFlow<SoundMode?>
}

/** Map of TMDB provider ids to the packages on this TV. */
object Providers {
    data class Provider(val key: String, val name: String, val tmdbIds: Set<Int>, val packages: List<String>, val color: Long)

    val all = listOf(
        Provider("netflix", "Netflix", setOf(8, 175, 1796), listOf("com.netflix.ninja"), 0xFFB20710),
        Provider("prime", "Prime Video", setOf(9, 119, 2100), listOf("com.amazon.amazonvideo.livingroom"), 0xFF0F79AF),
        Provider("jiohotstar", "JioHotstar", setOf(122, 220, 2336), listOf("com.jio.media.stb.ondemand", "in.startv.hotstar"), 0xFF0F1E5A),
        Provider("sonyliv", "SonyLIV", setOf(237), listOf("com.sonyliv"), 0xFF1D1D1D),
        Provider("zee5", "ZEE5", setOf(232), listOf("com.graymatrix.did"), 0xFF6A1B9A),
        Provider("appletv", "Apple TV", setOf(2, 350), listOf("com.apple.atve.androidtv.appletv"), 0xFF1C1C1E),
        Provider("youtube", "YouTube", setOf(192, 188), listOf("com.google.android.youtube.tv"), 0xFFCC0000),
        Provider("crunchyroll", "Crunchyroll", setOf(283), listOf("com.crunchyroll.crunchyroid"), 0xFFF47521),
        Provider("googletv", "Google TV", setOf(3), listOf("com.google.android.videos"), 0xFF1A73E8),
    )

    fun byTmdb(id: Int) = all.firstOrNull { id in it.tmdbIds }
    fun byPackage(pkg: String) = all.firstOrNull { pkg in it.packages }
    fun byKey(key: String) = all.firstOrNull { it.key == key }
}

/** Pre-sized app banners for cards when a title has no artwork. Cached; safe to call on any thread. */
interface AppArt {
    fun banner(packageName: String, width: Int, height: Int): Bitmap?

    /**
     * 16:9 art for an app tile: the app's TV banner, else its launcher icon (or the chosen icon
     * pack's) drawn large on its brand colour. Null = no art; draw the glyph. Slow on a miss
     * (PackageManager, decoding): call it off the main thread. [iconPack] "" = app logos.
     */
    fun tile(component: String, width: Int, height: Int, iconPack: String): Bitmap? = null

    /** [tile] from the cache only: never blocks. */
    fun cachedTile(component: String, width: Int, height: Int, iconPack: String): Bitmap? = null
}
