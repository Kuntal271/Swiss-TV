package com.swissdark.launcher.data

import com.swissdark.launcher.domain.MediaType
import com.swissdark.launcher.domain.SearchHit
import com.swissdark.launcher.domain.TitleRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OMDb and TVmaze parsing against responses saved from the real APIs (test resources/titles). */
class TitleSourcesParseTest {
    private fun res(name: String) = Json.parseToJsonElement(javaClass.classLoader!!.getResource("titles/$name")!!.readText())

    @Test fun ids_roundTrip() {
        assertEquals(-12004706, TitleIds.fromImdb("tt12004706"))
        assertEquals("tt12004706", TitleIds.imdb(-12004706))
        assertEquals("tt0098904", TitleIds.imdb(TitleIds.fromImdb("tt0098904")!!))
        val m = TitleIds.fromMaze(42155)
        assertEquals(42155, TitleIds.maze(m))
        assertNull(TitleIds.imdb(m))
        assertNull(TitleIds.maze(-12004706))
        assertTrue(TitleIds.isTmdb(1399))
        assertNull(TitleIds.fromImdb("nm123x"))
    }

    @Test fun omdb_search() {
        val hits = OmdbParse.search(res("omdb_search_vinland.json").jsonObject)
        assertTrue(hits.isNotEmpty())
        val v = hits.first()
        assertEquals("Vinland Saga", v.ref.name)
        assertEquals(MediaType.TV, v.ref.type)
        assertEquals(TitleIds.fromImdb("tt10233448"), v.ref.tmdbId)
        assertEquals("Series · 2019", v.meta)
        assertTrue(v.posterUrl!!.startsWith("https://m.media-amazon.com/"))
        assertTrue('@' !in v.posterUrl!! && "%40" in v.posterUrl!!) // Coil reads a raw '@' as user-info
        assertTrue(hits.any { it.ref.type == MediaType.MOVIE })
    }

    @Test fun omdb_details() {
        val d = OmdbParse.details(res("omdb_title_panchayat.json").jsonObject, MediaType.TV)!!
        assertEquals("Panchayat", d.ref.name)
        assertEquals(MediaType.TV, d.ref.type)
        assertEquals("2020", d.year)
        assertEquals(35, d.runtimeMin)
        assertEquals(4, d.seasons)
        assertEquals("TV-14", d.ageRating)
        assertEquals("IMDb 9.0", d.score)
        assertEquals("Hindi", d.language)
        assertEquals(listOf("Comedy", "Drama"), d.genres)
        assertEquals(listOf("Jitendra Kumar", "Raghubir Yadav", "Chandan Roy"), d.cast.map { it.name })
        assertTrue(d.synopsis.startsWith("Panchayat is a comedy-drama"))
        assertNotNull(d.posterUrl)
        assertEquals("OMDb", d.source)
    }

    @Test fun omdb_season() {
        val eps = OmdbParse.season(res("omdb_season_vinland_s2.json").jsonObject, 2)
        assertTrue(eps.size >= 4)
        assertEquals(1, eps.first().number)
        assertEquals("Slave", eps.first().name)
        assertEquals(2, eps.first().season)
    }

    @Test fun tvmaze_search_and_where_to_watch() {
        val arr = res("tvmaze_search_panchayat.json").jsonArray
        val show = arr.first().jsonObject["show"]!!.jsonObject
        val hit = TvMazeParse.hit(show)!!
        assertEquals("Panchayat", hit.ref.name)
        assertEquals(TitleIds.fromImdb("tt12004706"), hit.ref.tmdbId)
        assertEquals("Series · Hindi", hit.meta)
        assertTrue(hit.posterUrl!!.contains("tvmaze.com"))
        val a = TvMazeParse.availability(show)
        assertEquals(listOf("Prime Video"), a.map { it.providerName })
        assertEquals("com.amazon.amazonvideo.livingroom", a.first().packageName)
    }

    @Test fun tvmaze_provider_mapping() {
        assertEquals("netflix", TvMazeParse.provider("Netflix")?.key)
        assertEquals("jiohotstar", TvMazeParse.provider("Disney+")?.key)
        assertEquals("jiohotstar", TvMazeParse.provider("JioHotstar")?.key)
        assertEquals("jiohotstar", TvMazeParse.provider("HBO")?.key)
        assertEquals("crunchyroll", TvMazeParse.provider("Crunchyroll")?.key)
        assertEquals("sonyliv", TvMazeParse.provider("Sony SAB")?.key)
        assertNull(TvMazeParse.provider("Amazon miniTV"))
        assertNull(TvMazeParse.provider("NHK"))
    }

    @Test fun tvmaze_details() {
        val d = TvMazeParse.details(res("tvmaze_show_vinland.json").jsonObject, "2026-10-25")!!
        assertEquals("Vinland Saga", d.ref.name)
        assertEquals(TitleIds.fromImdb("tt10233448"), d.ref.tmdbId)
        assertEquals("2019", d.year)
        assertEquals(30, d.runtimeMin)
        assertTrue(d.seasons!! >= 2)
        assertTrue(d.cast.isNotEmpty() && d.cast.all { it.id < 0 })
        assertNotNull(d.backdropUrl)
        assertTrue(d.synopsis.isNotEmpty() && '<' !in d.synopsis)
        assertTrue(d.nextEpisodes.isNotEmpty())
        val s = d.nextEpisodes.first().season
        assertTrue(d.nextEpisodes.all { it.season == s })
        assertEquals(d.seasons, s)
        assertTrue(d.availability.isEmpty()) // NHK: no Indian service known
    }

    @Test fun tvmaze_latestSeason_ignoresUnaired() {
        val show = res("tvmaze_show_vinland.json").jsonObject
        assertEquals(1, TvMazeParse.latestSeason(show, "2019-07-08").first().season)
    }

    @Test fun tvmaze_schedules() {
        val web = TvMazeParse.scheduleShows(res("tvmaze_schedule_web.json") as JsonArray)
        assertTrue(web.isNotEmpty())
        assertTrue(web.all { it["name"] != null })
        val inn = TvMazeParse.scheduleShows(res("tvmaze_schedule_in.json") as JsonArray)
        assertTrue(inn.isNotEmpty())
        assertEquals(inn.size, inn.distinctBy { (it as JsonObject)["id"] }.size)
    }

    @Test fun merge_joinsOnImdb_exactFirst() {
        val panch = TitleRef(TitleIds.fromImdb("tt12004706")!!, MediaType.TV, "Panchayat")
        val maze = listOf(
            SearchHit.Title(TitleRef(TitleIds.fromMaze(1), MediaType.TV, "Panchayat Elections"), "m1", null, "Series", null, false) to 0.9,
            SearchHit.Title(panch, null, null, "Series · Hindi", null, false) to 0.8,
        )
        val omdb = listOf(
            SearchHit.Title(panch, "omdb-poster", null, "Series · 2020", null, false),
            SearchHit.Title(TitleRef(-111, MediaType.MOVIE, "Panchayat Raj"), null, null, "Film · 1999", null, false),
        )
        val out = TitleSources.merge("panchayat", maze, omdb)
        assertEquals(3, out.size)
        assertEquals("Panchayat", out.first().ref.name)
        assertEquals("omdb-poster", out.first().posterUrl) // TVmaze had none: OMDb's fills in
        assertEquals("Series · Hindi", out.first().meta)
    }
}
