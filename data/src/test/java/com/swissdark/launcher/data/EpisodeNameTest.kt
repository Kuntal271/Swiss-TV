package com.swissdark.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** WatchedStore.episodeName: titles that lead with season/episode numbering. */
class EpisodeNameTest {
    @Test
    fun `S E prefix strips into the episode name`() {
        assertEquals(Triple(2, 14, "Bixby's Back"), WatchedStore.episodeName("S2 E14 Bixby's Back"))
        assertEquals(Triple(2, 14, "Pilot"), WatchedStore.episodeName("S02E14 - Pilot"))
        assertEquals(Triple(2, 14, "Bixby's Back"), WatchedStore.episodeName("Season 2 · Episode 14 · Bixby's Back"))
        assertEquals(Triple(null, 14, "Bixby's Back"), WatchedStore.episodeName("E14 Bixby's Back"))
        assertEquals(Triple(null, 7, "the box"), WatchedStore.episodeName("episode 7 the box"))
        assertEquals(Triple(1, 5, "night"), WatchedStore.episodeName("s1e5 night"))
    }

    @Test
    fun `plain titles and bare numbering stay as they are`() {
        assertNull(WatchedStore.episodeName("Bixby's Back"))
        assertNull(WatchedStore.episodeName("S2 E14"))
        assertNull(WatchedStore.episodeName("Episode"))
        assertNull(WatchedStore.episodeName(""))
        assertNull(WatchedStore.episodeName("S16 E1 X"))   // a one-letter name is not a name
    }
}
