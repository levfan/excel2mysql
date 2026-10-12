package com.excel2mysql.core;

import com.excel2mysql.pinyin.PinyinConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * core/infer.py 的命名部分：文件名到表名、表头到字段名。
 * 规则逐条对平 Python，等价性由 verify 侧的金标准差分判定，不由本类自我声明。
 */
public final class Names {

    public static final int TABLE_MAX_LEN = 64;
    public static final int FIELD_MAX_LEN = 30;

    private static final Pattern PAREN_TABLE =
            Pattern.compile("[\\uff08(]\\s*([^\\uff08\\uff09()]*)\\s*[\\uff09)]\\s*$");
    private static final Pattern TABLE_EN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern NON_WORD =
            Pattern.compile("[^\\w]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern RUN =
            Pattern.compile("[A-Za-z0-9_]+|[\\u4e00-\\u9fff]+");
    private static final Pattern HANJI_HEAD = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern NOT_LOWER_OR_DIGIT = Pattern.compile("[^a-z0-9]");
    private static final Pattern WS_EDGE = Pattern.compile(
            "^[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]+"
          + "|[\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]+$");

    private final PinyinConverter pinyin;

    public Names(PinyinConverter pinyin) {
        this.pinyin = pinyin;
    }

    /** 对齐 Python str.strip()：Java 的 trim() 只裁 &lt;= U+0020，会把全角空格漏在名字里。 */
    public static String pyStrip(String s) {
        return WS_EDGE.matcher(s).replaceAll("");
    }

    /** 文件名 -> (表名, 表注释)。括号里是合法英文标识符才采用，否则整名转拼音。 */
    public String[] parseTableNameAndComment(String filename) {
        String stem = stripExtension(baseName(filename));
        Matcher m = PAREN_TABLE.matcher(stem);
        if (m.find()) {
            String raw = pyStrip(m.group(1));
            String head = pyStrip(stem.substring(0, m.start()));
            String base = head.isEmpty() ? stem : head;
            if (TABLE_EN.matcher(raw).matches() && hasAsciiAlpha(raw)) {
                String lower = raw.toLowerCase();
                return new String[]{cut(lower, TABLE_MAX_LEN), base};
            }
            return new String[]{sanitizeTableName(stem), stem};
        }
        return new String[]{sanitizeTableName(stem), stem};
    }

    public String sanitizeTableName(String text) {
        String name = cnToTableName(text, TABLE_MAX_LEN);
        return name.isEmpty() ? "t_import_data" : name;
    }

    public String cnToTableName(String text, int limit) {
        String t = pyStrip(text == null ? "" : String.valueOf(text));
        if (t.isEmpty()) {
            return "";
        }
        List<Syllable> syllables = syllables(t);
        if (syllables.isEmpty()) {
            return "";
        }
        return fitName(syllables, limit, "t_");
    }

    /** 表头 -> 蛇形字段名；空表头与无可识别字符的表头都退化成 col。 */
    public String colToField(Object cn) {
        return colToField(cn, FIELD_MAX_LEN);
    }

    public String colToField(Object cn, int limit) {
        if (cn == null || pyStrip(String.valueOf(cn)).isEmpty()) {
            return "col";
        }
        List<Syllable> syllables = syllables(String.valueOf(cn));
        if (syllables.isEmpty()) {
            return "col";
        }
        return fitName(syllables, limit, "f_");
    }

    /**
     * 文本切成音节序列：连续汉字段逐字/逐词出一个音节，英文数字段按下划线再切。
     * 注意有个不能"顺手修好"的边角——é 和假名这类属于 \w 但两个 findall 模式都不匹配的字符，
     * 会被整段丢弃，Python 侧就是这么表现的，改名等于改契约。
     */
    public List<Syllable> syllables(String text) {
        String norm = NON_WORD.matcher(pyStrip(String.valueOf(text)))
                .replaceAll("_");
        List<Syllable> out = new ArrayList<Syllable>();
        Matcher m = RUN.matcher(norm);
        while (m.find()) {
            String seg = m.group();
            if (HANJI_HEAD.matcher(seg).find()) {
                for (String p : pinyin.lazy(seg)) {
                    out.add(new Syllable(p, true));
                }
            } else {
                for (String p : seg.toLowerCase().split("_")) {
                    if (!p.isEmpty()) {
                        out.add(new Syllable(p, false));
                    }
                }
            }
        }
        List<Syllable> cleaned = new ArrayList<Syllable>();
        for (Syllable s : out) {
            String syl = NOT_LOWER_OR_DIGIT.matcher(s.text).replaceAll("");
            if (!syl.isEmpty()) {
                cleaned.add(new Syllable(syl, s.fromHanzi));
            }
        }
        return cleaned;
    }

    /**
     * 全拼优先；全拼放不下就整名降级为各音节首字母（首字母之间不加下划线）。
     * 数字/英文音节必须整段保留，否则 "1.0mm" 和 "100.0mm" 会压成同一个名字。
     */
    public static String fitName(List<Syllable> syllables, int limit, String digitPrefix) {
        boolean hasCn = false;
        for (Syllable s : syllables) {
            if (s.fromHanzi) {
                hasCn = true;
                break;
            }
        }
        String name = fit(joinPieces(syllables), limit);
        if (hasCn && join(syllables).length() > limit) {
            StringBuilder initials = new StringBuilder();
            for (Syllable s : syllables) {
                initials.append(s.fromHanzi ? s.text.substring(0, 1) : s.text);
            }
            name = cut(initials.toString(), limit);
        }
        if (!name.isEmpty() && Character.isDigit(name.charAt(0))) {
            name = digitPrefix + name;
        }
        return cut(name, limit);
    }

    private static List<String> joinPieces(List<Syllable> syllables) {
        List<String> pieces = new ArrayList<String>();
        for (Syllable s : syllables) {
            pieces.add(s.text);
        }
        return pieces;
    }

    private static String join(List<Syllable> syllables) {
        StringBuilder sb = new StringBuilder();
        for (Syllable s : syllables) {
            if (sb.length() > 0) {
                sb.append('_');
            }
            sb.append(s.text);
        }
        return sb.toString();
    }

    /** 逐个累加，一旦超限就停：超长的是"整个后续"，不是"最后一个音节的一半"。 */
    private static String fit(List<String> pieces, int limit) {
        String out = "";
        for (String piece : pieces) {
            String cand = out.isEmpty() ? piece : out + "_" + piece;
            if (cand.length() > limit) {
                break;
            }
            out = cand;
        }
        return cut(out, limit);
    }

    private static String cut(String s, int limit) {
        return s.length() <= limit ? s : s.substring(0, limit);
    }

    private static boolean hasAsciiAlpha(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return true;
            }
        }
        return false;
    }

    static String baseName(String path) {
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash < 0 ? path : p.substring(slash + 1);
    }

    /** 对齐 os.path.splitext：只切最后一个点，且点不能在开头、末尾或紧邻另一个点。 */
    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return name;
        }
        String ext = name.substring(dot);
        if (ext.indexOf('.', 1) >= 0) {
            return name;
        }
        return name.substring(0, dot);
    }

    /** 一个音节，外加"是不是从汉字来的"——降级成首字母时只有汉字音节参与。 */
    public static final class Syllable {
        public final String text;
        public final boolean fromHanzi;

        public Syllable(String text, boolean fromHanzi) {
            this.text = text;
            this.fromHanzi = fromHanzi;
        }
    }
}
