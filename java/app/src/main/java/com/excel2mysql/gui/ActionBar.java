package com.excel2mysql.gui;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;

import javax.swing.BorderFactory;
import javax.swing.JPanel;

/**
 * 底部动作条的浅色底板：覆盖开关与执行按钮放进同一块底里。
 *
 * 对应 main.py 的 ActionBar。用户口径：「覆盖已存在表」要像"执行前确认"的一部分，
 * 而不是和按钮各站一边。底色只负责归组，不加边框阴影，免得和蓝色的主按钮抢注意力。
 */
public final class ActionBar extends JPanel {

    public static final Color BG = new Color(0xf4, 0xf7, 0xfc);
    public static final Color BORDER = new Color(0xdc, 0xe5, 0xf2);

    public ActionBar(LayoutManager layout) {
        super(layout);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(
                UiScale.px(6), UiScale.px(10), UiScale.px(6), UiScale.px(10)));
    }

    public Color fillColor() {
        return BG;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int inset = UiScale.px(1);
            int arc = UiScale.px(12);
            int width = getWidth() - 2 * inset - 1;
            int height = getHeight() - 2 * inset - 1;
            g2.setColor(BG);
            g2.fillRoundRect(inset, inset, width, height, arc, arc);
            g2.setColor(BORDER);
            g2.drawRoundRect(inset, inset, width, height, arc, arc);
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
