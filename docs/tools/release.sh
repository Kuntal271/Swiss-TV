#!/bin/bash
# Cut a release of Swiss Home: build release, read the APK's own version, hash it,
# write update.json and publish both to GitHub Releases (the launcher's OTA feed).
#   docs/tools/release.sh [notes...]
# Needs `gh` authorised as Kuntal271 (gh auth login — device flow, once).
set -euo pipefail
cd "$(dirname "$0")/../.."

export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleRelease

APK=app/build/outputs/apk/release/app-release.apk
SDK=/opt/homebrew/share/android-commandlinetools
AAPT=$(ls "$SDK"/build-tools/*/aapt2 2>/dev/null | sort | tail -1)
[ -n "$AAPT" ] || { echo "aapt2 not found under $SDK/build-tools"; exit 1; }

BADGE=$("$AAPT" dump badging "$APK" 2>/dev/null)
VC=$(printf '%s' "$BADGE" | sed -nE "s/.*versionCode='([0-9]+)'.*/\1/p")
VN=$(printf '%s' "$BADGE" | sed -nE "s/.*versionName='([^']*)'.*/\1/p")
[ -n "$VC" ] && [ -n "$VN" ] || { echo "could not read version from badging: $BADGE"; exit 1; }
SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)
TAG="v$VC"
NOTES="${*:-Swiss Home $VN (versionCode $VC).}"

mkdir -p build
cat > build/update.json <<EOF
{"versionCode": $VC, "versionName": "$VN", "url": "https://github.com/Kuntal271/Swiss-TV/releases/download/$TAG/app-release.apk", "sha256": "$SHA"}
EOF

echo "== version $VN (code $VC) sha256 $SHA"
gh release create "$TAG" "$APK#Swiss Home $VN" build/update.json \
  --repo Kuntal271/Swiss-TV --title "Swiss Home $VN" --notes "$NOTES"
echo "== released $TAG — the TV picks it up on its next check (releases/latest/download/update.json)"
