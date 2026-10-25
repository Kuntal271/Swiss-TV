package com.swissdark.launcher.feature.settings

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import com.swissdark.launcher.core.design.Backdrop
import com.swissdark.launcher.core.design.LocalReduceMotion
import com.swissdark.launcher.core.design.Motion
import com.swissdark.launcher.core.design.drawSlide
import com.swissdark.launcher.core.design.rememberAppear
import com.swissdark.launcher.core.design.Fmt
import com.swissdark.launcher.core.design.Footer
import com.swissdark.launcher.core.design.Frame
import com.swissdark.launcher.core.design.Header
import com.swissdark.launcher.core.design.KeyHint
import com.swissdark.launcher.core.design.Label
import com.swissdark.launcher.core.design.RuleRow
import com.swissdark.launcher.core.design.Swiss
import com.swissdark.launcher.core.design.SwissType
import com.swissdark.launcher.core.design.FocusRuleRow
import com.swissdark.launcher.core.design.T
import com.swissdark.launcher.core.design.TDraw
import com.swissdark.launcher.core.design.rememberRowFocus
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.swissdark.launcher.core.design.bottomRule
import com.swissdark.launcher.core.design.rememberMinute
import com.swissdark.launcher.core.design.topRule
import com.swissdark.launcher.core.focus.DpadKey
import com.swissdark.launcher.core.focus.FocusRoot
import com.swissdark.launcher.core.focus.FocusScreenState
import com.swissdark.launcher.core.focus.FocusStyle
import com.swissdark.launcher.core.focus.focusItem
import com.swissdark.launcher.core.focus.focusScroll
import com.swissdark.launcher.core.focus.focusViewport
import com.swissdark.launcher.core.focus.rememberFocusScreen
import com.swissdark.launcher.domain.AppEntry
import com.swissdark.launcher.domain.LauncherSettings
import com.swissdark.launcher.domain.PreparedWallpaper
import com.swissdark.launcher.domain.Providers
import com.swissdark.launcher.domain.TimeSlot
import com.swissdark.launcher.domain.Wallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalTime
import kotlin.math.roundToInt

@Immutable
class SettingsInfo(
    val version: String,
    val isHome: Boolean,
    val usageAccess: Boolean,
    val watchNext: String,
    val tmdb: Boolean,
    val photosPath: String,
    val titleSource: String = "TMDB",                 // "TMDB", "OMDb + TVmaze" or "TVmaze"
)

@Immutable
class SettingsActions(
    val update: ((LauncherSettings) -> LauncherSettings) -> Unit,
    val thumbnail: (Wallpaper, Int, Int) -> Bitmap?,
    val rescanWallpapers: () -> Unit,
    val back: () -> Unit,
)

private val sections = listOf("Home", "Wallpaper", "Up next", "Apps", "Remote", "About")

