package com.swissdark.launcher.feature.panels

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swissdark.launcher.core.design.Backdrop
import com.swissdark.launcher.core.design.Fmt
import com.swissdark.launcher.core.design.Label
import com.swissdark.launcher.core.design.Swiss
import com.swissdark.launcher.core.design.SwissType
import com.swissdark.launcher.core.design.T
import com.swissdark.launcher.core.design.bottomRule
import com.swissdark.launcher.core.design.leftRule
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
import com.swissdark.launcher.domain.PictureMode
import com.swissdark.launcher.domain.PreparedWallpaper
import com.swissdark.launcher.domain.SoundMode
import com.swissdark.launcher.domain.TvInput

private fun back(onBack: () -> Unit): (KeyEvent) -> Boolean = { e ->
    if (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_TV_INPUT || e.keyCode == KeyEvent.KEYCODE_SETTINGS) {
        if (e.action == KeyEvent.ACTION_UP) onBack()
        true
    } else false
}

/** Wallpaper and a dim clock on the left, the panel on the right (SwInputs / SwQuickSettings). */
@Composable
private fun PanelFrame(wallpaper: PreparedWallpaper, width: Dp, content: @Composable () -> Unit) {
    val now by rememberMinute()
    Row(Modifier.fillMaxSize().background(Swiss.Ground)) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Backdrop(wallpaper, totalDim = 0.6f)
            T(now.format(Fmt.clock), SwissType.ClockPanel, Modifier.align(Alignment.BottomStart).padding(start = 120.dp, bottom = 120.dp), Swiss.DimClock)
        }
        Box(
            Modifier.width(width).fillMaxHeight().background(Swiss.Panel).leftRule()
                .padding(72.dp),
        ) { content() }
    }
}

/** Panel header: label on the left, amber status on the right, a rule under both. */
@Composable
private fun PanelHeader(title: String, status: String, rule: Boolean = true) {
    Row(Modifier.fillMaxWidth().then(if (rule) Modifier.bottomRule() else Modifier).padding(bottom = 18.dp)) {
        Label(title, Modifier.weight(1f).alignByBaseline(), Swiss.Ink)
        Label(status, Modifier.alignByBaseline(), Swiss.Focus)
    }
}

/**
 * One full-width row between rules, shared by both panels. It is a single focus item: the
 * engine only ever sees a vertical stack of rows, so UP/DOWN walk the rows in visual order.
 * Focus = a 4 px amber rule on top and amber text (the row restyles itself, as in the design).
 */
@Composable
private fun PanelRow(
    focus: FocusScreenState,
    index: Int,
    last: Boolean,
    scrolling: Boolean = false,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    val f by remember(focus, index) { focus.isFocused(index) }
    var m = Modifier.fillMaxWidth().focusItem(focus, index, FocusStyle.SELF, scrolling)
    m = if (f) m.topRule(4.dp, Swiss.Focus) else m.topRule()
    if (last) m = m.bottomRule()
    Row(m) { content(f) }
}

// ---------------------------------------------------------------- Inputs

@Immutable
class InputsActions(val switchTo: (TvInput) -> Unit, val back: () -> Unit)

@Composable
fun InputsPanel(inputs: List<TvInput>, current: String?, wallpaper: PreparedWallpaper, actions: InputsActions, active: Boolean) {
    val focus = rememberFocusScreen(inputs.size, initial = inputs.indexOfFirst { it.label == current }.coerceAtLeast(0), active = active)
    focus.onClick = { i -> inputs.getOrNull(i)?.let(actions.switchTo) }
    focus.onOtherKey = back(actions.back)
    PanelFrame(wallpaper, 760.dp) {
        FocusRoot(focus, Swiss.Focus) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(36.dp)) {
                PanelHeader("Input", "Now · " + (current ?: "Home"))
                T("Watch\nfrom", SwissType.Display110)
                // The list takes the free height and scrolls with focus when the TV reports more
                // inputs than fit (the design shows four; this TV reports seven).
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().focusViewport(focus)) {
                    Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).focusScroll(focus)) {
                        if (inputs.isEmpty()) T("No inputs reported by this TV.", SwissType.Body22)
                        for ((i, inp) in inputs.withIndex()) {
                            PanelRow(focus, i, last = i == inputs.lastIndex, scrolling = true) { f ->
                                T(inp.label, SwissType.Row44, Modifier.weight(1f).alignByBaseline().padding(vertical = 22.dp), if (f) Swiss.Focus else Swiss.Ink, maxLines = 1)
                                T(inp.detail, SwissType.Meta22, Modifier.alignByBaseline(), if (f) Swiss.Focus else Swiss.Muted, maxLines = 1)
                            }
                        }
                    }
                }
                T("Names come from HDMI-CEC. Switching runs through TCL’s own TV app, which stays installed.", SwissType.Body22)
            }
        }
    }
}

