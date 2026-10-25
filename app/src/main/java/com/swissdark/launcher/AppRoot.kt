package com.swissdark.launcher

import android.app.ActivityOptions
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.swissdark.launcher.core.design.LocalIconPack
import com.swissdark.launcher.core.design.LocalReduceMotion
import com.swissdark.launcher.core.design.LocalScreenProgress
import com.swissdark.launcher.core.design.Motion
import com.swissdark.launcher.core.design.drawSlide
import com.swissdark.launcher.core.design.enter
import com.swissdark.launcher.core.focus.LaunchOrigin
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
import com.swissdark.launcher.feature.home.isAppOnly
import com.swissdark.launcher.feature.home.isAppPlayback
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
    val reduce = LocalReduceMotion.current

    // Home becomes visible again (first show, or back from another app): Home plays its entrance,
    // and route changes in the next moment (the Home key clearing the stack) cut instead of fading.
    var homeEntrance by remember { mutableIntStateOf(0) }
    val startedAt = remember { longArrayOf(0L) }
    val lifecycle = activity.lifecycle
    DisposableEffect(lifecycle) {
        var first = true
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) {
                startedAt[0] = SystemClock.uptimeMillis()
                if (first) first = false else homeEntrance++
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Apps opened from a tile grow out of that tile (ActivityOptions scale-up from its rect).
    val view = LocalView.current
    DisposableEffect(view, reduce) {
        g.launchAdapter.launchOptions = if (reduce) null else {
            {
                LaunchOrigin.take()?.let { r ->
                    ActivityOptions.makeScaleUpAnimation(view, r[0], r[1], r[2].coerceAtLeast(1), r[3].coerceAtLeast(1)).toBundle()
                }
            }
        }
        onDispose { g.launchAdapter.launchOptions = null }
    }

    // Route transitions. The screen that was on top stays composed (inactive) while it fades out.
    val nav = remember { NavTransition() }
    if (nav.top != top) {
        val old = nav.top
        val cut = reduce || SystemClock.uptimeMillis() - startedAt[0] < 600
        nav.back = routes.size < nav.depth
        nav.leaving = if (cut || old == Route.Home || old == top) null else old
        nav.cut = cut
        nav.top = top
        nav.depth = routes.size
        // Home shows through only when a screen opens straight over it; page to page cross-fades on the ground.
        nav.covered.value = cut || old != Route.Home
    }
    val leaving = nav.leaving
    if (leaving != null) {
        LaunchedEffect(leaving) {
            delay(Motion.SHORT + 40L)
            if (nav.leaving == leaving) {
                nav.leaving = null
                nav.tick.intValue++
            }
        }
    }

    fun report(r: LaunchResult, what: String) {
        when (r) {
            is LaunchResult.Failed -> message = r.reason
            LaunchResult.OpenedAppHome -> message = "$what opened its home screen — search for it there"
            LaunchResult.Ok -> {}
        }
    }

    CompositionLocalProvider(LocalIconPack provides settings.iconPack) {
    Box(Modifier.fillMaxSize().background(Swiss.Ground)) {
        // Home stays drawn until the screen on top has fully covered it, then costs nothing.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (top == Route.Home || !nav.covered.value) 1f else 0f }) {
            HomeScreen(
                snapshot, wallpaper,
                HomeStatus(device, network, isHome, settings.lastInput ?: "Home", if (top == Route.Home) "Hold OK to remove a card or pin an app" else ""),
                remember {
                    HomeActions(
                        openItem = { item ->
                            if (item.isAppPlayback()) {
                                // Netflix, Prime…: no title to show a page for. Straight back in:
                                // the live session if there is one, else the app.
                                val uri = item.intentUri
                                if (uri == null || g.launcher.watchedOpener?.invoke(uri) != true) report(g.launcher.launch(item.service), item.serviceName)
                            } else if (item.isAppOnly()) {
                                // Nothing to show about a bare app: open it, like an app tile does.
                                report(g.launcher.launch(item.service), item.serviceName)
                            } else {
                                val list = g.home.snapshot.value.upNext
                                activity.push(Route.UpNext(item, list.indexOf(item).coerceAtLeast(0), list.size))
                            }
                        },
                        removeItem = { g.home.removeFromUpNext(it) },
                        launchApp = { report(g.launcher.launch(it.id), it.label) },
                        pinApp = { g.apps.setPinned(it.id, !it.pinned); g.refresh(RefreshReason.SETTINGS_CHANGED) },
                        allApps = { activity.push(Route.AllApps) },
                        search = { activity.push(Route.Search) },
                        inputs = { activity.push(Route.Inputs) },
                        quickSettings = { activity.push(Route.Quick) },
                        launcherSettings = { activity.push(Route.Settings()) },
                    )
                },
                active = top == Route.Home,
                entrance = homeEntrance,
                backdropArt = remember { { item -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { g.backdropArt.bitmapFor(item) } } },
            )
        }

        @Composable
        fun RouteScreen(r: Route, active: Boolean) {
            when (r) {
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
                        active = active,
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
                    active = active,
                )
                is Route.UpNext -> {
                    val item = r.item
                    var details by remember(item.key) { mutableStateOf<TitleDetails?>(null) }
                    LaunchedEffect(item.key) { item.title?.let { details = g.titles.details(it) } }
                    UpNextTitleScreen(
                        item, r.position, r.total, details, device,
                        UpNextActions(
                            resume = {
                                val res = if (item.isAppOnly()) g.launcher.launch(item.service)
                                else g.launcher.openTitle(item.title ?: TitleRef(0, com.swissdark.launcher.domain.MediaType.TV, item.name), item.service, item.intentUri)
                                report(res, item.serviceName)
                            },
                            remove = { g.home.removeFromUpNext(item); activity.pop() },
                            openEpisode = { report(g.launcher.openTitle(item.title ?: TitleRef(0, com.swissdark.launcher.domain.MediaType.TV, item.name), item.service), item.serviceName) },
                            back = { activity.pop() },
                        ),
                        active = active,
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
                        active = active,
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
                        active = active,
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
                        active = active,
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
                            tmdb = g.settings.settings.value.tmdbKey.isNotBlank() || BuildConfig.TMDB_KEY.isNotBlank(),
                            titleSource = g.tmdb.label,
                            photosPath = "/sdcard/Android/data/${BuildConfig.APPLICATION_ID}/files/photos",
                            iconPacks = remember { g.iconPacks() },
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
                        active = active,
                        initialSection = r.section,
                    )
                }
            }
        }

        // Own restart scope: dropping the faded-out screen recomposes only this part, not Home.
        Scope {
            nav.tick.intValue
            for (r in listOfNotNull(nav.leaving?.takeIf { it != top }, top.takeIf { it != Route.Home })) key(r) {
                val isTop = r == top
                RouteLayer(
                    panel = r == Route.Quick || r == Route.Inputs,
                    entering = isTop,
                    back = nav.back,
                    cut = nav.cut && isTop,
                    onCovered = { if (isTop) nav.covered.value = true },
                ) {
                    RouteScreen(r, isTop)
                }
            }
        }

        if (BuildConfig.DEBUG || settings.showLatencyOverlay) {
            LatencyOverlay(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp))
        }

        // Message bar: slides up and fades in, fades out after four seconds.
        val msg = message
        val msgP = remember { Animatable(0f) }
        var shown by remember { mutableStateOf<String?>(null) }
        if (msg != null) {
            LaunchedEffect(msg) {
                shown = msg
                if (reduce) msgP.snapTo(1f) else msgP.animateTo(1f, Motion.enter(Motion.MEDIUM))
                delay(4_000)
                if (reduce) msgP.snapTo(0f) else msgP.animateTo(0f, Motion.exit(Motion.SHORT))
                message = null
                shown = null
            }
        }
        shown?.let { text ->
            T(
                text, SwissType.Footer,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp).enter({ msgP.value }, dy = 24f)
                    .background(Swiss.Panel).topRule(4.dp, Swiss.Focus)
                    .padding(horizontal = 32.dp, vertical = 18.dp),
            )
        }
    }
    }
}

