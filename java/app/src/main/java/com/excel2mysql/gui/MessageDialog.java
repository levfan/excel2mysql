package com.excel2mysql.gui;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;

/**
 * 结果/告警消息框：Python 默认 9pt 字、正文可滚动可选中、带「复制全部」。
 *
 * <p>以前直接用 {@link JOptionPane#showMessageDialog}：正文是 12 号字，比主界面
 * 16pt 的日志小一截；内容一长没有滚动条，超出的行只能去底部日志里找；想复制
 * 整段报错也只能拖着鼠标从第一行选到最后一行。这几件事在现场都挨过反馈。
 *
 * <p>正文就是调用方给的那份原文：横向由 {@link JTextArea} 自己的折行按内容区右边界
 * 断开（拉大窗口会重排），纵向靠滚动条，不截行。「复制全部」复制的也是同一份原文。
 * 窗口至少 820x420（Qt 逻辑像素，按屏幕 DPI 折算），按内容算高后夹进屏幕可用区。
 */
public final class MessageDialog extends JDialog {

    /** 正文与标题共用 Python 应用默认的 9pt，按 DPI 折成物理像素（144dpi → 18px）。 */
    public static final int FONT_SIZE = UiScale.fontPt(UiScale.UI_POINT_SIZE);

    /** 以下四个是 Qt 逻辑像素的设计值，交给 Windows.clientSize 统一折算一次。 */
    private static final int WANT_WIDTH = 880;
    private static final int MIN_WIDTH = 820;
    private static final int MIN_HEIGHT = 420;
    private static final int MAX_HEIGHT = 640;

    private final JTextArea area;
    private final JScrollPane scroll;
    private final JButton copy;
    private final String displayText;
    private final String fullText;

    private MessageDialog(Window owner, String title, String body, String fullText, int iconType) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.displayText = body;
        this.fullText = fullText == null ? body : fullText;
        area = createArea(body);
        scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(0xdd, 0xdd, 0xdd)));
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getViewport().setBackground(Color.WHITE);

        JPanel content = new JPanel(new BorderLayout(0, UiScale.px(10)));
        content.setBorder(BorderFactory.createEmptyBorder(
                UiScale.px(14), UiScale.px(14), UiScale.px(12), UiScale.px(14)));
        content.add(buildHeader(title, iconType), BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);

        copy = new JButton("复制全部");
        copy.setToolTipText("复制未折行的完整内容，可直接粘贴到工单或聊天窗口");
        copy.addActionListener(e -> copyAll());
        JButton close = new JButton("关闭");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiScale.px(8), 0));
        buttons.add(copy);
        buttons.add(close);
        content.add(buttons, BorderLayout.SOUTH);

        content.setMinimumSize(new Dimension(UiScale.px(680), UiScale.px(320)));
        setContentPane(content);
        getRootPane().setDefaultButton(close);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(true);

        int lines = Math.max(1, body.split("\n", -1).length);
        FontMetrics fm = area.getFontMetrics(area.getFont());
        // clientSize 会把设计值折成物理像素，所以行高先按同一因子折回设计像素，
        // 不能把已经乘过 1.5 的高度再乘一次
        int designLine = Math.max(1,
                (int) Math.round(fm.getHeight() / UiScale.factor()));
        int height = Math.max(MIN_HEIGHT,
                Math.min(MAX_HEIGHT, lines * designLine + 120));
        Windows.clientSize(content, this, WANT_WIDTH, height);
        setMinimumSize(new Dimension(UiScale.px(MIN_WIDTH), UiScale.px(MIN_HEIGHT)));
    }

    private static JPanel buildHeader(String title, int iconType) {
        JPanel head = new JPanel(new BorderLayout(UiScale.px(10), 0));
        head.setOpaque(false);
        Icon icon = icon(iconType);
        if (icon != null) {
            head.add(new JLabel(icon), BorderLayout.WEST);
        }
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD, (float) FONT_SIZE));
        head.add(label, BorderLayout.CENTER);
        return head;
    }

    private static Icon icon(int iconType) {
        String key = iconType == JOptionPane.ERROR_MESSAGE ? "OptionPane.errorIcon"
                : iconType == JOptionPane.WARNING_MESSAGE ? "OptionPane.warningIcon"
                : iconType == JOptionPane.QUESTION_MESSAGE ? "OptionPane.questionIcon"
                : "OptionPane.informationIcon";
        return UIManager.getIcon(key);
    }

    /** 正文控件单独抽出来，自检量到的就是真实弹窗里的那一个。 */
    public static JTextArea createArea(String body) {
        JTextArea area = new JTextArea(body);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(area.getFont().deriveFont(Font.PLAIN, (float) FONT_SIZE));
        area.setBackground(Color.WHITE);
        area.setForeground(new Color(0x22, 0x22, 0x22));
        area.setBorder(BorderFactory.createEmptyBorder(
                UiScale.px(10), UiScale.px(12), UiScale.px(10), UiScale.px(12)));
        area.setCaretPosition(0);
        // 与 Python 结果弹窗同一个下限：长路径与文件清单不至于被挤成一团
        int minW = UiScale.px(MIN_WIDTH);
        int minH = UiScale.px(MIN_HEIGHT);
        area.setMinimumSize(new Dimension(minW, minH));
        // 折行文本区必须先给宽度再问首选高度，算出来的才是「按这个宽度折完有多高」。
        // 直接钉成 420 高的话，80 行报错也只报 420，滚动区以为装得下，尾部就看不到了
        area.setSize(minW, Short.MAX_VALUE);
        Dimension pref = area.getPreferredSize();
        area.setPreferredSize(new Dimension(minW, Math.max(minH, pref.height)));
        return area;
    }

    /**
     * 构造但不显示。GuiMain 的自检就靠这一句量字体、滚动区与窗口尺寸，
     * 不必在事件分发线程上把模态框真摆出来。
     */
    public static MessageDialog create(Window owner, String title, String body,
                                       String fullText, int iconType) {
        return new MessageDialog(owner, title, body, fullText, iconType);
    }

    public static void show(Window owner, String title, String body, String fullText, int iconType) {
        MessageDialog dialog = create(owner, title, body, fullText, iconType);
        dialog.setVisible(true);
        dialog.dispose();
    }

    private void copyAll() {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(fullText), null);
            copy.setText("已复制");
        } catch (RuntimeException ex) {
            copy.setText("复制失败");
        }
        Timer restore = new Timer(1200, e -> copy.setText("复制全部"));
        restore.setRepeats(false);
        restore.start();
    }

    public JTextArea textArea() {
        return area;
    }

    public JScrollPane scrollPane() {
        return scroll;
    }

    public JButton copyButton() {
        return copy;
    }

    public String displayText() {
        return displayText;
    }

    public String fullText() {
        return fullText;
    }

    /** 自检要在事件分发线程外也能问一句：控件树里有没有滚动区。 */
    public static boolean hasScrollPane(JComponent component) {
        return component instanceof JScrollPane
                || SwingUtilities.getAncestorOfClass(JScrollPane.class, component) != null;
    }
}
