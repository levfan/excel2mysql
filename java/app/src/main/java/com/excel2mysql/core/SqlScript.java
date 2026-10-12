package com.excel2mysql.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * core/sql_script.py：从脚本里提 CREATE TABLE 表名、批量加前缀/换首段、按分号切语句。
 */
public final class SqlScript {

    /** 兼容 schema.table 限定名，取最后一段。 */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
          + "(?:`?[\\w$]+`?\\s*\\.\\s*)?`?([A-Za-z0-9_$]+)`?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern BARE_HEAD = Pattern.compile(
            "(CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?|DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?|"
          + "INSERT\\s+INTO\\s+|ALTER\\s+TABLE\\s+|TRUNCATE\\s+TABLE\\s+)"
          + "(?<![\\w$`])%s(?![\\w$`])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private SqlScript() {
    }

    /** 按首次出现顺序统计表名与出现次数。 */
    public static List<Map<String, Object>> extractCreateTables(String text) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        List<String> order = new ArrayList<String>();
        Matcher m = CREATE_TABLE.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (!counts.containsKey(name)) {
                counts.put(name, Integer.valueOf(0));
                order.add(name);
            }
            counts.put(name, Integer.valueOf(counts.get(name).intValue() + 1));
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (String n : order) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("name", n);
            row.put("count", counts.get(n));
            out.add(row);
        }
        return out;
    }

    /** 改写结果：新文本 + 改写清单 + 没匹配到引用的表名。 */
    public static final class Rewrite {
        public String text;
        public final List<Map<String, Object>> renamed = new ArrayList<Map<String, Object>>();
        public final List<String> noHit = new ArrayList<String>();
        /** 已带该前缀的 + 没匹配到引用的，顺序与 Python 的 skipped + nohit 一致。 */
        public final List<String> skipped = new ArrayList<String>();
    }

    private static Rewrite rewriteNames(String text, Map<String, String> plan) {
        Rewrite r = new Rewrite();
        String current = text;
        for (Map.Entry<String, String> e : plan.entrySet()) {
            String old = e.getKey();
            String rep = e.getValue();
            int n = 0;
            // 带反引号的引用全局替换，覆盖 DROP/INSERT/ALTER 等
            String quoted = Pattern.quote("`" + old + "`");
            Matcher q = Pattern.compile(quoted).matcher(current);
            StringBuffer sb = new StringBuffer();
            while (q.find()) {
                q.appendReplacement(sb, Matcher.quoteReplacement("`" + rep + "`"));
                n++;
            }
            q.appendTail(sb);
            current = sb.toString();

            Matcher b = Pattern.compile(String.format(BARE_HEAD.pattern(),
                            Pattern.quote(old)),
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS)
                    .matcher(current);
            StringBuffer sb2 = new StringBuffer();
            while (b.find()) {
                b.appendReplacement(sb2, Matcher.quoteReplacement(b.group(1) + rep));
                n++;
            }
            b.appendTail(sb2);
            current = sb2.toString();

            if (n > 0) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("old", old);
                item.put("new", rep);
                item.put("n", Integer.valueOf(n));
                r.renamed.add(item);
            } else {
                r.noHit.add(old);
            }
        }
        r.text = current;
        return r;
    }

    public static Rewrite applyPrefix(String text, String prefix) {
        Rewrite empty = new Rewrite();
        String p = prefix == null ? "" : Names.pyStrip(prefix).replace("`", "").replace("\\", "");
        if (p.isEmpty()) {
            empty.text = text;
            return empty;
        }
        Map<String, String> plan = new LinkedHashMap<String, String>();
        for (Map<String, Object> t : extractCreateTables(text)) {
            String old = (String) t.get("name");
            if (old.startsWith(p)) {
                empty.skipped.add(old);        // 已有该前缀，重复应用不叠加
                continue;
            }
            plan.put(old, p + old);
        }
        Rewrite r = rewriteNames(text, plan);
        r.skipped.addAll(0, empty.skipped);
        r.skipped.addAll(r.noHit);
        return r;
    }

    public static Rewrite replaceFirstSegment(String text, String newHead) {
        Rewrite empty = new Rewrite();
        String head = Prefix.clean(newHead);
        if (head.isEmpty()) {
            empty.text = text;
            return empty;
        }
        Map<String, String> plan = new LinkedHashMap<String, String>();
        for (Map<String, Object> t : extractCreateTables(text)) {
            String old = (String) t.get("name");
            String rep = Prefix.swapFirstSegment(old, head);
            if (rep == null) {
                empty.skipped.add(old);
                continue;
            }
            plan.put(old, rep);
        }
        Rewrite r = rewriteNames(text, plan);
        r.skipped.addAll(0, empty.skipped);
        r.skipped.addAll(r.noHit);
        return r;
    }

    /**
     * 按分号切语句，正确跳过 '...' / "..." 字符串（支持反斜杠与双写引号转义）、
     * `...` 反引号标识符、-- 与 # 行注释、块注释。不支持 DELIMITER 存储过程脚本。
     */
    public static List<String> splitStatements(String text) {
        List<String> stmts = new ArrayList<String>();
        StringBuilder buf = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            char nxt = i + 1 < n ? text.charAt(i + 1) : 0;
            if (ch == '\'' || ch == '"' || ch == '`') {
                char quote = ch;
                buf.append(ch);
                i++;
                while (i < n) {
                    char c = text.charAt(i);
                    buf.append(c);
                    if (c == '\\' && quote != '`' && i + 1 < n) {
                        buf.append(text.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (c == quote) {
                        if (i + 1 < n && text.charAt(i + 1) == quote) {
                            buf.append(text.charAt(i + 1));
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if ((ch == '-' && nxt == '-') || ch == '#') {
                int j = text.indexOf('\n', i);
                if (j < 0) {
                    j = n;
                }
                buf.append(text, i, j);
                i = j;
                continue;
            }
            if (ch == '/' && nxt == '*') {
                int j = text.indexOf("*/", i + 2);
                j = j < 0 ? n : j + 2;
                buf.append(text, i, j);
                i = j;
                continue;
            }
            if (ch == ';') {
                String s = Names.pyStrip(buf.toString());
                if (!s.isEmpty()) {
                    stmts.add(s);
                }
                buf.setLength(0);
                i++;
                continue;
            }
            buf.append(ch);
            i++;
        }
        String tail = Names.pyStrip(buf.toString());
        if (!tail.isEmpty()) {
            stmts.add(tail);
        }
        return stmts;
    }
}
