#!/usr/bin/env bash
# Build MediaVault debug APK on Termux (aarch64 aapt2).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
cd "$ROOT"

PROPS_WRAPPER="$ROOT/gradle/wrapper/gradle-wrapper.properties"
GRADLE_PROPS="$ROOT/gradle.properties"
ZIP="$ROOT/gradle/wrapper/gradle-8.9-bin.zip"

bash "$ROOT/gradle/wrapper/ensure-gradle-zip.sh"

PROPS_BAK=""
GRADLE_PROPS_BAK=""
restore_props() {
  if [[ -n "$PROPS_BAK" && -f "$PROPS_BAK" ]]; then
    mv -f "$PROPS_BAK" "$PROPS_WRAPPER"
  fi
  if [[ -n "$GRADLE_PROPS_BAK" && -f "$GRADLE_PROPS_BAK" ]]; then
    mv -f "$GRADLE_PROPS_BAK" "$GRADLE_PROPS"
  fi
}
trap restore_props EXIT

# 本地有 zip 时用 file://，避免重复从网络拉 dist；CI 不跑本脚本，仓库 properties 仍为 HTTPS。
if [[ -f "$ZIP" ]]; then
  PROPS_BAK="$(mktemp)"
  cp "$PROPS_WRAPPER" "$PROPS_BAK"
  FILE_URI="$(python3 -c "from pathlib import Path; print(Path('$ZIP').resolve().as_uri())")"
  sed -i "s|^distributionUrl=.*|distributionUrl=${FILE_URI//\//\\/}|" "$PROPS_WRAPPER"
  sed -i 's/^validateDistributionUrl=.*/validateDistributionUrl=false/' "$PROPS_WRAPPER"
fi

chmod +x "$ROOT/gradlew" 2>/dev/null || true
if [[ "$#" -gt 0 ]]; then
  GRADLE_TASKS=("$@")
else
  GRADLE_TASKS=(assembleDebug)
fi

# 选用可在本机执行的 aapt2（勿把此行提交进 gradle.properties，否则 CI 会找 Termux 路径）
find_local_aapt2() {
  if [[ -d "${ANDROID_HOME}/build-tools" ]]; then
    local bt a
    for bt in $(ls -1 "${ANDROID_HOME}/build-tools" 2>/dev/null | sort -V -r); do
      a="${ANDROID_HOME}/build-tools/${bt}/aapt2"
      if [[ -x "$a" ]] && file "$a" 2>/dev/null | grep -qE 'aarch64|ARM'; then
        echo "$a"
        return 0
      fi
    done
  fi
  local AAPT2
  for AAPT2 in /data/data/com.termux/files/usr/bin/aapt2 "${PREFIX:-}/bin/aapt2"; do
    if [[ -x "$AAPT2" ]]; then
      echo "$AAPT2"
      return 0
    fi
  done
  return 1
}

AAPT2="$(find_local_aapt2 || true)"
if [[ -z "$AAPT2" ]]; then
  echo "pack_mediavault: 未找到可执行的 aapt2（Termux 包或 aarch64 build-tools）" >&2
  exit 1
fi
echo "pack_mediavault: aapt2=$AAPT2"

# 清掉 Gradle 缓存里 Maven 拉下来的 x86_64 aapt2，否则会忽略 override 仍去跑 linux 包
while IFS= read -r -d '' bad; do
  rm -rf "$(dirname "$(dirname "$bad")")" 2>/dev/null || true
done < <(find "${HOME}/.gradle/caches" -path '*/transformed/aapt2-*/aapt2' -print0 2>/dev/null)

GRADLE_PROPS_BAK="$(mktemp)"
cp "$GRADLE_PROPS" "$GRADLE_PROPS_BAK"
if grep -q '^android.aapt2FromMavenOverride=' "$GRADLE_PROPS"; then
  sed -i "s|^android.aapt2FromMavenOverride=.*|android.aapt2FromMavenOverride=$AAPT2|" "$GRADLE_PROPS"
else
  printf '\n# pack_mediavault.sh only — restored on exit; do not commit\nandroid.aapt2FromMavenOverride=%s\n' "$AAPT2" >> "$GRADLE_PROPS"
fi

./gradlew "${GRADLE_TASKS[@]}" --no-daemon

COPY_APK=false
for task in "${GRADLE_TASKS[@]}"; do
  case "$task" in
    assembleDebug|:app:assembleDebug)
      COPY_APK=true
      ;;
  esac
done

if [[ "$COPY_APK" != true ]]; then
  exit 0
fi

OUT="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
VN=$(grep versionName "$ROOT/app/build.gradle.kts" | head -1 | sed 's/.*"\(.*\)".*/\1/')
DEST="$(dirname "$ROOT")/MediaVault_${VN}_debug.apk"
cp -f "$OUT" "$DEST"
ls -lh "$OUT" "$DEST"
aapt dump badging "$OUT" 2>/dev/null | head -3 || true
