#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""图标修正稿：统一留白 + 按真实尺寸做大小阶梯。

上一版的问题（用户一眼看出）：
  1. 图形在 108 视口里占了 x24..84.5 —— 圆形遮罩可见区只有中间 72（18..90），
     所以只剩 6 单位边距，看着"顶着边"。Google 的示例图标内容通常只在中央 ~52-56 单位内。
  2. 三条杠在大图上成立，落到圆形遮罩 + 真机尺寸就糊、不好看。

这一版：所有候选统一「内容包围盒 ≤52×52 且中心在 (54,54)」，
并输出 152 / 96 / 64 / 48px 四档（大致对应图标在桌面/设置/抽屉/通知栏的观感）。

用法：python tools/icon-refine.py
"""
from __future__ import annotations

import pathlib
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFont
from reportlab.graphics import renderPM
from svglib.svglib import svg2rlg

R = 3.75          # 圆角半径基准
BG = (0x39, 0xC5, 0xBB)      # 初音绿（沿用现底色）
INK = (0x0A, 0x34, 0x36)     # 墨绿前景

# 每个候选：(说明, SVG 片段)。坐标都已按「内容 ≤52、中心 (54,54)」重排。
CANDS = [
    ("现在这样（旧 A）", "三条杠顶到边，圆里显挤", (
        '<g fill="{c}"><rect x="24" y="30" width="37" height="9.5" rx="4.75"/>'
        '<rect x="24" y="50" width="27" height="9.5" rx="4.75"/>'
        '<rect x="24" y="70" width="17" height="9.5" rx="4.75"/>'
        '<circle cx="77" cy="34.75" r="7.5"/></g>')),

    ("A1 · 两条 + 点", "元素少一半，线条变粗、更松弛", (
        '<g fill="{c}"><rect x="32" y="42.75" width="32" height="7.5" rx="3.75"/>'
        '<rect x="32" y="57.75" width="22" height="7.5" rx="3.75"/>'
        '<circle cx="70.5" cy="46.5" r="5.5"/></g>')),

    ("A2 · 一条 + 点", "最简：一条内容 + 一个点", (
        '<g fill="{c}"><rect x="32.5" y="50" width="28" height="8" rx="4"/>'
        '<circle cx="66" cy="54" r="6"/></g>')),

    ("A3 · 三条（放大留白）", "保留原概念，只缩到 52 并加粗", (
        '<g fill="{c}"><rect x="38" y="40" width="32" height="8" rx="4"/>'
        '<rect x="38" y="51.5" width="24" height="8" rx="4"/>'
        '<rect x="38" y="63" width="16" height="8" rx="4"/></g>')),

    ("C′ · 折角便签", "「笔记」最直白的意象，剪影也读得出", (
        '<g fill="{c}"><path d="M42 31h24a8 8 0 0 1 8 8v21L57 77H42a8 8 0 0 1-8-8V39a8 8 0 0 1 8-8z"/>'
        '<path d="M59.5 74.5l13-13v13z"/></g>')),

    ("D′ · 书签", "下方 V 口，形状最好认、最不容易糊", (
        '<g fill="{c}"><path d="M42 31h24a4 4 0 0 1 4 4v42L54 61 38 77V35a4 4 0 0 1 4-4z"/></g>')),

    # 卡片 + 三条等长线：单色图标里同色线会隐形，只有两种画法成立
    ("E1 · 卡片（实心）+ 三条镂空线", "M3 官方 note 图标就是这么画的（整块卡片、线是挖空的）", (
        '<g fill="{c}" fill-rule="evenodd"><path d="M43 31h22a8 8 0 0 1 8 8v30a8 8 0 0 1-8 8H43a8 8 0 0 1-8-8V39a8 8 0 0 1 8-8z'
        'M46.25 39h15.5a2.25 2.25 0 0 1 0 4.5h-15.5a2.25 2.25 0 0 1 0-4.5z'
        'M46.25 51.75h15.5a2.25 2.25 0 0 1 0 4.5h-15.5a2.25 2.25 0 0 1 0-4.5z'
        'M46.25 64.5h15.5a2.25 2.25 0 0 1 0 4.5h-15.5a2.25 2.25 0 0 1 0-4.5z"/></g>')),

    ("E2 · 卡片（描边）+ 三条实线", "卡片只画外框，线是实心的、对比更硬", (
        '<g fill="{c}" fill-rule="evenodd">'
        '<path d="M43 31h22a8 8 0 0 1 8 8v30a8 8 0 0 1-8 8H43a8 8 0 0 1-8-8V39a8 8 0 0 1 8-8z'
        'M43 36.5h22a3.5 3.5 0 0 1 3.5 3.5v30a3.5 3.5 0 0 1-3.5 3.5H43a3.5 3.5 0 0 1-3.5-3.5V40a3.5 3.5 0 0 1 3.5-3.5z"/>'
        '<rect x="46.25" y="42.5" width="15.5" height="4.5" rx="2.25"/>'
        '<rect x="46.25" y="52.75" width="15.5" height="4.5" rx="2.25"/>'
        '<rect x="46.25" y="63" width="15.5" height="4.5" rx="2.25"/></g>')),
]

SIZES = [152, 96, 64, 48]
FONT = "C:/Windows/Fonts/msyh.ttc"
FONT_B = "C:/Windows/Fonts/msyhbd.ttc"


def render_shape(inner: str, color: str, px: int) -> Image.Image:
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" '
           f'width="{px * 3}" height="{px * 3}">{inner.format(c=color)}</svg>')
    tmp = pathlib.Path(tempfile.mkdtemp()) / "s.svg"
    tmp.write_text(svg, encoding="utf-8")
    png = tmp.with_suffix(".png")
    renderPM.drawToFile(svg2rlg(str(tmp)), str(png), fmt="PNG", bg=0xFFFFFF)
    img = Image.open(png).convert("RGBA")
    px_map = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px_map[x, y]
            if r > 246 and g > 246 and b > 246:
                px_map[x, y] = (r, g, b, 0)
    return img.resize((px, px), Image.LANCZOS)


def circle_icon(inner: str, px: int) -> Image.Image:
    """把形状放进圆形遮罩（模拟桌面图标）。"""
    shape = render_shape(inner, "#%02X%02X%02X" % INK, px)
    plate = Image.new("RGBA", (px, px), BG + (255,))
    mask = Image.new("L", (px, px), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, px - 1, px - 1], fill=255)
    out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    out.paste(plate, (0, 0), mask)
    out.alpha_composite(shape)
    return out


def main():
    out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1
                       else "C:/Users/zsnc5/Desktop/harness/KnowNote/tools/icon-refined.png")
    f_name = ImageFont.truetype(FONT_B, 26)
    f_desc = ImageFont.truetype(FONT, 17)
    f_size = ImageFont.truetype(FONT, 13)
    f_hdr = ImageFont.truetype(FONT_B, 30)
    f_sub = ImageFont.truetype(FONT, 18)

    PAD, LEFT = 26, 300
    row_h = 200
    width = PAD * 2 + LEFT + sum(SIZES) + 40 * (len(SIZES) - 1) + 40
    height = PAD * 2 + 110 + len(CANDS) * row_h
    page = Image.new("RGB", (width, height), (250, 250, 250))
    d = ImageDraw.Draw(page)

    d.text((PAD, PAD), "图标修正稿 —— 统一留白 + 真实尺寸阶梯", font=f_hdr, fill=(20, 20, 20))
    for i, head in enumerate([
        "规则：内容包围盒 ≤52×52、中心在 (54,54)（圆形遮罩的可见区是中间 72）。",
        "右边四档大致对应 桌面 / 设置 / 抽屉 / 通知栏 的观感。回我一个编号即可。",
    ]):
        d.text((PAD, PAD + 42 + i * 26), head, font=f_sub, fill=(110, 110, 110))

    y = PAD + 110
    for name, desc, inner in CANDS:
        d.text((PAD, y + 40), name, font=f_name, fill=(15, 15, 15))
        d.text((PAD, y + 76), desc, font=f_desc, fill=(110, 110, 110))
        x = PAD + LEFT
        for px in SIZES:
            icon = circle_icon(inner, px)
            page.paste(icon, (x, y + (152 - px) // 2 + 10), icon)
            lbl = f"{px}px"
            d.text((x + px // 2 - d.textlength(lbl, font=f_size) / 2, y + 176), lbl, font=f_size, fill=(140, 140, 140))
            x += px + 40
        y += row_h

    page.save(out)
    print("已写出:", out, page.size)


if __name__ == "__main__":
    main()
