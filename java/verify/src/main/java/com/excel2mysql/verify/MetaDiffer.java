package com.excel2mysql.verify;

import com.excel2mysql.core.Infer;
import com.excel2mysql.core.MetaExport;
import com.excel2mysql.core.Names;
import com.excel2mysql.core.TableBuilder;
import com.excel2mysql.core.TableOps;
import com.excel2mysql.pinyin.PinyinConverter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 撞「生成标准/简易 excel 文件」的产物：Java 写出的文件要和 Python 写出的逐格同值，
 * 命名、Sheet 名、目录前缀、元数据七列、日期落成文本都算。
 *
 * 只让各自回读自证不够：两条线可以各自"内部一致"地写出不同文件，测试照样全绿，
 * 而用户拿到的是文件本身。金标准由 java/tools/export_meta_golden.py 产出，
 * 既含写出的原始格子，也含 Python 把自己那份读回来得到的表定义。
 */
public final class MetaDiffer {

    private static int bad;
    private static int records;
    private static int cells;
    private static int sheetCount;
    private static final List<String> shown = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        String golden = args.length > 0 ? args[0] : "java/verify/golden/meta.jsonl.gz";
        String fixtureDir = args.length > 1 ? args[1] : "java/verify/fixtures";
        String work = args.length > 2 ? args[2]
                : System.getProperty("java.io.tmpdir") + File.separator + "e2m-meta-differ";
        deleteTree(new File(work));
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(golden)), Charset.forName("UTF-8")));
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isEmpty()) {
                compare(MiniJson.parseObject(line), fixtureDir, work);
            }
        }
        reader.close();
        deleteTree(new File(work));
        System.out.println("- [INFO] META-DIFF | 记录 " + records + " 条 | Sheet " + sheetCount
                + " 个 | 比对 " + cells + " 格 | 不一致 " + bad + " 处");
        for (String s : shown) {
            System.out.println(s);
        }
        if (records == 0) {
            System.out.println("x [ERROR] META-DIFF | 金标准是空的，等于没比");
            System.exit(1);
        }
        if (bad > 0) {
            System.out.println("x [ERROR] META-DIFF | Java 写出的文件与 Python 不等价");
            System.exit(1);
        }
        System.out.println("+ [OK] META-DIFF | 命名、内容、回读定义都与 Python 一致");
    }
    @SuppressWarnings("unchecked")
    private static void compare(Map<String, Object> rec, String fixtureDir, String work)
            throws Exception {
        records++;
        String src = (String) rec.get("src");
        String kind = (String) rec.get("kind");
        boolean withMeta = "std".equals(kind);
        TableBuilder.Result built =
                new TableBuilder(new Infer(names()), names()).build(fixtureDir + "/" + src);
        String gotName = MetaExport.fileName(built.table);
        wantStr(src, "导出文件名", rec.get("file"), gotName);

        String dir = work + "/" + kind + "/" + safe(src);
        String file = dir + "/" + gotName;
        MetaExport.writeTable(MetaExport.of(built.table, built), file, withMeta);
        // 目录前缀也在产物上撞一次：两条线各自拼一遍，就会有一天拼得不一样
        wantStr(src, "输出目录前缀", (String) rec.get("dir_prefix"),
                prefixOf(MetaExport.outputDir(dir, withMeta, "20260101")));

        List<Object> wantSheets = (List<Object>) rec.get("sheets");
        List<MetaExport.Dump> got = MetaExport.dump(file);
        if (wantSheets.size() != got.size()) {
            report(src, "Sheet 数", String.valueOf(wantSheets.size()),
                    String.valueOf(got.size()));
        } else {
            sheetCount += got.size();
            for (int i = 0; i < got.size(); i++) {
                Map<String, Object> w = (Map<String, Object>) wantSheets.get(i);
                compareSheet(src + "#" + kind, String.valueOf(w.get("name")),
                        (List<Object>) w.get("rows"), got.get(i));
            }
        }
        wantStr(src, "源表数据行数", ((Map<String, Object>) rec.get("def")).get("row_count"),
                String.valueOf(built.rows.size()));

        TableBuilder.Result back = new TableBuilder(new Infer(names()), names()).build(file);
        Map<String, Object> wantBack = (Map<String, Object>) rec.get("back");
        compareDef(src + " 回读", wantBack, back);
        wantStr(src + " 回读", "数据行数", wantBack.get("row_count"),
                String.valueOf(back.rows.size()));
    }

    private static String prefixOf(String dir) {
        String name = new File(dir).getName();
        int at = name.lastIndexOf('_');
        return at < 0 ? name : name.substring(0, at);
    }
    /**
     * 把 POI 读出来的参差网格整形成和 Python 侧 raw_sheets() 同一形状：
     * 行尾空格裁掉、尾部整行裁掉、短行补到最大宽度。两边各自"按自己的读法"比，
     * 差的就只是内容本身。
     */
    private static List<List<Object>> shape(List<List<Object>> rows) {
        List<List<Object>> out = new ArrayList<List<Object>>();
        for (List<Object> row : rows) {
            List<Object> r = new ArrayList<Object>(row);
            while (!r.isEmpty() && blankish(r.get(r.size() - 1))) {
                r.remove(r.size() - 1);
            }
            out.add(r);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        int width = 0;
        for (List<Object> r : out) {
            width = Math.max(width, r.size());
        }
        for (List<Object> r : out) {
            while (r.size() < width) {
                r.add(null);
            }
        }
        return out;
    }

    private static boolean blankish(Object v) {
        return v == null || "".equals(v);
    }

    @SuppressWarnings("unchecked")
    private static void compareSheet(String tag, String wantName, List<Object> wantRows,
                                     MetaExport.Dump got) {
        wantStr(tag, "Sheet 名", wantName, got.sheet);
        List<List<Object>> have = shape(got.cells);
        if (wantRows.size() != have.size()) {
            report(tag, "Sheet[" + wantName + "] 行数", String.valueOf(wantRows.size()),
                    String.valueOf(have.size()));
        }
        int height = Math.max(wantRows.size(), have.size());
        for (int i = 0; i < height; i++) {
            List<Object> w = i < wantRows.size() ? (List<Object>) wantRows.get(i) : null;
            List<Object> g = i < have.size() ? have.get(i) : null;
            if (w == null || g == null) {
                report(tag, "Sheet[" + wantName + "] 第 " + i + " 行",
                        w == null ? "无" : w.size() + " 格",
                        g == null ? "无" : g.size() + " 格");
                continue;
            }
            cells += w.size();
            for (int j = 0; j < Math.max(w.size(), g.size()); j++) {
                String ww = j < w.size() ? pair((List<Object>) w.get(j)) : "(缺)";
                String gw = j < g.size() ? encode(g.get(j)) : "(缺)";
                if (!ww.equals(gw)) {
                    report(tag, "Sheet[" + wantName + "] 行" + i + " 列" + j,
                            ascii(ww), ascii(gw));
                }
            }
        }
    }
    private static String pair(List<Object> cell) {
        String kind = (String) cell.get(0);
        String text = (String) cell.get(1);
        if ("float".equals(kind)) {
            return "float\u0000" + canonical(Double.valueOf(text).doubleValue());
        }
        return kind + "\u0000" + text;
    }

    /**
     * 与 java/tools/export_meta_golden.py 的 encode() 一一对齐。
     *
     * 这里刻意不做读取层那套"纯空白按空值等价"的折叠：比的是写出去的文件本身，
     * "    " 和空格丢了就是丢了，折成同一个值等于把这条路的缺陷记成一致。
     */
    private static String encode(Object v) {
        if (v == null) {
            return "none\u0000";
        }
        if (v instanceof BigInteger) {
            return "int\u0000" + v.toString();
        }
        if (v instanceof Double) {
            double d = ((Double) v).doubleValue();
            return Double.isNaN(d) ? "none\u0000" : "float\u0000" + canonical(d);
        }
        if (v instanceof Boolean) {
            return "bool\u0000" + (((Boolean) v) ? "1" : "0");
        }
        return "str\u0000" + String.valueOf(v);
    }

    private static String canonical(double d) {
        return new java.math.BigDecimal(d).stripTrailingZeros().toPlainString();
    }

    /** 整张表定义逐字段比：表名、注释、来源、主键与每一列的字段名/类型/可空/主键标记。 */
    @SuppressWarnings("unchecked")
    private static void compareDef(String file, Map<String, Object> want,
                                   TableBuilder.Result got) {
        TableOps.Table t = got.table;
        wantStr(file, "表名", want.get("table_name"), t.tableName);
        wantStr(file, "基础表名", want.get("base_table_name"), t.baseTableName);
        wantStr(file, "表注释", want.get("table_comment"), t.tableComment);
        wantStr(file, "来源", want.get("source"), t.source);
        wantStr(file, "主键", want.get("primary_key"), pkOf(t));
        wantStr(file, "主键理由", want.get("pk_reason"), got.pkReason);
        List<Object> ws = (List<Object>) want.get("columns");
        if (ws.size() != t.columns.size()) {
            report(file, "列数", String.valueOf(ws.size()), String.valueOf(t.columns.size()));
            return;
        }
        for (int i = 0; i < ws.size(); i++) {
            Map<String, Object> w = (Map<String, Object>) ws.get(i);
            Infer.Column g = t.columns.get(i);
            wantStr(file, "列#" + i + " 字段名", w.get("field"), g.field);
            wantStr(file, "列#" + i + " 注释", w.get("comment"), g.comment);
            wantStr(file, "列#" + i + " 类型", w.get("type"), g.type);
            wantBool(file, "列#" + i + " 可空", (Boolean) w.get("nullable"), g.nullable);
            wantBool(file, "列#" + i + " 主键", (Boolean) w.get("primary_key"),
                    Boolean.valueOf(g.primaryKey));
        }
    }

    private static String pkOf(TableOps.Table t) {
        StringBuilder sb = new StringBuilder();
        for (Infer.Column c : t.columns) {
            if (c.primaryKey) {
                if (sb.length() > 0) {
                    sb.append('+');
                }
                sb.append(c.field);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void wantStr(String file, String what, Object want, String have) {
        String w = want == null ? null : String.valueOf(want);
        if (w == null ? have != null : !w.equals(have)) {
            report(file, what, ascii(String.valueOf(want)), ascii(String.valueOf(have)));
        }
    }

    private static void wantBool(String file, String what, Boolean want, Boolean have) {
        if (want == null ? have != null : !want.equals(have)) {
            report(file, what, String.valueOf(want), String.valueOf(have));
        }
    }

    private static void report(String file, String what, String want, String have) {
        bad++;
        if (shown.size() < 15) {
            shown.add("     导出[" + file + "] " + what + " 期望 " + want + " 实际 " + have);
        }
    }

    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Names names;

    /** 词典加载失败要立刻停：拿半套拼音表去比，比出来的红绿都不算数。 */
    private static Names names() {
        if (names == null) {
            try {
                names = new Names(PinyinConverter.loadFromResources());
            } catch (IOException e) {
                throw new IllegalStateException("拼音词典加载失败", e);
            }
        }
        return names;
    }

    private static String ascii(Object v) {
        StringBuilder sb = new StringBuilder();
        for (char ch : String.valueOf(v).toCharArray()) {
            if (ch >= 0x20 && ch < 0x7F && ch != 0) {
                sb.append(ch == '"' ? "\\\"" : String.valueOf(ch));
            } else if (ch == 0) {
                sb.append('|');
            } else {
                sb.append(String.format("\\u%04x", Integer.valueOf(ch)));
            }
        }
        return sb.toString();
    }
    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }
}
