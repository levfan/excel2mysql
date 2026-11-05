package com.excel2mysql.gui;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 「关于」里那一行仓库链接：左边 GitHub 轮廓、中间「GitHub」、行尾外链箭头，整行都是热区。
 * 对应 ui_help.py 的 RepoLinkRow。
 *
 * <p>地址不铺在正文那一格（框窄，长地址会被挤到看不全），所以取走它的路径给三条：
 * 悬浮提示写完整地址、「复制全部」多带一行、本机打不开浏览器时直接进剪贴板。
 * 点了必须有可见回执——静默不响应等于没这一行。
 *
 * <p>浏览器那一步交给注入进来的 opener：自检与离屏渲染不许真开浏览器，
 * 生产入口不传就用 {@link #openInBrowser}（那条路真的走系统外壳）。
 */
public final class RepoLinkRow extends JPanel {

    private static final java.awt.Color HOVER = new java.awt.Color(0xee, 0xf2, 0xf6);

    private final JLabel iconLabel = new JLabel();
    private final JLabel textLabel = new JLabel(AboutDialog.REPO_LABEL);
    private final JLabel arrowLabel = new JLabel();
    private final String url;
    private final Function<String, Boolean> opener;
    private final List<String> missing = new ArrayList<String>();

    private javax.swing.Timer restore;
    private String lastStatus = "";

    public RepoLinkRow(String url, Function<String, Boolean> opener) {
        super(new BorderLayout(0, 0));
        this.url = url;
        this.opener = opener;
        setOpaque(false);
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorder(BorderFactory.createEmptyBorder(
                UiScale.px(3), UiScale.px(6), UiScale.px(3), UiScale.px(6)));

        putIcon(iconLabel, AboutDialog.REPO_ICON_FILE, AboutDialog.REPO_ICON_PX);
        putIcon(arrowLabel, AboutDialog.REPO_ARROW_FILE, AboutDialog.REPO_ARROW_PX);
        textLabel.setFont(textLabel.getFont().deriveFont(Font.PLAIN));

        JPanel left = new JPanel(new BorderLayout(UiScale.px(7), 0));
        left.setOpaque(false);
        left.add(iconLabel, BorderLayout.WEST);
        left.add(textLabel, BorderLayout.CENTER);
        add(left, BorderLayout.WEST);
        add(arrowLabel, BorderLayout.EAST);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    activate();
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                setOpaque(true);
                setBackground(HOVER);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setOpaque(false);
                setBackground(null);
            }
        });
        // Tab 走到这一行，回车与空格等价于单击：只有鼠标的链接在键盘上是摸不到的
        addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) {
                    activate();
                }
            }
        });
    }

    /** 读随包的小图；文件没进包时那一格留空并记一笔，缺图要能被自检发现而不是画个叉。 */
    private void putIcon(JLabel label, String resource, int px) {
        URL u = RepoLinkRow.class.getResource(resource);
        if (u == null) {
            missing.add(resource);
            return;
        }
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(u);
            if (img == null) {
                missing.add(resource);
                return;
            }
            label.setIcon(new ImageIcon(img.getScaledInstance(
                    UiScale.px(px), UiScale.px(px), java.awt.Image.SCALE_SMOOTH)));
        } catch (Exception e) {
            missing.add(resource);
        }
    }

    /** 单击那一下：先交给浏览器，交不出去就把地址放进剪贴板，回执只说实话。 */
    public String activate() {
        boolean opened = false;
        try {
            opened = Boolean.TRUE.equals(opener.apply(url));
        } catch (Exception ignored) {
            opened = false;
        }
        if (opened) {
            lastStatus = "opened";
            flash(AboutDialog.REPO_OPENED_LABEL);
        } else {
            lastStatus = copyUrl() ? "copied" : "failed";
            flash("copied".equals(lastStatus)
                    ? AboutDialog.REPO_COPIED_LABEL : AboutDialog.REPO_FAIL_LABEL);
        }
        return lastStatus;
    }

    /** 把地址写进剪贴板并回读确认：没有桌面会话或被别的程序占着时会静默落空。 */
    private boolean copyUrl() {
        try {
            java.awt.datatransfer.Clipboard cb =
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            cb.setContents(new StringSelection(url), null);
            Object back = cb.getData(DataFlavor.stringFlavor);
            return url.equals(back == null ? null : String.valueOf(back));
        } catch (Exception e) {
            return false;
        }
    }

    private void flash(String label) {
        textLabel.setText(label);
        if (restore != null && restore.isRunning()) {
            restore.stop();
        }
        restore = new javax.swing.Timer(2000, e ->
                textLabel.setText(AboutDialog.REPO_LABEL));
        restore.setRepeats(false);
        restore.start();
    }

    /**
     * 把地址交给系统默认浏览器；交不出去一律 False，不抛异常。
     *
     * <p>无头环境（自检、离屏渲染）根本不该碰这一步，所以先按 isHeadless 挡掉：
     * 那趟里 Desktop.getDesktop() 会抛 HeadlessException，而不是"这台机器打不开"。
     */
    public static boolean openInBrowser(String url) {
        try {
            if (GraphicsEnvironment.isHeadless()
                    || !java.awt.Desktop.isDesktopSupported()) {
                return false;
            }
            java.awt.Desktop desk = java.awt.Desktop.getDesktop();
            if (!desk.isSupported(java.awt.Desktop.Action.BROWSE)) {
                return false;
            }
            desk.browse(new URI(url));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 自检与对撞用：这一行此刻显示的字。 */
    public String rowText() {
        return textLabel.getText();
    }

    public String rowUrl() {
        return url;
    }

    public String status() {
        return lastStatus;
    }

    /**
     * 自检用：没读进来的随包图，空串表示两张都在。
     *
     * <p>只报文件名：Python 那边是 assets/ 相对路径，这里带的是 classpath 前缀，
     * 比全路径对撞永远红，缺不缺图才是判据。
     */
    public String iconsMissing() {
        StringBuilder sb = new StringBuilder();
        for (String m : missing) {
            sb.append(sb.length() == 0 ? "" : ",")
                    .append(m.substring(m.lastIndexOf('/') + 1));
        }
        return sb.toString();
    }

    /** 自检用：这一行是不是真能点——手型光标、能 Tab 到、已经摆出高度。 */
    public boolean clickable() {
        return getCursor().getType() == Cursor.HAND_CURSOR && isFocusable()
                && getHeight() > 0;
    }
}
