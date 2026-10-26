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
import com.swissdark.launcher.domain.ContinueSource
import com.swissdark.launcher.domain.MediaType
import kotlinx.coroutines.launch
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
    val weatherNow by g.weather.now.collectAsState()
    val nowPlaying by g.watched.nowPlaying.collectAsState()
    val appsAll by g.apps.apps.collectAsState()
    val nowPlayingText = remember(nowPlaying, appsAll) {
        nowPlaying?.let { np ->
            val app = appsAll.firstOrNull { a -> a.id.packageName == np.packageName }?.label ?: np.packageName
            listOfNotNull(app, np.title?.takeIf { it.isNotBlank() }).joinToString(" — ").takeIf { it.isNotBlank() }
        }
    }
    val top = routes.last()
    var message by remember { mutableStateOf<String?>(null) }
    // How long the message stays (ms); the undo prompt for a removed card stays 5 s.
    var messageMs by remember { mutableStateOf(4_000L) }
    // Until when OK on Home brings back the card just removed.
    val undoUntil = remember { longArrayOf(0L) }
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
        // Fade-through: Home fades out before a page comes in, and back in after a page has gone.
        // Panels keep Home under their scrim.
        val oldPage = old != Route.Home && !isPanel(old)
        nav.homeTarget = if (top == Route.Home || isPanel(top) && old == Route.Home) 1f else 0f
        nav.homeDelay = if (top == Route.Home && oldPage && !cut) FADE_OUT_MS else 0
        nav.homeCut = cut
    }
    LaunchedEffect(top) {
        val target = nav.homeTarget
        if (nav.homeA.value == target) return@LaunchedEffect
        if (nav.homeCut) nav.homeA.snapTo(target)
        else {
            if (nav.homeDelay > 0) delay(nav.homeDelay.toLong())
            nav.homeA.animateTo(target, if (target == 0f) Motion.exit(FADE_OUT_MS) else Motion.enter(Motion.SHORT))
        }
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
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (top == Route.Home || !nav.covered.value) nav.homeA.value else 0f }) {
            HomeScreen(
                snapshot, wallpaper,
                HomeStatus(
                    device, network, isHome, settings.lastInput ?: "Home",
                    if (top == Route.Home) "Hold OK to remove a card or pin an app" else "",
                    weatherNow?.let { "${it.tempC}° ${it.text}" } ?: "",
                ),
                remember {
                    // OK during the undo window after a removal brings the card back instead.
                    fun undone(): Boolean {
                        if (SystemClock.uptimeMillis() >= undoUntil[0]) return false
                        undoUntil[0] = 0L
                        if (!g.home.undoRemove()) return false
                        messageMs = 2_000L
                        message = "Card restored"
                        return true
                    }
                    fun details(item: ContinueItem) {
                        val list = g.home.snapshot.value.upNext
                        activity.push(Route.UpNext(item, list.indexOf(item).coerceAtLeast(0), list.size))
                    }
                    HomeActions(
                        openItem = { item ->
                            val uri = item.intentUri
                            if (!undone()) when {
                                // OK = Resume, as the hint says: straight back into what was playing
                                // (the live session, the video at its position, else the app).
                                // The title page stays one hold-OK away ("Details").
                                item.source == ContinueSource.WATCHED ->
                                    if (uri == null || g.launcher.watchedOpener?.invoke(uri) != true) {
                                        report(g.launcher.openTitle(item.title ?: TitleRef(0, MediaType.TV, item.name), item.service), item.serviceName)
                                    }
                                item.source == ContinueSource.WATCH_NEXT && uri != null ->
                                    report(g.launcher.openTitle(item.title ?: TitleRef(0, MediaType.TV, item.name), item.service, uri), item.serviceName)
                                // Nothing to show about a bare app: open it, like an app tile does.
                                item.isAppOnly() -> report(g.launcher.launch(item.service), item.serviceName)
                                // Saved for later: nothing to resume yet, show what it is.
                                else -> details(item)
                            }
                        },
                        removeItem = {
                            g.home.removeFromUpNext(it)
                            undoUntil[0] = SystemClock.uptimeMillis() + 5_000L
                            messageMs = 5_000L
                            message = "Removed “${it.name}” · OK to undo"
                        },
                        details = { details(it) },
                        launchApp = { if (!undone()) report(g.launcher.launch(it.id), it.label) },
                        pinApp = { g.apps.setPinned(it.id, !it.pinned); g.refresh(RefreshReason.SETTINGS_CHANGED) },
                        allApps = { if (!undone()) activity.push(Route.AllApps) },
                        search = { if (!undone()) activity.push(Route.Search) },
                        inputs = { if (!undone()) activity.push(Route.Inputs) },
                        quickSettings = { if (!undone()) activity.push(Route.Quick) },
                        launcherSettings = { if (!undone()) activity.push(Route.Settings()) },
                    )
                },
                active = top == Route.Home,
                entrance = homeEntrance,
                backdropArt = remember { { item -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { g.backdropArt.bitmapFor(item) } } },
                nowPlaying = nowPlayingText,
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
                    val titleScope = androidx.compose.runtime.rememberCoroutineScope()
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
                                val ref = details?.ref ?: r.ref
                                fun go() {
                                    val res = g.launcher.openTitle(ref, app!!.id)
                                    // Remembered only to name what plays next in that app; not a card by itself.
                                    if (res !is LaunchResult.Failed) g.titles.recordOpened(ref, app.id, details?.backdropUrl ?: r.art)
                                    if (res !is LaunchResult.OpenedAppHome) report(res, a.providerName)
                                }
                                if (app == null) {
                                    message = "${a.providerName} isn’t installed — opening Play Store"
                                    pkg?.let { activity.openPlayStore(it) }
                                } else if (!g.launcher.opensTitles(app.id.packageName)) {
                                    // Netflix, Prime Video, JioHotstar take no search: say so before handing off.
                                    messageMs = 2_500L
                                    message = "${a.providerName} opens on its home — search “${ref.name}” there"
                                    titleScope.launch { delay(2_500); go() }
                                } else go()
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
                        backTo = if (routes.getOrNull(routes.size - 2) == Route.Quick) "Quick settings" else "Home",
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
                    panel = isPanel(r),
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
                delay(messageMs)
                if (reduce) msgP.snapTo(0f) else msgP.animateTo(0f, Motion.exit(Motion.SHORT))
                message = null
                shown = null
                messageMs = 4_000L
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
    /** Home's own alpha for fade-through (pages) — panels leave it at 1. */
    val homeA = Animatable(1f)
    var homeTarget = 1f
    var homeDelay = 0
    var homeCut = true
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
            // Pages fade through: the screen going away (or Home) fades out first, then this one comes in.
            if (p.value < 1f) p.animateTo(1f, if (panel) Motion.enter(Motion.LONG) else Motion.enter(Motion.MEDIUM, delay = FADE_OUT_MS))
            onCovered()
        } else {
            p.animateTo(0f, Motion.exit(if (panel) Motion.SHORT else FADE_OUT_MS))
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

/** Fade-through: how long the outgoing screen takes to fade out before the next one fades in. */
private const val FADE_OUT_MS = 90

private fun isPanel(r: Route) = r == Route.Quick || r == Route.Inputs
