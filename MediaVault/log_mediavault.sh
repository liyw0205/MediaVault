#!/usr/bin/env bash
# MediaVault 播放 / 远程 / 刮削 相关 logcat
#
# 用法:
#   ./log_mediavault.sh                 # 录到 Ctrl+C
#   ./log_mediavault.sh 120             # 录 120 秒后结束
#   ./log_mediavault.sh 120 com.mediavault
#   LOG_DIR=~/Downloads ./log_mediavault.sh
#
# 环境:
#   Linux / macOS：手机 USB 调试，本机执行 adb logcat（自动走 adb）
#   Termux / 设备本机：直接 logcat（无 adb 或 adb 不可用时）
#
# 可选: APP_PACKAGE, LOG_DIR, ADB（adb 可执行文件路径）

set -u

APP_PACKAGE="${2:-${APP_PACKAGE:-com.mediavault}}"
DURATION="${1:-}"
SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || SCRIPT_DIR="."
LOG_DIR="${LOG_DIR:-$SCRIPT_DIR}"
ADB="${ADB:-adb}"

mkdir -p "$LOG_DIR" 2>/dev/null || {
  echo "无法创建日志目录: $LOG_DIR"
  exit 1
}

# --- 选择 logcat 来源：主机 adb 或本机 logcat ---
USE_ADB=0
if command -v "$ADB" >/dev/null 2>&1; then
  if "$ADB" get-state >/dev/null 2>&1; then
    USE_ADB=1
    echo "使用 adb 从已连接设备抓 log"
  fi
fi

if [ "$USE_ADB" -eq 0 ]; then
  if command -v logcat >/dev/null 2>&1; then
    echo "使用本机 logcat（Termux / 设备 shell）"
  else
    echo "错误: 未找到 adb 连接，且本机无 logcat。请安装 platform-tools 或在本机 Termux 运行。"
    exit 1
  fi
fi

stamp="$(date +%Y%m%d_%H%M%S)"
if [ -n "$APP_PACKAGE" ]; then
  LOG_FILE="$LOG_DIR/logcat_mediavault_${APP_PACKAGE}_${stamp}.log"
else
  LOG_FILE="$LOG_DIR/logcat_mediavault_all_${stamp}.log"
fi

APP_PID=""
if [ "$USE_ADB" -eq 1 ] && [ -n "$APP_PACKAGE" ]; then
  APP_PID="$("$ADB" shell pidof "$APP_PACKAGE" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
elif [ "$USE_ADB" -eq 0 ] && [ -n "$APP_PACKAGE" ]; then
  APP_PID="$(pidof "$APP_PACKAGE" 2>/dev/null | awk '{print $1}')"
fi

LOGCAT_PID=""
DURATION_PID=""
ENDED=0

cleanup() {
  [ "$ENDED" -eq 1 ] && return
  ENDED=1
  echo ""
  echo "----------------------------------------"
  echo "正在结束记录 ($(date '+%H:%M:%S'))..."

  [ -n "${DURATION_PID:-}" ] && kill "$DURATION_PID" 2>/dev/null
  [ -n "${LOGCAT_PID:-}" ] && kill "$LOGCAT_PID" 2>/dev/null

  wait "$LOGCAT_PID" 2>/dev/null
  sleep 0.3
  kill -9 "$LOGCAT_PID" 2>/dev/null

  {
    echo ""
    echo "========== capture end $(date '+%Y-%m-%d %H:%M:%S') =========="
    if [ -f "$LOG_FILE" ]; then
      echo "lines: $(wc -l < "$LOG_FILE" 2>/dev/null | tr -d ' ')"
      echo "size: $(wc -c < "$LOG_FILE" 2>/dev/null | tr -d ' ') bytes"
    fi
  } >> "$LOG_FILE" 2>/dev/null

  echo "日志已保存: $LOG_FILE"
  if [ -f "$LOG_FILE" ]; then
    du -h "$LOG_FILE" 2>/dev/null | awk '{print "大小: "$1}'
    wc -l < "$LOG_FILE" 2>/dev/null | awk '{print "行数: "$1}'
  fi
  exit 0
}

trap cleanup INT TERM

{
  echo "========== MediaVault log capture =========="
  echo "start: $(date '+%Y-%m-%d %H:%M:%S')"
  echo "mode: $([ "$USE_ADB" -eq 1 ] && echo adb || echo local-logcat)"
  echo "package: ${APP_PACKAGE:-<all>}"
  echo "pid: ${APP_PID:-<not running>}"
  echo "duration: ${DURATION:-until Ctrl+C}"
  echo "file: $LOG_FILE"
  echo "tags: ExoPlayer, MediaVault, RemoteDataSource, WebDav, okhttp, scrape, PlayerActivity"
  echo "----------------------------------------"
} > "$LOG_FILE"

echo "开始记录 → $LOG_FILE"
echo "包名: ${APP_PACKAGE:-全部}  PID: ${APP_PID:-未运行}"
if [ -n "$DURATION" ] && [ "$DURATION" -gt 0 ] 2>/dev/null; then
  echo "时长: ${DURATION}s（可随时 Ctrl+C 提前结束）"
else
  echo "时长: 直到 Ctrl+C"
fi
echo "----------------------------------------"

GREP_RE="${APP_PACKAGE}|mediavault|ExoPlayer|RemoteDataSource|WebDav|okhttp|PlayerActivity|RemoteLibrary|scrape|PROPFIND|IOException"

if [ "$USE_ADB" -eq 1 ]; then
  if [ -n "$APP_PID" ]; then
    "$ADB" logcat --pid="$APP_PID" -v threadtime >> "$LOG_FILE" 2>&1 &
    LOGCAT_PID=$!
  elif [ -n "$APP_PACKAGE" ]; then
    "$ADB" logcat -v threadtime 2>&1 | grep -iE "$GREP_RE" >> "$LOG_FILE" &
    LOGCAT_PID=$!
  else
    "$ADB" logcat -v threadtime >> "$LOG_FILE" 2>&1 &
    LOGCAT_PID=$!
  fi
else
  if [ "$(id -u 2>/dev/null)" != "0" ]; then
    echo "提示: 未 root 时部分机型 logcat --pid 可能失败，将退化为 grep"
  fi
  if [ -n "$APP_PID" ]; then
    logcat --pid="$APP_PID" -v threadtime >> "$LOG_FILE" 2>&1 &
    LOGCAT_PID=$!
  elif [ -n "$APP_PACKAGE" ]; then
    logcat -v threadtime 2>&1 | grep -iE "$GREP_RE" >> "$LOG_FILE" &
    LOGCAT_PID=$!
  else
    logcat -v threadtime >> "$LOG_FILE" 2>&1 &
    LOGCAT_PID=$!
  fi
fi

if [ -n "$DURATION" ] && [ "$DURATION" -gt 0 ] 2>/dev/null; then
  (
    sleep "$DURATION"
    kill -TERM $$ 2>/dev/null
  ) &
  DURATION_PID=$!
fi

wait "$LOGCAT_PID" 2>/dev/null
cleanup