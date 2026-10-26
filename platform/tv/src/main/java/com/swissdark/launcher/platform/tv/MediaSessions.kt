package com.swissdark.launcher.platform.tv

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.swissdark.launcher.domain.NowPlaying
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One reading of another app's media session: what is playing and how far in.
 * Only taken on changes (metadata, play/pause/stop, session gone) and once a minute while playing.
 */
data class MediaSample(
    val packageName: String,
    val title: String?,
    val subtitle: String?,          // DISPLAY_SUBTITLE
    val artist: String?,
    val album: String?,
    val description: String?,      // DISPLAY_DESCRIPTION
    val mediaId: String?,
    val artUri: String?,            // ART_URI / DISPLAY_ICON_URI / ALBUM_ART_URI
    val art: Bitmap?,               // ART / DISPLAY_ICON / ALBUM_ART, only when there is no URI
    val positionMs: Long,           // extrapolated to now while playing
    val durationMs: Long,
    /** What the app itself last reported, before extrapolation: Prime keeps this at 0. */
    val reportedPositionMs: Long,
    val playing: Boolean,
    val ended: Boolean,             // STATE_STOPPED or the session went away
    val sessionActivity: PendingIntent?,
    val atMillis: Long,
)

/**
 * Watches every app's media sessions. Needs the notification listener [listener] to be enabled
 * (`cmd notification allow_listener …`, see setup.sh); otherwise [start] returns false and nothing
 * happens. All callbacks run on the main looper and only copy a few fields before handing off.
 */
