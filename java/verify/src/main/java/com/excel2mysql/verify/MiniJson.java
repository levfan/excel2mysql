package com.excel2mysql.verify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 金标准文件用的极简 JSON 读取器：对象/数组/字符串/整数/小数/布尔/null，够用即可。
 * 不引第三方库，为的是差分器在只有 JDK 8 的机器上也能直接跑。
 */
public final class MiniJson {

    private final String s;
    private int i;

    private MiniJson(String text) {
        s = text;
    }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw new IllegalStateException("JSON 尾部有多余内容于 " + p.i);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        return (Map<String, Object>) parse(text);
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private char next() {
        return s.charAt(i++);
    }

    private Object value() {
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return number();
        }
    }

    private void expect(char c) {
        if (next() != c) {
            throw new IllegalStateException("期望 " + c + " 于 " + (i - 1));
        }
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) {
            throw new IllegalStateException("期望 " + word + " 于 " + i);
        }
        i += word.length();
    }

    private Map<String, Object> object() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        expect('{');
        ws();
        if (next() == '}') {
            return out;
        }
        i--;
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            out.put(key, value());
            ws();
            char c = next();
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw new IllegalStateException("期望 , 或 } 于 " + (i - 1));
            }
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<Object>();
        expect('[');
        ws();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return out;
        }
        while (true) {
            ws();
            out.add(value());
            ws();
            char c = next();
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                throw new IllegalStateException("期望 , 或 ] 于 " + (i - 1));
            }
        }
    }

    private Object number() {
        int start = i;
        if (i < s.length() && s.charAt(i) == '-') {
            i++;
        }
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || "+-.eE".indexOf(s.charAt(i)) >= 0)) {
            i++;
        }
        String raw = s.substring(start, i);
        if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
            try {
                return Long.valueOf(Long.parseLong(raw));
            } catch (NumberFormatException e) {
                    /* 超出 long 的整数交给字符串表示，比对时按字面比 */
                }
        }
        return raw;
    }

    private String string() {
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
