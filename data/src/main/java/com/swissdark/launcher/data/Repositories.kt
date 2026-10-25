package com.swissdark.launcher.data

import android.content.Context
import com.swissdark.launcher.core.perf.Sections
import com.swissdark.launcher.core.perf.traced
import com.swissdark.launcher.domain.AppEntry
import com.swissdark.launcher.domain.AppId
import com.swissdark.launcher.domain.AppsRepository
import com.swissdark.launcher.domain.ContinueItem
import com.swissdark.launcher.domain.ContinueSource
import com.swissdark.launcher.domain.HeroInfo
import com.swissdark.launcher.domain.HomeRepository
import com.swissdark.launcher.domain.HomeSnapshot
import com.swissdark.launcher.domain.MediaType
import com.swissdark.launcher.domain.Providers
import com.swissdark.launcher.domain.RefreshReason
import com.swissdark.launcher.domain.TimeSlot
import com.swissdark.launcher.domain.TitleRef
import com.swissdark.launcher.domain.hero
import com.swissdark.launcher.platform.tv.InstalledApp
import com.swissdark.launcher.platform.tv.PackagesAdapter
import com.swissdark.launcher.platform.tv.UsageAdapter
import com.swissdark.launcher.platform.tv.WatchNextAdapter
import com.swissdark.launcher.platform.tv.WatchNextRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean

/** Tabler glyph per package. Unknown apps get the generic app glyph. */
object AppGlyphs {
    private val map = mapOf(
        "com.netflix.ninja" to "netflix",
        "com.google.android.youtube.tv" to "youtube",
        "com.amazon.amazonvideo.livingroom" to "prime",
        "com.jio.media.stb.ondemand" to "jiohotstar",
        "in.startv.hotstar" to "jiohotstar",
        "com.spotify.tv.android" to "spotify",
        "com.android.tv.settings" to "settings",
        "com.google.android.youtube.tvkids" to "youtube_kids",
        "com.google.android.youtube.tvmusic" to "youtube_music",
        "com.apple.atve.androidtv.appletv" to "appletv",
        "com.tcl.ui_mediaCenter" to "files",
        "com.android.vending" to "playstore",
        "org.xbmc.kodi" to "kodi",
        "com.plexapp.android" to "plex",
        "com.sonyliv" to "sonyliv",
        "org.videolan.vlc" to "vlc",
        "com.graymatrix.did" to "zee5",
        "com.tcl.browser" to "world",
        "com.google.android.play.games" to "gamepad",
        "com.google.android.videos" to "play_circle",
        "com.crunchyroll.crunchyroid" to "play_circle",
        "com.yablio.sendfilestotv" to "send",
        "com.tcl.magiconnectfree" to "cast",
        "com.tcl.tv" to "antenna",
        "com.tcl.guard" to "shield",
        "com.tcl.ocean.instructions" to "info",
        "com.tcl.smartalexa" to "mic",
        "com.tcl.dashboard" to "dashboard",
        "com.tcl.exhibit" to "photo",
    )

    fun of(pkg: String): String = map[pkg] ?: "apps"
}

