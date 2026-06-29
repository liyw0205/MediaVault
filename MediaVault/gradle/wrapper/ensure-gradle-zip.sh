#!/usr/bin/env bash
# 若 gradle/wrapper/gradle-8.9-bin.zip 不存在则从官方下载（供 Termux 离线 wrapper 使用）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ZIP="$ROOT/gradle/wrapper/gradle-8.9-bin.zip"
URL="https://services.gradle.org/distributions/gradle-8.9-bin.zip"

if [[ -f "$ZIP" ]]; then
  echo "Gradle zip 已存在: $ZIP"
  exit 0
fi

echo "下载 Gradle 8.9 → $ZIP"
mkdir -p "$(dirname "$ZIP")"
if command -v curl >/dev/null 2>&1; then
  curl -fL --retry 3 --connect-timeout 30 -o "$ZIP" "$URL"
elif command -v wget >/dev/null 2>&1; then
  wget -O "$ZIP" "$URL"
else
  echo "需要 curl 或 wget" >&2
  exit 1
fi
ls -lh "$ZIP"