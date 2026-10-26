package com.swissdark.launcher.core.design

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import com.swissdark.launcher.core.focus.FocusScreenState
import com.swissdark.launcher.core.focus.FocusStyle
import com.swissdark.launcher.core.focus.focusItem
import com.swissdark.launcher.domain.AppArt
import com.swissdark.launcher.domain.PreparedWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

val LocalAppArt = staticCompositionLocalOf<AppArt?> { null }

// ---------- rules ----------

fun Modifier.topRule(width: Dp = 2.dp, color: Color = Swiss.Rule) = drawBehind {
    drawRect(color, Offset.Zero, Size(size.width, width.toPx()))
}

fun Modifier.bottomRule(width: Dp = 2.dp, color: Color = Swiss.Rule) = drawBehind {
    val w = width.toPx()
    drawRect(color, Offset(0f, size.height - w), Size(size.width, w))
}

fun Modifier.leftRule(width: Dp = 2.dp, color: Color = Swiss.Rule) = drawBehind {
    drawRect(color, Offset.Zero, Size(width.toPx(), size.height))
}

// ---------- text ----------

@Composable
fun T(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified, maxLines: Int = Int.MAX_VALUE, align: TextAlign? = null) {
    val scale = LocalTypeScale.current
    val scaled = remember(style, scale) { style.atTypeScale(scale) }
    BasicText(
        text,
        modifier,
        style = if (align != null) scaled.copy(textAlign = align) else scaled,
        color = { if (color == Color.Unspecified) scaled.color else color },
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Text whose colour is read in the draw phase only: a change re-draws, never recomposes or measures. */
@Composable
fun TDraw(text: String, style: TextStyle, color: ColorProducer, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val scale = LocalTypeScale.current
    val scaled = remember(style, scale) { style.atTypeScale(scale) }
    BasicText(text, modifier, style = scaled, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Label 22 caps. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Swiss.Muted, style: TextStyle = SwissType.Label) {
    T(text.uppercase(Locale.ROOT), style, modifier, color, maxLines = 1)
}

// ---------- page frame ----------

/**
 * Wallpaper (already darkened at decode). Screens that want it darker pass [totalDim]: the extra
 * dim is a colour filter on the wallpaper's own draw (multiply), not a full-screen scrim layer,
 * so the GPU fills the screen once instead of twice.
 */
@Composable
fun Backdrop(wallpaper: PreparedWallpaper?, totalDim: Float? = null, modifier: Modifier = Modifier) {
    val extra = if (totalDim != null && wallpaper != null) {
        // Wallpaper is pre-darkened by d; multiply the rest so the total matches the design.
        val d = wallpaper.dim
        if (d >= 1f) 0f else (1f - (1f - totalDim) / (1f - d)).coerceIn(0f, 1f)
    } else 0f
    val filter = remember(extra) {
        if (extra <= 0f) null else {
            val k = 1f - extra
            ColorFilter.lighting(multiply = Color(k, k, k), add = Color.Black)
        }
    }
    Box(modifier.fillMaxSize().background(Swiss.Ground)) {
        Crossfade(wallpaper, animationSpec = tween(600), label = "wallpaper") { wp ->
            val bmp = wp?.bitmap
            if (bmp != null) {
                val img = remember(bmp) { bmp.asImageBitmap() }
                Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, colorFilter = filter)
            }
        }
    }
}

/** 72 / 120 margins, 36 between rows. */
@Composable
fun Frame(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().padding(horizontal = Swiss.MARGIN_X.dp, vertical = Swiss.MARGIN_Y.dp),
        verticalArrangement = Arrangement.spacedBy(36.dp),
        content = content,
    )
}

@Composable
fun Header(a: String, b: String, c: String, d: String) {
    Row(Modifier.fillMaxWidth().bottomRule().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(Swiss.GUTTER.dp)) {
        Label(a, Modifier.width(Swiss.span(3)), Swiss.Ink)
        Label(b, Modifier.width(Swiss.span(3)))
        Label(c, Modifier.width(Swiss.span(3)))
        Box(Modifier.width(Swiss.span(3)), contentAlignment = Alignment.CenterEnd) { Label(d, color = Swiss.Ink) }
    }
}

data class KeyHint(val key: String, val action: String)

@Composable
fun Footer(hint: String, keys: List<KeyHint>, modifier: Modifier = Modifier) {
    // Same bar as Home: key hints first on the left, the focused item's name muted on the right.
    Row(modifier.fillMaxWidth().topRule().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            for (k in keys) KeyHintText(k)
        }
        T(hint, SwissType.Footer, Modifier.weight(1f).padding(start = 48.dp), Swiss.Muted, maxLines = 1, align = TextAlign.End)
    }
}

