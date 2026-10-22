package com.excel2mysql.gui;

import com.excel2mysql.core.AppInfo;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「关于」小框：版本 / 提交 / 构建日期三行，值全部来自 AppInfo。
 * 对应 ui_help.py:104-157。
 *
 * 提交号与构建日期是打包时烧进产物的（目标机没有 git），开发态现场问一次；
 * 问不到就显示"开发态（未打包）"或"无 git 信息"，不编。
 */
public final class AboutDialog extends JDialog {

    /** 「关于」里那一行作者，与 Python 侧 ui_help.AUTHOR 同一份（对撞键 dlg.about.rows 比行名）。 */
    public static final String AUTHOR = "刘帆";

    private final Map<String, HintLabel> rows = new LinkedHashMap<String, HintLabel>();
    private final List<String> captions = new ArrayList<String>();

    public AboutDialog(Frame owner) {
        this(owner, AppInfo.load());
    }

    /** 自检用：喂一份指定构建信息，覆盖“提交号带着长分支名”这类现场值。 */
    public AboutDialog(Frame owner, AppInfo info) {
        super(owner, "关于", true);

        JLabel title = new JLabel("Excel → MySQL 批量导入工具");
        // Python 是默认 9pt 再加 2pt；这里按 11pt 折成物理像素（144dpi → 22px）
        title.setFont(title.getFont().deriveFont(Font.BOLD,
                (float) UiScale.fontPt(UiScale.UI_POINT_SIZE + 2)));

        // 两列不能等宽：GridLayout 会按容器宽一分为二，长提交号只能挤在 171px 里被裁；
        // 这一列要按文字自己报的宽度算，多出来的宽度再给它
        JPanel grid = new JPanel(new java.awt.GridBagLayout());
        addRow(grid, "version", "版本", "v" + info.versionText(), false);
        addRow(grid, "commit", "提交", info.commitDisplay(), false);
        addRow(grid, "built", "构建日期", info.builtAt, false);
        addRow(grid, "author", "作者", AUTHOR, true);

        JButton close = new JButton("关闭");
        close.addActionListener(e -> dispose());
        JPanel row = new JPanel(new BorderLayout());
        row.add(close, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(UiScale.px(6), UiScale.px(10)));
        root.setBorder(BorderFactory.createEmptyBorder(
                UiScale.px(10), UiScale.px(12), UiScale.px(10), UiScale.px(12)));
        JPanel top = new JPanel(new BorderLayout());
        top.add(title, BorderLayout.NORTH);
        top.add(grid, BorderLayout.SOUTH);
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
        Windows.clientSize(getContentPane(), this, 380, 190);
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
