
Swiss Dark TV Launcher — HLD & LLD
 · 
Summary
We build a single-APK Android TV launcher for the TCL 55C61B that turns a D-pad press into a visible focus change with under 10 ms of app work per press, so every move lands on the very next screen refresh.
On a 60 Hz panel a new frame is drawn every 16.7 ms. If the launcher finishes its work in under 10 ms, the move is always shown on the next frame, with about 6 ms of headroom for the system's own render step. The app cannot control the time the remote takes to transmit or the TV panel's own picture processing; those add a fixed delay that is the same for every app, including Google TV.
The design reaches this by doing all heavy work (ranking, images, network, disk) before the press, keeping the next screen state in memory, and making a focus move change only a few numbers on the GPU rather than rebuilding the screen.
The launcher keeps Google TV, Play Store, Chromecast, DRM and system updates untouched; it replaces only the Home screen.
Goals and performance budgets
Every budget below is a release gate: a build that misses one on the real TV does not ship.
Goals
Every screen in the Swiss dark design: four time-of-day homes, All apps, title page, search, where to watch, inputs, quick settings, launcher settings, screensaver.
Navigation that feels instant on a budget TV chip, measured on the 55C61B itself, not an emulator.
Survives TCL and Google updates through a re-runnable setup script.
Non-goals
Replacing Google TV's "For You" feed, rooting, or a custom ROM.
Phone or tablet layouts. The launcher targets one 1920 × 1080 TV canvas.
Analytics, ads or any account system.
Metric
Budget
Measured how
D-pad press → frame submitted (app work)
p95 < 8 ms, p99 < 10 ms
Perfetto trace section nav.key to RenderThread sync
Frames over 16.7 ms while holding a D-pad key
0 in a 10 s scroll
Macrobenchmark FrameTimingMetric
Home key → Home visible (warm)
< 120 ms
am start timing, Perfetto
Cold start → first Home frame
< 700 ms
Macrobenchmark StartupTimingMetric
Local search (apps, history) per keystroke
< 5 ms
Microbenchmark of the index
Online search first results
< 400 ms p50
Trace from keystroke to list bind
Wallpaper slot change
Cross-fade starts < 16 ms after the boundary
Bitmap prepared 5 min early
Memory (PSS) on Home
< 140 MB
dumpsys meminfo
Garbage collections during a 10 s scroll
0
Allocation tracker
APK size
< 10 MB
Wallpapers downloaded, not bundled
HLD: system context
The launcher is one APK that sits between the remote and the TV's existing services; it talks to the internet only for titles and wallpapers.
Everything inside the grey band stays exactly as TCL ships it, which is what keeps DRM, Chromecast and OTA updates working.
HLD: architecture and threads
Five layers, each depending only on the one below, and five threads, of which only the main thread may touch what is on screen.
The main thread does three things on a press: read an array, write an integer, redraw two items. Everything slow lives on the four other threads and reaches the screen only as a finished, immutable result.
HLD: the navigation hot path
A D-pad press triggers four steps of app work with a combined budget under 8 ms; everything they need was prepared before the press.
The per-step numbers are budgets the benchmarks enforce, not measurements yet; week 2 produces the first real numbers on the TV.
Data, caching and startup
The Home screen never waits for anything: it draws from a ready-made snapshot in memory, and every source refreshes that snapshot in the background.
Three cache tiers
Tier
Holds
Read time
Written when
L1 memory
HomeSnapshot (immutable), decoded bitmaps for visible items ±2, focus graph
< 0.1 ms
Any source changes
L2 disk snapshot
Last HomeSnapshot as one protobuf file, read with memory mapping
3–8 ms
After each L1 change, debounced 2 s
L3 sources
Package list, usage stats, Watch Next, TMDB, wallpapers
10 ms – 2 s
Their own schedules (below)
Refresh schedule
Source
Trigger
Thread
Installed apps
PACKAGE_ADDED/REMOVED/CHANGED broadcast
Background
Habit ranking
Home resumed, at most once per 10 min
Background
Continue watching
Home resumed + Watch Next change notification
Background
Trending, new titles, where to watch
WorkManager, once a day on Wi-Fi, and on title open if older than 24 h
Background
Wallpaper for next slot
AlarmManager 5 min before 06:00, 12:00, 18:00, 23:00
Background
Startup order (cold)
Process starts; Baseline Profile means the Home code is already compiled ahead of time, not interpreted.
Memory-map the L2 snapshot and the current wallpaper's pre-scaled file; draw the first frame from them.
Only after the first frame: start refreshers, register broadcast receivers, warm the image cache.
A changed snapshot is swapped in atomically; the focused item never moves under the user.
As the system's Home app, the launcher is kept in memory with high priority, so cold starts are rare (after reboot or an update); the warm Home path is the one that matters daily.
Platform integrations and update resilience
Every system feature goes through one small adapter in :platform:tv, so a TCL or Google change breaks one class, not the app.
Feature
Android API
Permission or setup
Status
Be the Home screen
HOME + LEANBACK_LAUNCHER intent filters; ADB cmd package set-home-activity
Setup script
Solid
Home-button fallback
AccessibilityService catching Home
User enables once
Fallback only
App list and banners
PackageManager.queryIntentActivities, getApplicationBanner
<queries> in manifest
Solid
Habit ranking
UsageStatsManager.queryEvents
appops set … GET_USAGE_STATS allow via ADB
Solid
Continue watching
TvContractCompat.WatchNextPrograms
May need a system-only permission
Test on TV
Inputs and CEC names
TvInputManager.getTvInputList, open with buildChannelUriForPassthroughInput
None for listing
Test on TV
Voice search
SpeechRecognizer
RECORD_AUDIO
Solid
Picture and sound modes
TCL private settings
Probably blocked
Fallback: open TCL settings page
Screensaver
DreamService
User picks it in Settings
Solid
Titles, posters, where to watch
TMDB REST API, region IN
Free API key, JustWatch credit
Solid
Surviving updates
setup.sh (laptop, ADB over Wi-Fi) is idempotent: disables the chosen bloat with pm disable-user, grants usage access, sets the home activity. Safe to run any number of times.
On every boot the launcher checks whether it is still the Home app. If not, it shows one line on its own screen: "A TV update reset Home — run setup again."
Never disabled: Google TV's launcher package (holds the profile picker), setup wizard, Play services, TCL's TV input app.
LLD: modules and key interfaces
Kotlin, Jetpack Compose for TV, one activity, 14 Gradle modules; feature modules depend only on :core and :domain, never on each other.
Module
Owns
:app
MainActivity, Home intent filters, DI graph (Hilt), Baseline Profile
:core:design
Swiss tokens (colours, Archivo type scale, 12-col grid), IconTile, PosterCard, FocusRing
:core:focus
Focus engine (LLD below)
:core:perf
Trace sections, JankStats hook, debug latency overlay
:domain
Models, use cases, repository interfaces
:feature:home
Home screen, four time-of-day variants are one screen with a different wallpaper
:feature:apps
All apps grid, pin/hide/move
:feature:search
Search start, results, filters
:feature:title
Title page, where to watch, episodes
:feature:panels
Inputs and quick settings side panels
:feature:settings
Launcher settings, wallpaper picker
:feature:screensaver
DreamService with clock over wallpaper
:data
Repositories, protobuf snapshot store, Room (titles, search history), TMDB client
:platform:tv
Adapters around every Android TV API in the table above
```
        // never empty after first frame
    suspend fun refresh(reason: RefreshReason)
}
interface AppLauncher {
    fun launch(app: AppId): LaunchResult           // main thread,       // already decoded at 1920x1080
    fun slotFor(time: LocalTime): TimeSlot
}
interface SearchEngine {
    fun local(query: String): List      // synchronous, >
}]]>
```
LLD: data models and storage
All UI models are immutable and marked @Immutable, so Compose can skip any part of the screen whose data did not change.
```
,   // max 3
    val apps: ImmutableList,         // max 6, habit-ordered
    val hero: HeroInfo                  // text for the focused Up next item
)
@Immutable data class AppEntry(
    val id: AppId,                      // package + activity, value class over Int index
    val label: String,
    val icon: IconRef,                  // Tabler glyph id or banner cache key
    val pinned: Boolean
)
@Immutable data class ContinueItem(
    val title: TitleRef, val service: AppId,
    val name: String, val meta: String,
    val progress: Float?,               // null for new titles
    val artKey: ImageKey                // pre-sized 480x270 thumbnail
)
@Immutable data class Availability(
    val service: ProviderId, val kind: Kind,   // STREAM, RENT, BUY, FREE
    val inMyPlan: Boolean, val priceInr: Int?
)]]>
```
Store
Technology
Contents
Size
Home snapshot
One protobuf file, memory-mapped
Last HomeSnapshot + focus graph
< 50 KB
Settings
Proto DataStore
Pins, hidden apps, services you pay for, wallpaper per slot, dim levels
< 10 KB
Habits
Small binary file, 16 apps × 168 hour-of-week buckets of Float
Decayed launch counts
< 11 KB
Titles and search history
Room (SQLite), FTS4 table for history
TMDB titles, availability, last 50 searches
< 5 MB, 30-day expiry
Images
Disk cache, pre-sized WebP
Posters 342 px, thumbnails 480 px, wallpapers 1920 × 1080
< 150 MB, LRU
LLD: focus engine
A D-pad press is one array lookup and one integer write; nothing is searched, measured or rebuilt.
Compose's built-in focus search walks the layout tree and measures distances on every press. On a slow chip that alone can cost several milliseconds. We replace it with a precomputed table.
Focus graph. Every focusable item on a screen gets an index 0…n. When a screen's layout is known (once, after its first layout pass), we store for each index its four neighbours and its on-screen rectangle:
```
 g.up[cur]; DOWN -> g.down[cur]
            LEFT -> g.left[cur]; RIGHT -> g.right[cur]
        }
        if (next >= 0) focused.intValue = next   // the only write
        Trace.endSection()
        return next >= 0
    }
}]]>
```
Drawing focus without rebuilding. One FocusRing layer sits above the screen. It reads rects for the focused index inside graphicsLayer { translationX…; translationY… }, so a move changes only GPU properties: no recomposition and no layout. Item colours (amber title on the focused card) switch through a derivedStateOf { focused == myIndex } read in the draw phase, so only two items redraw: the one losing focus and the one gaining it.
Rows that scroll. Lazy rows keep 2 items pre-composed beyond each edge (beyondBoundsItemCount = 2), so the next item is always ready before focus reaches it. Scrolling is a translation of an already-built row.
Held keys. A held D-pad repeats about every 50 ms. Repeats skip the 120 ms ring animation and jump directly, so focus never falls behind the finger. The hero text and progress for the newly focused item update in the same frame from the snapshot; its artwork comes from the L1 bitmap cache, which already holds items ±2 around focus.
LLD: snapshot, habit ranking and wallpapers
All three run off the main thread and hand the UI a finished result; none of them can delay a key press.
Snapshot builder. One coroutine on a single background thread merges the sources into a new HomeSnapshot. If the result equals the current one, nothing is published. Otherwise it is published through StateFlow, the focus graph is rebuilt off-thread, and both are written to disk 2 s later. The item that has focus keeps focus by id, even if it moved position.
Habit ranking. For each app we keep a launch score per hour of the week (168 buckets). Each launch adds 1 to its bucket; once a day every bucket is multiplied by 0.97, so old habits fade in about a month.
```
```
H is the decayed count for the current hour-of-week bucket h, the neighbours smooth over edges of the hour, and R is recency (1 if used in the last 2 hours, decaying to 0 over a day). Pinned apps always come first. With 16 apps this is about 50 multiply-adds: microseconds.
Wallpaper scheduler. Four slots: morning 06–12, afternoon 12–18, evening 18–23, night 23–06.
Five minutes before a boundary, an AlarmManager alarm decodes the next wallpaper on a background thread to 1920 × 1080 (inSampleSize plus scaling), darkens it, then copies it into a GPU-resident HARDWARE bitmap.
At the boundary, the Home screen cross-fades old to new over 600 ms; both bitmaps are already on the GPU.
The old bitmap is released after the fade.
The darkening is not a second full-screen layer: it is applied once as a colour filter while the wallpaper is decoded, saving one full-screen overdraw per frame.
LLD: search and where to watch
Search answers in two waves: local results in under 5 ms on every keystroke, online results once typing pauses.
Wave 1, local (same frame as the keystroke). A sorted array of normalised app labels, recent searches and cached TMDB titles, searched by binary prefix lookup plus a word-start match ("you" finds "YouTube Kids"). About 2,000 entries at most; lookup is under 1 ms.
Wave 2, online.
Wait 150 ms after the last keystroke (debounce), cancel any older request.
Check Room for the same query from the last 24 h; if found, show it immediately.
Otherwise call TMDB search/multi (titles and people) with region=IN. One shared OkHttp client, HTTP/2, connection kept warm while the search screen is open.
For each result, read availability from Room; missing ones are fetched from TMDB watch/providers in one batch after the list is drawn, so posters never wait for availability.
Filters (My services, Everything, Free, Kids-safe) run on the in-memory list; switching filters costs no network.
Trending. trending/all/week and trending/all/day fetched once a day; the search start screen opens from cache.
Where to watch. TMDB returns, per country, the services that stream, rent or buy a title, with data supplied by JustWatch. We map each TMDB provider id to an installed package through a small bundled table (Netflix, Prime Video, JioHotstar, SonyLIV, ZEE5, Apple TV, YouTube). inMyPlan = provider is in the user's services list in Settings.
Opening a title.
Service
How
Fallback
YouTube
Video or search intent
—
Netflix, Prime Video, JioHotstar, others
App search intent with the title text, if the app accepts it
Open the app's home
Trailer
YouTube with the TMDB trailer key
Hide the button
Which apps accept a search intent is checked on the TV once and stored, so the launcher never tries a route that fails.
LLD: images and memory
No image is ever decoded larger than it is drawn, and no image is decoded on the main thread.
Google TV draws its interface on a 1920 × 1080 canvas even on a 4K panel (video plays at full 4K underneath). A 4K wallpaper decoded at full size takes about 33 MB and over 100 ms on a TV chip; scaled to 1080p it takes about 8 MB and is drawn sharp at the size the interface actually uses. The 4K originals are downloaded once and immediately re-encoded to 1080p WebP on disk. Check with adb shell wm size on the 55C61B; if it reports 3840 × 2160, keep 4K for the wallpaper only.
Image
Drawn at
Decoded as
Each
Held in memory
Wallpaper (current + next during fade)
1920 × 1080
Hardware bitmap
~8 MB
2 → 16 MB
Up next thumbnail
480 × 270
Hardware bitmap
~0.5 MB
3 + 4 prefetched → 3.5 MB
Poster (search, trending)
228 × 342
Hardware bitmap
~0.3 MB
30 → 9 MB
Title page backdrop
1920 × 1080
Hardware bitmap
~8 MB
1 → 8 MB
App icons
24 px vector
Path, no bitmap
< 1 KB
all → < 50 KB
Image loader. Coil 3 with a custom size resolver that always requests the exact drawn size, one decode thread at normal priority for the focused row and one at low priority for prefetch. Requests for items that scroll out of range are cancelled.
Memory budget (PSS on Home): code and runtime 45 MB, images 40 MB, Compose and UI 25 MB, data 10 MB, headroom 20 MB → 140 MB. On a 2 GB TV, staying small keeps the launcher alive in the background so the Home button never triggers a cold start.
Performance verification
The 10 ms target is proved on the 55C61B over ADB on every release build, not assumed from a fast phone.
Test
Tool
Scenario
Pass if
Navigation latency
Perfetto + trace section nav.key
200 single presses across Home, All apps, search results
p95 < 8 ms, p99 < 10 ms from key event to frame submitted
Held-key scroll
Macrobenchmark FrameTimingMetric
Hold Right for 10 s across All apps
0 frames over 16.7 ms
Warm Home
Macrobenchmark
Open YouTube, press Home, 20 runs
median < 120 ms
Cold start
Macrobenchmark StartupTimingMetric
Force-stop, press Home, 10 runs
median < 700 ms
Local search
Microbenchmark
2,000-entry index, 1–6 letter queries
< 5 ms worst case
Allocations on the hot path
Allocation tracker during scroll
Hold Down 10 s on Home
0 GCs
Recompositions per press
Compose compiler metrics + layout inspector
One Right press on Up next
≤ 2 items recomposed, 0 relayouts
Build settings that the numbers depend on
Release build only: R8 full mode, Baseline Profile and Startup Profile generated from the benchmarks above.
Compose compiler strong skipping on; every UI model @Immutable; no lambdas allocated in item composables.
StrictMode in debug catches disk or network on the main thread.
A debug-only overlay shows the last 60 key-to-frame times in the corner, so slow screens are found while using the TV.
CI gate. A cheap Android TV box on the desk runs the suite nightly; the 55C61B runs it before each release. A regression over 10 % against the last release fails the build.
Risks, open questions and build order
The biggest risk is not speed but access: what TCL's firmware lets a third-party Home app do.
Risk
Effect
Fallback
TCL update resets the Home app
Google TV returns
Boot check + one-command setup script
Watch Next is closed to us
No real "Continue" row
Show last-used apps with their last title from our own history
Compose too slow on this chip
Misses 10 ms on dense grids
Swap only that grid to a RecyclerView inside Compose; focus engine unchanged
Apps ignore search intents
Title opens app home, not the show
Title page tells you what to search for
TMDB key limits or outage
No posters or where to watch
30-day Room cache; Home never depends on TMDB
Open questions (answer on the TV in week 1)
Chip, RAM and Android TV version: adb shell getprop ro.board.platform, cat /proc/meminfo, getprop ro.build.version.release.
Interface resolution: adb shell wm size (1080p or 4K canvas).
Can a third-party app read Watch Next on this firmware?
Which streaming apps accept a search intent?
Do HDMI passthrough intents switch inputs without TCL's app in front?
Build order
Week 1 — Probe app: answers the five questions above; setup script.
Week 2 — Skeleton: :core:design, :core:focus, Home with static data; latency overlay; first Perfetto numbers.
Week 3 — Real data: apps, habits, wallpapers by time of day, snapshot cache.
Week 4 — All apps, inputs and quick settings panels, screensaver.
Week 5 — Search, title page, where to watch (TMDB).
Week 6 — Launcher settings, Baseline Profiles, full benchmark suite passing on the 55C61B.
