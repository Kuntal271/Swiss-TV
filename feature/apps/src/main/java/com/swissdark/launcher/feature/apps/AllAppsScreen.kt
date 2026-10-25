package com.swissdark.launcher.feature.apps

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import com.swissdark.launcher.core.design.Backdrop
import com.swissdark.launcher.core.design.Motion
import com.swissdark.launcher.core.design.drawPop
import com.swissdark.launcher.core.design.enter
import com.swissdark.launcher.core.design.rememberAppear
import com.swissdark.launcher.core.design.Fmt
import com.swissdark.launcher.core.design.Frame
import com.swissdark.launcher.core.design.Header
import com.swissdark.launcher.core.design.AppTile
import com.swissdark.launcher.core.design.Label
import com.swissdark.launcher.core.design.Swiss
import com.swissdark.launcher.core.design.SwissType
import com.swissdark.launcher.core.design.T
import com.swissdark.launcher.core.design.TabText
import com.swissdark.launcher.core.design.rememberMinute
import com.swissdark.launcher.core.design.topRule
import com.swissdark.launcher.core.focus.DpadKey
import com.swissdark.launcher.core.focus.FocusRoot
import com.swissdark.launcher.core.design.edgeFade
import com.swissdark.launcher.core.focus.focusScroll
import com.swissdark.launcher.core.focus.focusViewport
import com.swissdark.launcher.core.focus.rememberFocusScreen
import com.swissdark.launcher.domain.AppEntry
import com.swissdark.launcher.domain.PreparedWallpaper

@Immutable
class AllAppsActions(
    val launch: (AppEntry) -> Unit,
    val setPinned: (AppEntry, Boolean) -> Unit,
    val hide: (AppEntry) -> Unit,
    val move: (AppEntry, Int) -> Unit,
    val back: () -> Unit,
)

private const val COLS = 5