// ---------------------------------------------------------------- Quick settings

@Immutable
class QuickActions(
    val picture: (PictureMode) -> Unit,
    val sound: (SoundMode) -> Unit,
    val sleep: (Int) -> Unit,
    val pictureOff: () -> Unit,
    val allSettings: () -> Unit,
    val launcherSettings: () -> Unit,
    val back: () -> Unit,
    /** Opens network settings. Null = the panel opens Android TV's network page itself. */
    val network: (() -> Unit)? = null,
)

private val pictures = listOf(PictureMode.MOVIE to "Movie", PictureMode.STANDARD to "Standard", PictureMode.VIVID to "Vivid", PictureMode.GAME to "Game")
private val sounds = listOf(SoundMode.STANDARD to "Standard", SoundMode.DIALOGUE to "Dialogue", SoundMode.NIGHT to "Night")
private val sleeps = listOf(0 to "Off", 30 to "30", 60 to "60", 90 to "90 min")

// Rows, top to bottom. One focus item each; LEFT/RIGHT move a cursor inside the row.
private const val ROW_PICTURE = 0
private const val ROW_SOUND = 1
private const val ROW_SLEEP = 2
private const val ROW_NETWORK = 3
private const val ROW_SCREEN = 4
private const val ROW_LINKS = 5
private const val ROWS = 6
private val rowSizes = intArrayOf(pictures.size, sounds.size, sleeps.size, 1, 1, 2)

/** The design's 200 px label column; values start at the same x on every row. */
private val LabelCol = 200.dp

@Composable
fun QuickSettingsPanel(
    picture: PictureMode?,
    sound: SoundMode?,
    sleepMinutes: Int,
    networkName: String,
    status: String,
    wallpaper: PreparedWallpaper,
    actions: QuickActions,
    active: Boolean,
) {
    val context = LocalContext.current
    val pictureSel = pictures.indexOfFirst { it.first == (picture ?: PictureMode.STANDARD) }.coerceAtLeast(0)
    val soundSel = sounds.indexOfFirst { it.first == (sound ?: SoundMode.STANDARD) }.coerceAtLeast(0)
    val sleepSel = sleeps.indexOfFirst { it.first == sleepMinutes }.coerceAtLeast(0)
    fun selectedOf(row: Int) = when (row) {
        ROW_PICTURE -> pictureSel
        ROW_SOUND -> soundSel
        ROW_SLEEP -> sleepSel
        else -> 0
    }

    val focus = rememberFocusScreen(ROWS, initial = ROW_PICTURE, active = active)
    // Option under the D-pad in the focused row. Entering a row puts it on the current value.
    val cursor = remember { mutableIntStateOf(pictureSel) }
    focus.onFocusChanged = { row -> cursor.intValue = selectedOf(row) }
    focus.onEdge = { row, dir ->
        val c = cursor.intValue
        val n = rowSizes.getOrElse(row) { 1 }
        when {
            dir == DpadKey.LEFT && c > 0 -> { cursor.intValue = c - 1; true }
            dir == DpadKey.RIGHT && c < n - 1 -> { cursor.intValue = c + 1; true }
            else -> false
        }
    }
    focus.onClick = { row ->
        val c = cursor.intValue
        when (row) {
            ROW_PICTURE -> actions.picture(pictures[c.coerceIn(0, pictures.lastIndex)].first)
            ROW_SOUND -> actions.sound(sounds[c.coerceIn(0, sounds.lastIndex)].first)
            ROW_SLEEP -> actions.sleep(sleeps[c.coerceIn(0, sleeps.lastIndex)].first)
            ROW_NETWORK -> actions.network?.invoke() ?: openNetworkSettings(context)
            ROW_SCREEN -> actions.pictureOff()
            ROW_LINKS -> if (c == 0) actions.allSettings() else actions.launcherSettings()
        }
    }
    focus.onOtherKey = back(actions.back)

    PanelFrame(wallpaper, 960.dp) {
        FocusRoot(focus, Swiss.Focus) {
            Column(Modifier.fillMaxSize()) {
                // No rule under the header: the first row's rule is the header rule. A second rule
                // 36 px above it read as an empty bordered row above PICTURE (bug 1).
                PanelHeader("Quick settings", status, rule = false)
                Column(Modifier.fillMaxWidth()) {
                    OptionRow(focus, ROW_PICTURE, "Picture", pictures.map { it.second }, pictureSel, cursor)
                    OptionRow(focus, ROW_SOUND, "Sound", sounds.map { it.second }, soundSel, cursor)
                    OptionRow(focus, ROW_SLEEP, "Sleep", sleeps.map { it.second }, sleepSel, cursor)
                    ValueRow(focus, ROW_NETWORK, "Network", networkName, last = false)
                    ValueRow(focus, ROW_SCREEN, "Screen", "Picture off, sound on", last = true)
                }
                Box(Modifier.weight(1f))
                LinksRow(focus, ROW_LINKS, listOf("All TV settings →", "Launcher settings →"), cursor)
            }
        }
    }
}