class AppsRepositoryImpl(
    private val packages: PackagesAdapter,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) : AppsRepository {
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    override val apps: StateFlow<List<AppEntry>> = _apps

    /** Last PackageManager scan; pins, order and hiding are re-applied to it without IPC. */
    private var scanned: List<InstalledApp>? = null

    /**
     * Rebuilds the list. [rescan] queries PackageManager (binder IPC, tens of ms on this TV):
     * only at cold start and when packages change. Settings changes just re-sort the last scan.
     */
    @Synchronized
    fun reload(rescan: Boolean = true) {
        val raw = scanned.takeUnless { rescan } ?: packages.launchable().also { l ->
            scanned = l
            Providers.installed = l.mapTo(HashSet()) { it.packageName }
        }
        val s = settings.settings.value
        val pinned = s.pinned.toHashSet()
        val list = raw.map {
            // The old JioCinema app is only a "go to JioHotstar" stub once JioHotstar is installed.
            val moved = Providers.resolve(it.packageName) != it.packageName
            AppEntry(AppId(it.component), if (moved) "JioCinema (moved)" else it.label, AppGlyphs.of(it.packageName), it.packageName in pinned)
        }
        val order = s.customOrder
        val next = if (order.isEmpty()) list.sortedBy { it.label.lowercase() }
        else {
            val pos = HashMap<String, Int>(order.size * 2).apply { order.forEachIndexed { i, p -> putIfAbsent(p, i) } }
            list.sortedWith(compareBy<AppEntry> { pos[it.id.packageName] ?: Int.MAX_VALUE }.thenBy { it.label.lowercase() })
        }
        if (next != _apps.value) _apps.value = next
    }

    fun visible(): List<AppEntry> {
        val hidden = settings.settings.value.hidden.toHashSet()
        return _apps.value.filter { it.id.packageName !in hidden }
    }

    override fun setPinned(id: AppId, pinned: Boolean) {
        settings.update { s ->
            val p = s.pinned.filter { it != id.packageName }
            s.copy(pinned = if (pinned) p + id.packageName else p)
        }
        scope.launch(Dispatchers.Default) { reload(rescan = false) }
    }

    override fun setHidden(id: AppId, hidden: Boolean) {
        settings.update { s ->
            val h = s.hidden.filter { it != id.packageName }
            s.copy(hidden = if (hidden) h + id.packageName else h)
        }
        scope.launch(Dispatchers.Default) { reload(rescan = false) }
    }

    override fun move(id: AppId, delta: Int) {
        val current = visible().map { it.id.packageName }.toMutableList()
        val i = current.indexOf(id.packageName)
        val j = (i + delta).coerceIn(0, current.size - 1)
        if (i < 0 || i == j) return
        current.removeAt(i); current.add(j, id.packageName)
        settings.update { it.copy(customOrder = current) }
        reload(rescan = false) // called on the main thread: no PackageManager IPC here
    }
}

