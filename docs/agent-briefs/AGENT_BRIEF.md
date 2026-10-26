# Swiss Dark TV Launcher: shared brief for all agents

Read this whole file before you start. It is the only shared context you have.

## The product
An Android TV launcher (the Home app) for the user's TCL "Smart TV Pro" (product G09_4K_GB).
- Android 12 (API 31), Amlogic t5m, 32-bit ARM, about 2 GB RAM.
- The UI draws at 1920x1080. A DesignCanvas sets density = screenWidthPx/1920, so 1 dp = 1 design px.
- Style is "Swiss Dark": colours #0A0A0B / #141416 / #EDEDED, focus yellow #F2C230, the Archivo font, and a 12-column grid. Everything is driven by the D-pad.
- Google TV's own Home (com.google.android.apps.tv.launcherx) and the setup wizard (com.google.android.tungsten.setupwraith) are now disabled. Swiss Home is the only Home app.
  - Do NOT re-enable either package.
  - Do NOT run `UNDO=1 setup.sh`.
  - Do NOT uninstall com.swissdark.launcher. Uninstalling loses the Home role and the user's settings.

## Code
The code is in /Users/kuntal/swiss-tv-launcher. It is a Kotlin + Jetpack Compose project with 15 Gradle modules, and it is a git repo (baseline commits exist).
- core/focus/FocusEngine.kt, FocusScreen.kt
  - The focus engine. FocusGraphBuilder computes neighbours geometrically from item rects.
  - FocusScreenState, KeyRouter, FocusRoot draws the yellow ring; focusItem() is the modifier.
- core/design: Tokens.kt (colours, grid span(n)), Type.kt, Components.kt (Header, Footer, Backdrop, IconTile, PosterCard, etc.), Glyphs.kt.
- core/perf/Perf.kt: trace sections, KeyLatency, JankStats, LatencyOverlay.
- domain: models and interfaces.
- data: DataGraph.kt (manual DI and startBackground), Repositories.kt, Titles.kt, Wallpapers.kt, Stores.kt, Habits.kt, Tmdb.kt, Search.kt, Work.kt.
- platform/tv: Apps.kt, Launch.kt (LaunchAdapter: starting other apps), Inputs.kt, TclSettings.kt, Usage.kt, WatchNext.kt, Voice.kt, Network.kt.
- Feature screens:
  - feature/home/HomeScreen.kt
  - feature/apps/AllAppsScreen.kt
  - feature/search/SearchScreen.kt
  - feature/title/TitleScreens.kt (UpNextTitleScreen and TitleDetailsScreen)
  - feature/panels/Panels.kt (InputsPanel and QuickSettingsPanel)
  - feature/settings/LauncherSettingsScreen.kt
  - feature/screensaver
- app:
  - MainActivity.kt (routes stack; dispatchKeyEvent goes to KeyRouter)
  - AppRoot.kt (route to screen; Home always stays composed underneath, with alpha 0)
  - Services.kt
  - AndroidManifest.xml
  - proguard rules
  - baseline-prof.txt
- benchmark/: macrobenchmarks.
- setup/setup.sh: idempotent installer.

### Design source of truth
The design is a set of HTML mockups, one per screen. Read the one you need.

Location: /private/tmp/claude-501/-Users-kuntal/b9b65d0b-ed8d-472e-b136-cea1d60964e8/scratchpad/artifact-files/b9eb6fbc-d577-4dfd-85aa-e0b482156717/project/

- Home: SwHome / SwHomeDay / SwHomeAfternoon / SwHomeNight
- Screens: SwAllApps, SwSearchStart, SwSearch, SwSearchResults, SwTitle, SwWhereToWatch, SwInputs, SwQuickSettings, SwLauncherSettings, SwScreensaver, SwSystem
- canvas.json
- The *.jpg files in the parent folder are the design's images.

The HLD/LLD is in the same scratchpad folder: hld.md.

### Performance budgets
These come from the HLD and are release gates, measured on the real TV:
- D-pad press to frame submitted (app work): p95 < 8 ms, p99 < 10 ms. The trace section is "nav.key".
- Frames over 16.7 ms while holding a D-pad key: 0 in a 10 s scroll.
- Home key to Home visible (warm): < 120 ms.
- Cold start to first Home frame: < 700 ms.
- Local search per keystroke: < 5 ms.
- Memory (PSS) on Home: < 140 MB.
- GCs during a 10 s scroll: 0.
- APK size: < 10 MB.

## Build
```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd /Users/kuntal/swiss-tv-launcher
./gradlew -q :feature:panels:compileReleaseKotlin            # compile just your module (fast)
./gradlew -q :app:assembleRelease                              # full APK -> app/build/outputs/apk/release/app-release.apk
./gradlew -q :data:testDebugUnitTest                           # unit tests
```
- Several agents share this working tree at the same time. If Gradle says it is waiting for a lock, just wait or retry.
- A compile error in a file you do not own is someone else's work in progress. Do not touch it. Wait a minute and retry.

## The TV (shared, physical, one at a time)
Use adb over Wi-Fi at 192.168.31.55:5555.

