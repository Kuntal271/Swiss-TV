package com.swissdark.launcher.core.design

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val Archivo = FontFamily(
    Font(R.font.archivo_regular, FontWeight.Normal),
    Font(R.font.archivo_medium, FontWeight.Medium),
    Font(R.font.archivo_extrabold, FontWeight.ExtraBold),
)

private val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

private fun style(size: Int, weight: FontWeight, lineHeight: Float = 1.2f, tracking: TextUnit = 0.sp) = TextStyle(
    fontFamily = Archivo,
    fontSize = size.sp,
    fontWeight = weight,
    lineHeight = (size * lineHeight).sp,
    letterSpacing = tracking,
    color = Swiss.Ink,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = tight,
)

/** Archivo, two weights. Label 22 caps is the smallest text, readable at 3 m. */
object SwissType {
    val Clock = style(160, FontWeight.ExtraBold, 0.85f, (-7).sp)
    val ClockPanel = style(160, FontWeight.ExtraBold, 0.85f, (-6).sp)
    val Saver = style(320, FontWeight.ExtraBold, 0.8f, (-14).sp)
    val Display120 = style(120, FontWeight.ExtraBold, 0.85f, (-4).sp)
    val Display110 = style(110, FontWeight.ExtraBold, 0.85f, (-4).sp)
    val Query120 = style(120, FontWeight.ExtraBold, 1.0f, (-4).sp)
    val Query96 = style(96, FontWeight.ExtraBold, 1.0f, (-3).sp)
    val Title92 = style(92, FontWeight.ExtraBold, 0.9f, (-3).sp)
    val Title84 = style(84, FontWeight.ExtraBold, 0.92f, (-3).sp)
    val Hero = style(60, FontWeight.ExtraBold, 0.95f, (-2).sp)
    val Row44 = style(44, FontWeight.ExtraBold)
    val Row36 = style(36, FontWeight.ExtraBold)
    val Row34 = style(34, FontWeight.ExtraBold)
    val Row32 = style(32, FontWeight.ExtraBold)
    val Row30 = style(30, FontWeight.ExtraBold)
    val Button = style(28, FontWeight.ExtraBold)
    val Row28 = style(28, FontWeight.ExtraBold)
    val Card = style(26, FontWeight.ExtraBold, 1.15f)
    val Card24 = style(24, FontWeight.ExtraBold, 1.15f)
    val Tile22 = style(22, FontWeight.ExtraBold)
    val Small20 = style(20, FontWeight.ExtraBold)
    val Label = style(22, FontWeight.Medium, 1.2f, 1.sp)
    val Label18 = style(18, FontWeight.Medium, 1.2f, 1.sp)
    val Meta = style(20, FontWeight.Medium).copy(color = Swiss.Muted)
    val Meta18 = style(18, FontWeight.Medium).copy(color = Swiss.Muted)
    val Meta16 = style(16, FontWeight.Medium).copy(color = Swiss.Muted)
    val Footer = style(24, FontWeight.Medium)
    val Body22 = style(22, FontWeight.Normal, 1.5f).copy(color = Swiss.Muted)
    val Body24 = style(24, FontWeight.Normal, 1.5f).copy(color = Swiss.Muted)
    val Body26 = style(26, FontWeight.Normal, 1.45f)
    val Meta22 = style(22, FontWeight.Medium).copy(color = Swiss.Muted)
    val Value26 = style(26, FontWeight.Medium)

    @Suppress("unused") val Em = 1.em
}
