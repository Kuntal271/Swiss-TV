package com.swissdark.launcher.core.perf

import android.app.Activity
import android.util.Log
import android.view.Choreographer
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.metrics.performance.JankStats
import androidx.tracing.Trace

/** Named trace sections; the Perfetto queries in the benchmark module look for these names. */
object Sections {
    /**
     * Async section: from the moment a D-pad key is dispatched to the app until the frame that
     * shows its result has been rendered and submitted (frame commit). This is the real budget:
     * handler work + recomposition + layout + record + RenderThread draw (+ the wait for vsync).
     */
    const val NAV_KEY = "nav.key"
    /** The synchronous part of a D-pad press on the UI thread (lookup, scroll, ring, callbacks). */
    const val NAV_DISPATCH = "nav.dispatch"
    /** The neighbour lookup alone (one array read). */
    const val NAV_LOOKUP = "nav.lookup"
    const val SNAPSHOT_BUILD = "snapshot.build"
    const val SNAPSHOT_READ = "snapshot.read"
    const val WALLPAPER_DECODE = "wallpaper.decode"
    const val SEARCH_LOCAL = "search.local"
    const val HABIT_RANK = "habit.rank"
}

inline fun <T> traced(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}

/**
 * Key-to-frame latency: from the key event's own timestamp to the commit of the frame that draws
 * the result (ViewTreeObserver frame-commit callback, i.e. rendered and handed to the display).
 * Also emits the async trace section [Sections.NAV_KEY] over the same span (from dispatch).
 * One key in flight at a time; fixed-size ring buffer, no allocation per press.
 */
object KeyLatency {
    private const val N = 60
    private val samples = IntArray(N)       // microseconds
    private var count = 0
    private var head = 0
    private var pendingEventNanos = 0L
    private var inFlight = false
    private var cookie = 0
    private var view: View? = null
    val version = mutableIntStateOf(0)

    private val commit = Runnable { finish(System.nanoTime()) }

    // Fallback before attach(): the start of the next frame.
    private val frameStart = Choreographer.FrameCallback { frameNanos -> finish(frameNanos) }

    /** The window's decor view; frame-commit callbacks are registered on it. */
    fun attach(view: View) {
        this.view = view
    }

    /** Call from the key handler with KeyEvent.eventTime (uptime millis). */
    fun onKey(eventUptimeMillis: Long) {
        if (inFlight) return
        inFlight = true
        pendingEventNanos = eventUptimeMillis * 1_000_000L
        cookie++
        Trace.beginAsyncSection(Sections.NAV_KEY, cookie)
        val v = view
        if (v != null && v.isAttachedToWindow) {
            v.viewTreeObserver.registerFrameCommitCallback(commit)
            // Make sure a frame follows even when the press changed nothing (an edge).
            v.invalidate()
        } else {
            Choreographer.getInstance().postFrameCallback(frameStart)
        }
    }

    private fun finish(nowNanos: Long) {
        if (!inFlight) return
        inFlight = false
        Trace.endAsyncSection(Sections.NAV_KEY, cookie)
        val us = ((nowNanos - pendingEventNanos) / 1000L).toInt().coerceAtLeast(0)
        samples[head] = us
        head = (head + 1) % N
        if (count < N) count++
        version.intValue++
    }

    fun last(): Int = if (count == 0) 0 else samples[(head - 1 + N) % N]

    fun percentile(p: Float): Int {
        if (count == 0) return 0
        val copy = samples.copyOf(count)
        copy.sort()
        return copy[((count - 1) * p).toInt()]
    }

    fun forEach(block: (index: Int, us: Int) -> Unit) {
        for (i in 0 until count) block(i, samples[(head - count + i + N) % N])
    }
}

object Jank {
    private var stats: JankStats? = null
    var jankyFrames = 0
        private set

    fun install(activity: Activity) {
        KeyLatency.attach(activity.window.decorView)
        stats = JankStats.createAndTrack(activity.window) { frame ->
            if (frame.isJank) {
                jankyFrames++
                Log.w("SwissPerf", "jank ${frame.frameDurationUiNanos / 1_000_000} ms ${frame.states}")
            }
        }
    }

    fun setTracking(enabled: Boolean) {
        stats?.isTrackingEnabled = enabled
    }
}

/** Debug-only corner overlay with the last 60 key-to-frame times. */
@Composable
fun LatencyOverlay(modifier: Modifier = Modifier) {
    KeyLatency.version.intValue // read: redraw on each sample
    val last = KeyLatency.last() / 1000f
    val p95 = KeyLatency.percentile(0.95f) / 1000f
    val p99 = KeyLatency.percentile(0.99f) / 1000f
    Column(modifier.background(Color(0xCC000000)).padding(12.dp)) {
        BasicText(
            "key→frame  last %.1f  p95 %.1f  p99 %.1f ms  jank %d".format(last, p95, p99, Jank.jankyFrames),
            style = TextStyle(color = Color(0xFFEDEDED), fontSize = 18.sp),
        )
        Canvas(Modifier.size(360.dp, 60.dp)) {
            val w = size.width / 60f
            KeyLatency.forEach { i, us ->
                val ms = us / 1000f
                val h = (ms / 20f).coerceAtMost(1f) * size.height
                val c = if (ms < 10f) Color(0xFF9C9CA3) else if (ms < 16.7f) Color(0xFFF2C230) else Color(0xFFE5484D)
                drawRect(c, Offset(i * w, size.height - h), Size(w - 1f, h))
            }
            // 10 ms budget line
            drawRect(Color(0xFFEDEDED), Offset(0f, size.height * (1f - 10f / 20f)), Size(size.width, 1f))
        }
    }
}

