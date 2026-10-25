package com.swissdark.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WatchedTest {
    @Test fun seasonEpisode() {
        assertEquals(2 to 5, WatchedStore.seasonEpisode("S2 E5 · The Long Night"))
        assertEquals(2 to 5, WatchedStore.seasonEpisode("Show S02E05"))
        assertEquals(1 to 12, WatchedStore.seasonEpisode("Season 1 · Episode 12"))
        assertEquals(null to 7, WatchedStore.seasonEpisode("Episode 7 - Day of Atonement"))
        assertNull(WatchedStore.seasonEpisode("Day of Atonement"))
        assertNull(WatchedStore.seasonEpisode("Big Buck Bunny 60fps 4K"))
    }

    @Test fun norm() {
        assertEquals("blackclover", WatchedStore.norm("Black Clover!"))
    }

    // ---- untitled apps (Netflix, Prime…) ----

    private val NF = "com.netflix.ninja"
    private val PV = "com.amazon.amazonvideo.livingroom"
    private val MIN = 60_000L

    /** Plays for [ms] in 30 s ticks (as the watcher samples), position from [from]. Returns the last verdict. */
    private fun PlaybackGate.play(pkg: String, t0: Long, ms: Long, from: Long, dur: Long, reported: (Long) -> Long = { it }): PlaybackGate.Verdict? {
        var v: PlaybackGate.Verdict? = null
        var t = 0L
        while (true) {
            val pos = from + t
            v = feed(pkg, playing = true, ended = false, reportedPosMs = reported(pos), positionMs = pos, durationMs = dur, atMs = t0 + t)
            if (t >= ms) break
            t = minOf(t + 30_000L, ms)
        }
        return v
    }

    @Test fun netflixHomePreviewsNeverCount() {
        val g = PlaybackGate()
        // Netflix home: preview after preview, no metadata, each from 0 and STOPPED after ~50 s.
        var t = 0L
        repeat(6) {
            assertNull(g.play(NF, t, 50_000L, 0L, 0L))
            assertNull(g.feed(NF, false, true, 0L, 0L, 0L, t + 51_000L))
            t += 60_000L
        }
    }

    @Test fun shortItemsAreTrailersEvenWhenLong() {
        val g = PlaybackGate()
        // A 2:30 trailer with a known duration, played to the end and looped: never a watch.
        assertNull(g.play(PV, 0L, 150_000L, 0L, 150_000L))
        assertNull(g.play(PV, 150_000L, 150_000L, 0L, 150_000L))
    }

    @Test fun realLengthCountsAfterOneMinute() {
        val g = PlaybackGate()
        assertNull(g.play(NF, 0L, 30_000L, 0L, 45 * MIN))
        val v = g.play(NF, 0L, 60_000L, 0L, 45 * MIN)
        assertNotNull(v)
        assertEquals(45 * MIN, v!!.durationMs)
        assertEquals(60_000L, v.positionMs)
    }

    @Test fun resumedMidwayCountsAfterOneMinuteWithoutDuration() {
        val g = PlaybackGate()
        assertNotNull(g.play(NF, 0L, 60_000L, 23 * MIN, 0L))
    }

    @Test fun nothingToGoOnNeedsTwoMinutes() {
        val g = PlaybackGate()
        assertNull(g.play(NF, 0L, 90_000L, 0L, 0L))
        val v = g.play(NF, 90_000L, 30_000L, 90_000L, 0L)
        // 2 min of play, but no duration: a card without a progress bar.
        assertEquals(PlaybackGate.Verdict(0, 0), v)
    }

    @Test fun pausesKeepCountingButStopStartsOver() {
        val g = PlaybackGate()
        g.play(NF, 0L, 40_000L, 0L, 40 * MIN)
        assertNull(g.feed(NF, false, false, 40_000L, 40_000L, 40 * MIN, 41_000L))     // paused
        assertNotNull(g.play(NF, 300_000L, 30_000L, 40_000L, 40 * MIN))                // 70 s in total

        val h = PlaybackGate()
        h.play(NF, 0L, 40_000L, 0L, 40 * MIN)
        assertNull(h.feed(NF, false, true, 0L, 0L, 40 * MIN, 41_000L))                // stopped
        assertNull(h.play(NF, 300_000L, 30_000L, 0L, 40 * MIN))
    }

    @Test fun newItemStartsOver() {
        val g = PlaybackGate()
        g.play(PV, 0L, 50_000L, 0L, 3 * MIN)
        assertNull(g.play(PV, 50_000L, 30_000L, 0L, 50 * MIN))   // trailer then a film: only 30 s of the film
    }

    @Test fun primeStuckPositionGetsNoBar() {
        val g = PlaybackGate()
        // Prime: real duration, but its own position never leaves 0.
        val v = g.play(PV, 0L, 90_000L, 0L, 50 * MIN) { 0L }
        assertEquals(PlaybackGate.Verdict(0, 0), v)
    }

    @Test fun sleepGapIsCapped() {
        val g = PlaybackGate()
        g.feed(NF, true, false, 0L, 0L, 0L, 0L)
        // One sample 3 h later (TV slept while "playing"): counts as 45 s, not 3 h.
        assertNull(g.feed(NF, true, false, 0L, 0L, 0L, 3 * 60 * MIN))
    }

    @Test fun acceptedItemKeepsUpdatingOnStop() {
        val g = PlaybackGate()
        assertNotNull(g.play(NF, 0L, 60_000L, 10 * MIN, 40 * MIN))
        assertNotNull(g.feed(NF, false, true, 0L, 0L, 40 * MIN, 61_000L))
    }
}
