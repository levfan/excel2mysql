package com.excel2mysql.gui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextPane;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.util.List;

/**
 * 「SQL 脚本改名」页的 DDL 预览：一张表一个标签页，正文只读等宽。
 *
 * 与第一页的 DDLDialog 同形状（同一档尺寸、同样只读、同样一颗关闭），差别只在正文来源：
 * 第一页是从 Excel 推断出来的表定义现拼 DDL，这一页本来就是脚本正文。
 * 正文先按格式化器排一遍再显示——从前这里照原文贴，脚本里一行到底的 CREATE TABLE
 * 在预览里就是一行长条，读的人只能横向拖。排的是显示，脚本本身一个字不动：
 * 改名、保存、执行仍按原文走，下方那句话当面说清。
 * 对应 Python 侧 main.py 的 SqlScriptDdlDialog。
 */
public final class SqlScriptDdlDialog extends JDialog {

    /** 预览下面那一句：排过版的是显示，没动的是脚本正文，两件事必须分开说。 */
    public static final String NOTE = "预览按格式化器排版；脚本正文一个字没改，改名、保存、执行都按原文走";

    private final JLabel noteLabel = new JLabel(NOTE);

    /** 一页一项：{标签上的表名, 那句建表正文}。 */
    public SqlScriptDdlDialog(Frame owner, List<String[]> pages) {
        super(owner, "DDL 预览", false);
        WindowIcons.apply(this);

        JTabbedPane tabs = new JTabbedPane();
        // 与结构弹窗同理：Qt 的 QTabWidget 默认单行滚动，Swing 默认换行
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        TabScrollers.apply(tabs);
        for (String[] page : pages) {
            // 与第一页那个弹窗同件同法：显示前过一遍格式化器，再按角色上色
            // （字段名与注释两支蓝、类型加粗、关键字一支紫加粗、DROP 走告警橙）
            String shown = com.excel2mysql.core.SqlScript.formatSql(page[1]);
            JTextPane area = new JTextPane();
            area.setEditable(false);
            // Python 用 QFont("Menlo") 的默认档；按 DPI 折成物理像素才对得上
            area.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN,
                    UiScale.fontPt(UiScale.UI_POINT_SIZE)));
            RichText.paint(area, shown, RichText.ddlSpans(shown), UiScale.px(2));
            area.setCaretPosition(0);
            JScrollPane scroll = new JScrollPane(area);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            tabs.addTab(page[0], scroll);
        }

        JButton close = new JButton("关闭");
        close.addActionListener(e -> dispose());
        JPanel row = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, UiScale.px(6), UiScale.px(4)));
        row.add(close);

        JPanel body = new JPanel(new BorderLayout(UiScale.px(6), UiScale.px(6)));
        body.setBorder(BorderFactory.createEmptyBorder(
                UiScale.px(8), UiScale.px(8), UiScale.px(8), UiScale.px(8)));
        body.add(tabs, BorderLayout.CENTER);
        row.setPreferredSize(new Dimension(10, UiScale.px(36)));
        JPanel south = new JPanel(new BorderLayout());
        south.add(noteLabel, BorderLayout.NORTH);
        south.add(row, BorderLayout.SOUTH);
        body.add(south, BorderLayout.SOUTH);
        setContentPane(body);

        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "closeSqlDdl");
        getRootPane().getActionMap().put("closeSqlDdl", new javax.swing.AbstractAction() {
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

    public String tabTitleAt(int index) {
        JTabbedPane tabs = findTabs(getContentPane());
        return tabs == null ? "" : tabs.getTitleAt(index);
    }

    public String tabText(int index) {
        JTextPane pane = paneAt(index);
        return pane == null ? "" : RichText.textOf(pane);
    }

    /** 自检与对撞用：那句"排的是显示、没动的是正文"当前的文字。 */
    public String noteText() {
        return noteLabel.getText();
    }

    /** 自检与对撞用：这一页读回来的角色分布（按颜色认，不读常量）。 */
    public String tabSignature(int index) {
        JTextPane pane = paneAt(index);
        return pane == null ? "" : RichText.roleSignature(pane);
    }

    /** 自检用：这一页那块正文，供"真画出来没有"的像素判据取用。 */
    public java.awt.Component tabView(int index) {
        return paneAt(index);
    }

    private JTextPane paneAt(int index) {
        JTabbedPane tabs = findTabs(getContentPane());
        if (tabs == null) {
            return null;
        }
        java.awt.Component c = tabs.getComponentAt(index);
        if (c instanceof JScrollPane) {
            java.awt.Component inner = ((JScrollPane) c).getViewport().getView();
            if (inner instanceof JTextPane) {
                return (JTextPane) inner;
            }
        }
        return null;
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
