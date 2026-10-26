package com.swissdark.launcher.core.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TypeScaleTest {
    @Test
    fun `reading text grows with its line height`() {
        val g = TextStyle(fontSize = 24.sp, lineHeight = 29.sp).atTypeScale(1.3f)
        assertEquals(31.2f, g.fontSize.value, 0.01f)
        assertEquals(37.7f, g.lineHeight.value, 0.01f)
    }

    @Test
    fun `display sizes of 100 sp and up are exempt`() {
        val s = TextStyle(fontSize = 160.sp, lineHeight = 141.sp)
        assertEquals(160f, s.atTypeScale(1.3f).fontSize.value, 0.01f)
        assertEquals(141f, s.atTypeScale(1.3f).lineHeight.value, 0.01f)
    }

    @Test
    fun `scale 1 returns the same style`() {
        val s = TextStyle(fontSize = 24.sp, lineHeight = 29.sp)
        assertSame(s, s.atTypeScale(1f))
    }

    @Test
    fun `an unspecified line height stays unspecified`() {
        val g = TextStyle(fontSize = 24.sp).atTypeScale(1.3f)
        assertEquals(31.2f, g.fontSize.value, 0.01f)
        assertTrue(g.lineHeight.isUnspecified)
    }

    @Test
    fun `em letter spacing is relative and survives as em`() {
        val g = TextStyle(fontSize = 24.sp, letterSpacing = 0.07.em).atTypeScale(1.15f)
        assertEquals(27.6f, g.fontSize.value, 0.01f)
        assertFalse(g.letterSpacing.isUnspecified)
    }

    @Test
    fun `an unspecified font size is left alone`() {
        val s = TextStyle(fontSize = TextUnit.Unspecified)
        assertSame(s, s.atTypeScale(1.3f))
    }
}