/** Bookkeeping for route transitions; plain fields are written during composition only. */
private class NavTransition {
    var top: Route = Route.Home
    var depth = 1
    var leaving: Route? = null
    var back = false
    var cut = true
    /** The top screen has finished entering and hides Home completely. */
    val covered = mutableStateOf(true)
    val tick = mutableIntStateOf(0)
}

/**
 * One route's layer. Pages fade in with a 32 px slide (from the right going forward, from the
 * left going back) and fade out the opposite way. Panels provide their progress to PanelFrame,
 * which fades the scrim side and slides the panel itself in from the right.
 */
@Composable
private fun RouteLayer(
    panel: Boolean,
    entering: Boolean,
    back: Boolean,
    cut: Boolean,
    onCovered: () -> Unit,
    content: @Composable () -> Unit,
) {
    val p = remember { Animatable(if (cut) 1f else 0f) }
    val dir = remember { floatArrayOf(0f) }
    LaunchedEffect(entering) {
        if (entering) {
            if (p.value < 1f) p.animateTo(1f, if (panel) Motion.enter(Motion.LONG) else Motion.enter(Motion.MEDIUM))
            onCovered()
        } else {
            p.animateTo(0f, Motion.exit(if (panel) Motion.SHORT else Motion.QUICK))
        }
    }
    // Going forward a page comes in from the right and the old one leaves to the left; Back reverses.
    dir[0] = if (entering) (if (back) -1f else 1f) else (if (back) 1f else -1f)
    val progress = remember { { p.value } }
    if (panel) {
        CompositionLocalProvider(LocalScreenProgress provides progress) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    } else {
        Box(Modifier.fillMaxSize().drawSlide(progress, dx = Motion.PAGE_SLIDE * dir[0])) { content() }
    }
}

@Composable
private fun Scope(content: @Composable () -> Unit) = content()
