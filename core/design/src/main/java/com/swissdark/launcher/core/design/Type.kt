package com.swissdark.launcher.core.design

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp

/**
 * Inter (variable: opsz 14–32, wght 100–900), subset to Latin. Two optical sizes from one file:
 * Text (opsz 22) for everything people read at 3 m, Display (opsz 32, tighter) for big headings.
 */
@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int, opsz: Float) = Font(
    R.font.inter_variable,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opsz)),
)

val InterText = FontFamily(inter(400, 22f), inter(500, 22f), inter(600, 22f))
val InterDisplay = FontFamily(inter(700, 32f), inter(800, 32f))

/** cv05: lower-case l with a tail, cv08: upper-case I with serifs — no l / I / 1 confusion. */
private const val FEATURES = "cv05, cv08"
private const val FEATURES_TNUM = "cv05, cv08, tnum"

private val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

private fun style(
    family: FontFamily,
    size: Int,
    weight: Int,
    lineHeight: Float = 1.2f,
    tracking: TextUnit = 0.sp,
    features: String = FEATURES,
) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    lineHeight = (size * lineHeight).sp,
    letterSpacing = tracking,
    fontFeatureSettings = features,
    color = Swiss.Ink,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = tight,
)

private fun display(size: Int, weight: Int, lineHeight: Float = 1.2f, tracking: TextUnit = 0.sp, features: String = FEATURES) =
    style(InterDisplay, size, weight, lineHeight, tracking, features)

private fun text(size: Int, weight: Int, lineHeight: Float = 1.2f, tracking: TextUnit = 0.sp) =
    style(InterText, size, weight, lineHeight, tracking)

/**
 * Comfort text size (Settings › Home › Text size): reading text grows by [LocalTypeScale],
 * layout, tiles and art keep their geometry. Display sizes of 100 sp and up (the clock, the
 * big display type) are exempt — they are not reading text and would overflow their columns.
 */
val LocalTypeScale = staticCompositionLocalOf { 1f }

/** [this] with its font size (and line height) grown by [scale]; unchanged at 1 or ≥ 100 sp. */
fun TextStyle.atTypeScale(scale: Float): TextStyle = when {
    scale == 1f || fontSize.value >= 100f || fontSize.isUnspecified -> this
    else -> copy(
        fontSize = (fontSize.value * scale).sp,
        lineHeight = if (lineHeight.isUnspecified) lineHeight else (lineHeight.value * scale).sp,
    )
}

/** Inter. Nothing is smaller than 24 px: Label, Meta and the small print all read at 3 m. */
object SwissType {
    val Clock = display(160, 800, 0.88f, (-0.03).em, FEATURES_TNUM)
    val ClockPanel = Clock
    val Saver = display(320, 800, 0.88f, (-0.03).em, FEATURES_TNUM)
    val Display120 = display(120, 800, 0.88f, (-0.03).em)
    val Display110 = display(110, 800, 0.88f, (-0.03).em)
    val Query120 = display(120, 700, 1.0f, (-0.025).em)
    val Query96 = display(96, 700, 1.0f, (-0.02).em)
    val Title92 = display(92, 800, 0.92f, (-0.02).em)
    val Title84 = display(84, 800, 0.94f, (-0.02).em)
    val Hero = display(64, 800, 1.0f, (-0.02).em)
    val Row44 = display(44, 700, 1.2f, (-0.01).em)
    val Row36 = display(36, 700, 1.2f, (-0.01).em)
    val Row34 = text(34, 600)
    val Row32 = text(32, 600)
    val Row30 = text(30, 600)
    val Row28 = text(28, 600)
    val Button = text(28, 600)
    val Card = text(28, 600, 1.15f)
    val Tile = text(26, 600)
    val Label = text(24, 500, 1.2f, 0.07.em)
    val Meta = text(24, 500).copy(color = Swiss.Muted)
    val Footer = text(26, 500)
    val Body = text(26, 400, 1.45f).copy(color = Swiss.Muted)
    val Body26 = text(26, 400, 1.45f)
    val Value = text(28, 500)

    // Older names, kept so code written against them still builds. All map onto the ≥ 24 px scale.
    @Deprecated("Card", ReplaceWith("SwissType.Card")) val Card24 = Card
    @Deprecated("Tile", ReplaceWith("SwissType.Tile")) val Tile22 = Tile
    @Deprecated("Tile", ReplaceWith("SwissType.Tile")) val Small20 = Tile
    @Deprecated("Label", ReplaceWith("SwissType.Label")) val Label18 = Label
    @Deprecated("Meta", ReplaceWith("SwissType.Meta")) val Meta22 = Meta
    @Deprecated("Meta", ReplaceWith("SwissType.Meta")) val Meta18 = Meta
    @Deprecated("Meta", ReplaceWith("SwissType.Meta")) val Meta16 = Meta
    @Deprecated("Body", ReplaceWith("SwissType.Body")) val Body22 = Body
    @Deprecated("Body", ReplaceWith("SwissType.Body")) val Body24 = Body
    @Deprecated("Value", ReplaceWith("SwissType.Value")) val Value26 = Value
}
