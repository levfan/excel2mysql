package com.excel2mysql.gui;

import com.excel2mysql.core.AppInfo;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
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
 * 「关于」小框：版本 / 提交 / 构建日期 / 作者四行，值全部来自 AppInfo。
 * 对应 ui_help.py 的 AboutDialog。
 *
 * 提交号与构建日期是打包时烧进产物的（目标机没有 git），开发态现场问一次；
 * 问不到就显示"开发态（未打包）"或"无 git 信息"，不编。来源这一层不占界面，
 * 挂在「提交」「构建日期」两行的悬浮提示上，说明文字与 Python 侧逐字同形。
 */
public final class AboutDialog extends JDialog {

    /** 「关于」里那一行作者，与 Python 侧 ui_help.AUTHOR 同一份（对撞键 dlg.about.rows 比行名）。 */
    public static final String AUTHOR = "刘帆";

    /**
     * 「提交」这一行的来源说明：这一格哪来的、取不到会写什么、自己核该跑哪条命令。
     * 与 Python 侧 ui_help.COMMIT_TIP 逐字相同；各栈只在末尾接自己那套取用方式
     * （Qt 鼠标选中、Swing 双击整行），两侧自检都按同样的三个关键词判。
     */
    public static final String COMMIT_TIP =
            "提交号取自构建戳：交付包里是打包那一刻烧进去的那一份，"
            + "直接跑源码时现场问 git；问不到写「无 git 信息」，不编一个号出来。"
            + "要核当前源码版本，在仓库目录里跑 git rev-parse --short HEAD";
    /** 「构建日期」这一行的来源说明，与 Python 侧 ui_help.BUILT_TIP 逐字相同。 */
    public static final String BUILT_TIP =
            "构建日期同样取自构建戳：交付包里写的是打包完成那一刻，"
            + "直接跑源码时这一格显示「开发态（未打包）」";
    /** 「复制全部」那颗按钮的三种状态，与 Python 侧 ui_help 的同名常量逐字相同（对撞比）。 */
    public static final String COPY_LABEL = "复制全部";
    public static final String COPIED_LABEL = "已复制";
    public static final String CLIP_FAIL_LABEL = "剪贴板打不开";
    /** 值那一行的取用方式：JTextField 拖选是真能选，双击只负责把整行选上，不吹"即复制"。 */
    static final String COPY_HINT = "双击整行选中，Ctrl+C 复制";

    private final Map<String, HintLabel> rows = new LinkedHashMap<String, HintLabel>();
    private final List<String> captions = new ArrayList<String>();
    private JButton copyButton;
    private JLabel headingLabel;

    public AboutDialog(Frame owner) {
        this(owner, AppInfo.load());
    }

    /** 自检用：喂一份指定构建信息，覆盖“提交号带着长分支名”这类现场值。 */
    public AboutDialog(Frame owner, AppInfo info) {
        super(owner, "关于", true);
        WindowIcons.apply(this);

        JLabel title = new JLabel(AppInfo.APP_NAME);
        headingLabel = title;
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
        rows.get("commit").setToolTipText(COMMIT_TIP + "；" + COPY_HINT);
        rows.get("built").setToolTipText(BUILT_TIP);

        JButton close = new JButton("关闭");
        close.addActionListener(e -> dispose());
        JButton copy = new JButton(COPY_LABEL);
        copy.setToolTipText("把上面四行一次放进剪贴板（提交号给 40 位完整值）");
        copy.addActionListener(e -> {
            String text = copyText();
            boolean ok = false;
            try {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                        new java.awt.datatransfer.StringSelection(text), null);
                // 写了不等于放进去了：没有桌面会话、或被别的程序占着剪贴板时会直接抛
                // IllegalStateException，也有环境静默落空。回读一次，回执只说实话
                Object back = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                ok = text.equals(back == null ? null : String.valueOf(back));
            } catch (Exception ignored) {
                ok = false;
            }
            copy.setText(ok ? COPIED_LABEL : CLIP_FAIL_LABEL);
            javax.swing.Timer back2 = new javax.swing.Timer(2000, ev -> copy.setText(COPY_LABEL));
            back2.setRepeats(false);
            back2.start();
        });
        copyButton = copy;
        JPanel row = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, UiScale.px(6), 0));
        buttons.add(copy);
        buttons.add(close);
        row.add(buttons, BorderLayout.EAST);

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
        // 230 那档是垫了分隔线、底下又多出「复制全部」那一行之后要的：
        // 仍按客户区给，换字号只会变高不会裁字
        Windows.clientSize(getContentPane(), this, 380, 230);
    }

    /** 自检用：某一行的悬浮提示文字（来源说明挂在提示里，界面不再铺小字）。 */
    public String tipOf(String key) {
        HintLabel label = rows.get(key);
        return label == null ? null : label.getToolTipText();
    }

    /** 自检用：那颗按钮当前的文字（点下去要先变「已复制」，两秒后复原）。 */
    public String copyButtonText() {
        return copyButton == null ? "" : copyButton.getText();
    }

    /** 自检与双端对撞用：框面上那一行产品名 + 复制按钮的两种文字。 */
    public String headingText() {
        return headingLabel == null ? "" : headingLabel.getText();
    }

    public String copyLabels() {
        return copyButtonText() + "|" + COPIED_LABEL + "|" + CLIP_FAIL_LABEL;
    }

    /**
     * 「复制全部」放进剪贴板的那一份文本，与 Python 侧 {@code copy_text()} 同一形状。
     *
     * <p>正面那一格仍是短号（一行放得下、也够人眼看），贴出去的要能一路粘进 git 里查，
     * 所以这里给 40 位；老产物没烧全号时退回短号，不写一个空值出去。
     */
    public String copyText() {
        return copyText(AppInfo.load());
    }

    /** 自检用：喂一份指定构建信息算出同一份文本。 */
    public static String copyText(AppInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append(AppInfo.APP_NAME).append('\n');
        sb.append("版本：v").append(info.versionText()).append('\n');
        sb.append("提交：").append(info.commitDisplayFor(info.fullCommit())).append('\n');
        sb.append("构建日期：").append(info.builtAt).append('\n');
        sb.append("作者：").append(AUTHOR);
        return sb.toString();
    }

    private void addRow(JPanel grid, String key, String label, String value, boolean last) {
        int row = rows.size();
        JLabel head = new JLabel(label);
        head.setForeground(new Color(0x66, 0x66, 0x66));
        captions.add(label);
        // 值用 HintLabel（不可编辑的 JTextField）：拖选、高亮、Ctrl+C 都是现成的。
        // 双击不吹"即复制"——那要自己编一套；这里让双击把整行选上，剩下的一步照旧走 Ctrl+C
        HintLabel body = new HintLabel();
        body.setText(value);
        body.setFont(body.getFont().deriveFont(Font.PLAIN));
        body.setToolTipText(COPY_HINT);
        body.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    body.selectAll();
                }
            }
        });
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
