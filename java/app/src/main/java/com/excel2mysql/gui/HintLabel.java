package com.excel2mysql.gui;

import javax.swing.JTextField;
import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;

/**
 * 提示条：Python 那边是开了 `TextSelectableByMouse` 的 QLabel（`main.py:887`、`1121`），
 * 用户要能真的按住拖选再 Ctrl+C 抄出去核对。
 * JLabel 一个字也选不了，所以这里用"不可编辑 + 无边框 + 透明底"的文本框顶它：
 * 拖选、高亮、复制都是现成的，不用再自己编一套"双击即复制"。
 */
public final class HintLabel extends JTextField {

    private static final Color OK_COLOR = new Color(0x1a, 0x7f, 0x37);
    private static final Color BAD_COLOR = new Color(0xc6, 0x28, 0x28);

    public HintLabel() {
        setFont(getFont().deriveFont(Font.BOLD));
        setEditable(false);
        setBorder(null);
        setMargin(new Insets(2, 2, 2, 2));
        // 不画底色才不像一块输入框，看着仍像界面上的一行说明
        setOpaque(false);
    }

    public void setOk(String message) {
        setText(message);
        setForeground(OK_COLOR);
    }

    public void setBad(String message) {
        setText(message);
        setForeground(BAD_COLOR);
    }

    public String hintText() {
        String s = getText();
        return s == null ? "" : s;
    }

    /** 自检判色用：绿色代表已选定目标库，红色代表还没选。 */
    public boolean isOk() {
        return getForeground().equals(OK_COLOR);
    }
}
