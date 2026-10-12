package com.excel2mysql.verify;

import com.excel2mysql.core.Frame;
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
        int cells = 0;
        int bad = 0;
        List<String> shown = new ArrayList<String>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            Map<String, Object> rec = MiniJson.parseObject(line);
            Map<String, Object> def = (Map<String, Object>) rec.get("def");
            if (!"normal".equals(def.get("source"))) {
                continue;
            }
            String file = (String) rec.get("file");
            files++;
            Map<String, Object> sheet = (Map<String, Object>) ((List<Object>) rec.get("grid")).get(0);
            List<Object> wantHeaders = (List<Object>) sheet.get("headers");
            List<Object> wantRows = (List<Object>) sheet.get("rows");

            List<WorkbookReader.Sheet> read =
                    WorkbookReader.read(fixtureDir + "/" + file);
            List<List<Object>> rows = rawRows(read.isEmpty() ? null : read.get(0));

            // pandas 的规则：行尾空串裁掉、尾部整行空裁掉、短行补到最大宽度
            rows = normalize(rows);
            Frame frame = Frame.of(rows);

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
                + " 格 | 不一致 " + bad + " 处 | 空白折叠 " + blankFolded + " 格");
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

    private static int blankFolded;

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
