package com.swissdark.launcher.feature.panels

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swissdark.launcher.core.design.Backdrop
import com.swissdark.launcher.core.design.Fmt
import com.swissdark.launcher.core.design.Label
import com.swissdark.launcher.core.design.RuleRow
import com.swissdark.launcher.core.design.Swiss
import com.swissdark.launcher.core.design.SwissType
import com.swissdark.launcher.core.design.T
import com.swissdark.launcher.core.design.TabText
import com.swissdark.launcher.core.design.bottomRule
import com.swissdark.launcher.core.design.leftRule
import com.swissdark.launcher.core.design.rememberMinute
import com.swissdark.launcher.core.design.topRule
import com.swissdark.launcher.core.focus.FocusRoot
import com.swissdark.launcher.core.focus.FocusScreenState
import com.swissdark.launcher.core.focus.FocusStyle
import com.swissdark.launcher.core.focus.focusItem
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

/** Wallpaper and a dim clock on the left, the panel on the right. */
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
                Row(Modifier.fillMaxWidth().bottomRule().padding(bottom = 18.dp)) {
                    Label("Input", Modifier.weight(1f), Swiss.Ink)
                    Label("Now · " + (current ?: "Home"), color = Swiss.Focus)
                }
                T("Watch\nfrom", SwissType.Display110)
                Column {
                    if (inputs.isEmpty()) T("No inputs reported by this TV.", SwissType.Body22)
                    for ((i, inp) in inputs.withIndex()) {
                        RuleRow(focus, i, bottomRule = i == inputs.lastIndex, selected = false) { f ->
                            T(inp.label, SwissType.Row44, Modifier.weight(1f), if (f) Swiss.Focus else Swiss.Ink, maxLines = 1)
                            T(inp.detail, SwissType.Meta22, color = if (f) Swiss.Focus else Swiss.Muted, maxLines = 1)
                        }
                    }
                }
                Box(Modifier.weight(1f))
                T("Names come from HDMI-CEC. Switching runs through TCL’s own TV app, which stays installed.", SwissType.Body22)
            }
        }
    }
}

@Immutable
class QuickActions(
    val picture: (PictureMode) -> Unit,
    val sound: (SoundMode) -> Unit,
    val sleep: (Int) -> Unit,
    val pictureOff: () -> Unit,
    val allSettings: () -> Unit,
    val launcherSettings: () -> Unit,
    val back: () -> Unit,
)

private val pictures = listOf(PictureMode.MOVIE to "Movie", PictureMode.STANDARD to "Standard", PictureMode.VIVID to "Vivid", PictureMode.GAME to "Game")
private val sounds = listOf(SoundMode.STANDARD to "Standard", SoundMode.DIALOGUE to "Dialogue", SoundMode.NIGHT to "Night")
private val sleeps = listOf(0 to "Off", 30 to "30", 60 to "60", 90 to "90 min")

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
    // 0–3 picture, 4–6 sound, 7–10 sleep, 11 screen, 12 all settings, 13 launcher settings
    val focus = rememberFocusScreen(14, active = active)
    focus.onClick = { i ->
        when (i) {
            in 0..3 -> actions.picture(pictures[i].first)
            in 4..6 -> actions.sound(sounds[i - 4].first)
            in 7..10 -> actions.sleep(sleeps[i - 7].first)
            11 -> actions.pictureOff()
            12 -> actions.allSettings()
            13 -> actions.launcherSettings()
        }
    }
    focus.onOtherKey = back(actions.back)
    PanelFrame(wallpaper, 960.dp) {
        FocusRoot(focus, Swiss.Focus) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(36.dp)) {
                Row(Modifier.fillMaxWidth().bottomRule().padding(bottom = 18.dp)) {
                    Label("Quick settings", Modifier.weight(1f), Swiss.Ink)
                    Label(status, color = Swiss.Focus)
                }
                Column {
                    OptionRow(focus, "Picture", 0, pictures.map { it.second }, pictures.indexOfFirst { it.first == (picture ?: PictureMode.STANDARD) })
                    OptionRow(focus, "Sound", 4, sounds.map { it.second }, sounds.indexOfFirst { it.first == (sound ?: SoundMode.STANDARD) })
                    OptionRow(focus, "Sleep", 7, sleeps.map { it.second }, sleeps.indexOfFirst { it.first == sleepMinutes }.coerceAtLeast(0))
                    Row(Modifier.fillMaxWidth().topRule()) {
                        Label("Network", Modifier.width(200.dp).padding(vertical = 24.dp))
                        T(networkName, SwissType.Row34, Modifier.padding(vertical = 20.dp), maxLines = 1)
                    }
                    ScreenRow(focus)
                }
                Box(Modifier.weight(1f))
                T("Picture, sound and sleep are private to TCL: each opens TCL’s own page with that setting.", SwissType.Meta18)
                Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    LinkText(focus, 12, "All TV settings →")
                    LinkText(focus, 13, "Launcher settings →")
                }
            }
        }
    }
}

@Composable
private fun OptionRow(focus: FocusScreenState, label: String, base: Int, options: List<String>, selected: Int) {
    val rowFocused by androidx.compose.runtime.remember(base, options.size) {
        androidx.compose.runtime.derivedStateOf { focus.engine.focused.intValue in base until base + options.size }
    }
    val m = if (rowFocused) Modifier.topRule(4.dp, Swiss.Focus) else Modifier.topRule()
    Row(Modifier.fillMaxWidth().then(m), verticalAlignment = Alignment.CenterVertically) {
        Label(label, Modifier.width(200.dp).padding(vertical = 24.dp), if (rowFocused) Swiss.Focus else Swiss.Muted)
        Row(Modifier.padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            for ((k, o) in options.withIndex()) TabText(focus, base + k, o, k == selected)
        }
    }
}

@Composable
private fun ScreenRow(focus: FocusScreenState) {
    val f by remember(focus, 11) { focus.isFocused(11) }
    Row(Modifier.fillMaxWidth().then(if (f) Modifier.topRule(4.dp, Swiss.Focus) else Modifier.topRule()).bottomRule(), verticalAlignment = Alignment.CenterVertically) {
        Label("Screen", Modifier.width(200.dp).padding(vertical = 24.dp), if (f) Swiss.Focus else Swiss.Muted)
        T("Picture off, sound on", SwissType.Row34, Modifier.focusItem(focus, 11, FocusStyle.SELF).padding(vertical = 24.dp), if (f) Swiss.Focus else Swiss.Ink)
    }
}

@Composable
private fun LinkText(focus: FocusScreenState, index: Int, text: String) {
    val f by remember(focus, index) { focus.isFocused(index) }
    T(
        text, SwissType.Button,
        Modifier.focusItem(focus, index, FocusStyle.SELF).then(if (f) Modifier.bottomRule(3.dp, Swiss.Focus) else Modifier).padding(vertical = 8.dp),
        Swiss.Focus,
    )
}
