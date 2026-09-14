#!/usr/bin/env bash
# Builds a debug-signed installable APK without Gradle, using only the Android
# SDK's build-tools. Produces dist/omni-bridge.apk.
#
# Usage: ./build_apk.sh [ANDROID_SDK_ROOT]
set -euo pipefail

SDK="${1:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
BT="$SDK/build-tools/35.0.0"
PLATFORM_JAR="$SDK/platforms/android-34/android.jar"
for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$PLATFORM_JAR"; do
  if [ ! -e "$f" ]; then echo "missing tool: $f" >&2; exit 1; fi
done

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build"
DIST="$ROOT/dist"
rm -rf "$OUT" "$DIST"
mkdir -p "$OUT/classes" "$OUT/dex" "$DIST"

echo "==> aapt2: link manifest"
"$BT/aapt2" link \
  -I "$PLATFORM_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --min-sdk-version 29 \
  --target-sdk-version 34 \
  --version-code 1 \
  --version-name 0.1.0 \
  -o "$OUT/base.apk"

echo "==> javac: core + bridge"
javac --release 11 -nowarn \
  -classpath "$PLATFORM_JAR" \
  -d "$OUT/classes" \
  $(find "$ROOT/../omni-track-core/src" "$ROOT/src" -name '*.java')

echo "==> d8: dex"
CLASS_FILES=( $(find "$OUT/classes" -name '*.class') )
"$BT/d8" --release --min-api 29 --lib "$PLATFORM_JAR" --output "$OUT/dex" "${CLASS_FILES[@]}"

echo "==> package dex into apk"
(cd "$OUT" && zip -q -j base.apk dex/classes.dex)

echo "==> zipalign + sign"
KEYSTORE="$ROOT/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass omnitrack -keypass omnitrack \
    -alias omnitrack -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=OmniTrack Debug,O=OmniTrack,C=US" >/dev/null 2>&1
fi
"$BT/zipalign" -f 4 "$OUT/base.apk" "$DIST/omni-bridge.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:omnitrack "$DIST/omni-bridge.apk"
"$BT/apksigner" verify "$DIST/omni-bridge.apk"

echo "==> built: $DIST/omni-bridge.apk"
ls -la "$DIST"
