package com.swissdark.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** update.json parsing and the newer-than-me rule. */
class UpdateMetaTest {
    private val good = """{"versionCode": 430600, "versionName": "2026.10.27-1200",
        "url": "https://github.com/Kuntal271/Swiss-TV/releases/download/v430600/app-release.apk",
        "sha256": "abc123"}"""

    @Test fun newerIsOffered() {
        val m = Updates.Meta.parse(good, 430599)!!
        assertEquals(430600, m.versionCode)
        assertEquals("2026.10.27-1200", m.versionName)
        assertEquals("abc123", m.sha256)
    }

    @Test fun sameOrOlderIsNull() {
        assertNull(Updates.Meta.parse(good, 430600))
        assertNull(Updates.Meta.parse(good, 430601))
    }

    @Test fun malformedIsNull() {
        assertNull(Updates.Meta.parse("not json", 0))
        assertNull(Updates.Meta.parse("""{"versionName": "x", "url": "https://x"}""", 0))
        assertNull(Updates.Meta.parse("""{"versionCode": 5, "versionName": "x", "url": "http://insecure"}""", 0))
    }

    @Test fun missingChecksumIsAllowed() {
        val m = Updates.Meta.parse("""{"versionCode": 9, "versionName": "x", "url": "https://a/b.apk"}""", 5)!!
        assertEquals(null, m.sha256)
    }
}
