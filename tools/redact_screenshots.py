#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""按无障碍树里节点的真实坐标给截图打码。

为什么这么做：手写像素坐标迟早会错（之前点按就吃过亏），
而 uiautomator dump 给出的是系统报的 bounds —— 用同一份 dump 去定位敏感文案，
再填成不透明方块，比"大概在这个位置"可靠得多。

用法：
    python tools/redact-screenshots.py <png> <dump.xml> [<png> <dump.xml> ...]

命中规则（正则）：
  * IPv4（局域网地址）
  * android-[0-9a-f]{8,}（本机设备标识）
  * 同步密钥：一串 8 位小写字母数字（同步页那行）
  * 主机地址输入框里的 192.168.x.x:端口
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw

PATTERNS = [
    re.compile(r"\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b"),   # IPv4[:port]
    re.compile(r"android-[0-9a-fA-F]{8,}"),                # 设备标识
    re.compile(r"^[a-z2-9]{8}$"),                          # 同步密钥（8 位可读随机串）
]

FILL = (32, 32, 32)      # 打码色：深灰，和深浅主题都不冲突
PAD = 6


def bounds_of(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not m:
        return None
    return tuple(int(x) for x in m.groups())


def collect_targets(xml_path: str):
    """返回 [(x1,y1,x2,y2, 命中的文字)]"""
    root = ET.parse(xml_path).getroot()
    hits = []
    for node in root.iter("node"):
        text = (node.get("text") or "").strip() or (node.get("content-desc") or "").strip()
        if not text:
            continue
        if any(p.search(text) for p in PATTERNS):
            b = bounds_of(node)
            if b:
                hits.append((*b, text))
    return hits


def redact(png_path: str, xml_path: str) -> int:
    targets = collect_targets(xml_path)
    if not targets:
        print(f"  {png_path}: 未发现需要打码的文本")
        return 0
    img = Image.open(png_path).convert("RGB")
    draw = ImageDraw.Draw(img)
    for x1, y1, x2, y2, text in targets:
        draw.rectangle(
            [max(0, x1 - PAD), max(0, y1 - PAD), min(img.width, x2 + PAD), min(img.height, y2 + PAD)],
            fill=FILL,
        )
        print(f"  {png_path}: 打码 '{text[:40]}' → ({x1},{y1})-({x2},{y2})")
    img.save(png_path)
    return len(targets)


def main(argv):
    if len(argv) < 3 or len(argv) % 2 == 0:
        print(__doc__)
        return 2
    total = 0
    for i in range(1, len(argv), 2):
        total += redact(argv[i], argv[i + 1])
    print(f"共打码 {total} 处")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
