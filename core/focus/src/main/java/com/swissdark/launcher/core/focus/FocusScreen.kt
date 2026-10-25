package com.swissdark.launcher.core.focus

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawWithContent
import com.swissdark.launcher.core.perf.KeyLatency
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How an item shows focus. RING: the shared amber ring layer. SELF: the item restyles itself. */
enum class FocusStyle { RING, SELF }

/**
 * One screen's focus: item rects, the precomputed graph, the ring and the scroll offset.
 * A D-pad press reads one array entry and writes one integer; the ring moves by changing
 * draw-phase floats only, so nothing is recomposed or measured.
 */
@Stable
class FocusScreenState internal constructor(
    count: Int,
    initial: Int,
    private val scope: CoroutineScope,
) {
    val engine = ArrayFocusEngine(initial)
    var count = count
        private set
    private var rects = FloatArray(count * 4)
    private var styles = arrayOfNulls<FocusStyle>(count)
    private var scrolls = BooleanArray(count)
    private var seen = BooleanArray(count)
    private var seenCount = 0
    private var dirty = true

    // Ring, in root px. Read only in the draw phase.
    private val ringX = mutableFloatStateOf(0f)
    private val ringY = mutableFloatStateOf(0f)
    private val ringW = mutableFloatStateOf(0f)
    private val ringH = mutableFloatStateOf(0f)
    private val ringAlpha = mutableFloatStateOf(0f)
    private val anim = Animatable(1f)
    private var animJob: Job? = null
    private val from = FloatArray(4)
    private val to = FloatArray(4)

    /** Vertical scroll of the scrolling region, applied at placement. */
    val scrollY = mutableFloatStateOf(0f)
    var viewportTop = 0f
    var viewportBottom = Float.MAX_VALUE

    var onClick: (Int) -> Unit = {}
    var onLongClick: (Int) -> Unit = {}
    /** No neighbour in that direction. Return true if handled (e.g. moved to another screen part). */
    var onEdge: (Int, DpadKey) -> Boolean = { _, _ -> false }
    /** Any other key (Back, media, Search, Input). Return true if handled. */
    var onOtherKey: (KeyEvent) -> Boolean = { false }
    var onFocusChanged: (Int) -> Unit = {}
    var ringOffset = 6f
    var ringStroke = 4f
    var enabled = true

    private var okDownAt = -1L
    private var longFired = false

    val focused: Int get() = engine.focused.intValue

    fun isFocused(index: Int): State<Boolean> = derivedStateOf { engine.focused.intValue == index }

    /** Bumped when the item count changes; FocusRoot remounts its content so every item re-registers. */
    val generation = androidx.compose.runtime.mutableIntStateOf(0)

    fun resize(newCount: Int, keepIndex: Int) {
        if (newCount == count) return
        generation.intValue++
        count = newCount
        rects = FloatArray(newCount * 4)
        styles = arrayOfNulls(newCount)
        scrolls = BooleanArray(newCount)
        seen = BooleanArray(newCount)
        seenCount = 0
        dirty = true
        engine.bind(FocusGraph.EMPTY)
        engine.set(keepIndex.coerceIn(0, (newCount - 1).coerceAtLeast(0)))
    }

    internal fun register(index: Int, c: LayoutCoordinates, style: FocusStyle, scrolling: Boolean) {
        if (index < 0 || index >= count) return
        val p = c.positionInRoot()
        val y = p.y + if (scrolling) scrollY.floatValue else 0f
        val o = index * 4
        val w = c.size.width.toFloat(); val h = c.size.height.toFloat()
        if (rects[o] != p.x || rects[o + 1] != y || rects[o + 2] != w || rects[o + 3] != h) {
            rects[o] = p.x; rects[o + 1] = y; rects[o + 2] = w; rects[o + 3] = h
            dirty = true
        }
        styles[index] = style
        scrolls[index] = scrolling
        if (!seen[index]) {
            seen[index] = true
            seenCount++
        }
        if (index == engine.focused.intValue) placeRing(animate = false)
    }

    private fun ensureGraph() {
        if (!dirty) return
        engine.bind(FocusGraphBuilder.build(rects, count))
        dirty = false
    }

    fun focus(index: Int, animate: Boolean = false) {
        if (index < 0 || index >= count) return
        engine.set(index)
        ensureVisible(index)
        placeRing(animate)
        onFocusChanged(index)
    }

    private fun ensureVisible(i: Int) {
        if (!scrolls[i]) return
        val top = rects[i * 4 + 1] - 24f
        val bottom = rects[i * 4 + 1] + rects[i * 4 + 3] + 120f
        val s = scrollY.floatValue
        if (bottom - s > viewportBottom) scrollY.floatValue = bottom - viewportBottom
        else if (top - s < viewportTop) scrollY.floatValue = (top - viewportTop).coerceAtLeast(0f)
    }

    private fun placeRing(animate: Boolean) {
        val i = engine.focused.intValue
        if (i < 0 || i >= count || styles[i] != FocusStyle.RING) {
            ringAlpha.floatValue = 0f
            return
        }
        val o = i * 4
        val dy = if (scrolls[i]) scrollY.floatValue else 0f
        to[0] = rects[o]; to[1] = rects[o + 1] - dy; to[2] = rects[o + 2]; to[3] = rects[o + 3]
        val wasVisible = ringAlpha.floatValue > 0f
        ringAlpha.floatValue = 1f
        animJob?.cancel()
        if (!animate || !wasVisible) {
            ringX.floatValue = to[0]; ringY.floatValue = to[1]; ringW.floatValue = to[2]; ringH.floatValue = to[3]
            return
        }
        from[0] = ringX.floatValue; from[1] = ringY.floatValue; from[2] = ringW.floatValue; from[3] = ringH.floatValue
        animJob = scope.launch {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(120, easing = FastOutSlowInEasing)) {
                val t = value
                ringX.floatValue = from[0] + (to[0] - from[0]) * t
                ringY.floatValue = from[1] + (to[1] - from[1]) * t
                ringW.floatValue = from[2] + (to[2] - from[2]) * t
                ringH.floatValue = from[3] + (to[3] - from[3]) * t
            }
        }
    }

    /** Entry point for every key. Returns true when consumed. */
    fun handle(e: KeyEvent): Boolean {
        if (!enabled) return false
        val dir = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> DpadKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> DpadKey.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> DpadKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> DpadKey.RIGHT
            else -> null
        }
        if (dir != null) {
            if (e.action != KeyEvent.ACTION_DOWN) return true
            KeyLatency.onKey(e.eventTime)
            ensureGraph()
            val cur = engine.focused.intValue
            val repeat = e.repeatCount > 0
            if (engine.onKey(dir, repeat)) {
                val next = engine.focused.intValue
                ensureVisible(next)
                // Held keys repeat ~every 50 ms: skip the ring animation so focus never lags.
                placeRing(animate = !repeat)
                onFocusChanged(next)
            } else {
                onEdge(cur, dir)
            }
            return true
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (e.action == KeyEvent.ACTION_DOWN) {
                    if (e.repeatCount == 0) {
                        okDownAt = e.downTime
                        longFired = false
                    } else if (!longFired && (e.isLongPress || e.eventTime - e.downTime > 500)) {
                        longFired = true
                        onLongClick(engine.focused.intValue)
                    }
                } else if (e.action == KeyEvent.ACTION_UP) {
                    if (okDownAt >= 0 && !longFired) onClick(engine.focused.intValue)
                    okDownAt = -1
                }
                return true
            }
        }
        return onOtherKey(e)
    }

    internal fun drawRing(scope: androidx.compose.ui.graphics.drawscope.DrawScope, color: Color) {
        if (ringAlpha.floatValue <= 0f) return
        val off = ringOffset + ringStroke / 2f
        scope.drawRect(
            color = color,
            topLeft = Offset(ringX.floatValue - off, ringY.floatValue - off),
            size = Size(ringW.floatValue + off * 2, ringH.floatValue + off * 2),
            style = Stroke(width = ringStroke),
        )
    }
}

