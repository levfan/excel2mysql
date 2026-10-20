package com.excel2mysql.core;

import com.excel2mysql.pinyin.PinyinConverter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    // 括号里的版本号不是表名：V2 / v2 / V2_1 都回退成完整文件名转拼音。
    // 只认「单个 V + 数字（可带下划线数字段）」，order2、A1 这类仍是正常英文名
    private static final Pattern TABLE_VER = Pattern.compile("[Vv][0-9]+(?:_[0-9]+)*");

    // 表头里的成对括号注释（发布日期（yyyy-MM-dd）、金额（元）、是否已婚【是/否】）。
    // 括号一律写成码位转义：全角（）与半角() 在源码里看着一模一样，
    // 两边各数一遍必漏一个，Python 侧就是这么静默漏剥过一轮的。
    private static final String ANNOT_OPEN =
            "\\(\\[\uff08\uff3b\uff5b{}\u3010\u3016\u3014\u3008\u300a\u300c\u300e";
    private static final String ANNOT_CLOSE =
            "\\)\\]\uff09\uff3d\uff5d{}\u3011\u3017\u3015\u3009\u300b\u300d\u300f";
    private static final Pattern ANNOT_SPAN = Pattern.compile(
            "[" + ANNOT_OPEN + "][^" + ANNOT_CLOSE + "]*[" + ANNOT_CLOSE + "]");
    // 嵌套剥完剩下的孤立括号符（只可能来自手写配不对的表头）
    private static final Pattern ANNOT_CHAR =
            Pattern.compile("[" + ANNOT_OPEN + ANNOT_CLOSE + "]");
    // 左括号写了、右括号忘配（发布日期（yyyy-MM-dd））：从它起到行尾整段当注释丢
    private static final Pattern ANNOT_OPEN_TAIL =
            Pattern.compile("[" + ANNOT_OPEN + "].*$", Pattern.DOTALL);
    private static final Pattern HANJI = Pattern.compile("[\u4e00-\u9fff]");
    // 日期/时间格式掩码串（yyyy-MM-dd、YYYYMMDD、yy/MM/dd、HH:mm:ss）。
    // 两头不许贴字母数字，否则 1.0mm 里的 mm 会被当掩码吃掉；首段必须字母掩码符起头，
    // 所以 2020-01-01 这种真日期不会被误剥
    private static final Pattern MASK_SEG = Pattern.compile(
            "(?<![0-9A-Za-z])[yYmMdDhHsS]{2,}(?:[-/\u2013_.:\uff1a][0-9yYmMdDhHsS]+)*(?![0-9A-Za-z])");
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
    // 用户开关「表名与字段名取拼音首字母」：默认 false = 全拼（现有行为）。
    // 放在实例上而不是方法参数上，GUI 复选框与 CLI 开关各设一次即可贯穿整轮解析
    private boolean initialsOnly;
    // 用户开关「表名与字段名转大写」：默认 false = 小写（现有行为）。
    // 与 initialsOnly 一样跟着 Names 实例走，一轮解析共用一套命名口径
    private boolean upperOnly;

    public Names(PinyinConverter pinyin) {
        this.pinyin = pinyin;
    }

    public void setInitialsOnly(boolean b) {
        this.initialsOnly = b;
    }

    public boolean isInitialsOnly() {
        return initialsOnly;
    }

    public void setUpperOnly(boolean b) {
        this.upperOnly = b;
    }

    public boolean isUpperOnly() {
        return upperOnly;
    }

    private String applyCase(String name) {
        return upperOnly ? name.toUpperCase(Locale.ROOT) : name;
    }

    /** 对齐 Python str.strip()：Java 的 trim() 只裁 &lt;= U+0020，会把全角空格漏在名字里。 */
    public static String pyStrip(String s) {
        return WS_EDGE.matcher(s).replaceAll("");
    }

    /** 文件名 -> (表名, 表注释)。括号里是合法英文标识符（版本号除外）才采用，否则整名转拼音。 */
    public String[] parseTableNameAndComment(String filename) {
        String stem = stripExtension(baseName(filename));
        Matcher m = PAREN_TABLE.matcher(stem);
        if (m.find()) {
            String raw = pyStrip(m.group(1));
            String head = pyStrip(stem.substring(0, m.start()));
            String base = head.isEmpty() ? stem : head;
            // (V2) 这类版本号不是表名，与纯数字序号一样回退；
            // 回退后版本段仍留在拼音名末尾，不同版本不会塌成同名加 _2
            if (TABLE_EN.matcher(raw).matches() && hasAsciiAlpha(raw)
                    && !TABLE_VER.matcher(raw).matches()) {
                // 括号里是使用者显式写下的英文名，首字母开关不管它，大小写开关要管
                String cased = upperOnly ? raw.toUpperCase(Locale.ROOT)
                        : raw.toLowerCase(Locale.ROOT);
                return new String[]{cut(cased, TABLE_MAX_LEN), base};
            }
            return new String[]{sanitizeTableName(stem), stem};
        }
        return new String[]{sanitizeTableName(stem), stem};
    }

    public String sanitizeTableName(String text) {
        String name = cnToTableName(text, TABLE_MAX_LEN);
        return name.isEmpty() ? (upperOnly ? "T_IMPORT_DATA" : "t_import_data") : name;
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
        return applyCase(fitName(syllables, limit, "t_", initialsOnly));
    }

    /** 表头 -> 蛇形字段名；空表头与无可识别字符的表头都退化成 col。 */
    public String colToField(Object cn) {
        return colToField(cn, FIELD_MAX_LEN);
    }

    /**
     * 表头 -> 剥掉注释后的文本，口径与 Python _strip_header_annotations 逐字对齐：
     * 成对括号整段去掉，嵌套最多三轮；表头含汉字时再剥"没配右半边的左括号起至行尾"
     * 与日期时间格式掩码串；最后删掉配不对的孤立括号符。纯英文表头不参与掩码猜测。
     */
    public static String stripHeaderAnnotations(String text) {
        String s = text == null ? "" : text;
        for (int i = 0; i < 3; i++) {
            String out = ANNOT_SPAN.matcher(s).replaceAll("");
            if (out.equals(s)) {
                break;
            }
            s = out;
        }
        if (!HANJI.matcher(s).find()) {
            // 纯英文表头里 MM / dd 可能就是名字本身（列号、月份列），不跟着猜
            return ANNOT_CHAR.matcher(s).replaceAll("");
        }
        s = ANNOT_OPEN_TAIL.matcher(s).replaceAll("");
        s = MASK_SEG.matcher(s).replaceAll("");
        return ANNOT_CHAR.matcher(s).replaceAll("");
    }

    public String colToField(Object cn, int limit) {
        if (cn == null || pyStrip(String.valueOf(cn)).isEmpty()) {
            return upperOnly ? "COL" : "col";
        }
        String cleaned = stripHeaderAnnotations(String.valueOf(cn));
        if (pyStrip(cleaned).isEmpty()) {
            cleaned = String.valueOf(cn);     // 整格就是括号（（备注说明））：全剥会退化成 col
        }
        List<Syllable> syllables = syllables(cleaned);
        if (syllables.isEmpty()) {
            return upperOnly ? "COL" : "col";
        }
        return applyCase(fitName(syllables, limit, "f_", initialsOnly));
    }

    /**
     * 同名字段靠 _2/_3 去重；计数状态由调用方那份 Map 带着走（一张表一份）。
     * 只留这一处实现：读取层与 verify 侧的命名差分都得走它，各写一遍就会一边去重一边不去
     * （表头注释剥完之后，「发布日期（yyyy-MM-dd）」与「发布日期」就是同名）。
     */
    public static String dedupField(Map<String, Integer> seen, String field) {
        Integer prev = seen.get(field);
        if (prev == null) {
            seen.put(field, Integer.valueOf(1));
            return field;
        }
        int n = prev.intValue() + 1;
        seen.put(field, Integer.valueOf(n));
        return field + "_" + n;
    }

    /** 新建一份去重状态（一张表一份）。 */
    public static Map<String, Integer> newDedupState() {
        return new LinkedHashMap<String, Integer>();
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
        return fitName(syllables, limit, digitPrefix, false);
    }

    /**
     * initials=true 是用户开关选的「取中文首字母」：汉字音节一律取首字母
     * （与装不下时的降级形式同一套紧凑写法），英文/数字段照旧整段保留。
     */
    public static String fitName(List<Syllable> syllables, int limit, String digitPrefix,
                                 boolean initials) {
        boolean hasCn = false;
        for (Syllable s : syllables) {
            if (s.fromHanzi) {
                hasCn = true;
                break;
            }
        }
        String name;
        if (initials && hasCn) {
            StringBuilder b = new StringBuilder();
            for (Syllable s : syllables) {
                b.append(s.fromHanzi ? s.text.substring(0, 1) : s.text);
            }
            name = b.toString();
        } else {
            name = fit(joinPieces(syllables), limit);
            if (hasCn && join(syllables).length() > limit) {
                StringBuilder initialsB = new StringBuilder();
                for (Syllable s : syllables) {
                    initialsB.append(s.fromHanzi ? s.text.substring(0, 1) : s.text);
                }
                name = cut(initialsB.toString(), limit);
            }
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
