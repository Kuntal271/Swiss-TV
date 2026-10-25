package com.swissdark.launcher.core.design

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Swiss motion: short, purposeful, no bounce. Enters decelerate (emphasized), exits are quick fades.
 *
 * Every animation here runs in the draw phase (layer alpha / translation / scale or a canvas
 * transform), never on layout size or padding, so a running animation re-records a layer or two
 * and recomposes nothing.
 *
 * Two kinds of movement, on purpose:
 * - [drawSlide]: a canvas transform. Layout coordinates do not move, so focus rects and the ring
 *   (drawn inside the same subtree) stay consistent. Used for whole screens and panels.
 * - [enter]: layer alpha plus a canvas translation for small groups. Groups that hold ring-style
 *   focus items only fade (the ring lives outside them and would not slide along).
 * Nothing here moves layout coordinates: focus rects are always the settled layout positions.
 */
object Motion {
    // Durations, ms
    const val QUICK = 120        // exits, small state changes, focus ring
    const val SHORT = 160        // pops, fades of small pieces
    const val MEDIUM = 220       // page enters
    const val LONG = 280         // progress fills, panels
    const val STAGGER = 40       // between groups of a staggered entrance

    /** Material 3 emphasized decelerate: fast out of the gate, long settle. */
    val EnterEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val StandardEasing: Easing = FastOutSlowInEasing
    val ExitEasing: Easing = FastOutLinearInEasing

    /** Page slide distance, px (= design px). */
    const val PAGE_SLIDE = 32f
    const val ITEM_SLIDE = 16f
    const val FOCUS_SCALE = 1.04f

    fun <T> enter(duration: Int = MEDIUM, delay: Int = 0): FiniteAnimationSpec<T> = tween(duration, delay, EnterEasing)
    fun <T> exit(duration: Int = QUICK): FiniteAnimationSpec<T> = tween(duration, 0, ExitEasing)
    fun <T> standard(duration: Int = SHORT): FiniteAnimationSpec<T> = tween(duration, 0, StandardEasing)

    /** True when the user turned animations off (Developer options: Animator duration scale = 0). */
    fun reduceMotion(context: Context): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) {
        false
    }
}

/** Provided by [DesignCanvas]. True = draw final states, never animate. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Spec helper: the given spec, or a snap when motion is reduced. */
@Composable
fun <T> motionSpec(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = if (LocalReduceMotion.current) snap() else spec

/**
 * A 0 → 1 progress that runs once when [key] first appears (or changes). Starts at [from] so the
 * first frame already shows the content; with reduced motion it is 1 from the start.
 */
@Composable
fun rememberAppear(key: Any?, duration: Int = Motion.MEDIUM, delay: Int = 0, from: Float = 0f, easing: Easing = Motion.EnterEasing): Animatable<Float, *> {
    val reduce = LocalReduceMotion.current
    val a = remember(key) { Animatable(if (reduce) 1f else from) }
    LaunchedEffect(key) {
        if (!reduce && a.value < 1f) a.animateTo(1f, tween(duration, delay, easing))
    }
    return a
}

/**
 * Fade + slide driven by [progress] (0 hidden … 1 settled). Alpha is a layer property (ModulateAlpha,
 * so a fading group never needs an offscreen buffer); the slide is a canvas translation, so layout
 * coordinates never move and focus rects registered inside stay exact. Keep dx/dy at 0 on groups
 * that hold ring-style focus items: the ring is drawn outside the group and would not slide.
 */
fun Modifier.enter(progress: () -> Float, dx: Float = 0f, dy: Float = Motion.ITEM_SLIDE, fromAlpha: Float = 0f): Modifier {
    val faded = graphicsLayer {
        alpha = fromAlpha + (1f - fromAlpha) * progress()
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
    if (dx == 0f && dy == 0f) return faded
    return faded.drawWithContent {
        val p = 1f - progress()
        if (p == 0f) drawContent() else translate(dx * p, dy * p) { this@drawWithContent.drawContent() }
    }.graphicsLayer()
}

/**
 * Whole-screen enter/exit: layer alpha, canvas translation. The canvas transform does not move
 * layout coordinates, so the focus ring drawn inside the screen stays on its item. The inner
 * layer keeps the screen's own drawing cached while the outer one re-records one op per frame.
 */
fun Modifier.drawSlide(progress: () -> Float, dx: Float = Motion.PAGE_SLIDE, dy: Float = 0f, fade: Boolean = true): Modifier =
    graphicsLayer {
        if (fade) alpha = progress()
    }.drawWithContent {
        val p = 1f - progress()
        if (p == 0f) drawContent() else translate(dx * p, dy * p) { this@drawWithContent.drawContent() }
    }.graphicsLayer()

/** Pop: scale from [from] to 1 around the centre, with a fade (menus, dialogs). Canvas transform only. */
fun Modifier.drawPop(progress: () -> Float, from: Float = 0.96f): Modifier =
    graphicsLayer { alpha = progress() }
        .drawWithContent {
            val s = from + (1f - from) * progress()
            if (s == 1f) drawContent() else scale(s) { this@drawWithContent.drawContent() }
        }.graphicsLayer()

/** Text that crossfades when it changes (clock minute). Once a minute, so composition cost is moot. */
@Composable
fun CrossfadeText(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Crossfade(text, modifier, animationSpec = motionSpec(tween(Motion.LONG, 0, Motion.StandardEasing)), label = "text") {
        T(it, style, color = color, maxLines = 1)
    }
}

/**
 * Enter progress of the screen being shown (0 → 1; back to 0 while it leaves). Provided by the
 * app's route layer to screens that animate their own parts (side panels); 1 elsewhere.
 */
val LocalScreenProgress = staticCompositionLocalOf<() -> Float> { { 1f } }
