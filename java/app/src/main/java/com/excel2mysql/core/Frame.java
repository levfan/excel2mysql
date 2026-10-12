package com.excel2mysql.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * pandas 的表头语义：第一行当列名，空列名补成 Unnamed: N，重名列追加 .1 .2。
 *
 * 这两条不是装饰：字段名由列名转拼音得来，"重复名"和"重复名.1"转出来是
 * chong_fu_ming 与 chong_fu_ming_1（点被当非 \w 字符换成下划线），
 * 不复刻就会在同名列上生成一样的字段，建表直接撞。
 */
public final class Frame {

    public final List<Object> headers;
    public final List<List<Object>> rows;

    private Frame(List<Object> headers, List<List<Object>> rows) {
        this.headers = headers;
        this.rows = rows;
    }

    public static Frame of(List<List<Object>> rawRows) {
        if (rawRows.isEmpty()) {
            return new Frame(new ArrayList<Object>(), new ArrayList<List<Object>>());
        }
        List<Object> first = rawRows.get(0);
        Map<String, Integer> seen = new LinkedHashMap<String, Integer>();
        List<Object> headers = new ArrayList<Object>();
        for (int i = 0; i < first.size(); i++) {
            Object v = first.get(i);
            String name = blank(v) ? "Unnamed: " + i : String.valueOf(v);
            Integer count = seen.get(name);
            if (count == null) {
                seen.put(name, Integer.valueOf(1));
            } else {
                seen.put(name, Integer.valueOf(count.intValue() + 1));
                name = name + "." + count.intValue();
            }
            headers.add(name);
        }
        List<List<Object>> rows = new ArrayList<List<Object>>();
        for (int i = 1; i < rawRows.size(); i++) {
            rows.add(rawRows.get(i));
        }
        return new Frame(headers, rows);
    }

    private static boolean blank(Object v) {
        return v == null || "".equals(v);
    }
}