/** Keys go to the top-most registered screen (panels sit above the screen under them). */
object KeyRouter {
    private val stack = ArrayList<FocusScreenState>(4)

    fun push(s: FocusScreenState) {
        stack.remove(s); stack.add(s)
    }

    fun remove(s: FocusScreenState) {
        stack.remove(s)
    }

    fun dispatch(e: KeyEvent): Boolean = stack.lastOrNull()?.handle(e) ?: false
}

@Composable
fun rememberFocusScreen(count: Int, initial: Int = 0, active: Boolean = true): FocusScreenState {
    val scope = rememberCoroutineScope()
    val state = remember { FocusScreenState(count, initial, scope) }
    state.resize(count, state.focused)
    DisposableEffect(state, active) {
        if (active) KeyRouter.push(state)
        onDispose { KeyRouter.remove(state) }
    }
    return state
}

/** Root of a focus screen: draws the single ring layer above its content. */
@Composable
fun FocusRoot(state: FocusScreenState, ringColor: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.drawWithContent {
        drawContent()
        state.drawRing(this, ringColor)
    }) { androidx.compose.runtime.key(state.generation.intValue) { content() } }
}

/** Marks a focusable item (or the part of it the ring should surround). */
fun Modifier.focusItem(
    state: FocusScreenState,
    index: Int,
    style: FocusStyle = FocusStyle.RING,
    scrolling: Boolean = false,
): Modifier = onGloballyPositioned { state.register(index, it, style, scrolling) }

/** The scrolling region: offset at placement, so a scroll never recomposes or measures. */
fun Modifier.focusScroll(state: FocusScreenState): Modifier =
    offset { IntOffset(0, -state.scrollY.floatValue.roundToInt()) }
        .onGloballyPositioned { }

/** Tells the state where the scrolling viewport is (call on the clipping container). */
fun Modifier.focusViewport(state: FocusScreenState): Modifier = onGloballyPositioned {
    val p = it.positionInRoot()
    state.viewportTop = p.y
    state.viewportBottom = p.y + it.size.height
}
