package com.excel2mysql.pinyin;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * lazy_pinyin 的 Java 复刻：严格模式正向最大匹配分词 + 整词优先选音 + NORMAL 去声调。
 * 规则来源是 pypinyin 0.55 的 seg/mmseg.py、seg/simpleseg.py、converter.py，
 * 等价性由 java/verify/golden/cases.jsonl.gz 全量语料判定，不由本类自我声明。
 */
public final class PinyinConverter {

    /** RE_HANS 的码点区间，取自词典导出时记录的 RE_HANS.pattern 原文。 */
    private static final int[][] HAN_RANGES = {
        {0x3007, 0x3007}, {0xE815, 0xE864}, {0xFA18, 0xFA18},
        {0x3400, 0x4DBF}, {0x4E00, 0x9FFF}, {0xF900, 0xFAFF},
        {0x20000, 0x2A6DF}, {0x2A703, 0x2B73F}, {0x2B740, 0x2B81D},
        {0x2B825, 0x2BF6E}, {0x2C029, 0x2CE93}, {0x2D016, 0x2D016},
        {0x2D11B, 0x2EBD9}, {0x2F80A, 0x2FA1F}, {0x30000, 0x3134A},
        {0x300F7, 0x31288}, {0x30EDD, 0x30EDE}, {0x31350, 0x32389},
    };

    private static final Map<Integer, Character> TONE_MAP = new HashMap<Integer, Character>();
    private static final Set<Integer> DROP_CODEPOINTS = new HashSet<Integer>();

    static {
        String[][] pairs = {
            {"a", "0101 00E1 01CE 00E0"},
            {"e", "0113 00E9 011B 00E8 00EA 1EBF 1EC1"},
            {"i", "012B 00ED 01D0 00EC"},
            {"o", "014D 00F3 01D2 00F2"},
            {"u", "016B 00FA 01D4 00F9"},
            {"v", "00FC 01D6 01D8 01DA 01DC"},
            {"n", "0144 0148 01F9"},
            {"m", "1E3F"},
        };
        for (String[] row : pairs) {
            char base = row[0].charAt(0);
            for (String hex : row[1].split(" ")) {
                TONE_MAP.put(Integer.valueOf(hex, 16), Character.valueOf(base));
            }
        }
        // 组合变音符：基字已单独处理，附加符直接丢弃
        String[] drops = {"0300", "0301", "0304", "030C", "0306", "0323", "303"};
        for (String hex : drops) {
            DROP_CODEPOINTS.add(Integer.valueOf(hex, 16));
        }
    }

    private final Map<Integer, String> single = new HashMap<Integer, String>();
    private final Map<String, List<String[]>> phrases = new HashMap<String, List<String[]>>();
    private final Set<String> prefixes = new HashSet<String>();
    private final Map<String, Integer> branches = new LinkedHashMap<String, Integer>();

    private PinyinConverter() {
    }

