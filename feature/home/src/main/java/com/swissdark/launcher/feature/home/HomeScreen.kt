package com.swissdark.launcher.feature.home

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import com.swissdark.launcher.core.focus.HomePress
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import com.swissdark.launcher.core.design.Backdrop
import com.swissdark.launcher.core.design.CrossfadeText
import com.swissdark.launcher.core.design.LocalReduceMotion
import com.swissdark.launcher.core.design.Motion
import com.swissdark.launcher.core.design.enter
import com.swissdark.launcher.core.design.Fmt
import com.swissdark.launcher.core.design.Frame
import com.swissdark.launcher.core.design.Header
import com.swissdark.launcher.core.design.IconTile
import com.swissdark.launcher.core.design.Label
import com.swissdark.launcher.core.design.PosterCard
import com.swissdark.launcher.core.design.ProgressBar
import com.swissdark.launcher.core.design.Swiss
import com.swissdark.launcher.core.design.SwissType
import com.swissdark.launcher.core.design.T
import com.swissdark.launcher.core.design.TabText
import com.swissdark.launcher.core.design.rememberMinute
import com.swissdark.launcher.core.design.topRule
import com.swissdark.launcher.core.focus.DpadKey
import com.swissdark.launcher.core.focus.FocusRoot
import com.swissdark.launcher.core.focus.rememberFocusScreen
import com.swissdark.launcher.domain.AppEntry
import com.swissdark.launcher.domain.ContinueItem
import com.swissdark.launcher.domain.HomeSnapshot
import com.swissdark.launcher.domain.PreparedWallpaper
import com.swissdark.launcher.domain.TimeSlot
import com.swissdark.launcher.domain.hero
import androidx.compose.foundation.layout.padding
import com.swissdark.launcher.domain.ContinueSource

@Immutable
class HomeActions(
    val openItem: (ContinueItem) -> Unit,
    val removeItem: (ContinueItem) -> Unit,
    val launchApp: (AppEntry) -> Unit,
    val pinApp: (AppEntry) -> Unit,
    val allApps: () -> Unit,
    val search: () -> Unit,
    val inputs: () -> Unit,
    val quickSettings: () -> Unit,
)

@Immutable
class HomeStatus(val deviceName: String, val network: String, val isHome: Boolean, val input: String, val hint: String)

private val links = listOf("Search", "All apps", "Inputs", "Settings")

/**
 * Home. One screen for all four times of day: only the wallpaper and the habit ranking change.
 * A press moves one integer; the hero text reads the focused card from the snapshot.
 */