/** "OK Resume": the key name in ink, what it does muted. */
@Composable
fun KeyHintText(k: KeyHint) {
    Row {
        T(k.key, SwissType.Footer, color = Swiss.Ink, maxLines = 1)
        T(" " + k.action, SwissType.Footer, color = Swiss.Muted, maxLines = 1)
    }
}

// ---------- pieces ----------

/** Track + fill. The fill animates to its value (from 0 on first show) in the draw phase. */
@Composable
fun ProgressBar(fraction: Float, fill: Color, modifier: Modifier = Modifier) {
    val f = rememberFill(fraction)
    Spacer(
        modifier.fillMaxWidth().height(6.dp).drawBehind {
            drawRect(Swiss.Track)
            drawRect(fill, Offset.Zero, Size(size.width * f.value, size.height))
        },
    )
}

/** Animated 0..1 fill: from 0 on first composition, then from the old value to the new one. */
@Composable
internal fun rememberFill(target: Float): State<Float> {
    val t = target.coerceIn(0f, 1f)
    val reduce = LocalReduceMotion.current
    val a = remember { Animatable(if (reduce) t else 0f) }
    LaunchedEffect(t) {
        if (reduce) a.snapTo(t) else a.animateTo(t, tween(Motion.LONG, Motion.STAGGER * 2, Motion.EnterEasing))
    }
    return a.asState()
}

/**
 * Focus scale for an item marked focusItem(scale = true): a canvas scale around the centre, read
 * in the draw phase from the focus state, so it moves on the ring's own progress and never
 * touches layout (focus rects stay unscaled; the ring adds the scale itself).
 */
@Composable
fun Modifier.focusScaled(state: FocusScreenState, index: Int): Modifier {
    if (LocalReduceMotion.current) return this.graphicsLayer()
    val sc = remember(state, index) { state.scaleState(index) }
    return this.graphicsLayer().drawWithContent {
        val s = sc.value
        if (s == 1f) drawContent() else scale(s) { this@drawWithContent.drawContent() }
    }.graphicsLayer()
}

@Composable
fun Glyph(id: String, size: Dp = Glyphs.SIZE, color: Color = Swiss.Ink, modifier: Modifier = Modifier) {
    Image(rememberVectorPainter(Glyphs.get(id)), null, modifier.size(size), colorFilter = ColorFilter.tint(color))
}

/** App tile: outline icon over a label, square edges, translucent panel. */
@Composable
fun IconTile(
    state: FocusScreenState,
    index: Int,
    glyph: String,
    label: String,
    height: Dp,
    modifier: Modifier = Modifier,
    style: TextStyle = SwissType.Tile,
    iconSize: Dp = 47.dp,
    scrolling: Boolean = false,
    badge: String? = null,
    outline: Boolean = false,
) {
    // Focus is read only while drawing (glyph tint, label colour) inside the tile's own layer:
    // a D-pad press re-records two small layers and recomposes nothing.
    val focused = remember(state, index) { state.isFocused(index) }
    val color = remember(focused) { ColorProducer { if (focused.value) Swiss.Focus else Swiss.Ink } }
    val reduce = LocalReduceMotion.current
    Column(
        modifier
            .height(height)
            .focusItem(state, index, FocusStyle.RING, scrolling, scale = !reduce)
            .focusScaled(state, index)
            .border(1.dp, Swiss.Track)
            .background(Swiss.TileFill)
            .then(if (outline) Modifier.drawBehind { drawInsetBorder(3.dp.toPx(), Swiss.Focus) } else Modifier)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val painter = rememberVectorPainter(Glyphs.get(glyph))
            Spacer(
                Modifier.size(iconSize).drawBehind {
                    with(painter) { draw(size, colorFilter = if (focused.value) FocusTint else InkTint) }
                },
            )
            if (badge != null) Label(badge, color = Swiss.Ink, style = SwissType.Label)
        }
        TDraw(label, style, color, maxLines = 1)
    }
}