@Composable
fun LauncherSettingsScreen(
    settings: LauncherSettings,
    catalog: List<Wallpaper>,
    allApps: List<AppEntry>,
    info: SettingsInfo,
    deviceName: String,
    wallpaper: PreparedWallpaper,
    actions: SettingsActions,
    active: Boolean,
    initialSection: Int = 1,
) {
    var section by remember { mutableIntStateOf(initialSection) }
    var slot by remember { mutableStateOf(TimeSlot.at(LocalTime.now())) }
    val now by rememberMinute()
    val scope = rememberCoroutineScope()

    val content: SectionContent = when (section) {
        0 -> homeSection(settings, actions)
        1 -> wallpaperSection(settings, catalog, slot, { slot = it }, actions, info)
        2 -> servicesSection(settings, actions)
        3 -> appsSection(settings, allApps, actions)
        4 -> remoteSection(info)
        else -> aboutSection(info)
    }
    val focus = rememberFocusScreen(sections.size + content.count, initial = initialSection, active = active)
    // Moving through the section list does not rebuild the right side on every press: the
    // section under focus is shown once focus rests on it (SECTION_DWELL_MS), on OK, or as soon
    // as focus moves right into the content.
    val nav = remember { SectionNav() }
    fun show(s: Int) {
        nav.cancel()
        if (s != section) {
            section = s
            focus.scrollY.floatValue = 0f
        }
    }
    focus.onFocusChanged = { i ->
        val wasPending = nav.pending != null
        nav.cancel()
        if (i < sections.size) {
            nav.row = i
            if (i != section) nav.pending = scope.launch { delay(SECTION_DWELL_MS); nav.pending = null; show(i) }
        } else if (wasPending && nav.row != section) {
            // RIGHT before the dwell ran out: open that row's section and enter it at the top.
            val target = nav.row
            show(target)
            focus.focus(if (contentCount(target, catalog, allApps) > 0) sections.size else target)
        }
    }
    focus.onEdge = { i, dir ->
        // RIGHT from a row whose section is not shown yet and whose shown section has nothing to enter.
        if (dir == DpadKey.RIGHT && i < sections.size && i != section && contentCount(i, catalog, allApps) > 0) {
            show(i)
            focus.focus(sections.size)
            true
        } else false
    }
    focus.onClick = { i -> if (i >= sections.size) content.click(i - sections.size) else show(i) }
    focus.onOtherKey = { e ->
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) actions.back()
            true
        } else false
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(wallpaper, totalDim = 0.85f)
        FocusRoot(focus, Swiss.Focus) {
            Frame {
                Header(deviceName.uppercase(), now.format(Fmt.clock), "Launcher v${info.version}", "Settings")
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Swiss.GUTTER.dp)) {
                    Column(Modifier.width(Swiss.span(3))) {
                        for ((i, s) in sections.withIndex()) {
                            val sel = i == section
                            FocusRuleRow(focus, i, bottomRule = i == sections.lastIndex, selected = sel, vertical = 18.dp) { f ->
                                TDraw(s, SwissType.Row34, if (sel) AmberColor else f.ink)
                            }
                        }
                    }
                    Box(Modifier.width(Swiss.span(1)))
                    // A new section fades in with a 16 px slide (draw phase: rows keep their layout).
                    val sectionIn = rememberAppear(section, Motion.MEDIUM, from = 0.2f)
                    Box(Modifier.weight(1f).fillMaxHeight().clipToBounds().focusViewport(focus)) {
                        Column(Modifier.fillMaxWidth().drawSlide({ sectionIn.value }, dx = Motion.ITEM_SLIDE).focusScroll(focus), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                            T(sections[section], SwissType.Hero)
                            content.body(focus, sections.size)
                        }
                    }
                }
                Footer("Settings and viewing habits stay on the TV", footerKeys)
            }
        }
    }
}

private const val SECTION_DWELL_MS = 350L

private val footerKeys = listOf(KeyHint("Back", "Home"))

private val AmberColor = ColorProducer { Swiss.Focus }

/** The section row under focus and its pending switch (plain fields: touched from key handling only). */
private class SectionNav {
    var row = -1
    var pending: Job? = null
    fun cancel() {
        pending?.cancel()
        pending = null
    }
}

/** Focus items in section [s] (must match the counts the section builders below report). */
private fun contentCount(s: Int, catalog: List<Wallpaper>, apps: List<AppEntry>): Int = when (s) {
    0 -> 2
    1 -> 4 + minOf(catalog.size, 11) + 1 + 2
    2 -> serviceKeys.size + 1
    3 -> apps.size + 1
    else -> 0
}

private class SectionContent(
    val count: Int,
    val click: (Int) -> Unit,
    val body: @Composable (FocusScreenState, Int) -> Unit,
)

// ---------- rows ----------

@Composable
private fun ValueRow(focus: FocusScreenState, index: Int, label: String, value: String, last: Boolean = false) {
    FocusRuleRow(focus, index, bottomRule = last, vertical = 18.dp, scrolling = true) { f ->
        TDraw(label, SwissType.Row30, f.ink, Modifier.weight(1f), maxLines = 1)
        T(value, SwissType.Value26, color = Swiss.Focus, maxLines = 1)
    }
}

@Composable
private fun InfoRow(label: String, value: String, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().topRule().let { if (last) it.bottomRule() else it }.padding(vertical = 18.dp)) {
        T(label, SwissType.Row30, Modifier.weight(1f), maxLines = 1)
        T(value, SwissType.Value26, color = Swiss.Muted, maxLines = 1)
    }
}

private fun onOff(b: Boolean) = if (b) "On" else "Off"

// ---------- sections ----------

private fun homeSection(s: LauncherSettings, a: SettingsActions) = SectionContent(
    count = 2,
    click = { i ->
        when (i) {
            0 -> a.update { it.copy(showUpNext = !it.showUpNext) }
            1 -> a.update { it.copy(showLatencyOverlay = !it.showLatencyOverlay) }
        }
    },
) { f, base ->
    Column {
        ValueRow(f, base, "Show Up next", onOff(s.showUpNext))
        ValueRow(f, base + 1, "Key latency overlay", onOff(s.showLatencyOverlay))
        InfoRow("Apps on Home", "6, by habit · pinned first")
        InfoRow("Clock", "24-hour", last = true)
    }
}

