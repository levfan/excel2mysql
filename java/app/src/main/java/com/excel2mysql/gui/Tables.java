package com.excel2mysql.gui;

import java.awt.Font;
import java.awt.FontMetrics;

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