private val InkTint = ColorFilter.tint(Swiss.Ink)
private val FocusTint = ColorFilter.tint(Swiss.Focus)

/** Package name to Tabler glyph id (provided by the app from AppGlyphs); unknown apps get the generic glyph. */
val LocalAppGlyph = staticCompositionLocalOf<(String) -> String> { { "apps" } }

/** Stand-in art for an app without a TV banner: its outline glyph centred on a tile, as in IconTile. */
@Composable
fun AppGlyphArt(glyph: String, modifier: Modifier = Modifier) {
    val painter = rememberVectorPainter(Glyphs.get(glyph))
    Box(
        modifier
            .background(Swiss.TileFill)
            .border(1.dp, Swiss.Track)
            .drawBehind {
                val g = size.minDimension * 0.4f
                translate((size.width - g) / 2f, (size.height - g) / 2f) {
                    with(painter) { draw(Size(g, g), colorFilter = InkTint) }
                }
            },
    )
}

/** Icon pack chosen in Launcher settings ("" = the apps' own logos); part of every tile art key. */
val LocalIconPack = staticCompositionLocalOf { "" }

/** Tile art is decoded once at this size (16:9) and scaled by the GPU to each screen's tile. */
const val TILE_ART_W = 320
const val TILE_ART_H = 180

/**
 * An app's 16:9 art: its TV banner, else its logo on its brand colour (see AppArt.tile), else
 * its outline glyph. Cache hits draw on the first frame; misses decode off the main thread and
 * fade in.
 */
@Composable
fun AppTileArt(component: String, glyph: String, modifier: Modifier = Modifier) {
    val art = LocalAppArt.current
    val pack = LocalIconPack.current
    var bmp by remember(component, pack) { mutableStateOf(art?.cachedTile(component, TILE_ART_W, TILE_ART_H, pack)) }
    var missing by remember(component, pack) { mutableStateOf(art == null) }
    val cached = remember(component, pack) { bmp != null }
    if (bmp == null && !missing) {
        LaunchedEffect(component, pack) {
            val b = withContext(Dispatchers.IO) { runCatching { art?.tile(component, TILE_ART_W, TILE_ART_H, pack) }.getOrNull() }
            if (b == null) missing = true else bmp = b
        }
    }
    val b = bmp
    when {
        b != null -> {
            val img = remember(b) { b.asImageBitmap() }
            val fade = rememberAppear(if (cached) Unit else b, if (cached) 0 else Motion.MEDIUM)
            Image(
                img, null,
                modifier.background(Swiss.Placeholder).graphicsLayer { alpha = if (cached) 1f else fade.value },
                contentScale = ContentScale.Crop,
            )
        }
        missing -> AppGlyphArt(glyph, modifier)
        else -> Box(modifier.background(Swiss.Placeholder))
    }
}

/**
 * App tile: the app's art (16:9) with its name below. Focus = the shared ring around the art and
 * a 1.04 scale; the name turns amber, as on the cards. [badge] sits on the art's corner.
 */
@Composable
fun AppTile(
    state: FocusScreenState,
    index: Int,
    component: String,
    glyph: String,
    label: String,
    modifier: Modifier = Modifier,
    scrolling: Boolean = false,
    badge: String? = null,
    outline: Boolean = false,
) {
    val focused = remember(state, index) { state.isFocused(index) }
    val color = remember(focused) { ColorProducer { if (focused.value) Swiss.Focus else Swiss.Ink } }
    val reduce = LocalReduceMotion.current
    Column(modifier.graphicsLayer(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .focusItem(state, index, FocusStyle.RING, scrolling, scale = !reduce)
                .focusScaled(state, index),
        ) {
            AppTileArt(component, glyph, Modifier.fillMaxSize())
            if (outline) Spacer(Modifier.fillMaxSize().drawBehind { drawInsetBorder(4.dp.toPx(), Swiss.Focus) })
            if (badge != null) {
                T(
                    badge.uppercase(Locale.ROOT), SwissType.Label,
                    Modifier.align(Alignment.TopEnd).background(Swiss.Ground).padding(horizontal = 10.dp, vertical = 4.dp),
                    Swiss.Ink, maxLines = 1,
                )
            }
        }
        TDraw(label, SwissType.Tile, color, maxLines = 1)
    }
}

