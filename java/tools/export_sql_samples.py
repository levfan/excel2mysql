# -*- coding: utf-8 -*-
"""一次性：把 sql_samples.py 的 SAMPLES 抽成 Java 源，两条图形线共用一份示例。

和 export_help_java.py 同一个道理：示例清单只要有两份，就一定会各说各话，
而且界面等价是靠枚举对撞判定的，两边条目不同直接对不上。
一条示例七个字段（分组/短标题/说明/场景/解决的问题/关键词/语句）都跟着走，
连搜索用的 haystack/matches 也一起生成——两边同一个词命中不同批条目，
是最难被发现的那种不等价。
"""
import io
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")))
from sql_samples import SAMPLES, haystack  # noqa: E402

OUT = os.path.join("app", "src", "main", "java", "com", "excel2mysql", "gui", "SqlSamples.java")


def java_str(text):
    """Java 8 没有文本块，多行语句按行相加；\\n 由 NL 常量拼。"""
    lines = text.split("\n")
    if not lines:
        return '""'
    parts = []
    for i, ln in enumerate(lines):
        quoted = '"' + ln.replace("\\", "\\\\").replace('"', '\\"') + '"'
        parts.append(("            " if i == 0 else "                    ") + quoted
                     + (" " + "+ NL +" if i < len(lines) - 1 else ""))
    return "\n".join(parts)


def java_one_line(text):
    """单行字段（分组/标题/说明/场景/问题/关键词）：原文里没有换行，直接引号包住。"""
    assert "\n" not in text, text
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


out = []
out.append("package com.excel2mysql.gui;")
out.append("")
out.append("/**")
out.append(" * SQL 执行器页签的示例清单，由 java/tools/export_sql_samples.py")
out.append(" * 从仓库根目录的 sql_samples.py 导出，不要在 Java 侧手改。")
out.append(" * 两条图形线共用同一份来源，界面上那条「示例 (?)」按钮打开的就是这张表。")
out.append(" *")
out.append(" * 一条示例七个字段：目录用 group + title（短标题，长的那句在 desc 里），")
out.append(" * 右侧介绍用 desc + scenario + problem，左侧模糊搜索按 keywords 与这几项、")
out.append(" * 语句本身一起匹配（见 {@link #haystack}）。")
out.append(" */")
out.append("public final class SqlSamples {")
out.append("")
out.append('    public static final String NL = String.valueOf((char) 10);')
out.append("")
out.append("    /** 一条示例：分组、短标题、说明、场景、解决的问题、关键词、可执行语句。 */")
out.append("    public static final class Sample {")
out.append("        public final String group;")
out.append("        public final String title;")
out.append("        public final String desc;")
out.append("        public final String scenario;")
out.append("        public final String problem;")
out.append("        public final String keywords;")
out.append("        public final String sql;")
out.append("")
out.append("        Sample(String group, String title, String desc, String scenario,")
out.append("               String problem, String keywords, String sql) {")
out.append("            this.group = group;")
out.append("            this.title = title;")
out.append("            this.desc = desc;")
out.append("            this.scenario = scenario;")
out.append("            this.problem = problem;")
out.append("            this.keywords = keywords;")
out.append("            this.sql = sql;")
out.append("        }")
out.append("    }")
out.append("")
out.append("    /** 共 %d 条，顺序即对话框里目录的顺序。 */" % len(SAMPLES))
out.append("    public static final Sample[] ALL = new Sample[] {")
for i, s in enumerate(SAMPLES):
    out.append("        new Sample(%s," % java_one_line(s.group))
    out.append("                %s, %s," % (java_one_line(s.title), java_one_line(s.desc)))
    out.append("                %s," % java_one_line(s.scenario))
    out.append("                %s," % java_one_line(s.problem))
    out.append("                %s," % java_one_line(s.keywords))
    out.append("                %s)%s" % (java_str(s.sql), "," if i < len(SAMPLES) - 1 else ""))
out.append("    };")
out.append("")
out.append("    private SqlSamples() {")
out.append("    }")
out.append("")
out.append("    /**")
out.append("     * 一条示例可被搜到的全部文本（统一转小写），与 Python 侧 sql_samples.haystack")
out.append("     * 同一份字段拼法：搜索框里同一个词，两条线必须命中同一批条目。")
out.append("     * 匹配就是一句子串包含，不引分词与打分——示例是给人挑的，不是检索引擎。")
out.append("     */")
out.append("    public static String haystack(Sample s) {")
out.append('        return (s.group + " " + s.title + " " + s.desc + " " + s.scenario + " "')
out.append('                + s.problem + " " + s.keywords + " " + s.sql)')
out.append("                .toLowerCase(java.util.Locale.ROOT);")
out.append("    }")
out.append("")
out.append("    /** 关键词过滤：空串等于不过滤。 */")
out.append("    public static boolean matches(Sample s, String needle) {")
out.append("        String key = needle == null ? \"\" : needle.trim().toLowerCase(java.util.Locale.ROOT);")
out.append("        return key.isEmpty() || haystack(s).contains(key);")
out.append("    }")
out.append("")
out.append("    /**")
out.append("     * 右侧上方那段介绍的正文，与 Python 侧 SqlSamplesDialog.intro_text 逐字同形：")
out.append("     * 纯文本五行，不引 HTML 引擎。")
out.append("     */")
out.append("    public static String introText(Sample s) {")
out.append('        return "【" + s.group + " · " + s.title + "】" + NL')
out.append('                + "说明：" + s.desc + NL')
out.append('                + "场景：" + s.scenario + NL')
out.append('                + "解决的问题：" + s.problem + NL')
out.append('                + "关键词：" + s.keywords;')
out.append("    }")
out.append("}")

io.open(OUT, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
print("wrote", OUT, "samples", len(SAMPLES))
