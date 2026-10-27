# Swiss Home — dark Swiss-grid launcher for the TCL Google TV

Built from the *Swiss Dark TV Launcher — HLD & LLD* doc and the *Minimal TV Launcher* design canvas.
One APK replaces only the Home screen; Google TV, Play Store, Chromecast, DRM and OTA stay untouched.

## Target (read over ADB)
TCL Smart TV Pro (G09_4K_GB) · Android TV 12 (API 31) · Amlogic T5M, armeabi-v7a · 2 GB RAM ·
panel 3840 × 2160 @ 60/120 Hz, HDR · UI canvas 1920 × 1080 @ density 320 → design px = dp 1:1.

## Build and install
```
./gradlew :app:assembleRelease          # 2.9 MB APK (budget < 10 MB)
TV=192.168.31.55 TMDB_KEY=<v3 key or v4 token> ./setup/setup.sh
```
`setup.sh` is idempotent: installs, grants usage access / mic / Wi-Fi name, pushes the 1080p wallpapers,
sets the screensaver, disables only the chosen `BLOAT`, compiles speed-profile, and sets Home
(falls back to an accessibility service if the firmware refuses). `UNDO=1` gives Home back to Google TV.

## Modules (as the LLD)
`:app` · `:core:design` (tokens, Archivo, 12-col grid, Tabler glyphs) · `:core:focus` (array focus engine)
· `:core:perf` (trace sections, JankStats, latency overlay) · `:domain` · `:data` · `:platform:tv`
· `:feature:{home,apps,search,title,panels,settings,screensaver}` · `:benchmark` (macrobenchmarks).

## Remote
Up/Down/Left/Right move focus · OK opens · Hold OK: remove a card / pin, hide, move an app ·
Home footer links: Search, All apps, Inputs, Settings, then icon-only Restart and Power off ·
Input key → Inputs · Settings key → Quick settings. In All apps, Up on the top row opens the
letter bar: pick a letter, OK jumps to it.

## What the launcher does
- **Home** — clock, date, live weather (open-meteo), wallpaper + focus backdrops, Continue
  watching, Apps by habit, all driven by one array focus engine (no recomposition per key).
- **Continue watching** — per-app rows with position and progress; episode rows resolve to their
  series (name, numbers, artwork) through TVmaze → OMDb → TMDB → IMDb, confirmed by episode name
  and numbers before anything is shown; a wrong-show name collision can never overwrite the card.
- **Search** — local catalog and YouTube, TMDB title pages, where-to-watch.
- **Panels** — Inputs (HDMI), Quick settings, Launcher settings (comfort text size, weather city).
- **Now-playing chip**, letter jump in All apps, Restart (restarts the launcher) and
  Power off (device-admin `lockNow()`, same as the remote's power key) from the header.

## ADB helper hooks (all DUMP-protected: shell only, apps cannot send them)
```
am broadcast -a com.swissdark.launcher.CONFIG       --es tmdb_key <key> --es omdb_key <key> --es services netflix,prime
am broadcast -a com.swissdark.launcher.RENAME_SHOW  --es key 'in.startv.hotstar|latest' --es show 'VisionQuest'
am broadcast -a com.swissdark.launcher.WATCHED_DUMP                      # rows → logcat -s WatchedDump
am broadcast -a com.swissdark.launcher.DEBUG_SCREEN_OFF                  # clear Continue as on standby
am broadcast -a com.swissdark.launcher.DEBUG_SEED   --ez remove true     # demo cards on/off
```
`RENAME_SHOW` names the series for a row the catalogs cannot resolve (very new shows); the
automatic chain picks such titles up on its own once the web indexes them (~24 h).

## Repository map
`HANDOVER.md` (what is done, what is left, TV commands) · `docs/HLD-LLD.md` ·
`docs/design/project/*.dc.html` (the design the app must match) · `docs/agent-briefs/`
(safety rules and test commands) · `docs/IMPL-2026-10-26.md` (feature round) ·
`docs/tools/ring.py` (screenshot focus checks).

## Gates
`./gradlew :data:testReleaseUnitTest` (local search < 5 ms) ·
`./gradlew :benchmark:connectedBenchmarkAndroidTest` (cold start, warm Home, nav.key, held-key frames).

## Notes from the first install on the TCL (G09_4K_GB)
- `cmd package set-home-activity` does not stick on this firmware: Home is held by Google TV, so setup turns on the accessibility fallback (`HomeFallbackService`). It survives normal updates. A **force-stop** turns it off, and Android delivers nothing to a force-stopped app. To restore it, open Swiss Home once from Apps (it re-enables itself using the `WRITE_SECURE_SETTINGS` grant), or re-run setup.
- Watch Next is system-only here, so Up next is built from the launcher's own history and last-used apps.
- Picture → `com.tcl.settings.PictureActivity`; Sound → `com.tcl.settings.SoundActivity`; Sleep → Energy saving (all inside `com.android.tv.settings`).
- Inputs: HDMI 1–3 = `com.tcl.tvinput/.TvPassThroughService/HW15|HW16|HW17`; the remote's Input key sends `KEYCODE_TV_INPUT`.
