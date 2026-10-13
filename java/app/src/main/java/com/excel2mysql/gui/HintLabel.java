package com.excel2mysql.gui;

import javax.swing.JLabel;
import java.awt.Color;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * 可鼠标选中的提示条。
 *
 * JLabel 本身选不了文字，Python 侧那两条提示条是可以框选复制的
 * （main.py:887、1121），所以这里加一段"拖选即复制到剪贴板"的兜底，
 * 让用户能把「当前目标库：xxx」抄出去核对。
 */
public final class HintLabel extends JLabel {

    private static final Color OK_COLOR = new Color(0x1a, 0x7f, 0x37);
    private static final Color BAD_COLOR = new Color(0xc6, 0x28, 0x28);


    public HintLabel() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && getText() != null && !getText().isEmpty()) {
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                            new StringSelection(getText()), null);
                }
            }
        });
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
        return getText() == null ? "" : getText();
    }
}
