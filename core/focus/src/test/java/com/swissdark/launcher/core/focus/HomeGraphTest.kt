package com.swissdark.launcher.core.focus

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Home's real geometry at 1920 x 1080 (design px): Up next cards in the left 7 columns, a 2 x 3
 * app grid in the right 5, four footer links bottom right. Indices follow HomeScreen: cards,
 * then apps, then links.
 */
class HomeGraphTest {
    private val col = (1920f - 240f - 11 * 32f) / 12f
    private fun span(n: Int) = col * n + 32f * (n - 1)
    private val appsX = 120f + span(7) + 32f
    private val tileW = (span(5) - 32f) / 3f

    private fun home(nUp: Int, dy: Float = 0f, footerDy: Float = 0f): FloatArray {
        val r = ArrayList<Float>()
        val cardW = (span(7) - 48f) / 3f
        for (i in 0 until nUp) r += listOf(120f + i * (cardW + 24f), 680f + dy, cardW, 156f)
        for (row in 0 until 2) for (c in 0 until 3) r += listOf(appsX + c * (tileW + 16f), 680f + row * 128f + dy, tileW, 112f)
        val links = listOf(1332f to 76f, 1457f to 87f, 1593f to 68f, 1710f to 89f)
        for ((x, w) in links) r += listOf(x, 972f + footerDy, w, 38f)
        return r.toFloatArray()
    }

    private fun check(nUp: Int, dy: Float = 0f, footerDy: Float = 0f) {
        val rects = home(nUp, dy, footerDy)
        val n = rects.size / 4
        val g = FocusGraphBuilder.build(rects, n)
        val l0 = nUp + 6
        // Footer LEFT/RIGHT stay in the footer.
        for (k in 0 until 4) {
            assertEquals("right of link $k", if (k < 3) l0 + k + 1 else -1, g.right[l0 + k])
            assertEquals("left of link $k", if (k > 0) l0 + k - 1 else -1, g.left[l0 + k])
        }
        // Bottom-row tiles go DOWN to the nearest footer link.
        for (c in 0 until 3) {
            val t = nUp + 3 + c
            val tcx = rects[t * 4] + rects[t * 4 + 2] / 2
            val nearest = (0 until 4).minByOrNull { k -> kotlin.math.abs(rects[(l0 + k) * 4] + rects[(l0 + k) * 4 + 2] / 2 - tcx) }!!
            assertEquals("down of tile $c", l0 + nearest, g.down[t])
        }
        // The right-hand tiles do not jump RIGHT down into the footer.
        assertEquals(-1, g.right[nUp + 2]); assertEquals(-1, g.right[nUp + 5])
        // Grid LEFT reaches the Up next cards when there are any.
        if (nUp > 0) assertEquals(nUp - 1, g.left[nUp])
        // Footer UP returns to the grid's bottom row.
        for (k in 0 until 4) {
            val up = g.up[l0 + k]
            assert(up in nUp + 3 until nUp + 6) { "up of link $k = $up" }
        }
    }

    @Test fun withUpNext() = check(3)
    @Test fun emptyUpNext() = check(0)
    @Test fun midEntranceOffsets() = check(3, dy = 16f, footerDy = 8f)
}
