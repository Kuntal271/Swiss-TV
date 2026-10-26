package com.swissdark.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * Dumps every Continue watching row to logcat (tag `WatchedDump`) for remote debugging:
 * `adb shell am broadcast -a com.swissdark.launcher.WATCHED_DUMP -p com.swissdark.launcher`
 * then `adb logcat -d -s WatchedDump`. DUMP is held by the shell, not by apps.
 */
class WatchedDumpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as LauncherApp).graph
        val pending = goAsync()
        graph.scope.launch {
            try {
                for (row in graph.watched.recent(60)) {
                    android.util.Log.i(
                        "WatchedDump",
                        "key=${row.key} show=${row.show} display=${row.displayName} ep=${row.episodeTitle} " +
                            "s=${row.season} e=${row.episode} series=${row.series} " +
                            "pos=${row.positionMs}/${row.durationMs} art=${row.art?.takeLast(12)} " +
                            "tmdb=${row.tmdbId}/${row.tmdbType} tmdbArt=${row.tmdbArt?.takeLast(16)} upd=${row.updatedAt}",
                    )
                }
                android.util.Log.i("WatchedDump", "dump complete")
            } finally {
                pending.finish()
            }
        }
    }
}
