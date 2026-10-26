package com.swissdark.launcher.data

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.swissdark.launcher.domain.ForegroundWatch
import com.swissdark.launcher.domain.MediaType
import com.swissdark.launcher.domain.NowPlaying
import com.swissdark.launcher.domain.SearchHit
import com.swissdark.launcher.domain.TitleRef
import com.swissdark.launcher.platform.tv.LaunchAdapter
import com.swissdark.launcher.platform.tv.MediaSample
import com.swissdark.launcher.platform.tv.MediaSessionWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * What was actually watched, from other apps' media sessions: one row per show (its latest
 * episode) or per film/video. Its own small database, so the cache database's schema stays as is.
 */
@Entity(tableName = "watched")
data class WatchedRow(
    @PrimaryKey val key: String,            // "<package>|<normalised show or title>"
    val pkg: String,
    val show: String,                       // series name, or the film/video title
    val episodeTitle: String?,              // episode name; YouTube: the channel
    val season: Int?,
    val episode: Int?,
    val series: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val art: String?,                       // http(s) url from the session, or file:// thumbnail
    val mediaId: String?,
    val updatedAt: Long,
    val tmdbId: Int? = null,
    val tmdbType: String? = null,
    val tmdbArt: String? = null,
    val tmdbTried: Boolean = false,
) {
    val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs * 0.95

    /** An app that plays without saying what (Netflix, Prime Video…): one row per app, no title. */
    val appOnly: Boolean get() = key.endsWith(WatchedStore.APP_GROUP)
}

/**
 * Decides whether an app that publishes no title (Netflix, Prime Video, JioHotstar…) is really
 * being watched, rather than autoplaying a trailer on its home screen. Pure, so it is unit-tested.
 *
 * Observed on the TV: Netflix's home autoplays previews as PLAYING with no metadata at all (no
 * duration, position from 0) and goes STOPPED when you move on; Prime reports the placeholder
 * title "PrimeVideo" with its position stuck at 0. So:
 *  - a known duration under 5 min is a trailer/preview: never;
 *  - PLAYING time counts up across pauses but starts again after STOPPED or a new item (duration
 *    change), so a string of previews never adds up;
 *  - 60 s of play is enough when the item is a real length (≥ 5 min) or was resumed mid-way;
 *    with nothing to go on (no duration, from 0) it takes 2 min, longer than most previews.
 * Once accepted, every later sample of that item updates the row (pause, stop: recency).
 */
class PlaybackGate {
    /** [positionMs]/[durationMs] are 0 when the app's numbers are not worth a progress bar. */
    data class Verdict(val positionMs: Long, val durationMs: Long)

    private class S {
        var played = 0L
        var lastAt = -1L
        var playing = false
        var startPos = -1L
        var dur = 0L
        var accepted = false
        var lastReported = -1L
        var moved = false
    }

    private val byPkg = HashMap<String, S>()

    /**
     * One media-session sample. [ended]: STOPPED or the session went away. [reportedPosMs] is the
     * app's own position; [positionMs] the same extrapolated to [atMs]. Null: not (yet) a watch.
     */
    fun feed(pkg: String, playing: Boolean, ended: Boolean, reportedPosMs: Long, positionMs: Long, durationMs: Long, atMs: Long): Verdict? {
        var s = byPkg.getOrPut(pkg) { S() }
        if (s.playing && s.lastAt >= 0) s.played += (atMs - s.lastAt).coerceIn(0L, MAX_GAP_MS)
        // Another item (episode, film, trailer) in the same session: start over.
        if (s.dur > 0 && durationMs > 0 && kotlin.math.abs(durationMs - s.dur) > 2_000) {
            s = S(); byPkg[pkg] = s
        }
        if (durationMs > 0) s.dur = durationMs
        if (s.startPos < 0 && playing) s.startPos = reportedPosMs
        if (s.lastReported >= 0 && reportedPosMs > 0 && reportedPosMs != s.lastReported) s.moved = true
        s.lastReported = reportedPosMs
        s.playing = playing && !ended
        s.lastAt = atMs
        if (!s.accepted && !(s.dur in 1 until REAL_MIN_MS)) {
            val need = if (s.dur >= REAL_MIN_MS || s.startPos >= RESUMED_MS) PLAY_MS else PLAY_UNKNOWN_MS
            if (s.played >= need) s.accepted = true
        }
        val v = if (!s.accepted) null else {
            val sane = s.dur >= REAL_MIN_MS && s.moved && positionMs in 1..s.dur
            if (sane) Verdict(positionMs, s.dur) else Verdict(0, 0)
        }
        if (ended) byPkg.remove(pkg)
        return v
    }

