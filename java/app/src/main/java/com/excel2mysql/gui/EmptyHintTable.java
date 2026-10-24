package com.excel2mysql.gui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

import javax.swing.JTable;
import javax.swing.table.TableModel;

/**
 * 清单为空时在表格中央画一个"拖进来"的图标和一句引导语。
 *
 * 对应 main.py 的 EmptyHintTable。画在表格自身上而不是叠一个覆盖控件：
 * 覆盖控件会吃掉滚轮与右键，表格一空就只剩这层装饰可点，反而更迷惑。
 */
public class EmptyHintTable extends JTable {

    /** 三张清单的空态文案：与上方虚线区说同一句话，指的就是那块区域。 */
    public static final String FILE_HINT = "请把 Excel 文件或 ZIP 压缩包拖到上方虚线区";
    public static final String SQL_HINT = "请把 SQL 脚本拖到上方虚线区";
    public static final String RUN_HINT = "请把 SQL 文件拖到上方虚线区，按清单顺序执行";

    private static final Color HINT_COLOR = new Color(0x8a, 0x97, 0xa8);
    private static final Color ICON_COLOR = new Color(0xae, 0xbd, 0xd1);

    private String hint;

    public EmptyHintTable(TableModel model, String hint) {
        super(model);
        this.hint = hint;
    }

    public String emptyHint() {
        return hint == null ? "" : hint;
    }

    public void setEmptyHint(String text) {
        hint = text;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (getRowCount() > 0 || hint == null || hint.isEmpty()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            Font font = getFont().deriveFont(Font.PLAIN, (float) UiScale.fontPt(11));
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            int icon = UiScale.px(44);
            int gap = UiScale.px(10);
            int total = icon + gap + fm.getHeight();
            int top = Math.max(UiScale.px(8), (getHeight() - total) / 2);
            int cx = getWidth() / 2;
            paintIcon(g2, cx, top, icon);
            g2.setColor(HINT_COLOR);
            int textWidth = fm.stringWidth(hint);
            g2.drawString(hint, (getWidth() - textWidth) / 2,
                    top + icon + gap + fm.getAscent());
        } finally {
            g2.dispose();
        }
    }

    private static void paintIcon(Graphics2D g2, int cx, int top, int icon) {
        g2.setColor(ICON_COLOR);
        g2.setStroke(new java.awt.BasicStroke(UiScale.px(2),
                java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        int half = icon / 2;
        int trayTop = top + (int) (icon * 0.42D);
        int trayBottom = top + icon;
        int radius = Math.max(1, UiScale.px(6));
        Path2D.Double tray = new Path2D.Double();
        tray.moveTo(cx - half, trayTop);
        tray.lineTo(cx - half, trayBottom - radius);
        tray.quadTo(cx - half, trayBottom, cx - half + radius, trayBottom);
        tray.lineTo(cx + half - radius, trayBottom);
        tray.quadTo(cx + half, trayBottom, cx + half, trayBottom - radius);
        tray.lineTo(cx + half, trayTop);
        g2.draw(tray);
        int arrowTip = trayTop - UiScale.px(4);
        int wing = UiScale.px(7);
        g2.drawLine(cx, top + UiScale.px(2), cx, arrowTip);
        g2.drawLine(cx - wing, arrowTip - wing, cx, arrowTip);
        g2.drawLine(cx + wing, arrowTip - wing, cx, arrowTip);
    }
}
