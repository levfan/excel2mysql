# -*- coding: utf-8 -*-
"""一次性：把 sql_samples.py 的 SAMPLES 抽成 Java 源，两条图形线共用一份示例。

和 export_help_java.py 同一个道理：示例清单只要有两份，就一定会各说各话，
而且界面等价是靠枚举对撞判定的，两边条目不同直接对不上。
"""
import io
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")))
from sql_samples import SAMPLES  # noqa: E402

OUT = os.path.join("app", "src", "main", "java", "com", "excel2mysql", "gui", "SqlSamples.java")


def java_str(text):
    """Java 8 没有文本块，多行语句按行相加；\n 由 NL 常量拼。"""
    lines = text.split("\n")
    if not lines:
        return '""'
    parts = []
    for i, ln in enumerate(lines):
        quoted = '"' + ln.replace("\\", "\\\\").replace('"', '\\"') + '"'
        parts.append(("            " if i == 0 else "                    ") + quoted
                     + (" " + "+ NL +" if i < len(lines) - 1 else ""))
    return "\n".join(parts)


out = []
out.append("package com.excel2mysql.gui;")
out.append("")
out.append("/**")
out.append(" * SQL 执行器页签的示例清单，由 java/tools/export_sql_samples.py")
out.append(" * 从仓库根目录的 sql_samples.py 导出，不要在 Java 侧手改。")
out.append(" * 两条图形线共用同一份来源，界面上那条「示例 (?)」按钮打开的就是这张表。")
out.append(" */")
out.append("public final class SqlSamples {")
out.append("")
out.append('    public static final String NL = String.valueOf((char) 10);')
out.append("")
out.append("    /** 一条示例：所属分组、做什么用、可执行语句。 */")
out.append("    public static final class Sample {")
out.append("        public final String group;")
out.append("        public final String desc;")
out.append("        public final String sql;")
out.append("")
out.append("        Sample(String group, String desc, String sql) {")
out.append("            this.group = group;")
out.append("            this.desc = desc;")
out.append("            this.sql = sql;")
out.append("        }")
out.append("    }")
out.append("")
out.append("    /** 共 %d 条，顺序即对话框里的顺序。 */" % len(SAMPLES))
out.append("    public static final Sample[] ALL = new Sample[] {")
for i, (group, desc, sql) in enumerate(SAMPLES):
    out.append("        new Sample(%s," % java_str(group))
    out.append("                %s," % java_str(desc))
    out.append("                %s)%s" % (java_str(sql), "," if i < len(SAMPLES) - 1 else ""))
out.append("    };")
out.append("")
out.append("    private SqlSamples() {")
out.append("    }")
out.append("}")
out.append("")

io.open(OUT, "w", encoding="utf-8", newline="\n").write("\n".join(out))
print("wrote", OUT, "samples", len(SAMPLES))
