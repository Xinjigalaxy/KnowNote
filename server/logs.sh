#!/data/data/com.termux/files/usr/bin/bash
# 看同步中心的日志（后台模式写的是 hub.log）。Ctrl+C 退出。
set -u
cd "$(dirname "$0")" || exit 1
LOG="${1:-hub.log}"
if [ ! -f "$LOG" ]; then
  echo "[i] 还没有 $LOG（前台跑的时候日志只打到屏幕上）"
  exit 0
fi
echo "[i] 正在跟踪 $LOG（Ctrl+C 退出）"
tail -n 60 -f "$LOG"
