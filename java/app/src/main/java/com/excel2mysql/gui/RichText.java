package com.excel2mysql.gui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JTextPane;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * 按角色给一段纯文本上色的公共件：示例介绍块与 DDL 预览都走这里。
 *
 * 不引 HTML：Swing 的 HTML 渲染器是 3.2 那一套，圆角与 padding 都不认，同一份 HTML
 * 两栈也会画出两种样子。这里只设字符属性与段前距，**文本内容逐字不变**，
 * 用例另钉一条"读回来的纯文本必须等于原文"，防止上了色顺手把内容也改了。
 *
 * 与 Python 侧 main.py 的 rich_format / paint_styled / intro_spans / ddl_spans 同构；
 * 两栈是否切得一样由对撞键 intro.rich 与 ddl.rich 判（读的是真文档，不是抄的常量）。
 */
public final class RichText {

    /** 角色编号，与 Python 侧 spans 里的 title/label/... 一一对应。 */
    public static final int ROLE_TITLE = 0;
    public static final int ROLE_LABEL = 1;
    public static final int ROLE_VALUE = 2;
    public static final int ROLE_CHIP = 3;
    public static final int ROLE_IDENT = 4;
    public static final int ROLE_COMMENT = 5;
    public static final int ROLE_TYPE = 6;
    public static final int ROLE_KEYWORD = 7;
    public static final int ROLE_DANGER = 8;

    /** 配色全部取自既有色板：主按钮蓝、目录分组蓝、日志告警橙，不新造色值。 */
    public static final Color TITLE_FG = new Color(0x2f, 0x6f, 0xeb);
    public static final Color LABEL_FG = new Color(0x1f, 0x5f, 0xd0);
    public static final Color CHIP_FG = Color.WHITE;
    public static final Color CHIP_BG = new Color(0x1f, 0x5f, 0xd0);
    /** 关键字那一档：与正文同族但退到背景，读 DDL 时先看字段与注释、不看 CREATE / DEFAULT。 */
    public static final Color KEYWORD_FG = new Color(0x9a, 0xa7, 0xb8);
    /** DROP 那一行：整段预览里唯一会把数据真删掉的动作，用日志告警那一档橙。 */
    public static final Color DANGER_FG = new Color(0xb2, 0x6a, 0x00);

    private static final Set<String> DDL_KEYWORDS = new HashSet<String>(Arrays.asList(
            "CREATE", "TABLE", "PRIMARY", "KEY", "NOT", "NULL", "DEFAULT",
            "ENGINE", "CHARSET", "COLLATE", "IF", "EXISTS", "DROP",
            "UNIQUE", "COMMENT", "AUTO_INCREMENT", "USING", "INNODB", "UTF8MB4"));
    private static final Set<String> DDL_TYPES = new HashSet<String>(Arrays.asList(
            "VARCHAR", "CHAR", "DECIMAL", "NUMERIC", "INT", "INTEGER", "BIGINT",
            "TINYINT", "SMALLINT", "MEDIUMINT", "FLOAT", "DOUBLE", "DATE",
            "DATETIME", "TIMESTAMP", "TIME", "YEAR", "TEXT", "TINYTEXT",
            "MEDIUMTEXT", "LONGTEXT", "BLOB", "JSON", "BIT", "UNSIGNED", "BINARY"));
    /** 反引号名与单引号串排在裸词前面，注释里出现的 NULL 之类不会被误当成关键字。 */
    private static final Pattern DDL_TOKEN = Pattern.compile(
            "`[^`]*`|'(?:[^']|'')*'|[A-Za-z_][A-Za-z0-9_]*");

    private RichText() {
    }

