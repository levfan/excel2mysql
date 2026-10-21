package com.excel2mysql.gui;

import javax.swing.JTextField;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/**
 * 提示条：Python 那边是开了 `TextSelectableByMouse` 的 QLabel（`main.py:887`、`1121`），
 * 用户要能真的按住拖选再 Ctrl+C 抄出去核对。
 * JLabel 一个字也选不了，所以这里用"不可编辑 + 无边框 + 透明底"的文本框顶它：
 * 拖选、高亮、复制都是现成的，不用再自己编一套"双击即复制"。
 */
public final class HintLabel extends JTextField {

    private static final Color OK_COLOR = new Color(0x1a, 0x7f, 0x37);
    private static final Color BAD_COLOR = new Color(0xc6, 0x28, 0x28);
    private static final Color WARN_FILL = new Color(0xF2, 0xC1, 0x4E);
    private static final Color WARN_EDGE = new Color(0x8C, 0x6D, 0x1F);
    private static final Color WARN_MARK = new Color(0x3A, 0x2A, 0x00);
    private static final int PAD = UiScale.px(2);
    private static final int ICON_ROOM = UiScale.px(22);

    private String badText = "";
    private boolean warning;

    public HintLabel() {
        setFont(getFont().deriveFont(Font.BOLD));
        setEditable(false);
        setBorder(null);
        setMargin(new Insets(PAD, PAD, PAD, PAD));
        // 不画底色才不像一块输入框，看着仍像界面上的一行说明
        setOpaque(false);
    }

    public void setOk(String message) {
        warning = false;
        setText(message);
        setBorder(null);
        setMargin(new Insets(PAD, PAD, PAD, PAD));
        setForeground(OK_COLOR);
    }

    /**
     * 告警文案里的 ⚠ 在 Microsoft YaHei 下是豆腐块（JDK8 对物理字体不做逐字回退），
     * 所以文字里不带这个字符，改为在左侧自绘一个同形状的三角图标；hintText 仍
     * 返回带 ⚠ 的原文，双端对撞按同一句话比。
     *
     * <p>文字左移量只能给空边框：Windows 外观下 JTextField 的 margin 在 border
     * 为 null 时根本不进文字排版区（实测 margin.left=100 仍从 0 开始画）。
     */
    public void setBad(String message) {
        warning = true;
        badText = message;
        String shown = message.startsWith("⚠ ") ? message.substring(2) : message;
        setText(shown);
        setBorder(javax.swing.BorderFactory.createEmptyBorder(
                0, PAD + ICON_ROOM, 0, 0));
        setMargin(new Insets(PAD, PAD, PAD, PAD));
        setForeground(BAD_COLOR);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!warning) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        int w = UiScale.px(16);
        int h = UiScale.px(14);
        int x = PAD;
        int y = Math.max(0, (getHeight() - h) / 2);
        int[] xs = {x + w / 2, x, x + w};
        int[] ys = {y, y + h, y + h};
        g2.setColor(WARN_FILL);
        g2.fillPolygon(xs, ys, 3);
        g2.setColor(WARN_EDGE);
        g2.setStroke(new BasicStroke(Math.max(1f, (float) UiScale.factor() * 0.75f)));
        g2.drawPolygon(xs, ys, 3);
        int cx = x + w / 2;
        g2.setColor(WARN_MARK);
        g2.setStroke(new BasicStroke(Math.max(1f, (float) UiScale.factor())));
        g2.drawLine(cx, y + h * 34 / 100, cx, y + h * 62 / 100);
        int dot = Math.max(1, UiScale.px(2));
        g2.fillOval(cx - dot / 2, y + h * 72 / 100, dot, dot);
        g2.dispose();
    }

    public String hintText() {
        if (warning) {
            return badText;
        }
        String s = getText();
        return s == null ? "" : s;
    }

    /** 自检判色用：绿色代表已选定目标库，红色代表还没选。 */
    public boolean isOk() {
        return getForeground().equals(OK_COLOR);
    }
}
