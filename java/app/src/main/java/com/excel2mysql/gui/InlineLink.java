package com.excel2mysql.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.net.URL;
import java.util.function.Function;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 「关于」里作者那一格：署名 + 一枚很小的外链箭头，整格可点开仓库。
 * 对应 ui_help.py 的 LinkCell。
 *
 * <p>单独占一行太突兀，而"谁做的"与"在哪座仓库上"本来就是同一件事。地址取走的路径两条：
 * 「复制全部」多带一行、本机打不开浏览器时直接进剪贴板；不挂悬浮提示，那套说明归《使用说明》。
 * 点了必须有可见回执（署名那一格临时换成回执那一句，两秒后复原）——静默不响应等于没这个链接。
 *
 * <p>浏览器那一步交给注入进来的 opener：自检与离屏渲染不许真开浏览器，
 * 生产入口不传就用 {@link #openInBrowser}（那条路真的走系统外壳）。
 */
public final class InlineLink extends JPanel {

    private static final Color LINK_COLOR = new Color(0x0b, 0x5c, 0xad);

    private final JLabel textLabel;
    private final JLabel arrowLabel = new JLabel();
    private final String url;
    private final Function<String, Boolean> opener;

    private boolean arrowMissing;
    private String lastStatus = "";
    private javax.swing.Timer restore;

    public InlineLink(String text, String url, Function<String, Boolean> opener) {
        super(new BorderLayout(0, 0));
        this.url = url;
        this.opener = opener;
        setOpaque(false);
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        textLabel = new JLabel(text);
        textLabel.setFont(textLabel.getFont().deriveFont(Font.PLAIN));
        putArrow();

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(4), 0));
        left.setOpaque(false);
        left.add(textLabel);
        left.add(arrowLabel);
        // 箭头紧跟名字：顶到格子最右边就又变成"一整行链接"那种观感了
        add(left, BorderLayout.WEST);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    activate();
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                // 悬停只把名字换成链接蓝：整格铺底色在这一档字号下会把两个字糊成一团
                textLabel.setForeground(LINK_COLOR);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                textLabel.setForeground(null);
            }
        });
        // Tab 走到这一格，回车与空格等价于单击：只有鼠标的链接在键盘上是摸不到的
        addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) {
                    activate();
                }
            }
        });
    }

    /** 读随包的那枚小箭头；文件没进包时那一格留空并记一笔，缺图要能被自检发现。 */
    private void putArrow() {
        URL u = InlineLink.class.getResource(AboutDialog.LINK_ARROW_FILE);
        if (u == null) {
            arrowMissing = true;
            return;
        }
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(u);
            if (img == null) {
                arrowMissing = true;
                return;
            }
            int px = UiScale.px(AboutDialog.LINK_ARROW_PX);
            arrowLabel.setIcon(new ImageIcon(img.getScaledInstance(px, px,
                    java.awt.Image.SCALE_SMOOTH)));
        } catch (Exception e) {
            arrowMissing = true;
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
        restore = new javax.swing.Timer(2000, e -> textLabel.setText(textBase()));
        restore.setRepeats(false);
        restore.start();
    }

    /** 回执复原要落回署名，不是落回某个写死的词：这一格的正文本来就是传进来的。 */
    private String textBase() {
        return AboutDialog.AUTHOR;
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

    /** 自检与对撞用：这一格此刻显示的字。 */
    public String cellText() {
        return textLabel.getText();
    }

    public String linkUrl() {
        return url;
    }

    public String status() {
        return lastStatus;
    }

    /** 自检用：那枚小箭头没读进来时报资源路径，读到了报空串。 */
    public String arrowMissing() {
        return arrowMissing ? AboutDialog.LINK_ARROW_FILE : "";
    }

    /** 自检用：这一格是不是真能点——手型光标、能 Tab 到、已经摆出高度。 */
    public boolean clickable() {
        return getCursor().getType() == Cursor.HAND_CURSOR && isFocusable()
                && getHeight() > 0;
    }
}
