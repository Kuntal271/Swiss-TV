package com.swissdark.launcher.feature.home

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.swissdark.launcher.core.design.AppTile
import com.swissdark.launcher.core.design.Glyph
import com.swissdark.launcher.core.design.HSpace
import com.swissdark.launcher.core.design.KeyHint
import com.swissdark.launcher.core.design.KeyHintText
import com.swissdark.launcher.core.design.NavChip
import com.swissdark.launcher.core.design.bottomRule
import com.swissdark.launcher.core.design.Label
import com.swissdark.launcher.core.design.PosterCard
import com.swissdark.launcher.core.design.ProgressBar
import com.swissdark.launcher.core.design.Swiss
import com.swissdark.launcher.core.design.SwissType
import com.swissdark.launcher.core.design.T
import com.swissdark.launcher.core.design.rememberMinute
import com.swissdark.launcher.core.design.topRule
import com.swissdark.launcher.core.focus.DpadKey
import com.swissdark.launcher.core.focus.FocusRoot
import com.swissdark.launcher.core.focus.FocusScreenState
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
    val launcherSettings: () -> Unit = quickSettings,
)

@Immutable
class HomeStatus(val deviceName: String, val network: String, val isHome: Boolean, val input: String, val hint: String)

/** Top navigation: glyph + label. Index order = focus order after the content. */
private val links = listOf("search" to "Search", "grid" to "All apps", "monitor" to "Inputs", "adjustments" to "Settings")

/** Two rows of four, by habit. */
private const val APP_COLS = 4
private const val APP_ROWS = 2

/**
 * Home. One screen for all four times of day: only the wallpaper and the habit ranking change.
 * A press moves one integer; the hero text reads the focused card from the snapshot.
 * Focus order: Continue watching cards, apps, then the four nav buttons in the header.
 */
@Composable
fun HomeScreen(snapshot: HomeSnapshot, wallpaper: PreparedWallpaper, status: HomeStatus, actions: HomeActions, active: Boolean, entrance: Int = 0) {
    val up = snapshot.upNext
    val apps = snapshot.apps.take(APP_COLS * APP_ROWS)
    val nUp = up.size
    val nApps = apps.size
    val navBase = nUp + nApps
    val count = navBase + links.size
    val focus = rememberFocusScreen(count, initial = 0, active = active)
    val now by rememberMinute()

    focus.onClick = { i ->
        when {
            i < nUp -> actions.openItem(up[i])
            i < nUp + nApps -> actions.launchApp(apps[i - nUp])
            else -> when (i - navBase) {
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
    val keys = remember(up, apps) { up.map { it.key } + apps.map { it.id.component } + links.map { it.second } }
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
    // MENU: a press opens Quick settings, holding it opens Launcher settings.
    val menuHeld = remember { BooleanArray(1) }
    focus.onOtherKey = { e ->
        if (e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> true
            KeyEvent.KEYCODE_MENU -> {
                if (e.repeatCount == 0) menuHeld[0] = false
                else if (!menuHeld[0] && (e.isLongPress || e.eventTime - e.downTime > 500)) {
                    menuHeld[0] = true
                    actions.launcherSettings()
                }
                true
            }
            else -> false
        }
        else if (e.action != KeyEvent.ACTION_UP) false
        else when (e.keyCode) {
            KeyEvent.KEYCODE_SEARCH -> { actions.search(); true }
            KeyEvent.KEYCODE_TV_INPUT -> { actions.inputs(); true }
            KeyEvent.KEYCODE_MENU -> {
                if (!menuHeld[0]) actions.quickSettings()
                menuHeld[0] = false
                true
            }
            KeyEvent.KEYCODE_SETTINGS -> { actions.quickSettings(); true }
            KeyEvent.KEYCODE_BACK -> {
                // Home is the bottom of the stack. Back goes to the first card (or app), and
                // from there to Search.
                val first = if (navBase > 0) 0 else navBase
                focus.focus(if (focus.focused == first) navBase else first, animate = true)
                true
            }
            else -> false
        }
    }

    // Entrance (first show, back from an app): five groups settle in turn, top to bottom.
    // Everything is composed and drawn on frame 1 (from 35 % alpha), 300 ms in all. The clock
    // column also rises 16 px (canvas translation); the parts with focus items only fade, so
    // focus rects and the ring never move.
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
        Backdrop(wallpaper)
        FocusRoot(focus, Swiss.Focus) {
            Frame {
                Box(Modifier.enter(g0, dy = 0f, fromAlpha = FROM_ALPHA)) {
                    HomeHeader(focus, navBase, status)
                }
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Swiss.GUTTER.dp)) {
                    // Clock, date, hero, caption
                    Column(
                        Modifier.width(Swiss.span(4)).fillMaxHeight().enter(g1, fromAlpha = FROM_ALPHA),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CrossfadeText(now.format(Fmt.clock), SwissType.Clock)
                            Label(now.format(Fmt.longDate))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
                            Hero(focus.engine.focused, up, status)
                            caption(wallpaper)?.let { T(it, SwissType.Meta, maxLines = 1) }
                        }
                    }
                    // Continue watching (only when there is something), then apps.
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                        if (nUp > 0) {
                            Column(Modifier.enter(g2, dy = 0f, fromAlpha = FROM_ALPHA), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Label("Continue watching")
                                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                    for ((i, item) in up.withIndex()) {
                                        PosterCard(
                                            focus, i, item.artKey, 140.dp, item.name, null,
                                            Modifier.weight(1f), progress = item.progress,
                                        )
                                    }
                                    repeat(3 - up.size) { Box(Modifier.weight(1f)) }
                                }
                            }
                        }
                        Column(Modifier.enter(g3, dy = 0f, fromAlpha = FROM_ALPHA), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Label("Apps · by habit at %02d:00".format(snapshot.habitHour))
                            AppsGrid(focus, apps, nUp)
                        }
                    }
                }
                Box(Modifier.enter(g4, dy = 0f, fromAlpha = FROM_ALPHA)) {
                    HintBar(focus, status.hint.isNotEmpty(), up, apps)
                }
            }
        }
    }
}

