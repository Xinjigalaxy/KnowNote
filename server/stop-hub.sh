#!/data/data/com.termux/files/usr/bin/bash
# 停掉后台跑的同步中心（start-hub.sh bg 起的那个）。
set -u
cd "$(dirname "$0")" || exit 1

PIDFILE="hub.pid"
if [ ! -f "$PIDFILE" ]; then
  echo "[i] 没有 $PIDFILE —— 中心不是用 start-hub.sh bg 起的？"
  exit 0
fi

PID="$(cat "$PIDFILE")"
if kill -0 "$PID" 2>/dev/null; then
  kill "$PID" && echo "[ok] 已停止 pid $PID"
else
  echo "[i] pid $PID 已经不在了"
fi
rm -f "$PIDFILE"
