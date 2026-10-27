package com.excel2mysql.gui;

import java.awt.Font;
import java.awt.FontMetrics;

import javax.swing.BorderFactory;

/**
 * 表格字体与行高：内容与表头同一档，且必须比日志大一档。
 *
 * 表格取数据网格的常规档（11pt，与 Excel 同级），比日志大一档；
 * 18pt 是上一轮"内容要比日志大"的过头做法，一屏装不下几行。
 * 行高按字体量出来，不写死像素——写死的数在 144dpi 上会把字裁掉一半。
 * Python 侧同一口径在 main.py 的 style_table_font / TABLE_FONT_SIZE。
 */
public final class Tables {

    /**
     * 设计稿字号（pt）。2026-10-23 定的口径是"内容比日志大一档"：
     * 表格 11、日志 10（见 LogPanel.DEFAULT_FONT_SIZE），两边同一组数，
     * 由对撞键 font.table.pt / font.log.pt 核同值，自检另断"表格 &gt; 日志"。
     */
    public static final int POINT_SIZE = 11;

    /**
     * 每张表格左上角那一句标识（标题边框）。用户口径：每个表格都要有标识，
     * 第一页的「待导入文件」就是样子——标题里顺带写明这一张表怎么操作。
     * 与 Python 侧 main.py 的 FILE_TABLE_TITLE / SQL_TABLE_TITLE / COLUMN_TABLE_TITLE 同句，
     * 对撞器读的是真控件上的标题（不是两边各抄一份常量）。
     */
    public static final String FILE_TABLE_TITLE =
            "待导入文件（双击表名或表注释编辑，双击其他列改字段）";
    public static final String SQL_TABLE_TITLE =
            "待处理的脚本表（双击表名或表注释编辑，双击其他列看元数据）";
    public static final String COLUMN_TABLE_TITLE = "字段清单";
    /** 第三页「SQL 执行器」的脚本队列：标题只说按顺序执行，粒度看日志与按钮提示。 */
    public static final String RUN_TABLE_TITLE =
            "待执行脚本（按清单顺序执行）";