    /**
     * 换行归一成 \n：两栈的文档里段界只占一个字符。
     *
     * 脚本正文是从磁盘读进来的，CRLF 那份会让文档比原文短一截——角色段是按原文算的
     * 偏移，插进文档就整体错一格（第一行还是对的，越往后越歪）。
     * 与 Python 侧 main.py 的 rich_text 同一条。
     */
    public static String normalizeNewlines(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    /**
     * 介绍那五行的切分：第一行整行是标题；其余每行冒号（全角）之前是字段名，
     * 之后是值；「关键词：」后面那一段单独成块。
     */
    public static List<int[]> introSpans(String text) {
        text = normalizeNewlines(text);
        List<int[]> spans = new ArrayList<int[]>();
        int pos = 0;
        boolean first = true;
        for (String line : text.split("\n", -1)) {
            int end = pos + line.length();
            if (first) {
                spans.add(new int[]{pos, end, ROLE_TITLE});
            } else {
                int cut = line.indexOf('：');
                if (cut < 0) {
                    spans.add(new int[]{pos, end, ROLE_VALUE});
                } else {
                    spans.add(new int[]{pos, pos + cut + 1, ROLE_LABEL});
                    spans.add(new int[]{pos + cut + 1, end,
                            line.startsWith("关键词：") ? ROLE_CHIP : ROLE_VALUE});
                }
            }
            first = false;
            pos = end + 1;
        }
        return spans;
    }

    /**
     * 一句 DDL 的角色切分：按"一行一字段"的形状认，不跑完整 SQL 语法。
     *
     * `DROP TABLE` 那一行整行标警示色——整段预览里只有它会把数据真删掉，它该跳出来。
     * 与 Python 侧 main.py 的 ddl_spans 同一条规则，两栈切得一样由对撞键 ddl.rich 判。
     */
    public static List<int[]> ddlSpans(String text) {
        text = normalizeNewlines(text);
        List<int[]> spans = new ArrayList<int[]>();
        int pos = 0;
        for (String line : text.split("\n", -1)) {
            if (line.trim().toUpperCase(java.util.Locale.ROOT).startsWith("DROP TABLE")) {
                spans.add(new int[]{pos, pos + line.length(), ROLE_DANGER});
            } else {
                Matcher mk = DDL_TOKEN.matcher(line);
                while (mk.find()) {
                    String w = mk.group();
                    int role;
                    if (w.startsWith("`")) {
                        role = ROLE_IDENT;
                    } else if (w.startsWith("'")) {
                        role = ROLE_COMMENT;
                    } else {
                        String up = w.toUpperCase(java.util.Locale.ROOT);
                        if (DDL_TYPES.contains(up)) {
                            role = ROLE_TYPE;
                        } else if (DDL_KEYWORDS.contains(up)) {
                            role = ROLE_KEYWORD;
                        } else {
                            continue;
                        }
                    }
                    spans.add(new int[]{pos + mk.start(), pos + mk.end(), role});
                }
            }
            pos += line.length() + 1;
        }
        return spans;
    }

    /**
     * 按角色上色后写进控件：先铺一层值段，再逐段覆盖，最后统一设段前距。
     *
     * 字体族与字号每段都显式写：JTextPane 默认样式里的字体是从 UIManager 取的，
     * 不写就会跟控件自己的字体（Tables.font() 那套 DPI 折算档）不是一回事。
     *
     * @param blockSpacing 段前距（物理像素），0 表示不动行距
     */
    public static void paint(JTextPane pane, String text, List<int[]> spans,
                             int blockSpacing) {
        text = normalizeNewlines(text);
        final java.awt.Font base = pane.getFont();
        StyledDocument doc = pane.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException e) {
            return;
        }
        SimpleAttributeSet plain = attrs(base, base.getSize(), pane.getForeground(), null,
                false);
        try {
            doc.insertString(0, text, plain);
        } catch (BadLocationException e) {
            return;
        }
        for (int[] sp : spans) {
            if (sp[0] >= sp[1]) {
                continue;
            }
            doc.setCharacterAttributes(sp[0], sp[1] - sp[0],
                    attrsFor(sp[2], base, pane.getForeground()), true);
        }
        if (blockSpacing > 0) {
            SimpleAttributeSet para = new SimpleAttributeSet();
            StyleConstants.setSpaceBelow(para, blockSpacing);
            Element root = doc.getDefaultRootElement();
            for (int i = 0; i < root.getElementCount(); i++) {
                Element b = root.getElement(i);
                doc.setParagraphAttributes(b.getStartOffset(),
                        Math.max(1, b.getEndOffset() - b.getStartOffset()), para, false);
            }
        }
    }

