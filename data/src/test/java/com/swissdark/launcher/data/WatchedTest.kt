package com.swissdark.launcher.data

import org.junit.Assert.assertEquals
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
}
