# -*- coding: utf-8 -*-
"""一次性：把 help_doc.py 的 SECTIONS 抽成 Java 源，内容不重写、只换载体。"""
import io
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")))
from help_doc import SECTIONS  # noqa: E402

OUT = os.path.join("app", "src", "main", "java", "com", "excel2mysql", "gui", "HelpDoc.java")


def java_str(text):
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def java_multiline(text):
    """把一段 HTML 拆成行相加，避免 Java 8 里没有的三引号写法。"""
    lines = text.split("\n")
    if lines and not lines[0]:
        lines = lines[1:]
    if lines and not lines[-1]:
        lines = lines[:-1]
    if not lines:
        return '""'
    parts = []
    for i, ln in enumerate(lines):
        parts.append(("            " if i == 0 else "                    ")
                     + java_str(ln) + (" " + "+ NL +" if i < len(lines) - 1 else ""))
    return "\n".join(parts)


out = []
out.append("package com.excel2mysql.gui;")
out.append("")
out.append("/**")
out.append(" * 使用说明正文。内容与 Python 侧 help_doc.py 的 SECTIONS 一一对应，")
out.append(" * 由 tools 里的导出脚本生成，不要手改——改文案请改 help_doc.py 再重新导出，")
out.append(" * 否则两条线的说明会分叉。")
out.append(" */")
out.append("public final class HelpDoc {")
out.append("")
out.append("    /** NL 而不是反斜杠 n：Java 源里的换行转义容易被工具链吃掉。 */")
out.append('    public static final String NL = String.valueOf((char) 10);')
out.append("")
out.append("    /** 一章：锚点、目录标题、正文 HTML 片段。 */")
out.append("    public static final class Section {")
out.append("        public final String anchor;")
out.append("        public final String title;")
out.append("        public final String body;")
out.append("")
out.append("        Section(String anchor, String title, String body) {")
out.append("            this.anchor = anchor;")
out.append("            this.title = title;")
out.append("            this.body = body;")
out.append("        }")
out.append("    }")
out.append("")
out.append("    private static final Section[] LIST = {")
for anchor, title, body in SECTIONS:
    out.append("        new Section(" + java_str(anchor) + ", " + java_str(title) + ",")
    body_src = java_multiline(body)
    out.append(body_src.replace("            ", "                ", 1))
    out.append("        ),")
out.append("    };")
out.append("")
out.append("    private HelpDoc() {")
out.append("    }")
out.append("")
out.append("    public static Section[] sections() {")
out.append("        return LIST.clone();")
out.append("    }")
out.append("")
out.append("    public static int count() {")
out.append("        return LIST.length;")
out.append("    }")
out.append("")
out.append("    /** 目录标题，顺序与正文一致。 */")
out.append("    public static String[] titles() {")
out.append("        String[] out = new String[LIST.length];")
out.append("        for (int i = 0; i < LIST.length; i++) {")
out.append("            out[i] = LIST[i].title;")
out.append("        }")
out.append("        return out;")
out.append("    }")
out.append("")
out.append("    /** Qt 的 Rich Text 只吃 CSS 子集，Java 的 HTML 渲染器同理：属性写多了不报错但静默失效。 */")
out.append('    public static final String STYLE = String.join(NL,')
style_src = io.open(os.path.join(os.path.dirname(__file__), "..", "..", "ui_help.py"),
                    encoding="utf-8").read()
start = style_src.index('STYLE = """') + len('STYLE = """')
end = style_src.index('"""', start)
for line in style_src[start:end].split("\n"):
    if line.strip() == "":
        continue
    out.append("            " + java_str(line) + ",")
out[-1] = out[-1].rstrip(",")
out.append("    );")
out.append("")
out.append("    public static String html() {")
out.append('        StringBuilder sb = new StringBuilder("<html><head><style>");')
out.append("        sb.append(STYLE).append(\"</style></head><body>\");")
out.append("        for (int i = 0; i < LIST.length; i++) {")
out.append("            if (i > 0) {")
out.append('                sb.append("<hr>");')
out.append("            }")
out.append('            String body = LIST[i].body;')
out.append('            int h2 = body.indexOf("<h2");')
out.append('            sb.append("<a name=\\"").append(LIST[i].anchor).append("\\"></a>");')
out.append('            if (h2 >= 0) {')
out.append('                // 空的 a 标签渲染不出元素，所以把 id 挂到真有内容的 h2 上，')
out.append('                // 目录跳转才有落点；文字本身一个字节没改。')
out.append('                sb.append(body, 0, h2 + 3).append(" id=\\"").append(LIST[i].anchor)')
out.append('                        .append("\\"").append(body.substring(h2 + 3));')
out.append('            } else {')
out.append('                sb.append(body);')
out.append('            }')

out.append("        }")
out.append('        return sb.append("</body></html>").toString();')
out.append("    }")
out.append("}")
out.append("")

io.open(OUT, "w", encoding="utf-8", newline="\n").write("\n".join(out))
print("wrote", OUT, "sections", len(SECTIONS))
