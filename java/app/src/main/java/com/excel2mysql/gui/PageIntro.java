package com.excel2mysql.gui;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Font;

/**
 * 页签顶部那句介绍的底板，三页共用（Python 侧 main.py 的 page_intro / PAGE_INTRO_QSS）。
 *
 * 文字放在状态条下面一行、不在按钮旁边排：这句是整页的定位说明，
 * 与目标库提示条同一竖列才看得见。Swing 没有 border-radius，
 * 用四边 1px 细边 + 左边 3px 强调条做出同一形状，强调色取主按钮那一支。
 */
public final class PageIntro {

    private static final Color TEXT = new Color(0x1f, 0x5f, 0xd0);
    private static final Color BACKGROUND = new Color(0xf4, 0xf8, 0xff);
    private static final Color EDGE = new Color(0x8f, 0xb4, 0xea);

    private PageIntro() {
    }

    public static JLabel of(String text) {
        JLabel label = new JLabel(text);
        label.setOpaque(true);
        label.setBackground(BACKGROUND);
        label.setForeground(TEXT);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        Border frame = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 1, EDGE),
                BorderFactory.createMatteBorder(0, 3, 0, 0, Buttons.primaryColor()));
        label.setBorder(BorderFactory.createCompoundBorder(frame,
                BorderFactory.createEmptyBorder(UiScale.px(4), UiScale.px(10),
                        UiScale.px(4), UiScale.px(10))));
        return label;
    }
}
