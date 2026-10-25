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

    /** An app is found even when hundreds of titles sort before it in the prefix range. */
    @Test
    fun appBeyondManyTitlesIsFound() {
        val tv = AppEntry(AppId("com.tcl.tv/.Main"), "TV", "antenna")
        val many = (0 until 600).map { i -> SearchHit.Title(TitleRef(10_000 + i, MediaType.MOVIE, "Ti $i"), null, null, "Film", null, false) }
        val idx = LocalIndex.build(listOf(tv), emptyList(), many)
        val hits = idx.lookup("t")
        assertEquals("TV", (hits.first() as SearchHit.App).app.label)
        assertTrue(hits.size <= 24)
    }

    /**
     * HLD budget: local search per keystroke < 5 ms. A 300-app index (plus 1,500 seen titles and
     * 50 recent searches) is typed one letter at a time, as on the TV, for many words.
     */
    @Test
    fun keystrokeUnder5msWith300Apps() {
        val rnd = java.util.Random(42)
        val syll = listOf("ka", "ro", "ne", "ti", "vu", "lo", "mi", "sa", "de", "po", "tv", "you", "play")
        fun word() = (1..(2 + rnd.nextInt(3))).joinToString("") { syll[rnd.nextInt(syll.size)] }
        val apps300 = (0 until 300).map { i ->
            val label = (1..(1 + rnd.nextInt(3))).joinToString(" ") { word().replaceFirstChar(Char::uppercase) }
            AppEntry(AppId("pkg.app$i/.Main"), label, "apps")
        }
        val seen = (0 until 1_500).map { i ->
            SearchHit.Title(TitleRef(i, MediaType.MOVIE, (1..3).joinToString(" ") { word() }), null, null, "Film", null, false)
        }
        val recent = (0 until 50).map { SearchHit.Recent(word(), "Title") }
        val idx = LocalIndex.build(apps300, recent, seen)
        assertTrue(idx.size == 1_850)

        val typed = apps300.take(40).map { it.label } + listOf("zzzzzz", "t", "p", "k")
        val keystrokes = typed.flatMap { w -> (1..w.length).map { w.substring(0, it) } }
        repeat(50) { for (q in keystrokes) idx.lookup(q) } // warm the JIT, as a running app would be
        val times = LongArray(keystrokes.size)
        for ((k, q) in keystrokes.withIndex()) {
            val t = System.nanoTime()
            val hits = idx.lookup(q)
            times[k] = System.nanoTime() - t
            assertTrue(hits.size <= 24)
        }
        times.sort()
        val p95 = times[(times.size * 95) / 100]
        val worst = times.last()
        println("LocalIndex 300 apps: ${keystrokes.size} keystrokes, median ${times[times.size / 2] / 1000} µs, p95 ${p95 / 1000} µs, worst ${worst / 1000} µs")
        assertTrue("worst ${worst / 1000} µs", worst < 5_000_000L)
        // The TV CPU is several times slower than a dev machine: keep a 5x margin on p95.
        assertTrue("p95 ${p95 / 1000} µs", p95 < 1_000_000L)
    }

    companion object {
        val words = listOf("alpha", "bravo", "city", "dawn", "echo", "forest", "ghost", "harbour")
    }
}
