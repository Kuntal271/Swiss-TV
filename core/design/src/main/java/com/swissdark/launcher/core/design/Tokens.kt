package com.swissdark.launcher.core.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import com.swissdark.launcher.core.perf.SemanticsGate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Black first, one accent. All other colour comes from artwork. */
object Swiss {
    val Ground = Color(0xFF0A0A0B)
    val Panel = Color(0xFF141416)
    val Rule = Color(0xFF2E2E33)
    val Ink = Color(0xFFEDEDED)
    val Focus = Color(0xFFF2C230)
    val Muted = Color(0xFF9C9CA3)
    val Track = Color(0xFF3A3A40)
    val TileFill = Color(0xD1141416)       // rgba(20,20,22,0.82)
    val DimClock = Color(0xFF6E6E75)
    val Placeholder = Color(0xFF26262B)
    val Well = Color(0xFF1C1C1F)
    val Dashed = Color(0xFF55555C)

    // 1920 × 1080 · 12 columns · 32 gutter · 120 side margin · 72 top/bottom
    const val CANVAS_W = 1920f
    const val MARGIN_X = 120f
    const val MARGIN_Y = 72f
    const val GUTTER = 32f
    const val COL = (CANVAS_W - 2 * MARGIN_X - 11 * GUTTER) / 12f

    /** Width of n grid columns including the gutters between them. */
    fun span(n: Int): Dp = (COL * n + GUTTER * (n - 1)).dp
}

/**
 * One design pixel = one dp. The TV draws its UI on a 1920 × 1080 canvas, so on the 55C61B
 * this is a density of 1.0 and the mock-ups map pixel for pixel.
 */
@Composable
fun DesignCanvas(typeScale: Float = 1f, content: @Composable () -> Unit) {
    val cfg = LocalConfiguration.current
    val physical = LocalDensity.current
    val widthPx = cfg.screenWidthDp * physical.density
    val density = Density(widthPx / Swiss.CANVAS_W, 1f)
    val view = LocalView.current
    DisposableEffect(view) {
        val detach = SemanticsGate.attach(view)
        onDispose { detach() }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val reduce = androidx.compose.runtime.remember(ctx) { Motion.reduceMotion(ctx) }
    CompositionLocalProvider(LocalDensity provides density, LocalReduceMotion provides reduce, LocalTypeScale provides typeScale, content = content)
}
