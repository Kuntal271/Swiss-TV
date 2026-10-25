#!/usr/bin/env bash
# Swiss Home — one-command, idempotent setup for the TCL Google TV over ADB (Wi-Fi).
# Safe to run any number of times, and again after any TCL or Google update.
#
#   TV=192.168.31.55 ./setup/setup.sh                 # install + configure
#   TMDB_KEY=xxxx ./setup/setup.sh                    # also store a TMDB key (titles, trending, where to watch)
#   OMDB_KEY=xxxx ./setup/setup.sh                    # OMDb key: titles via OMDb + TVmaze when TMDB is unusable
#   SERVICES=netflix,prime,jiohotstar ./setup/setup.sh
#   PHOTOS=~/Pictures/tv ./setup/setup.sh             # push your own wallpapers
#   BLOAT="com.tcl.exhibit com.tcl.ocean.instructions" ./setup/setup.sh   # disable chosen bloat
#   UNDO=1 ./setup/setup.sh                           # give Home back to Google TV, re-enable bloat
set -euo pipefail

TV="${TV:-192.168.31.55}"
PORT="${PORT:-5555}"
PKG="com.swissdark.launcher"
HOME_ACT="$PKG/.MainActivity"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
APK="${APK:-$ROOT/app/build/outputs/apk/release/app-release.apk}"
# The baseline profile ships as a .dm next to the APK; installing both gives status=speed-profile.
DM="${DM:-$ROOT/app/build/outputs/apk/release/baselineProfiles/0/app-release.dm}"
BLOAT="${BLOAT:-com.tcl.exhibit com.tcl.ocean.instructions}"
# Never touched: Google TV's launcher (profile picker), setup wizard, Play services, TCL's TV input app.
PROTECTED="com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith com.google.android.gms com.tcl.tv com.android.vending"

say() { printf '\033[1;33m›\033[0m %s\n' "$*"; }
ok()  { printf '\033[1;32m✓\033[0m %s\n' "$*"; }
warn(){ printf '\033[1;31m!\033[0m %s\n' "$*"; }

say "Connecting to $TV:$PORT"
adb connect "$TV:$PORT" >/dev/null || true
S="$TV:$PORT"
adb -s "$S" get-state >/dev/null 2>&1 || { warn "TV not reachable. Turn it on, enable Network debugging, then rerun."; exit 1; }
A() { adb -s "$S" shell "$@"; }

if [[ "${UNDO:-}" == "1" ]]; then
  say "Giving Home back to Google TV"
  A pm enable com.google.android.apps.tv.launcherx >/dev/null 2>&1 && ok "re-enabled Google TV Home" || true
  A pm enable com.google.android.tungsten.setupwraith >/dev/null 2>&1 && ok "re-enabled setup wizard" || true
  A cmd role remove-role-holder --user 0 android.app.role.HOME "$PKG" >/dev/null 2>&1 || true
  A cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity || true
  for p in $BLOAT; do A pm enable "$p" >/dev/null 2>&1 && ok "re-enabled $p" || true; done
  A settings delete secure enabled_accessibility_services >/dev/null 2>&1 || true
  ok "Done. Uninstall with: adb -s $S uninstall $PKG"
  exit 0
fi

say "TV: $(A getprop ro.product.manufacturer) $(A getprop ro.product.model) · Android $(A getprop ro.build.version.release) · $(A getprop ro.board.platform) · UI $(A wm size | tail -1 | awk '{print $3}')"

if [[ ! -f "$APK" ]]; then
  say "Building release APK"
  (cd "$ROOT" && ./gradlew -q :app:assembleRelease)
fi
say "Installing $(basename "$APK") ($(du -h "$APK" | cut -f1))"
if [[ -f "$DM" ]]; then adb -s "$S" install-multiple -r -g "$APK" "$DM" >/dev/null; else adb -s "$S" install -r -g "$APK" >/dev/null; fi
ok "installed $(A dumpsys package $PKG | grep -m1 versionName | xargs)"

say "Permissions"
A appops set "$PKG" GET_USAGE_STATS allow && ok "usage access (habit ranking)"
A pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null && ok "microphone (voice search)" || warn "microphone grant refused"
A pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS 2>/dev/null && ok "secure settings (keeps the Home fallback on across updates)" || warn "secure settings grant refused: re-run setup after each update"
A pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION 2>/dev/null && ok "location (Wi-Fi name only)" || warn "location grant refused"
if A pm grant "$PKG" com.android.providers.tv.permission.READ_EPG_DATA 2>/dev/null; then ok "Watch Next read permission"; else warn "Watch Next permission is system-only here: Up next uses the launcher's own history"; fi
# Up next: a notification listener may read media sessions (what is playing, how far in). It reads no notifications.
if A cmd notification allow_listener "$PKG/$PKG.MediaListenerService" 2>&1 | grep -qiE "error|exception|not allowed"; then warn "media-session access refused: Up next only shows titles opened from Search"; else ok "media sessions (Up next: what you watched)"; fi