/** Device name and a network glyph on the left, the four nav buttons on the right. */
@Composable
private fun HomeHeader(focus: FocusScreenState, base: Int, status: HomeStatus) {
    Row(Modifier.fillMaxWidth().bottomRule().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(status.deviceName, color = Swiss.Ink)
        HSpace(16.dp)
        Glyph(networkGlyph(status.network), 30.dp, Swiss.Muted)
        Box(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((k, l) in links.withIndex()) NavChip(focus, base + k, l.first, l.second)
        }
    }
}

private fun networkGlyph(status: String): String = when {
    status.startsWith("Offline") -> "wifi_off"
    status.startsWith("Ethernet") -> "ethernet"
    else -> "wifi"
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
    Column(Modifier.fillMaxWidth().enter({ swap.value }, dx = 12f, dy = 0f, fromAlpha = 0.2f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!status.isHome) {
            Label("A TV update reset Home — run setup again.", color = Swiss.Ink)
        }
        if (item != null) {
            val h = item.hero()
            Label(h.label, color = Swiss.Ink)
            T(h.title, SwissType.Hero, maxLines = 2)
            T(h.meta, SwissType.Meta, maxLines = 2)
            if (h.progress != null) ProgressBar(h.progress!!, Swiss.Ink)
        }
    }
}

@Composable
private fun AppsGrid(focus: FocusScreenState, apps: List<AppEntry>, base: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        for (row in 0 until APP_ROWS) {
            if (row * APP_COLS >= apps.size) break
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                for (col in 0 until APP_COLS) {
                    val k = row * APP_COLS + col
                    val a = apps.getOrNull(k)
                    if (a == null) Box(Modifier.weight(1f)) else AppTile(
                        focus, base + k, a.id.component, a.icon, a.label,
                        Modifier.weight(1f),
                        badge = if (a.pinned) "Pin" else null,
                    )
                }
            }
        }
    }
}

private fun caption(w: PreparedWallpaper): String? {
    val slot: TimeSlot = w.slot
    val name = w.wallpaper?.name ?: return null
    return "${slot.label} ${slot.range} · $name" + if (w.wallpaper?.is4k == true) " · 4K" else ""
}

/** An Up next entry that is just an app (no title, no deep link): OK opens the app itself. */
fun ContinueItem.isAppOnly(): Boolean = source == ContinueSource.APP || (title == null && intentUri == null)

/**
 * Bottom bar: what the keys do on whatever is focused ("OK Resume · Hold OK Remove · Menu Quick
 * settings"). Reads focus in its own scope, so a key press recomposes only this line.
 */
@Composable
private fun HintBar(focus: FocusScreenState, shown: Boolean, up: List<ContinueItem>, apps: List<AppEntry>) {
    // Every focusable's hints are built once per snapshot; a press only picks a list, and the bar
    // recomposes only when the picked list actually changes.
    val all = remember(up, apps) { Array(up.size + apps.size + 1) { hints(it, up, apps) } }
    val list by remember(all, shown) {
        derivedStateOf { if (!shown) emptyList() else all.getOrElse(focus.engine.focused.intValue) { all.last() } }
    }
    Row(
        Modifier.fillMaxWidth().topRule().padding(top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        for (k in list) KeyHintText(k)
    }
}

private val MenuHint = KeyHint("Menu", "Quick settings")
private val HoldMenuHint = KeyHint("Hold Menu", "Launcher settings")

private fun hints(i: Int, up: List<ContinueItem>, apps: List<AppEntry>): List<KeyHint> {
    val nUp = up.size
    return when {
        i in 0 until nUp -> listOf(
            KeyHint("OK", if (up[i].isAppOnly() && !up[i].isAppPlayback()) "Open ${up[i].serviceName}" else "Resume"),
            KeyHint("Hold OK", "Remove"),
            MenuHint,
        )
        i in nUp until nUp + apps.size -> listOf(
            KeyHint("OK", "Open"),
            KeyHint("Hold OK", if (apps[i - nUp].pinned) "Unpin" else "Pin to Home"),
            MenuHint,
        )
        else -> listOf(KeyHint("OK", "Open"), KeyHint("Back", "Continue watching"), MenuHint, HoldMenuHint)

    }
}

/**
 * Something played in an app that never says what (Netflix, Prime Video…): the card is the app.
 * OK resumes it straight away (live session, else the app); there is no title page to show.
 * Matches WatchedStore's "<package>|@app" rows.
 */
fun ContinueItem.isAppPlayback(): Boolean = source == ContinueSource.WATCHED && title == null && key.endsWith("|@app")
