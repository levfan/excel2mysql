package com.excel2mysql.gui;

import com.excel2mysql.core.AppInfo;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「关于」小框：版本 / 提交 / 构建日期 / 作者四行，值全部来自 AppInfo，
 * 底下再垫两行小字讲清提交号与构建日期的来源。对应 ui_help.py 的 AboutDialog。
 *
 * 提交号与构建日期是打包时烧进产物的（目标机没有 git），开发态现场问一次；
 * 问不到就显示"开发态（未打包）"或"无 git 信息"，不编。
 */
public final class AboutDialog extends JDialog {

    /** 「关于」里那一行作者，与 Python 侧 ui_help.AUTHOR 同一份（对撞键 dlg.about.rows 比行名）。 */
    public static final String AUTHOR = "刘帆";

    /**
     * 底下那两行小字：提交号与构建日期从前只写一个值，开发态那句「开发态（未打包）」
     * 看不出所以然。这一格哪来的、取不到会写什么、自己核该跑哪条命令，当场说清。
     * 与 Python 侧 ui_help 的 ABOUT_FOOT_1 / ABOUT_FOOT_2 逐字相同，由 dlg.about.footnote 比。
     */
    public static final String FOOT_1 =
            "提交号与构建日期取自构建戳：交付包里是打包那一刻烧进去的那一份，"
            + "直接跑源码时现场问 git";
    public static final String FOOT_2 =
            "问不到就写「无 git 信息」，不编一个号出来；"
            + "要核当前源码版本，在仓库目录里跑 git rev-parse --short HEAD";

    private final Map<String, HintLabel> rows = new LinkedHashMap<String, HintLabel>();
    private final List<String> captions = new ArrayList<String>();
    private final JTextArea footnote = new JTextArea(FOOT_1 + "\n" + FOOT_2);

    public AboutDialog(Frame owner) {
        this(owner, AppInfo.load());
    }

