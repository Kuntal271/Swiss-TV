package com.swissdark.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.swissdark.launcher.core.design.Swiss
import com.swissdark.launcher.core.design.SwissType
import com.swissdark.launcher.core.design.T
import com.swissdark.launcher.core.design.topRule
import com.swissdark.launcher.core.perf.LatencyOverlay
import com.swissdark.launcher.data.DataGraph
import com.swissdark.launcher.domain.Availability
import com.swissdark.launcher.domain.ContinueItem
import com.swissdark.launcher.domain.ContinueSource
import com.swissdark.launcher.domain.LaunchResult
import com.swissdark.launcher.domain.Providers
import com.swissdark.launcher.domain.RefreshReason
import com.swissdark.launcher.domain.SearchHit
import com.swissdark.launcher.domain.TitleDetails
import com.swissdark.launcher.domain.TitleRef
import com.swissdark.launcher.feature.apps.AllAppsActions
import com.swissdark.launcher.feature.apps.AllAppsScreen
import com.swissdark.launcher.feature.home.HomeActions
import com.swissdark.launcher.feature.home.HomeScreen
import com.swissdark.launcher.feature.home.HomeStatus
import com.swissdark.launcher.feature.panels.InputsActions
import com.swissdark.launcher.feature.panels.InputsPanel
import com.swissdark.launcher.feature.panels.QuickActions
import com.swissdark.launcher.feature.panels.QuickSettingsPanel
import com.swissdark.launcher.feature.search.SearchActions
import com.swissdark.launcher.feature.search.SearchScreen
import com.swissdark.launcher.feature.settings.LauncherSettingsScreen
import com.swissdark.launcher.feature.settings.SettingsActions
import com.swissdark.launcher.feature.settings.SettingsInfo
import com.swissdark.launcher.feature.title.TitleActions
import com.swissdark.launcher.feature.title.TitleDetailsScreen
import com.swissdark.launcher.feature.title.UpNextActions
import com.swissdark.launcher.feature.title.UpNextTitleScreen
import kotlinx.coroutines.delay

sealed interface Route {
    data object Home : Route
    data object AllApps : Route
    data object Search : Route
    data class UpNext(val item: ContinueItem, val position: Int, val total: Int) : Route
    data class Title(val ref: TitleRef, val from: String, val art: String?) : Route
    data object Inputs : Route
    data object Quick : Route
    data class Settings(val section: Int = 1) : Route
}

/**
 * Home stays composed underneath everything (hidden, not torn down), so the Home key shows it
 * without rebuilding. Other screens are composed while they are on top.
 */
