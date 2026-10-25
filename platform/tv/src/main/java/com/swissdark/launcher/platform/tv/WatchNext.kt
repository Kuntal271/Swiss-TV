package com.swissdark.launcher.platform.tv

import android.content.Context
import android.database.ContentObserver
import android.media.tv.TvContract
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
 * TvContract.WatchNextPrograms. Many Google TV builds only return the caller's own rows,
 * so an empty or refused read means "closed to us" and Home falls back to our own history.
 */
class WatchNextAdapter(private val context: Context) {
    sealed interface Result {
        data class Rows(val rows: List<WatchNextRow>) : Result
        data class Closed(val why: String) : Result
    }

    fun read(limit: Int = 12): Result {
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

    fun observe(onChange: () -> Unit): ContentObserver? {
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        return try {
            context.contentResolver.registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, o)
            o
        } catch (_: Exception) {
            null
        }
    }
}
