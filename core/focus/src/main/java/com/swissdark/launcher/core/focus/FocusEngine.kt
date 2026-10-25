package com.swissdark.launcher.core.focus

import androidx.compose.runtime.IntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.tracing.Trace
import com.swissdark.launcher.core.perf.Sections

enum class DpadKey { UP, DOWN, LEFT, RIGHT }

/** Precomputed neighbours. -1 = edge. Rects are x, y, w, h in px, content coordinates. */
class FocusGraph(val n: Int) {
    val up = IntArray(n) { -1 }
    val down = IntArray(n) { -1 }
    val left = IntArray(n) { -1 }
    val right = IntArray(n) { -1 }
    val rects = FloatArray(n * 4)

    companion object {
        val EMPTY = FocusGraph(0)
    }
}

interface FocusEngine {
    val focused: IntState                          // the only state a D-pad press writes
    fun onKey(key: DpadKey, repeat: Boolean): Boolean
    fun bind(graph: FocusGraph)
}

class ArrayFocusEngine(initial: Int = 0) : FocusEngine {
    private val state = mutableIntStateOf(initial)
    override val focused: IntState get() = state
    var graph: FocusGraph = FocusGraph.EMPTY
        private set

    override fun bind(graph: FocusGraph) {
        this.graph = graph
        if (graph.n > 0 && state.intValue >= graph.n) state.intValue = graph.n - 1
    }

    fun set(index: Int) {
        state.intValue = index
    }

    override fun onKey(key: DpadKey, repeat: Boolean): Boolean {
        Trace.beginSection(Sections.NAV_LOOKUP)
        val g = graph
        val cur = state.intValue
        val next = if (cur < 0 || cur >= g.n) -1 else when (key) {
            DpadKey.UP -> g.up[cur]
            DpadKey.DOWN -> g.down[cur]
            DpadKey.LEFT -> g.left[cur]
            DpadKey.RIGHT -> g.right[cur]
        }
        if (next >= 0) state.intValue = next   // the only write
        Trace.endSection()
        return next >= 0
    }
}

/**
 * Builds the four neighbour tables from item rectangles, once per layout.
 * Candidates that overlap the current item's row (or column) win; among them the nearest.
 */
object FocusGraphBuilder {
    fun build(rects: FloatArray, n: Int, groups: IntArray? = null): FocusGraph {
        val g = FocusGraph(n)
        rects.copyInto(g.rects, 0, 0, n * 4)
        for (i in 0 until n) {
            g.up[i] = best(rects, n, i, DpadKey.UP, groups)
            g.down[i] = best(rects, n, i, DpadKey.DOWN, groups)
            g.left[i] = best(rects, n, i, DpadKey.LEFT, groups)
            g.right[i] = best(rects, n, i, DpadKey.RIGHT, groups)
        }
        return g
    }

    private fun best(r: FloatArray, n: Int, i: Int, dir: DpadKey, groups: IntArray?): Int {
        val ix = r[i * 4]; val iy = r[i * 4 + 1]; val iw = r[i * 4 + 2]; val ih = r[i * 4 + 3]
        if (iw <= 0f && ih <= 0f) return -1
        val icx = ix + iw / 2f; val icy = iy + ih / 2f
        var bestBeam = -1; var bestBeamScore = Float.MAX_VALUE
        var bestAny = -1; var bestAnyScore = Float.MAX_VALUE
        for (j in 0 until n) {
            if (j == i) continue
            val jx = r[j * 4]; val jy = r[j * 4 + 1]; val jw = r[j * 4 + 2]; val jh = r[j * 4 + 3]
            if (jw <= 0f && jh <= 0f) continue
            val jcx = jx + jw / 2f; val jcy = jy + jh / 2f
            val primary: Float
            val overlap: Float
            val orthoCenter: Float
            when (dir) {
                DpadKey.RIGHT -> {
                    if (jcx <= icx + 1f || jx < ix + iw / 2f) continue
                    primary = (jx - (ix + iw)).coerceAtLeast(0f)
                    overlap = minOf(iy + ih, jy + jh) - maxOf(iy, jy)
                    orthoCenter = kotlin.math.abs(jcy - icy)
                }
                DpadKey.LEFT -> {
                    if (jcx >= icx - 1f || jx + jw > ix + iw / 2f) continue
                    primary = (ix - (jx + jw)).coerceAtLeast(0f)
                    overlap = minOf(iy + ih, jy + jh) - maxOf(iy, jy)
                    orthoCenter = kotlin.math.abs(jcy - icy)
                }
                DpadKey.DOWN -> {
                    if (jcy <= icy + 1f || jy < iy + ih / 2f) continue
                    primary = (jy - (iy + ih)).coerceAtLeast(0f)
                    overlap = minOf(ix + iw, jx + jw) - maxOf(ix, jx)
                    orthoCenter = kotlin.math.abs(jcx - icx)
                }
                DpadKey.UP -> {
                    if (jcy >= icy - 1f || jy + jh > iy + ih / 2f) continue
                    primary = (iy - (jy + jh)).coerceAtLeast(0f)
                    overlap = minOf(ix + iw, jx + jw) - maxOf(ix, jx)
                    orthoCenter = kotlin.math.abs(jcx - icx)
                }
            }
            val sameGroup = groups == null || groups[i] == groups[j]
            if (overlap > 0f) {
                // In the beam: nearest first, then best aligned. Same group gets a small bonus.
                val s = primary * 4f + orthoCenter + if (sameGroup) 0f else 200f
                if (s < bestBeamScore) { bestBeamScore = s; bestBeam = j }
            } else {
                val gap = -overlap
                // LEFT/RIGHT never jump to another row that is further away than the smaller of the
                // two items is tall (e.g. a footer link must not jump up into the tile grid).
                if ((dir == DpadKey.LEFT || dir == DpadKey.RIGHT) && gap > minOf(ih, jh)) continue
                val s = primary + gap * 3f + orthoCenter * 0.5f
                if (s < bestAnyScore) { bestAnyScore = s; bestAny = j }
            }
        }
        return if (bestBeam >= 0) bestBeam else bestAny
    }
}
