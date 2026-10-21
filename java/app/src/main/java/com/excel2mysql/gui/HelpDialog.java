package com.excel2mysql.gui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.text.AttributeSet;
import javax.swing.text.Element;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Frame;

/**
 * 使用说明窗口：左目录定宽 170、不给焦点，右整篇 HTML，点目录跳锚点，Esc 关闭。
 * 对应 ui_help.py:45-101。
 */
public final class HelpDialog extends JDialog {

    private final JList<String> toc;
    private final JEditorPane browser;
    private String currentAnchor = "";
    private String jumpVia = "";

    public HelpDialog(Frame owner) {
        super(owner, "使用说明", true);
        HelpDoc.Section[] sections = HelpDoc.sections();
        DefaultListModel<String> model = new DefaultListModel<String>();
        for (HelpDoc.Section s : sections) {
            model.addElement(s.title);
        }
        toc = new JList<String>(model);
        toc.setFocusable(false);
        toc.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane tocScroll = new JScrollPane(toc,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        Dimension fixed = new Dimension(UiScale.px(170), UiScale.px(100));
        tocScroll.setPreferredSize(fixed);
        tocScroll.setMinimumSize(fixed);
        tocScroll.setMaximumSize(new Dimension(UiScale.px(170), 32767));

        browser = new JEditorPane();
        browser.setEditable(false);
        browser.setContentType("text/html");
        browser.setText(swingHtml(HelpDoc.html()));
        browser.setBorder(null);
        JScrollPane bodyScroll = new JScrollPane(browser);
        bodyScroll.setBorder(null);

        toc.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                pickSelected();
            }
        });

        JLabel titleLabel = new JLabel("目录");
        titleLabel.setFont(titleLabel.getFont().deriveFont(java.awt.Font.BOLD));
        JPanel left = new JPanel(new BorderLayout(UiScale.px(4), UiScale.px(4)));
        left.add(titleLabel, BorderLayout.NORTH);
        left.add(tocScroll, BorderLayout.CENTER);

        JPanel center = new JPanel(new BorderLayout(UiScale.px(8), 0));
        center.add(left, BorderLayout.WEST);
        center.add(bodyScroll, BorderLayout.CENTER);

        JLabel hint = new JLabel("随时按 F1 或标题栏下方的「?」打开本页");
        hint.setForeground(new Color(0x66, 0x66, 0x66));
        JButton close = new JButton("关闭");
        close.addActionListener(e -> dispose());
        JPanel row = new JPanel(new BorderLayout());
        row.add(hint, BorderLayout.WEST);
        row.add(close, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(UiScale.px(6), UiScale.px(6)));
        root.setBorder(BorderFactory.createEmptyBorder(
                UiScale.px(8), UiScale.px(8), UiScale.px(8), UiScale.px(8)));
        root.add(center, BorderLayout.CENTER);
        row.setPreferredSize(new Dimension(10, UiScale.px(32)));
        root.add(row, BorderLayout.SOUTH);
        setContentPane(root);

        closeOnEscape();
        Windows.clientSize(getContentPane(), this, 960, 700);
    }

    private void closeOnEscape() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "closeHelp");
        getRootPane().getActionMap().put("closeHelp", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                dispose();
            }
        });
    }

    /**
     * Qt 在 150% 屏上把 10.5pt 渲染成 21 个物理像素。Swing 的 HTML 渲染器却把
     * CSS 的 px 当成 pt 用（96dpi 的 px 落进 72dpi 的字体，字号放大 4/3），
     * 写 21px 实际画出 28px。这里先按屏幕 DPI 折出目标物理像素，再乘 3/4 写回
     * CSS，手册正文才与 Python 同为 21px；其余样式原样保留。
     */
    public static String swingHtml(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("font-size:\\s*([0-9]+(?:\\.[0-9]+)?)pt").matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            double css = UiScale.fontPt(Double.parseDouble(m.group(1))) * 0.75;
            String px = css == Math.rint(css) ? String.valueOf((int) css)
                    : String.valueOf(css);
            m.appendReplacement(sb, "font-size: " + px + "px");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private void pickSelected() {
        int i = toc.getSelectedIndex();
        if (i >= 0) {
            jumpTo(HelpDoc.sections()[i].anchor);
        }
    }

    /** 跳锚点：把光标放到该章的位置上，滚动跟着走。 */
    public boolean jumpTo(String anchor) {
        javax.swing.text.Document raw = browser.getDocument();
        if (!(raw instanceof HTMLDocument)) {
            return false;
        }
        HTMLDocument doc = (HTMLDocument) raw;
        Element named = findNamed(doc.getDefaultRootElement(), anchor);
        if (named != null) {
            browser.setCaretPosition(named.getStartOffset());
            currentAnchor = anchor;
            jumpVia = "anchor";
            return true;
        }
        // 空的 a 标签在 Java 的 HTML 解析器里不一定留下元素，退一步按章标题定位
        String title = titleOf(anchor);
        if (title.isEmpty()) {
            return false;
        }
        int at;
        try {
            at = doc.getText(0, doc.getLength()).indexOf(title);
        } catch (javax.swing.text.BadLocationException e) {
            return false;
        }
        if (at < 0) {
            return false;
        }
        browser.setCaretPosition(at);
        currentAnchor = anchor;
        jumpVia = "title";
        return true;
    }

    private static String titleOf(String anchor) {
        for (HelpDoc.Section s : HelpDoc.sections()) {
            if (s.anchor.equals(anchor)) {
                return s.title;
            }
        }
        return "";
    }

    private static Element findNamed(Element el, String anchor) {
        javax.swing.text.AttributeSet attrs = el.getAttributes();
        Object name = attrs.getAttribute(HTML.Attribute.NAME);
        if (name == null) {
            name = attrs.getAttribute(HTML.Attribute.ID);
        }
        if (name != null && name.toString().equals(anchor)) {
            return el;
        }
        for (int i = 0; i < el.getElementCount(); i++) {
            Element hit = findNamed(el.getElement(i), anchor);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    public void selectToc(int index) {
        toc.setSelectedIndex(index);
        toc.ensureIndexIsVisible(index);
    }

    public int tocCount() {
        return toc.getModel().getSize();
    }

    public String tocTitle(int index) {
        return toc.getModel().getElementAt(index);
    }

    public String currentAnchor() {
        return currentAnchor;
    }

    /** 自检用：跳成是靠锚点还是靠标题文本定位的，决定这份 HTML 能不能照抄。 */
    public String jumpVia() {
        return jumpVia;
    }

    /** 自检用：正文真的渲染出来了才有这个长度。 */
    public int bodyLength() {
        return browser.getDocument().getLength();
    }

    /**
     * 自检用：正文首段第一行的实际像素行高。只查 HTML 字符串里写了多少 px
     * 咬不住"Swing 把 CSS px 又放大 4/3"这件事，这里量渲染结果。
     */
    public int bodyLineHeight() {
        try {
            javax.swing.text.Document doc = browser.getDocument();
            String text = doc.getText(0, doc.getLength());
            int at = text.indexOf("这个工具把一批");
            if (at < 0) {
                return -1;
            }
            java.awt.Rectangle r = browser.modelToView(at);
            return r == null ? -1 : r.height;
        } catch (javax.swing.text.BadLocationException e) {
            return -1;
        }
    }

    /** 自检用：窗口里实际渲染的那份 HTML（字号已折成 px）。 */
    public String html() {
        return browser.getText();
    }
}