say "First start (creates the app's folders)"
A am start -W -n "$HOME_ACT" >/dev/null
for d in wallpapers photos; do A mkdir -p "/sdcard/Android/data/$PKG/files/$d"; done

say "Wallpapers (1080p WebP, re-encoded from 4K originals)"
for f in "$HERE"/wallpapers/*.webp; do
  adb -s "$S" push "$f" "/sdcard/Android/data/$PKG/files/wallpapers/" >/dev/null && echo "   $(basename "$f")"
done
if [[ -n "${PHOTOS:-}" && -d "$PHOTOS" ]]; then
  say "Your photos from $PHOTOS"
  for f in "$PHOTOS"/*.{jpg,jpeg,png,webp,JPG,JPEG,PNG}; do
    [[ -f "$f" ]] || continue
    adb -s "$S" push "$f" "/sdcard/Android/data/$PKG/files/photos/" >/dev/null && echo "   $(basename "$f")"
  done
fi

if [[ -n "${TMDB_KEY:-}" || -n "${OMDB_KEY:-}" || -n "${SERVICES:-}" ]]; then
  say "Launcher config"
  args=()
  [[ -n "${TMDB_KEY:-}" ]] && args+=(--es tmdb_key "$TMDB_KEY")
  [[ -n "${OMDB_KEY:-}" ]] && args+=(--es omdb_key "$OMDB_KEY")
  [[ -n "${SERVICES:-}" ]] && args+=(--es services "$SERVICES")
  A am broadcast -a "$PKG.CONFIG" -n "$PKG/.ConfigReceiver" "${args[@]}" >/dev/null && ok "stored"
fi

say "Screensaver"
A settings put secure screensaver_components "$PKG/.SwissDream"
A settings put secure screensaver_default_component "$PKG/.SwissDream" 2>/dev/null || true
A settings put secure screensaver_enabled 1
ok "Swiss clock is the screensaver"

if [[ -n "$BLOAT" ]]; then
  say "Disabling chosen bloat (pm disable-user, reversible with UNDO=1)"
  for p in $BLOAT; do
    if [[ " $PROTECTED " == *" $p "* ]]; then warn "refusing to disable protected $p"; continue; fi
    A pm disable-user --user 0 "$p" >/dev/null 2>&1 && ok "$p" || echo "   $p not present"
  done
fi

say "Compiling ahead of time (speed)"
A cmd package compile -m speed -f "$PKG" >/dev/null && ok "compiled (speed: measured 406 ms cold vs 552 ms speed-profile)"

say "Setting Home"
# Google TV Home outranks any third-party Home, and the setup wizard's RecoveryActivity
# takes over when it is gone, so both are disabled (UNDO=1 re-enables them).
# GOOGLE_HOME=keep leaves them alone and relies on the accessibility fallback instead.
if [[ "${GOOGLE_HOME:-off}" != "keep" ]]; then
  A pm disable-user --user 0 com.google.android.apps.tv.launcherx >/dev/null 2>&1 && ok "Google TV Home disabled"
  A pm disable-user --user 0 com.google.android.tungsten.setupwraith >/dev/null 2>&1 && ok "setup-wizard recovery Home disabled"
fi
A cmd role add-role-holder --user 0 android.app.role.HOME "$PKG" >/dev/null 2>&1 || true
A cmd package set-home-activity "$HOME_ACT" >/dev/null 2>&1 || true
CUR="$(A cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep '/' | head -1 | tr -d '\r ')"
if [[ "$CUR" == "$HOME_ACT" || "$CUR" == "$PKG/$PKG.MainActivity" ]]; then
  ok "Home button opens Swiss Home"
  A settings delete secure enabled_accessibility_services >/dev/null 2>&1 || true
else
  warn "Home still resolves to $CUR — enabling the accessibility fallback"
  A settings put secure enabled_accessibility_services "$PKG/$PKG.HomeFallbackService"
  A settings put secure accessibility_enabled 1
fi

A input keyevent KEYCODE_HOME
ok "Done. Press Home on the remote."
