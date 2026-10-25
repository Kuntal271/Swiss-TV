package com.swissdark.launcher.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ContinueClearTest {
    /** In-memory stand-in for the watched db, launcher history and the Home snapshot. */
    private class Fake : ContinueCleaner.Ops {
        val watched = mutableListOf("com.netflix.ninja|~app", "com.google.android.youtube.tv|bigbuckbunny")
        val history = mutableListOf("tmdb:tv:1399")
        var clearedAt = 0L
        var snapshotUpNext = listOf("a", "b", "c")
        var failWatched = false
        override suspend fun deleteWatched(): Int {
            if (failWatched) error("db locked")
            return watched.size.also { watched.clear() }
        }
        override suspend fun deleteHistory() = history.size.also { history.clear() }
        override fun markCleared(at: Long) { clearedAt = at }
        override suspend fun rebuild() { snapshotUpNext = watched + history }
    }

    @Test fun clearsEverythingAndRebuilds() = runBlocking {
        val f = Fake()
        assertTrue(ContinueCleaner({ true }, f, log = {}).clear("SCREEN_OFF", now = 1000L))
        assertTrue(f.watched.isEmpty())
        assertTrue(f.history.isEmpty())
        assertEquals(1000L, f.clearedAt)
        assertTrue(f.snapshotUpNext.isEmpty())
    }

    @Test fun settingOffDoesNothingUnlessForced() = runBlocking {
        val f = Fake()
        val c = ContinueCleaner({ false }, f, log = {})
        assertFalse(c.clear("SCREEN_OFF"))
        assertEquals(2, f.watched.size)
        assertEquals(3, f.snapshotUpNext.size)
        assertTrue(c.clear("debug", force = true))
        assertTrue(f.snapshotUpNext.isEmpty())
    }

    @Test fun oneFailingStoreStillClearsTheRest() = runBlocking {
        val f = Fake().apply { failWatched = true }
        assertTrue(ContinueCleaner({ true }, f, log = {}).clear("SHUTDOWN", now = 5L))
        assertTrue(f.history.isEmpty())
        assertEquals(5L, f.clearedAt)
    }

    @Test fun watchNextRowsEngagedBeforeTheClearAreHidden() {
        assertFalse(ContinueCleaner.clearedWatchNext(lastEngagement = 500, clearedAt = 0))   // never cleared
        assertTrue(ContinueCleaner.clearedWatchNext(lastEngagement = 500, clearedAt = 1000))
        assertTrue(ContinueCleaner.clearedWatchNext(lastEngagement = 1000, clearedAt = 1000))
        assertFalse(ContinueCleaner.clearedWatchNext(lastEngagement = 1500, clearedAt = 1000)) // watched again
        assertFalse(ContinueCleaner.clearedWatchNext(lastEngagement = 0, clearedAt = 1000))    // unknown
    }

    @Test fun deletesThumbnailFiles() {
        val dir = Files.createTempDirectory("watched").toFile()
        repeat(3) { java.io.File(dir, "w$it-1.jpg").writeText("x") }
        assertEquals(3, ContinueCleaner.deleteFiles(dir))
        assertEquals(0, dir.listFiles()!!.size)
        assertEquals(0, ContinueCleaner.deleteFiles(java.io.File(dir, "missing")))
        dir.delete()
    }
}
