#!/usr/bin/env bash
# Compiles omni-track-core against the Android SDK and runs the JVM codec tests.
# Usage: ./run_tests.sh [ANDROID_SDK_ROOT]
set -euo pipefail

SDK="${1:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
PLATFORM_JAR="$SDK/platforms/android-34/android.jar"
if [ ! -f "$PLATFORM_JAR" ]; then
  echo "android.jar not found at $PLATFORM_JAR (install platform 34 or edit this script)" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/test-classes"

echo "==> compiling core sources"
javac --release 11 -nowarn -classpath "$PLATFORM_JAR" -d "$OUT/classes" \
  $(find "$ROOT/src" -name '*.java')

echo "==> compiling tests (pure JVM classes only)"
javac --release 11 -nowarn -classpath "$OUT/classes" -d "$OUT/test-classes" \
  $(find "$ROOT/test" -name '*.java')

echo "==> running codec tests"
java -classpath "$OUT/test-classes:$OUT/classes" org.omnitrack.core.CodecTest

echo "==> OK"