/** Artwork from a URL, a local file, or an app banner ("banner:<package>"). */
@Composable
fun Art(key: String?, width: Int, height: Int, modifier: Modifier = Modifier) {
    when {
        key == null -> Box(modifier.background(Swiss.Placeholder))
        key.startsWith("banner:") -> {
            val art = LocalAppArt.current
            val pkg = key.removePrefix("banner:")
            val bmp = remember(pkg, width, height) { art?.banner(pkg, width, height)?.asImageBitmap() }
            if (bmp != null) Image(bmp, null, modifier.background(Swiss.Placeholder), contentScale = ContentScale.Crop)
            else AppGlyphArt(LocalAppGlyph.current(pkg), modifier)
        }
        else -> {
            val ctx = LocalContext.current
            val reduce = LocalReduceMotion.current
            val req = remember(key, width, height, reduce) {
                // Network art fades in when it arrives; memory-cache hits draw at once (Coil skips the fade).
                ImageRequest.Builder(ctx).data(key).size(width, height).crossfade(if (reduce) 0 else Motion.MEDIUM).build()
            }
            AsyncImage(req, null, modifier.background(Swiss.Placeholder), contentScale = ContentScale.Crop)
        }
    }
}

/** Poster or thumbnail card: the ring surrounds the image only; the title turns amber. */
@Composable
fun PosterCard(
    state: FocusScreenState,
    index: Int,
    art: String?,
    imageHeight: Dp,
    title: String,
    meta: String?,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    titleStyle: TextStyle = SwissType.Card,
    metaStyle: TextStyle = SwissType.Meta,
    gap: Dp = 12.dp,
    decodeW: Int = 480,
    decodeH: Int = 270,
    scrolling: Boolean = false,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    // Focus is read only while drawing, inside the card's own layer (see IconTile).
    val focused = remember(state, index) { state.isFocused(index) }
    val color = remember(focused) { ColorProducer { if (focused.value) Swiss.Focus else Swiss.Ink } }
    val reduce = LocalReduceMotion.current
    Column(modifier.graphicsLayer(), verticalArrangement = Arrangement.spacedBy(gap)) {
        Column(Modifier.fillMaxWidth().focusItem(state, index, FocusStyle.RING, scrolling, scale = !reduce).focusScaled(state, index)) {
            Art(art, decodeW, decodeH, Modifier.fillMaxWidth().height(imageHeight))
            if (progress != null) {
                val f = rememberFill(progress)
                Spacer(
                    Modifier.fillMaxWidth().height(6.dp).drawBehind {
                        drawRect(Swiss.Track)
                        drawRect(color(), Offset.Zero, Size(size.width * f.value, size.height))
                    },
                )
            }
        }
        TDraw(title, titleStyle, color, maxLines = 2)
        if (meta != null) T(meta, metaStyle, maxLines = 1)
        extra?.invoke(this)
    }
}

/** Filled ink square = on your services; hollow = not. (Yellow is kept for focus.) */
@Composable
fun ServiceDot(text: String, onMyServices: Boolean, style: TextStyle = SwissType.Meta) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(12.dp).then(if (onMyServices) Modifier.background(Swiss.Ink) else Modifier.border(2.dp, Swiss.Muted)))
        T(text, style, color = if (onMyServices) Swiss.Ink else Swiss.Muted, maxLines = 1)
    }
}

// ---------- focus language for text controls ----------
//
// Images and tiles show focus with the shared 4 px ring and a 1.04 scale. Text controls (nav
// buttons, buttons, options, list rows) show it as a solid yellow fill with black text: buttons
// scale 1.04, full-width rows 1.02. The fill and the text colour follow the focus scale's own
// progress (FocusScreenState.focusFraction), read in the draw phase only: a D-pad press
// re-draws two small layers and recomposes nothing. Selected-but-not-focused = ink text and a
// 10 px square marker.

