#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""修资源文件里的插值：把译文里的 ${expr} / $name 换成 %N$s 位置参数。

背景：codemod 只把**中文原文**的插值转成了 %1$s，译文里的 ${...} 被原样写进资源，
而 Android 资源里 Kotlin 模板不会展开 —— 日语界面上就显示成 "$days 日" 这种字面量。

规则：按**表达式文本**对齐编号（而不是按出现顺序），这样日语里把参数提前也仍然正确。
    zh 原文： "保留天数 $days 天"        → 资源 "保留天数 %1$s 天"，调用点传 days
    日语译文："${days} 日"                → 资源 "%1$s 日"            ✔
如果译文漏了某个占位符，脚本会列出来（信息会丢，但不会崩）。

用法：python tools/i18n-fix-args.py [--dry-run]
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(".")
RES = ROOT / "app/src/main/res"
COLUMNS = {"values": "en", "values-zh": "zh", "values-zh-rTW": "zh-TW", "values-ja": "ja"}
MARK = "\x00"


def slug(text: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")
    if not s:
        s = "s"
    if s[0].isdigit():
        s = "s_" + s
    return s[:48]


def scan_interpolations(text: str):
    """按出现顺序返回 [表达式]，认 ${...}（含嵌套括号/字符串）与 $name。"""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == "\\":
            i += 2; continue
        if c == "$":
            if i + 1 < n and text[i + 1] == "{":
                depth, j = 1, i + 2
                while j < n and depth > 0:
                    if text[j] == "\\":
                        j += 2; continue
                    if text[j] == '"':
                        j += 1
                        while j < n and text[j] != '"':
                            j += 2 if text[j] == "\\" else 1
                        j += 1; continue
                    if text[j] == "{":
                        depth += 1
                    elif text[j] == "}":
                        depth -= 1
                    j += 1
                out.append(text[i + 2:j - 1])
                i = j; continue
            m = re.match(r"[A-Za-z_][A-Za-z0-9_.]*", text[i + 1:])
            if m:
                out.append(m.group(0))
                i += 1 + len(m.group(0)); continue
        i += 1
    return out


def convert(text: str, index_of: dict):
    """把 text 里的插值替换成 %N$s（按表达式查表）。返回 (结果, 缺失的表达式)。"""
    missing, out, i, n = [], [], 0, len(text)
    while i < n:
        c = text[i]
        if c == "\\":
            out.append(text[i:i + 2]); i += 2; continue
        if c == "$":
            expr = None
            if i + 1 < n and text[i + 1] == "{":
                depth, j = 1, i + 2
                while j < n and depth > 0:
                    if text[j] == "\\":
                        j += 2; continue
                    if text[j] == '"':
                        j += 1
                        while j < n and text[j] != '"':
                            j += 2 if text[j] == "\\" else 1
                        j += 1; continue
                    if text[j] == "{":
                        depth += 1
                    elif text[j] == "}":
                        depth -= 1
                    j += 1
                expr, end = text[i + 2:j - 1], j
            else:
                m = re.match(r"[A-Za-z_][A-Za-z0-9_.]*", text[i + 1:])
                if m:
                    expr, end = m.group(0), i + 1 + len(m.group(0))
            if expr is not None:
                idx = index_of.get(expr)
                if idx is None:
                    missing.append(expr)
                    out.append(text[i:end])
                else:
                    out.append(f"%{idx}$s")
                i = end
                continue
        out.append(c); i += 1
    return "".join(out), missing


def to_resource_value(lit: str):
    """和 i18n-apply.py 里同名函数保持一致：中文原文 → 资源文本 + 实参列表。"""
    out, args = [], []
    i, n, counter = 0, len(lit), 0
    while i < n:
        c = lit[i]
        if c == "\\":
            out.append(lit[i:i + 2]); i += 2; continue
        if c == "$":
            if i + 1 < n and lit[i + 1] == "{":
                depth, j = 1, i + 2
                while j < n and depth > 0:
                    if lit[j] == "\\":
                        j += 2; continue
                    if lit[j] == '"':
                        j += 1
                        while j < n and lit[j] != '"':
                            j += 2 if lit[j] == "\\" else 1
                        j += 1; continue
                    if lit[j] == "{":
                        depth += 1
                    elif lit[j] == "}":
                        depth -= 1
                    j += 1
                expr, end = lit[i + 2:j - 1], j
            else:
                m = re.match(r"[A-Za-z_][A-Za-z0-9_.]*", lit[i + 1:])
                if not m:
                    out.append(c); i += 1; continue
                expr, end = m.group(0), i + 1 + len(m.group(0))
            counter += 1
            out.append(f"{MARK}{counter}{MARK}")
            args.append(expr)
            i = end
            continue
        out.append(c); i += 1
    value = "".join(out)
    value = value.replace("&", "&amp;").replace("<", "&lt;")
    value = value.replace("'", "\\'").replace('"', '\\"')
    value = value.replace("\n", "\\n")
    if counter:
        value = value.replace("%", "%%")
        value = re.sub(MARK + r"(\d+)" + MARK, lambda m: f"%{m.group(1)}$s", value)
    if value != value.strip():
        value = f'"{value}"'
    return value, args


def escape(text: str) -> str:
    v = text.replace("&", "&amp;").replace("<", "&lt;")
    v = v.replace("'", "\\'").replace('"', '\\"')
    v = v.replace("\n", "\\n")
    if v != v.strip():
        v = f'"{v}"'
    return v


def main() -> int:
    dry = "--dry-run" in sys.argv
    rows = []
    with (ROOT / "tools/i18n-final.tsv").open(encoding="utf-8") as fh:
        header = fh.readline().rstrip("\n").split("\t")
        for line in fh:
            if line.strip():
                rows.append(dict(zip(header, line.rstrip("\n").split("\t"))))

    # 复算 key（与 codemod 同一套分配规则）
    used, plan = set(), []
    for row in rows:
        key = slug(row["en"])
        base, k = key, 2
        while key in used:
            key = f"{base}_{k}"; k += 1
        used.add(key)
        zh = row["zh"]
        zh_value, args = to_resource_value(zh)
        index_of = {expr: i + 1 for i, expr in enumerate(args)}
        plan.append((key, zh, zh_value, index_of, row))

    problems, changed = [], 0
    for folder, col in COLUMNS.items():
        path = RES / folder / "strings.xml"
        if not path.exists():
            continue
        text = path.read_text(encoding="utf-8")
        for key, zh, zh_value, index_of, row in plan:
            if not index_of:
                continue                      # 无插值，不碰
            raw = row[col]
            if col == "zh":
                new_value = zh_value
            else:
                converted, missing = convert(raw, index_of)
                if missing:
                    problems.append(f"{folder}/{key}: 译文缺占位符 {missing}（原文 {zh[:40]}）")
                new_value = escape(converted)
            pat = re.compile(r'(<string name="' + re.escape(key) + r'"(?:\s[^>]*)?>)(.*?)(</string>)', re.S)
            m = pat.search(text)
            if not m:
                continue
            if m.group(2) == new_value:
                continue
            text = text[:m.start(2)] + new_value + text[m.end(2):]
            changed += 1
            if dry:
                print(f"  [dry] {folder}/{key}: {m.group(2)[:50]}  →  {new_value[:50]}")
        if not dry:
            path.write_text(text, encoding="utf-8")

    print(f"改写的资源条目：{changed}")
    if problems:
        print(f"\n需要注意的译文（占位符对不上，信息可能丢失）：{len(problems)}")
        for p in problems:
            print("   ·", p)

    # 校验
    left = 0
    for folder in COLUMNS:
        p = RES / folder / "strings.xml"
        if not p.exists():
            continue
        for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', p.read_text(encoding="utf-8"), re.S):
            if re.search(r"\$\{|(?<!%\d)\$[A-Za-z_]", m.group(2)):
                left += 1
                print(f"   !! {folder}/{m.group(1)} 仍有未展开的模板：{m.group(2)[:60]}")
    print(f"残留未展开模板的资源条目：{left}（应为 0）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