    /** 角色 → 字符属性。与 Python 侧 intro_formats / ddl_formats 那张表一一对应。 */
    private static SimpleAttributeSet attrsFor(int role, java.awt.Font base, Color def) {
        if (role == ROLE_TITLE) {
            return attrs(base, base.getSize() + 1, TITLE_FG, null, true);
        }
        if (role == ROLE_LABEL) {
            return attrs(base, base.getSize(), LABEL_FG, null, true);
        }
        if (role == ROLE_CHIP) {
            return attrs(base, base.getSize(), CHIP_FG, CHIP_BG, false);
        }
        if (role == ROLE_IDENT) {
            return attrs(base, base.getSize(), LABEL_FG, null, false);
        }
        if (role == ROLE_COMMENT) {
            return attrs(base, base.getSize(), TITLE_FG, null, false);
        }
        if (role == ROLE_TYPE) {
            return attrs(base, base.getSize(), def, null, true);
        }
        if (role == ROLE_KEYWORD) {
            return attrs(base, base.getSize(), KEYWORD_FG, null, false);
        }
        if (role == ROLE_DANGER) {
            return attrs(base, base.getSize(), DANGER_FG, null, true);
        }
        return attrs(base, base.getSize(), def, null, false);
    }

    private static SimpleAttributeSet attrs(java.awt.Font base, int size, Color fg,
                                            Color bg, boolean bold) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setFontFamily(a, base.getFamily());
        StyleConstants.setFontSize(a, size);
        StyleConstants.setBold(a, bold);
        StyleConstants.setForeground(a, fg);
        if (bg != null) {
            StyleConstants.setBackground(a, bg);
        }
        return a;
    }

    /**
     * 读回控件里的文本，段界一律按 \n 给回去。
     *
     * Swing 的 getText() 把段界那个字符展开成 \r\n（JTextComponent 的规矩），Qt 的
     * toPlainText() 就是 \n——不归一，"纯文本一字未改"这条判据与对撞键会因为几行之间
     * 那几个换行各多一个 \r 而假红。
     */
    public static String textOf(JTextPane pane) {
        String t = pane.getText();
        return t == null ? "" : t.replace("\r\n", "\n");
    }

    /**
     * 从真文档里读回角色分布，形如 {@code T12|L3|V23|L4|K18}。
     *
     * 判角色靠颜色不靠记忆：这段串是"屏幕上现在到底哪一段是什么色"，
     * 所以两栈切分规则一旦不一致，对撞就会红，而不是两边各自读常量绿着。
     * 换行符整个跳过，与 Python 侧 main.py 的 styled_signature 同一条读法：段界那个字符
     * 没有字形，Qt 读回来是下一段那一档、Swing 读回来是上一段那一档，计进去两边永远差一格。
     */
    public static String roleSignature(JTextPane pane) {
        StyledDocument doc = pane.getStyledDocument();
        String text = textOf(pane);
        int len = text.length();
        if (len <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        char prev = '?';
        int run = 0;
        for (int i = 0; i < len; i++) {
            if (text.charAt(i) == '\n') {
                continue;
            }
            char role = roleAt(doc, i, pane);
            if (role == prev) {
                run++;
            } else {
                if (prev != '?') {
                    sb.append(prev).append(run).append('|');
                }
                prev = role;
                run = 1;
            }
        }
        sb.append(prev).append(run);
        return sb.toString();
    }

    private static char roleAt(StyledDocument doc, int offset, JTextPane pane) {
        Element run = doc.getCharacterElement(offset);
        AttributeSet attrs = run.getAttributes();
        Color bg = StyleConstants.getBackground(attrs);
        if (bg != null && near(bg, CHIP_BG)) {
            return 'K';
        }
        Color fg = StyleConstants.getForeground(attrs);
        if (fg == null) {
            fg = pane.getForeground();
        }
        if (near(fg, TITLE_FG)) {
            return 'T';
        }
        if (near(fg, LABEL_FG)) {
            return 'L';
        }
        if (near(fg, KEYWORD_FG)) {
            return 'W';
        }
        if (near(fg, DANGER_FG)) {
            return 'D';
        }
        // 类型那一档不加颜色、只加粗，判序必须排在四支色后面：
        // DROP 那行既加粗又是橙色，归错档两栈就会一格都对不上
        if (StyleConstants.isBold(attrs)) {
            return 'B';
        }
        return 'V';
    }

    private static boolean near(Color a, Color b) {
        return a != null && b != null && Math.abs(a.getRed() - b.getRed()) <= 18
                && Math.abs(a.getGreen() - b.getGreen()) <= 18
                && Math.abs(a.getBlue() - b.getBlue()) <= 18;
    }
}
