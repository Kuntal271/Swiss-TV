package com.swissdark.launcher.platform.tv

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.swissdark.launcher.domain.ForegroundWatch

/** A foreground launch of an app at a wall-clock time. */
data class LaunchEvent(val packageName: String, val timeMillis: Long)

/** UsageStatsManager.queryEvents. Needs `appops set <pkg> GET_USAGE_STATS allow` (setup.sh). */
class UsageAdapter(private val context: Context) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)

    fun hasAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Distinct app foregrounds (a resume of a different package than the previous one). */
    fun launches(fromMillis: Long, toMillis: Long): List<LaunchEvent> {
        if (!hasAccess()) return emptyList()
        val events = try {
            usm.queryEvents(fromMillis, toMillis)
        } catch (_: SecurityException) {
            return emptyList()
        }
        val out = ArrayList<LaunchEvent>()
        val e = UsageEvents.Event()
        var lastPkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            val pkg = e.packageName
            if (pkg == lastPkg || pkg == context.packageName) continue
            lastPkg = pkg
            out.add(LaunchEvent(pkg, e.timeStamp))
        }
        return out
    }

    /** Last foreground time per package. */
    fun lastUsed(sinceMillis: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (l in launches(sinceMillis, System.currentTimeMillis())) out[l.packageName] = l.timeMillis
        return out
    }

    /**
     * Raw foreground transitions (RESUMED in, PAUSED/STOPPED out) for [ForegroundWatch.totals].
     * The launcher's own events are dropped. Needs the GET_USAGE_STATS appop (setup.sh).
     */
    fun foregroundTransitions(fromMillis: Long, toMillis: Long): List<ForegroundWatch.Event> {
        if (!hasAccess()) return emptyList()
        val events = try {
            usm.queryEvents(fromMillis, toMillis)
        } catch (_: SecurityException) {
            return emptyList()
        }
        val out = ArrayList<ForegroundWatch.Event>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val kind = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> ForegroundWatch.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> ForegroundWatch.PAUSED
                else -> continue
            }
            val pkg = e.packageName
            if (pkg == context.packageName) continue
            out.add(ForegroundWatch.Event(pkg, kind, e.timeStamp))
        }
        return out
    }
}
