#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""给 README 拍一套脱敏截图（docs/screenshots/）。

为什么脚本化而不是手点：
  每个动作前先 `uiautomator dump` 读系统的无障碍树，用**系统报的坐标**点，
  不靠写死的像素 —— 写死坐标在上一轮已经点偏过一次（1200 vs 实际 1352）。
  含共享密钥 / 设备标识 / 局域网地址的页面，截图后立刻用 tools/redact_screenshots.py
  按同一份 dump 打码。

用法：python tools/make-screenshots.py [serial]
"""
from __future__ import annotations

import pathlib
import re
import subprocess
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from redact_screenshots import redact  # noqa: E402

ADB = "C:/Users/zsnc5/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL = sys.argv[1] if len(sys.argv) > 1 else "<device-serial>"
PKG = "com.xinjigalaxy.knownotes.debug"
OUT = pathlib.Path("docs/screenshots")
TMP = pathlib.Path(__import__("os").environ.get("LOCALAPPDATA", "/tmp")) / "Temp"


def sh(*args: str) -> str:
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True).stdout.decode("utf-8", "replace")


def dump() -> str:
    sh("shell", "uiautomator", "dump", "/sdcard/shot.xml")
    xml = sh("exec-out", "cat", "/sdcard/shot.xml")
    TMP.mkdir(parents=True, exist_ok=True)
    (TMP / "shot.xml").write_text(xml, encoding="utf-8")
    return xml


def find(xml: str, text: str, nth: int = 0):
    hits = []
    for m in re.finditer(r"<node[^>]*>", xml):
        tag = m.group(0)
        t = re.search(r'text="([^"]*)"', tag)
        if t and t.group(1).strip() == text:
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
            if b:
                x1, y1, x2, y2 = map(int, b.groups())
                hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
    return hits[nth] if len(hits) > nth else None


def tap(x, y, wait=1.5):
    sh("shell", "input", "tap", str(x), str(y))
    time.sleep(wait)


def tap_text(text, nth=0, wait=1.5):
    pos = find(dump(), text, nth)
    if not pos:
        raise SystemExit(f"找不到可点元素：{text}")
    tap(pos[0], pos[1], wait)
    return pos


def shot(name: str, do_redact=False):
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / name
    with path.open("wb") as fh:
        fh.write(subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"],
                                capture_output=True).stdout)
    if do_redact:
        # 统计行是异步加载的：早于它出现就去打码，设备标识会被漏掉
        dump()
        redact(str(path), str(TMP / "shot.xml"))
    print(f"  {name}")


def main():
    sh("shell", "am", "force-stop", PKG)
    time.sleep(1)
    sh("shell", "am", "start", "-n", f"{PKG}/com.xinjigalaxy.knownotes.MainActivity")
    time.sleep(6)

    print("拍图：")
    xml = dump()
    shot("01-notes-list.png")

    # 列表页第一张卡片 → 预览（点击=查看）
    card = None
    for m in re.finditer(r"<node[^>]*>", xml):
        tag = m.group(0)
        if 'clickable="true"' not in tag:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if b and int(b.group(4)) - int(b.group(2)) > 150:
            card = tuple(map(int, b.groups()))
            break
    if card:
        x1, y1, x2, y2 = card
        tap((x1 + x2) // 2, (y1 + y2) // 2, 3)
        shot("02-note-preview.png")
        sh("shell", "input", "keyevent", "4")          # 返回
        time.sleep(2)

    # 搜索
    pos = None
    for m in re.finditer(r"<node[^>]*>", dump()):
        tag = m.group(0)
        if 'class="android.widget.EditText"' in tag:
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
            if b:
                pos = ((int(b.group(1)) + int(b.group(3))) // 2, (int(b.group(2)) + int(b.group(4))) // 2)
                break
    if pos:
        tap(pos[0], pos[1], 1.5)
        sh("shell", "input", "text", "记")
        time.sleep(2)
        shot("03-search.png")
        sh("shell", "input", "keyevent", "4")
        time.sleep(2)

    # 分组 / 标签二级页
    tap_text("分组", wait=2.5)
    shot("04-groups.png")
    xml = dump()
    first = [m.group(0) for m in re.finditer(r"<node[^>]*>", xml) if 'clickable="true"' in m.group(0)]
    if first:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', first[0])
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            tap((x1 + x2) // 2, (y1 + y2) // 2, 2.5)
            shot("05-group-detail.png")
            sh("shell", "input", "keyevent", "4")
            time.sleep(2)

    # 更多（含统计，可能显示设备标识 → 打码）
    tap_text("更多", wait=3)
    shot("06-more-stats.png", do_redact=True)

    # 设置（含语言区）
    tap_text("设置", wait=3)
    shot("07-settings.png")
    # 深色
    tap_text("深色", wait=3)
    shot("08-settings-dark.png")
    tap_text("跟随系统", wait=3)
    sh("shell", "input", "keyevent", "4")
    time.sleep(2)

    # 同步页（含共享密钥/主机地址 → 打码）
    tap_text("局域网同步", wait=3) if find(dump(), "局域网同步") else None
    if find(dump(), "局域网同步"):
        tap_text("局域网同步", wait=3)
        shot("09-sync.png", do_redact=True)
        sh("shell", "input", "keyevent", "4")
        time.sleep(2)

    # 多语言展示：设置里切英文 → 笔记页，再切回跟随系统
    if find(dump(), "更多"):
        tap_text("更多", wait=2.5)
        tap_text("设置", wait=3)
        if find(dump(), "English"):
            tap_text("English", wait=5)
        sh("shell", "input", "keyevent", "4")
        time.sleep(2)
        shot("10-english-notes.png")
        # 切回中文
        tap_text("More", wait=2.5)
        tap_text("Settings", wait=3)
        if find(dump(), "跟随系统"):
            tap_text("跟随系统", wait=5)

    print(f"完成，输出目录：{OUT}")


if __name__ == "__main__":
    main()
