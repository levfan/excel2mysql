package com.excel2mysql.gui;

import java.awt.Image;
import java.awt.Window;
import java.net.URL;

/**
 * 所有顶层窗口共用的一颗图标：源图只有 {@code /app_icon.png} 一份（由
 * {@code java/tools/export_icon_png.py} 从 {@code assets/app_icon.ico} 导出）。
 *
 * <p>以前全仓只有主窗口调过一次 {@code setIconImage}，其余 8 个顶层窗口
 * （SQL 示例、使用说明、关于、提示框、进度、两个 DDL 预览、表结构预览、元数据）
 * 都挂着系统默认那颗 Java 图标 —— 用户看的就是"同一个程序，标题栏图标不一样"。
 * Python 那条线是应用级 {@code setWindowIcon}，所有窗口天然跟着走，没有这个问题；
 * 两端是否一致由对撞键 {@code window.icon.*} 读真窗口的 {@code getIconImages()} 核。
 */
public final class WindowIcons {

    private static Image cached;
    private static boolean loaded;

    private WindowIcons() {
    }

    /** 那颗图标；源图缺失时返回 null（调用方自己决定要不要说一句）。 */
    public static synchronized Image image() {
        if (!loaded) {
            loaded = true;
            URL u = WindowIcons.class.getResource("/app_icon.png");
            if (u != null) {
                try {
                    cached = javax.imageio.ImageIO.read(u);
                } catch (Exception e) {
                    cached = null;
                }
            }
        }
        return cached;
    }

    /** 给一个顶层窗口挂上那颗图标。 */
    public static void apply(Window w) {
        Image img = image();
        if (img != null) {
            w.setIconImage(img);
        }
    }

    /** 自检与对撞用：这个窗口生效的图标最大边长，没挂图标就是 0。 */
    public static int sideOf(Window w) {
        java.util.List<Image> imgs = w.getIconImages();
        int best = 0;
        if (imgs != null) {
            for (Image img : imgs) {
                best = Math.max(best, Math.max(img.getWidth(null), img.getHeight(null)));
            }
        }
        return best;
    }
}
