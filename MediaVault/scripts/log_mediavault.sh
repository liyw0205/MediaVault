#!/system/bin/sh
# MediaVault 播放器 / 远程 WebDAV·FTP·SMB / 刮削 logcat
# 用法:
#   ./log_mediavault.sh              # 录到按 Ctrl+C 结束
#   ./log_mediavault.sh 120          # 录 120 秒后自动结束
#   ./log_mediavault.sh 0 com.mediavault
#   LOG_DIR=/sdcard/Download ./log_mediavault.sh
#
# 数据目录（调试时可 adb pull）:
#   /data/user/0/com.mediavault/files/library.json
#   remotes.json, scrape-record.tsv, playback progress 等

set -u

APP_PACKAGE="${2:-${APP_PACKAGE:-com.mediavault}}"
DURATION="${1:-}"
SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)" || SCRIPT_DIR="/data/data/com.termux/files/home/devwork"
LOG_DIR="${LOG_DIR:-$SCRIPT_DIR}"

mkdir -p "$LOG_DIR" 2>/dev/null || {
  echo "无法创建日志目录: $LOG_DIR"
  exit 1
}

if [ "$(id -u)" != "0" ]; then
  echo "提示: 未 root，部分机型 logcat --pid 可能失败，将退化为包名 grep"
fi

stamp="$(date +%Y%m%d_%H%M%S)"
if [ -n "$APP_PACKAGE" ]; then
  LOG_FILE="$LOG_DIR/logcat_mediavault_${APP_PACKAGE}_${stamp}.log"
else
  LOG_FILE="$LOG_DIR/logcat_mediavault_all_${stamp}.log"
fi

APP_PID=""
if [ -n "$APP_PACKAGE" ]; then
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
  echo "package: ${APP_PACKAGE:-<all>}"
  echo "pid: ${APP_PID:-<not running>}"
  echo "duration: ${DURATION:-until Ctrl+C}"
  echo "file: $LOG_FILE"
  echo "tags of interest: ExoPlayer, MediaVault, mediavault, RemoteDataSource, WebDav, okhttp, scrape, PlayerActivity"
  echo "remote browse: Settings -> WebDAV root /dav -> folder icon; video tap play"
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

if [ -n "$APP_PID" ]; then
  logcat --pid="$APP_PID" -v threadtime >> "$LOG_FILE" 2>&1 &
  LOGCAT_PID=$!
elif [ -n "$APP_PACKAGE" ]; then
  logcat -v threadtime 2>&1 | grep -iE "$APP_PACKAGE|mediavault|ExoPlayer|RemoteDataSource|WebDav|okhttp|PlayerActivity|RemoteLibrary|scrape|PROPFIND|IOException" >> "$LOG_FILE" &
  LOGCAT_PID=$!
else
  logcat -v threadtime >> "$LOG_FILE" 2>&1 &
  LOGCAT_PID=$!
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