private fun wallpaperSection(
    s: LauncherSettings,
    catalog: List<Wallpaper>,
    slot: TimeSlot,
    setSlot: (TimeSlot) -> Unit,
    a: SettingsActions,
    info: SettingsInfo,
): SectionContent {
    val slots = TimeSlot.entries
    val picks = catalog.take(11)
    // 0–3 slots, then picks, then "Your photos", then darken − and +
    val nPick = picks.size + 1
    return SectionContent(
        count = 4 + nPick + 2,
        click = { i ->
            when {
                i < 4 -> setSlot(slots[i])
                i < 4 + picks.size -> a.update { it.copy(wallpaperBySlot = it.wallpaperBySlot + (slot.name to picks[i - 4].id)) }
                i == 4 + picks.size -> a.rescanWallpapers()
                else -> {
                    val d = if (i == 4 + nPick) -0.05f else 0.05f
                    a.update {
                        val cur = it.dimBySlot[slot.name] ?: 0.45f
                        it.copy(dimBySlot = it.dimBySlot + (slot.name to (((cur + d) * 20).roundToInt() / 20f).coerceIn(0f, 0.9f)))
                    }
                }
            }
        },
    ) { f, base ->
        val nowSlot = TimeSlot.at(LocalTime.now())
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Label("Changes with the time of day")
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                for ((k, sl) in slots.withIndex()) {
                    val wp = catalog.firstOrNull { it.id == s.wallpaperBySlot[sl.name] } ?: catalog.firstOrNull()
                    Thumb(f, base + k, wp, 150, a, Modifier.weight(1f), selected = sl == slot) {
                        Row {
                            T(sl.label, SwissType.Card)
                            if (sl == nowSlot) T(" · now", SwissType.Card, color = Swiss.Focus)
                        }
                        T("${sl.range} · ${wp?.name ?: "none"}", SwissType.Meta18, maxLines = 1)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Label("Pick for ${slot.label}")
            val all = picks.size + 1
            for (r in 0 until (all + 5) / 6) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                for (c in 0 until 6) {
                    val k = r * 6 + c
                    when {
                        k < picks.size -> {
                            val wp = picks[k]
                            val chosen = s.wallpaperBySlot[slot.name] == wp.id
                            Thumb(f, base + 4 + k, wp, 96, a, Modifier.weight(1f), selected = chosen) {
                                T(wp.name, SwissType.Tile22, color = if (chosen) Swiss.Focus else Swiss.Ink, maxLines = 1)
                            }
                        }
                        k == picks.size -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier.fillMaxWidth().height(96.dp).focusItem(f, base + 4 + k, scrolling = true)
                                    .background(Swiss.Well).border(1.dp, Swiss.Dashed),
                                contentAlignment = Alignment.Center,
                            ) { T("+", SwissType.Row44.copy(fontSize = SwissType.Row44.fontSize), color = Swiss.Muted) }
                            T("Your photos", SwissType.Tile22)
                        }
                        else -> Box(Modifier.weight(1f))
                    }
                }
            }
            T("Add your own: adb push photos to ${info.photosPath}, then pick +.", SwissType.Meta18)
        }
        val dim = s.dimBySlot[slot.name] ?: 0.45f
        Row(Modifier.fillMaxWidth().topRule().bottomRule(), verticalAlignment = Alignment.CenterVertically) {
            T("Darken wallpaper", SwissType.Row30, Modifier.weight(1f).padding(vertical = 18.dp))
            StepButton(f, base + 4 + nPick, "−")
            T("${(dim * 100).roundToInt()}%", SwissType.Value26, Modifier.padding(horizontal = 24.dp), Swiss.Focus)
            StepButton(f, base + 5 + nPick, "+")
        }
    }
}

@Composable
private fun StepButton(f: FocusScreenState, index: Int, text: String) {
    val rf = rememberRowFocus(f, index)
    TDraw(
        text, SwissType.Row30, rf.ink,
        Modifier.focusItem(f, index, FocusStyle.SELF, scrolling = true)
            .drawBehind {
                val w = 2.dp.toPx()
                drawRect(if (rf.focused.value) Swiss.Focus else Swiss.Rule, Offset(w / 2f, w / 2f), Size(size.width - w, size.height - w), style = Stroke(w))
            }
            .padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun Thumb(
    f: FocusScreenState,
    index: Int,
    wp: Wallpaper?,
    h: Int,
    a: SettingsActions,
    modifier: Modifier,
    selected: Boolean,
    caption: @Composable () -> Unit,
) {
    var bmp by remember(wp?.id, h) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(wp?.id, h) {
        if (wp != null) bmp = withContext(Dispatchers.IO) { a.thumbnail(wp, h * 16 / 9, h) }
    }
    // Selection: the amber rule under the picked thumbnail draws out from the centre.
    val reduce = LocalReduceMotion.current
    val sel = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected) {
        val t = if (selected) 1f else 0f
        if (sel.value != t) { if (reduce) sel.snapTo(t) else sel.animateTo(t, Motion.standard(Motion.MEDIUM)) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(h.dp).focusItem(f, index, scrolling = true).background(Swiss.Placeholder)
                .drawWithContent {
                    drawContent()
                    val s = sel.value
                    if (s > 0f) {
                        val w = 4.dp.toPx(); val rw = size.width * s
                        drawRect(Swiss.Focus, Offset((size.width - rw) / 2f, size.height - w), Size(rw, w))
                    }
                },
        ) {
            // Thumbnails fade in as they decode.
            bmp?.let {
                val fade = rememberAppear(it, Motion.MEDIUM)
                Image(remember(it) { it.asImageBitmap() }, null, Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }, contentScale = ContentScale.Crop)
            }
        }
        caption()
    }
}