@Composable
fun HomeScreen(snapshot: HomeSnapshot, wallpaper: PreparedWallpaper, status: HomeStatus, actions: HomeActions, active: Boolean, entrance: Int = 0, backdropArt: (suspend (ContinueItem) -> android.graphics.Bitmap?)? = null) {
    val up = snapshot.upNext
    val apps = snapshot.apps
    val nUp = up.size
    val nApps = apps.size
    val count = nUp + nApps + links.size
    val focus = rememberFocusScreen(count, initial = 0, active = active)
    val now by rememberMinute()

    focus.onClick = { i ->
        when {
            i < nUp -> actions.openItem(up[i])
            i < nUp + nApps -> actions.launchApp(apps[i - nUp])
            else -> when (i - nUp - nApps) {
                0 -> actions.search()
                1 -> actions.allApps()
                2 -> actions.inputs()
                else -> actions.quickSettings()
            }
        }
    }
    focus.onLongClick = { i ->
        when {
            i < nUp -> actions.removeItem(up[i])
            i < nUp + nApps -> actions.pinApp(apps[i - nUp])
        }
    }
    focus.onEdge = { _, _: DpadKey -> false }
    // A new snapshot never moves focus out from under the user: it follows the item's id.
    val keys = remember(up, apps) { up.map { it.key } + apps.map { it.id.component } + links }
    val lastKey = remember { arrayOfNulls<String>(1) }
    focus.onFocusChanged = { lastKey[0] = keys.getOrNull(it) }
    LaunchedEffect(keys) {
        val idx = keys.indexOf(lastKey[0])
        if (idx >= 0 && idx != focus.focused) focus.focus(idx)
    }
    // Home pressed while Home is already in front: back to the first Up next card (or first app).
    LaunchedEffect(focus) {
        snapshotFlow { HomePress.count.intValue }.drop(1).collect { if (focus.count > 0) focus.focus(0) }
    }
    focus.onOtherKey = { e ->
        if (e.action != KeyEvent.ACTION_UP) e.keyCode == KeyEvent.KEYCODE_BACK
        else when (e.keyCode) {
            KeyEvent.KEYCODE_SEARCH -> { actions.search(); true }
            KeyEvent.KEYCODE_TV_INPUT -> { actions.inputs(); true }
            KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_MENU -> { actions.quickSettings(); true }
            KeyEvent.KEYCODE_BACK -> true          // Home is the bottom of the stack
            else -> false
        }
    }

    // Entrance (first show, back from an app): five groups settle in turn, top to bottom.
    // Everything is composed and drawn on frame 1 (from 35 % alpha), 300 ms in all. Header and
    // clock also rise 16 px (canvas translation); the rows with focus items only fade, so focus
    // rects and the ring never move.
    val reduce = LocalReduceMotion.current
    val enter = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(entrance) {
        if (reduce) return@LaunchedEffect
        enter.snapTo(0f)
        enter.animateTo(1f, tween(ENTRANCE_MS, easing = LinearEasing))
    }
    val g0 = remember { group(enter, 0) }
    val g1 = remember { group(enter, 1) }
    val g2 = remember { group(enter, 2) }
    val g3 = remember { group(enter, 3) }
    val g4 = remember { group(enter, 4) }

    Box(Modifier.fillMaxSize()) {
        // Focus backdrop: the focused Continue watching card's art over the wallpaper (indices < nUp).
        com.swissdark.launcher.core.design.FocusBackdrop(wallpaper, com.swissdark.launcher.core.design.rememberFocusedArt(focus.engine.focused, up, backdropArt))
        FocusRoot(focus, Swiss.Focus) {
            Frame {
                Box(Modifier.enter(g0, fromAlpha = FROM_ALPHA)) {
                    Header(status.deviceName.uppercase(), now.format(Fmt.headerDate), status.network, "Home")
                }
                // Clock, hero, caption
                Row(Modifier.fillMaxWidth().weight(1f).enter(g1, fromAlpha = FROM_ALPHA), horizontalArrangement = Arrangement.spacedBy(Swiss.GUTTER.dp)) {
                    Column(Modifier.width(Swiss.span(6)).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CrossfadeText(now.format(Fmt.clock), SwissType.Clock)
                            Label(now.format(Fmt.longDate))
                        }
                        Hero(focus.engine.focused, up, status)
                    }
                    Box(Modifier.fillMaxHeight().weight(1f), contentAlignment = Alignment.BottomEnd) {
                        T(caption(wallpaper), SwissType.Meta, maxLines = 1)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Swiss.GUTTER.dp)) {
                    Column(Modifier.width(Swiss.span(7)).enter(g2, dy = 0f, fromAlpha = FROM_ALPHA), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Label("Continue watching")
                        if (up.isEmpty()) {
                            T("Nothing to continue yet — start something and it’ll appear here.", SwissType.Body22, Modifier.padding(vertical = 60.dp))
                        } else Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            for ((i, item) in up.withIndex()) {
                                PosterCard(
                                    focus, i, item.artKey, 150.dp, item.name, item.meta,
                                    Modifier.weight(1f), progress = item.progress,
                                )
                            }
                            repeat(3 - up.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                    Column(Modifier.width(Swiss.span(5)).enter(g3, dy = 0f, fromAlpha = FROM_ALPHA), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Label("Apps · by habit at %02d:00".format(snapshot.habitHour))
                        AppsGrid(focus, apps, nUp)
                    }
                }
                Box(Modifier.enter(g4, dy = 0f, fromAlpha = FROM_ALPHA)) {
                    Footer(focus, nUp + nApps, status.hint, up, apps)
                }
            }
        }
    }
}

private const val ENTRANCE_MS = 300
private const val GROUP_MS = 180
private const val GROUP_STEP = 30
private const val FROM_ALPHA = 0.35f

/** Group k's eased 0..1 progress inside the shared entrance timeline. */
private fun group(t: Animatable<Float, *>, k: Int): () -> Float = {
    val ms = t.value * ENTRANCE_MS - k * GROUP_STEP
    Motion.EnterEasing.transform((ms / GROUP_MS).coerceIn(0f, 1f))
}

@Composable
private fun Hero(focused: androidx.compose.runtime.IntState, up: List<ContinueItem>, status: HomeStatus) {
    // Derived: a press recomposes the hero only when it lands on a different Up next card,
    // not on every move between app tiles and links.
    val heroIndex by remember(up) { derivedStateOf { val i = focused.intValue; if (i < up.size) i else 0 } }
    val item = up.getOrNull(heroIndex)
    // New card under focus: the hero text fades up from 20 % and slides 12 px in from the right.
    val reduce = LocalReduceMotion.current
    val swap = remember { Animatable(1f) }
    val shownKey = remember { arrayOfNulls<String>(1) }
    val key = item?.key
    LaunchedEffect(key) {
        val changed = shownKey[0] != null && shownKey[0] != key
        shownKey[0] = key
        if (changed && !reduce) {
            swap.snapTo(0f)
            swap.animateTo(1f, Motion.enter(Motion.MEDIUM))
        }
    }
    Column(Modifier.widthIn(max = 760.dp).enter({ swap.value }, dx = 12f, dy = 0f, fromAlpha = 0.2f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!status.isHome) {
            Label("A TV update reset Home — run setup again.", color = Swiss.Focus)
        }
        if (item != null) {
            val h = item.hero()
            Label(h.label, color = Swiss.Focus)
            T(h.title, SwissType.Hero, maxLines = 2)
            T(h.meta, SwissType.Meta22, maxLines = 1)
            if (h.progress != null) ProgressBar(h.progress!!, Swiss.Ink)
        }
    }
}

@Composable
private fun AppsGrid(focus: com.swissdark.launcher.core.focus.FocusScreenState, apps: List<AppEntry>, base: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (row in 0 until 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (col in 0 until 3) {
                    val k = row * 3 + col
                    val a = apps.getOrNull(k)
                    if (a == null) Box(Modifier.weight(1f)) else IconTile(
                        focus, base + k, a.icon, a.label, 112.dp, Modifier.weight(1f),
                        badge = if (a.pinned) "Pin" else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun Footer(
    focus: com.swissdark.launcher.core.focus.FocusScreenState,
    base: Int,
    hint: String,
    up: List<ContinueItem>,
    apps: List<AppEntry>,
) {
    Row(Modifier.fillMaxWidth().topRule().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        FooterHint(focus, hint, up, apps, Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            for ((k, l) in links.withIndex()) {
                TabText(focus, base + k, l, selected = false, style = SwissType.Footer, unselected = Swiss.Ink, underline = 3.dp)
            }
        }
    }
}

private fun caption(w: PreparedWallpaper): String {
    val slot: TimeSlot = w.slot
    val name = w.wallpaper?.name ?: "No wallpaper yet — run setup.sh"
    return "${slot.label} ${slot.range} · $name" + if (w.wallpaper?.is4k == true) " · 4K" else ""
}

/** An Up next entry that is just an app (no title, no deep link): OK opens the app itself. */
fun ContinueItem.isAppOnly(): Boolean = source == ContinueSource.APP || (title == null && intentUri == null)

/**
 * Something played in an app that never says what (Netflix, Prime Video…): the card is the app.
 * OK resumes it straight away (live session, else the app); there is no title page to show.
 * Matches WatchedStore's "<package>|@app" rows.
 */
fun ContinueItem.isAppPlayback(): Boolean = source == ContinueSource.WATCHED && title == null && key.endsWith("|@app")

/** Reads focus in its own scope, so a key press recomposes only this line. */
@Composable
private fun FooterHint(
    focus: com.swissdark.launcher.core.focus.FocusScreenState,
    hint: String,
    up: List<ContinueItem>,
    apps: List<AppEntry>,
    modifier: Modifier,
) {
    // Every focusable's hint is built once per snapshot; a press only picks one, and this line
    // recomposes only when the picked text actually changes.
    val hints = remember(up, apps, hint) { Array(up.size + apps.size) { footerHint(it, up, apps, hint) } }
    val text by remember(hints, hint) {
        derivedStateOf { if (hint.isEmpty()) "" else hints.getOrNull(focus.engine.focused.intValue) ?: hint }
    }
    T(text, SwissType.Footer, modifier, Swiss.Muted, maxLines = 1)
}

/** The footer says what OK and Hold OK do on whatever is focused. */
private fun footerHint(i: Int, up: List<ContinueItem>, apps: List<AppEntry>, fallback: String): String {
    val nUp = up.size
    return when {
        i < nUp -> {
            val item = up[i]
            when {
                item.isAppPlayback() -> "OK goes back to ${item.serviceName} · Hold OK to remove it"
                item.isAppOnly() -> "OK opens ${item.serviceName} · Hold OK to remove it"
                else -> "OK for details and resume · Hold OK to remove it"
            }
        }
        i < nUp + apps.size -> {
            val a = apps[i - nUp]
            "OK opens ${a.label} · Hold OK to ${if (a.pinned) "unpin it" else "pin it here"}"
        }
        else -> fallback
    }
}
