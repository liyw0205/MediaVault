#!/usr/bin/env bash
# Build MediaVault debug APK on Termux (aarch64 aapt2).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/MediaVault" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
cd "$ROOT"
gradle assembleDebug --no-daemon
OUT="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
VER=$(grep versionName app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)".*/\1/')
DEST="$(dirname "$ROOT")/MediaVault_${VER}_debug.apk"
cp -f "$OUT" "$DEST"
ls -lh "$OUT" "$DEST"
aapt dump badging "$OUT" 2>/dev/null | head -3