private val serviceKeys = listOf("netflix", "prime", "jiohotstar", "sonyliv", "zee5", "appletv", "crunchyroll", "youtube")

private fun servicesSection(s: LauncherSettings, a: SettingsActions) = SectionContent(
    count = serviceKeys.size + 2,
    click = { i ->
        if (i < serviceKeys.size) {
            val k = serviceKeys[i]
            a.update { it.copy(myServices = if (k in it.myServices) it.myServices - k else it.myServices + k) }
        } else if (i == serviceKeys.size) a.update { it.copy(clearContinueOnOff = !it.clearContinueOnOff) }
        else a.update { it.copy(removedUpNext = emptyList()) }
    },
) { f, base ->
    Column {
        Label("Services you pay for", Modifier.padding(bottom = 12.dp))
        for ((k, key) in serviceKeys.withIndex()) {
            val p = Providers.byKey(key) ?: continue
            ValueRow(f, base + k, p.name + if (key == "youtube") " Premium" else "", if (key in s.myServices) "In my plan" else "—")
        }
        ValueRow(f, base + serviceKeys.size, "Clear when TV turns off", onOff(s.clearContinueOnOff))
        ValueRow(f, base + serviceKeys.size + 1, "Bring back removed Up next items", "${s.removedUpNext.size} removed", last = true)
    }
}

private fun appsSection(s: LauncherSettings, apps: List<AppEntry>, a: SettingsActions) = SectionContent(
    count = apps.size + 1,
    click = { i ->
        if (i < apps.size) {
            val pkg = apps[i].id.packageName
            a.update { it.copy(hidden = if (pkg in it.hidden) it.hidden - pkg else it.hidden + pkg) }
        } else a.update { it.copy(customOrder = emptyList()) }
    },
) { f, base ->
    Column {
        Label("OK shows or hides an app. Hidden apps stay installed.", Modifier.padding(bottom = 12.dp))
        ValueRow(f, base + apps.size, "All apps order", if (s.customOrder.isEmpty()) "A to Z" else "Yours · OK resets")
        for ((k, app) in apps.withIndex()) {
            ValueRow(f, base + k, app.label, if (app.id.packageName in s.hidden) "Hidden" else "Shown", last = k == apps.lastIndex)
        }
    }
}

private fun remoteSection(info: SettingsInfo) = SectionContent(0, {}) { _, _ ->
    Column {
        InfoRow("Home button", if (info.isHome) "Swiss Home" else "Not set to Swiss Home — run setup.sh")
        InfoRow("Google TV Home", "Off · UNDO=1 ./setup/setup.sh restores it")
        InfoRow("Mic", "Google Assistant · Search here: Mic")
        InfoRow("Input", "Inputs panel")
        InfoRow("Settings", "Quick settings panel")
        InfoRow("Back on Home", "Does nothing", last = true)
    }
}

private fun aboutSection(info: SettingsInfo) = SectionContent(0, {}) { _, _ ->
    Column {
        InfoRow("Version", info.version)
        InfoRow("Home app", if (info.isHome) "This launcher" else "Not set — run setup.sh")
        InfoRow("Usage access (habits)", if (info.usageAccess) "Granted" else "Missing — run setup.sh")
        InfoRow("Watch Next", info.watchNext)
        InfoRow(
            "Titles",
            if (info.titleSource == "TMDB") "TMDB" else info.titleSource + " · TMDB key " + if (info.tmdb) "refused or unreachable" else "missing",
            last = true,
        )
        T(
            (if (info.titleSource == "TMDB") "Titles and artwork: TMDB. Where to watch: JustWatch. "
            else "Titles: ${info.titleSource} (TMDB is used when its key works). Where to watch: TVmaze channels, approximate. ") +
                "Icons: Tabler Icons (MIT). Type: Archivo (OFL). " +
                "This product uses the TMDB API but is not endorsed or certified by TMDB.",
            SwissType.Body22, Modifier.padding(top = 24.dp),
        )
    }
}
