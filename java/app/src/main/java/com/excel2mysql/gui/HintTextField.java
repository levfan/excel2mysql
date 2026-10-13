package com.excel2mysql.gui;

import javax.swing.JTextField;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 带占位提示的输入框：内容为空且没拿到焦点时，把提示语以灰字画在框里。
 * 对应 Qt 的 setPlaceholderText——Swing 没有这个能力，不画就等于没有：
 * 只挂 tooltip 的话，光标不悬上去根本不知道该填什么格式。
 */
public final class HintTextField extends JTextField {

    private static final Color HINT_COLOR = new Color(0x99, 0x99, 0x99);

    private final String hint;

    public HintTextField(int columns, String hint) {
        super(columns);
        this.hint = hint == null ? "" : hint;
    }

    public String hintText() {
        return hint;
    }

    /** 该不该画提示：有文字或正在编辑时都不该挡着用户自己写的内容。 */
    public boolean shouldPaintHint() {
        return getText().isEmpty() && !hasFocus();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!shouldPaintHint() || hint.isEmpty()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font f = getFont().deriveFont(Font.ITALIC);
            g2.setFont(f);
            g2.setColor(HINT_COLOR);
            int y = (getHeight() + g2.getFontMetrics().getAscent()
                    - g2.getFontMetrics().getDescent()) / 2;
            g2.drawString(hint, g2.getFontMetrics().stringWidth("M") + 2, y);
        } finally {
            g2.dispose();
        }
    }
}
