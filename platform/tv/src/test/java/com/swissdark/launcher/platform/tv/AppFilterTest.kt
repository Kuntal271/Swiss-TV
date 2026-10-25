package com.swissdark.launcher.platform.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.StringReader

class AppFilterTest {
    private fun parse(xml: String): AppFilter {
        val p = KXmlParser()
        p.setInput(StringReader(xml))
        return AppFilter.parse(p)
    }

    private val sample = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <iconback img1="iconback" />
            <scale factor="0.8" />
            <!-- Netflix -->
            <item component="ComponentInfo{com.netflix.ninja/com.netflix.ninja.MainActivity}" drawable="netflix" />
            <item component="ComponentInfo{com.netflix.mediaclient/com.netflix.mediaclient.ui.launch.UIWebViewActivity}" drawable="netflix_phone" />
            <item component="ComponentInfo{com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity}" drawable="youtube" />
            <item component="ComponentInfo{com.google.android.youtube.tv/com.other.Activity}" drawable="youtube_alt" />
            <item component=":LAUNCHER_ACTION_APP_DRAWER" drawable="drawer" />
            <item component="ComponentInfo{broken}" drawable="broken" />
            <item component="ComponentInfo{com.empty/.Main}" drawable="" />
            <calendar prefix="calendar_" />
        </resources>
    """.trimIndent()

    @Test fun exactComponentWins() {
        val f = parse(sample)
        assertEquals("youtube_alt", f.drawableFor("com.google.android.youtube.tv/com.other.Activity"))
        assertEquals("youtube", f.drawableFor("com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity"))
    }

    @Test fun shortClassNameIsExpanded() {
        val f = parse(sample)
        assertEquals("netflix", f.drawableFor("com.netflix.ninja/.MainActivity"))
    }

    @Test fun fallsBackToPackageWhenActivityDiffers() {
        val f = parse(sample)
        // The TV activity is not in the pack, but the package is: first entry for the package.
        assertEquals("youtube", f.drawableFor("com.google.android.youtube.tv/.SomethingElse"))
    }

    @Test fun unknownAndMalformedAreIgnored() {
        val f = parse(sample)
        assertNull(f.drawableFor("org.unknown/.Main"))
        assertNull(f.drawableFor("not a component"))
        assertNull(f.drawableFor("com.empty/.Main"))   // empty drawable skipped
        assertEquals(4, f.size)
    }

    @Test fun emptyFile() {
        assertTrue(parse("<resources/>").isEmpty)
    }

    @Test fun splitHandlesBothForms() {
        assertEquals("a.b" to "a.b.C", AppFilter.split("ComponentInfo{a.b/.C}"))
        assertEquals("a.b" to "x.Y", AppFilter.split("a.b/x.Y"))
        assertNull(AppFilter.split("ComponentInfo{a.b/}"))
        assertNull(AppFilter.split("/x"))
    }
}
