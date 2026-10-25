package com.swissdark.launcher.core.focus

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.tracing.Trace
import com.swissdark.launcher.core.perf.KeyLatency
import com.swissdark.launcher.core.perf.Sections
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
    private var scales = BooleanArray(count)
    private var scaleTo = FloatArray(count)     // per-item focus scale; 0 = the screen's focusScale
    private var seen = BooleanArray(count)
    /** The live node that registered each index (an item that leaves composition clears only its own slot). */
    private var owners = arrayOfNulls<Any>(count)
    /** Every attached focus item, so a count change can re-register them without a remount. */
    private val live = ArrayList<FocusItemNode>()
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

    // Focus scale, read in the draw phase only. The item that gains focus grows from curFrom to
    // curTo and the one that lost it shrinks back to 1, both on the ring's own 0..1 progress, so
    // ring and item always move together.
    private val scaleCur = mutableIntStateOf(-1)
    private val scalePrev = mutableIntStateOf(-1)
    private val progress = mutableFloatStateOf(1f)
    private var curFrom = 1f
    private var curTo = 1f
    private var prevFrom = 1f
    private var ringMoves = false
    private var ringScrolls = false

    /** Scale of a focused item that opted in (focusItem(scale = true)). 1 = no scale. */
    var focusScale = 1.04f

    /** Current draw scale of [index]; reads snapshot state, so call it from the draw phase. */
    fun scaleOf(index: Int): Float {
        // Progress is read only by the two items involved, so an animation frame never
        // re-evaluates every item's scale.
        if (index == scaleCur.intValue) return curFrom + (curTo - curFrom) * progress.floatValue
        if (index == scalePrev.intValue) return prevFrom + (1f - prevFrom) * progress.floatValue
        return 1f
    }

    /** Per-item scale that only notifies when this item's value changes. */
    fun scaleState(index: Int): State<Float> = derivedStateOf { scaleOf(index) }

    /** The scale [i] grows to when focused (1 = it does not scale). */
    private fun targetScale(i: Int): Float = if (!scales[i]) 1f else if (scaleTo[i] > 0f) scaleTo[i] else focusScale

    /**
     * 0..1, how focused [index] is: 1 when focused, 0 when not, and in between on the focus
     * scale's own progress, so a fill or colour fades exactly with the scale. Draw phase only.
     */
    fun focusFraction(index: Int): Float {
        if (index < 0 || index >= count) return 0f
        val t = targetScale(index)
        if (t == 1f) return if (engine.focused.intValue == index) 1f else 0f
        return ((scaleOf(index) - 1f) / (t - 1f)).coerceIn(0f, 1f)
    }

    /** [focusFraction] as a state that only notifies when this item's value changes. */
    fun fractionState(index: Int): State<Float> = derivedStateOf { focusFraction(index) }

    /**
     * Vertical scroll of the scrolling region. Applied as a canvas translation (draw phase only):
     * a scroll never measures, places or re-registers anything, and the registered rects of
     * scrolling items stay in unscrolled layout space.
     */
    val scrollY = mutableFloatStateOf(0f)
    /** Where [scrollY] is heading. Focus geometry uses this; drawing uses the animated [scrollY]. */
    private var scrollTarget = 0f
    private val scrollAnim = Animatable(0f)
    private var scrollJob: kotlinx.coroutines.Job? = null

    /**
     * Glides the scroll to [y]: an emphasized-decelerate ease for single presses, a short linear
     * catch-up while a key is held so the list keeps pace with the focus. Draw phase only.
     */
    fun scrollTo(y: Float, animate: Boolean = true, held: Boolean = false) {
        if (y == scrollTarget && (scrollJob?.isActive == true || y == scrollY.floatValue)) return
        scrollTarget = y
        scrollJob?.cancel()
        if (!animate) {
            scrollY.floatValue = y
            return
        }
        scrollJob = scope.launch {
            scrollAnim.snapTo(scrollY.floatValue)
            val spec = if (held) tween<Float>(SCROLL_HELD_MS, easing = androidx.compose.animation.core.LinearEasing)
            else tween(SCROLL_MS, easing = ScrollEasing)
            scrollAnim.animateTo(y, spec) { scrollY.floatValue = value }
        }
    }
    var viewportTop = 0f
    var viewportBottom = Float.MAX_VALUE
    /** Bottom of the scrolling content (root px, unscrolled); the scroll never goes past it. */
    var contentBottom = Float.MAX_VALUE

    var onClick: (Int) -> Unit = {}
    var onLongClick: (Int) -> Unit = {}
    /** No neighbour in that direction. Return true if handled (e.g. moved to another screen part). */
    var onEdge: (Int, DpadKey) -> Boolean = { _, _ -> false }
    /** Any other key (Back, media, Search, Input). Return true if handled. */
    var onOtherKey: (KeyEvent) -> Boolean = { false }
    var onFocusChanged: (Int) -> Unit = {}
    /**
     * Runs after the graph picked [to] for a press from [from] in [dir]; returns where focus really
     * goes (e.g. focus memory, or a row the screen wants kept). Returning [from] = no move.
     */
    var redirect: (from: Int, to: Int, dir: DpadKey) -> Int = { _, to, _ -> to }
    /**
     * Scroll only to row starts: the viewport's top edge always sits EDGE above some scrolling
     * item's top, so no sliver of a row (art cut, label bright) is ever left at the top.
     */
    var snapRows = false
    /** Scroll back to 0 whenever the focused item is visible there (keeps headings above it whole). */
    var preferTop = false
    var ringOffset = 6f
    var ringStroke = 4f
    var enabled = true

    private var okDownAt = -1L
    private var longFired = false

    val focused: Int get() = engine.focused.intValue

    fun isFocused(index: Int): State<Boolean> = derivedStateOf { engine.focused.intValue == index }

    /**
     * New item count. Nothing is remounted: the arrays are rebuilt and every live item registers
     * again from its last coordinates; items that leave composition clear their own slot, and new
     * ones register on their first placement.
     */
    fun resize(newCount: Int, keepIndex: Int) {
        if (newCount == count) return
        count = newCount
        rects = FloatArray(newCount * 4)
        styles = arrayOfNulls(newCount)
        scrolls = BooleanArray(newCount)
        scales = BooleanArray(newCount)
        scaleTo = FloatArray(newCount)
        scaleCur.intValue = -1
        scalePrev.intValue = -1
        seen = BooleanArray(newCount)
        owners = arrayOfNulls(newCount)
        dirty = true
        engine.bind(FocusGraph.EMPTY)
        engine.set(keepIndex.coerceIn(0, (newCount - 1).coerceAtLeast(0)))
        for (k in live.indices) live[k].reRegister()
    }

    internal fun attach(node: FocusItemNode) {
        if (!live.contains(node)) live.add(node)
    }

    /** [node] left composition (or moved to another index): clear its slot if it still owns it. */
    internal fun unregister(node: FocusItemNode, index: Int, detach: Boolean) {
        if (detach) live.remove(node)
        if (index < 0 || index >= count || owners[index] !== node) return
        owners[index] = null
        val o = index * 4
        rects[o] = 0f; rects[o + 1] = 0f; rects[o + 2] = 0f; rects[o + 3] = 0f
        seen[index] = false
        dirty = true
    }

    internal fun register(owner: Any?, index: Int, c: LayoutCoordinates, style: FocusStyle, scrolling: Boolean, scale: Boolean = false, scaleAmount: Float = 0f) {
        if (index < 0 || index >= count || !c.isAttached) return
        if (owner != null) owners[index] = owner
        // Scrolling is a canvas translation, so layout positions are already unscrolled.
        val p = c.positionInRoot()
        val y = p.y
        val o = index * 4
        val w = c.size.width.toFloat(); val h = c.size.height.toFloat()
        var changed = styles[index] != style || scrolls[index] != scrolling || scales[index] != scale || scaleTo[index] != scaleAmount || !seen[index]
        if (rects[o] != p.x || rects[o + 1] != y || rects[o + 2] != w || rects[o + 3] != h) {
            rects[o] = p.x; rects[o + 1] = y; rects[o + 2] = w; rects[o + 3] = h
            dirty = true
            changed = true
        }
        styles[index] = style
        scrolls[index] = scrolling
        scales[index] = scale
        scaleTo[index] = scaleAmount
        seen[index] = true
        // Global layout passes (a scroll, the hero text reflowing) call this for every item.
        // Only a real move of the focused item touches the ring, and a running ring animation
        // is re-targeted rather than snapped.
        if (changed && index == engine.focused.intValue) {
            if (animJob?.isActive == true && style == FocusStyle.RING) {
                ringTarget(index)
            } else {
                placeRing(animate = false)
            }
        }
    }

    private fun ensureGraph() {
        if (!dirty) return
        engine.bind(FocusGraphBuilder.build(rects, count))
        dirty = false
    }

    fun focus(index: Int, animate: Boolean = false) {
        if (index < 0 || index >= count) return
        engine.set(index)
        ensureVisible(index, animate, held = false)
        placeRing(animate)
        onFocusChanged(index)
    }

    private fun ensureVisible(i: Int, animate: Boolean, held: Boolean) {
        if (!scrolls[i]) return
        val top = rects[i * 4 + 1] - EDGE
        // Below the focused item keep a peek of the next row, but never scroll past the end of
        // the content: the last row then sits EDGE above the viewport bottom, so its focus scale
        // and ring are never clipped.
        val itemBottom = rects[i * 4 + 1] + rects[i * 4 + 3]
        val maxScroll = (maxOf(contentBottom.coerceAtMost(Float.MAX_VALUE / 2), itemBottom) + EDGE - viewportBottom).coerceAtLeast(0f)
        val bottom = itemBottom + PEEK
        val s = scrollTarget
        // Everything fits, or (preferTop) this item shows with no scroll at all: stay at the top,
        // so labels and titles above the first rows are never sliced.
        if (contentBottom <= viewportBottom || (preferTop && itemBottom + EDGE <= viewportBottom)) {
            if (s != 0f) scrollTo(0f, animate, held)
            return
        }
        var y = when {
            bottom - s > viewportBottom -> if (snapRows) bottom - viewportBottom else (bottom - viewportBottom).coerceAtMost(maxScroll)
            top - s < viewportTop -> (top - viewportTop).coerceAtLeast(0f)
            s > maxScroll && !snapRows -> maxScroll
            else -> return
        }
        if (snapRows && y > 0f) y = rowStartAtOrAfter(y, i)
        scrollTo(y, animate, held)
    }

    /** The smallest row-start scroll ≥ [y] that still shows item [i]'s top (no allocation). */
    private fun rowStartAtOrAfter(y: Float, i: Int): Float {
        val limit = rects[i * 4 + 1] - EDGE - viewportTop
        var best = Float.MAX_VALUE
        for (k in 0 until count) {
            if (!scrolls[k] || !seen[k]) continue
            val c = rects[k * 4 + 1] - EDGE - viewportTop
            if (c >= y - 0.5f && c <= limit + 0.5f && c < best) best = c
        }
        return if (best == Float.MAX_VALUE) y else best.coerceAtLeast(0f)
    }

    /** The ring's target for item [i]: its rect, grown by the focus scale when the item scales. */
    private fun ringTarget(i: Int) {
        val o = i * 4
        val s = targetScale(i)
        val w = rects[o + 2]; val h = rects[o + 3]
        val dy = 0f  // scrolling items: content space; drawRing applies the live scroll
        val gx = w * (s - 1f) / 2f; val gy = h * (s - 1f) / 2f
        to[0] = rects[o] - gx; to[1] = rects[o + 1] - dy - gy; to[2] = w + 2 * gx; to[3] = h + 2 * gy
    }

    private fun placeRing(animate: Boolean) {
        val i = engine.focused.intValue
        animJob?.cancel()
        if (i < 0 || i >= count) {
            ringAlpha.floatValue = 0f
            return
        }
        // Hand the scale over from whatever is scaled now (mid-animation values included).
        val old = scaleCur.intValue
        val sNew = scaleOf(i)
        prevFrom = if (old != i && old >= 0) scaleOf(old) else 1f
        curFrom = sNew
        curTo = targetScale(i)
        scalePrev.intValue = if (old != i) old else -1
        scaleCur.intValue = i

        val ring = styles[i] == FocusStyle.RING
        val wasVisible = ringAlpha.floatValue > 0f
        // Moving between scrolled and fixed items: carry the ring's on-screen position over.
        val nowScrolls = scrolls[i]
        if (nowScrolls != ringScrolls) {
            ringY.floatValue += if (nowScrolls) scrollY.floatValue else -scrollY.floatValue
            ringScrolls = nowScrolls
        }
        if (ring) {
            ringTarget(i)
            ringAlpha.floatValue = 1f
        } else {
            ringAlpha.floatValue = 0f
        }
        ringMoves = ring && wasVisible
        if (!ringMoves && ring) {
            ringX.floatValue = to[0]; ringY.floatValue = to[1]; ringW.floatValue = to[2]; ringH.floatValue = to[3]
        }
        if (!animate || (!ringMoves && curFrom == curTo && prevFrom == 1f)) {
            progress.floatValue = 1f
            if (ring) { ringX.floatValue = to[0]; ringY.floatValue = to[1]; ringW.floatValue = to[2]; ringH.floatValue = to[3] }
            return
        }
        from[0] = ringX.floatValue; from[1] = ringY.floatValue; from[2] = ringW.floatValue; from[3] = ringH.floatValue
        progress.floatValue = 0f
        animJob = scope.launch {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(if (ringScrolls) RING_SCROLL_MS else RING_MS, easing = ScrollEasing)) {
                val t = value
                progress.floatValue = t
                if (ringMoves) {
                    ringW.floatValue = from[2] + (to[2] - from[2]) * t
                    ringH.floatValue = from[3] + (to[3] - from[3]) * t
                    if (ringScrolls) {
                        // Tiles in a scrolling grid: the ring sits on the new tile at once (growing with
                        // its scale), never half-way between two rows, however fast the presses come.
                        ringX.floatValue = to[0] + (to[2] - ringW.floatValue) / 2f
                        ringY.floatValue = to[1] + (to[3] - ringH.floatValue) / 2f
                    } else {
                        ringX.floatValue = from[0] + (to[0] - from[0]) * t
                        ringY.floatValue = from[1] + (to[1] - from[1]) * t
                    }
                }
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
            Trace.beginSection(Sections.NAV_DISPATCH)
            try { move(dir, e.repeatCount > 0) } finally { Trace.endSection() }
            return true
        }
        return handleOther(e)
    }

    private fun move(dir: DpadKey, repeat: Boolean) {
        ensureGraph()
        val cur = engine.focused.intValue
        if (engine.onKey(dir, repeat)) {
            var next = engine.focused.intValue
            val r = redirect(cur, next, dir)
            if (r != next) {
                if (r < 0 || r >= count || r == cur) { engine.set(cur); return }
                engine.set(r); next = r
            }
            ensureVisible(next, animate = true, held = repeat)
            // Held keys repeat ~every 50 ms: skip the ring animation so focus never lags.
            placeRing(animate = !repeat)
            onFocusChanged(next)
        } else {
            onEdge(cur, dir)
        }
    }

    private fun handleOther(e: KeyEvent): Boolean {
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
                    if (okDownAt >= 0 && !longFired) {
                        recordOrigin(engine.focused.intValue)
                        onClick(engine.focused.intValue)
                    }
                    okDownAt = -1
                }
                return true
            }
        }
        return onOtherKey(e)
    }

    /** Where the clicked item is on screen, so an app started by this click can grow out of it. */
    private fun recordOrigin(i: Int) {
        if (i < 0 || i >= count || !seen[i]) return
        val o = i * 4
        val dy = if (scrolls[i]) scrollY.floatValue else 0f
        val s = targetScale(i)
        val w = rects[o + 2] * s; val h = rects[o + 3] * s
        LaunchOrigin.record(rects[o] - (w - rects[o + 2]) / 2f, rects[o + 1] - dy - (h - rects[o + 3]) / 2f, w, h)
    }

    internal fun drawRing(scope: androidx.compose.ui.graphics.drawscope.DrawScope, color: Color) {
        if (ringAlpha.floatValue <= 0f) return
        val off = ringOffset + ringStroke / 2f
        var st = stroke
        if (st.width != ringStroke) { st = Stroke(width = ringStroke); stroke = st }
        scope.drawRect(
            color = color,
            topLeft = Offset(ringX.floatValue - off, ringY.floatValue - off - if (ringScrolls) scrollY.floatValue else 0f),
            size = Size(ringW.floatValue + off * 2, ringH.floatValue + off * 2),
            style = st,
        )
    }

    private var stroke = Stroke(width = 4f)

    private companion object {
        const val EDGE = 24f
        const val PEEK = 120f
        const val RING_MS = 180
        const val RING_SCROLL_MS = 120     // items in a scrolling grid: the ring grows with the tile's scale
        const val SCROLL_MS = 320
        const val SCROLL_HELD_MS = 90
        val ScrollEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    }
}