    public static PinyinConverter load(Path dir) throws IOException {
        PinyinConverter c = new PinyinConverter();
        Charset utf8 = Charset.forName("UTF-8");
        for (String line : Files.readAllLines(dir.resolve("single.txt"), utf8)) {
            if (line.isEmpty()) {
                continue;
            }
            int tab = line.indexOf('\t');
            int code = Integer.parseInt(line.substring(0, tab), 16);
            c.single.put(Integer.valueOf(code), line.substring(tab + 1));
        }
        BufferedReader r = Files.newBufferedReader(dir.resolve("phrase.txt"), utf8);
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                String phrase = line.substring(0, tab);
                List<String[]> positions = new ArrayList<String[]>();
                for (String position : line.substring(tab + 1).split(" ")) {
                    positions.add(position.split("\\+"));
                }
                c.phrases.put(phrase, positions);
                for (int i = 1; i <= phrase.codePointCount(0, phrase.length()); i++) {
                    c.prefixes.add(phrase.substring(0, offsetByCodePoints(phrase, i)));
                }
            }
        } finally {
            r.close();
        }
        return c;
    }

    private static int offsetByCodePoints(String s, int count) {
        return s.offsetByCodePoints(0, count);
    }

    public int singleEntries() {
        return single.size();
    }

    public int phraseEntries() {
        return phrases.size();
    }

    public Map<String, Integer> branchCounts() {
        return branches;
    }

    private void bump(String key) {
        Integer prev = branches.get(key);
        branches.put(key, prev == null ? Integer.valueOf(1) : Integer.valueOf(prev.intValue() + 1));
    }

    static boolean isHanCodePoint(int cp) {
        for (int[] range : HAN_RANGES) {
            if (cp >= range[0] && cp <= range[1]) {
                return true;
            }
        }
        return false;
    }

    /** 对应 Python 的 RE_HANS.match(x)：只锚定开头，不要求整串都是汉字。 */
    static boolean matchesHan(String text) {
        if (text.isEmpty()) {
            return false;
        }
        return isHanCodePoint(text.codePointAt(0));
    }

    static String normalize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            Character plain = TONE_MAP.get(Integer.valueOf(cp));
            if (plain != null) {
                sb.append(plain.charValue());
            } else if (!DROP_CODEPOINTS.contains(Integer.valueOf(cp))) {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }

    /** 复刻 seg/mmseg.py 的 Seg.cut，strict = no_non_phrases = true。B2 与 E 在该配置下结构性不可达。 */
    private List<String> cut(String text) {
        List<String> out = new ArrayList<String>();
        String remain = text;
        while (remain.length() > 0) {
            String matched = "";
            String lastValidWord = "";
            int lastValidEnd = 0;
            boolean broke = false;
            int len = remain.codePointCount(0, remain.length());
            for (int index = 1; index <= len; index++) {
                String word = remain.substring(0, offsetByCodePoints(remain, index));
                if (prefixes.contains(word)) {
                    matched = word;
                    if (phrases.containsKey(word)) {
                        lastValidWord = word;
                        lastValidEnd = word.length();
                    }
                } else {
                    if (lastValidWord.length() > 0) {
                        bump("A");
                        out.add(lastValidWord);
                        remain = remain.substring(lastValidEnd);
                    } else {
                        bump("B");
                        int first = remain.codePointAt(0);
                        out.add(remain.substring(0, Character.charCount(first)));
                        remain = remain.substring(Character.charCount(first));
                    }
                    broke = true;
                    break;
                }
            }
            if (!broke) {
                if (lastValidWord.length() > 0) {
                    bump("C");
                    out.add(lastValidWord);
                    remain = remain.substring(lastValidEnd);
                } else if (!phrases.containsKey(remain)) {
                    bump("D");
                    int i = 0;
                    while (i < remain.length()) {
                        int cp = remain.codePointAt(i);
                        int w = Character.charCount(cp);
                        out.add(remain.substring(i, i + w));
                        i += w;
                    }
                    return out;
                } else {
                    bump("E");
                    out.add(remain);
                    return out;
                }
            }
        }
        return out;
    }

    /** 复刻 seg/simpleseg.py 的 seg()：先按是否汉字切段，汉字段再进最大匹配。 */
    public List<String> seg(String text) {
        List<String> runs = new ArrayList<String>();
        StringBuilder s = new StringBuilder();
        Boolean han = null;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int w = Character.charCount(cp);
            boolean now = isHanCodePoint(cp);
            if (han == null) {
                han = Boolean.valueOf(now);
                s.appendCodePoint(cp);
            } else if (han.booleanValue() == now) {
                s.appendCodePoint(cp);
            } else {
                runs.add(s.toString());
                s = new StringBuilder();
                s.appendCodePoint(cp);
                han = Boolean.valueOf(now);
            }
            i += w;
        }
        if (s.length() > 0) {
            runs.add(s.toString());
        }

        List<String> ret = new ArrayList<String>();
        for (String run : runs) {
            if (!matchesHan(run)) {
                ret.add(run);
            } else if (!phrases.isEmpty()) {
                ret.addAll(cut(run));
            } else {
                ret.add(run);
            }
        }
        return ret;
    }

    /** 复刻 lazy_pinyin(heteronym=False, style=NORMAL)：每字只取首个读音。 */
    public List<String> lazy(String text) {
        List<String> out = new ArrayList<String>();
        for (String token : seg(text)) {
            if (!matchesHan(token)) {
                out.add(token);
                continue;
            }
            List<String[]> positions = phrases.get(token);
            if (positions != null) {
                int idx = 0;
                for (String[] candidates : positions) {
                    out.add(candidates.length > 0 ? normalize(candidates[0]) : "");
                    idx++;
                }
            } else {
                int j = 0;
                while (j < token.length()) {
                    int cp = token.codePointAt(j);
                    int w = Character.charCount(cp);
                    String raw = single.get(Integer.valueOf(cp));
                    out.add(raw == null ? token.substring(j, j + w)
                                        : normalize(raw.split(",")[0]));
                    j += w;
                }
            }
        }
        return out;
    }
}