    companion object {
        const val REAL_MIN_MS = 5 * 60_000L
        const val PLAY_MS = 60_000L
        const val PLAY_UNKNOWN_MS = 120_000L
        const val RESUMED_MS = 60_000L
        /** Samples come every 30 s while playing; a longer gap (TV asleep) counts as no more than this. */
        const val MAX_GAP_MS = 45_000L
    }
}

@Dao
interface WatchedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(r: WatchedRow)
    @Query("SELECT * FROM watched WHERE `key` = :key") suspend fun get(key: String): WatchedRow?
    @Query("SELECT * FROM watched ORDER BY updatedAt DESC LIMIT :n") suspend fun recent(n: Int): List<WatchedRow>
    @Query("DELETE FROM watched WHERE `key` = :key") suspend fun delete(key: String)
    @Query("DELETE FROM watched") suspend fun clear(): Int
    @Query("DELETE FROM watched WHERE `key` NOT IN (SELECT `key` FROM watched ORDER BY updatedAt DESC LIMIT :keep)") suspend fun trim(keep: Int)
}

@Database(entities = [WatchedRow::class], version = 1, exportSchema = false)
abstract class WatchedDb : RoomDatabase() {
    abstract fun dao(): WatchedDao

    companion object {
        fun open(context: Context): WatchedDb =
            Room.databaseBuilder(context, WatchedDb::class.java, "watched.db").build()
    }
}