/** Counts Home-key presses that reach the launcher (onNewIntent); Home resets its focus on each. */
object HomePress {
    val count = mutableIntStateOf(0)
    fun press() { count.intValue++ }
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
    Box(modifier) {
        content()
        // The ring lives in its own layer: moving or animating it re-records only this layer,
        // never the screen underneath.
        Spacer(Modifier.matchParentSize().graphicsLayer().drawBehind { state.drawRing(this, ringColor) })
    }
}

/** Marks a focusable item (or the part of it the ring should surround). */
fun Modifier.focusItem(
    state: FocusScreenState,
    index: Int,
    style: FocusStyle = FocusStyle.RING,
    scrolling: Boolean = false,
    scale: Boolean = false,
    /** The focused scale for this item; 0 = the screen's focusScale (1.04). */
    scaleAmount: Float = 0f,
): Modifier = this then FocusItemElement(state, index, style, scrolling, scale, scaleAmount)

private data class FocusItemElement(
    val state: FocusScreenState,
    val index: Int,
    val style: FocusStyle,
    val scrolling: Boolean,
    val scale: Boolean,
    val scaleAmount: Float,
) : ModifierNodeElement<FocusItemNode>() {
    override fun create() = FocusItemNode(state, index, style, scrolling, scale, scaleAmount)
    override fun update(node: FocusItemNode) = node.update(state, index, style, scrolling, scale, scaleAmount)
}

