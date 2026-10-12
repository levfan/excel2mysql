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

            // pandas 侧先 dropna(axis=1, how=all) 再 dropna(axis=0, how=all)
            List<Integer> keep = new ArrayList<Integer>();
            for (int i = 0; i < headers.size(); i++) {
                for (List<Object> row : rows) {
                    Object v = i < row.size() ? row.get(i) : null;
                    if (!isNullish(v)) {
                        keep.add(Integer.valueOf(i));
                        break;
                    }
                }
            }
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
