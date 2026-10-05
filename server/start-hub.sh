#!/data/data/com.termux/files/usr/bin/bash
# KnowNote 局域网同步中心 —— Termux 启动脚本
#
#   bash start-hub.sh            前台跑（Ctrl+C 停，日志直接看屏幕）
#   bash start-hub.sh bg         后台跑（日志写 hub.log，pid 写 hub.pid）
#   bash start-hub.sh bg 120     后台跑，每 120 秒轮询一次
#   bash start-hub.sh check      只看每台设备通不通，不同步
#   bash start-hub.sh status     看本地库有多少条、最近的同步记录
#
# 第一次跑会先生成 hub.conf.json，改完里面 devices 的手机地址再跑一次。
set -u

cd "$(dirname "$0")" || exit 1
CONF="${CONF:-hub.conf.json}"
MODE="${1:-run}"
INTERVAL="${2:-}"

if ! command -v python >/dev/null 2>&1; then
  echo "[x] 没找到 python。Termux 里先装一个：pkg install python"
  exit 1
fi

# 息屏后别让系统把网络与 CPU 掐掉（Termux 官方给的 wake-lock 开关）
if command -v termux-wake-lock >/dev/null 2>&1; then
  termux-wake-lock >/dev/null 2>&1 && echo "[ok] 已获取 wake-lock（息屏也能继续同步）"
fi

if [ ! -f "$CONF" ]; then
  echo "[i] 还没有配置，先生成一份：$CONF"
  python knownote_hub.py --init-config "$CONF" || exit 1
  echo
  echo "[i] 把 $CONF 里 devices 那台手机的地址改成真实地址，然后重新跑："
  echo "      bash start-hub.sh"
  exit 0
fi

ARGS="--config $CONF"
if [ -n "$INTERVAL" ]; then
  ARGS="$ARGS --interval $INTERVAL"
fi

case "$MODE" in
  run|start)
    exec python knownote_hub.py $ARGS
    ;;
  bg)
    if [ -f hub.pid ] && kill -0 "$(cat hub.pid)" 2>/dev/null; then
      echo "[i] 已经在跑了（pid $(cat hub.pid)）。要重启先跑：bash stop-hub.sh"
      exit 0
    fi
    nohup python knownote_hub.py $ARGS >> hub.log 2>&1 &
    echo $! > hub.pid
    echo "[ok] 已后台启动，pid $(cat hub.pid)，日志：$(pwd)/hub.log"
    echo "[i] 看日志：bash logs.sh     停止：bash stop-hub.sh"
    ;;
  check)
    exec python knownote_hub.py --config "$CONF" --check
    ;;
  status)
    exec python knownote_hub.py --config "$CONF" --status
    ;;
  *)
    echo "用法: bash start-hub.sh [run|bg|check|status] [轮询间隔秒]"
    exit 2
    ;;
esac