/**
 * A focus item's registration. It registers on every global position change, clears its slot
 * when it leaves composition, and moves its slot when its index changes, so the screen's arrays
 * always match what is composed without remounting anything.
 */
internal class FocusItemNode(
    private var state: FocusScreenState,
    private var index: Int,
    private var style: FocusStyle,
    private var scrolling: Boolean,
    private var scale: Boolean,
    private var scaleAmount: Float,
) : Modifier.Node(), GlobalPositionAwareModifierNode {
    private var coords: LayoutCoordinates? = null

    override fun onAttach() = state.attach(this)

    override fun onDetach() {
        state.unregister(this, index, detach = true)
        coords = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        coords = coordinates
        state.register(this, index, coordinates, style, scrolling, scale, scaleAmount)
    }

    fun update(state: FocusScreenState, index: Int, style: FocusStyle, scrolling: Boolean, scale: Boolean, scaleAmount: Float) {
        if (state !== this.state) {
            this.state.unregister(this, this.index, detach = true)
            state.attach(this)
        } else if (index != this.index) {
            state.unregister(this, this.index, detach = false)
        }
        this.state = state; this.index = index; this.style = style; this.scrolling = scrolling; this.scale = scale; this.scaleAmount = scaleAmount
        reRegister()
    }

    fun reRegister() {
        val c = coords ?: return
        if (isAttached && c.isAttached) state.register(this, index, c, style, scrolling, scale, scaleAmount)
    }
}

