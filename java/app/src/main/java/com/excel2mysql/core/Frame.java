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

    public final String name;
    public final List<Object> headers;
    public final List<List<Object>> rows;

    private final Map<Object, Integer> headerIndex = new LinkedHashMap<Object, Integer>();

    public Frame(String name, List<List<Object>> rawRows) {
        this.name = name;
        if (rawRows.isEmpty()) {
            this.headers = new ArrayList<Object>();
            this.rows = new ArrayList<List<Object>>();
            return;
        }
        List<Object> first = rawRows.get(0);
        Map<String, Integer> seen = new LinkedHashMap<String, Integer>();
        List<Object> hdr = new ArrayList<Object>();
        for (int i = 0; i < first.size(); i++) {
            Object v = first.get(i);
            String label = blank(v) ? "Unnamed: " + i : String.valueOf(v);
            Integer count = seen.get(label);
            if (count == null) {
                seen.put(label, Integer.valueOf(1));
            } else {
                seen.put(label, Integer.valueOf(count.intValue() + 1));
                label = label + "." + count.intValue();
            }
            hdr.add(label);
            if (!headerIndex.containsKey(label)) {
                headerIndex.put(label, Integer.valueOf(hdr.size() - 1));
            }
        }
        this.headers = hdr;
        List<List<Object>> data = new ArrayList<List<Object>>();
        for (int i = 1; i < rawRows.size(); i++) {
            data.add(rawRows.get(i));
        }
        this.rows = data;
    }

    /** 按表头取值所在列号；找不到返回 -1。重复表头取第一次出现的位置，和 pandas 取列一致。 */
    public int indexOf(Object header) {
        Integer at = headerIndex.get(header);
        return at == null ? -1 : at.intValue();
    }

    private static boolean blank(Object v) {
        return v == null || "".equals(v);
    }
}