@Composable
fun AppRoot(g: DataGraph, routes: SnapshotStateList<Route>, activity: MainActivity) {
    val snapshot by g.home.snapshot.collectAsState()
    val wallpaper by g.wallpapers.current.collectAsState()
    val settings by g.settings.settings.collectAsState()
    val network by g.status.network.collectAsState()
    val isHome by g.status.isHomeApp.collectAsState()
    val top = routes.last()
    var message by remember { mutableStateOf<String?>(null) }
    val device = g.status.deviceName

    fun report(r: LaunchResult, what: String) {
        when (r) {
            is LaunchResult.Failed -> message = r.reason
            LaunchResult.OpenedAppHome -> message = "$what opened its home screen — search for it there"
            LaunchResult.Ok -> {}
        }
    }

    Box(Modifier.fillMaxSize().background(Swiss.Ground)) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (top == Route.Home) 1f else 0f }) {
            HomeScreen(
                snapshot, wallpaper,
                HomeStatus(device, network, isHome, settings.lastInput ?: "Home", if (top == Route.Home) "Hold OK to remove a card or pin an app · Hold Home for Google TV" else ""),
                remember {
                    HomeActions(
                        openItem = { item ->
                            val list = g.home.snapshot.value.upNext
                            activity.push(Route.UpNext(item, list.indexOf(item).coerceAtLeast(0), list.size))
                        },
                        removeItem = { g.home.removeFromUpNext(it) },
                        launchApp = { report(g.launcher.launch(it.id), it.label) },
                        pinApp = { g.apps.setPinned(it.id, !it.pinned); g.refresh(RefreshReason.SETTINGS_CHANGED) },
                        allApps = { activity.push(Route.AllApps) },
                        search = { activity.push(Route.Search) },
                        inputs = { activity.push(Route.Inputs) },
                        quickSettings = { activity.push(Route.Quick) },
                    )
                },
                active = top == Route.Home,
            )
        }

        when (val r = top) {
            Route.Home -> {}
            Route.AllApps -> {
                val all by g.apps.apps.collectAsState()
                val hidden = settings.hidden.toHashSet()
                val shown = all.filter { it.id.packageName !in hidden }
                AllAppsScreen(
                    shown, all.size - shown.size, settings.customOrder.isNotEmpty(), device, wallpaper,
                    remember {
                        AllAppsActions(
                            launch = { report(g.launcher.launch(it.id), it.label) },
                            setPinned = { a, p -> g.apps.setPinned(a.id, p); g.refresh(RefreshReason.SETTINGS_CHANGED) },
                            hide = { g.apps.setHidden(it.id, true); g.refresh(RefreshReason.SETTINGS_CHANGED) },
                            move = { a, d -> g.apps.move(a.id, d) },
                            back = { activity.pop() },
                        )
                    },
                    active = true,
                )
            }
            Route.Search -> SearchScreen(
                g.search,
                settings.myServices.mapNotNull { Providers.byKey(it)?.name },
                g.tmdb.hasKey,
                device, wallpaper,
                remember {
                    SearchActions(
                        launchApp = { report(g.launcher.launch(it.id), it.label) },
                        openTitle = { hit: SearchHit.Title, from -> activity.push(Route.Title(hit.ref, from, hit.backdropUrl)) },
                        listen = { p, f, e -> activity.listen(p, f, e) },
                        stopListening = { activity.stopListening() },
                        back = { activity.pop() },
                    )
                },
                active = true,
            )
            is Route.UpNext -> {
                val item = r.item
                var details by remember(item.key) { mutableStateOf<TitleDetails?>(null) }
                LaunchedEffect(item.key) { item.title?.let { details = g.titles.details(it) } }
                UpNextTitleScreen(
                    item, r.position, r.total, details, device,
                    UpNextActions(
                        resume = {
                            val res = if (item.source == ContinueSource.APP || item.title == null && item.intentUri == null) g.launcher.launch(item.service)
                            else g.launcher.openTitle(item.title ?: TitleRef(0, com.swissdark.launcher.domain.MediaType.TV, item.name), item.service, item.intentUri)
                            report(res, item.serviceName)
                        },
                        remove = { g.home.removeFromUpNext(item); activity.pop() },
                        openEpisode = { report(g.launcher.openTitle(item.title ?: TitleRef(0, com.swissdark.launcher.domain.MediaType.TV, item.name), item.service), item.serviceName) },
                        back = { activity.pop() },
                    ),
                    active = true,
                )
            }
            is Route.Title -> {
                var details by remember(r.ref) { mutableStateOf<TitleDetails?>(null) }
                var failed by remember(r.ref) { mutableStateOf(false) }
                var saved by remember(r.ref) { mutableStateOf(false) }
                LaunchedEffect(r.ref) {
                    details = g.titles.details(r.ref)
                    failed = details == null
                }
                TitleDetailsScreen(
                    r.ref, details, failed, r.from, saved, device,
                    TitleActions(
                        watch = { a: Availability ->
                            val pkg = a.packageName
                            val app = pkg?.let { p -> g.apps.apps.value.firstOrNull { it.id.packageName == p } }
                            if (app == null) {
                                message = "${a.providerName} isn’t installed — opening Play Store"
                                pkg?.let { activity.openPlayStore(it) }
                            } else {
                                val res = g.launcher.openTitle(details?.ref ?: r.ref, app.id)
                                if (res !is LaunchResult.Failed) g.titles.recordOpened(details?.ref ?: r.ref, app.id, details?.backdropUrl ?: r.art)
                                report(res, a.providerName)
                            }
                        },
                        trailer = { report(g.launcher.openTrailer(it), "YouTube") },
                        save = { details?.let { g.titles.saveForLater(it); saved = true } },
                        back = { activity.pop() },
                    ),
                    active = true,
                )
            }
            Route.Inputs -> {
                val inputs = remember { g.inputs.inputs() }
                InputsPanel(
                    inputs, settings.lastInput, wallpaper,
                    InputsActions(
                        switchTo = { inp ->
                            g.settings.update { it.copy(lastInput = inp.label) }
                            report(g.inputs.switchTo(inp), inp.label)
                        },
                        back = { activity.pop() },
                    ),
                    active = true,
                )
            }
            Route.Quick -> {
                val pic by g.quickSettings.pictureMode.collectAsState()
                val snd by g.quickSettings.soundMode.collectAsState()
                val netName by g.status.networkName.collectAsState()
                QuickSettingsPanel(
                    pic, snd, settings.sleepMinutes, netName, "Home", wallpaper,
                    QuickActions(
                        picture = { report(g.quickSettings.setPicture(it), "TCL picture settings") },
                        sound = { report(g.quickSettings.setSound(it), "TCL sound settings") },
                        sleep = { m -> g.settings.update { it.copy(sleepMinutes = m) }; report(g.quickSettings.setSleep(m), "TCL sleep timer") },
                        pictureOff = { report(g.quickSettings.pictureOff(), "TCL settings") },
                        allSettings = { report(g.launcher.openSystemSettings(), "Settings") },
                        launcherSettings = { activity.push(Route.Settings()) },
                        back = { activity.pop() },
                    ),
                    active = true,
                )
            }
            is Route.Settings -> {
                val catalog by g.wallpapers.catalog.collectAsState()
                val all by g.apps.apps.collectAsState()
                LauncherSettingsScreen(
                    settings, catalog, all,
                    SettingsInfo(
                        version = BuildConfig.VERSION_NAME,
                        isHome = isHome,
                        usageAccess = g.usage.hasAccess(),
                        watchNext = g.home.watchNextStatus,
                        tmdb = g.tmdb.hasKey,
                        photosPath = "/sdcard/Android/data/${BuildConfig.APPLICATION_ID}/files/photos",
                    ),
                    device, wallpaper,
                    remember {
                        SettingsActions(
                            update = { t ->
                                g.settings.update(t)
                                g.apps.reload()
                                g.refresh(RefreshReason.SETTINGS_CHANGED)
                                g.search.rebuildIndex()
                            },
                            thumbnail = { w, a, b -> g.wallpapers.loadThumbnail(w, a, b) },
                            rescanWallpapers = { g.wallpapers.refresh() },
                            back = { activity.pop() },
                        )
                    },
                    active = true,
                    initialSection = r.section,
                )
            }
        }

        if (BuildConfig.DEBUG || settings.showLatencyOverlay) {
            LatencyOverlay(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp))
        }

        val msg = message
        if (msg != null) {
            LaunchedEffect(msg) { delay(4_000); message = null }
            T(
                msg, SwissType.Footer,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp).background(Swiss.Panel).topRule(4.dp, Swiss.Focus)
                    .padding(horizontal = 32.dp, vertical = 18.dp),
            )
        }
    }
}

