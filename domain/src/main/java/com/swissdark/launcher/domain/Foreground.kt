package com.swissdark.launcher.domain

/**
 * Foreground time from UsageStats events, for apps that publish no media session at all
 * (JioHotstar): enough time in the app counts as watching it, and Home shows the app's own
 * card ("Watched X ago"). Pure logic, so it is unit-tested; UsageStatsManager stays in
 * :platform:tv, which feeds [totals] with [Event]s.
 */
object ForegroundWatch {
    /** UsageEvents.Event.ACTIVITY_RESUMED: the app came to the foreground. */
    const val RESUMED = 1
    /** UsageEvents.Event.ACTIVITY_PAUSED / ACTIVITY_STOPPED: it left the foreground. */
    const val PAUSED = 2

    /** One raw usage event. */
    data class Event(val pkg: String, val kind: Int, val at: Long)

    /** Foreground use of one package inside the queried window. */
    data class Use(val pkg: String, val totalMs: Long, val lastEnd: Long)

    /**
     * Foreground totals per package for [events] inside [from]..[to]. Spans are clamped to the
     * window; a resume without a pause keeps the span open — another activity of the same app
     * resuming, or the app still in front at [to] — and an open span counts up to [to].
     * Unmatched pauses (the app was already in front before [from]) are ignored, and packages
     * with no time in the window are left out.
     */
    fun totals(events: List<Event>, from: Long, to: Long): Map<String, Use> {
        if (to <= from) return emptyMap()
        val openSince = HashMap<String, Long>()
        val total = HashMap<String, Long>()
        val lastEnd = HashMap<String, Long>()
        for (e in events.sortedBy { it.at }) when (e.kind) {
            RESUMED -> openSince.putIfAbsent(e.pkg, e.at)
            PAUSED -> {
                val start = openSince.remove(e.pkg) ?: continue
                val span = minOf(e.at, to) - maxOf(start, from)
                if (span > 0) total[e.pkg] = (total[e.pkg] ?: 0L) + span
                lastEnd[e.pkg] = minOf(e.at, to)
            }
            else -> {}
        }
        // Still in front at the end of the window: count up to [to] and close.
        for (pkg in openSince.keys.toList()) {
            val start = openSince.remove(pkg)!!
            val span = to - maxOf(start, from)
            if (span > 0) total[pkg] = (total[pkg] ?: 0L) + span
            lastEnd[pkg] = to
        }
        val out = HashMap<String, Use>(total.size)
        for ((pkg, t) in total) out[pkg] = Use(pkg, t, lastEnd[pkg] ?: to)
        return out
    }
}