/** Focus scale of a full-width list row. */
const val ROW_SCALE = 1.02f

/** Draw-phase colours of a text control: they turn black as the yellow fill comes in. */
@androidx.compose.runtime.Stable
class FocusInk internal constructor(private val f: State<Float>) {
    /** 0..1, how focused the control is. Read it in the draw phase only. */
    val fraction: Float get() = f.value
    val ink = ColorProducer { lerp(Swiss.Ink, Swiss.Ground, f.value) }
    val muted = ColorProducer { lerp(Swiss.Muted, Swiss.Ground, f.value) }
}

@Composable
fun rememberFocusInk(state: FocusScreenState, index: Int): FocusInk =
    remember(state, index) { FocusInk(state.fractionState(index)) }

/**
 * A text control's focus: registers the item (FocusStyle.SELF), scales it by [scale] around its
 * centre and fills it yellow as it gains focus. All draw phase.
 */
@Composable
fun Modifier.focusFill(
    state: FocusScreenState,
    index: Int,
    ink: FocusInk,
    scale: Float = Motion.FOCUS_SCALE,
    scrolling: Boolean = false,
    /** Drawn under the fill (inside the scale), e.g. a primary button's own block. */
    base: Color = Color.Transparent,
): Modifier {
    val reduce = LocalReduceMotion.current
    return this
        .focusItem(state, index, FocusStyle.SELF, scrolling, scale = !reduce && scale != 1f, scaleAmount = scale)
        .focusScaled(state, index)
        .drawBehind {
            val a = ink.fraction
            if (base.alpha > 0f && a < 1f) drawRect(base)
            if (a > 0f) drawRect(Swiss.Focus, alpha = a)
        }
}

/** The 10 px square that marks the selected option. */
@Composable
fun SelectedMarker(color: ColorProducer, modifier: Modifier = Modifier) {
    Spacer(modifier.size(10.dp).drawBehind { drawRect(color()) })
}

/** A glyph whose tint is read in the draw phase. */
@Composable
fun GlyphDraw(id: String, color: ColorProducer, modifier: Modifier = Modifier, size: Dp = Glyphs.SIZE) {
    val painter = rememberVectorPainter(Glyphs.get(id))
    Spacer(
        modifier.size(size).drawBehind {
            with(painter) { draw(this@drawBehind.size, colorFilter = tintOf(color())) }
        },
    )
}

private val InkFilter = ColorFilter.tint(Swiss.Ink)
private val GroundFilter = ColorFilter.tint(Swiss.Ground)

private fun tintOf(c: Color): ColorFilter = when (c) {
    Swiss.Ink -> InkFilter
    Swiss.Ground -> GroundFilter
    else -> ColorFilter.tint(c)
}

/**
 * Top navigation button: icon + label, 60 px tall, square corners. Unfocused = ink on nothing;
 * focused = solid yellow, black icon and text, scale 1.04 (same timing as the tiles).
 */
@Composable
fun NavChip(state: FocusScreenState, index: Int, glyph: String, label: String, modifier: Modifier = Modifier) {
    val ink = rememberFocusInk(state, index)
    Row(
        modifier.height(60.dp).focusFill(state, index, ink).padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlyphDraw(glyph, ink.ink, size = 34.dp)
        TDraw(label, SwissType.Button, ink.ink, maxLines = 1)
    }
}

/**
 * A row between rules. Focus = the whole row fills yellow with black text and grows 1.02;
 * [selected] (e.g. the current section) = a 10 px ink marker before the content.
 * [content] gets the row's draw-phase colours.
 */
@Composable
fun RuleRow(
    state: FocusScreenState,
    index: Int,
    modifier: Modifier = Modifier,
    bottomRule: Boolean = false,
    selected: Boolean = false,
    scrolling: Boolean = false,
    vertical: Dp = 22.dp,
    content: @Composable RowScope.(FocusInk) -> Unit,
) {
    val ink = rememberFocusInk(state, index)
    var m = modifier.fillMaxWidth().topRule()
    if (bottomRule) m = m.bottomRule()
    Row(
        m.focusFill(state, index, ink, ROW_SCALE, scrolling).padding(horizontal = 24.dp, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            SelectedMarker(ink.ink)
            Spacer(Modifier.width(16.dp))
        }
        content(ink)
    }
}

