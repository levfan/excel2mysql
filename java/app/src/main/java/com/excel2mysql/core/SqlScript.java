package com.excel2mysql.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    /** 执行前要删的那张表：schema 限定名整段留下（db.tbl 就该删 db.tbl）。 */
    private static final Pattern CREATE_TARGET = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
          + "((?:`?[\\w$]+`?\\s*\\.\\s*)?`?[A-Za-z0-9_$]+`?)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** 覆盖执行准备：执行副本 + 这份脚本要建的表名（按出现顺序去重）。 */
    public static final class DropPrep {
        public final String text;
        public final List<String> tables = new ArrayList<String>();

        DropPrep(String text) {
            this.text = text;
        }
    }

    /** 整句 DROP TABLE 的开头：脚本自己已经删过的表，「覆盖已存在表」不再补第二句。 */
    private static final Pattern DROP_HEAD = Pattern.compile(
            "^DROP\\s+TABLE\\b(.*)$", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_IF_EXISTS = Pattern.compile(
            "^\\s*IF\\s+EXISTS\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_TARGET_ITEM = Pattern.compile(
            "`?[A-Za-z0-9_$]+`?(?:\\s*\\.\\s*`?[A-Za-z0-9_$]+`?)?");

    /**
     * `db`.`t` / db.t / `t` 归成同一个比对键：只去反引号与空白，大小写留着。
     *
     * 反引号不影响 MySQL 认哪张表；大小写在 Linux 上是两张表，所以不敢不分。
     * 比对只用来判断"脚本自己删过没有"，键取不上就是多补一句 DROP（原有行为），
     * 不会因为归一过头而少删。
     */
    private static String dropKey(String target) {
        return target.replace("`", "").replaceAll("\\s+", "");
    }

    /** 这一句是整句 DROP TABLE 的话，返回它删掉的表名键；掺了认不出的写法就整句不算。 */
    private static List<String> droppedTargets(String stmt) {
        String masked = maskComments(stmt).trim();
        Matcher m = DROP_HEAD.matcher(masked);
        if (!m.matches()) {
            return new ArrayList<String>();
        }
        String rest = m.group(1);
        Matcher ifExists = DROP_IF_EXISTS.matcher(rest);
        if (ifExists.find()) {
            rest = rest.substring(ifExists.end());
        }
        rest = rest.trim();
        if (rest.endsWith(";")) {
            rest = rest.substring(0, rest.length() - 1).trim();
        }
        List<String> keys = new ArrayList<String>();
        for (String part : rest.split(",")) {
            String one = part.trim();
            if (one.isEmpty()) {
                continue;
            }
            if (!DROP_TARGET_ITEM.matcher(one).matches()) {
                return new ArrayList<String>();   // 认不出的写法：整句不算，宁可多补一句
            }
            keys.add(dropKey(one));
        }
        return keys;
    }

    /**
     * 给执行副本开头的每个建表目标补一句 DROP TABLE IF EXISTS。
     *
     * 只在「覆盖已存在表」勾选后由执行动作调用，不写回脚本正文。
     * 只认整句以 CREATE TABLE 开头的语句：注释里的建表、字符串里的建表都不该被删，
     * 这是删除动作，判据必须比"清单里显示哪些表"更严。
     * 脚本自己已经写了 DROP TABLE 的表不再补第二句：补出来的那一批排在副本最前面，
     * 会跑在整库 dump 自带的 /*!40014 SET ... FOREIGN_KEY_CHECKS=0 *&#47; 之前，
     * 被外键引用的父表在那之前删不掉，现场看到的就是 MySQL 3730。
     * 顺序按出现顺序倒过来，近似"先删子表再删父表"。与 core/sql_script.py 同语义。
     */
    public static DropPrep prependDropTables(String text) {
        List<String> statements = splitStatements(text);
        Set<String> droppedKeys = new HashSet<String>();
        for (String stmt : statements) {
            droppedKeys.addAll(droppedTargets(stmt));
        }
        Map<String, String> pending = new LinkedHashMap<String, String>();
        for (String stmt : statements) {
            String masked = maskComments(stmt).trim();
            if (!Pattern.compile("^CREATE\\s+TABLE\\b", Pattern.CASE_INSENSITIVE)
                    .matcher(masked).find()) {
                continue;
            }
            Matcher m = CREATE_TARGET.matcher(masked);
            if (!m.find()) {
                continue;
            }
            String target = m.group(1).replaceAll("\\s+", "");
            String key = dropKey(target);
            if (droppedKeys.contains(key) || pending.containsKey(key)) {
                continue;
            }
            pending.put(key, target);
        }
        if (pending.isEmpty()) {
            DropPrep none = new DropPrep(text);
            return none;
        }
        List<String> names = new ArrayList<String>(pending.values());
        StringBuilder block = new StringBuilder();
        for (int i = names.size() - 1; i >= 0; i--) {
            block.append("DROP TABLE IF EXISTS ").append(names.get(i)).append(";\n");
        }
        block.append('\n').append(text);
        DropPrep out = new DropPrep(block.toString());
        out.tables.addAll(names);
        return out;
    }

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

    /** 把一个现有表名改成新名字，并同步 CREATE/DROP/INSERT/ALTER/TRUNCATE 引用。 */
    public static Rewrite renameTable(String text, String oldName, String newName) {
        Rewrite r = new Rewrite();
        r.text = text;
        String old = trimIdentifier(oldName);
        String rep = trimIdentifier(newName);
        if (old.isEmpty() || rep.isEmpty() || old.equals(rep)
                || !rep.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
            return r;
        }
        Map<String, String> plan = new LinkedHashMap<String, String>();
        plan.put(old, rep);
        return rewriteNames(text, plan);
    }

    /** 把页面显示名一次性写进脚本：{脚本当前表名: 显示名}，空名与没变化的条目跳过。 */
    public static Rewrite applyDisplayRenames(String text, Map<String, String> displayMap) {
        Map<String, String> plan = new LinkedHashMap<String, String>();
        if (displayMap != null) {
            for (Map.Entry<String, String> e : displayMap.entrySet()) {
                String old = e.getKey() == null ? "" : e.getKey().trim();
                String rep = e.getValue() == null ? "" : e.getValue().trim();
                if (!old.isEmpty() && !rep.isEmpty() && !old.equals(rep)) {
                    plan.put(old, rep);
                }
            }
        }
        return rewriteNames(text, plan);
    }

    /** 一次表注释改写的结局：新正文，以及有没有真的改动。 */
    public static final class CommentEdit {
        public String text = "";
        public boolean changed;
    }

    /**
     * 改一张表的表注释：把那句 CREATE TABLE 的表选项 COMMENT='…' 换成新内容。
     *
     * 找不到这张表的 CREATE TABLE、或那句压根没有列定义（LIKE / AS SELECT 写法没有
     * 表选项可挂）时 changed=false 并原样返回，由界面明说为什么没落盘，不静默吞掉
     * 一次操作。同名表被写两遍只改第一处，与元数据导出「同名表只按第一处」同一条规则。
     * 写法统一成 MySQL 的标准形：单引号包住、里面的单引号翻倍，原来用双引号的一起换过来；
     * 原本没有 COMMENT 的在列定义右括号后补一句（表选项不分先后）。
     * 清空注释写的是 COMMENT=''，读回来就是空串，不在脚本里留看不见的占位词。
     */
    public static CommentEdit setTableComment(String text, String tableName, String comment) {
        CommentEdit out = new CommentEdit();
        out.text = text;
        String name = trimIdentifier(tableName);
        if (name.isEmpty()) {
            return out;
        }
        String body = (comment == null ? "" : comment.trim());
        String literal = "'" + body.replace("'", "''") + "'";
        String maskedAll = maskComments(text);
        Matcher m = CREATE_TABLE.matcher(maskedAll);
        while (m.find()) {
            if (!name.equals(m.group(1))) {
                continue;
            }
            int semi = m.end() + firstTopLevel(maskedAll.substring(m.end()), ';');
            Matcher nx = CREATE_TABLE.matcher(maskedAll);
            boolean hasNext = nx.find(m.end());
            int limit = hasNext ? Math.min(semi, nx.start()) : semi;
            int openAt = maskedAll.indexOf('(', m.end());
            if (openAt < 0 || openAt >= limit) {
                return out;       // LIKE / AS SELECT：没有表选项这段可改
            }
            int close = matchParen(maskedAll, openAt);
            if (close < 0) {
                return out;
            }
            int stop = close + 1 + firstTopLevel(maskedAll.substring(close + 1), ';');
            Matcher m2 = CREATE_TABLE.matcher(maskedAll);
            if (m2.find(close + 1)) {
                stop = Math.min(stop, m2.start());
            }
            String options = text.substring(close + 1, stop);
            String optionsMasked = maskedAll.substring(close + 1, stop);
            Matcher cm = TABLE_COMMENT_OPT.matcher(optionsMasked);
            if (!cm.find()) {
                out.text = text.substring(0, close + 1) + " COMMENT=" + literal
                        + text.substring(close + 1);
                out.changed = true;
                return out;
            }
            int at = close + 1 + cm.start();
            int end = close + 1 + cm.end();
            // 整段重写而不是只换字面量：等号有无、单双引号、翻倍引号这些写法差异一次抹平，
            // 改完再读回来一定是刚输入的内容
            String next = text.substring(0, at) + "COMMENT=" + literal + text.substring(end);
            out.text = next;
            out.changed = !next.equals(text);
            return out;
        }
        return out;
    }

    private static String trimIdentifier(String name) {
        return name == null ? "" : name.trim().replace("`", "");
    }

    /** 只削两侧的空白与包边反引号：中间的反引号要留着，它决定这个名字合不合法。 */
    private static String trimIdent(String name) {
        String s = name == null ? "" : name.trim();
        if (s.length() >= 2 && s.charAt(0) == '`' && s.charAt(s.length() - 1) == '`') {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** 一列改写的结果：正文、是否真改了、没改成时的原因。与 Python 的三元组同义。 */
    public static final class ColumnEdit {
        public String text = "";
        public boolean changed;
        public String reason = "";
    }

    private static final Pattern COL_COMMENT_KW = Pattern.compile("(?i)\\bCOMMENT\\s+");
    private static final Pattern COL_TYPE = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_$]*(?:\\s*\\([^()]*\\))?)");
    private static final Pattern FIELD_BAD = Pattern.compile("[\\s`;\"'\\\\]");

    /** 约束行里第一个括号名单中的列名：PRIMARY KEY (a,b) 与 KEY `i` (`c`) 都取到列名。 */
    private static List<String> parenTokens(String maskedHead) {
        List<String> names = new ArrayList<String>();
        int open = maskedHead.indexOf('(');
        if (open < 0) {
            return names;
        }
        int close = matchParen(maskedHead, open);
        if (close < 0) {
            return names;
        }
        for (String token : maskedHead.substring(open + 1, close).split(",")) {
            String name = leadingName(token).name;
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * 列定义里 COMMENT 字面量的 {起, 止}（含关键字）；只有关键字没有字面量、
     * 或者根本没提到 COMMENT 时返回 null。掩码副本用来认关键字，取值走原文。
     */
    private static int[] columnCommentSpan(String maskedPiece, String piece) {
        Matcher m = COL_COMMENT_KW.matcher(maskedPiece);
        while (m.find()) {
            int i = m.end();
            if (i >= piece.length() || (piece.charAt(i) != '\'' && piece.charAt(i) != '"')) {
                continue;
            }
            char quote = piece.charAt(i);
            int j = i + 1;
            while (j < piece.length()) {
                if (piece.charAt(j) == '\\' && quote != '`') {
                    j += 2;
                    continue;
                }
                if (piece.charAt(j) == quote) {
                    if (j + 1 < piece.length() && piece.charAt(j + 1) == quote) {
                        j += 2;
                        continue;
                    }
                    return new int[]{m.start(), j + 1};
                }
                j++;
            }
        }
        return null;
    }

    /**
     * 改脚本里某一列的字段名 / 字段注释 / 数据类型，三个新值传 null 表示这一项不改。
     *
     * 只动那一行的那一段：字段名换标识符、类型换名字后面那个类型词（UNSIGNED、NOT NULL、
     * DEFAULT、AUTO_INCREMENT 原样留着），注释换 COMMENT 的字面量、原来没有就补在内容末尾。
     * 被主键或索引名单引用的字段拒绝改名——索引行写的还是旧名字，放过去就生成一句
     * 引用不存在列的 DDL，等建表时才炸比在这儿拒绝难查得多。
     * 同名表被写两遍只改第一处，与表注释、元数据导出「同名表只按第一处」是同一条规则。
     * 判定与 Python 侧的 edit_column 逐形状对撞（金标准 op "col-edit"）。
     */
    public static ColumnEdit editColumn(String text, String tableName, String field,
                                        String newField, String newComment, String newType) {
        ColumnEdit out = new ColumnEdit();
        out.text = text == null ? "" : text;
        String name = trimIdent(tableName);
        String old = trimIdent(field);
        if (name.isEmpty() || old.isEmpty()) {
            out.reason = "没给表名或字段名";
            return out;
        }
        boolean wantField = newField != null;
        boolean wantComment = newComment != null;
        boolean wantType = newType != null;
        if (!wantField && !wantComment && !wantType) {
            out.reason = "没有要改的内容";
            return out;
        }
        String nf = "";
        if (wantField) {
            nf = trimIdent(newField);
            if (nf.isEmpty()) {
                out.reason = "字段名不能为空";
                return out;
            }
            if (FIELD_BAD.matcher(nf).find()) {
                out.reason = "字段名不能含空格、引号、反引号或分号：" + nf;
                return out;
            }
            if (nf.length() > 64) {
                out.reason = "字段名超过 64 个字符：" + nf;
                return out;
            }
        }
        String nt = null;
        if (wantType) {
            nt = newType.trim();
            if (nt.isEmpty()) {
                out.reason = "数据类型不能为空";
                return out;
            }
            if (FIELD_BAD.matcher(nt.replace("(", "").replace(")", "")
                    .replace(",", "")).find()) {
                out.reason = "数据类型写法不对：" + nt;
                return out;
            }
        }
        String src = out.text;
        String masked = maskComments(src);
        Matcher head = CREATE_TABLE.matcher(masked);
        while (head.find()) {
            if (!name.equals(head.group(1))) {
                continue;
            }
            int open = masked.indexOf('(', head.end());
            if (open < 0) {
                out.reason = "那句 CREATE TABLE 没有列定义（LIKE / AS SELECT 写法）";
                return out;
            }
            int close = matchParen(masked, open);
            if (close < 0) {
                out.reason = "那句 CREATE TABLE 的括号配不上，无法定位列定义";
                return out;
            }
            String body = src.substring(open + 1, close);
            String bodyMasked = masked.substring(open + 1, close);
            List<String> pieces = splitTopLevel(body, bodyMasked);
            List<String> constraintNames = new ArrayList<String>();
            int start = -1;
            String raw = null;
            int nameEnd = -1;
            int at = 0;
            for (String piece : pieces) {
                if (!piece.trim().isEmpty()) {
                    String maskedPiece = maskComments(piece);
                    String headMasked = lstrip(maskedPiece);
                    if (CONSTRAINT_HEAD.matcher(headMasked).find()) {
                        constraintNames.addAll(parenTokens(headMasked));
                    } else {
                        NameAt na = leadingName(piece);
                        if (!na.name.isEmpty() && na.name.equalsIgnoreCase(old) && raw == null) {
                            start = at;
                            raw = piece;
                            nameEnd = na.end;
                        }
                    }
                }
                at += piece.length() + 1;
            }
            if (raw == null) {
                out.reason = "表「" + name + "」里没有字段「" + old + "」（可能被改写或删除了）";
                return out;
            }
            if (wantField && !nf.equalsIgnoreCase(old)) {
                for (String ref : constraintNames) {
                    if (ref.equalsIgnoreCase(old)) {
                        out.reason = "字段「" + old + "」被主键或索引列名单引用，改名会留下引用旧列的定义";
                        return out;
                    }
                }
            }
            // 三处替换按从右到左落笔，先动的段落不会挪后动的偏移
            List<int[]> spans = new ArrayList<int[]>();
            List<String> subs = new ArrayList<String>();
            int ws = raw.length() - lstrip(raw).length();
            int tailAt = nameEnd;
            String tail = raw.substring(nameEnd);
            if (wantComment) {
                String literal = "'" + newComment.trim().replace("'", "''") + "'";
                int[] span = columnCommentSpan(maskComments(raw), raw);
                if (span == null) {
                    int cut = rstrip(raw).length();     // 插在内容末尾，不落进尾部换行里
                    spans.add(new int[]{cut, cut});
                    subs.add(" COMMENT " + literal);
                } else {
                    spans.add(new int[]{span[0], span[1]});
                    subs.add("COMMENT " + literal);
                }
            }
            if (wantType) {
                Matcher tm = COL_TYPE.matcher(tail);
                if (!tm.find()) {
                    out.reason = "读不出字段「" + old + "」的类型，先别改";
                    return out;
                }
                spans.add(new int[]{tailAt + tm.start(1), tailAt + tm.end(1)});
                subs.add(nt);
            }
            if (wantField && !nf.equalsIgnoreCase(old)) {
                boolean quoted = raw.charAt(ws) == '`';
                spans.add(new int[]{ws, nameEnd});
                subs.add(quoted ? "`" + nf.replace("`", "``") + "`" : nf);
            }
            Integer[] order = new Integer[spans.size()];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            final List<int[]> ref = spans;
            java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return ref.get(b)[0] - ref.get(a)[0];
                }
            });
            String patched = raw;
            for (int idx : order) {
                int[] sp = spans.get(idx);
                patched = patched.substring(0, sp[0]) + subs.get(idx) + patched.substring(sp[1]);
            }
            if (patched.equals(raw)) {
                out.reason = "填回去的和原来一样，脚本未改";
                return out;
            }
            int from = open + 1 + start;
            out.text = src.substring(0, from) + patched + src.substring(from + raw.length());
            out.changed = true;
            return out;
        }
        out.reason = "脚本里找不到表「" + name + "」的 CREATE TABLE";
        return out;
    }

    /**
     * 按分号切语句，正确跳过 '...' / "..." 字符串（支持反斜杠与双写引号转义）、
     * `...` 反引号标识符、-- 与 # 行注释、块注释。不支持 DELIMITER 存储过程脚本——
     * 那是整库 dump 的写法，那条通道走 splitDumpStatements。
     */
    public static List<String> splitStatements(String text) {
        return splitStatements(text, false);
    }

    /** 按整库 dump 的规则切句：在分号切分之上认 DELIMITER 指令（客户端指令不发给服务端）。 */
    public static List<String> splitDumpStatements(String text) {
        return splitStatements(text, true);
    }

    /** 行首的 DELIMITER 指令；只认整行（后面除空白外没有别的内容）。 */
    private static final Pattern DELIMITER_DIRECTIVE = Pattern.compile(
            "[ \\t]*DELIMITER[ \\t]+(\\S+)[ \\t]*(?:\\r?\\n|$)", Pattern.CASE_INSENSITIVE);

    /** 这一行是不是 DELIMITER 指令：先做 9 个字符的比较，避免每行都拼一次缓冲。 */
    private static boolean looksLikeDelimiter(String text, int at) {
        int n = text.length();
        int i = at;
        while (i < n && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return text.regionMatches(true, i, "DELIMITER", 0, 9);
    }

    private static List<String> splitStatements(String text, boolean withDelimiter) {
        List<String> stmts = new ArrayList<String>();
        StringBuilder buf = new StringBuilder();
        int i = 0;
        int n = text.length();
        String delim = ";";
        while (i < n) {
            if (withDelimiter && (i == 0 || text.charAt(i - 1) == '\n')
                    && looksLikeDelimiter(text, i)
                    && !hasExecutableContent(buf.toString())) {
                Matcher m = DELIMITER_DIRECTIVE.matcher(text);
                m.region(i, n);
                if (m.lookingAt()) {
                    delim = m.group(1);
                    buf.setLength(0);
                    i = m.end();
                    continue;
                }
            }
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
            if (text.startsWith(delim, i)) {
                String s = Names.pyStrip(buf.toString());
                if (!s.isEmpty()) {
                    stmts.add(s);
                }
                buf.setLength(0);
                i += delim.length();
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

    /**
     * 这一句里除了注释和空白还剩东西吗（两条执行通道共用这一份判据）。
     *
     * `splitStatements` 会把 `-- 只有注释` 这种整段是注释的片段也切成一条（它只按分号切，
     * 不管内容），而「SQL 执行器」要对用户说"只写注释也不算"——判据就得在这儿补上，
     * 否则会出现"切出来一条、执行时又说没得执行"，或者把空注释当成正经一句报"已提交 1 条"。
     * 走的是与切句同一套词法（引号 / 反引号 / -- 与 # 行注释 / 块注释）：
     * 字符串里的 `--` 不算注释，所以 `SELECT '-- x'` 仍然是一句真要执行的语句。
     *
     * 版本注释 `/*!NNNNN ...*&#47;` 要算：正文是服务端真会执行的语句，不是给人看的注释。
     * 导出工具把 SET NAMES、SET TIME_ZONE、SET FOREIGN_KEY_CHECKS=0 全写在这类语句里，
     * 判成注释就等于把这些设置悄悄丢掉——现场那份 MariaDB 整库 dump 走普通通道时
     * 1382 条只剩 679 条，外键检查因此一直开着，删被引用的父表就是 MySQL 3730。
     * `/*M!...*&#47;`（MariaDB 客户端沙箱标记）与 `/*!999999\- ...`（发给服务端是空查询）
     * 只在客户端生效，仍按没有内容处理。与 core/sql_script.py 同语义。
     */
    public static boolean hasExecutableContent(String stmt) {
        int i = 0;
        int n = stmt.length();
        while (i < n) {
            char ch = stmt.charAt(i);
            char nxt = i + 1 < n ? stmt.charAt(i + 1) : 0;
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                char quote = ch;
                i++;
                while (i < n) {
                    char c = stmt.charAt(i);
                    if (c == '\\' && quote != '`') {
                        i += 2;
                        continue;
                    }
                    if (c == quote) {
                        if (i + 1 < n && stmt.charAt(i + 1) == quote) {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                return true;        // 引号里有任何内容都是一句真要执行的语句
            }
            if ((ch == '-' && nxt == '-') || ch == '#') {
                int j = stmt.indexOf('\n', i);
                i = j < 0 ? n : j;
                continue;
            }
            if (ch == '/' && nxt == '*') {
                int j = stmt.indexOf("*/", i + 2);
                if (stmt.startsWith("/*!", i) && !stmt.startsWith("/*M!", i)) {
                    String body = stmt.substring(i + 3, j < 0 ? n : j)
                            .replaceFirst("^\\d*", "").trim();
                    if (!body.isEmpty() && !body.startsWith("\\")) {
                        return true;
                    }
                }
                i = j < 0 ? n : j + 2;
                continue;
            }
            return true;            // 既不是空白也不是注释 → 有可执行内容
        }
        return false;
    }

    /** 切句再剔掉"整句只剩注释"的片段——执行器只认真正要执行的那几句。 */
    public static List<String> executableStatements(String text) {
        List<String> out = new ArrayList<String>();
        for (String s : splitStatements(text)) {
            if (hasExecutableContent(s)) {
                out.add(s);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 整库 dump：识别与按 dump 方式执行需要的分类（与 core/sql_script.py 同口径）
    // ------------------------------------------------------------------

    /** mysqldump / MariaDB dump / phpMyAdmin 导出都会带这几句里的一句。 */
    private static final String[] DUMP_HEAD_MARKERS = {
            "-- mysql dump", "-- mariadb dump", "-- mysql shell dump",
            "-- phpmyadmin sql dump", "enable the sandbox mode",
    };
    private static final String DUMP_TAIL_MARKER = "-- dump completed";
    private static final int DUMP_HEAD_CHARS = 8192;
    private static final int DUMP_TAIL_CHARS = 4096;

    private static final Pattern DUMP_NAV = Pattern.compile(
            "^\\s*DROP\\s+DATABASE\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DUMP_SWITCH = Pattern.compile(
            "^\\s*USE\\s+([^\\s;]+)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DUMP_VERSION_COMMENT = Pattern.compile(
            "/\\*!\\d*\\s*(.*?)\\s*\\*/", Pattern.DOTALL);
    private static final Pattern DUMP_BLOCK_COMMENT = Pattern.compile(
            "/\\*[^*]*(?:\\*(?!/)[^*]*)*\\*/", Pattern.DOTALL);
    private static final Pattern DUMP_LINE_COMMENT = Pattern.compile(
            "(?m)^[ \\t]*(?:--|#).*$");

    /**
     * 语句开头那截把注释摊平后的样子，只用来认第一个关键字。
     *
     * 只截前 300 字符：认的是语句开头，正文里出现的 `--` 或块注释不该参与判定。
     * 版本注释 `/*!40101 SET ... *&#47;` 服务端真会执行，把正文摊平留下；普通块注释与
     * MariaDB 客户端沙箱标记 `/*M!...*&#47;` 整块去掉。与 Python 的 _dump_statement_head 同序。
     */
    private static String dumpStatementHead(String stmt) {
        String s = stmt == null ? "" : stmt;
        s = s.length() > 300 ? s.substring(0, 300) : s;
        s = DUMP_LINE_COMMENT.matcher(s).replaceAll("");
        Matcher v = DUMP_VERSION_COMMENT.matcher(s);
        StringBuffer flat = new StringBuffer();
        while (v.find()) {
            v.appendReplacement(flat, Matcher.quoteReplacement(v.group(1)));
        }
        v.appendTail(flat);
        s = DUMP_BLOCK_COMMENT.matcher(flat.toString()).replaceAll(" ");
        return s.replaceAll("\\s+", " ").trim();
    }

    /** 这份脚本是不是整库 dump：看抬头 / 结尾那几句导出工具才会写的话。 */
    public static boolean isDumpScript(String text) {
        if (text == null) {
            return false;
        }
        String head = text.substring(0, Math.min(text.length(), DUMP_HEAD_CHARS))
                .toLowerCase(Locale.ROOT);
        for (String marker : DUMP_HEAD_MARKERS) {
            if (head.contains(marker)) {
                return true;
            }
        }
        String tail = text.substring(Math.max(0, text.length() - DUMP_TAIL_CHARS))
                .toLowerCase(Locale.ROOT);
        return tail.contains(DUMP_TAIL_MARKER);
    }

    /** 整库 dump 的可执行语句：认 DELIMITER，并保留 `/*!...*&#47;` 版本注释语句。 */
    /**
     * 内容判据与普通通道共用 hasExecutableContent：两条通道只在"怎么切句"和"库级语句
     * 跳不跳"上不同，什么算一句必须一样——分开写过一次就分叉过一次，普通通道把版本注释
     * 当注释丢掉那回（MySQL 3730）就是这么来的。
     */
    public static List<String> dumpStatements(String text) {
        List<String> out = new ArrayList<String>();
        for (String s : splitDumpStatements(text)) {
            if (hasExecutableContent(s)) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 这句是 dump 里不该执行的语句吗，返回关键字（空串表示照常执行）。
     *
     * 只有删库语句被拦下：CREATE DATABASE 与 USE 是 dump 在说"这些表建在哪个库里"，
     * 跳过它们等于把用户要导的那个库抹掉，表会散进界面随手选中的库；而 DROP DATABASE
     * 执行一次就把目标库整个删掉，风险不对等，仍然只跳过、不执行。
     * 语句开头常带着 `--` 注释块（真 dump 的 "Current Database" 那段就在建库语句前面），
     * `/*!40000 DROP DATABASE ... *&#47;` 也要认，所以先把注释摊平再认第一个关键字。
     */
    public static String dumpSkipReason(String stmt) {
        return DUMP_NAV.matcher(dumpStatementHead(stmt)).lookingAt() ? "DROP DATABASE" : "";
    }

    /**
     * 这句是 dump 里的切库语句（`USE ...`）吗，返回它切过去的库名（不是切库返回空串）。
     *
     * 建库不改会话库，只有 USE 改；日志与收尾汇总要说的是"后续语句落在哪个库"。
     * 名字两边的反引号与引号都剥掉，库名里带 `-` 或 `.` 也照原样取。
     */
    public static String dumpDatabaseSwitch(String stmt) {
        Matcher m = DUMP_SWITCH.matcher(dumpStatementHead(stmt));
        if (!m.lookingAt()) {
            return "";
        }
        return m.group(1).replaceAll("^[`\"]+", "").replaceAll("[`\"]+$", "");
    }

    // ------------------------------------------------------------------
    // CREATE TABLE 正文解析：给「SQL 脚本改名」页那两颗生成按钮当数据源
    // 与 core/sql_script.py 的 parse_create_tables 逐格对撞（金标准第 8 步）
    // ------------------------------------------------------------------

    /** 表级约束行的开头，识别出来只为跳过，别把 KEY 的名字当字段名。 */
    private static final Pattern CONSTRAINT_HEAD = Pattern.compile(
            "^(PRIMARY\\s+KEY|UNIQUE\\s+(?:KEY|INDEX)?|FULLTEXT\\s+(?:KEY|INDEX)?"
          + "|SPATIAL\\s+(?:KEY|INDEX)?|FOREIGN\\s+KEY|CONSTRAINT|CHECK|PERIOD|KEY|INDEX)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern TABLE_COMMENT_OPT = Pattern.compile(
            "\\bCOMMENT\\b\\s*=?\\s*('(?:[^']|'')*'|\"(?:[^\"]|\"\")*\")",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern TYPE_HEAD = Pattern.compile("^([A-Za-z][A-Za-z0-9_]*)(\\()?");
    private static final Pattern NUM_ONLY = Pattern.compile("^\\d+$");
    private static final Pattern NUM_PAIR = Pattern.compile("^\\d+\\s*,\\s*\\d+$");
    private static final Pattern COL_COMMENT_MARK = Pattern.compile(
            "\\bCOMMENT\\b\\s*=?", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_PK = Pattern.compile(
            "\\bPRIMARY\\s+KEY\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_NULL = Pattern.compile(
            "\\bNOT\\s+NULL\\b", Pattern.CASE_INSENSITIVE);

    private static final String DUP_NOTE = "表名 %s 在本脚本里被定义了多次，只按第一处的列定义导出";

    /** 一条 CREATE TABLE 解析出的表定义，列用 Infer.Column，MetaExport 直接就能吃。 */
    public static final class ParsedTable {
        public String tableName = "";
        public String tableComment = "";
        public final List<Infer.Column> columns = new ArrayList<Infer.Column>();
        public final List<String> notes = new ArrayList<String>();
    }

    /**
     * 把脚本里的 CREATE TABLE 解析成表定义，按首次出现顺序返回。
     *
     * 表名取的是**当前文本里的名字**，所以页面上先改前缀再生成，产物用的就是新表名。
     * 只解析到能填进元数据 Sheet 的程度：列名、类型与长度、列注释、主键、可空。
     * DEFAULT / AUTO_INCREMENT / 生成列规则不进交付件；表达不了的（UNSIGNED、ENUM 等）
     * 近似成能装下的形状，并在该表的 notes 里逐列点名，不静默改语义。
     */
    public static List<ParsedTable> parseCreateTables(String text) {
        String maskedAll = maskComments(text);
        List<ParsedTable> tables = new ArrayList<ParsedTable>();
        Map<String, ParsedTable> seen = new LinkedHashMap<String, ParsedTable>();
        Matcher m = CREATE_TABLE.matcher(maskedAll);
        while (m.find()) {
            String name = m.group(1);
            // 列定义的 '(' 必须落在本条语句里：往后找会把下一条 CREATE TABLE 的括号当成自己的，
            // LIKE / AS SELECT 这种没有列定义的写法就会带着别人的列被导出
            // （界限 = 顶层分号或下一条 CREATE TABLE，取更早的那个）
            int semi = m.end() + firstTopLevel(maskedAll.substring(m.end()), ';');
            Matcher nx = CREATE_TABLE.matcher(maskedAll);
            int limit = nx.find(m.end()) ? Math.min(semi, nx.start()) : semi;
            int openAt = maskedAll.indexOf('(', m.end());
            if (openAt < 0 || openAt >= limit) {
                if (seen.containsKey(name)) {
                    continue;   // 本来就没有列可导，重复出现也不必再说明
                }
                ParsedTable t = new ParsedTable();
                t.tableName = name;
                t.notes.add("这条 CREATE TABLE 后面没有列定义（LIKE 或 AS SELECT 写法），"
                        + "无法生成元数据");
                seen.put(name, t);
                tables.add(t);
                continue;
            }
            int close = matchParen(maskedAll, openAt);
            if (close < 0) {
                continue;
            }
            String body = text.substring(openAt + 1, close);
            String bodyMasked = maskedAll.substring(openAt + 1, close);
            int stop = close + 1 + firstTopLevel(maskedAll.substring(close + 1), ';');
            Matcher m2 = CREATE_TABLE.matcher(maskedAll);
            if (m2.find(close + 1)) {
                stop = Math.min(stop, m2.start());
            }
            String options = text.substring(close + 1, stop);
            String optionsMasked = maskedAll.substring(close + 1, stop);
            String tableComment = "";
            Matcher cm = TABLE_COMMENT_OPT.matcher(optionsMasked);
            if (cm.find()) {
                String raw = options.substring(cm.start(1), cm.end(1));
                tableComment = raw.substring(1, raw.length() - 1)
                        .replace(raw.substring(0, 1) + raw.substring(0, 1), raw.substring(0, 1));
            }

            List<String> pkFromConstraint = new ArrayList<String>();
            List<Infer.Column> cols = new ArrayList<Infer.Column>();
            List<String> notes = new ArrayList<String>();
            for (String piece : splitTopLevel(body, bodyMasked)) {
                if (piece.trim().isEmpty()) {
                    continue;
                }
                String pieceMasked = maskComments(piece);
                String headMasked = lstrip(pieceMasked);
                if (CONSTRAINT_HEAD.matcher(headMasked).find()) {
                    if (headMasked.toUpperCase().startsWith("PRIMARY KEY")) {
                        int o = headMasked.indexOf('(');
                        int c = o < 0 ? -1 : matchParen(headMasked, o);
                        if (c > o) {
                            for (String token : headMasked.substring(o + 1, c).split(",")) {
                                String col = leadingName(token).name;
                                if (!col.isEmpty()) {
                                    pkFromConstraint.add(col);
                                }
                            }
                        }
                    }
                    continue;
                }
                NameAt ln = leadingName(piece);
                String field = ln.name;
                if (field.isEmpty()) {
                    continue;
                }
                String tail = piece.substring(ln.end);
                String maskedTail = pieceMasked.substring(ln.end);
                String[] cc = columnComment(tail, maskedTail);
                String comment = cc[0];
                tail = cc[1];
                maskedTail = maskComments(tail);
                String[] nt = normalizeType(tail);
                Infer.Column col = new Infer.Column();
                col.field = field;
                col.comment = comment;
                col.type = nt[1] == null ? nt[0] : nt[0] + "(" + nt[1] + ")";
                col.primaryKey = INLINE_PK.matcher(maskedTail).find();
                col.nullable = Boolean.valueOf(!NOT_NULL.matcher(maskedTail).find());
                if (!nt[3].isEmpty()) {
                    notes.add("列 " + field + " 的 " + nt[2] + " 装不进元数据 Sheet，按 "
                            + col.type + " 导出（丢了 " + nt[3] + "）");
                }
                cols.add(col);
            }
            if (!pkFromConstraint.isEmpty()) {
                for (Infer.Column col : cols) {
                    for (String pk : pkFromConstraint) {
                        if (col.field != null && col.field.equalsIgnoreCase(pk)) {
                            col.primaryKey = true;
                        }
                    }
                }
            }
            for (Infer.Column col : cols) {
                if (col.primaryKey) {
                    col.nullable = Boolean.FALSE;   // 主键建表时一定是 NOT NULL
                }
            }
            ParsedTable t = new ParsedTable();
            t.tableName = name;
            t.tableComment = tableComment;
            t.columns.addAll(cols);
            t.notes.addAll(notes);
            if (seen.containsKey(name)) {
                // 第二处的定义不导出，但也不静默：说明挂在第一处那条上
                seen.get(name).notes.add(DUP_NOTE.replace("%s", name));
                continue;
            }
            seen.put(name, t);
            tables.add(t);
        }
        return tables;
    }

    /** 返回与 text 等长、注释字符换成空格的副本；引号里的内容原样留着，索引一一对应。 */
    static String maskComments(String text) {
        char[] out = text.toCharArray();
        int i = 0, n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            char nxt = i + 1 < n ? text.charAt(i + 1) : 0;
            if (ch == '\'' || ch == '"' || ch == '`') {
                i = skipQuoted(text, i, ch);
                continue;
            }
            if ((ch == '-' && nxt == '-') || ch == '#') {
                int j = text.indexOf('\n', i);
                j = j < 0 ? n : j;
                for (int k = i; k < j; k++) {
                    if (!Character.isWhitespace(out[k])) {
                        out[k] = ' ';
                    }
                }
                i = j;
                continue;
            }
            if (ch == '/' && nxt == '*') {
                int j = text.indexOf("*/", i + 2);
                j = j < 0 ? n : j + 2;
                for (int k = i; k < j; k++) {
                    if (!Character.isWhitespace(out[k])) {
                        out[k] = ' ';
                    }
                }
                i = j;
                continue;
            }
            i++;
        }
        return new String(out);
    }

    /** 从 start 的引号字符跳到引号结束之后的位置（双写与反斜杠转义都认）。 */
    private static int skipQuoted(String text, int start, char quote) {
        int i = start + 1, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\' && quote != '`' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < n && text.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    /** masked[start] 是 '('，返回配对 ')' 的下标；配不上返回 -1。 */
    static int matchParen(String masked, int start) {
        int depth = 0;
        for (int i = start; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 按顶层逗号切开列定义与约束行；括号内的逗号不算。 */
    static List<String> splitTopLevel(String body, String masked) {
        List<String> out = new ArrayList<String>();
        int depth = 0, last = 0;
        for (int i = 0; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(body.substring(last, i));
                last = i + 1;
            }
        }
        String tail = body.substring(last);
        if (!tail.trim().isEmpty()) {
            out.add(tail);
        }
        return out;
    }

    /** masked 里第一个不在引号/括号/注释中的 ch 的下标；找不到返回 masked.length()。 */
    static int firstTopLevel(String masked, char ch) {
        int depth = 0, i = 0, n = masked.length();
        while (i < n) {
            char c = masked.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(masked, i, c);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ch && depth == 0) {
                return i;
            }
            i++;
        }
        return n;
    }

    /** 一行定义开头的名字与它在原文里的结束下标（尾巴要从这个下标往后切）。 */
    static final class NameAt {
        final String name;
        final int end;

        NameAt(String name, int end) {
            this.name = name;
            this.end = end;
        }
    }

    /** 取一行定义开头的名字，返回 {名字, 名字在原文里的结束下标}。 */
    static NameAt leadingName(String piece) {
        String s = lstrip(piece);
        int off = piece.length() - s.length();
        if (s.startsWith("`")) {
            int end = s.indexOf('`', 1);
            while (end >= 0 && end + 1 < s.length() && s.charAt(end + 1) == '`') {
                end = s.indexOf('`', end + 2);
            }
            if (end < 0) {
                return new NameAt("", off);
            }
            return new NameAt(s.substring(1, end).replace("``", "`"), off + end + 1);
        }
        Matcher m = Pattern.compile("^([A-Za-z0-9_$]+)").matcher(s);
        if (!m.find()) {
            return new NameAt("", off);
        }
        return new NameAt(m.group(1), off + m.end());
    }

    /**
     * DDL 里的类型串 -> {数据类型, 长度, 丢了什么}。
     *
     * 元数据 Sheet 的读回端只认「一个关键字 + 纯数字长度」，所以 `int unsigned` 只能落成
     * INT + 修饰丢掉的说明；ENUM/SET 这类括号里不是数字的，装进去会产出非法 DDL
     * （只有 ENUM 没有取值），按 TEXT 导出并点名原类型。
     */
    /** 返回值是 {数据类型, 长度, 原样类型, 丢了什么}，四项与 Python 侧同序同义。 */
    static String[] normalizeType(String raw) {
        String s = (raw == null ? "" : raw).trim();
        Matcher m = TYPE_HEAD.matcher(s);
        if (!m.find()) {
            return new String[]{"TEXT", null, s, "无法识别的类型"};
        }
        String base = m.group(1).toUpperCase();
        String length = null;
        String src = base;
        String rest = s;
        if (m.group(2) != null && !m.group(2).isEmpty()) {
            String masked = maskComments(s);
            int openAt = masked.indexOf('(', m.start(2));
            int close = matchParen(masked, openAt);
            if (close < 0) {
                return new String[]{"TEXT", null, s, "类型括号没配对"};
            }
            String args = s.substring(openAt + 1, close).trim();
            rest = s.substring(close + 1).trim();
            src = base + "(" + args + ")";
            if (NUM_ONLY.matcher(args).find()) {
                length = args;
            } else if (NUM_PAIR.matcher(args).find()) {
                String[] parts = args.split(",");
                length = parts[0].trim() + "," + parts[1].trim();
            } else {
                return new String[]{"TEXT", null, src, "取值列表 " + args};
            }
        }
        StringBuilder dropped = new StringBuilder();
        String tail = rest.toUpperCase();
        String[] kws = {"UNSIGNED", "SIGNED", "ZEROFILL"};
        for (int i = 0; i < kws.length; i++) {
            if (Pattern.compile("\\b" + kws[i] + "\\b").matcher(tail).find()) {
                if (dropped.length() > 0) {
                    dropped.append('、');
                }
                dropped.append(kws[i]);
            }
        }
        if (Pattern.compile("\\bCHARACTER\\s+SET\\b").matcher(tail).find()
                || Pattern.compile("\\bCOLLATE\\b").matcher(tail).find()) {
            if (dropped.length() > 0) {
                dropped.append('、');
            }
            dropped.append("字符集/排序规则");
        }
        return new String[]{base, length, src, dropped.toString()};
    }

    /** 取列注释，返回 {注释, 去掉注释后的原文尾巴}；maskedTail 与 tail 等长同索引。 */
    static String[] columnComment(String tail, String maskedTail) {
        Matcher m = COL_COMMENT_MARK.matcher(maskedTail);
        if (!m.find()) {
            return new String[]{"", tail};
        }
        String rest = lstrip(tail.substring(m.end()));
        if (rest.isEmpty()) {
            return new String[]{"", tail.substring(0, m.start())};
        }
        char quote = rest.charAt(0);
        if (quote != '\'' && quote != '"') {
            return new String[]{"", tail.substring(0, m.start())};
        }
        String maskedRest = maskComments(rest);
        int end = maskedRest.indexOf(quote, 1);
        while (end >= 0 && end + 1 < maskedRest.length() && maskedRest.charAt(end + 1) == quote) {
            end = maskedRest.indexOf(quote, end + 2);
        }
        if (end < 0) {
            return new String[]{"", tail.substring(0, m.start())};
        }
        String value = rest.substring(1, end).replace("" + quote + quote, "" + quote);
        return new String[]{value, tail.substring(0, m.start())};
    }

    private static String lstrip(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    /** 尾部空白去掉后的样子，只要长度用（内容末尾的下标）。 */
    private static String rstrip(String s) {
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) {
            i--;
        }
        return s.substring(0, i);
    }

}
