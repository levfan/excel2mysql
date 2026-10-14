# -*- coding: utf-8 -*-
"""把内置《使用说明》(help_doc.py) 导出成一份纯文本操作手册。

为什么要这份文件：图形版的构建产物里原先只有一个 exe，现场拿到手没有能直接读的说明书，
必须先双击 exe 再点右上角「?」才看得到——而不确定 exe 能不能跑的机器上，人恰恰不想先双击。
内容不重写一遍，是从 help_doc.py 的 SECTIONS 渲染出来的，所以随包手册和界面内置说明
不会各说各话（改文案只改 SECTIONS，两边一起变）。

用法：
    python export_manual_txt.py <输出路径> [产品线标签]
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from help_doc import SECTIONS  # noqa: E402
from appinfo import APP_VERSION  # noqa: E402

HEADER = """Excel → MySQL 批量导入工具 v{version}（{line}）
===========================================================================

怎么开始
  双击本目录里的可执行文件即可，不用装 Python、不用联网。
  界面上任何一步都能点右上角「?」（或按 F1）打开同一份说明；这份文件是它的纯文本版，
  方便在还没双击 exe 之前先看清楚要求和支持范围。

这一份对应哪条交付线
  本项目有三条交付线，功能区别见下面「三条线与选型」一节，别把命令行的开关抄到本包里。

目录（下面逐节展开）
{toc}
===========================================================================
"""

TWO_SPACES = re.compile(r"[ \t]{2,}")
TAG_RE = re.compile(r"<[^>]+>")


def _cell(text):
    """表格单元格：去掉标签，把折行压成一行——手册里一行就是一条记录。"""
    return TWO_SPACES.sub(" ", " ".join(TAG_RE.sub("", text).split())).strip()


def render_body(html):
    """把一节 HTML 渲染成可读的纯文本（标题、列表、表格、代码块都保序）。"""
    out = []
    # 先按块级标签切段，避免 <tr>/<li>/<p> 粘成一坨
    chunks = re.split(r"(?=<h2>|<h3>|<li>|<tr>|<p|<pre>)", html.strip())
    for ch in chunks:
        ch = ch.strip()
        if not ch:
            continue
        if ch.startswith("<h2>"):
            out.append("\n【" + _cell(ch) + "】\n")
        elif ch.startswith("<h3>"):
            out.append("\n— " + _cell(ch) + "\n")
        elif ch.startswith("<li>"):
            out.append("  · " + _cell(ch))
        elif ch.startswith("<tr>"):
            cells = [_cell(c) for c in re.findall(r"<t[hd][^>]*>(.*?)</t[hd]>",
                                                  ch, re.S)]
            cells = [c for c in cells if c]
            if not cells:
                continue
            head = ch.find("<th") >= 0
            out.append(("  ▸ " if head else "    ") + " | ".join(cells))
        elif ch.startswith("<pre>"):
            for ln in TAG_RE.sub("", ch).strip().splitlines():
                out.append("      " + ln.rstrip())
        elif ch.startswith("<p"):
            txt = _cell(ch)
            if txt:
                out.append(("提示  " if ch.find('class="tip"') >= 0 else "")
                           + txt + "\n")
        else:
            txt = _cell(ch)
            if txt:
                out.append(txt)
    return "\n".join(out).rstrip() + "\n"


def render(line_label):
    parts = [HEADER.format(version=APP_VERSION, line=line_label,
                           toc="\n".join("  %2d. %s" % (i + 1, title)
                                         for i, (_a, title, _b) in enumerate(SECTIONS)))]
    for i, (_anchor, title, body) in enumerate(SECTIONS):
        parts.append("\n\n" + "=" * 72 + "\n%2d. %s\n" % (i + 1, title) + "=" * 72 + "\n")
        parts.append(render_body(body))
    return "\n".join(parts).replace("\r\n", "\n")


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "README-manual.txt")
    label = sys.argv[2] if len(sys.argv) > 2 else "图形版（Windows 10 1809+ / 11）"
    text = render(label)
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with io.open(out, "wb") as fh:
        fh.write(text.replace("\n", "\r\n").encode("utf-8"))
    print("wrote", out, len(text), "字符 /", len(SECTIONS), "章")
    return 0


if __name__ == "__main__":
    sys.exit(main())
