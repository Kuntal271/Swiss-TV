package com.swissdark.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeParseTest {
    private val html = """
        ..."videoRenderer":{"videoId":"N4kHwxWDTAA","thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/N4kHwxWDTAA/hqdefault.jpg"}]},"title":{"runs":[{"text":"I Pranked Samay Raina With A Fake Show"}]}}
        ..."videoRenderer":{"videoId":"Z4aMdO8K0XE","thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/Z4aMdO8K0XE/hq720.jpg?sqp=x&rs=y"}]},"title":{"runs":[{"text":"INDIA’S GOT LATENT S2 EP8 ft. MC Stan, Tanmay Bhat"}]}}
    """.trimIndent()

    @Test fun firstMatchingTitle() {
        val hit = YouTubeParse.firstMatch(html, "INDIA’S GOT LATENT S2 EP8 ft. MC Stan, Tanmay Bhat")!!
        assertEquals("Z4aMdO8K0XE", hit.id)
        assertEquals("https://i.ytimg.com/vi/Z4aMdO8K0XE/hq720.jpg", hit.thumb)
    }

    @Test fun truncatedSessionTitle() {
        assertEquals("Z4aMdO8K0XE", YouTubeParse.firstMatch(html, "INDIA'S GOT LATENT S2 EP8 ft.")?.id)
    }

    @Test fun noMatch() {
        assertNull(YouTubeParse.firstMatch(html, "Something else entirely"))
        assertEquals("https://i.ytimg.com/vi/N4kHwxWDTAA/mqdefault.jpg", YouTubeParse.results(html)[0].thumb)
    }
}
