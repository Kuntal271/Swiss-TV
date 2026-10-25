package com.swissdark.launcher.core.design

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.swissdark.launcher.domain.PreparedWallpaper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 600 ms, like the wallpaper slot cross-fade. */
const val FOCUS_BACKDROP_MS = 600
/** Focus must rest this long on a card before its art is loaded/shown. */
const val FOCUS_BACKDROP_DEBOUNCE_MS = 250L

/**
 * The wallpaper, with the focused title's art cross-faded over it ([focusedArt] null = wallpaper).
 *
 * Draw phase only: the two art layers live in one Spacer's drawBehind, which reads the bitmaps
 * and the fade progress; a running fade re-records that one draw op per frame and recomposes
 * nothing. A change while a fade runs starts from the current mix (the old art fades out from
 * wherever it was), so quick D-pad moves never pop.
 */
@Composable
fun FocusBackdrop(wallpaper: PreparedWallpaper?, focusedArt: State<ImageBitmap?>, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val layers = remember { FadeLayers() }
    LaunchedEffect(layers) {
        snapshotFlow { focusedArt.value }.distinctUntilChanged().collectLatest { target ->
            if (target === layers.cur.value) return@collectLatest
            // What is on screen now becomes the outgoing layer, fading from its current alpha.
            if (layers.cur.value != null) {
                layers.prevFrom = layers.t.value
                layers.prev.value = layers.cur.value
            } else {
                layers.prevFrom = layers.prevAlphaNow()
            }
            layers.cur.value = target
            layers.t.snapTo(0f)
            layers.t.animateTo(1f, if (reduce) snap() else tween(FOCUS_BACKDROP_MS, 0, Motion.StandardEasing))
            layers.prev.value = null
        }
    }
    Box(modifier.fillMaxSize()) {
        Backdrop(wallpaper)
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                val dst = IntSize(size.width.toInt(), size.height.toInt())
                val t = layers.t.value
                layers.prev.value?.let { b ->
                    val a = layers.prevFrom * (1f - t)
                    if (a > 0f) drawImage(b, IntOffset.Zero, IntSize(b.width, b.height), IntOffset.Zero, dst, alpha = a, filterQuality = FilterQuality.Low)
                }
                layers.cur.value?.let { b ->
                    if (t > 0f) drawImage(b, IntOffset.Zero, IntSize(b.width, b.height), IntOffset.Zero, dst, alpha = t, filterQuality = FilterQuality.Low)
                }
                // Title art is brighter than our wallpapers: shade the left (clock, hero) and the
                // bottom (rows, key hints) so text stays readable, fading with the art itself.
                val art = if (layers.cur.value != null) t else layers.prevFrom * (1f - t)
                if (art > 0f) {
                    drawRect(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.72f * art),
                            0.55f to Color.Black.copy(alpha = 0.25f * art),
                            1f to Color.Transparent,
                        ),
                    )
                    drawRect(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0.45f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.78f * art),
                            startY = 0f, endY = size.height,
                        ),
                    )
                }
            },
        )
    }
}

private class FadeLayers {
    val prev: MutableState<ImageBitmap?> = mutableStateOf(null)
    val cur: MutableState<ImageBitmap?> = mutableStateOf(null)
    var prevFrom = 0f
    val t = Animatable(1f)
    fun prevAlphaNow(): Float = if (prev.value == null) 0f else prevFrom * (1f - t.value)
}

/**
 * Art for the focused item of [items] (focus index in [focused]; indices ≥ items.size are not
 * items, e.g. app tiles and links → null). Debounced by [FOCUS_BACKDROP_DEBOUNCE_MS]; loads run
 * through [load] (suspending, off main, cached by the provider). The first item is prefetched.
 */
@Composable
fun <T> rememberFocusedArt(focused: IntState, items: List<T>, load: (suspend (T) -> Bitmap?)?): State<ImageBitmap?> {
    val out = remember { mutableStateOf<ImageBitmap?>(null) }
    val last = remember { arrayOfNulls<Bitmap>(1) }
    val loader by rememberUpdatedState(load)
    val list by rememberUpdatedState(items)
    LaunchedEffect(items.firstOrNull()) {
        val first = items.firstOrNull() ?: return@LaunchedEffect
        launch { runCatching { loader?.invoke(first) } }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { focused.intValue to list }.distinctUntilChanged().collectLatest { (i, l) ->
            val item = l.getOrNull(i)
            delay(FOCUS_BACKDROP_DEBOUNCE_MS)
            val bmp = if (item == null) null else runCatching { loader?.invoke(item) }.getOrNull()
            if (bmp !== last[0]) {
                last[0] = bmp
                out.value = bmp?.asImageBitmap()
            }
        }
    }
    return out
}
