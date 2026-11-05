package com.excel2mysql.gui;

import javax.swing.UIManager;
import javax.swing.UIDefaults;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;

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

    /** Python 应用默认字体：随包那一份字体的 9pt 折算成物理像素后的那档。 */
    public static final int UI_POINT_SIZE = 9;

    /**
     * 随包界面字体在 classpath 里的路径。
     *
     * <p>文件只有一份，住在仓库 <code>assets/fonts/NotoSansSC-Regular.ttf</code>（思源黑体的
     * Google 版，SIL OFL 1.1，许可随包在 <code>assets/fonts/LICENSE-OFL.txt</code>）；
     * Maven 把那个目录当资源目录抄进 jar，Python 那条线读的是同一个文件。
     */
    public static final String FONT_RESOURCE = "/fonts/NotoSansSC-Regular.ttf";

    /** 随包字体加载出来的那一个 Font；取不到就是 null，一切退回逻辑字体并说明原因。 */
    private static final Font BUNDLED = loadBundled();

    private static final double DPI = Toolkit.getDefaultToolkit().getScreenResolution();
    private static final double FACTOR = Math.max(1.0, DPI / 96.0);
    private static final String FAMILY = BUNDLED == null ? Font.DIALOG : BUNDLED.getFamily();

    private UiScale() {
    }

    /**
     * 读随包字体。
     *
     * <p>绑的是加载回来的 Font 对象，不是族名：现场机器上可能已经装着一支同名的
     * Noto Sans SC，按族名再查一次拿到的就是系统那支——那样"内置"就成了假的，
     * 机器上有没有字体这件事又变回了机器的决定。
     */
    private static Font loadBundled() {
        java.io.InputStream in = UiScale.class.getResourceAsStream(FONT_RESOURCE);
        if (in == null) {
            System.out.println("! [WARN] GUI | classpath 里没有随包字体 " + FONT_RESOURCE
                    + "，界面退回对话框字体（中文可能画不出）");
            return null;
        }
        try {
            Font f = Font.createFont(Font.TRUETYPE_FONT, in);
            // 顺手注册进图形环境：JTable 表头这类自己按名找字体的地方也能撞上这一支；
            // 找不到或已存在都不算失败，主路径用的是上面这个对象
            try {
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
            } catch (RuntimeException e) {
                System.out.println("! [WARN] GUI | 随包字体注册失败（不影响按对象使用）："
                        + e.getMessage());
            }
            return f;
        } catch (Exception e) {
            System.out.println("! [WARN] GUI | 随包字体加载失败：" + e
                    + "，界面退回对话框字体（中文可能画不出）");
            return null;
        } finally {
            try {
                in.close();
            } catch (java.io.IOException e) {
                // 流关不掉不影响已经读进来的 Font，不值得为它换一次启动失败
            }
        }
    }

    /** 界面字体：随包那一支按字号与样式派生；只有加载失败时才退回逻辑字体。 */
    public static Font font(int style, int sizePx) {
        return BUNDLED == null ? new Font(Font.DIALOG, style, sizePx)
                : BUNDLED.deriveFont(style, (float) sizePx);
    }

    /** 随包字体到底有没有装上：自检按它判"界面吃的是内置那支，不是系统里碰巧同名的"。 */
    public static boolean bundledLoaded() {
        return BUNDLED != null;
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

    /** 反算回点值：对撞键要比的是设计稿那一档（pt），不是各台机器上的物理像素。 */
    public static int fontPxToPt(int px) {
        return (int) Math.round(px * 72.0 / DPI);
    }

    /** 界面字体的族名：随包那支的名字；只有字体没打进包时才是对话框字体。 */
    public static String fontFamily() {
        return FAMILY;
    }

    /**
     * 装系统外观并把界面默认字体换成随包那一支。
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
        Font base = font(Font.PLAIN, fontPt(UI_POINT_SIZE));
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
                defaults.put(key, font(((Font) old).getStyle(), base.getSize()));
            }
        }
        // 少数控件不查 .font 键，直接吃这两个总开关
        UIManager.put("OptionPane.messageFont", base);
        UIManager.put("OptionPane.buttonFont", base);
    }
}