/**
 * Turns media-session samples into [WatchedRow]s and opens them again.
 * Writes happen on one background thread, only when a sample says something new.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchedStore(
    private val context: Context,
    private val scope: CoroutineScope,
    private val tmdb: TmdbClient,
    private val launch: LaunchAdapter,
    private val http: okhttp3.OkHttpClient? = null,
    private val onChanged: () -> Unit,
) {
    private val db by lazy { WatchedDb.open(context) }
    private val writer = Dispatchers.IO.limitedParallelism(1)
    private val thumbs by lazy { File(context.filesDir, "watched").apply { mkdirs() } }
    /** Which row each app's live session is playing right now (main thread writes, any thread reads). */
    private val liveKeyByPkg = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()

    val listener = ComponentName(context.packageName, LISTENER_CLASS)
    private val watcher by lazy { MediaSessionWatcher(context, listener) { onSample(it) } }

    val status: String get() = if (started) watcher.status else "not started"

    /** What another app is playing right now (Home's chip; null when nothing is). */
    val nowPlaying: StateFlow<NowPlaying?> get() = watcher.nowPlaying
    @Volatile private var started = false

    /** Main thread. Idempotent; false until setup.sh has enabled the notification listener. */
    fun start(): Boolean {
        started = true
        if (watcher.start()) return true
        // Enabled but not bound (an app update unbinds it until the next boot): ask for a rebind;
        // MediaListenerService.onListenerConnected calls start() again.
        try {
            android.service.notification.NotificationListenerService.requestRebind(listener)
        } catch (_: Exception) {}
        return false
    }

    fun stop() {
        if (started) watcher.stop()
    }

    /** Rows Home last showed (or that were just written): [open] runs on the main thread, no DB. */
    private val known = java.util.concurrent.ConcurrentHashMap<String, WatchedRow>()

    suspend fun recent(n: Int): List<WatchedRow> = withContext(writer) {
        db.dao().recent(n).also { rows -> for (r in rows) known[r.key] = r }
    }

    /** The show's picture found online for row [key] (see BackdropArt.showArtUrl): the card's art from now on. */
    suspend fun setShowArt(key: String, url: String) = withContext(writer) {
        val cur = db.dao().get(key) ?: return@withContext
        if (cur.tmdbArt == url) return@withContext
        val next = cur.copy(tmdbArt = url)
        db.dao().put(next); known[key] = next
        onChanged()
    }

    /** Test hook (DEBUG_SEED): three demo cards, or remove them again. Demo keys start with "demo". */
    suspend fun seedDemo(remove: Boolean) = withContext(writer) {
        val now = System.currentTimeMillis()
        val rows = listOf(
            WatchedRow("$CRUNCHYROLL|demo1", CRUNCHYROLL, "Day of Atonement", null, 2, 4, true, 600_000, 1_420_000,
                "https://static.tvmaze.com/uploads/images/original_untouched/647/1619129.jpg", null, now, tmdbTried = true),
            WatchedRow("$YOUTUBE|demo2", YOUTUBE, "Demo video", "Demo channel", null, null, false, 300_000, 900_000,
                "https://i.ytimg.com/vi/aqz-KE-bpKQ/hq720.jpg", null, now - 1000, tmdbTried = true),
            WatchedRow("com.sonyliv|demo3", "com.sonyliv", "Demo show", "Episode 3", 1, 3, true, 900_000, 2_400_000,
                "https://static.tvmaze.com/uploads/images/original_untouched/647/1619129.jpg", null, now - 2000, tmdbTried = true),
        )
        for (r in rows) if (remove) { db.dao().delete(r.key); known.remove(r.key) } else { db.dao().put(r); known[r.key] = r }
        onChanged()
    }

    fun delete(key: String) {
        // An app-only card the user removed stays away until the app is used again afterwards
        // (see [noteForeground]); remember when it went.
        if (key.endsWith(APP_GROUP)) markRemoved(key.substringBefore('|'))
        known.remove(key)
        scope.launch(writer) { db.dao().delete(key) }
    }

    /**
     * False from TV standby (screen off) until the screen is on again: the "paused/stopped"
     * samples apps send as the TV goes off must not bring cleared rows straight back.
     */
    @Volatile var recording = true

    /** Continue watching cleared (TV off / boot): every row, and the cached thumbnails. */
    suspend fun clearAll(): Int = withContext(writer) {
        val n = db.dao().clear()
        known.clear()
        liveKeyByPkg.clear()
        n + ContinueCleaner.deleteFiles(thumbs)
    }

    // ---- recording ----

    private val gate = PlaybackGate()

    private fun onSample(s: MediaSample) {
        if (!recording) return
        if (untitled(s)) return onUntitled(s)
        val parsed = parse(s) ?: return
        val key = s.packageName + "|" + parsed.group
        if (!s.ended) liveKeyByPkg[s.packageName] = key
        val art = s.art
        scope.launch(writer) {
            try {
                record(s, key, parsed, art)
            } catch (e: Exception) {
                Log.w(MediaSessionWatcher.TAG, "record failed", e)
            }
        }
    }

    /** No usable title: Netflix (no metadata), Prime ("PrimeVideo"), most Indian apps, VLC… */
    private fun untitled(s: MediaSample): Boolean {
        if (s.packageName == YOUTUBE || s.packageName == CRUNCHYROLL) return false
        val t = s.title ?: return true
        return isPlaceholder(t, s.packageName)
    }

    private fun onUntitled(s: MediaSample) {
        val v = gate.feed(s.packageName, s.playing, s.ended, s.reportedPositionMs, s.positionMs, s.durationMs, s.atMillis) ?: return
        val key = s.packageName + "|" + APP_GROUP
        if (s.ended) liveKeyByPkg.remove(s.packageName, key) else liveKeyByPkg[s.packageName] = key
        scope.launch(writer) {
            try {
                recordApp(s, key, v)
            } catch (e: Exception) {
                Log.w(MediaSessionWatcher.TAG, "record failed", e)
            }
        }
    }

    private suspend fun recordApp(s: MediaSample, key: String, v: PlaybackGate.Verdict) {
        if (!launchable(s.packageName)) return
        val dao = db.dao()
        val old = dao.get(key)
        // Netflix reports position 0 as it stops: keep the last real numbers.
        val keep = old != null && v.durationMs == 0L && old.durationMs > 0 && s.ended
        val row = WatchedRow(
            key = key, pkg = s.packageName, show = appLabel(s.packageName), episodeTitle = null,
            season = null, episode = null, series = false,
            positionMs = if (keep) old!!.positionMs else v.positionMs,
            durationMs = if (keep) old!!.durationMs else v.durationMs,
            art = null, mediaId = null, updatedAt = s.atMillis, tmdbTried = true,
        )
        if (row == old) return
        dao.put(row)
        known[key] = row
        if (old == null) dao.trim(KEEP)
        if (old == null || !s.playing) onChanged()
    }

    private fun launchable(pkg: String): Boolean = launchables.getOrPut(pkg) {
        val pm = context.packageManager
        try { pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null } catch (_: Exception) { false }
    }
    private val launchables = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { "" }
    }.ifEmpty { pkg }

    private class Parsed(
        val show: String, val episodeTitle: String?, val season: Int?, val episode: Int?, val series: Boolean,
        /** The show is really an episode name (JioHotstar's "S2 E14 …"): art comes from TVmaze, not TMDB. */
        val episodeOnly: Boolean = false,
    ) {
        /** One row per show; apps that only publish the episode name get one row (their latest). */
        var group: String = norm(show)
    }

    /** Null for placeholders, ads and other things that are not a title someone is watching. */
    private fun parse(s: MediaSample): Parsed? {
        val title = s.title ?: return null
        if (isPlaceholder(title, s.packageName)) return null
        if (s.durationMs in 1 until MIN_DURATION_MS) return null           // ads, previews, shorts
        val se = seasonEpisode(listOfNotNull(s.subtitle, s.description, title, s.album).joinToString(" · "))
        return when (s.packageName) {
            YOUTUBE -> Parsed(title, s.artist ?: s.subtitle, null, null, false)
            // Crunchyroll publishes only the episode name (and an episode id): no show to group by.
            CRUNCHYROLL -> Parsed(title, null, se?.first, se?.second, true).apply { group = "latest" }
            else -> {
                // Most players put the show in ALBUM (or ARTIST / DISPLAY_SUBTITLE) and the episode in TITLE.
                val show = listOfNotNull(s.album, s.artist, s.subtitle)
                    .firstOrNull { it != title && !isPlaceholder(it, s.packageName) && seasonEpisode(it) == null }
                when {
                    show != null -> Parsed(show, title, se?.first, se?.second, true)
                    // JioHotstar titles its sessions "S2 E14 Bixby's Back": what follows the numbering
                    // is the episode name. Like Crunchyroll, one row per app (the latest episode);
                    // the card's art comes from the TVmaze episode lookup, not TMDB (watchCardArt).
                    se != null -> episodeName(title)?.let { (season, ep, name) ->
                        Parsed(name, null, season, ep, true, episodeOnly = true).apply { group = "latest" }
                    } ?: Parsed(title, null, se.first, se.second, true)
                    else -> Parsed(title, null, null, null, false)
                }
            }
        }
    }

    private suspend fun record(s: MediaSample, key: String, p: Parsed, art: Bitmap?) {
        val dao = db.dao()
        val old = dao.get(key)
        val sameEpisode = old != null && old.show == p.show && old.episodeTitle == p.episodeTitle && old.episode == p.episode
        // A fresh episode or title starts at the top: nothing watched yet is not worth a card.
        if (s.positionMs < MIN_WATCHED_MS && !sameEpisode) return

        var artKey = s.artUri?.takeIf { it.startsWith("http") }
        if (artKey == null) {
            artKey = if (sameEpisode && old?.art != null) old.art
            else art?.let { saveThumb(key, it) } ?: old?.art?.takeIf { sameEpisode }
        }
        val row = WatchedRow(
            key = key, pkg = s.packageName, show = p.show, episodeTitle = p.episodeTitle,
            season = p.season ?: old?.season?.takeIf { sameEpisode }, episode = p.episode ?: old?.episode?.takeIf { sameEpisode },
            series = p.series || old?.series == true,
            // Some players report 0 (or rewind) as they stop: keep the last real position.
            positionMs = if (old != null && sameEpisode && (s.positionMs == 0L || (s.ended && s.positionMs < old.positionMs))) old.positionMs else s.positionMs,
            durationMs = if (s.durationMs > 0) s.durationMs else if (sameEpisode) old?.durationMs ?: 0 else 0,
            art = artKey, mediaId = s.mediaId,
            updatedAt = s.atMillis,
            tmdbId = old?.tmdbId, tmdbType = old?.tmdbType, tmdbArt = old?.tmdbArt, tmdbTried = old?.tmdbTried == true,
        )
        if (row == old) return
        dao.put(row)
        known[key] = row
        if (old == null) dao.trim(KEEP)
        // While a title plays, Home is out of sight: tell it on pause/stop and on a new title only.
        if (old == null || !sameEpisode || !s.playing) onChanged()
        if (!row.tmdbTried && !p.episodeOnly && s.packageName != YOUTUBE && s.packageName != CRUNCHYROLL && tmdb.hasKey) matchTmdb(row)
        if (s.packageName == YOUTUBE && youTubeId(row) == null) scope.launch { resolveYouTube(row) }
    }

    // ---- YouTube: its session carries no video id ----

    private val ytPrefs by lazy { context.getSharedPreferences("youtube_ids", Context.MODE_PRIVATE) }
    private val ytResolving = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The row's video id: the session's media id when it is one, else the one found by [resolveYouTube]. */
    private fun youTubeId(row: WatchedRow): String? =
        row.mediaId?.takeIf { YT_ID.matches(it) } ?: ytPrefs.getString(ytKey(row), null)

    private fun ytKey(row: WatchedRow) = row.key + "|" + norm(row.episodeTitle.orEmpty())

    /**
     * Finds the video on youtube.com's search page ("<title> <channel>"): the first result whose
     * title matches. Remembered per row; its thumbnail becomes the card's (sharp, 16:9) art.
     */
    private suspend fun resolveYouTube(row: WatchedRow): String? {
        val client = http ?: return null
        val k = ytKey(row)
        ytPrefs.getString(k, null)?.let { return it }
        if (!ytResolving.add(k)) return null
        try {
            val q = java.net.URLEncoder.encode(listOfNotNull(row.show, row.episodeTitle).joinToString(" "), "UTF-8")
            val req = okhttp3.Request.Builder().url("https://www.youtube.com/results?search_query=$q")
                .header("User-Agent", DESKTOP_UA).header("Accept-Language", "en").build()
            val (code, html) = withContext(Dispatchers.IO) { client.fetch(req) }
            if (code != 200) return null
            val hit = YouTubeParse.firstMatch(html, row.show) ?: return null
            ytPrefs.edit().putString(k, hit.id).putString("$k|art", hit.thumb).apply()
            // Sharper art than the session's small bitmap, for the card and the focus backdrop.
            withContext(writer) {
                val cur = db.dao().get(row.key) ?: return@withContext
                if (cur.tmdbArt != hit.thumb) {
                    val next = cur.copy(tmdbArt = hit.thumb)
                    db.dao().put(next); known[row.key] = next
                    onChanged()
                }
            }
            return hit.id
        } catch (e: Exception) {
            Log.w(MediaSessionWatcher.TAG, "youtube lookup failed", e)
            return null
        } finally {
            ytResolving.remove(k)
        }
    }

    /**
     * YouTube: the live session if it is still on this video, else the video itself at the
     * position reached (youtube.com/watch?v=…&t=…s, which YouTube for TV opens in its player).
     * The id is looked up when unknown (a second or two); only if that fails, YouTube's search.
     */
    private fun openYouTube(row: WatchedRow, key: String): Boolean {
        if (started && liveKeyByPkg[row.pkg] == key && watcher.isLive(row.pkg)) {
            val pi = watcher.sessionActivity(row.pkg)
            if (pi != null && send(pi)) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ watcher.play(row.pkg) }, 800)
                return true
            }
        }
        youTubeId(row)?.let { if (launch.openUri(watchUrl(it, row), row.pkg)) return true }
        scope.launch(Dispatchers.Main) {
            val id = kotlinx.coroutines.withTimeoutOrNull(4_000) { resolveYouTube(row) }
            val ok = id != null && launch.openUri(watchUrl(id, row), row.pkg)
            if (!ok && !launch.search(row.pkg, listOfNotNull(row.show, row.episodeTitle).joinToString(" "))) launch.launchPackage(row.pkg)
        }
        return true
    }

    private fun watchUrl(id: String, row: WatchedRow) =
        "https://www.youtube.com/watch?v=$id" + if (row.positionMs > 5_000) "&t=${row.positionMs / 1000}s" else ""

    /** Series/film name → TMDB, for real details on the Up next page. Exact name matches only. */
    private suspend fun matchTmdb(row: WatchedRow) {
        val hits = try { tmdb.searchMulti(row.show) } catch (_: Exception) { return }
        val want = norm(row.show)
        val hit = hits.filterIsInstance<SearchHit.Title>()
            .filter { norm(it.ref.name) == want }
            .sortedByDescending { (if (row.series == (it.ref.type == MediaType.TV)) 1000.0 else 0.0) + it.popularity }
            .firstOrNull()
        val cur = db.dao().get(row.key) ?: return
        db.dao().put(
            cur.copy(
                tmdbTried = true,
                tmdbId = hit?.ref?.tmdbId, tmdbType = hit?.ref?.type?.name,
                tmdbArt = hit?.backdropUrl ?: hit?.posterUrl,
            ),
        )
        if (hit != null) onChanged()
    }

    /** 480 × 270 centre crop, JPEG. Named per row: a new episode overwrites the old picture. */
    private fun saveThumb(key: String, src: Bitmap): String? = try {
        val w = 480; val h = 270
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val cw = (w / scale).toInt(); val ch = (h / scale).toInt()
        val sx = (src.width - cw) / 2; val sy = (src.height - ch) / 2
        Canvas(out).drawBitmap(src, Rect(sx, sy, sx + cw, sy + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        // A fresh name per picture: image caches key on the path.
        thumbs.listFiles { f -> f.name.startsWith(fileStem(key)) }?.forEach { it.delete() }
        val f = File(thumbs, fileStem(key) + "-" + System.currentTimeMillis() + ".jpg")
        f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        out.recycle()
        "file://" + f.absolutePath
    } catch (e: Exception) {
        null
    }

    private fun fileStem(key: String) = "w" + Integer.toHexString(key.hashCode())

    private fun isPlaceholder(t: String, pkg: String): Boolean {
        val n = norm(t)
        if (n.isEmpty() || n in PLACEHOLDERS) return true
        val label = labels.getOrPut(pkg) {
            try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { "" }
        }
        return label.isNotEmpty() && n == norm(label)
    }

    // ---- UsageStats fallback: apps that publish no media session at all (JioHotstar) ----

    private val removedPrefs by lazy { context.getSharedPreferences("watched_removed", Context.MODE_PRIVATE) }
    private fun removedAt(pkg: String): Long = removedPrefs.getLong(pkg, 0L)
    private fun markRemoved(pkg: String) { removedPrefs.edit().putLong(pkg, System.currentTimeMillis()).apply() }

    /**
     * [candidates]: foreground use per (resolved) package since the last Continue watching
     * clear, polled by HomeRepositoryImpl from UsageStats. An app that never publishes a
     * session (JioHotstar) never reaches [onUntitled], so [FOREGROUND_WATCH_MS] of foreground
     * counts as watching it: the app-only row like the gate's, "Watched X ago". A row's real
     * playback numbers are never overwritten (only its recency moves), a row removed by the
     * user stays away until foreground time after the removal, and nothing is written while
     * the screen is off.
     */
    fun noteForeground(candidates: Map<String, ForegroundWatch.Use>) {
        if (!recording || candidates.isEmpty()) return
        for ((pkg, use) in candidates) {
            if (use.totalMs < FOREGROUND_WATCH_MS) continue
            val key = "$pkg|$APP_GROUP"
            val removed = removedAt(pkg)
            if (removed > 0 && use.lastEnd <= removed) continue
            // Steady state (the row is already as new as this use): skip the writer round-trip.
            val current = known[key]
            if (current != null && use.lastEnd <= current.updatedAt) continue
            scope.launch(writer) {
                try {
                    if (!launchable(pkg)) return@launch
                    // Re-checked on the writer: a removal that landed after the poll must win
                    // over the write, or the card would come back and no later poll removes it.
                    val removedNow = removedAt(pkg)
                    if (removedNow > 0 && use.lastEnd <= removedNow) return@launch
                    val dao = db.dao()
                    val old = dao.get(key)
                    if (old != null) {
                        if (use.lastEnd <= old.updatedAt) return@launch
                        val next = old.copy(updatedAt = use.lastEnd)
                        dao.put(next); known[key] = next
                    } else {
                        val row = WatchedRow(
                            key = key, pkg = pkg, show = appLabel(pkg), episodeTitle = null,
                            season = null, episode = null, series = false,
                            positionMs = 0, durationMs = 0, art = null, mediaId = null,
                            updatedAt = use.lastEnd, tmdbTried = true,
                        )
                        dao.put(row); known[key] = row; dao.trim(KEEP)
                    }
                    onChanged()
                } catch (e: Exception) {
                    Log.w(MediaSessionWatcher.TAG, "foreground note failed", e)
                }
            }
        }
    }

    // ---- opening ----

    /**
     * Resume [uri] ("swiss-watched:<key>"): the live session's own intent while that session is
     * still playing this title, else a deep link the app understands. False: caller falls back
     * to the app's search, then the app.
     */
    fun open(uri: String): Boolean {
        val key = uri.removePrefix(PREFIX)
        val row = known[key] ?: return false
        if (row.appOnly) return openApp(row)
        if (row.pkg == YOUTUBE) return openYouTube(row, key)
        if (liveKeyByPkg[row.pkg] == key) {
            val pi = if (started) watcher.sessionActivity(row.pkg) else null
            if (pi != null && send(pi)) return true
        }
        if (deepLink(row)?.let { launch.openUri(it, row.pkg) } == true) return true
        // Crunchyroll: no working deep link, and its search does not find episode names.
        // Its home opens on "Continue watching", which is the next best thing.
        return row.pkg == CRUNCHYROLL && launch.launchPackage(row.pkg)
    }

    /**
     * Netflix, Prime…: the live session's own intent if it has one (Netflix and Prime publish
     * none), else the app itself, which comes back where it was or on its own Continue watching
     * row. A session left paused is unpaused once the app is in front.
     */
    private fun openApp(row: WatchedRow): Boolean {
        val live = started && watcher.isLive(row.pkg)
        val pi = if (live) watcher.sessionActivity(row.pkg) else null
        // Not live: open the app it belongs to (the old JioCinema stub → JioHotstar).
        if (!(pi != null && send(pi)) && !launch.launchPackage(if (live) row.pkg else com.swissdark.launcher.domain.Providers.resolve(row.pkg))) return false
        if (live) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ watcher.play(row.pkg) }, 800)
        return true
    }

    private fun send(pi: PendingIntent): Boolean = try {
        val opts = ActivityOptions.makeBasic()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        pi.send(context, 0, null, null, null, null, opts.toBundle())
        true
    } catch (_: Exception) {
        false
    }

    private fun deepLink(row: WatchedRow): String? = when (row.pkg) {
        YOUTUBE -> row.mediaId?.takeIf { YT_ID.matches(it) }?.let {
            "https://www.youtube.com/watch?v=$it" + if (row.positionMs > 0) "&t=${row.positionMs / 1000}s" else ""
        }
        else -> null
    }

    companion object {
        const val PREFIX = "swiss-watched:"
        const val LISTENER_CLASS = "com.swissdark.launcher.MediaListenerService"
        const val YOUTUBE = "com.google.android.youtube.tv"
        const val CRUNCHYROLL = "com.crunchyroll.crunchyroid"
        /** Row key suffix ("<package>|@app") of an app that played without a title. */
        const val APP_GROUP = "@app"
        private const val KEEP = 60
        /** Foreground time (UsageStats fallback) that counts as watching an app with no session. */
        const val FOREGROUND_WATCH_MS = 3 * 60_000L
        private const val MIN_WATCHED_MS = 15_000L
        private const val MIN_DURATION_MS = 90_000L
        private val YT_ID = Regex("[A-Za-z0-9_-]{11}")
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private val PLACEHOLDERS = setOf(
            "primevideo", "netflix", "youtube", "crunchyroll", "jiohotstar", "hotstar", "jiocinema",
            "advertisement", "ad", "loading", "live", "unknown",
        )

        fun norm(s: String): String = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

        private val SE = listOf(
            Regex("""\bS(?:eason)?\s*(\d{1,2})\s*[·:,.\-]?\s*E(?:p(?:isode)?)?\.?\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE),
            Regex("""\bSeason\s+(\d{1,2})\D{1,12}Episode\s+(\d{1,4})\b""", RegexOption.IGNORE_CASE),
        )
        private val E_ONLY = Regex("""\b(?:E|Ep\.?|Episode)\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE)

        /** (season, episode) from "S2 E5", "S02E05", "Season 2 · Episode 5" or "Episode 5" (season null). */
        fun seasonEpisode(s: String): Pair<Int?, Int>? {
            for (r in SE) r.find(s)?.let { m -> return m.groupValues[1].toInt() to m.groupValues[2].toInt() }
            return E_ONLY.find(s)?.let { null to it.groupValues[1].toInt() }
        }

        private val SE_NAME = Regex(
            "^\\s*S(?:eason)?\\s*(\\d{1,2})\\s*[·:,.\\-]?\\s*E(?:p(?:isode)?)?\\.??\\s*(\\d{1,4})\\s*[·:,.\\-]?\\s*(.+?)\\s*$",
            RegexOption.IGNORE_CASE,
        )
        private val E_NAME = Regex(
            "^\\s*E(?:p(?:isode)?)?\\.??\\s*(\\d{1,4})\\s*[·:,.\\-]?\\s*(.+?)\\s*$",
            RegexOption.IGNORE_CASE,
        )

        /**
         * "S2 E14 Bixby's Back" → (2, 14, "Bixby's Back"); "E14 Bixby's Back" → (null, 14, …).
         * Null when the title does not lead with numbering or the name part is too short to be one.
         */
        fun episodeName(title: String): Triple<Int?, Int, String>? {
            SE_NAME.find(title)?.let { m ->
                return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].trim())
                    .takeIf { it.third.length >= 2 }
            }
            E_NAME.find(title)?.let { m ->
                return Triple(null, m.groupValues[1].toInt(), m.groupValues[2].trim())
                    .takeIf { it.third.length >= 2 }
            }
            return null
        }

        fun ref(row: WatchedRow): TitleRef? {
            val id = row.tmdbId ?: return null
            val type = row.tmdbType?.let { runCatching { MediaType.valueOf(it) }.getOrNull() } ?: return null
            return TitleRef(id, type, row.show)
        }
    }
}


