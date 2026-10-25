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
import com.swissdark.launcher.core.focus.FocusScreenState
import com.swissdark.launcher.core.focus.FocusStyle
import com.swissdark.launcher.core.focus.focusItem
import com.swissdark.launcher.domain.AppArt
import com.swissdark.launcher.domain.PreparedWallpaper
import kotlinx.coroutines.delay
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
    BasicText(
        text,
        modifier,
        style = if (align != null) style.copy(textAlign = align) else style,
        color = { if (color == Color.Unspecified) style.color else color },
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Label 22 caps. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Swiss.Muted, style: TextStyle = SwissType.Label) {
    T(text.uppercase(Locale.ROOT), style, modifier, color, maxLines = 1)
}

// ---------- page frame ----------

/** Wallpaper (already darkened at decode) with an optional extra scrim for non-Home screens. */
@Composable
fun Backdrop(wallpaper: PreparedWallpaper?, totalDim: Float? = null, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Swiss.Ground)) {
        Crossfade(wallpaper, animationSpec = tween(600), label = "wallpaper") { wp ->
            val bmp = wp?.bitmap
            if (bmp != null) {
                val img = remember(bmp) { bmp.asImageBitmap() }
                Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        if (totalDim != null && wallpaper != null) {
            // Wallpaper is pre-darkened by d; add a scrim so the total matches the design.
            val d = wallpaper.dim
            val extra = if (d >= 1f) 0f else (1f - (1f - totalDim) / (1f - d)).coerceIn(0f, 1f)
            if (extra > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = extra)))
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
        Box(Modifier.width(Swiss.span(3)), contentAlignment = Alignment.CenterEnd) { Label(d, color = Swiss.Focus) }
    }
}

class KeyHint(val key: String, val action: String)

@Composable
fun Footer(hint: String, keys: List<KeyHint>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().topRule().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        T(hint, SwissType.Footer, Modifier.weight(1f), Swiss.Muted, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            for (k in keys) Row {
                T(k.key, SwissType.Footer, color = Swiss.Focus)
                T(" " + k.action, SwissType.Footer)
            }
        }
    }
}

// ---------- pieces ----------

@Composable
fun ProgressBar(fraction: Float, fill: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).background(Swiss.Track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(fill))
    }
}

@Composable
fun Glyph(id: String, size: Dp, color: Color, modifier: Modifier = Modifier) {
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
    style: TextStyle = SwissType.Tile22,
    iconSize: Dp = 47.dp,
    scrolling: Boolean = false,
    badge: String? = null,
) {
    val focused by remember(state, index) { state.isFocused(index) }
    val c = if (focused) Swiss.Focus else Swiss.Ink
    Column(
        modifier
            .height(height)
            .focusItem(state, index, FocusStyle.RING, scrolling)
            .border(1.dp, Swiss.Track)
            .background(Swiss.TileFill)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Glyph(glyph, iconSize, c)
            if (badge != null) Label(badge, color = Swiss.Focus, style = SwissType.Label18)
        }
        T(label, style, color = c, maxLines = 1)
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
            else Box(modifier.background(Swiss.Placeholder))
        }
        else -> {
            val ctx = LocalContext.current
            val req = remember(key, width, height) {
                ImageRequest.Builder(ctx).data(key).size(width, height).build()
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
    val focused by remember(state, index) { state.isFocused(index) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        Column(Modifier.fillMaxWidth().focusItem(state, index, FocusStyle.RING, scrolling)) {
            Art(art, decodeW, decodeH, Modifier.fillMaxWidth().height(imageHeight))
            if (progress != null) ProgressBar(progress, if (focused) Swiss.Focus else Swiss.Ink)
        }
        T(title, titleStyle, color = if (focused) Swiss.Focus else Swiss.Ink, maxLines = 2)
        if (meta != null) T(meta, metaStyle, maxLines = 1)
        extra?.invoke(this)
    }
}

/** Amber square = on your services; grey = not. */
@Composable
fun ServiceDot(text: String, onMyServices: Boolean, style: TextStyle = SwissType.Meta18) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).background(if (onMyServices) Swiss.Focus else Swiss.Muted))
        T(text, style, color = if (onMyServices) Swiss.Ink else Swiss.Muted, maxLines = 1)
    }
}

/** A row between rules. Focus = a 4 px amber rule on top and amber text (the row restyles itself). */
@Composable
fun RuleRow(
    state: FocusScreenState,
    index: Int,
    modifier: Modifier = Modifier,
    bottomRule: Boolean = false,
    selected: Boolean = false,
    scrolling: Boolean = false,
    vertical: Dp = 22.dp,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    val focused by remember(state, index) { state.isFocused(index) }
    val amber = focused || selected
    var m = modifier.fillMaxWidth().focusItem(state, index, FocusStyle.SELF, scrolling)
    m = if (amber) m.topRule(4.dp, Swiss.Focus) else m.topRule()
    if (bottomRule) m = m.bottomRule()
    Row(m.padding(vertical = vertical), verticalAlignment = Alignment.CenterVertically) { content(focused) }
}

/** Text tab with an underline: selected = underline, focused = amber. */
@Composable
fun TabText(
    state: FocusScreenState,
    index: Int,
    text: String,
    selected: Boolean,
    style: TextStyle = SwissType.Row34,
    unselected: Color = Swiss.Muted,
    underline: Dp = 4.dp,
) {
    val focused by remember(state, index) { state.isFocused(index) }
    val color = when {
        focused -> Swiss.Focus
        selected -> Swiss.Ink
        else -> unselected
    }
    var m = Modifier.focusItem(state, index, FocusStyle.SELF)
    if (selected || focused) m = m.bottomRule(underline, if (focused) Swiss.Focus else Swiss.Ink)
    T(text, style, m.padding(vertical = 4.dp), color, maxLines = 1)
}

@Composable
fun PrimaryButton(state: FocusScreenState, index: Int, text: String) {
    T(
        text, SwissType.Button,
        Modifier.focusItem(state, index).background(Swiss.Focus).padding(horizontal = 32.dp, vertical = 22.dp),
        Swiss.Ground, maxLines = 1,
    )
}

@Composable
fun OutlineButton(state: FocusScreenState, index: Int, text: String) {
    val focused by remember(state, index) { state.isFocused(index) }
    val c = if (focused) Swiss.Focus else Swiss.Ink
    T(
        text, SwissType.Button,
        Modifier.focusItem(state, index).border(2.dp, c).padding(horizontal = 28.dp, vertical = 20.dp),
        c, maxLines = 1,
    )
}

@Composable
fun Chip(state: FocusScreenState, index: Int, text: String) {
    val focused by remember(state, index) { state.isFocused(index) }
    val c = if (focused) Swiss.Focus else Swiss.Ink
    T(
        text, SwissType.Card24.copy(lineHeight = SwissType.Card24.fontSize),
        Modifier.focusItem(state, index).border(2.dp, if (focused) Swiss.Focus else Swiss.Rule).padding(horizontal = 22.dp, vertical = 12.dp),
        c, maxLines = 1,
    )
}

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