/** A to Z (or your order once you move something). Hidden apps and disabled bloat never show. */
@Composable
fun AllAppsScreen(
    apps: List<AppEntry>,
    hiddenCount: Int,
    customOrder: Boolean,
    deviceName: String,
    wallpaper: PreparedWallpaper,
    actions: AllAppsActions,
    active: Boolean,
) {
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    var moving by remember { mutableStateOf<AppEntry?>(null) }
    // Where the app being moved started, so Back can put it back.
    val moveFrom = remember { intArrayOf(-1) }
    val grid = rememberFocusScreen(apps.size, active = active && menuFor == null && moving == null)
    // Scroll by whole rows: no sliver of a row (cut art, bright label) at the top edge.
    grid.snapRows = true
    val now by rememberMinute()

    // Focus follows an app by id when the list reorders (move, pin).
    val lastId = remember { arrayOfNulls<String>(1) }
    grid.onFocusChanged = { lastId[0] = apps.getOrNull(it)?.id?.component }
    LaunchedEffect(apps) {
        val i = apps.indexOfFirst { it.id.component == lastId[0] }
        // While moving, the lifted tile glides to its new slot with the ring.
        if (i >= 0 && i != grid.focused) grid.focus(i, animate = moving != null)
    }
    // Move mode lifts the tile: it grows to 1.08 (ring with it) and gains a yellow outline.
    LaunchedEffect(moving != null) {
        val target = if (moving != null) 1.08f else Motion.FOCUS_SCALE
        if (grid.focusScale != target) {
            grid.focusScale = target
            grid.focus(grid.focused, animate = true)
        }
    }
    // The grid fades in just after the page itself slides in.
    val gridIn = rememberAppear(Unit, Motion.MEDIUM, delay = Motion.STAGGER)
    grid.onClick = { i -> apps.getOrNull(i)?.let(actions.launch) }
    grid.onLongClick = { i -> apps.getOrNull(i)?.let { lastId[0] = it.id.component; menuFor = it } }
    grid.onOtherKey = { e ->
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) actions.back()
            true
        } else false
    }

    // Hold-OK menu: Pin · Hide · Move · Cancel
    val menu = rememberFocusScreen(4, active = active && menuFor != null)
    menu.onClick = { i ->
        val a = menuFor
        menuFor = null
        if (a != null) when (i) {
            0 -> actions.setPinned(a, !a.pinned)
            1 -> actions.hide(a)
            2 -> { moveFrom[0] = apps.indexOfFirst { it.id == a.id }; moving = a }
        }
    }
    menu.onOtherKey = { e ->
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) menuFor = null
            true
        } else false
    }

    // Move mode: every arrow press moves the app; OK puts it down, Back puts it back where it was.
    val mover = rememberFocusScreen(0, active = active && moving != null)
    mover.onEdge = { _, d ->
        moving?.let { a ->
            actions.move(a, when (d) { DpadKey.LEFT -> -1; DpadKey.RIGHT -> 1; DpadKey.UP -> -COLS; DpadKey.DOWN -> COLS })
        }
        true
    }
    mover.onClick = { moving = null }
    mover.onOtherKey = { e ->
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) {
                val a = moving
                val now = if (a == null) -1 else apps.indexOfFirst { it.id == a.id }
                if (a != null && moveFrom[0] >= 0 && now >= 0 && now != moveFrom[0]) actions.move(a, moveFrom[0] - now)
                moving = null
            }
            true
        } else false
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(wallpaper, totalDim = 0.8f)
        FocusRoot(grid, Swiss.Focus) {
            Frame {
                Header(deviceName.uppercase(), now.format(Fmt.clock), (if (customOrder) "Your order" else "A to Z") + " · $hiddenCount hidden", "All apps")
                T("All apps · ${apps.size}", SwissType.Row44, maxLines = 1)
                Box(
                    Modifier.fillMaxWidth().weight(1f).enter({ gridIn.value }, dy = 0f).clipToBounds().focusViewport(grid)
                        .edgeFade(
                            // Fade the bottom edge only while there are rows beyond it. The top needs
                            // none: the scroll snaps to whole rows, so a full row sits at the top.
                            topAmount = { 0f },
                            bottomAmount = { (grid.contentBottom - grid.scrollY.floatValue - grid.viewportBottom) / 48f },
                        ),
                ) {
                    // 12 px of air above the first row so its ring and scale are never clipped.
                    Column(Modifier.fillMaxWidth().focusScroll(grid).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                        for (r in 0 until (apps.size + COLS - 1) / COLS) {
                            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                                for (c in 0 until COLS) {
                                    val i = r * COLS + c
                                    val a = apps.getOrNull(i)
                                    if (a == null) Box(Modifier.weight(1f))
                                    else AppTile(
                                        grid, i, a.id.component, a.icon, a.label, Modifier.weight(1f),
                                        scrolling = true,
                                        badge = when {
                                            moving?.id == a.id -> "Moving"
                                            a.pinned -> "Pinned"
                                            else -> null
                                        },
                                        outline = moving?.id == a.id,
                                    )
                                }
                            }
                        }
                    }
                }
                // The Hold-OK menu sits over the footer at the footer's height: the grid never
                // shrinks, so the focused tile and its name stay in view.
                Box(Modifier.fillMaxWidth()) {
                    AppsFooter(grid, apps, moving)
                    val m = menuFor
                    if (m != null) {
                        // Hold-OK menu pops in: scale 0.96 → 1 with a fade, 160 ms.
                        val pop = rememberAppear(m, Motion.SHORT)
                        FocusRoot(menu, Swiss.Focus, Modifier.matchParentSize()) {
                            Row(
                                Modifier.fillMaxSize().drawPop({ pop.value }).topRule(4.dp, Swiss.Ink).background(Swiss.Panel).padding(start = 24.dp, end = 12.dp, top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp),
                            ) {
                                Label(m.label, color = Swiss.Ink, modifier = Modifier.weight(1f))
                                MenuItem { TabText(menu, 0, if (m.pinned) "Unpin from Home" else "Pin to Home", false, SwissType.Row28, Swiss.Ink, marker = false) }
                                MenuItem { TabText(menu, 1, "Hide", false, SwissType.Row28, Swiss.Ink, marker = false) }
                                MenuItem { TabText(menu, 2, "Move", false, SwissType.Row28, Swiss.Ink, marker = false) }
                                MenuItem { TabText(menu, 3, "Cancel", false, SwissType.Row28, Swiss.Ink, marker = false) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Reads focus on its own, so a press recomposes only this line, not the grid. */
@Composable
private fun AppsFooter(grid: com.swissdark.launcher.core.focus.FocusScreenState, apps: List<AppEntry>, moving: AppEntry?) {
    val focusedApp = apps.getOrNull(grid.engine.focused.intValue)
    com.swissdark.launcher.core.design.Footer(
        when {
            moving != null -> "Moving ${moving.label}"
            focusedApp?.pinned == true -> "${focusedApp.label} · pinned to Home"
            focusedApp != null -> focusedApp.label
            else -> "No apps"
        },
        if (moving != null) moveKeys else footerKeys,
    )
}

/** A menu button that may be a little taller than the footer bar: it overflows evenly, never clips. */
@Composable
private fun MenuItem(content: @Composable () -> Unit) {
    Box(Modifier.wrapContentHeight(unbounded = true)) { content() }
}

private val moveKeys = listOf(
    com.swissdark.launcher.core.design.KeyHint("Arrows", "Move"),
    com.swissdark.launcher.core.design.KeyHint("OK", "Done"),
    com.swissdark.launcher.core.design.KeyHint("Back", "Cancel"),
)

// Built once: a press recomposes the footer line, and must not allocate the key hints again.
private val footerKeys = listOf(
    com.swissdark.launcher.core.design.KeyHint("OK", "Open"),
    com.swissdark.launcher.core.design.KeyHint("Hold OK", "Pin · Hide · Move"),
    com.swissdark.launcher.core.design.KeyHint("Back", "Home"),
)
