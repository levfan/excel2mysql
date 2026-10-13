package com.excel2mysql.verify;

import com.excel2mysql.core.Frame;
import com.excel2mysql.core.Infer;
import com.excel2mysql.core.Names;
import com.excel2mysql.core.TableBuilder;
import com.excel2mysql.core.TableOps;
import com.excel2mysql.pinyin.PinyinConverter;
import com.excel2mysql.core.MergeCheck;
import com.excel2mysql.excel.WorkbookReader;

import java.io.BufferedReader;
import java.io.FileInputStream;
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
 * 撞读取层：同一份夹具，FastExcel 读出来的网格要和 pandas.read_excel(dtype=object) 逐格同值。
 * 只比第一张 Sheet 的第 1 类普通表，结构化模式那条路等这层对齐了再撞。
 */
public final class ReaderDiffer {

    public static void main(String[] args) throws IOException {
        String golden = args.length > 0 ? args[0] : "java/verify/golden/excel.jsonl.gz";
        String fixtureDir = args.length > 1 ? args[1] : "java/verify/fixtures";
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(golden)), Charset.forName("UTF-8")));
        int files = 0;
        int structuredFiles = 0;
        int mergedFiles = 0;
        int rejected = 0;
        int cells = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            Map<String, Object> rec = MiniJson.parseObject(line);
            Map<String, Object> def = (Map<String, Object>) rec.get("def");
            String file = (String) rec.get("file");
            String path = fixtureDir + "/" + file;
            // 合并单元格检测要在读数据之前判，且它本身就是契约：
            // 老格式只有把格式化信息读出来才看得到合并区，是最容易"静默漏检"的一段
            Object wantMerged = rec.get("merged");
            if (wantMerged != null) {
                mergedFiles++;
                Map<String, List<String>> got = MergeCheck.detect(path);
                String want = sortRanges((Map<String, Object>) wantMerged);
                String have = sortRangesFromLists(got);
                if (!want.equals(have)) {
                    bad++;
                    if (shown.size() < 15) {
                        shown.add("     合并区 [" + file + "] 期望 " + ascii(want)
                                + " 实际 " + ascii(have));
                    }
                }
                if ("merged".equals(def.get("source"))) {
                    // 这条夹具本来就该被拒：漏检就会静默建出一张错表
                    try {
                        MergeCheck.ensureNoMergedCells(path);
                        bad++;
                        if (shown.size() < 15) {
                            shown.add("     漏检 [" + file + "] 含合并区却没拒绝");
                        }
                    } catch (MergeCheck.MergedCellsException expected) {
                        rejected++;
                    }
                }
            }
            if ("structured".equals(def.get("source"))) {
                // 元数据 Sheet 驱动那条路从没被端到端比过：NameDiffer 比的是拼音规则
                // （这里字段名是用户手写的），TypeDiffer 比的是推断器（这里类型来自元数据），
                // 两边都名正言顺地跳过了它。这里拿 Java 整条 build 的结果逐字段比。
                structuredFiles++;
                TableBuilder.Result built =
                        new TableBuilder(new Infer(names()), names()).build(path);
                compareDef(file, def, built);
                continue;
            }
            if (!"normal".equals(def.get("source"))) {
                continue;
            }
            files++;
            Map<String, Object> sheet = (Map<String, Object>) ((List<Object>) rec.get("grid")).get(0);
            List<Object> wantHeaders = (List<Object>) sheet.get("headers");
            List<Object> wantRows = (List<Object>) sheet.get("rows");

            List<WorkbookReader.Sheet> read =
                    WorkbookReader.read(path);
            List<List<Object>> rows = rawRows(read.isEmpty() ? null : read.get(0));

            // pandas 的规则：行尾空串裁掉、尾部整行空裁掉、短行补到最大宽度
            rows = normalize(rows);
            Frame frame = new Frame("s0", rows);

            int height = Math.max(wantRows.size(), frame.rows.size());
            for (int i = -1; i < height; i++) {
                List<Object> w = i < 0 ? wantHeaders
                        : (i < wantRows.size() ? (List<Object>) wantRows.get(i) : null);
                List<String> g = i < 0 ? encodeList(frame.headers)
                        : (i < frame.rows.size() ? encodeRow(frame.rows.get(i)) : null);
                if (w == null || g == null) {
                    bad++;
                    if (shown.size() < 15) {
                        shown.add("     行 [" + file + " #" + i + "] 期望 "
                                + (w == null ? "无" : w.size() + " 格") + " 实际 "
                                + (g == null ? "无" : g.size() + " 格"));
                    }
                    continue;
                }
                cells += w.size();
                for (int j = 0; j < Math.max(w.size(), g.size()); j++) {
                    String gw = j < g.size() ? g.get(j) : "(缺)";
                    String ww = j < w.size() ? pair((List<Object>) w.get(j)) : "(缺)";
                    if (!gw.equals(ww)) {
                        bad++;
                        if (shown.size() < 15) {
                            shown.add("     格 [" + file + " 行" + i + " 列" + j
                                    + "] 期望 " + ascii(ww) + " 实际 " + ascii(gw));
                        }
                    }
                }
            }
        }
        reader.close();
        System.out.println("- [INFO] READER-DIFF | 表 " + files + " 张 | 比对 " + cells
                + " 格 | 不一致 " + bad + " 处 | 空白折叠 " + blankFolded + " 格"
                + " | 结构定义表端到端比对 " + structuredFiles + " 张"
                + " | 合并区检测 " + mergedFiles + " 个文件（其中按拒绝记账 " + rejected + "）");
        for (String s : shown) {
            System.out.println(s);
        }
        if (bad > 0) {
            System.out.println("x [ERROR] READER-DIFF | 读取层与 pandas 不等价");
            System.exit(1);
        }
        System.out.println("+ [OK] READER-DIFF | 逐格取值与 pandas 一致");
    }

    private static List<List<Object>> rawRows(WorkbookReader.Sheet sheet) {
        List<List<Object>> rows = new ArrayList<List<Object>>();
        if (sheet == null) {
            return rows;
        }
        for (List<Object> r : sheet.rows) {
            rows.add(r);
        }
        return rows;
    }

    private static boolean isBlankCell(Object v) {
        return v == null || "".equals(v);
    }

    private static List<List<Object>> normalize(List<List<Object>> rows) {
        int lastWithData = -1;
        for (int i = 0; i < rows.size(); i++) {
            List<Object> row = rows.get(i);
            while (!row.isEmpty() && isBlankCell(row.get(row.size() - 1))) {
                row.remove(row.size() - 1);
            }
            if (!row.isEmpty()) {
                lastWithData = i;
            }
        }
        List<List<Object>> kept = new ArrayList<List<Object>>(rows.subList(0, lastWithData + 1));
        int width = 0;
        for (List<Object> r : kept) {
            width = Math.max(width, r.size());
        }
        for (List<Object> r : kept) {
            while (r.size() < width) {
                r.add(null);
            }
        }
        return kept;
    }

    private static String pair(List<Object> cell) {
        String kind = (String) cell.get(0);
        String text = (String) cell.get(1);
        if ("float".equals(kind)) {
            // Python 侧存的是 float.hex()，归一到和 encode() 同一个十进制最短形
            return "float\u0000" + canonical(Double.valueOf(text).doubleValue());
        }
        if ("str".equals(kind) && !text.isEmpty() && text.trim().isEmpty()) {
            // 只在 Python 侧计数：Java 侧本来就是 null，不折也一致
            blankFolded++;
            return "none\u0000";
        }
        return kind + "\u0000" + text;
    }

    private static List<String> encodeList(List<Object> values) {
        return encodeRow(values);
    }

    /** 与 java/tools/export_excel_golden.py 的 encode() 一一对齐。 */
    private static List<String> encodeRow(List<Object> row) {
        List<String> out = new ArrayList<String>();
        for (Object v : row) {
            out.add(encode(v));
        }
        return out;
    }

    private static String encode(Object v) {
        if (v == null) {
            return "none\u0000";
        }
        if (v instanceof BigInteger) {
            return "int\u0000" + v.toString();
        }
        if (v instanceof Double) {
            double d = ((Double) v).doubleValue();
            if (Double.isNaN(d)) {
                return "none\u0000";
            }
            return "float\u0000" + canonical(d);
        }
        if (v instanceof Boolean) {
            return "bool\u0000" + (((Boolean) v) ? "1" : "0");
        }
        if (v instanceof LocalDateTime) {
            LocalDateTime dt = (LocalDateTime) v;
            return "datetime\u0000" + String.format("%04d-%02d-%02dT%02d:%02d:%02d",
                    dt.getYear(), dt.getMonthValue(), dt.getDayOfMonth(),
                    dt.getHour(), dt.getMinute(), dt.getSecond());
        }
        if (v instanceof LocalDate) {
            return "date\u0000" + v.toString();
        }
        if (v instanceof String) {
            String s = (String) v;
            // 纯空白文本按空值等价处理：infer_columns 在统计字节宽度之前就 continue 掉它，
            // _convert 也把它写成 NULL，所以"null vs \"   \""产品层面观察不到。
            // 这类折叠要计数报出来，不能静默——它确实是两条链取值的差别，只是不可观测。
            if (!s.isEmpty() && s.trim().isEmpty()) {
                blankFolded++;
                return "none\u0000";
            }
            return "str\u0000" + s;
        }
        return "str\u0000" + String.valueOf(v);
    }

    /** 金标准里的合并区：{sheet: [range, ...]}，排序后拼成可比对的规范文本。 */
    @SuppressWarnings("unchecked")
    private static String sortRanges(Map<String, Object> merged) {
        List<String> parts = new ArrayList<String>();
        List<String> names = new ArrayList<String>(merged.keySet());
        java.util.Collections.sort(names);
        for (String n : names) {
            List<Object> raw = (List<Object>) merged.get(n);
            List<String> rs = new ArrayList<String>();
            for (Object o : raw) {
                rs.add(String.valueOf(o));
            }
            java.util.Collections.sort(rs);
            parts.add(n + "=" + rs);
        }
        return parts.toString();
    }

    private static String sortRangesFromLists(Map<String, List<String>> merged) {
        Map<String, Object> asMap = new java.util.TreeMap<String, Object>(merged);
        return sortRanges(asMap);
    }

    private static Names names;

    /** 词典只在建结构定义表时才需要；加载失败要立刻停，不能拿半套拼音表去比。 */
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

    /** 整张表定义逐字段比：表名、注释、来源、主键与每一列的字段名/类型/可空/主键标记。 */
    @SuppressWarnings("unchecked")
    private static void compareDef(String file, Map<String, Object> want, TableBuilder.Result got) {
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
            shown.add("     结构定义[" + file + "] " + what + " 期望 " + want + " 实际 " + have);
        }
    }

    private static int blankFolded;
    private static int bad;
    private static final List<String> shown = new ArrayList<String>();

    /**
     * 两边归一到同一个可比形式：Python 的 float.hex() 是 0x1.9000000000000p+3，
     * Java 的 Double.toHexString 是 0x1.9p3，字面比必然假红。
     * 比"是不是同一个 double"用位级最准，但报表时要能读出数值，所以这里用十进制最短形。
     */
    private static String canonical(double d) {
        return new java.math.BigDecimal(d).stripTrailingZeros().toPlainString();
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
}
