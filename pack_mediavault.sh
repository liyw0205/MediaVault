#!/usr/bin/env bash
# Build MediaVault debug APK on Termux (aarch64 aapt2).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
cd "$ROOT"

PROPS="$ROOT/gradle/wrapper/gradle-wrapper.properties"
ZIP="$ROOT/gradle/wrapper/gradle-8.9-bin.zip"

bash "$ROOT/gradle/wrapper/ensure-gradle-zip.sh"

PROPS_BAK=""
restore_props() {
  if [[ -n "$PROPS_BAK" && -f "$PROPS_BAK" ]]; then
    mv -f "$PROPS_BAK" "$PROPS"
  fi
}
trap restore_props EXIT

# 本地有 zip 时用 file://，避免重复从网络拉 dist；CI 不跑本脚本，仓库 properties 仍为 HTTPS。
if [[ -f "$ZIP" ]]; then
  PROPS_BAK="$(mktemp)"
  cp "$PROPS" "$PROPS_BAK"
  FILE_URL="distributionUrl=file\\:///$(echo "$ZIP" | sed 's| |%20|g')"
  sed -i "s|^distributionUrl=.*|$FILE_URL|" "$PROPS"
  sed -i 's/^validateDistributionUrl=.*/validateDistributionUrl=false/' "$PROPS"
fi

chmod +x "$ROOT/gradlew" 2>/dev/null || true

# Termux 本机 aapt2（勿写入 gradle.properties，否则 CI 会失败）
GRADLE_EXTRA=()
for AAPT2 in /data/data/com.termux/files/usr/bin/aapt2 "$PREFIX/bin/aapt2"; do
  if [[ -x "$AAPT2" ]]; then
    GRADLE_EXTRA+=("-Dandroid.aapt2FromMavenOverride=$AAPT2")
    break
  fi
done

./gradlew assembleDebug --no-daemon "${GRADLE_EXTRA[@]}"

OUT="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
VN=$(grep versionName "$ROOT/app/build.gradle.kts" | head -1 | sed 's/.*"\(.*\)".*/\1/')
DEST="$(dirname "$ROOT")/MediaVault_${VN}_debug.apk"
cp -f "$OUT" "$DEST"
ls -lh "$OUT" "$DEST"
aapt dump badging "$OUT" 2>/dev/null | head -3