@Composable
private fun RowScope.RowLabel(text: String, focused: Boolean) {
    Label(text, Modifier.width(LabelCol).alignByBaseline().padding(vertical = 24.dp), if (focused) Swiss.Focus else Swiss.Muted)
}

/** Label + a row of options. Current value: underlined; in the focused row the cursor is amber. */
@Composable
private fun OptionRow(focus: FocusScreenState, index: Int, label: String, options: List<String>, selected: Int, cursor: IntState) {
    PanelRow(focus, index, last = false) { f ->
        RowLabel(label, f)
        Row(
            Modifier.weight(1f).alignByBaseline().padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            val c = if (f) cursor.intValue else -1
            for ((k, o) in options.withIndex()) {
                val (color, rule) = when {
                    k == c -> Swiss.Focus to Swiss.Focus
                    k == selected -> Swiss.Ink to Swiss.Ink
                    else -> Swiss.Muted to Color.Transparent
                }
                T(o, SwissType.Row34, Modifier.alignByBaseline().bottomRule(4.dp, rule).padding(top = 4.dp, bottom = 8.dp), color, maxLines = 1)
            }
        }
    }
}

/** Label + one value that opens a page (Network, Screen). */
@Composable
private fun ValueRow(focus: FocusScreenState, index: Int, label: String, value: String, last: Boolean) {
    PanelRow(focus, index, last) { f ->
        RowLabel(label, f)
        T(value, SwissType.Row34, Modifier.weight(1f).alignByBaseline().padding(vertical = 24.dp), if (f) Swiss.Focus else Swiss.Ink, maxLines = 1)
    }
}

/** Footer links: one focus item, LEFT/RIGHT between links; the focused link is underlined. */
@Composable
private fun LinksRow(focus: FocusScreenState, index: Int, links: List<String>, cursor: IntState) {
    val f by remember(focus, index) { focus.isFocused(index) }
    Row(Modifier.fillMaxWidth().focusItem(focus, index, FocusStyle.SELF), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        val c = if (f) cursor.intValue else -1
        for ((k, text) in links.withIndex()) {
            T(
                text, SwissType.Button,
                Modifier.bottomRule(4.dp, if (k == c) Swiss.Focus else Color.Transparent).padding(vertical = 8.dp),
                if (k == 0 || k == c) Swiss.Focus else Swiss.Ink,
                maxLines = 1,
            )
        }
    }
}

private fun openNetworkSettings(context: Context) {
    val pm = context.packageManager
    val intent = listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)
        .map { Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        .firstOrNull { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null } ?: return
    runCatching { context.startActivity(intent) }
}