/** youtube.com search-page parsing (pure, unit-tested). */
object YouTubeParse {
    data class Hit(val id: String, val title: String, val thumb: String)

    private val RENDERER = Regex(""""videoRenderer":\{"videoId":"([A-Za-z0-9_-]{11})"""")
    private val TITLE = Regex(""""title":\{"runs":\[\{"text":"((?:[^"\\]|\\.)*)"""")
    private val UNI = Regex("""\\u([0-9a-fA-F]{4})""")

    fun results(html: String, max: Int = 8): List<Hit> = RENDERER.findAll(html).take(max).mapNotNull { m ->
        val seg = html.substring(m.range.last + 1, minOf(html.length, m.range.last + 4_000))
        val t = TITLE.find(seg)?.groupValues?.get(1) ?: return@mapNotNull null
        val id = m.groupValues[1]
        val thumb = if (seg.substringBefore("\"title\"").contains("/hq720.jpg")) "https://i.ytimg.com/vi/$id/hq720.jpg"
        else "https://i.ytimg.com/vi/$id/mqdefault.jpg"
        Hit(id, unescape(t), thumb)
    }.toList()

    /** The first result whose title is [title] (normalised; a session's title may be cut short). */
    fun firstMatch(html: String, title: String): Hit? {
        val want = WatchedStore.norm(title)
        if (want.length < 3) return null
        return results(html).firstOrNull { h ->
            val n = WatchedStore.norm(h.title)
            n == want || (want.length >= 12 && (n.startsWith(want) || want.startsWith(n) && n.length >= 12))
        }
    }

    fun unescape(s: String): String = UNI.replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\")
}