    /** 自检用：喂一份指定构建信息，覆盖“提交号带着长分支名”这类现场值。 */
    public AboutDialog(Frame owner, AppInfo info) {
        super(owner, "关于", true);
        WindowIcons.apply(this);

        JLabel title = new JLabel("Excel → MySQL 批量导入工具");
        // Python 是默认 9pt 再加 2pt；这里按 11pt 折成物理像素（144dpi → 22px）
        title.setFont(title.getFont().deriveFont(Font.BOLD,
                (float) UiScale.fontPt(UiScale.UI_POINT_SIZE + 2)));

        // 标题与四行之间垫一条细线：原来两档字号之间只靠空白分开，扫一眼分不出
        // 哪是产品名、哪是这台机器上这一份的构建信息（与 Qt 那条 QFrame.HLine 同义）
        JSeparator rule = new JSeparator(SwingConstants.HORIZONTAL);

        // 两列不能等宽：GridLayout 会按容器宽一分为二，长提交号只能挤在 171px 里被裁；
        // 这一列要按文字自己报的宽度算，多出来的宽度再给它
        JPanel grid = new JPanel(new java.awt.GridBagLayout());
        addRow(grid, "version", "版本", "v" + info.versionText(), false);
        addRow(grid, "commit", "提交", info.commitDisplay(), false);
        addRow(grid, "built", "构建日期", info.builtAt, false);
        addRow(grid, "author", "作者", AUTHOR, true);
        rows.get("commit").setToolTipText("这一行从哪来见下方两行小字；双击可整行复制");
        rows.get("built").setToolTipText("交付包里写的是打包完成那一刻；"
                + "直接跑源码时这一格是「开发态（未打包）」");

        // 两行小字要跟着窗口宽度折行：JLabel 不折行（除非上 HTML，而两栈的 HTML 渲染
        // 不是一套），用只读的 JTextArea 原生折行，顺带还能选中整段贴给别人
        footnote.setEditable(false);
        footnote.setOpaque(false);
        footnote.setForeground(new Color(0x66, 0x66, 0x66));
        footnote.setFont(title.getFont().deriveFont(Font.PLAIN,
                (float) UiScale.fontPt(UiScale.UI_POINT_SIZE - 1)));
        footnote.setLineWrap(true);
        footnote.setWrapStyleWord(true);
        // 不能 setCaret(null)：折行视图绘制时要读选区起点，光标被抽掉就是
        // WrappedPlain.paint 里的空指针（拍图才画得到，自检不画窗口发现不了）。
        // 只读 + 不可聚焦就已经没有闪烁光标，仍然可以拖选复制。
        footnote.setFocusable(false);
        footnote.setCaretColor(new Color(0, 0, 0, 0));
        footnote.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.TEXT_CURSOR));

        JButton close = new JButton("关闭");
        close.addActionListener(e -> dispose());
        JPanel row = new JPanel(new BorderLayout());
        row.add(close, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(UiScale.px(6), UiScale.px(10)));
        root.setBorder(BorderFactory.createEmptyBorder(
                UiScale.px(10), UiScale.px(12), UiScale.px(10), UiScale.px(12)));
        JPanel top = new JPanel(new BorderLayout());
        JPanel head = new JPanel(new BorderLayout(UiScale.px(6), UiScale.px(6)));
        head.add(title, BorderLayout.NORTH);
        head.add(rule, BorderLayout.SOUTH);
        top.add(head, BorderLayout.NORTH);
        top.add(grid, BorderLayout.CENTER);
        root.add(top, BorderLayout.NORTH);
        root.add(footnote, BorderLayout.CENTER);
        root.add(row, BorderLayout.SOUTH);
        setContentPane(root);
        // 首选尺寸只说"文字本身要多宽"：JTextField 左右还要各留 2px，且默认 380 宽在
        // 大字号或长提交号下不够。把这点余量并进内容最小尺寸，窗口就只会长不会裁
        java.awt.Dimension need = root.getPreferredSize();
        need.width += 8;
        root.setMinimumSize(need);

        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "closeAbout");
        getRootPane().getActionMap().put("closeAbout", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                dispose();
            }
        });
        // 尺寸要等组件都摆好再算：pack 之前设尺寸等于跟布局抢活
        // 250 那档是垫了分隔线与两行小字之后要的：仍按客户区给，换字号只会变高不会裁字
        Windows.clientSize(getContentPane(), this, 380, 250);
    }

    /** 自检与对撞用：底下那两行小字当前的文字（与 Qt 侧 foot.text() 逐字比）。 */
    public String footnoteText() {
        return footnote.getText();
    }

    /** 自检用：那两行小字的组件本身，供"真画一次"的像素判据取用。 */
    public java.awt.Component footnoteView() {
        return footnote;
    }

    private void addRow(JPanel grid, String key, String label, String value, boolean last) {
        int row = rows.size();
        JLabel head = new JLabel(label);
        head.setForeground(new Color(0x66, 0x66, 0x66));
        captions.add(label);
        // 值用 HintLabel：提交号要能取走，JLabel 不能拖选，双击即整行复制到剪贴板
        HintLabel body = new HintLabel();
        body.setText(value);
        body.setFont(body.getFont().deriveFont(Font.PLAIN));
        body.setToolTipText("双击复制这一行");
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = java.awt.GridBagConstraints.WEST;
        c.insets = new java.awt.Insets(0, 0,
                last ? 0 : UiScale.px(7), UiScale.px(14));
        grid.add(head, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        grid.add(body, c);
        rows.put(key, body);
    }

    /**
     * 自检用：首屏有没有文字被压得放不下。按控件自己的首选尺寸算，不写死像素，
     * 换字号、换机器都咬得住；空串表示没有裁字。
     *
     * <p>JTextField 的首选宽只报文字宽、不含 margin，所以文本框要把左右 margin 补回去，
     * 否则“刚好等于文字宽”这种贴边情形会漏过去。
     */
    public String fitProblems() {
        return fitProblems(getContentPane());
    }

    private static String fitProblems(java.awt.Container parent) {
        for (java.awt.Component k : parent.getComponents()) {
            if (!k.isVisible()) {
                continue;
            }
            String text = null;
            if (k instanceof JLabel) {
                text = ((JLabel) k).getText();
            } else if (k instanceof javax.swing.AbstractButton) {
                text = ((javax.swing.AbstractButton) k).getText();
            } else if (k instanceof javax.swing.JTextField) {
                text = ((javax.swing.JTextField) k).getText();
            }
            String name = text == null || text.isEmpty()
                    ? k.getClass().getSimpleName() : "「" + text + "」";
            if (k.getX() < 0 || k.getY() < 0
                    || k.getX() + k.getWidth() > parent.getWidth()
                    || k.getY() + k.getHeight() > parent.getHeight()) {
                return name + "摆到了容器外面";
            }
            if (text != null && !text.isEmpty()) {
                java.awt.Dimension need = k.getPreferredSize();
                if (k instanceof javax.swing.JTextField) {
                    java.awt.Insets m = ((javax.swing.JTextField) k).getMargin();
                    // 再留 2px：刚好等于文字宽就是贴边，字体度量差一点就会裁
                    need.width += m.left + m.right + 2;
                }
                if (k.getWidth() < need.width || k.getHeight() < need.height) {
                    return name + "只分到 " + k.getWidth() + "x" + k.getHeight()
                            + "，至少要 " + need.width + "x" + need.height;
                }
            }
            if (k instanceof java.awt.Container) {
                String sub = fitProblems((java.awt.Container) k);
                if (!sub.isEmpty()) {
                    return sub;
                }
            }
        }
        return "";
    }

    public String valueOf(String key) {
        HintLabel label = rows.get(key);
        return label == null ? null : label.hintText();
    }

    /** 三行的行名。双端对撞器只比行名：值里带提交号与构建时刻，两边必然不同。 */
    public List<String> rowCaptions() {
        return captions;
    }
}
