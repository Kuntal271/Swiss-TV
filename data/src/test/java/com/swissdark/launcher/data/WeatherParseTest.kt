package com.swissdark.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherParseTest {
    private val sample = """
        {"latitude":19.75,"longitude":72.75,"timezone":"Asia/Kolkata",
         "current":{"time":"2026-10-26T23:00","interval":900,"temperature_2m":27.4,"weather_code":2},
         "daily_units":{}}
    """.trimIndent()

    @Test
    fun `a normal payload parses`() {
        val w = WeatherParse.parse(sample)!!
        assertEquals(27, w.tempC)
        assertEquals("Partly cloudy", w.text)
    }

    @Test
    fun `broken payloads are null`() {
        assertNull(WeatherParse.parse("not json"))
        assertNull(WeatherParse.parse("{}"))
        assertNull(WeatherParse.parse("\"current\":{}"))
    }

    @Test
    fun `temperatures round to whole degrees`() {
        val hot = "\"current\":{\"temperature_2m\":26.5,\"weather_code\":0}"
        assertEquals(27, WeatherParse.parse("{$hot}")!!.tempC)  // 26.5 rounds to 27 (half up)
    }

    @Test
    fun `weather codes get short words`() {
        assertEquals("Clear", WeatherParse.text(0))
        assertEquals("Cloudy", WeatherParse.text(3))
        assertEquals("Fog", WeatherParse.text(45))
        assertEquals("Drizzle", WeatherParse.text(53))
        assertEquals("Rain", WeatherParse.text(63))
        assertEquals("Snow", WeatherParse.text(73))
        assertEquals("Showers", WeatherParse.text(80))
        assertEquals("Storm", WeatherParse.text(95))
        assertEquals("", WeatherParse.text(-1))
        assertEquals("", WeatherParse.text(42))
    }
}
