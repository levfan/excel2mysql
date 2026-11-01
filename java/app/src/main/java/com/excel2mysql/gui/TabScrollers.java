package com.excel2mysql.gui;

import com.sun.java.swing.plaf.windows.WindowsTabbedPaneUI;

import javax.swing.JButton;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.plaf.UIResource;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Polygon;
import java.awt.RenderingHints;

/**
 * 页签放不下时那两颗左右翻页钮：Swing 默认 16x16，与 Qt 那侧同样被用户点名"太小了"，
 * 两栈一起抬到 28x24（Qt 侧见 main.py 的 style_tab_scroll / TAB_SCROLL_BUTTON_WIDTH）。
 *
 * <p>为什么改 UI 而不直接改按钮：那两颗钮是 BasicTabbedPaneUI 的私有内部类，
 * 它把 getPreferredSize 写死成 16x16，setPreferredSize 会被这层覆写吃掉（实测无效），
 * 而页签布局读的就是 getPreferredSize。BasicTabbedPaneUI 留了 createScrollButton
 * 这个官方扩展点（@since 1.5），覆写它换自己的钮才真正改得动。
 *
 * <p>箭头也自己画：BasicArrowButton 的三角形固定 7x4，钮放大后箭头不会跟着长，
 * 只会变成"大框里一个小点"。
 *
 * <p>只在 Windows 外观下装。交付包只有 Windows 版，换别的外观时宁可不放大，
 * 也不能把主题化过的页签换成 Basic 那套扁的。
 */
public final class TabScrollers {

    /** 宽 28、高 24，单位是 Qt 逻辑像素（摆出来按 DPI 折算）。 */
    public static final int BUTTON_WIDTH = 28;
    public static final int BUTTON_HEIGHT = 24;

    /** 可用与置灰两支色，与 Qt 那侧原生箭头的深浅对得上；判据要认箭头就认这两支。 */
    public static final Color ARROW_ON = new Color(0x60, 0x60, 0x60);
    public static final Color ARROW_OFF = new Color(0xC0, 0xC0, 0xC0);
    /** 按下时那层底色，给"这一下点着了"的反馈。 */
    public static final Color PRESSED_BG = new Color(0xD8, 0xE8, 0xFF);

    private TabScrollers() {
    }

    /** 装到一条页签上；调用点必须在 setTabLayoutPolicy 之后，否则钮还没建出来。 */
    public static void apply(JTabbedPane tabs) {
        if (tabs.getUI() instanceof WindowsTabbedPaneUI) {
            tabs.setUI(new WideScrollUI());
        }
    }

    /** 这条页签上翻页钮的宽度（物理像素）；没有翻页钮时回 -1。自检与对撞读这一个数。 */
    public static int widthOf(JTabbedPane tabs) {
        int best = -1;
        for (java.awt.Component c : tabs.getComponents()) {
            if (c instanceof JButton) {
                best = Math.max(best, ((JButton) c).getPreferredSize().width);
            }
        }
        return best;
    }

    static final class WideScrollUI extends WindowsTabbedPaneUI {
        @Override
        protected JButton createScrollButton(int direction) {
            return new Arrow(direction);
        }
    }

    /** 一颗翻页钮：尺寸自己定，箭头按尺寸画，方向决定箭头朝左还是朝右。 */
    static final class Arrow extends JButton implements UIResource {

        private final boolean west;

        Arrow(int direction) {
            this.west = direction == SwingConstants.WEST;
            setMargin(new Insets(0, 0, 0, 0));
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            getAccessibleContext().setAccessibleName(this.west ? "上一页" : "下一页");
        }

        @Override
        public Dimension getPreferredSize() {
            // 必须写全 TabScrollers.：JButton 一路继承 ImageObserver，它自带的
            // WIDTH=1 / HEIGHT=2 会盖掉外层同名常量，实测把钮摆成 2x3
            return new Dimension(UiScale.px(TabScrollers.BUTTON_WIDTH),
                    UiScale.px(TabScrollers.BUTTON_HEIGHT));
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                if (isEnabled() && getModel().isArmed()) {
                    g2.setColor(PRESSED_BG);
                    g2.fillRect(0, 0, w, h);
                }
                int cy = h / 2;
                int half = Math.max(3, UiScale.px(5));
                int depth = Math.max(2, UiScale.px(4));
                int tip = west ? w / 2 - depth / 2 : w / 2 + depth / 2;
                int back = west ? w / 2 + depth / 2 : w / 2 - depth / 2;
                g2.setColor(isEnabled() ? ARROW_ON : ARROW_OFF);
                g2.fill(new Polygon(new int[]{back, back, tip},
                        new int[]{cy - half, cy + half, cy}, 3));
            } finally {
                g2.dispose();
            }
        }
    }
}
