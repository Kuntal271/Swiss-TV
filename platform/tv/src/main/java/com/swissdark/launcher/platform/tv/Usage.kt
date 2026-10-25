package com.swissdark.launcher.platform.tv

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

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
}
