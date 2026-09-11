#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把图标候选渲染成一张 PNG（发给 Telegram 用；桌面端有内嵌预览组件，Telegram 没有）。

形状直接复用 tools/icon-candidates.html 里的同一批 SVG path —— 不重画，
免得"发出去的图和最终落成 vector 的东西不是一回事"。

用法：python tools/render-icons.py [输出路径]
"""
from __future__ import annotations

import pathlib
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFont
from reportlab.graphics import renderPM
from svglib.svglib import svg2rlg

CANDS = {
    "A · 列表 + 碎片点": (
        '三条递减横线（"条目"感）+ 右上一点表示新记一条。小尺寸最不糊。',
        '<g fill="{c}"><rect x="24" y="26" width="52" height="9.5" rx="4.75"/>'
        '<rect x="24" y="46" width="38" height="9.5" rx="4.75"/>'
        '<rect x="24" y="66" width="26" height="9.5" rx="4.75"/>'
        '<circle cx="77" cy="30.75" r="7.5"/></g>'),
    "B · 碎片拼合": (
        '大碎片 + 两块小碎片正在拼合，呼应"碎片笔记"的名字，抽象但有辨识度。',
        '<g fill="{c}"><rect x="24" y="24" width="34" height="34" rx="10"/>'
        '<rect x="64" y="24" width="20" height="20" rx="7"/>'
        '<rect x="64" y="50" width="20" height="20" rx="7"/>'
        '<rect x="24" y="64" width="16" height="16" rx="6" opacity="0.55"/></g>'),
    "C · 折角便签": (
        '最经典的"笔记"意象：折角 + 便签，剪影里折角依然读得出。',
        '<g fill="{c}"><path d="M40 24h28a12 12 0 0 1 12 12v26L62 86H40a12 12 0 0 1-12-12V36a12 12 0 0 1 12-12z"/>'
        '<path d="M66 82l16-16v16z"/></g>'),
    "D · 书签": (
        '书签形（下方 V 口），贴合"归档"语义，形状好认。',
        '<g fill="{c}"><path d="M36 24h36a10 10 0 0 1 10 10v52L54 66 26 86V34a10 10 0 0 1 10-10z"/></g>'),
    "E · 井号标签": (
        '四条圆角短棒组成 #，对应应用里的标签能力，几何感最强。',
        '<g fill="{c}"><rect x="34" y="24" width="9.5" height="60" rx="4.75"/>'
        '<rect x="64.5" y="24" width="9.5" height="60" rx="4.75"/>'
        '<rect x="24" y="38" width="60" height="9.5" rx="4.75"/>'
        '<rect x="24" y="60.5" width="60" height="9.5" rx="4.75"/></g>'),
    "F · 字母 K": (
        'KnowNote 的几何字母标，品牌识别最直接，但和"笔记"语义关联最弱。',
        '<g fill="{c}"><rect x="28" y="24" width="11" height="60" rx="5.5"/>'
        '<rect x="28.5" y="54" width="52" height="11" rx="5.5" transform="rotate(-38 28.5 59.5)"/>'
        '<rect x="28.5" y="54" width="52" height="11" rx="5.5" transform="rotate(38 28.5 59.5)"/></g>'),
    "G · 圆 + 缺角碎片": (
        '圆形被切掉一角、旁边补上一小块。最抽象，也最"轻"。',
        '<g fill="{c}"><path d="M54 24a30 30 0 1 1-21.2 8.8A30 30 0 0 1 54 24zm0 11a19 19 0 1 0 13.4 5.6A19 19 0 0 0 54 35z" fill-rule="evenodd"/>'
        '<rect x="64" y="60" width="20" height="20" rx="7"/></g>'),
}

TILE = 152          # 每个图标显示尺寸
SCALE = 3           # SVG 栅格化倍率（再降采样，边缘才干净）
GAP = 14
PAD = 26
LEFT = 400          # 左侧文字区宽度
BG_PAGE = (250, 250, 250)
VARIANTS = [
    ("自适应 · 浅色", (0xDF, 0xF3, 0xF1), (0x00, 0x69, 0x6B), "squircle"),
    ("自适应 · 深色", (0x12, 0x34, 0x3A), (0x7F, 0xE7, 0xE4), "squircle"),
    ("主题图标（取色）", (0x1F, 0x1F, 0x22), (0x8A, 0xB4, 0xF8), "round"),
    ("单色剪影", None, (0x8E, 0x8E, 0x93), None),
]

FONT = "C:/Windows/Fonts/msyh.ttc"
FONT_B = "C:/Windows/Fonts/msyhbd.ttc"


def render_shape(inner: str, color: str) -> Image.Image:
    """把一段 SVG 片段栅格化成带 alpha 的图。"""
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" '
           f'width="{TILE * SCALE}" height="{TILE * SCALE}">{inner.format(c=color)}</svg>')
    tmp = pathlib.Path(tempfile.mkdtemp()) / "s.svg"
    tmp.write_text(svg, encoding="utf-8")
    d = svg2rlg(str(tmp))
    png = tmp.with_suffix(".png")
    renderPM.drawToFile(d, str(png), fmt="PNG", bg=0xFFFFFF)
    img = Image.open(png).convert("RGBA")
    # 白底转透明（形状都是单色实心，白色必属背景）
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if r > 246 and g > 246 and b > 246:
                px[x, y] = (r, g, b, 0)
    return img.resize((TILE, TILE), Image.LANCZOS)


def tile_image(shape: Image.Image, bg, mask):
    canvas = Image.new("RGBA", (TILE, TILE), (255, 255, 255, 0))
    if bg is None:                       # 单色剪影：透明底 + 虚线框
        d = ImageDraw.Draw(canvas)
        for i in range(0, TILE, 9):
            d.line([(i, 0), (min(i + 5, TILE), 0)], fill=(190, 190, 190, 255))
            d.line([(i, TILE - 1), (min(i + 5, TILE), TILE - 1)], fill=(190, 190, 190, 255))
            d.line([(0, i), (0, min(i + 5, TILE))], fill=(190, 190, 190, 255))
            d.line([(TILE - 1, i), (TILE - 1, min(i + 5, TILE))], fill=(190, 190, 190, 255))
    else:
        plate = Image.new("RGBA", (TILE, TILE), bg + (255,))
        m = Image.new("L", (TILE, TILE), 0)
        dm = ImageDraw.Draw(m)
        if mask == "round":
            dm.ellipse([0, 0, TILE - 1, TILE - 1], fill=255)
        else:
            dm.rounded_rectangle([0, 0, TILE - 1, TILE - 1], radius=TILE * 0.24, fill=255)
        canvas.paste(plate, (0, 0), m)
    canvas.alpha_composite(shape)
    return canvas


def main():
    out = pathlib.Path(sys.argv[1] if len(sys.argv) > 1
                       else "C:/Users/zsnc5/Desktop/harness/KnowNote/tools/icon-candidates.png")
    f_name = ImageFont.truetype(FONT_B, 26)
    f_meta = ImageFont.truetype(FONT, 17)
    f_lbl = ImageFont.truetype(FONT, 15)
    f_hdr = ImageFont.truetype(FONT_B, 30)
    f_sub = ImageFont.truetype(FONT, 18)

    row_h = TILE + 60
    width = PAD * 2 + LEFT + 4 * (TILE + GAP)
    height = PAD * 2 + 96 + len(CANDS) * row_h
    page = Image.new("RGB", (width, height), BG_PAGE)
    d = ImageDraw.Draw(page)

    d.text((PAD, PAD), "KnowNote · 碎片笔记 — 应用图标候选", font=f_hdr, fill=(20, 20, 20))
    for i, head in enumerate([
        "每行 4 种呈现：自适应图标（圆角遮罩，浅色 / 深色壁纸下）、主题图标（Android 13+ 跟随系统取色）、单色剪影（单色层的样子）。",
        "挑一个告诉我（也可以组合，例如「C 的形状 + A 的圆角」），我落成 Android vector drawable 并接上动态取色。",
    ]):
        d.text((PAD, PAD + 42 + i * 26), head, font=f_sub, fill=(110, 110, 110))

    y = PAD + 96
    for name, (meta, inner) in CANDS.items():
        d.text((PAD, y + 6), name, font=f_name, fill=(15, 15, 15))
        # meta 手动折行（按字符宽度）
        line, lines = "", []
        for ch in meta:
            if d.textlength(line + ch, font=f_meta) > LEFT - 40:
                lines.append(line); line = ch
            else:
                line += ch
        lines.append(line)
        for i, ln in enumerate(lines[:4]):
            d.text((PAD, y + 44 + i * 25), ln, font=f_meta, fill=(95, 95, 95))

        x = PAD + LEFT
        for label, bg, fg, mask in VARIANTS:
            shape = render_shape(inner, "#%02X%02X%02X" % fg)
            page.paste(tile_image(shape, bg, mask), (x, y), tile_image(shape, bg, mask))
            tw = d.textlength(label, font=f_lbl)
            d.text((x + (TILE - tw) / 2, y + TILE + 8), label, font=f_lbl, fill=(105, 105, 105))
            x += TILE + GAP
        y += row_h

    page.save(out)
    print("已写出:", out, page.size)


if __name__ == "__main__":
    main()
