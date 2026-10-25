package com.excel2mysql.verify;

import com.excel2mysql.core.Ddl;
import com.excel2mysql.core.Infer;
import com.excel2mysql.core.Names;
import com.excel2mysql.core.Prefix;
import com.excel2mysql.core.SqlRunner;
import com.excel2mysql.core.SqlScript;
import com.excel2mysql.core.TableOps;
import com.excel2mysql.pinyin.PinyinConverter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 撞 SQL 脚本文本线、DDL 生成与按类型转值。
 * 表定义从 excel 金标准里取（那张已经逐列对撞过），这里不重复读 Excel。
 */
public final class SqlDiffer {

    private static int checked;
    private static int bad;
    private static final List<String> shown = new ArrayList<String>();
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static void main(String[] args) throws IOException {
        checkScriptEncoding();
        String sqlGolden = args.length > 0 ? args[0] : "java/verify/golden/sql.jsonl.gz";
        String excelGolden = args.length > 1 ? args[1] : "java/verify/golden/excel.jsonl.gz";
        Map<String, TableOps.Table> byFile = loadTables(excelGolden);
        Names dummyNames = new Names(PinyinConverter.loadFromResources());

        BufferedReader r = open(sqlGolden);
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            Map<String, Object> rec = MiniJson.parseObject(line);
            String op = (String) rec.get("op");
            op = op == null ? "extract" : op;
            String in = (String) rec.get("in");
            if ("extract".equals(op)) {
                checked++;
                boolean same = eqList(mapNames(SqlScript.extractCreateTables(in)),
                        (List<Object>) rec.get("extract"));
                report("extract", in, same, null, null);
            } else if ("split".equals(op)) {
                checked++;
                List<String> got = SqlScript.splitStatements(in);
                List<Object> want = (List<Object>) rec.get("split");
                report("split", in, got.equals(want), join(got), join(want));
            } else if ("ddl-parse".equals(op)) {
                // 两条线各一份 CREATE TABLE 解析器：把结果拍成一行一格逐行对，
                // 类型、注释、主键、可空、近似说明哪一格不同都会露出来
                checked++;
                List<String> got = renderParsed(SqlScript.parseCreateTables(in));
                List<Object> want = (List<Object>) rec.get("out");
                report("ddl-parse", in, got.equals(want), join(got), join(want));
            } else if ("table-render".equals(op)) {
                // 结果集表格：两条线各写一份渲染器，宽度、边框、截断、NULL
                // 任一处对不上都会在某一行的某一格露出来
                checked++;
                List<String> cols = (List<String>) rec.get("columns");
                List<Object> rawRows = (List<Object>) rec.get("rows");
                List<List<String>> rows = new java.util.ArrayList<List<String>>();
                if (rawRows != null) {
                    for (Object o : rawRows) {
                        rows.add((List<String>) o);
                    }
                }
                List<String> got = com.excel2mysql.core.SqlExecute.renderTable(cols, rows);
                List<Object> want = (List<Object>) rec.get("out");
                report("table-render", in, got.equals(want), join(got), join(want));
            } else if ("prefix".equals(op) || "head".equals(op)) {
                checked++;
                String arg = (String) rec.get("arg");
                SqlScript.Rewrite got = "prefix".equals(op)
                        ? SqlScript.applyPrefix(in, arg) : SqlScript.replaceFirstSegment(in, arg);
                String want = (String) rec.get("out");
                boolean same = got.text.equals(want);
                List<Object> wantSkipped = (List<Object>) rec.get("skipped");
                List<String> gotSkipped = new ArrayList<String>(got.skipped);
                if (gotSkipped.size() != wantSkipped.size()) {
                    same = false;
                } else {
                    for (int i = 0; i < gotSkipped.size(); i++) {
                        if (!gotSkipped.get(i).equals(wantSkipped.get(i))) {
                            same = false;
                            break;
                        }
                    }
                }
                report(op + "(" + arg + ")", in, same, abbrev(got.text) + " skipped=" + got.skipped,
                        abbrev(want) + " skipped=" + wantSkipped);
            } else if ("comment".equals(op)) {
                checked++;
                String table = (String) rec.get("table");
                String cmt = (String) rec.get("comment");
                SqlScript.CommentEdit got = SqlScript.setTableComment(in, table, cmt);
                String want = (String) rec.get("out");
                int wantChanged = ((Number) rec.get("changed")).intValue();
                boolean same = got.text.equals(want) && (got.changed ? 1 : 0) == wantChanged;
                report("comment(" + table + ")", in, same,
                        abbrev(got.text) + " changed=" + (got.changed ? 1 : 0),
                        abbrev(want) + " changed=" + wantChanged);
            } else if ("col-edit".equals(op)) {
                // 单列改写：字段名 / 类型 / 注释三段各自定位替换，两条线各写一份
                // 「找那一行、换那一段」，落点错一处就会写出跑不了的脚本，
                // 所以连拒绝理由的文案一起逐字符对
                checked++;
                String table = (String) rec.get("table");
                String fld = (String) rec.get("field");
                SqlScript.ColumnEdit got = SqlScript.editColumn(in, table, fld,
                        (String) rec.get("new_field"), (String) rec.get("new_comment"),
                        (String) rec.get("new_type"));
                String want = (String) rec.get("out");
                int wantChanged = ((Number) rec.get("changed")).intValue();
                String wantReason = (String) rec.get("reason");
                boolean same = got.text.equals(want)
                        && (got.changed ? 1 : 0) == wantChanged
                        && got.reason.equals(wantReason);
                report("col-edit(" + table + "." + fld + ")", in, same,
                        abbrev(got.text) + " changed=" + (got.changed ? 1 : 0)
                                + " reason=" + got.reason,
                        abbrev(want) + " changed=" + wantChanged + " reason=" + wantReason);
            } else if ("clean".equals(op)) {
                checked++;
                String got = Prefix.clean(in);
                boolean same = got.equals(rec.get("out"))
                        && Prefix.isValid(got) == Boolean.TRUE.equals(rec.get("valid"));
                report("clean", in, same, got, String.valueOf(rec.get("out")));
            } else if ("swap".equals(op)) {
                checked++;
                Object got = Prefix.swapFirstSegment(in, "ch");
                Object want = rec.get("out");
                report("swap", in, eq(got, want), String.valueOf(got), String.valueOf(want));
            } else if ("ddl".equals(op)) {
                checked++;
                TableOps.Table t = byFile.get(in);
                if (t == null) {
                    report("ddl", in, false, "缺表定义", "");
                    continue;
                }
                String got = Ddl.buildDdl(t.tableName, t.tableComment, t.columns,
                        Boolean.TRUE.equals(rec.get("drop")));
                String want = (String) rec.get("out");
                report("ddl", in, got.equals(want), abbrev(diffFirst(got, want)), abbrev(want));
            } else if ("insert".equals(op)) {
                checked++;
                TableOps.Table t = byFile.get(in);
                String got = Ddl.buildInsertSql(t.tableName, t.columns)
                        .replace("?", "%s");   // Java 用 JDBC 的 ?，Python 用 pymysql 的 %s
                report("insert", in, got.equals(rec.get("out")), abbrev(got),
                        abbrev(String.valueOf(rec.get("out"))));
            } else if ("prefix-def".equals(op) || "head-def".equals(op)) {
                checked++;
                TableOps.Table t = byFile.get(in);
                List<TableOps.Table> list = new ArrayList<TableOps.Table>();
                list.add(t.copy());
                if ("prefix-def".equals(op)) {
                    for (int i = 0; i < 3; i++) {
                        TableOps.applyPrefix(list, (String) rec.get("arg"));
                    }
                } else {
                    TableOps.replaceFirstSegment(list, "chg");
                }
                List<String> got = TableOps.namesOf(list);
                List<Object> want = (List<Object>) rec.get("out");
                report(op, in, got.equals(want), got.toString(), want.toString());
            } else if ("convert".equals(op)) {
                checked++;
                Object v = decodeValue((List<Object>) rec.get("value"));
                String typ = (String) rec.get("type");
                Ddl.Converted got = Ddl.convert(v, typ);
                List<Object> wantOut = (List<Object>) rec.get("out");
                String wantErr = (String) rec.get("error");
                List<String> gotOut = encodeValue(got.value);
                boolean same = gotOut.get(0).equals(wantOut.get(0))
                        && gotOut.get(1).equals(wantOut.get(1))
                        && eq(got.error, wantErr);
                if (!same) {
                    checked++;
                    report("convert", typ + " <- " + wantOut, false,
                            gotOut + " err=" + got.error, wantOut + " err=" + wantErr);
                } else {
                    report("convert", typ, true, null, null);
                }
            } else {
                System.out.println("! [WARN] SQL-DIFF | 未识别的 op：" + op);
            }
        }
        r.close();
        System.out.println("- [INFO] SQL-DIFF | 比对 " + checked + " 项 | 不一致 " + bad + " 项");
        for (String s : shown) {
            System.out.println(s);
        }
        if (bad > 0) {
            System.out.println("x [ERROR] SQL-DIFF | 编码判定、SQL 脚本线或 DDL 线与 Python 不等价");
            System.exit(1);
        }
        System.out.println("+ [OK] SQL-DIFF | 编码判定、表名提取、语句切分、前缀改写、DDL 生成、按类型转值逐条一致");
    }

    /**
     * 真实 dump 的编码场景：UTF-8 正文里原样夹着二进制字段，严格 UTF-8 到坏字节处会失败。
     * 早先这里整份回落 GBK，表注释与字段注释全变成「瀵嗛挜」；用合成夹具把口径钉住。
     */
    private static void checkScriptEncoding() throws IOException {
        String chinese = "密钥";
        byte[] head = ("-- 中文注释\nCREATE TABLE `enc_mixed` (`id` INT COMMENT '"
                + chinese + "');\n").getBytes(UTF8);
        // 既不是合法 UTF-8、也不是合法 GBK：模拟 dump 里未转义的二进制值
        byte[] blob = new byte[] {(byte) 0xCE, 0x4E, 0x5E, 0x5C, 0x30,
                0x00, 0x02, (byte) 0xFF, '\n'};
        byte[] mixed = new byte[head.length + blob.length];
        System.arraycopy(head, 0, mixed, 0, head.length);
        System.arraycopy(blob, 0, mixed, head.length, blob.length);
        String got = readTempScript(mixed);
        checked++;
        report("encoding-mixed", "UTF-8 正文 + 二进制字段",
                got.contains(chinese) && !got.contains("瀵嗛挜"), abbrev(got), chinese);

        String gbkText = "-- 记事本 ANSI\nCREATE TABLE `enc_gbk` (`id` INT COMMENT '编号');\n";
        String gotGbk = readTempScript(gbkText.getBytes(Charset.forName("GBK")));
        checked++;
        report("encoding-gbk", "GBK 脚本", gotGbk.contains("编号"), abbrev(gotGbk), "编号");

        String withFffd = "-- 原正文里就有替换字符 \uFFFD\n"
                + "CREATE TABLE `enc_fffd` (`id` INT);\n";
        String gotFffd = readTempScript(withFffd.getBytes(UTF8));
        checked++;
        report("encoding-fffd", "UTF-8 正文含 U+FFFD", gotFffd.equals(withFffd),
                abbrev(gotFffd), abbrev(withFffd));
    }

    private static String readTempScript(byte[] bytes) throws IOException {
        File f = File.createTempFile("e2m-enc-", ".sql");
        f.deleteOnExit();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
        return SqlRunner.readScript(f);
    }

    private static Map<String, TableOps.Table> loadTables(String path) throws IOException {
        Map<String, TableOps.Table> out = new LinkedHashMap<String, TableOps.Table>();
        BufferedReader r = open(path);
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            Map<String, Object> rec = MiniJson.parseObject(line);
            Map<String, Object> def = (Map<String, Object>) rec.get("def");
            TableOps.Table t = new TableOps.Table();
            t.tableName = (String) def.get("table_name");
            t.baseTableName = (String) def.get("base_table_name");
            t.tableComment = (String) def.get("table_comment");
            t.source = (String) def.get("source");
            String pk = (String) def.get("primary_key");
            for (Object o : (List<Object>) def.get("columns")) {
                Map<String, Object> c = (Map<String, Object>) o;
                Infer.Column col = new Infer.Column();
                col.field = (String) c.get("field");
                col.comment = (String) c.get("comment");
                col.type = (String) c.get("type");
                col.primaryKey = pk != null && pk.equals(col.field);
                t.columns.add(col);
            }
            out.put((String) rec.get("file"), t);
        }
        r.close();
        return out;
    }

    private static BufferedReader open(String path) throws IOException {
        return new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(path)), Charset.forName("UTF-8")));
    }

    private static List<String> mapNames(List<Map<String, Object>> rows) {
        List<String> out = new ArrayList<String>();
        for (Map<String, Object> r : rows) {
            out.add(r.get("name") + ":" + r.get("count"));
        }
        return out;
    }

    private static boolean eqList(List<String> a, List<Object> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            Map<String, Object> row = (Map<String, Object>) b.get(i);
            String text = row.get("name") + ":" + row.get("count");
            if (!a.get(i).equals(text)) {
                return false;
            }
        }
        return true;
    }

    private static void report(String op, String in, boolean same, String got, String want) {
        if (same) {
            return;
        }
        bad++;
        if (shown.size() < 20) {
            shown.add("     差异[" + op + "] in=" + abbrev(in) + "\n       期望=" + want
                    + "\n       实际=" + got);
        }
    }

    /** 与 Python 侧 export_sql_golden.py 的 ddl_parse_lines 同一套渲染：一行一格，逐行对。 */
    private static List<String> renderParsed(List<SqlScript.ParsedTable> tables) {
        List<String> out = new ArrayList<String>();
        for (SqlScript.ParsedTable t : tables) {
            out.add("T|" + t.tableName + "|" + nz2(t.tableComment));
            for (com.excel2mysql.core.Infer.Column c : t.columns) {
                out.add("C|" + nz2(c.field) + "|" + nz2(c.type) + "|" + nz2(c.comment)
                        + "|" + (c.primaryKey ? "1" : "0")
                        + "|" + (c.nullable == null ? "-"
                                : (c.nullable.booleanValue() ? "1" : "0")));
            }
            for (String n : t.notes) {
                out.add("N|" + n);
            }
        }
        return out;
    }

    private static String nz2(String s) {
        return s == null ? "" : s;
    }

    private static String join(List<?> parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            if (sb.length() > 0) {
                sb.append(" || ");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String abbrev(String s) {
        if (s == null) {
            return "null";
        }
        String one = s.replace("\n", "\\n");
        return one.length() <= 160 ? one : one.substring(0, 160) + "~";
    }

    /** 不一致时给第一个不同的位置，省得在两段长文本里对眼。 */
    private static String diffFirst(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return a.substring(Math.max(0, i - 20), Math.min(a.length(), i + 40));
            }
        }
        return a.length() == b.length() ? a : a.substring(0, Math.min(a.length(), 120));
    }

    private static Object decodeValue(List<Object> cell) {
        String kind = (String) cell.get(0);
        String text = (String) cell.get(1);
        if ("null".equals(kind) || "None".equals(text)) {
            return null;
        }
        if ("bool".equals(kind)) {
            return Boolean.valueOf("True".equals(text));
        }
        if ("int".equals(kind)) {
            return new BigInteger(text);
        }
        if ("float".equals(kind)) {
            return Double.valueOf(Double.parseDouble(text));
        }
        if ("datetime".equals(kind)) {
            return LocalDateTime.parse(text);
        }
        if ("date".equals(kind)) {
            return LocalDate.parse(text);
        }
        return stripQuotes(text);
    }

    private static List<String> encodeValue(Object v) {
        List<String> out = new ArrayList<String>();
        if (v == null) {
            out.add("null");
            out.add("None");
            return out;
        }
        if (v instanceof Boolean) {
            out.add("bool");
            out.add(((Boolean) v) ? "True" : "False");
            return out;
        }
        if (v instanceof BigInteger || v instanceof Integer || v instanceof Long) {
            out.add("int");
            out.add(v.toString());
            return out;
        }
        if (v instanceof java.math.BigDecimal) {
            // Python 侧的编码器按 `type(v).__name__` 打标签，DECIMAL 列的字面值转出来是
            // Decimal；这边必须给同一个标签与同一种写法，否则两边都对也判成不等价
            out.add("Decimal");
            out.add("Decimal('" + v + "')");
            return out;
        }
        if (v instanceof Double) {
            out.add("float");
            out.add(com.excel2mysql.core.PyNumber.repr(((Double) v).doubleValue()));
            return out;
        }
        if (v instanceof LocalDateTime) {
            out.add("datetime");
            out.add(((LocalDateTime) v).toString());
            return out;
        }
        if (v instanceof LocalDate) {
            out.add("date");
            out.add(v.toString());
            return out;
        }
        out.add("str");
        out.add("'" + v + "'");
        return out;
    }

    private static String stripQuotes(String repr) {
        if (repr.length() >= 2 && repr.charAt(0) == '\'' && repr.charAt(repr.length() - 1) == '\'') {
            return repr.substring(1, repr.length() - 1).replace("\\'", "'").replace("\\\\", "\\");
        }
        return repr;
    }
}
