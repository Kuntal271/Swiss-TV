package com.swissdark.launcher.platform.tv

import android.content.Context
import android.database.ContentObserver
import android.media.tv.TvContract
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

data class WatchNextRow(
    val id: Long,
    val packageName: String,
    val title: String,
    val episodeTitle: String?,
    val season: String?,
    val episode: String?,
    val positionMs: Long,
    val durationMs: Long,
    val artUri: String?,
    val intentUri: String?,
    val lastEngagement: Long,
)

/**
 * TvContract.WatchNextPrograms. TvProvider returns only the caller's own rows unless it holds
 * the signature permission ACCESS_ALL_EPG_DATA, so for a sideloaded launcher Watch Next is
 * system-only. Once that is established the adapter stops querying and stops observing: every
 * app that updates its Watch Next row (YouTube does so during playback) would otherwise wake
 * the launcher and rebuild Home for nothing. Home falls back to its own history.
 */
class WatchNextAdapter(private val context: Context) {
    sealed interface Result {
        data class Rows(val rows: List<WatchNextRow>) : Result
        data class Closed(val why: String) : Result
    }

    private val main = Handler(Looper.getMainLooper())
    private val allEpg: Boolean by lazy {
        context.checkSelfPermission(ACCESS_ALL_EPG_DATA) == PackageManager.PERMISSION_GRANTED
    }

    /** Set once reading is known to be pointless for this process's lifetime. */
    @Volatile private var closedFor: String? = null
    @Volatile private var observer: ContentObserver? = null

    fun read(limit: Int = 12): Result {
        closedFor?.let { return Result.Closed(it) }
        val r = query(limit)
        if (r is Result.Closed) {
            // Refused or missing provider: never going to work. Empty without the system
            // permission: only our own rows are visible, and we publish none.
            val permanent = r.why.startsWith("security") || r.why == "no provider" || (r.why == "empty" && !allEpg)
            if (permanent) close(if (r.why == "empty") "system-only (empty, no ACCESS_ALL_EPG_DATA)" else r.why)
        }
        return r
    }

    private fun close(why: String) {
        closedFor = why
        main.post {
            observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
            observer = null
        }
    }

    private fun query(limit: Int): Result {
        val p = TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME
        val cols = arrayOf(
            TvContract.WatchNextPrograms._ID,
            p,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
        )
        return try {
            val c = context.contentResolver.query(
                TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC",
            ) ?: return Result.Closed("no provider")
            c.use {
                val rows = ArrayList<WatchNextRow>()
                while (it.moveToNext() && rows.size < limit) {
                    val pkg = it.getString(1) ?: continue
                    if (pkg == context.packageName) continue
                    rows.add(
                        WatchNextRow(
                            id = it.getLong(0), packageName = pkg,
                            title = it.getString(2) ?: continue,
                            episodeTitle = it.getString(3), season = it.getString(4), episode = it.getString(5),
                            positionMs = it.getLong(6), durationMs = it.getLong(7),
                            artUri = it.getString(8) ?: it.getString(9), intentUri = it.getString(10),
                            lastEngagement = it.getLong(11),
                        ),
                    )
                }
                if (rows.isEmpty()) Result.Closed("empty") else Result.Rows(rows)
            }
        } catch (e: SecurityException) {
            Result.Closed("security: ${e.message}")
        } catch (e: Exception) {
            Result.Closed("error: ${e.message}")
        }
    }

    /**
     * Change notifications, coalesced to one call per second (apps update progress rows in
     * bursts). Not registered, or unregistered later, once Watch Next is known to be closed.
     */
    fun observe(onChange: () -> Unit): ContentObserver? {
        if (closedFor != null) return null
        val fire = Runnable { if (closedFor == null) onChange() }
        val o = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                if (closedFor != null) { close(closedFor!!); return }
                main.removeCallbacks(fire)
                main.postDelayed(fire, 1_000)
            }
        }
        return try {
            context.contentResolver.registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, o)
            observer = o
            // A read may have closed the adapter while we registered.
            if (closedFor != null) close(closedFor!!)
            o
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val ACCESS_ALL_EPG_DATA = "com.android.providers.tv.permission.ACCESS_ALL_EPG_DATA"
    }
}
