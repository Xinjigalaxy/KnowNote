#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Kotlin 源码里的中文字符串字面量扫描器（状态机）。

为什么不用正则：Kotlin 的字符串模板允许嵌套字符串，
    "「${note.title.ifBlank { "未命名笔记" }}」已永久删除"
第一个写歪的版本就把这种写法切错了边界 —— 于是 codemod 改坏了两处代码。
所以这里老实按状态机走：注释 / 转义 / 字符字面量 / 模板嵌套 / raw string 都要认。

对外只暴露 scan(source) -> [(start, end, text)]，text 是**带转义的原始内容**。
"""
from __future__ import annotations

import re

CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\u3040-\u30ff\u3000-\u303f]")


def _skip_string(src: str, i: int, n: int):
    """src[i] == '"'，返回结束引号之后的下标；支持 ${} 模板（含嵌套字符串）。"""
    i += 1
    while i < n:
        c = src[i]
        if c == "\\":
            i += 2
            continue
        if c == '"':
            return i + 1
        if c == "$":
            if i + 1 < n and src[i + 1] == "{":
                i = _skip_expr(src, i + 1, n)
                continue
            m = re.match(r"[A-Za-z_][A-Za-z0-9_]*", src[i + 1:])
            if m:
                i += 1 + len(m.group(0))
                continue
        i += 1
    return n


def _skip_expr(src: str, i: int, n: int):
    """src[i] == '{'，跳到匹配的 '}' 之后；表达式里可以出现任意字符串/字符/括号。"""
    depth = 0
    while i < n:
        c = src[i]
        if c == "\\":
            i += 2; continue
        if c == '"':
            i = _skip_string(src, i, n); continue
        if c == "'":
            i += 2 if (i + 1 < n and src[i + 1] == "\\") else 1
            while i < n and src[i] != "'":
                i += 1
            i += 1; continue
        if c == "/" and i + 1 < n and src[i + 1] in "/*":
            i = _skip_comment(src, i, n); continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return n


def _skip_comment(src: str, i: int, n: int):
    if src.startswith("//", i):
        j = src.find("\n", i)
        return n if j < 0 else j + 1
    j = src.find("*/", i + 2)
    return n if j < 0 else j + 2


def scan(source: str):
    """返回 [(start, end, text)]，start/end 含两侧引号，text 为引号内原文。"""
    out = []
    i, n = 0, len(source)
    while i < n:
        c = source[i]
        if c == "/" and i + 1 < n and source[i + 1] in "/*":
            i = _skip_comment(source, i, n); continue
        if source.startswith('"""', i):
            j = source.find('"""', i + 3)
            i = n if j < 0 else j + 3
            continue
        if c == '"':
            end = _skip_string(source, i, n)
            text = source[i + 1:end - 1] if source[end - 1:end] == '"' else source[i + 1:end]
            out.append((i, end, text))
            i = end
            continue
        if c == "'":
            j = i + 1
            while j < n and source[j] != "'":
                j += 2 if source[j] == "\\" else 1
            i = j + 1
            continue
        i += 1
    return out


def literals_with_cjk(source: str):
    """只保留含中文/日文的字面量。"""
    return [span for span in scan(source) if CJK.search(span[2])]


if __name__ == "__main__":
    import pathlib
    import sys

    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/java")
    seen, rows = {}, []
    for f in sorted(root.rglob("*.kt")):
        for start, end, text in literals_with_cjk(f.read_text(encoding="utf-8")):
            rows.append((str(f), text))
            seen.setdefault(text, 0)
            seen[text] += 1
    print(f"字面量出现次数 {len(rows)}，去重 {len(seen)}")
    for text, cnt in seen.items():
        print(f"  x{cnt}  {text[:80]}")
