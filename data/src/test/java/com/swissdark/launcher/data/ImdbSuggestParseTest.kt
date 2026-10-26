package com.swissdark.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ImdbSuggestParse against a saved shape of IMDb's public suggestion endpoint. */
class ImdbSuggestParseTest {
    private val sample = """
        {"d":[
         {"i":{"height":1499,"imageUrl":"https://m.media-amazon.com/images/M/a._V1_.jpg","width":1000},
          "id":"tt6282412","l":"Come Together: A Fashion Picture in Motion","q":"short","qid":"short","rank":21849,"y":2016},
         {"i":{"height":755,"imageUrl":"https://m.media-amazon.com/images/M/b._V1_.jpg","width":509},
          "id":"tt1411238","l":"No Strings Attached","q":"feature","qid":"movie","rank":3293,"y":2011}
        ]}
    """.trimIndent()

    @Test
    fun `parses the hits`() {
        val hits = ImdbSuggestParse.parse(sample)
        assertEquals(2, hits.size)
        assertEquals("No Strings Attached", hits[1].title)
        assertEquals("movie", hits[1].kind)
        assertEquals(2011, hits[1].year)
    }

    @Test
    fun `the exactly matching real title wins`() {
        assertEquals("tt1411238", ImdbSuggestParse.best(ImdbSuggestParse.parse(sample), "No Strings Attached")!!.id)
    }

    @Test
    fun `near misses and non title kinds are rejected`() {
        assertNull(ImdbSuggestParse.best(ImdbSuggestParse.parse(sample), "No Strings"))
        assertNull(ImdbSuggestParse.best(ImdbSuggestParse.parse(sample), "Come Together: A Fashion Picture in Motion"))
        assertNull(ImdbSuggestParse.best(ImdbSuggestParse.parse(sample), "ab"))
        assertNull(ImdbSuggestParse.best(emptyList(), "anything"))
    }

    @Test
    fun `broken json yields nothing`() {
        assertEquals(0, ImdbSuggestParse.parse("nope").size)
        assertEquals(0, ImdbSuggestParse.parse("{}").size)
    }
}
