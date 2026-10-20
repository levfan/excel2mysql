package com.excel2mysql.core;

/**
 * 按"显示列"算宽度的那套规则，只给结果区表格用。
 *
 * 宽度写成显式区间表，不用 {@code Character.isWideFont} 之类的运行时判断：
 * 这条规则的另一端是 Python 侧 core/text_width.py，两边必须在任何机器上给出
 * 同一个宽度，否则同一个查询在两条线上排出不同的表。区间与那边逐条对齐，
 * 由金标准差分（SqlDiffer 的 table-render 分支）钉住。
 *
 * 弹窗折行用的是 {@link com.excel2mysql.gui.PopupText} 里那套，
 * 那边只要求两边对同一句折出同一个串，不参与跨语言金标准，所以没并到一起。
 */
public final class TextWidth {

    private static final int[][] WIDE_RANGES = {
            {0x1100, 0x115F},
            {0x2329, 0x232A},
            {0x2E80, 0x303E},
            {0x3041, 0x33FF},
            {0x3400, 0x4DBF},
            {0x4E00, 0x9FFF},
            {0xA000, 0xA4CF},
            {0xAC00, 0xD7A3},
            {0xF900, 0xFAFF},
            {0xFE10, 0xFE19},
            {0xFE30, 0xFE6F},
            {0xFF00, 0xFF60},
            {0xFFE0, 0xFFE6},
            {0x20000, 0x3FFFD},
    };

    private TextWidth() {
    }

    /** 一个码位占几列：宽字符（含全角）算 2，其余算 1。 */
    public static int displayCols(int codePoint) {
        for (int[] r : WIDE_RANGES) {
            if (codePoint >= r[0] && codePoint <= r[1]) {
                return 2;
            }
        }
        return 1;
    }

    /** 一段文字占几个显示列。 */
    public static int width(String s) {
        int n = 0;
        if (s == null) {
            return 0;
        }
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            n += displayCols(cp);
            i += Character.charCount(cp);
        }
        return n;
    }

    /** 截到 maxWidth 列以内，截过就补一个省略号（省略号本身占 1 列）。 */
    public static String clip(String s, int maxWidth) {
        if (s == null || width(s) <= maxWidth) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int budget = maxWidth - 1;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int c = displayCols(cp);
            if (used + c > budget) {
                break;
            }
            sb.appendCodePoint(cp);
            used += c;
            i += Character.charCount(cp);
        }
        return sb.append('…').toString();
    }

    /** 右侧补空格到 target 列。 */
    public static String pad(String s, int target) {
        String v = s == null ? "" : s;
        int gap = target - width(v);
        if (gap <= 0) {
            return v;
        }
        StringBuilder sb = new StringBuilder(v);
        for (int i = 0; i < gap; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