/**
 * Text button in a group (tabs, filters, menu items). Focused = yellow fill, black text, 1.04.
 * [selected] = ink text and a 10 px marker; others [unselected]. Set [marker] false for plain
 * action buttons that are never "selected" (no space is kept for the marker).
 */
@Composable
fun TabText(
    state: FocusScreenState,
    index: Int,
    text: String,
    selected: Boolean,
    style: TextStyle = SwissType.Row34,
    unselected: Color = Swiss.Muted,
    @Suppress("UNUSED_PARAMETER") underline: Dp = 4.dp,
    marker: Boolean = true,
) {
    val ink = rememberFocusInk(state, index)
    val base = if (selected) Swiss.Ink else unselected
    val color = remember(ink, base) { ColorProducer { lerp(base, Swiss.Ground, ink.fraction) } }
    Row(
        Modifier.focusFill(state, index, ink).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (marker) {
            if (selected) SelectedMarker(ink.ink) else Spacer(Modifier.size(10.dp))
            Spacer(Modifier.width(12.dp))
        }
        TDraw(text, style, color, maxLines = 1)
    }
}

/** The main action. Unfocused = ink block, black text; focused = yellow block, 1.04. */
@Composable
fun PrimaryButton(state: FocusScreenState, index: Int, text: String) {
    val ink = rememberFocusInk(state, index)
    TDraw(
        text, SwissType.Button, ButtonText,
        Modifier.focusFill(state, index, ink, base = Swiss.Ink).padding(horizontal = 32.dp, vertical = 22.dp),
        maxLines = 1,
    )
}

private val ButtonText = ColorProducer { Swiss.Ground }

/** A secondary action. Unfocused = 2 px ink outline; focused = yellow block, black text, 1.04. */
@Composable
fun OutlineButton(state: FocusScreenState, index: Int, text: String) {
    val ink = rememberFocusInk(state, index)
    TDraw(
        text, SwissType.Button, ink.ink,
        Modifier.focusFill(state, index, ink).drawBehind { if (ink.fraction < 1f) drawInsetBorder(2.dp.toPx(), Swiss.Ink) }
            .padding(horizontal = 28.dp, vertical = 20.dp),
        maxLines = 1,
    )
}

/** Same pixels as Modifier.border(w, c) with a rectangle shape, but the colour is read at draw time. */
private fun DrawScope.drawInsetBorder(w: Float, c: Color) {
    drawRect(c, Offset(w / 2f, w / 2f), Size(size.width - w, size.height - w), style = Stroke(w))
}

@Composable
fun Chip(state: FocusScreenState, index: Int, text: String) {
    val ink = rememberFocusInk(state, index)
    TDraw(
        text, ChipStyle, ink.ink,
        Modifier.focusFill(state, index, ink)
            .drawBehind { if (ink.fraction < 1f) drawInsetBorder(2.dp.toPx(), Swiss.Rule) }
            .padding(horizontal = 22.dp, vertical = 12.dp),
        maxLines = 1,
    )
}
private val ChipStyle = SwissType.Card.copy(lineHeight = SwissType.Card.fontSize)

/** Grey square with an initial, for people without a photo. */
@Composable
fun Initial(letter: String, modifier: Modifier, style: TextStyle = SwissType.Card) {
    Box(modifier.background(Swiss.Placeholder), contentAlignment = Alignment.Center) {
        T(letter.take(1).uppercase(), style, color = Swiss.Muted)
    }
}

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

// ---------- time ----------

/** Current time, updated on each minute boundary. */
@Composable
fun rememberMinute(): State<LocalDateTime> {
    val now = remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val t = LocalDateTime.now()
            now.value = t
            delay(((60 - t.second) * 1000L - t.nano / 1_000_000).coerceAtLeast(200))
        }
    }
    return now
}

object Fmt {
    val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val headerDate: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd.MM.yyyy", Locale.UK)
    val longDate: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
}
