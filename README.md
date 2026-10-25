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
Home footer links: Search, All apps, Inputs, Settings · Input key → Inputs · Settings key → Quick settings.

## Gates
`./gradlew :data:testReleaseUnitTest` (local search < 5 ms) ·
`./gradlew :benchmark:connectedBenchmarkAndroidTest` (cold start, warm Home, nav.key, held-key frames).

## Notes from the first install on the TCL (G09_4K_GB)
- `cmd package set-home-activity` does not stick on this firmware: Home is held by Google TV, so setup turns on the accessibility fallback (`HomeFallbackService`). It survives normal updates. A **force-stop** turns it off, and Android delivers nothing to a force-stopped app. To restore it, open Swiss Home once from Apps (it re-enables itself using the `WRITE_SECURE_SETTINGS` grant), or re-run setup.
- Watch Next is system-only here, so Up next is built from the launcher's own history and last-used apps.
- Picture → `com.tcl.settings.PictureActivity`; Sound → `com.tcl.settings.SoundActivity`; Sleep → Energy saving (all inside `com.android.tv.settings`).
- Inputs: HDMI 1–3 = `com.tcl.tvinput/.TvPassThroughService/HW15|HW16|HW17`; the remote's Input key sends `KEYCODE_TV_INPUT`.
