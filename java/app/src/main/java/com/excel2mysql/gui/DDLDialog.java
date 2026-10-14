package com.excel2mysql.gui;

import com.excel2mysql.core.Ddl;
import com.excel2mysql.core.TableOps;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.util.List;

/**
 * DDL 预览弹窗：一张表一个标签页，正文只读等宽。
 * 对应 main.py:1699-1716 的 DDLDialog——Python 就是把每张表的建表语句分开摆的，
 * 合成一整篇的话几十张表时要自己滚动找，勾选"覆盖已存在表"后哪些语句会变也看不清楚。
 */
public final class DDLDialog extends JDialog {

    public DDLDialog(Frame owner, List<TableOps.Table> tables, boolean dropIfExists) {
        super(owner, "DDL 预览", false);

        JTabbedPane tabs = new JTabbedPane();
        // 与结构弹窗同理：Qt 的 QTabWidget 默认单行滚动，Swing 默认换行
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        for (TableOps.Table t : tables) {
            JTextArea area = new JTextArea();
            area.setEditable(false);
            area.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));
            area.setText(Ddl.buildDdl(t.tableName, t.tableComment, t.columns, dropIfExists));
            area.setCaretPosition(0);
            JScrollPane scroll = new JScrollPane(area);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            tabs.addTab(t.tableName, scroll);
        }

        JButton close = new JButton("关闭");
        close.addActionListener(e -> dispose());
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 4));
        row.add(close);

        JPanel body = new JPanel(new BorderLayout(6, 6));
        body.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        body.add(tabs, BorderLayout.CENTER);
        row.setPreferredSize(new Dimension(10, 36));
        body.add(row, BorderLayout.SOUTH);
        setContentPane(body);

        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "closeDdl");
        getRootPane().getActionMap().put("closeDdl", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                dispose();
            }
        });
        Windows.clientSize(getContentPane(), this, 900, 600);
    }

    /** 自检用：标签页数与每页正文，逐表分开这件事只能这样验。 */
    public int tabCount() {
        JTabbedPane tabs = findTabs(getContentPane());
        return tabs == null ? -1 : tabs.getTabCount();
    }

    /** 标签页标题（表名）：双端界面枚举对撞器要按标题比，正文另有 tabText。 */
    public String tabTitleAt(int index) {
        JTabbedPane tabs = findTabs(getContentPane());
        return tabs == null ? "" : tabs.getTitleAt(index);
    }

    public String tabText(int index) {
        JTabbedPane tabs = findTabs(getContentPane());
        if (tabs == null) {
            return "";
        }
        java.awt.Component c = tabs.getComponentAt(index);
        if (c instanceof JScrollPane) {
            java.awt.Component inner = ((JScrollPane) c).getViewport().getView();
            if (inner instanceof JTextArea) {
                return ((JTextArea) inner).getText();
            }
        }
        return "";
    }

    private static JTabbedPane findTabs(java.awt.Container c) {
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof JTabbedPane) {
                return (JTabbedPane) child;
            }
            if (child instanceof java.awt.Container) {
                JTabbedPane hit = findTabs((java.awt.Container) child);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }
}
