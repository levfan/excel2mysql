package com.excel2mysql.gui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.plaf.basic.BasicButtonUI;

/**
 * 一屏只留一个视觉重点：主操作填色，次级操作描边，其余按钮保持系统样子。
 *
 * Windows 外观会整块重绘按钮底纹，直接 setBackground 不生效；
 * 换掉 ButtonUI 再自己上色，两栈的强调色才对得上（main.py 的 PRIMARY/SECONDARY_BUTTON_QSS）。
 */
public final class Buttons {

    private static final Color PRIMARY = new Color(0x2f, 0x6f, 0xeb);
    private static final Color PRIMARY_HOVER = new Color(0x3b, 0x7b, 0xf5);
    private static final Color PRIMARY_PRESSED = new Color(0x24, 0x59, 0xc9);
    private static final Color PRIMARY_DISABLED = new Color(0xaa, 0xc4, 0xf0);
    private static final Color PRIMARY_TEXT = Color.WHITE;
    /** 禁用态的字色：纯白压在这层淡蓝上对比不够，Python 侧同一句 QSS 用的就是它。 */
    private static final Color PRIMARY_DISABLED_TEXT = new Color(0xf5, 0xf8, 0xff);

    private static final Color SECONDARY_BG = new Color(0xf4, 0xf8, 0xff);
    private static final Color SECONDARY_HOVER = new Color(0xe8, 0xf1, 0xff);
    private static final Color SECONDARY_BORDER = new Color(0x8f, 0xb4, 0xea);
    private static final Color SECONDARY_EDGE = new Color(0x2f, 0x6f, 0xeb);
    private static final Color SECONDARY_TEXT = new Color(0x1f, 0x5f, 0xd0);
    private static final Color SECONDARY_DISABLED_BG = new Color(0xf7, 0xf9, 0xfc);
    private static final Color SECONDARY_DISABLED_BORDER = new Color(0xc9, 0xd4, 0xe4);
    private static final Color SECONDARY_DISABLED_TEXT = new Color(0x9a, 0xa7, 0xb8);

    private static final String ROLE_KEY = "excel2mysql.buttonRole";

    private Buttons() {
    }

    /** 自检读按钮有没有被标成强调/次级；只认自己打的那枚标记，不看外观算。 */
    public static String role(JButton button) {
        Object v = button == null ? null : button.getClientProperty(ROLE_KEY);
        return v == null ? "" : String.valueOf(v);
    }

    /** 主操作：填色白字（「开始导入」「执行」）。 */
    public static JButton primary(JButton button) {
        return paint(button, true);
    }

    /** 次级操作：浅底蓝字描边（「保存修改后ddl脚本」）。 */
    public static JButton secondary(JButton button) {
        return paint(button, false);
    }

    private static JButton paint(final JButton button, final boolean filled) {
        button.putClientProperty(ROLE_KEY, filled ? "primary" : "secondary");
        button.setUI(new BasicButtonUI());
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setFocusPainted(false);
        Font f = button.getFont();
        if (f != null) {
            button.setFont(f.deriveFont(Font.BOLD));
        }
        // 上色必须按"当下"的启用状态来：「开始导入」建出来就是禁用的，
        // 先涂一层强调色会让它看着就能点，现场那一下点击才把它跳成禁用色。
        apply(button, filled);
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (button.isEnabled()) {
                    button.setBackground(filled ? PRIMARY_HOVER : SECONDARY_HOVER);
                    button.setBorder(edge(filled ? PRIMARY : SECONDARY_EDGE));
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                apply(button, filled);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (button.isEnabled()) {
                    button.setBackground(filled ? PRIMARY_PRESSED : SECONDARY_BG);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                apply(button, filled);
            }
        });
        button.addChangeListener(e -> apply(button, filled));
        return button;
    }

    /** 按当前启用状态把底色、字色、描边与光标一次摆正；启用/禁用切换与鼠标离开都走这里。 */
    private static void apply(JButton button, boolean filled) {
        boolean on = button.isEnabled();
        button.setCursor(Cursor.getPredefinedCursor(
                on ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        if (on) {
            button.setBackground(filled ? PRIMARY : SECONDARY_BG);
            button.setForeground(filled ? PRIMARY_TEXT : SECONDARY_TEXT);
            button.setBorder(edge(filled ? PRIMARY_PRESSED : SECONDARY_BORDER));
        } else {
            button.setBackground(filled ? PRIMARY_DISABLED : SECONDARY_DISABLED_BG);
            button.setForeground(filled ? PRIMARY_DISABLED_TEXT : SECONDARY_DISABLED_TEXT);
            button.setBorder(edge(filled ? PRIMARY_DISABLED : SECONDARY_DISABLED_BORDER));
        }
    }

    /** 自检要用：强调色与禁用色各是多少，两栈必须同值。 */
    public static Color primaryColor() {
        return PRIMARY;
    }

    public static Color primaryDisabledColor() {
        return PRIMARY_DISABLED;
    }

    public static Color secondaryColor() {
        return SECONDARY_BG;
    }

    public static Color secondaryDisabledColor() {
        return SECONDARY_DISABLED_BG;
    }

    private static javax.swing.border.Border edge(Color color) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(color, 1),
                BorderFactory.createEmptyBorder(UiScale.px(2), UiScale.px(10),
                        UiScale.px(2), UiScale.px(10)));
    }
}