Only ONE agent may drive the TV at a time: key presses, installs, screenshots, and starting activities. Read-only `dumpsys` is also safer done under the lock.
```
LOCK=/private/tmp/claude-501/-Users-kuntal/b9b65d0b-ed8d-472e-b136-cea1d60964e8/scratchpad/tv.lock
until mkdir "$LOCK" 2>/dev/null; do sleep 5; done; echo "<your agent name>" > "$LOCK/owner"
adb connect 192.168.31.55:5555 >/dev/null; export ANDROID_SERIAL=192.168.31.55:5555
# ... your session: keep it under ~5 minutes ...
adb shell input keyevent KEYCODE_HOME     # leave the TV on Home when you finish
rm -rf "$LOCK"                             # ALWAYS release, even after errors
```
- Batch your on-TV work into short sessions, release the lock, and analyse screenshots afterwards.
- If the lock owner file is older than 10 minutes, the holder died. Remove the lock and take it.

### Useful commands
- Keys: `adb shell input keyevent KEYCODE_DPAD_DOWN` (also UP, LEFT, RIGHT, CENTER, BACK, HOME, MENU, SETTINGS, TV_INPUT, SEARCH).
- Hold OK: `adb shell input keyevent --longpress KEYCODE_DPAD_CENTER`.
- Screenshot: `adb exec-out screencap -p > <scratchpad>/<you>-<n>.png`. Then view it with the Read tool. Name files with your agent name.
- Top activity: `adb shell dumpsys activity activities | grep mResumedActivity`.
- Crashes: `adb logcat -c` before a session, then `adb logcat -d | grep -E "FATAL|AndroidRuntime: |ANR"`.
- Memory: `adb shell dumpsys meminfo com.swissdark.launcher | grep -E "TOTAL|Native|Java|Graphics"`.
- Frames: `adb shell dumpsys gfxinfo com.swissdark.launcher reset`, then interact, then `dumpsys gfxinfo com.swissdark.launcher`.
- Warm Home timing: `adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME`.
- Install a build (holding the lock), from a FULL successful `:app:assembleRelease` only:
  ```
  adb install-multiple -r app/build/outputs/apk/release/app-release.apk app/build/outputs/apk/release/baselineProfiles/0/app-release.dm
  adb shell cmd package compile -m speed -f com.swissdark.launcher   # REQUIRED after every install: measured fastest (406 ms cold)
  ```
- Release builds are not debuggable, so run-as does not work.
- The TV may be in real use. Do not change TV system settings or picture/sound settings, and do not factory reset. Do not install other apps.

## HARD SAFETY RULE (an earlier script turned the TV's Wi-Fi off)
Never send blind key sequences.
- Before EVERY key press (or batch of D-pad moves), check `adb shell dumpsys activity activities | grep mResumedActivity`. Only continue if it is com.swissdark.launcher, or the specific app you deliberately launched.
- If anything else is in front, stop and press KEYCODE_BACK/HOME. Never press OK/DPAD_CENTER/long-press inside com.android.tv.settings or any system UI.
- KEYCODE_SETTINGS opens the SYSTEM Settings app on this TV, not our panel; KEYCODE_TV_INPUT opens TCL's picker. To open our panels, use Home's footer links or KEYCODE_MENU.
- Never open network, Wi-Fi, power, or reset pages.
- NEVER power the TV off or put it to sleep (user's explicit instruction). That means no KEYCODE_POWER, KEYCODE_SLEEP or KEYCODE_SOFT_SLEEP, no `input keyevent 26/223/276`, and no `svc power`, `reboot` or `am start` of the screensaver or dream.
- Never select a Sleep option in Quick settings.

## Bugs the user reported (their words, lightly cleaned)
1. "In the quick settings, it's not very upright right now". The Quick settings panel looks misaligned or unbalanced.
   - From a screenshot: there is an empty bordered row above PICTURE.
   - The focus "ring" on a row shows as a yellow line above the row, not a proper focus treatment.
   - The rows' label and value columns may not line up with the design.
2. "Down padding: sleep directly goes to screen". In Quick settings, D-pad DOWN from the SLEEP row jumps straight to SCREEN.
   - It skips NETWORK, which is probably not focusable.
   - Navigation order and behaviour are wrong.
3. "In the home section, while opening the individual apps (such as TV, YouTube, and Crunchyroll), the UI isn't very good right now". Opening apps from Home looks poor.
   - Selecting an Up next card for an app-only item (no title metadata, e.g. "Crunchyroll · Last used 3 h ago") opens a near-empty title page.
   - App tiles and launch transitions may flash or look unfinished.
4. The user wants Google's experience gone and Swiss Home to feel superior. Any leftover Google-TV wording in our UI is wrong now, for example the Home footer hint "Hold Home for Google TV". Google TV's Home is disabled, so remove such wording and behaviour.

## Reporting
End with a concise report:
- What you changed (files), or what you found.
- For every bug or issue: severity (blocker/major/minor), exact repro steps (key sequence), expected vs actual, and screenshot paths.
- Measured numbers where relevant.
- Be factual. Say what you could not verify.
