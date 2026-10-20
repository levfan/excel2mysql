package com.excel2mysql.verify;

import com.excel2mysql.core.Infer;
import com.excel2mysql.core.Names;
import com.excel2mysql.pinyin.PinyinConverter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 用金标准里已经 dump 出来的格值撞类型推断，中间不经过 Excel 读取。
 * 这样报出来的差异只可能来自推断规则，不会和"读表格读得不对"混在一起。
 */
public final class TypeDiffer {

    public static void main(String[] args) throws IOException {
        String golden = args.length > 0 ? args[0] : "java/verify/golden/excel.jsonl.gz";
        Infer infer = new Infer(new Names(PinyinConverter.loadFromResources()));
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(golden)), Charset.forName("UTF-8")));
        int files = 0;
        int cols = 0;
        int bad = 0;
        List<String> shown = new ArrayList<String>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                continue;
            }
            Map<String, Object> rec = MiniJson.parseObject(line);
            String file = (String) rec.get("file");
            Map<String, Object> def = asMap(rec.get("def"));
            if (!"normal".equals(def.get("source"))) {
                continue;
            }
            files++;
            List<Object> grid = asList(rec.get("grid"));
            Map<String, Object> sheet = asMap(grid.get(0));
            List<Object> rawHeaders = asList(sheet.get("headers"));
            List<Object> rawRows = asList(sheet.get("rows"));

            List<Object> headers = new ArrayList<Object>();
            for (Object h : rawHeaders) {
                headers.add(decode(asList(h)));
            }
            List<List<Object>> rows = new ArrayList<List<Object>>();
            for (Object r : rawRows) {
                List<Object> cells = asList(r);
                List<Object> row = new ArrayList<Object>();
                for (Object c : cells) {
                    row.add(decode(asList(c)));
                }
                rows.add(row);
            }

            // 列取舍只允许有一份实现：整列没值且表头也没写字才丢，表头写了字的空列保留。
            // 这条规则在 TableBuilder.filterColumns 里，这里调它而不是再抄一遍——
            // 两处各写一遍的话改一边就静默分叉，报出来的差异还得再查一轮才知道是谁错了
            com.excel2mysql.core.TableBuilder.ColumnFilter cf =
                    com.excel2mysql.core.TableBuilder.filterColumns(headers, rows);
            List<Integer> keep = cf.keep;
            List<Object> keptHeaders = new ArrayList<Object>();
            for (Integer i : keep) {
                keptHeaders.add(headers.get(i.intValue()));
            }
            List<List<Object>> keptRows = new ArrayList<List<Object>>();
            for (List<Object> row : rows) {
                List<Object> projected = new ArrayList<Object>();
                boolean anyValue = false;
                for (Integer i : keep) {
                    Object v = i.intValue() < row.size() ? row.get(i.intValue()) : null;
                    projected.add(v);
                    if (!isNullish(v)) {
                        anyValue = true;
                    }
                }
                if (anyValue) {
                    keptRows.add(projected);
                }
            }

            List<Infer.Column> got = infer.inferColumns(keptHeaders, keptRows);
            String[] pk = infer.selectPrimaryKey(keptHeaders, keptRows, got);
            for (Infer.Column c : got) {
                if (c.field.equals(pk[0])) {
                    c.primaryKey = true;
                }
            }

            List<Object> wantCols = asList(def.get("columns"));
            if (wantCols.size() != got.size()) {
                bad++;
                if (shown.size() < 15) {
                    shown.add("     列数[" + file + "] 期望 " + wantCols.size()
                            + " 实际 " + got.size());
                }
                continue;
            }
            for (int i = 0; i < got.size(); i++) {
                Map<String, Object> w = asMap(wantCols.get(i));
                Infer.Column g = got.get(i);
                cols++;
                String wField = (String) w.get("field");
                String wType = (String) w.get("type");
                long wMax = toLong(w.get("max_len"));
                if (!eq(wField, g.field) || !eq(wType, g.type) || wMax != g.maxLen) {
                    bad++;
                    if (shown.size() < 15) {
                        shown.add("     列[" + file + " #" + i + " " + ascii(wField) + "]"
                                + " 期望 " + ascii(wField) + "/" + ascii(wType) + "/" + wMax
                                + " 实际 " + ascii(g.field) + "/" + ascii(g.type) + "/" + g.maxLen);
                    }
                }
            }
            String wPk = (String) def.get("primary_key");
            if (!eq(wPk, pk[0])) {
                bad++;
                if (shown.size() < 15) {
                    shown.add("     主键[" + file + "] 期望 " + ascii(wPk) + " 实际 " + ascii(pk[0]));
                }
            }
            // 具名空列与"只有表头没有数据行"：这两件事决定日志里那句点名与
            // "建出来是空表"的判定，两边必须给同一份清单
            List<Object> wEmpty = asList(def.get("empty_columns"));
            if (!joinCn(wEmpty).equals(joinCn(cf.emptyColumns))) {
                bad++;
                if (shown.size() < 15) {
                    shown.add("     空列[" + file + "] 期望 " + ascii(joinCn(wEmpty))
                            + " 实际 " + ascii(joinCn(cf.emptyColumns)));
                }
            }
            boolean wNoRows = Boolean.TRUE.equals(def.get("no_data_rows"));
            if (wNoRows != keptRows.isEmpty()) {
                bad++;
                if (shown.size() < 15) {
                    shown.add("     数据行数[" + file + "] 期望 no_data_rows=" + wNoRows
                            + " 实际 rows=" + keptRows.size());
                }
            }
        }
        reader.close();

        System.out.println("- [INFO] TYPE-DIFF | 表 " + files + " 张 | 列 " + cols
                + " 个 | 不一致 " + bad + " 处");
        for (String s : shown) {
            System.out.println(s);
        }
        if (bad > 0) {
            System.out.println("x [ERROR] TYPE-DIFF | 类型推断与 Python 线不等价");
            System.exit(1);
        }
        System.out.println("+ [OK] TYPE-DIFF | 字段名、类型、字节宽度、主键逐条一致");
    }

    private static boolean isNullish(Object v) {
        if (v == null) {
            return true;
        }
        return v instanceof Double && Double.isNaN(((Double) v).doubleValue());
    }

    private static Object decode(List<Object> cell) {
        String kind = (String) cell.get(0);
        String text = (String) cell.get(1);
        if ("none".equals(kind)) {
            return null;
        }
        if ("int".equals(kind)) {
            return new BigInteger(text);
        }
        if ("float".equals(kind)) {
            return Double.valueOf(Double.valueOf(text));
        }
        if ("bool".equals(kind)) {
            return Boolean.valueOf("1".equals(text));
        }
        if ("datetime".equals(kind)) {
            return LocalDateTime.parse(text);
        }
        if ("date".equals(kind)) {
            return LocalDate.parse(text);
        }
        return text;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static long toLong(Object v) {
        if (v == null) {
            return -1L;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        return -1L;
    }

    /** 按"、"连起来；null 当空表（老金标准里没这个键时不误报成差异）。 */
    private static String joinCn(List<?> xs) {
        if (xs == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object x : xs) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(x == null ? "" : String.valueOf(x));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    private static String ascii(Object v) {
        StringBuilder sb = new StringBuilder();
        for (char ch : String.valueOf(v).toCharArray()) {
            if (ch >= 0x20 && ch < 0x7F) {
                sb.append(ch);
            } else {
                sb.append(String.format("\\u%04x", Integer.valueOf(ch)));
            }
        }
        return sb.toString();
    }
}
