package com.excel2mysql.verify;

import com.github.stuxuhai.jpinyin.PinyinFormat;
import com.github.stuxuhai.jpinyin.PinyinHelper;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 把 jpinyin 拿去撞 P1 的拼音金标准语料：量的是换库之后表名会不会变，
 * 不是这个库能不能跑。期望值全部来自真实 pypinyin。
 *
 * 退出码 0 表示逐条等价，1 表示存在差异。
 */
public final class JpinyinDiffer {

    private static final String SEP = "\u0001";

    public static void main(String[] args) throws IOException {
        int perTag = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        Map<String, Integer> tagShown = new LinkedHashMap<String, Integer>();
        Map<String, Integer> byKind = new LinkedHashMap<String, Integer>();
        Map<String, Integer> byTag = new LinkedHashMap<String, Integer>();
        List<String> samples = new ArrayList<String>();
        int total = 0;
        int bad = 0;

        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(args[0])), Charset.forName("UTF-8")));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                Map<String, Object> record = PinyinDiffer.MiniJson.parseObject(line);
                String input = (String) record.get("in");
                String tag = (String) record.get("tag");
                @SuppressWarnings("unchecked")
                List<String> expected = (List<String>) record.get("exp");
                total++;
                List<String> actual;
                try {
                    // 必须显式 WITHOUT_TONE：默认是 WITH_TONE_MARK，
                    // 拿带声调的输出去比无调的期望值，单位不同必然 100% 不一致
                    actual = split(PinyinHelper.convertToPinyinString(
                            input, SEP, PinyinFormat.WITHOUT_TONE));
                } catch (Exception e) {
                    actual = null;
                }
                String kind = actual == null ? "抛出异常，取不到拼音" : classify(expected, actual);
                if (kind == null) {
                    bump(byTag, tag + " 一致");
                    continue;
                }
                bump(byTag, tag + " 不一致");
                bad++;
                bump(byKind, kind);
                // 每个分类都要有样本：只按出现顺序取前 N 条的话，
                // 语料里量最大的 single-batch 会把 phrase / project-sample 挤出报表
                Integer shownForTag = tagShown.get(tag);
                int shownCount = shownForTag == null ? 0 : shownForTag.intValue();
                if (shownCount < perTag) {
                    tagShown.put(tag, Integer.valueOf(shownCount + 1));
                    samples.add("     差异[" + tag + "] in=" + PinyinDiffer.ascii(input)
                            + "\n       pypinyin=" + PinyinDiffer.ascii(expected)
                            + "\n       jpinyin  =" + (actual == null ? "抛异常" : PinyinDiffer.ascii(actual)));
                }
            }
        } finally {
            reader.close();
        }

        System.out.println("- [INFO] JPINYIN-DIFF | 语料 " + total + " 条 | 不一致 " + bad
                + " 条（" + percent(bad, total) + "%）");
        for (Map.Entry<String, Integer> e : byKind.entrySet()) {
            System.out.println("    " + e.getKey() + " = " + e.getValue());
        }
        System.out.println("- [INFO] JPINYIN-DIFF | 按语料分类");
        for (Map.Entry<String, Integer> e : byTag.entrySet()) {
            System.out.println("    " + e.getKey() + " = " + e.getValue());
        }
        for (String s : samples) {
            System.out.println(s);
        }
        if (bad > 0) {
            System.out.println("x [ERROR] JPINYIN-DIFF | 换库会改动表名与字段名，属于数据契约变更");
            System.exit(1);
        }
        System.out.println("+ [OK] JPINYIN-DIFF | jpinyin 与 pypinyin 在本语料上逐条等价");
    }

    private static List<String> split(String joined) {
        List<String> out = new ArrayList<String>();
        if (joined.length() == 0) {
            return out;
        }
        int start = 0;
        while (true) {
            int at = joined.indexOf(SEP, start);
            if (at < 0) {
                out.add(joined.substring(start));
                return out;
            }
            out.add(joined.substring(start, at));
            start = at + SEP.length();
        }
    }

    /** null 表示这条一致；否则给出不一致的原因分类，供报表按类计数。 */
    private static String classify(List<String> expected, List<String> actual) {
        if (expected.equals(actual)) {
            return null;
        }
        if (expected.size() != actual.size()) {
            return "音节数不同（分词切法不一样）";
        }
        int firstDiff = -1;
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                firstDiff = i;
                break;
            }
        }
        String e = expected.get(firstDiff);
        String a = actual.get(firstDiff);
        if (e.length() == 1 && a.length() == 1) {
            return "非汉字字符处理方式不同";
        }
        if (e.isEmpty() || a.isEmpty()) {
            return "空音节的填法不同";
        }
        if (stripToneLike(e).equals(stripToneLike(a))) {
            return "v/ü 等字母写法不同";
        }
        return "同字不同音（多音字选音不同）";
    }

    private static String stripToneLike(String s) {
        return s.replace("v", "u").replace("U", "V");
    }

    private static void bump(Map<String, Integer> map, String key) {
        Integer prev = map.get(key);
        map.put(key, prev == null ? Integer.valueOf(1) : Integer.valueOf(prev.intValue() + 1));
    }

    private static String percent(int part, int whole) {
        if (whole == 0) {
            return "0.0";
        }
        return String.format("%.1f", Double.valueOf(part * 100.0 / whole));
    }
}
