# Swiss Home — handover (written 2026-10-26, ~00:15 IST)

An Android TV launcher ("Swiss Dark") built for the owner's TCL TV. This file is everything a new agent needs to continue.

## 1. Where things are
| What | Where |
|---|---|
| Repo (git, no remote) | `/Users/kuntal/swiss-tv-launcher` |
| **Branch to work on** | `wip-fixes-1025`, HEAD `5f856fa` (this is what is installed on the TV) |
| Last fully verified build | `main` = `a9a32c0` (older; `wip-fixes-1025` is 7 commits ahead, partly unverified) |
| Old worktree | `/Users/kuntal/swiss-tv-launcher-redesign` (branch `redesign`, already merged into main; ignore) |
| HLD + LLD | `docs/HLD-LLD.md` (copy of the owner's artifact https://claude.ai/artifact/N2DnyUBNHFMhfWunrSCypE?sk=sHopdNn-lkzUdk_Z4pH8PA ) |
| Design mockups (one HTML per screen) | `docs/design/project/*.dc.html` + `docs/design/*.jpg` + `canvas.json` (owner's artifact https://claude.ai/artifact/PxaEL81nBQx3igfAiiZmin?sk=lA1dDSbb4ZG1wPOjunY7NQ ) |
| Agent briefs (rules, safety, test commands) | `docs/agent-briefs/AGENT_BRIEF.md` (read first), `TESTER_BRIEF.md`, `FIX_BRIEF.md` |
| Screenshots | `docs/screens/` (more in the session scratchpad, which is temporary) |
| Helper | `docs/tools/ring.py` measures the yellow focus ring in a screenshot (pure Python, no PIL) |
| Memory notes (Claude Code) | `/Users/kuntal/.claude/projects/-Users-kuntal/memory/swiss-tv-launcher.md` |
| Full session transcript | `/Users/kuntal/.claude/projects/-Users-kuntal/b9b65d0b-ed8d-472e-b136-cea1d60964e8.jsonl` |

`README.md` is partly outdated (still says Archivo font, accessibility fallback, Watch Next). This file wins.

## 2. The TV and how to reach it
- TCL Smart TV Pro G09_4K_GB, Android 12 (API 31), Amlogic t5m, armeabi-v7a, ~2 GB RAM. UI canvas 1920x1080 on a 4K panel (everything draws at 2x, so full-screen layers are expensive on its GPU).
- `adb connect 192.168.31.55:5555` then `export ANDROID_SERIAL=192.168.31.55:5555`. Network: Jio AirFiber (DNS-blocks themoviedb.org; `api.tmdb.org` works).
- Google TV Home (`com.google.android.apps.tv.launcherx`) and `com.google.android.tungsten.setupwraith` are disabled via `pm disable-user`; Swiss Home holds `android.app.role.HOME`. Do NOT re-enable them, run `UNDO=1 setup.sh`, or uninstall `com.swissdark.launcher` (it loses the Home role and settings).
- The accessibility fallback service is deliberately OFF (it costs ~10 ms/frame in every app).

### Owner's hard rules (never break)
1. **Never power off / sleep the TV** (no KEYCODE_POWER/SLEEP/SOFT_SLEEP, no `svc power`, no screensaver/dream start, no Sleep option in Quick settings).
2. **Never send blind key presses.** Check `adb shell dumpsys activity activities | grep mResumedActivity` first; only press keys when it is `com.swissdark.launcher`. Never press keys inside system Settings or any system UI. `KEYCODE_SETTINGS` opens the *system* Settings on this TV. Never touch Wi-Fi/network (an earlier agent script turned Wi-Fi off).
3. The owner uses the TV for real. If YouTube/another app is in front that you did not open, leave it alone (I interrupted YouTube once by installing; avoid).
4. "Don't raise HITLs, do as I said": work autonomously, report at the end.
5. Use D-pad via `adb shell input dpad keyevent KEYCODE_DPAD_*` (plain `input keyevent` has source 0x0 and may be ignored on Home).
6. Owner wants helper agents on **Sonnet 5.5** (`model: "sonnet"` in the Agent tool).
7. One agent on the TV at a time: lock dir `<scratchpad>/tv.lock` (see AGENT_BRIEF.md), sessions <= 4 min, always release.

## 3. Build / install / test
```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd /Users/kuntal/swiss-tv-launcher
./gradlew -q :app:assembleRelease :data:testDebugUnitTest :core:focus:testDebugUnitTest
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -m speed -f com.swissdark.launcher    # required; measured fastest (406 ms cold)
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```
- First-time/idempotent setup: `TV=192.168.31.55 OMDB_KEY=<owner's key> ./setup/setup.sh` (disables Google Home + setupwraith, sets the HOME role, grants WRITE_SECURE_SETTINGS + notification listener, pushes wallpapers). `GOOGLE_HOME=keep` skips the disabling.
- Release builds are not debuggable (no `run-as`). Frames: `adb shell dumpsys gfxinfo com.swissdark.launcher reset` ... `dumpsys gfxinfo`. Warm Home: `am start -W -a android.intent.action.MAIN -c android.intent.category.HOME`.
- Perf budgets (HLD): key-to-frame p95 < 8 ms, 0 janky frames while holding a key, warm Home < 120 ms (measured 169 ms, open), cold start < 700 ms (ok ~400-550), PSS on Home < 140 MB (88 MB), APK < 10 MB (3.3 MB).
- Test hooks (DUMP-protected, adb only):
  - `adb shell am broadcast -a com.swissdark.launcher.DEBUG_SEED -p com.swissdark.launcher [--ez remove true]` adds/removes 3 fake Continue watching cards (keys start `demo`).
  - `adb shell am broadcast -a com.swissdark.launcher.DEBUG_SCREEN_OFF -p com.swissdark.launcher [--ez force true]` runs the clear-on-TV-off path (deletes real cards!).
  - `adb shell am broadcast -a com.swissdark.launcher.CONFIG --es tmdb_key ... --es omdb_key ... --es services netflix,prime`.
  - `adb shell setprop log.tag.SwissMedia DEBUG` logs every media-session sample.

## 4. Architecture (details in docs/HLD-LLD.md)
Kotlin + Jetpack Compose, 15 Gradle modules, manual DI (`data/DataGraph.kt`), draw-phase focus (no recomposition on D-pad moves).
- `core/focus`: `FocusEngine.kt` / `FocusScreen.kt` (geometric neighbours from item rects, `FocusScreenState`, `KeyRouter`, ring drawing, eased `scrollTo`, `ArrayFocusEngine`).
- `core/design`: tokens, `Type.kt` (Inter variable), `Components.kt` (Header/Footer/NavChip/AppTile/PosterCard), `Motion.kt`, `FocusBackdrop.kt`, `Glyphs.kt`.
- `core/perf`, `domain` (models, `Providers` list of streaming services), `data`, `platform/tv` (Apps, Launch, Inputs, MediaSessions, TclSettings, Usage, WatchNext), `feature/{home,apps,search,title,panels,settings,screensaver}`, `app` (MainActivity, AppRoot routes, Services, manifest), `benchmark`.
- Settings are protobuf-like: `domain/Models.kt LauncherSettings`; **append new fields only at the end** (field numbers follow declaration order).
- Continue watching pipeline: `MediaListenerService` (NotificationListenerService) -> `MediaSessionWatcher` -> `WatchedStore` (Room `watched.db`, `PlaybackGate` for title-less apps) -> `HomeRepositoryImpl.fromWatched` -> Home cards. Metadata: OMDb (owner's key) + TVmaze (+TMDB if a valid key; the owner's TMDB key is invalid). Backdrops: `data/BackdropArt.kt` (TVmaze/OMDb lookup, web-search + TVmaze confirmation for episode-only titles like Crunchyroll, scrims baked into the bitmap, LRU 4).
- Watch Next provider is signature|privileged: unreadable on this TV.

## 5. Design decisions (owner-approved)
- Inter font; header nav (Search / All apps / Inputs / Settings) as icon+label buttons, focused = solid yellow `#F2C230` fill with black text; bottom bar = key hints left, focused item's name right (muted). One focus language: tiles get yellow ring + 1.04 scale, text controls get yellow fill.
- Home = clock/date top-left, hero under it, one band: "Continue watching" (left, 3 cards) + "Apps by habit" 2x3 grid (right-aligned). Larger real app logos (readable for the owner's father); icon packs optional.
- "Continue watching" (renamed from Up next) shows only real watched titles, never bare apps. Netflix/Prime/JioHotstar get title-less progress cards. Cleared when the TV turns off (setting, default on).
- Focused card's art becomes the Home background (show art from our own lookup; fallback = card art). Crunchyroll card = the episode's TVmaze still; background = show art (Black Clover for "Day of Atonement").
- Signature easter egg bottom-right of Home: "Made with ♥ by Kuntal".

## 6. What was done (chronological summary)
1. Built the whole launcher from HLD/LLD + design; installed natively as Home on the TV; Google TV Home disabled.
2. 15-agent QA/optimise/E2E round; fixed Quick settings layout, Sleep->Screen focus jump, app-open polish; profiled app switching/stress.
3. Continue watching from media sessions; OMDb+TVmaze metadata; Netflix/Prime cards; rename; Inter font; new nav; real icons; icon packs; Home layout reverted to the previous arrangement with new design; focus backdrop; clear-on-TV-off; All apps eased scrolling + edge fades; apps grid right-aligned.
4. Day 2 (today): All apps edge inset (`EDGE_INSET` 16 dp) so first/last column focus boxes sit inside the page margins; Search closes the IME after submit and moves focus to results; Crunchyroll card uses the episode still (`BackdropArt.episodeArtUrl`, `DataGraph.watchCardArt`); Easter egg; card-switching animation reworked (scrims baked into art, old art kept opaque under new = no haze, 90 ms debounce / 300 ms fade, prefetch of all card art, hero text slides from the side you moved toward, `HERO_SLIDE`); All apps ring snaps onto the focused tile (`RING_SCROLL_MS`), fixing the ring drifting outside icons on quick Down presses; debug seed hook.

## 7. What is LEFT (priority order)
Everything below sits on `wip-fixes-1025`; items marked (written) have code in the branch from the paused round but were **never verified on the TV**.
1. **Verify on the TV the day-2 changes**: Crunchyroll episode still on a real card; All apps edge boxes symmetric (use `docs/tools/ring.py`: expect ring x ~120..1800); Search keyboard closes after submit; new card-switching animation on real cards; quick-Down ring in All apps.
2. **JioHotstar never appears in Continue watching.** `in.startv.hotstar` publishes no media session while idle (verified). Needs: (a) ask the owner to play a title 2-3 min and inspect `dumpsys media_session` + `SwissMedia` logs; (b) fallback: treat an app with no/placeholder session as watched after ~3 min foreground via UsageStats (see `platform/tv/Usage.kt`) -> app-only card "Watched X ago"; map the old JioCinema stub `com.jio.media.stb.ondemand` to the same JioHotstar card (`Providers.resolve`, written).
3. **Search** (written, unverified): IME stale-connection blocker (keys dropped on Home after leaving Search; the current fix keeps them working in a smoke test), field/genre-chip focus, DOWN from field to Recent, Back restores state, posters by IMDb id, duplicates, Kids genre, mic feedback, no Google/data-source wording.
4. **Continue watching** (written, unverified): YouTube resume by exact video id (`resolveYouTube`, `watchUrl`), OK resumes directly, undo on remove + Settings "Bring back removed", no card from merely opening an app, hand-off message for Netflix/Prime/JioHotstar (`AppRoot.kt` ~line 328), title-page polish, stable apps-by-habit order.
5. **Home/All apps/Settings/Panels** (written, unverified): Home focus memory + hero stickiness + header reachability; Launcher settings LEFT/UP/RIGHT + 350 ms dwell; Quick settings false "selected" marks; leftover developer/Google/setup.sh wording; route transitions overlapping; panel slide sliver; return focus to the launched tile; All apps menu covering a tile; Move-mode hints.
6. **Perf**: warm Home 169 ms vs 120 ms budget; card-switch frames: median 17 ms, ~5% over 30 ms (cause unknown; GPU 8 ms/frame vs 4 ms idle).
7. Run the robustness E2E tester (never finished), Netflix card check (owner must play Netflix >= 2.5 min), a real standby test of clear-on-off, then merge `wip-fixes-1025` into `main`.
8. Cleanup: `README.md` is outdated; no git remote exists (owner asked about "push": clarify; they may want a GitHub repo).

## 8. Known gotchas
- Reinstall/`force-stop` brings the last app to front; after install start HOME.
- `adb install -r` + speed-profile left the app in "verify" state; always run `cmd package compile -m speed -f`.
- Plain `input text` does not type into Search (no text connection); use the on-screen keyboard with D-pad.
- Compose focus rects must exclude animation transforms (animations are draw-only).
- zsh: unquoted `--include=*.kt` fails; quote globs. `echo ==` fails; use `echo '--'`.
- Commit identity: kuntal / kuntal@zamp.ai; commit trailer `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` (earlier commits say Opus 5.5).
- API keys are not stored in the repo. The owner has an OMDb key (valid) and a TMDB key (invalid); ask them for the OMDb key when re-running setup. The TV already has it in app prefs.