/**
 * Builds HomeSnapshot on one background thread from every source. Publishes only when the
 * result differs; writes the L2 file 2 s later. The UI never waits on any of this.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeRepositoryImpl(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val apps: AppsRepositoryImpl,
    private val habits: HabitStore,
    private val usage: UsageAdapter,
    private val watchNext: WatchNextAdapter,
    private val dao: LauncherDao,
    private val store: SnapshotStore,
    private val watched: WatchedStore? = null,
) : HomeRepository {
    private val worker = Dispatchers.Default.limitedParallelism(1)
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
    private val _snapshot = MutableStateFlow(store.read() ?: HomeSnapshot.EMPTY.copy(slot = TimeSlot.at(LocalTime.now())))
    override val snapshot: StateFlow<HomeSnapshot> = _snapshot
    private var lastRanking = 0L

    @Volatile var watchNextStatus: String = "not checked"
        private set

    /** Package broadcasts come in bursts (an update is REMOVED + ADDED + REPLACED + CHANGED…). */
    private val rescanPending = AtomicBoolean(false)

    override suspend fun refresh(reason: RefreshReason) {
        if (reason == RefreshReason.PACKAGES_CHANGED) {
            if (!rescanPending.compareAndSet(false, true)) return // one is already queued
            try {
                delay(500)
            } catch (e: kotlinx.coroutines.CancellationException) {
                rescanPending.set(false); throw e
            }
        }
        withContext(worker) { rebuild(reason) }
    }

    private suspend fun rebuild(reason: RefreshReason) {
        traced(Sections.SNAPSHOT_BUILD) {
            if (reason == RefreshReason.PACKAGES_CHANGED) rescanPending.set(false)
            when {
                reason == RefreshReason.PACKAGES_CHANGED || reason == RefreshReason.COLD_START || apps.apps.value.isEmpty() -> apps.reload()
                // Pin/hide/order: re-sort the last scan so the snapshot's pin flags are current.
                reason == RefreshReason.SETTINGS_CHANGED -> apps.reload(rescan = false)
            }
            ingestUsage(force = reason == RefreshReason.COLD_START)
            val next = build()
            val cur = _snapshot.value
            if (next.copy(version = cur.version) != cur) {
                val published = next.copy(version = cur.version + 1)
                _snapshot.value = published
                store.writeDebounced(published)
            }
        }
    }

    /** Continue watching was just cleared: rebuild now and write the L2 snapshot without the debounce. */
    suspend fun rebuildNow() {
        withContext(worker) { rebuild(RefreshReason.SETTINGS_CHANGED) }
        store.writeNow(_snapshot.value)
    }

    /**
     * Removal is undoable for [UNDO_MS]: the card is hidden at once (in memory) and the real
     * delete happens only when the undo window closes. A crash in between brings the card back,
     * never loses one by accident.
     */
    private val pendingRemoval = java.util.concurrent.ConcurrentHashMap<String, ContinueItem>()
    @Volatile private var lastRemoved: ContinueItem? = null
    private var commitJob: kotlinx.coroutines.Job? = null

    override fun removeFromUpNext(item: ContinueItem) {
        lastRemoved?.let { prev -> if (prev.key != item.key) commitRemoval(prev) }
        pendingRemoval[item.key] = item
        lastRemoved = item
        commitJob?.cancel()
        commitJob = scope.launch {
            delay(UNDO_MS)
            lastRemoved?.let { commitRemoval(it) }
            lastRemoved = null
        }
        scope.launch { refresh(RefreshReason.SETTINGS_CHANGED) }
    }

    override fun undoRemove(): Boolean {
        val item = lastRemoved ?: return false
        lastRemoved = null
        commitJob?.cancel()
        pendingRemoval.remove(item.key)
        scope.launch { refresh(RefreshReason.SETTINGS_CHANGED) }
        return true
    }

    private fun commitRemoval(item: ContinueItem) {
        if (pendingRemoval.remove(item.key) == null) return
        if (item.source != ContinueSource.WATCHED) settings.update { it.copy(removedUpNext = (it.removedUpNext + item.key).takeLast(100)) }
        if (item.source == ContinueSource.HISTORY) scope.launch { dao.deleteHistory(item.key) }
        // Watched rows are deleted, not remembered as removed: watching the show again brings it back.
        if (item.source == ContinueSource.WATCHED) watched?.delete(item.key.removePrefix("w:"))
    }

    /**
     * UsageStats ingest (binder IPC + a file write): at cold start, then at most once per 10 min.
     * Launches made from this launcher are recorded at launch time, so ranking stays current.
     */
    private fun ingestUsage(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRanking < 10 * 60_000L) return
        lastRanking = now
        if (!usage.hasAccess()) return
        if (!habits.seeded) {
            val from = now - 30L * 86_400_000L
            habits.seed(usage.launches(from, now).map { Providers.resolve(it.packageName) to it.timeMillis })
            prefs.edit().putLong("ingested", now).apply()
        } else {
            val since = prefs.getLong("ingested", now - 86_400_000L)
            for (l in usage.launches(since, now)) habits.ingest(Providers.resolve(l.packageName), l.timeMillis)
            prefs.edit().putLong("ingested", now).apply()
        }
        habits.decay(now)
        habits.save()
    }

    private suspend fun build(): HomeSnapshot {
        val now = System.currentTimeMillis()
        val time = LocalTime.now()
        val s = settings.settings.value
        val visible = apps.visible()
        val byPkg = visible.associateBy { it.id.packageName }
        val ranked = stableRanking(visible, s.pinned, now, time).mapNotNull { byPkg[it] }.take(6)

        val removed = s.removedUpNext.toHashSet().apply { addAll(pendingRemoval.keys) }
        val items = ArrayList<ContinueItem>(3)
        if (s.showUpNext) {
            when (val wn = watchNext.read()) {
                is WatchNextAdapter.Result.Rows -> {
                    watchNextStatus = "open (${wn.rows.size} rows)"
                    for (r in wn.rows) {
                        if (ContinueCleaner.clearedWatchNext(r.lastEngagement, s.continueClearedAt)) continue
                        val item = fromWatchNext(r, byPkg) ?: continue
                        if (item.key !in removed && items.none { it.key == item.key }) items.add(item)
                        if (items.size == 3) break
                    }
                }
                is WatchNextAdapter.Result.Closed -> watchNextStatus = "closed: ${wn.why}"
            }
            if (items.size < 3) {
                // What was actually watched (media sessions) and titles opened from this launcher,
                // newest first. Never bare apps: an app without a title is not something to continue.
                val cands = ArrayList<Pair<Long, ContinueItem>>()
                val history = dao.history(10)
                for (w in watched?.recent(20).orEmpty()) {
                    if (w.finished) continue
                    val item = fromWatched(w, byPkg, now, history) ?: continue
                    if (item.key !in removed) cands += w.updatedAt to item
                }
                // Opening a title from Search is not watching it: only "Save for later" makes a card
                // here. Opened titles name an app's later untitled playback instead (fromAppPlayback).
                for (h in history) {
                    if (!h.savedForLater) continue
                    if (h.key in removed || h.name.isBlank()) continue
                    val svc = Providers.byPackage(h.servicePackage)?.name ?: byPkg[h.servicePackage]?.label ?: h.servicePackage
                    cands += h.openedAt to ContinueItem(
                        key = h.key,
                        title = TitleRef(h.tmdbId, MediaType.valueOf(h.type), h.name),
                        service = Providers.resolve(h.servicePackage).let { p -> if (p == h.servicePackage) AppId(h.serviceComponent) else byPkg[p]?.id ?: AppId("$p/") },
                        serviceName = svc,
                        name = h.name,
                        meta = if (h.savedForLater) "$svc · Saved for later" else "$svc · ${ago(now - h.openedAt)}",
                        progress = null,
                        artKey = h.art ?: "banner:${Providers.resolve(h.servicePackage)}",
                        source = ContinueSource.HISTORY,
                    )
                }
                cands.sortByDescending { it.first }
                for ((_, item) in cands) {
                    val same = WatchedStore.norm(item.name)
                    // One card per show or title: the newest of watched / opened from Search wins.
                    if (items.any { it.key == item.key || WatchedStore.norm(it.name) == same }) continue
                    items.add(item)
                    if (items.size == 3) break
                }
            }
        }

        val first = items.firstOrNull()
        val hero = first?.let { heroFor(it) }
        return HomeSnapshot(
            version = 0,
            slot = TimeSlot.at(time),
            upNext = items,
            apps = ranked,
            hero = hero,
            habitHour = time.hour,
        )
    }

    private var rankKey: String? = null
    private var rankOrder: List<String> = emptyList()

    /**
     * Home's app order changes only when the hour (of the week) changes, or apps / pins change:
     * never because an app was just opened. Launches still update the habit scores, which show
     * from the next hour on. Aliases (the old JioCinema stub when JioHotstar is installed) are left out.
     */
    private fun stableRanking(visible: List<AppEntry>, pinned: List<String>, now: Long, time: LocalTime): List<String> {
        val pkgs = visible.map { it.id.packageName }.filter { Providers.resolve(it) == it }
        val day = java.time.LocalDate.now().dayOfWeek.value
        val key = "$day/${time.hour}|" + pinned.joinToString(",") + "|" + pkgs.sorted().hashCode()
        if (key != rankKey) {
            rankOrder = habits.rank(pkgs, pinned, now)
            rankKey = key
        }
        return rankOrder
    }

    /** "Crunchyroll · S1 E3 · 18 min left", "YouTube · 6 min left", "Netflix · 2 h ago". */
    private fun fromWatched(w0: WatchedRow, byPkg: Map<String, AppEntry>, now: Long, history: List<HistoryRow>): ContinueItem? {
        // The old JioCinema stub plays nothing itself any more: its rows belong to JioHotstar.
        val w = Providers.resolve(w0.pkg).let { p -> if (p != w0.pkg && byPkg[p] != null) w0.copy(pkg = p) else w0 }
        val app = byPkg[w.pkg] ?: return null
        val svc = Providers.byPackage(w.pkg)?.name ?: app.label
        if (w.appOnly) return fromAppPlayback(w, app, svc, now, history)
        val progress = if (w.durationMs > 0) (w.positionMs.toFloat() / w.durationMs).coerceIn(0f, 1f) else null
        val left = if (w.durationMs > 0) ((w.durationMs - w.positionMs) / 60_000L).toInt().coerceAtLeast(1) else null
        val se = when {
            w.season != null && w.episode != null -> "S${w.season} E${w.episode}"
            w.episode != null -> "E${w.episode}"
            else -> null
        }
        val meta = listOfNotNull(svc, se, left?.let { "${duration(it)} left" } ?: ago(now - w.updatedAt)).joinToString(" · ")
        return ContinueItem(
            key = "w:" + w.key, title = WatchedStore.ref(w), service = app.id, serviceName = svc,
            name = w.show, meta = meta, progress = progress?.takeIf { it > 0f },
            // YouTube: the video's own 16:9 thumbnail (found by id) beats the session's small bitmap;
            // Crunchyroll: the episode's own still (found from its name) beats its generic picture.
            artKey = (if (w.pkg == WatchedStore.YOUTUBE || w.pkg == WatchedStore.CRUNCHYROLL) w.tmdbArt ?: w.art else w.art ?: w.tmdbArt) ?: "banner:" + w.pkg,
            source = ContinueSource.WATCHED,
            intentUri = WatchedStore.PREFIX + w.key, season = w.season, episode = w.episode, minutesLeft = left,
            subtitle = w.episodeTitle,
        )
    }

    /**
     * Netflix, Prime…: they play without saying what. The card is the app's banner under its own
     * name: "Netflix" · "23 min left" with a bar when the app's numbers are sane, else
     * "Netflix" · "Watched 2 h ago". OK goes straight back into the app.
     */
    private fun fromAppPlayback(w: WatchedRow, app: AppEntry, svc: String, now: Long, history: List<HistoryRow>): ContinueItem {
        val known = w.durationMs > 0 && w.positionMs in 1 until w.durationMs
        val progress = if (known) w.positionMs.toFloat() / w.durationMs else null
        val left = if (known) ((w.durationMs - w.positionMs) / 60_000L).toInt().coerceAtLeast(1) else null
        val meta = left?.let { "${duration(it)} left" } ?: "Watched ${ago(now - w.updatedAt)}"
        // The app says nothing about what played, but if a title was opened in it from Search just
        // before, that is what is playing: "Panchayat · Prime Video" with the show's art.
        val opened = openedBefore(w, history)
        return ContinueItem(
            key = "w:" + w.key, title = opened?.let { TitleRef(it.tmdbId, MediaType.valueOf(it.type), it.name) },
            service = app.id, serviceName = svc,
            name = opened?.name ?: svc, meta = opened?.let { "$svc · $meta" } ?: meta, progress = progress,
            artKey = opened?.art ?: ("banner:" + w.pkg), source = ContinueSource.WATCHED,
            intentUri = WatchedStore.PREFIX + w.key, minutesLeft = left,
        )
    }

    /** The title last opened (from Search / a title page) in the same service shortly before [w] played. */
    private fun openedBefore(w: WatchedRow, history: List<HistoryRow>): HistoryRow? {
        val provider = Providers.byPackage(w.pkg)
        return history.filter { h ->
            !h.savedForLater && h.name.isNotBlank() &&
                (h.servicePackage == w.pkg || (provider != null && Providers.byPackage(h.servicePackage) == provider)) &&
                h.openedAt <= w.updatedAt && w.updatedAt - h.openedAt < OPENED_WINDOW_MS
        }.maxByOrNull { it.openedAt }?.takeIf { runCatching { MediaType.valueOf(it.type) }.isSuccess }
    }

    private fun fromWatchNext(r0: WatchNextRow, byPkg: Map<String, AppEntry>): ContinueItem? {
        val r = Providers.resolve(r0.packageName).let { p -> if (p != r0.packageName && byPkg[p] != null) r0.copy(packageName = p) else r0 }
        val app = byPkg[r.packageName] ?: return null
        val svc = Providers.byPackage(r.packageName)?.name ?: app.label
        val progress = if (r.durationMs > 0) (r.positionMs.toFloat() / r.durationMs).coerceIn(0f, 1f) else null
        val left = if (r.durationMs > 0) ((r.durationMs - r.positionMs) / 60_000L).toInt() else null
        val s = r.season?.toIntOrNull(); val e = r.episode?.toIntOrNull()
        val meta = when {
            s != null && e != null -> "$svc · S$s E$e"
            left != null -> "$svc · ${duration(left)} left"
            else -> svc
        }
        return ContinueItem(
            key = "wn:" + r.id, title = null, service = app.id, serviceName = svc,
            name = r.title, meta = meta, progress = progress?.takeIf { it > 0f },
            artKey = r.artUri ?: "banner:" + r.packageName, source = ContinueSource.WATCH_NEXT,
            intentUri = r.intentUri, season = s, episode = e, minutesLeft = left,
        )
    }

    companion object {
        /** How long a removed card can be brought back. */
        const val UNDO_MS = 5_500L
        /** An untitled playback this soon after a title was opened in the same app is that title. */
        private const val OPENED_WINDOW_MS = 4 * 60 * 60_000L

        fun heroFor(it: ContinueItem): HeroInfo = it.hero()

        fun duration(min: Int): String = if (min >= 60) "${min / 60}h %02dm".format(min % 60) else "$min min"

        fun ago(ms: Long): String {
            val m = ms / 60_000L
            return when {
                m < 2 -> "just now"
                m < 60 -> "$m min ago"
                m < 48 * 60 -> "${m / 60} h ago"
                else -> "${m / (24 * 60)} days ago"
            }
        }
    }
}