/**
 * The scrolling region. Its content is measured at full height (never squeezed into the
 * viewport) and scrolled by a canvas translation in its own layer, so a scroll re-records one
 * draw op: nothing recomposes, measures, places or re-registers.
 */
fun Modifier.focusScroll(state: FocusScreenState): Modifier =
    this.graphicsLayer()
        .drawWithContent {
            val s = state.scrollY.floatValue
            if (s == 0f) drawContent() else translate(0f, -s.roundToInt().toFloat()) { this@drawWithContent.drawContent() }
        }
        .graphicsLayer()
        .wrapContentHeight(Alignment.Top, unbounded = true)
        .onGloballyPositioned { state.contentBottom = it.positionInRoot().y + it.size.height }

/** Tells the state where the scrolling viewport is (call on the clipping container). */
fun Modifier.focusViewport(state: FocusScreenState): Modifier = onGloballyPositioned {
    val p = it.positionInRoot()
    state.viewportTop = p.y
    state.viewportBottom = p.y + it.size.height
}

/**
 * The on-screen rect (window px) of the item the last OK press clicked. An app launch that follows
 * within a moment takes it to start the app with a scale-up from that item.
 */
object LaunchOrigin {
    private val rect = IntArray(4)
    private var at = 0L

    fun record(x: Float, y: Float, w: Float, h: Float) {
        rect[0] = x.roundToInt(); rect[1] = y.roundToInt(); rect[2] = w.roundToInt(); rect[3] = h.roundToInt()
        at = android.os.SystemClock.uptimeMillis()
    }

    /** The rect as x, y, w, h if a click recorded one in the last second; cleared once taken. */
    fun take(): IntArray? {
        if (at == 0L || android.os.SystemClock.uptimeMillis() - at > 1_000) return null
        at = 0L
        return rect.copyOf()
    }
}
