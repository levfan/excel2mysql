package com.excel2mysql.gui;

import com.excel2mysql.core.AppInfo;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
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

    private final Map<String, HintLabel> rows = new LinkedHashMap<String, HintLabel>();
    private final List<String> captions = new ArrayList<String>();

    public AboutDialog(Frame owner) {
        super(owner, "关于", true);
        setSize(380, 168);
        setLocationRelativeTo(owner);
        AppInfo info = AppInfo.load();

        JLabel title = new JLabel("Excel → MySQL 批量导入工具");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 2f));

        JPanel grid = new JPanel(new java.awt.GridLayout(3, 2, 14, 7));
        addRow(grid, "version", "版本", "v" + info.versionText());
        addRow(grid, "commit", "提交", info.commitDisplay());
        addRow(grid, "built", "构建日期", info.builtAt);

        JButton close = new JButton("关闭");
        close.addActionListener(e -> dispose());
        JPanel row = new JPanel(new BorderLayout());
        row.add(close, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(6, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JPanel top = new JPanel(new BorderLayout());
        top.add(title, BorderLayout.NORTH);
        top.add(grid, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);
        row.setPreferredSize(new Dimension(10, 30));
        root.add(row, BorderLayout.SOUTH);
        setContentPane(root);

        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "closeAbout");
        getRootPane().getActionMap().put("closeAbout", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                dispose();
            }
        });
    }

    private void addRow(JPanel grid, String key, String label, String value) {
        JLabel head = new JLabel(label);
        head.setForeground(new Color(0x66, 0x66, 0x66));
        captions.add(label);
        // 值用 HintLabel：提交号要能取走，JLabel 不能拖选，双击即整行复制到剪贴板
        HintLabel body = new HintLabel();
        body.setText(value);
        body.setFont(body.getFont().deriveFont(Font.PLAIN));
        body.setToolTipText("双击复制这一行");
        grid.add(head);
        grid.add(body);
        rows.put(key, body);
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
