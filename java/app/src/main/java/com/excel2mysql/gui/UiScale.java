package com.excel2mysql.gui;

import javax.swing.UIManager;
import javax.swing.UIDefaults;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把 Python/Qt 的点值与逻辑像素折算成 Swing 在这台机器上的物理像素。
 *
 * <p>Qt 的字号是 pt，按设备缩放因子落到物理像素；JDK8 在 Windows 上不认
 * {@code sun.java2d.uiScale}，Swing 的字号与尺寸都当物理像素用。同一句
 * {@code QFont(..., 16)} 在 150% 屏上是 32px，Java 写 16 就只剩一半，
 * 这就是"整个系统字体比 Python 小不少"的根因。这里统一按屏幕 DPI 折算：
 * 1pt = dpi/72 像素，1 个 Qt 逻辑像素 = dpi/96 像素。
 *
 * <p>窗口的"设计尺寸"仍按 Python 的逻辑值写（1100x780 这类），只在真正
 * 摆窗口时折算；{@code e2mWant} 保留设计值，双端对撞器比的还是同一个数。
 */
public final class UiScale {

    /** Python 应用默认字体：QApplication 在 Windows 上的 Microsoft YaHei UI 9pt。 */
    public static final int UI_POINT_SIZE = 9;

    private static final double DPI = Toolkit.getDefaultToolkit().getScreenResolution();
    private static final double FACTOR = Math.max(1.0, DPI / 96.0);
    private static final String FAMILY = pickFamily();

    private UiScale() {
    }

    /** 1 个 Qt 逻辑像素等于几个 Swing 物理像素。 */
    public static double factor() {
        return FACTOR;
    }

    /** 设计值（Qt 逻辑像素）折算成 Swing 物理像素。 */
    public static int px(int design) {
        return (int) Math.round(design * FACTOR);
    }

    /** 点值折算成 Swing 物理像素：16pt 在 144dpi 上是 32px。 */
    public static int fontPt(double pt) {
        return (int) Math.round(pt * DPI / 72.0);
    }

    /** 当前实际选用的界面字族；没有雅黑时退回 Segoe UI / 对话框字体。 */
    public static String fontFamily() {
        return FAMILY;
    }

    /**
     * 装系统外观并把界面默认字体换成 Python 那一档。
     *
     * <p>生产入口、自检、离屏渲染三条路都调这一个函数：自检以前跑在 Metal
     * 默认外观上，量的字体与真实界面不是同一套，字号出了问题也咬不住。
     */
    public static void apply() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            System.out.println("! [WARN] GUI | 系统外观设置失败，用默认外观：" + e.getMessage());
        }
        Font base = new Font(FAMILY, Font.PLAIN, fontPt(UI_POINT_SIZE));
        UIDefaults defaults = UIManager.getDefaults();
        // 必须走 keys() 的枚举：JDK8 的 UIDefaults.keySet() 只含本地 put 过的键，
        // LAF 自带的那几百条一条都看不到，用它遍历等于什么都没改
        List<Object> keys = new ArrayList<Object>();
        java.util.Enumeration<Object> it = defaults.keys();
        while (it.hasMoreElements()) {
            keys.add(it.nextElement());
        }
        for (Object key : keys) {
            if (!String.valueOf(key).endsWith(".font")) {
                continue;
            }
            Object old = defaults.get(key);
            if (old instanceof Font) {
                // 保留各控件原本的粗体/斜体档，只换字族与字号
                defaults.put(key, new Font(FAMILY, ((Font) old).getStyle(), base.getSize()));
            }
        }
        // 少数控件不查 .font 键，直接吃这两个总开关
        UIManager.put("OptionPane.messageFont", base);
        UIManager.put("OptionPane.buttonFont", base);
    }

    private static String pickFamily() {
        Set<String> names = new HashSet<String>();
        for (String name : GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()) {
            names.add(name);
        }
        for (String want : new String[]{"Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI"}) {
            if (names.contains(want)) {
                return want;
            }
        }
        return Font.DIALOG;
    }
}
