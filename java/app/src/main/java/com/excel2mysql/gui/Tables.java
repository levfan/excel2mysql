package com.excel2mysql.gui;

import java.awt.Font;
import java.awt.FontMetrics;

import javax.swing.BorderFactory;

/**
 * 表格字体与行高：内容与表头同一档，且不许比日志小。
 *
 * 日志区按用户的现场反馈放大到 16pt 之后，表格还吃着系统默认（9pt），
 * 一眼看上去就是"日志比内容还大"。这里把表格提到同一档；行高按字体量出来，
 * 不写死像素——写死的数在 144dpi 上会把字裁掉一半。
 * Python 侧同一口径在 main.py 的 style_table_font / TABLE_FONT_SIZE。
 */
public final class Tables {

    /** 设计稿字号（pt），与日志那一档一致；两条线的对应用例断"日志字号 ≤ 表格字号"。 */
    public static final int POINT_SIZE = LogPanel.DEFAULT_FONT_SIZE;

    /**
     * 每张表格左上角那一句标识（标题边框）。用户口径：每个表格都要有标识，
     * 第一页的「待导入文件」就是样子——标题里顺带写明这一张表怎么操作。
     * 与 Python 侧 main.py 的 FILE_TABLE_TITLE / SQL_TABLE_TITLE / COLUMN_TABLE_TITLE 同句，
     * 对撞器读的是真控件上的标题（不是两边各抄一份常量）。
     */
    public static final String FILE_TABLE_TITLE =
            "待导入文件（单击表名改名，双击其他列改字段）";
    public static final String SQL_TABLE_TITLE =
            "待处理的脚本表（双击表名或表注释编辑，双击其他列看元数据）";
    public static final String COLUMN_TABLE_TITLE = "字段清单";

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
}
