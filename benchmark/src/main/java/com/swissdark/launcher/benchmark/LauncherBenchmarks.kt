package com.swissdark.launcher.benchmark

import android.content.Intent
import android.view.KeyEvent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PKG = "com.swissdark.launcher"

/**
 * Release gates, run on the 55C61B over ADB:
 *   ./gradlew :benchmark:connectedBenchmarkAndroidTest
 * Pass if: cold start median < 700 ms, warm Home median < 120 ms, 0 frames over 16.7 ms while
 * holding Right for 10 s in All apps, per-press frame work p95 < 8 ms / p99 < 10 ms and
 * nav.key (key dispatch to frame commit) p95 < 8 ms + one vsync.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class LauncherBenchmarks {
    @get:Rule val rule = MacrobenchmarkRule()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(PKG)

    @Test
    fun coldStart() = rule.measureRepeated(
        packageName = PKG,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 10,
    ) {
        pressHome()
        startActivityAndWait(homeIntent())
    }

    @Test
    fun warmHomeFromYouTube() = rule.measureRepeated(
        packageName = PKG,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 20,
        setupBlock = {
            device.executeShellCommand("monkey -p com.google.android.youtube.tv -c android.intent.category.LEANBACK_LAUNCHER 1")
            Thread.sleep(3_000)
        },
    ) {
        startActivityAndWait(homeIntent())
    }

    @Test
    fun navigationLatency() {
        KeyToFrameMetric.samples.clear()
        rule.measureRepeated(
            packageName = PKG,
            metrics = listOf(KeyToFrameMetric(), TraceSectionMetric("nav.dispatch", TraceSectionMetric.Mode.Max), FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = { startActivityAndWait(homeIntent()) },
        ) {
            // 200 single presses across Home, at a human pace (one frame + margin apart).
            repeat(50) {
                for (k in intArrayOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT)) {
                    device.pressKeyCode(k)
                    Thread.sleep(60)
                }
            }
        }
        // The release gate: the app's own work per press (handler + UI frame + RenderThread).
        val p95 = KeyToFrameMetric.percentile("frameWorkMs", 0.95)
        val p99 = KeyToFrameMetric.percentile("frameWorkMs", 0.99)
        assertTrue("key frame work p95 $p95 ms (budget 8)", p95 < 8.0)
        assertTrue("key frame work p99 $p99 ms (budget 10)", p99 < 10.0)
        // And the end-to-end: key to frame committed, within one vsync of the work.
        val k2f = KeyToFrameMetric.percentile("navKeyMs", 0.95)
        assertTrue("key to frame commit p95 $k2f ms (budget 8 + one 16.7 ms vsync)", k2f < 8.0 + 16.7)
    }

    @Test
    fun heldKeyScrollAllApps() = rule.measureRepeated(
        packageName = PKG,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 3,
        setupBlock = {
            startActivityAndWait(homeIntent())
            // Footer link "All apps": Down twice, Right once, OK.
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
            Thread.sleep(800)
        },
    ) {
        // Held key ≈ a repeat every 50 ms for 10 s, Right then Down.
        val end = System.currentTimeMillis() + 10_000
        var right = true
        while (System.currentTimeMillis() < end) {
            device.pressKeyCode(if (right) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_DOWN)
            right = !right
            Thread.sleep(50)
        }
    }
}
