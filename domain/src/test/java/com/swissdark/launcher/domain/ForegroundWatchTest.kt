package com.swissdark.launcher.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundWatchTest {
    private val R = ForegroundWatch.RESUMED
    private val P = ForegroundWatch.PAUSED
    private fun e(pkg: String, kind: Int, at: Long) = ForegroundWatch.Event(pkg, kind, at)

    @Test
    fun `one span inside the window`() {
        val m = ForegroundWatch.totals(listOf(e("a", R, 100), e("a", P, 400)), 0, 1_000)
        assertEquals(300L, m.getValue("a").totalMs)
        assertEquals(400L, m.getValue("a").lastEnd)
    }

    @Test
    fun `a span still open at the window end counts up to it`() {
        val m = ForegroundWatch.totals(listOf(e("a", R, 100)), 0, 1_000)
        assertEquals(900L, m.getValue("a").totalMs)
        assertEquals(1_000L, m.getValue("a").lastEnd)
    }

    @Test
    fun `re-resume of the same app continues one span`() {
        val m = ForegroundWatch.totals(listOf(e("a", R, 100), e("a", R, 300), e("a", P, 500)), 0, 1_000)
        assertEquals(400L, m.getValue("a").totalMs)
        assertEquals(500L, m.getValue("a").lastEnd)
    }

    @Test
    fun `two sessions sum and the later pause wins lastEnd`() {
        val m = ForegroundWatch.totals(
            listOf(e("a", R, 100), e("a", P, 200), e("a", R, 400), e("a", P, 700)), 0, 1_000,
        )
        assertEquals(400L, m.getValue("a").totalMs)
        assertEquals(700L, m.getValue("a").lastEnd)
    }

    @Test
    fun `a pause without a resume is ignored`() {
        assertTrue(ForegroundWatch.totals(listOf(e("a", P, 400)), 0, 1_000).isEmpty())
    }

    @Test
    fun `spans clamp to the window on both sides`() {
        val m = ForegroundWatch.totals(listOf(e("a", R, 50), e("a", P, 900)), 100, 800)
        assertEquals(700L, m.getValue("a").totalMs)
        assertEquals(800L, m.getValue("a").lastEnd)
    }

    @Test
    fun `two apps are counted independently`() {
        val m = ForegroundWatch.totals(
            listOf(e("a", R, 100), e("b", R, 200), e("a", P, 300), e("b", P, 600)), 0, 1_000,
        )
        assertEquals(200L, m.getValue("a").totalMs)
        assertEquals(400L, m.getValue("b").totalMs)
    }

    @Test
    fun `empty and inverted windows yield nothing`() {
        assertTrue(ForegroundWatch.totals(listOf(e("a", R, 100), e("a", P, 900)), 500, 500).isEmpty())
        assertTrue(ForegroundWatch.totals(listOf(e("a", R, 900), e("a", P, 1_100)), 0, 500).isEmpty())
    }
}