class MediaSessionWatcher(
    private val context: Context,
    private val listener: ComponentName,
    private val sink: (MediaSample) -> Unit,
) {
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val tracked = HashMap<SessionKey, Tracked>()
    @Volatile var running = false
        private set
    @Volatile var status: String = "not started"
        private set

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)

    /** What another app is playing right now (Home's chip). Null when nothing is. */
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    private fun updateNowPlaying() {
        val t = tracked.values.firstOrNull { t ->
            runCatching { t.controller.playbackState?.state == PlaybackState.STATE_PLAYING }.getOrDefault(false)
        }
        _nowPlaying.value = t?.controller?.let { c ->
            NowPlaying(c.packageName, runCatching { c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) }.getOrNull())
        }
    }

    private data class SessionKey(val pkg: String, val token: Any)

    private inner class Tracked(val controller: MediaController) : MediaController.Callback() {
        var lastTitle: String? = null
        var lastState = -1
        /** Last sample with a title: apps often clear metadata as they stop, before we can read it. */
        var lastGood: MediaSample? = null
        val tick = object : Runnable {
            override fun run() {
                emit(this@Tracked, ended = false)
                main.postDelayed(this, TICK_MS)
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val t = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            if (t != lastTitle) { lastTitle = t; schedule() }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val s = state?.state ?: PlaybackState.STATE_NONE
            if (s == lastState) return
            lastState = s
            main.removeCallbacks(tick)
            if (s == PlaybackState.STATE_PLAYING) main.postDelayed(tick, TICK_MS)
            schedule()
        }

        override fun onSessionDestroyed() {
            emit(this, ended = true)
            untrack(this)
        }

        /** Metadata and state usually change together: one sample a moment later covers both. */
        val pending = Runnable { emit(this, ended = false) }
        fun schedule() {
            main.removeCallbacks(pending)
            main.postDelayed(pending, DEBOUNCE_MS)
        }
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> sync(list.orEmpty()) }

    /** Safe to call repeatedly. False when the listener is not enabled on this TV. */
    fun start(): Boolean {
        if (running) return true
        return try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, listener, main)
            running = true
            status = "listening"
            main.post { sync(activeSessions()) }
            true
        } catch (e: SecurityException) {
            status = "no access: enable the notification listener (setup.sh)"
            false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try { msm.removeOnActiveSessionsChangedListener(sessionsChanged) } catch (_: Exception) {}
        main.post {
            for (t in tracked.values.toList()) { emit(t, ended = false); untrack(t) }
        }
    }

    /** The live session's own "open me" intent, while the app keeps it. */
    fun sessionActivity(pkg: String): PendingIntent? =
        tracked.values.firstOrNull { it.controller.packageName == pkg }?.controller?.sessionActivity

    /** True while [pkg] still has a session we are following. */
    fun isLive(pkg: String): Boolean = tracked.values.any { it.controller.packageName == pkg }

    /** Unpause [pkg]'s live session (only if it is paused). Main thread. */
    fun play(pkg: String): Boolean {
        val c = tracked.values.firstOrNull { it.controller.packageName == pkg }?.controller ?: return false
        return try {
            if (c.playbackState?.state != PlaybackState.STATE_PAUSED) return false
            c.transportControls.play()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun activeSessions(): List<MediaController> = try {
        msm.getActiveSessions(listener)
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun sync(list: List<MediaController>) {
        val keep = HashSet<SessionKey>()
        for (c in list) {
            if (c.packageName == context.packageName) continue
            val k = SessionKey(c.packageName, c.sessionToken)
            keep += k
            if (k in tracked) continue
            val t = Tracked(c)
            tracked[k] = t
            c.registerCallback(t, main)
            t.lastTitle = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            t.lastState = c.playbackState?.state ?: -1
            if (t.lastState == PlaybackState.STATE_PLAYING) main.postDelayed(t.tick, TICK_MS)
            t.schedule()
        }
        for ((k, t) in tracked.entries.toList()) {
            if (k !in keep) { emit(t, ended = true); untrack(t) }
        }
        updateNowPlaying()
    }

    private fun untrack(t: Tracked) {
        main.removeCallbacks(t.tick)
        main.removeCallbacks(t.pending)
        try { t.controller.unregisterCallback(t) } catch (_: Exception) {}
        tracked.entries.removeAll { it.value === t }
        updateNowPlaying()
    }

    private fun emit(t: Tracked, ended: Boolean) {
        val c = t.controller
        val md = try { c.metadata } catch (_: Exception) { null }
        val st = try { c.playbackState } catch (_: Exception) { null }
        val now = System.currentTimeMillis()
        var pos = st?.position ?: 0L
        val playing = st?.state == PlaybackState.STATE_PLAYING
        if (playing && st != null && st.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        fun s(key: String) = md?.getString(key)?.trim()?.takeIf { it.isNotEmpty() }
        val uri = s(MediaMetadata.METADATA_KEY_ART_URI) ?: s(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI) ?: s(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
        val bmp = if (uri != null || md == null) null else
            md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        val sample = MediaSample(
            packageName = c.packageName,
            title = s(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: s(MediaMetadata.METADATA_KEY_TITLE),
            subtitle = s(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            artist = s(MediaMetadata.METADATA_KEY_ARTIST) ?: s(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = s(MediaMetadata.METADATA_KEY_ALBUM),
            description = s(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION),
            mediaId = s(MediaMetadata.METADATA_KEY_MEDIA_ID),
            artUri = uri,
            art = bmp,
            positionMs = pos.coerceAtLeast(0L),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L,
            reportedPositionMs = (st?.position ?: 0L).coerceAtLeast(0L),
            playing = playing,
            ended = ended || st?.state == PlaybackState.STATE_STOPPED,
            sessionActivity = c.sessionActivity,
            atMillis = now,
        )
        if (Log.isLoggable(TAG, Log.DEBUG) || DEBUG_LOG) {
            Log.i(TAG, "${c.packageName} state=${st?.state} pos=${sample.positionMs} raw=${st?.position} actions=${st?.actions} dur=${sample.durationMs} keys=${md?.keySet()?.toList()} " +
                "title=${sample.title} sub=${sample.subtitle} artist=${sample.artist} album=${sample.album} desc=${sample.description} " +
                "id=${sample.mediaId} uri=${sample.artUri} bmp=${bmp?.width}x${bmp?.height} activity=${c.sessionActivity != null}")
        }
        val good = t.lastGood
        if (sample.title == null && good != null) {
            // Metadata cleared on stop: close the last title at where it had got to.
            t.lastGood = null
            val pos = if (good.playing) good.positionMs + (now - good.atMillis) else good.positionMs
            sink(good.copy(positionMs = if (good.durationMs > 0) minOf(pos, good.durationMs) else pos, playing = false, ended = true, atMillis = now))
            return
        }
        if (sample.title != null) t.lastGood = if (ended) null else sample
        sink(sample)
        updateNowPlaying()
    }

    companion object {
        const val TAG = "SwissMedia"
        /** `adb shell setprop log.tag.SwissMedia DEBUG` also turns this on. */
        @Volatile var DEBUG_LOG = false
        private const val TICK_MS = 30_000L
        private const val DEBOUNCE_MS = 700L
    }
}
