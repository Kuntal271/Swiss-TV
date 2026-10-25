package com.swissdark.launcher.data

import com.swissdark.launcher.domain.AppId
import com.swissdark.launcher.domain.ContinueItem
import com.swissdark.launcher.domain.ContinueSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropMatchTest {
    private fun res(name: String) = Json.parseToJsonElement(javaClass.classLoader!!.getResource("titles/$name")!!.readText()).jsonObject
    private val blackClover get() = res("tvmaze_show_blackclover.json")
    private val vinland get() = res("tvmaze_show_vinland.json")

    private fun show(id: Int, name: String, weight: Int = 50, genres: List<String> = emptyList()): JsonObject = buildJsonObject {
        put("id", id); put("name", name); put("weight", weight)
        put("genres", buildJsonArray { genres.forEach { add(JsonPrimitive(it)) } })
    }

    private fun item(name: String, pkg: String, key: String = "w:$pkg|x", source: ContinueSource = ContinueSource.WATCHED) =
        ContinueItem(key, null, AppId("$pkg/.Main"), "Svc", name, "", null, "banner:$pkg", source, intentUri = "swiss-watched:x")

    @Test fun norm_ignoresCasePunctuationAndYear() {
        assertEquals("blackclover", BackdropMatch.norm("Black Clover"))
        assertEquals(BackdropMatch.norm("Shōgun"), BackdropMatch.norm("SHŌGUN!"))
        assertEquals("theoffice", BackdropMatch.norm("The Office (2005)"))
    }

    @Test fun pickShow_exactOnly() {
        val hits = listOf(show(1, "Black Clover: Sword of the Wizard King"), show(2, "Black Clover"), show(3, "Clover"))
        assertEquals(2, BackdropMatch.pickShow("black clover", hits, null)?.int("id"))
        assertNull(BackdropMatch.pickShow("Black", hits, null))
        assertNull(BackdropMatch.pickShow("", hits, null))
    }

    @Test fun pickShow_sameName_prefersServiceThenWeight() {
        val hits = listOf(show(1, "Monster", weight = 90), show(2, "Monster", weight = 60, genres = listOf("Anime")))
        assertEquals(2, BackdropMatch.pickShow("Monster", hits, BackdropArt.CRUNCHYROLL)?.int("id"))
        assertEquals(1, BackdropMatch.pickShow("Monster", hits, "com.netflix.ninja")?.int("id"))
    }

    @Test fun pickByEpisode_findsBlackCloverFromEpisodeName() {
        val s = BackdropMatch.pickByEpisode(listOf(vinland, blackClover), "Day of Atonement", null, null)
        assertEquals("Black Clover", s?.str("name"))
        // With the episode number known it must agree (S2 E4 on TVmaze).
        assertNotNull(BackdropMatch.pickByEpisode(listOf(blackClover), "Day of Atonement", 2, 4))
        assertNull(BackdropMatch.pickByEpisode(listOf(blackClover), "Day of Atonement", null, 9))
    }

    @Test fun pickByEpisode_ambiguousOrUnknown_isNull() {
        assertNull(BackdropMatch.pickByEpisode(listOf(vinland), "Day of Atonement", null, null))
        // Two different shows with the same episode name: not confident.
        val twin = buildJsonObject {
            blackClover.forEach { (k, v) -> put(k, v) }
            put("id", 999_999)
        }
        assertNull(BackdropMatch.pickByEpisode(listOf(blackClover, twin), "Day of Atonement", null, null))
        // Same show twice is still one show.
        assertNotNull(BackdropMatch.pickByEpisode(listOf(blackClover, blackClover), "Day of Atonement", null, null))
        // Very short names are never trusted.
        assertNull(BackdropMatch.pickByEpisode(listOf(vinland), "Sw", null, null))
    }

    @Test fun imageFor_prefersBackground() {
        val (url, kind) = BackdropMatch.imageFor(blackClover)!!
        assertEquals(PreparedBackdrop.Kind.BACKGROUND, kind)
        assertTrue(url.startsWith("https://"))
        val posterOnly = buildJsonObject {
            put("id", 1); put("name", "X")
            put("image", buildJsonObject { put("original", "https://img/x.jpg") })
        }
        assertEquals("https://img/x.jpg" to PreparedBackdrop.Kind.POSTER, BackdropMatch.imageFor(posterOnly))
        assertNull(BackdropMatch.imageFor(show(1, "X")))
    }

    @Test fun omdbEpisodeIds_exactTitlesOnly() {
        val res = Json.parseToJsonElement(
            """{"Search":[{"Title":"Day of Atonement","imdbID":"tt1"},{"Title":"The Day of Atonement Part 2","imdbID":"tt2"},{"Title":"day of atonement!","imdbID":"tt3"}],"Response":"True"}""",
        ).jsonObject
        assertEquals(listOf("tt1", "tt3"), BackdropMatch.omdbEpisodeIds(res, "Day of Atonement"))
        assertEquals(emptyList<String>(), BackdropMatch.omdbEpisodeIds(null, "Day of Atonement"))
    }

    @Test fun lookupWorthy_skipsAppOnlyAndYouTube() {
        assertTrue(BackdropMatch.lookupWorthy(item("Black Clover", "com.crunchyroll.crunchyroid")))
        assertFalse(BackdropMatch.lookupWorthy(item("Netflix", "com.netflix.ninja", key = "w:com.netflix.ninja|@app")))
        assertFalse(BackdropMatch.lookupWorthy(item("Some video", WatchedStore.YOUTUBE)))
        assertFalse(BackdropMatch.lookupWorthy(item("Crunchyroll", "com.crunchyroll.crunchyroid", source = ContinueSource.APP)))
    }

    @Test fun boxBlur_flatStaysFlat_andSpreadsSpike() {
        val w = 8; val h = 4
        val flat = IntArray(w * h) { 0xFF336699.toInt() }
        BackdropMatch.boxBlur(flat, w, h, 2)
        assertTrue(flat.all { it == 0xFF336699.toInt() })
        val spike = IntArray(w * h) { 0xFF000000.toInt() }
        spike[1 * w + 4] = 0xFFFFFFFF.toInt()
        BackdropMatch.boxBlur(spike, w, h, 1)
        assertTrue(spike[1 * w + 3] and 0xFF > 0)
        assertTrue(spike[1 * w + 4] and 0xFF < 0xFF)
    }
}

class WebShowNamesTest {
    @org.junit.Test
    fun blackCloverFromSearchTitles() {
        val titles = listOf(
            "Season 2 Day of Atonement - Watch on Crunchyroll",
            "Nacht's Day of Atonement | Black Clover Season 2 Episode 4",
            "Black Clover Season 2, Episode 4 Finally Reveals Nacht's Past ... - CBR",
            "Black Clover Season 2, Episode 4 Release Date & Time",
            "Day of Atonement - Black Clover 2x04 | TVmaze",
        )
        val names = BackdropMatch.showNamesFromResults(titles, "Day of Atonement")
        org.junit.Assert.assertEquals("Black Clover", names.first())
    }
}
