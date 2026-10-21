package com.excel2mysql.gui;

/**
 * 弹窗正文的折行与限高。
 *
 * <p>Swing 的消息框把字符串交给 JLabel 画，一行 \\n 都折不不了：一条上百字符的
 * 产物目录能把弹窗撑到 1286 像素宽，比常见目标机的屏幕还宽，右边那半截就是看不见。
 * Qt 会自己折，但同一句话两边折出来的样子不一样，而且 30 行正文能把窗口撑到
 * 894 像素高——比屏幕高，底部直接被裁。
 *
 * <p>所以折行自己算。预算按最小的目标机 1024x768 定：72 显示列（全角记 2 列）
 * 约等于 36 个汉字的宽度，弹窗放大到 820x420 后长路径也能一行放下大半；24 行连
 * 标题栏和按钮仍在可用高度内。放不下的行不硬塞，换一句"其余见日志"——完整内容
 * 本来就写在底部日志里，硬塞只会把开头几行顶出屏幕。
 *
 * <p>这里的列宽规则与 Python 侧 {@code main._display_cols}（unicodedata 的 F/W 判宽）
 * 必须逐字符一致，两条线的界面才有得比；对撞器直接比折行后的整串文字。
 */
public final class PopupText {

    /** 一行最多几个显示列。 */
    public static final int WRAP_COLS = 72;

    /** 弹窗最多几行，超出的换成"其余见日志"。 */
    public static final int MAX_LINES = 24;

    private PopupText() {
    }

    /** 一行占几个显示列（守卫与自检都要能直接问）。 */
    public static int width(String line) {
        int n = 0;
        int i = 0;
        while (i < line.length()) {
            int cp = line.codePointAt(i);
            n += displayCols(cp);
            i += Character.charCount(cp);
        }
        return n;
    }

    /** 宽字符（含全角）算 2 列，其余算 1 列。 */
    static int displayCols(int codePoint) {
        return isWide(codePoint) ? 2 : 1;
    }

    private static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2329 && cp <= 0x232A)
                || (cp >= 0x2E80 && cp <= 0x303E)
                || (cp >= 0x3041 && cp <= 0x33FF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xA000 && cp <= 0xA4CF)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE10 && cp <= 0xFE19)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x20000 && cp <= 0x3FFFD);
    }

    /** 折行并在超高时截断，与 Python 线拿到同一个字符串（对撞与日志用这一份）。 */
    public static String wrap(String text) {
        java.util.List<String> lines = wrapLines(text);
        if (lines.size() <= MAX_LINES) {
            return join(lines);
        }
        java.util.List<String> kept = new java.util.ArrayList<String>(
                lines.subList(0, MAX_LINES - 1));
        kept.add("……（其余 " + (lines.size() - kept.size()) + " 行放不下，完整内容见底部日志）");
        return join(kept);
    }

    /**
     * 只折行、不砍行：Java 的结果弹窗有滚动区，长报错可以整篇留在框里翻，
     * 不再逼用户关掉弹窗去底部日志找。对撞仍用 {@link #wrap} 的截断版。
     */
    public static String wrapAll(String text) {
        return join(wrapLines(text));
    }

    private static java.util.List<String> wrapLines(String text) {
        java.util.List<String> lines = new java.util.ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        String[] segs = text.split("\n", -1);
        for (int s = 0; s < segs.length; s++) {
            String seg = segs[s];
            StringBuilder buf = new StringBuilder();
            int wide = 0;
            int i = 0;
            while (i < seg.length()) {
                int cp = seg.codePointAt(i);
                int w = displayCols(cp);
                if (buf.length() > 0 && wide + w > WRAP_COLS) {
                    lines.add(buf.toString());
                    buf.setLength(0);
                    wide = 0;
                }
                buf.appendCodePoint(cp);
                wide += w;
                i += Character.charCount(cp);
            }
            lines.add(buf.toString());
        }
        return lines;
    }

    private static String join(java.util.List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }
}
