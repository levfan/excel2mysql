package com.excel2mysql.verify;

import com.excel2mysql.pinyin.PinyinConverter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 读金标准语料，逐条比对 Java 复刻与 pypinyin 的期望值。
 * 不一致就非 0 退出：这条差分是 Java 线的闸门，不是自检打印。
 */
public final class PinyinDiffer {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("x [ERROR] 用法 | PinyinDiffer <词典目录> <cases.jsonl.gz>");
            System.exit(2);
        }
        Path dir = Paths.get(args[0]);
        PinyinConverter converter = PinyinConverter.load(dir);
        System.out.println("- [INFO] 词典载入 | single " + converter.singleEntries()
                + " 条 / phrase " + converter.phraseEntries() + " 条");

        int total = 0;
        int bad = 0;
        List<String> shown = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(args[1])), Charset.forName("UTF-8")));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                Map<String, Object> record = MiniJson.parseObject(line);
                String input = (String) record.get("in");
                String tag = (String) record.get("tag");
                @SuppressWarnings("unchecked")
                List<String> expected = (List<String>) record.get("exp");
                List<String> actual = converter.lazy(input);
                total++;
                if (!expected.equals(actual)) {
                    bad++;
                    if (shown.size() < 12) {
                        shown.add("     差异[" + tag + "] in=" + ascii(input)
                                + "\n       期望=" + ascii(expected)
                                + "\n       实际=" + ascii(actual));
                    }
                }
            }
        } finally {
            reader.close();
        }

        System.out.println("- [INFO] 分支命中 | " + converter.branchCounts());
        if (bad > 0) {
            System.out.println("x [ERROR] 拼音差分 | 不一致 " + bad + " / " + total + " 条");
            for (String s : shown) {
                System.out.println(s);
            }
            System.exit(1);
        }
        System.out.println("+ [OK] 拼音差分 | " + total + " 条全部一致，表名与字段名的转写等价成立");
    }

    static String ascii(Object value) {
        StringBuilder sb = new StringBuilder();
        for (char ch : String.valueOf(value).toCharArray()) {
            if (ch >= 0x20 && ch < 0x7F) {
                if (ch == '"') {
                    sb.append("\\\"");
                } else {
                    sb.append(ch);
                }
            } else {
                sb.append(String.format("\\u%04x", Integer.valueOf(ch)));
            }
        }
        return sb.toString();
    }

    /** 只够解析语料文件格式的对象/字符串数组，不引第三方 JSON 库。 */
    static final class MiniJson {
        private final String s;
        private int i;

        MiniJson(String text) {
            s = text;
        }

        static Map<String, Object> parseObject(String text) {
            MiniJson p = new MiniJson(text);
            p.ws();
            p.expect('{');
            java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<String, Object>();
            p.ws();
            if (p.peek() == '}') {
                p.i++;
                return out;
            }
            while (true) {
                p.ws();
                String key = p.readString();
                p.ws();
                p.expect(':');
                Object value = p.readValue();
                out.put(key, value);
                p.ws();
                char c = p.next();
                if (c == '}') {
                    return out;
                }
                if (c != ',') {
                    throw new IllegalStateException("期望 , 或 } 于 " + p.i + " : " + text);
                }
            }
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private char peek() {
            return s.charAt(i);
        }

        private char next() {
            return s.charAt(i++);
        }

        private void expect(char c) {
            if (next() != c) {
                throw new IllegalStateException("期望 " + c + " 于 " + (i - 1));
            }
        }

        private Object readValue() {
            ws();
            char c = peek();
            if (c == '"') {
                return readString();
            }
            if (c == '[') {
                i++;
                List<String> list = new ArrayList<String>();
                ws();
                if (peek() == ']') {
                    i++;
                    return list;
                }
                while (true) {
                    list.add(readString());
                    ws();
                    char sep = next();
                    if (sep == ']') {
                        return list;
                    }
                    if (sep != ',') {
                        throw new IllegalStateException("期望 , 或 ] 于 " + (i - 1));
                    }
                }
            }
            throw new IllegalStateException("不支持的值类型 '" + c + "' 于 " + i);
        }

        private String readString() {
            ws();
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = next();
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        throw new IllegalStateException("未知转义 \\" + e + " 于 " + (i - 1));
                }
            }
        }
    }
}
