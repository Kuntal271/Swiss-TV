package com.swissdark.launcher.benchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceMetric
import androidx.benchmark.perfetto.ExperimentalPerfettoTraceProcessorApi
import androidx.benchmark.perfetto.PerfettoTraceProcessor

/**
 * Real D-pad latency from the trace, per key press:
 * - navKeyMs: the app's async "nav.key" section, from key dispatch to the commit of the frame
 *   that shows the result (see KeyLatency in core/perf).
 * - keyToFrameMs: independent of app instrumentation: each key-down "deliverInputEvent" on the
 *   app's main thread to the end of the next RenderThread DrawFrame.
 * - frameWorkMs: the app's own work for that frame: main-thread Choreographer#doFrame plus
 *   RenderThread DrawFrame (excludes the wait for vsync). This is the 8 ms budget.
 *
 * Each value is reported as a sampled metric (P50/P90/P95/P99 in the benchmark output) and
 * also collected into [samples] so the test can assert on percentiles.
 */
@OptIn(ExperimentalMetricApi::class, ExperimentalPerfettoTraceProcessorApi::class)
class KeyToFrameMetric : TraceMetric() {
    override fun getMeasurements(captureInfo: CaptureInfo, traceSession: PerfettoTraceProcessor.Session): List<Measurement> {
        val pkg = captureInfo.targetPackageName
        val proc = "process.name LIKE '$pkg%'"
        val nav = traceSession.query(
            """
            SELECT slice.dur AS dur FROM slice
            JOIN process_track ON slice.track_id = process_track.id
            JOIN process USING(upid)
            WHERE slice.name = 'nav.key' AND $proc
            """.trimIndent(),
        ).map { it.long("dur") / 1e6 }.toList()

        fun threadSlices(nameLike: String, mainThread: Boolean?): List<LongArray> = traceSession.query(
            """
            SELECT slice.ts AS ts, slice.dur AS dur FROM slice
            JOIN thread_track ON slice.track_id = thread_track.id
            JOIN thread USING(utid) JOIN process USING(upid)
            WHERE slice.name LIKE '$nameLike' AND $proc
            ${when (mainThread) { true -> "AND thread.tid = process.pid"; false -> "AND thread.name = 'RenderThread'"; null -> "" }}
            ORDER BY slice.ts
            """.trimIndent(),
        ).map { longArrayOf(it.long("ts"), it.long("dur")) }.toList()

        // Key down and key up each deliver an input event; the down comes first.
        val inputs = threadSlices("deliverInputEvent%", true).filterIndexed { i, _ -> i % 2 == 0 }
        val frames = threadSlices("Choreographer#doFrame%", true)
        val draws = threadSlices("DrawFrame%", false)
        val k2f = ArrayList<Double>(); val work = ArrayList<Double>()
        for (inp in inputs) {
            val t = inp[0]
            val f = frames.firstOrNull { it[0] > t } ?: continue
            val d = draws.firstOrNull { it[0] >= f[0] } ?: continue
            k2f += (d[0] + d[1] - t) / 1e6
            work += (inp[1] + f[1] + d[1]) / 1e6
        }
        synchronized(samples) {
            samples.getOrPut("navKeyMs") { ArrayList() }.addAll(nav)
            samples.getOrPut("keyToFrameMs") { ArrayList() }.addAll(k2f)
            samples.getOrPut("frameWorkMs") { ArrayList() }.addAll(work)
        }
        return listOf(
            Measurement("navKeyMs", nav.ifEmpty { listOf(0.0) }),
            Measurement("keyToFrameMs", k2f.ifEmpty { listOf(0.0) }),
            Measurement("frameWorkMs", work.ifEmpty { listOf(0.0) }),
        )
    }

    companion object {
        /** All iterations' samples by metric name (cleared by the test before a run). */
        val samples = HashMap<String, ArrayList<Double>>()

        fun percentile(name: String, p: Double): Double {
            val v = synchronized(samples) { samples[name]?.sorted() }.orEmpty()
            if (v.isEmpty()) return Double.NaN
            return v[((v.size - 1) * p).toInt().coerceIn(0, v.size - 1)]
        }
    }
}
