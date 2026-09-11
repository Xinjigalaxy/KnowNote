#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""i18n codemod v2：按扫描到的**位置**替换，而不是全局子串替换。

输入
  tools/i18n-final.tsv     zh / en / zh-TW / ja（zh 必须和源码字面量逐字一致）
输出
  app/src/main/res/values{,-zh,-zh-rTW,-ja}/strings.xml（与已有键合并）
  就地改写 app/src/main/java/.../ui/**.kt

为什么按位置替换：字符串模板可以嵌套字符串，
    "「${t.ifBlank { "未命名" }}」已删除"
全局 replace 会切在错误的位置上，把代码改坏（第一版就是这么炸的）。
这里用 tools/i18n_scan.py 的状态机拿到精确区间，再从后往前替换。

data/** 与 *ViewModel*.kt 不在这里处理（它们的文案要么是诊断信息、要么要走 @StringRes 参数），
脚本会把它们列出来给人处理。
"""
from __future__ import annotations

import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from i18n_scan import literals_with_cjk  # noqa: E402

ROOT = pathlib.Path(".")
SRC_DIR = ROOT / "app/src/main/java"
RES_DIR = ROOT / "app/src/main/res"

MARK = "\x00"
COLUMNS = {"values": "en", "values-zh": "zh", "values-zh-rTW": "zhTW", "values-ja": "ja"}


def read_final():
    rows = []
    with (ROOT / "tools/i18n-final.tsv").open(encoding="utf-8") as fh:
        header = fh.readline().rstrip("\n").split("\t")
        for line in fh:
            if line.strip():
                rows.append(dict(zip(header, line.rstrip("\n").split("\t"))))
    return rows


def slug(text: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")
    if not s:
        s = "s"
    if s[0].isdigit():
        s = "s_" + s
    return s[:48]


def to_resource_value(lit: str):
    """字面量 → (资源文本, 是否有格式参数, 实参表达式列表)。"""
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
                        j += 1
                        continue
                    if lit[j] == "{":
                        depth += 1
                    elif lit[j] == "}":
                        depth -= 1
                    j += 1
                expr = lit[i + 2:j - 1]
            else:
                m = re.match(r"[A-Za-z_][A-Za-z0-9_.]*", lit[i + 1:])
                if not m:
                    out.append(c); i += 1; continue
                expr = m.group(0)
                j = i + 1 + len(expr)
            counter += 1
            out.append(f"{MARK}{counter}{MARK}")
            args.append(expr)
            i = j
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
    return value, counter > 0, args


def escape_translation(text: str) -> str:
    v = text.replace("&", "&amp;").replace("<", "&lt;")
    v = v.replace("'", "\\'").replace('"', '\\"')
    v = v.replace("\n", "\\n")
    if v != v.strip():
        v = f'"{v}"'
    return v


def main() -> int:
    rows = read_final()
    mapping, used = {}, set()
    for row in rows:
        zh = row["zh"]
        key = slug(row["en"])
        base, n = key, 2
        while key in used:
            key = f"{base}_{n}"; n += 1
        used.add(key)
        value, has_args, args = to_resource_value(zh)
        mapping[zh] = (key, value, has_args, args, {
            "zh": value,
            "en": escape_translation(row["en"]),
            "zhTW": escape_translation(row["zh-TW"]),
            "ja": escape_translation(row["ja"]),
        })

    # ---------- 写资源 ----------
    for folder, col in COLUMNS.items():
        path = RES_DIR / folder / "strings.xml"
        existing = path.read_text(encoding="utf-8") if path.exists() else ""
        have = set(re.findall(r'<string name="([^"]+)"', existing))
        body = [f'    <string name="{k}">{v[col]}</string>'
                for zh, (k, _rv, _ha, _a, v) in mapping.items() if k not in have]
        if not body:
            print(f"  {folder}: 无需新增"); continue
        if existing:
            new = existing.replace("</resources>", "\n".join(body) + "\n</resources>")
        else:
            new = ('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                   + "\n".join(body) + "\n</resources>\n")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(new, encoding="utf-8")
        print(f"  {folder}: +{len(body)} 条")

    # ---------- 改写调用点 ----------
    changed, skipped, untouched = 0, [], []
    for f in sorted(SRC_DIR.rglob("*.kt")):
        rel = str(f.relative_to(SRC_DIR)).replace("\\", "/")
        source = f.read_text(encoding="utf-8")
        spans = literals_with_cjk(source)
        if "/ui/" not in "/" + rel or "ViewModel" in f.name or f.name == "ViewModels.kt":
            if spans:
                skipped.append((rel, len(spans)))
            continue
        hits = [(s, e, t) for (s, e, t) in spans if t in mapping]
        if not hits:
            if spans:
                untouched.append((rel, [t for _, _, t in spans]))
            continue
        for start, end, text in sorted(hits, key=lambda x: -x[0]):
            key, _v, _ha, args, _vals = mapping[text]
            call = f"stringResource(R.string.{key}" + (", " + ", ".join(args) if args else "") + ")"
            source = source[:start] + call + source[end:]
        if "import androidx.compose.ui.res.stringResource" not in source:
            source = source.replace(
                "import androidx.compose.runtime.Composable",
                "import androidx.compose.runtime.Composable\nimport androidx.compose.ui.res.stringResource", 1)
        if "import com.xinjigalaxy.knownotes.R" not in source:
            source = source.replace(
                "import androidx.compose.ui.unit.dp",
                "import androidx.compose.ui.unit.dp\nimport com.xinjigalaxy.knownotes.R", 1)
        f.write_text(source, encoding="utf-8")
        changed += 1
        print(f"  改写 {rel}（{len(hits)} 处）")

    print(f"\n改写文件 {changed} 个")
    print(f"\n跳过（非可组合上下文，需手动）：")
    for rel, n in skipped:
        print(f"   - {rel}（{n} 处中文）")
    if untouched:
        print(f"\n可组合文件里没匹配上译文表的字面量（需手动确认）：")
        for rel, texts in untouched:
            print(f"   - {rel}: {texts}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