    /** 给一个容器套上"表格标识"标题边框，边距与第一页同一套。 */
    public static javax.swing.JComponent titled(javax.swing.JComponent box, String title) {
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(
                        UiScale.px(2), UiScale.px(6), UiScale.px(4), UiScale.px(6))));
        return box;
    }

    /**
     * 往上找到那层带标题边框的容器，读它的标题；没有就返回空串。
     *
     * 一路走的是 java.awt.Container：最上面那一层是 JDialog/JFrame，它们不是 JComponent，
     * 按 JComponent 走会在到顶时抛 ClassCastException。
     */
    public static String titledAncestor(java.awt.Component c) {
        java.awt.Component cur = c;
        while (cur != null) {
            if (cur instanceof javax.swing.JComponent) {
                String t = titledOf((javax.swing.JComponent) cur);
                if (!t.isEmpty()) {
                    return t;
                }
            }
            cur = cur.getParent();
        }
        return "";
    }

    /**
     * 读回一个容器上那层标题边框的文字（没有标题边框返回空串）。
     *
     * 对撞器要的"表格左上角的标识"必须是从真控件上读出来的，不能两边各抄一份常量——
     * 抄来的那份改了另一份不会红。titled() 是复合边框，所以要把标题那层找出来。
     */
    public static String titledOf(javax.swing.JComponent c) {
        javax.swing.border.Border b = c == null ? null : c.getBorder();
        if (b instanceof javax.swing.border.TitledBorder) {
            return String.valueOf(((javax.swing.border.TitledBorder) b).getTitle());
        }
        if (b instanceof javax.swing.border.CompoundBorder) {
            // JDK 8 的 CompoundBorder 只有 getBorderSide（外/内两层），不是 getBorderParts
            javax.swing.border.Border[] parts = new javax.swing.border.Border[]{
                    ((javax.swing.border.CompoundBorder) b).getOutsideBorder(),
                    ((javax.swing.border.CompoundBorder) b).getInsideBorder()};
            for (javax.swing.border.Border part : parts) {
                if (part instanceof javax.swing.border.TitledBorder) {
                    return String.valueOf(((javax.swing.border.TitledBorder) part).getTitle());
                }
            }
        }
        return "";
    }

    /**
     * 列宽 = 设计下限与"这一列真需要多宽"取大者。
     *
     * 只把宽度写死不够：超长字段名会被切进省略号，而切掉的那一截滚不到——
     * 要的是"宽度不够时能横向滚出来"，就得让列宽跟着内容长，表格比弹窗宽了，
     * 横向条才真能把尾巴带出来。Qt 侧同一算法在 main.py（sizeHintForColumn 取大者）。
     */
    public static javax.swing.JTable fitColumns(javax.swing.JTable table, int[] floors) {
        for (int c = 0; c < table.getColumnCount() && c < floors.length; c++) {
            table.getColumnModel().getColumn(c).setPreferredWidth(
                    Math.max(floors[c], contentWidth(table, c)));
        }
        return table;
    }

    /**
     * 这一列需要多宽：逐格问过真渲染器，取最宽的一格（表头也算），左右各留一点边距。
     *
     * 用 getCellRenderer + prepareRenderer 量，不自己按字符数估——中文字宽、
     * 加粗表头、不同 DPI 一估就偏。
     */
    public static int contentWidth(javax.swing.JTable table, int col) {
        int w = 0;
        javax.swing.table.TableColumn tc = table.getColumnModel().getColumn(col);
        javax.swing.table.JTableHeader header = table.getTableHeader();
        javax.swing.table.TableCellRenderer hr = tc.getHeaderRenderer() == null
                ? header.getDefaultRenderer() : tc.getHeaderRenderer();
        java.awt.Component hc = hr.getTableCellRendererComponent(
                table, tc.getHeaderValue(), false, false, -1, col);
        if (hc.getPreferredSize() != null) {
            w = hc.getPreferredSize().width + UiScale.px(12);
        }
        for (int r = 0; r < table.getRowCount(); r++) {
            java.awt.Component cell = table.prepareRenderer(
                    table.getCellRenderer(r, col), r, col);
            if (cell.getPreferredSize() != null) {
                w = Math.max(w, cell.getPreferredSize().width + UiScale.px(12));
            }
        }
        return w;
    }

    /** 这一列实际摆出来的宽度够不够装下内容（自检与取证读的是排完 layout 的真宽度）。 */
    public static boolean columnFitsContent(javax.swing.JTable table, int col) {
        return table.getColumnModel().getColumn(col).getWidth()
                >= contentWidth(table, col);
    }

    private Tables() {
    }

    public static Font font() {
        Font base = javax.swing.UIManager.getFont("Table.font");
        String family = base == null ? Font.SANS_SERIF : base.getFamily();
        return new Font(family, Font.PLAIN, UiScale.fontPt(POINT_SIZE));
    }

    /** 设内容字体、表头字体与行高；返回同一个表方便链式调用。 */
    public static javax.swing.JTable style(javax.swing.JTable table) {
        Font f = font();
        table.setFont(f);
        table.getTableHeader().setFont(f);
        FontMetrics fm = table.getFontMetrics(f);
        table.setRowHeight(fm.getHeight() + UiScale.px(6));
        return table;
    }

    /** 隔行底色（与 main.py 的 ZEBRA_ALT_BG 同值），逐行扫读时不容易串行。 */
    public static final java.awt.Color ZEBRA_ALT_BG = new java.awt.Color(0xf4, 0xf8, 0xfc);

    private static final String ZEBRA_KEY = "excel2mysql.zebra";

    /**
     * 给表格加斑马纹。Swing 没有 Qt 的 setAlternatingRowColors，只能包一层渲染器；
     * 已经按列挂过自定义渲染器的（SQL 页「文件」列的路径提示）也要一起包住，
     * 否则那一列会自己铺白底，条纹断在中间。
     */
    public static void zebra(javax.swing.JTable table) {
        if (Boolean.TRUE.equals(table.getClientProperty(ZEBRA_KEY))) {
            return;
        }
        javax.swing.table.TableCellRenderer base = table.getDefaultRenderer(Object.class);
        table.setDefaultRenderer(Object.class, new ZebraRenderer(base));
        for (int i = 0; i < table.getColumnCount(); i++) {
            javax.swing.table.TableColumn col = table.getColumnModel().getColumn(i);
            javax.swing.table.TableCellRenderer own = col.getCellRenderer();
            if (own != null && !(own instanceof ZebraRenderer)) {
                col.setCellRenderer(new ZebraRenderer(own));
            }
        }
        table.putClientProperty(ZEBRA_KEY, Boolean.TRUE);
    }

    /** 对撞器读的是真表格上的斑马纹开关，不是两边各抄一份常量。 */
    public static boolean isZebra(javax.swing.JTable table) {
        return Boolean.TRUE.equals(table.getClientProperty(ZEBRA_KEY));
    }

    private static final class ZebraRenderer implements javax.swing.table.TableCellRenderer {
        private final javax.swing.table.TableCellRenderer delegate;

        ZebraRenderer(javax.swing.table.TableCellRenderer delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.awt.Component getTableCellRendererComponent(
                javax.swing.JTable table, Object value, boolean selected,
                boolean focused, int row, int column) {
            java.awt.Component c = delegate.getTableCellRendererComponent(
                    table, value, selected, focused, row, column);
            if (!selected) {
                c.setBackground(row % 2 == 0 ? table.getBackground() : ZEBRA_ALT_BG);
            }
            return c;
        }
    }
}
