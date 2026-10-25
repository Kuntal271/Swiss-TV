package com.swissdark.launcher.data

import com.swissdark.launcher.domain.AppEntry
import com.swissdark.launcher.domain.AppId
import com.swissdark.launcher.domain.MediaType
import com.swissdark.launcher.domain.SearchHit
import com.swissdark.launcher.domain.TitleRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The local index must answer any 1–6 letter query in under 5 ms with ~2,000 entries. */
class LocalIndexTest {
    private val apps = listOf("YouTube", "YouTube Kids", "YouTube Music", "Netflix", "Prime Video", "JioHotstar", "VLC", "Crunchyroll")
        .map { AppEntry(AppId("pkg.${it.lowercase().replace(' ', '.')}/.Main"), it, "apps") }
    private val titles = (0 until 1_990).map { i ->
        SearchHit.Title(TitleRef(i, if (i % 2 == 0) MediaType.MOVIE else MediaType.TV, "Title $i ${words[i % words.size]} night"), null, null, "Film", null, false)
    }
    private val index = LocalIndex.build(apps, listOf(SearchHit.Recent("you", "App")), titles)

    @Test
    fun prefixFindsApps() {
        val hits = index.lookup("you")
        assertEquals("YouTube", (hits.first() as SearchHit.App).app.label)
        assertTrue(hits.filterIsInstance<SearchHit.App>().size == 3)
    }

    @Test
    fun wordStartFindsKids() {
        val hits = index.lookup("kids")
        assertTrue(hits.any { it is SearchHit.App && it.app.label == "YouTube Kids" })
    }

    @Test
    fun under5msWorstCase() {
        val queries = listOf("n", "ni", "nig", "nigh", "night", "nights", "t", "ti", "tit", "y", "yo", "you", "zzzzzz", "p", "pr")
        repeat(200) { for (q in queries) index.lookup(q) } // warm the JIT
        var worst = 0L
        for (q in queries) {
            val t = System.nanoTime()
            index.lookup(q)
            worst = maxOf(worst, System.nanoTime() - t)
        }
        assertTrue("worst ${worst / 1000} µs", worst < 5_000_000L)
    }

    companion object {
        val words = listOf("alpha", "bravo", "city", "dawn", "echo", "forest", "ghost", "harbour")
    }
}
