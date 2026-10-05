@echo off
chcp 65001 >nul
rem KnowNote 同步中心 —— Windows 本地跑（调试/当家庭服务器都行，Termux 请用 start-hub.sh）
cd /d "%~dp0"
if not exist hub.conf.json (
  python knownote_hub.py --init-config hub.conf.json
  echo.
  echo [i] 改完 hub.conf.json 里 devices 的手机地址再运行本脚本
  pause
  exit /b 0
)
python knownote_hub.py --config hub.conf.json %*
pause
