# -*- coding: utf-8 -*-
"""按"显示列"算宽度的那套规则，只给结果区表格用。

为什么不直接用 `unicodedata.east_asian_width`：那个表跟着 Unicode 版本走，
Python 升一次版就可能改口，而这条规则的两端是 Python 与 Java 两份实现——
两边必须在**任何机器上**给出同一个宽度，否则同一个查询在两条线上排出不同的表。
所以宽度写成显式区间表，与 Java 侧 core/TextWidth.java 逐区间对齐，
并由金标准差分（SqlDiffer 的 table-render 分支）钉住。

弹窗折行用的是另一条规则（`main._display_cols` / `gui.PopupText`），
那边只要求两边对同一句折出同一个串，不参与跨语言金标准，所以没并到一起。
"""

# 宽字符（含全角）占 2 列，其余占 1 列。区间与 Java 侧一致，改动要两边同步。
_WIDE_RANGES = (
    (0x1100, 0x115F),
    (0x2329, 0x232A),
    (0x2E80, 0x303E),
    (0x3041, 0x33FF),
    (0x3400, 0x4DBF),
    (0x4E00, 0x9FFF),
    (0xA000, 0xA4CF),
    (0xAC00, 0xD7A3),
    (0xF900, 0xFAFF),
    (0xFE10, 0xFE19),
    (0xFE30, 0xFE6F),
    (0xFF00, 0xFF60),
    (0xFFE0, 0xFFE6),
    (0x20000, 0x3FFFD),
)


def display_cols(ch):
    """一个字符占几列。传入长度必须为 1。"""
    cp = ord(ch)
    for lo, hi in _WIDE_RANGES:
        if lo <= cp <= hi:
            return 2
    return 1


def width(text):
    n = 0
    for ch in text:
        n += display_cols(ch)
    return n


def clip(text, max_width):
    """截到 max_width 列以内，截过就补一个省略号（省略号本身占 1 列）。"""
    if width(text) <= max_width:
        return text
    out = []
    used = 0
    budget = max_width - 1          # 给省略号留一格
    for ch in text:
        c = display_cols(ch)
        if used + c > budget:
            break
        out.append(ch)
        used += c
    return "".join(out) + "…"


def pad(text, target_width):
    """右侧补空格到 target_width 列。"""
    gap = target_width - width(text)
    return text + " " * gap if gap > 0 else text
