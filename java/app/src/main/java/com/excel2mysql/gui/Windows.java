package com.excel2mysql.gui;

import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import javax.swing.JComponent;

/**
 * 窗口摆位：把"客户区要多大"钉在与 Qt 同一口径上，并保证整窗落在屏幕可用区里。
 *
 * <p>Qt 的 {@code resize(w,h)} 说的是客户区，Swing 的 {@code setSize(w,h)} 说的是整个外框。
 * 照抄数字的话，这边内容区就小掉一条标题栏加四周边框：「关于」那三行会被压掉最后一行，
 * 进度框的进度条只剩一条缝。所以尺寸要钉在内容面板上再 pack。
 *
 * <p>配置值按正常桌面写（1100x780 这类），但目标机可能是 1024x768 的老屏，
 * 所以 pack 完按当前屏幕的可用区夹一次：夹的是客户区，外框仍在可用区内，
 * 弹窗底部的按钮不会被任务栏吃掉。
 *
 * <p>配置值本身记在内容面板的 {@code e2mWant} 上，两条线的对撞器比的是它——
 * 实测尺寸受现场屏幕影响，不该拿来跨线比。
 */
public final class Windows {

    private Windows() {
    }

    /** 把客户区摆成"至少 w x h，但不小于内容自己要求的最小尺寸"，再夹进屏幕可用区并居中。 */
    public static void clientSize(Container content, Window win, int w, int h) {
        if (content instanceof JComponent) {
            ((JComponent) content).putClientProperty("e2mWant", w + "x" + h);
        }
        // 用最小尺寸而不是首选尺寸兜底：Qt 的 resize 会被布局的 minimumSize 顶上去，
        // 但 JScrollPane/QTextBrowser 的首选尺寸是整篇文档那么大——拿首选尺寸来比，
        // 说明窗会被撑到 2550 宽。滚动区的最小尺寸很小，所以能钉回 960x700，
        // 而真正缩不掉的文字（一行的标签）仍会把窗口顶宽，不会被裁。
        content.setPreferredSize(null);
        win.pack();
        Dimension floor = content.getMinimumSize();
        Rectangle work = workArea(win);
        int cw = Math.max(w, floor.width);
        int ch = Math.max(h, floor.height);
        // 夹取按真实外框尺寸算，不按猜的常量：这台机器量出来标题栏加边框是 50x56，
        // 原先写死的 48 让"要 99999 宽"仍然超可用区 8 像素（GuiMain 的夹取断言抓到的）
        for (int i = 0; i < 4; i++) {
            content.setPreferredSize(new Dimension(cw, ch));
            win.pack();
            Insets fi = win.getInsets();
            int outerW = cw + fi.left + fi.right;
            int outerH = ch + fi.top + fi.bottom;
            if (outerW <= work.width && outerH <= work.height) {
                break;    // 内容的最小尺寸已经优先，夹到它为止，再小就要裁字了
            }
            int ncw = Math.max(floor.width,
                    Math.min(cw, cw + (work.width - outerW)));
            int nch = Math.max(floor.height,
                    Math.min(ch, ch + (work.height - outerH)));
            if (ncw == cw && nch == ch) {
                break;    // 已经被内容最小尺寸顶住，再夹也夹不动了
            }
            cw = ncw;
            ch = nch;
        }
        win.setLocationRelativeTo(null);
    }

    /** 当前屏幕去掉任务栏之后的可用区（自检要用它判"整窗在不在屏内"）。 */
    public static Rectangle workArea(Window win) {
        GraphicsConfiguration gc = win.getGraphicsConfiguration();
        if (gc == null) {
            Dimension s = Toolkit.getDefaultToolkit().getScreenSize();
            return new Rectangle(s.width, s.height);
        }
        Rectangle b = gc.getBounds();
        Insets i = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(b.x + i.left, b.y + i.top,
                b.width - i.left - i.right, b.height - i.top - i.bottom);
    }
}
