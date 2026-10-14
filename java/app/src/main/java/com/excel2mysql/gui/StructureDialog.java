package com.excel2mysql.gui;

import com.excel2mysql.core.Infer;
import com.excel2mysql.core.TableBuilder;
import com.excel2mysql.core.TableOps;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构弹窗：一张表一个标签页，改字段名/注释/类型/主键。
 * 对应 Python 侧 main.py:1619-1693 的 StructureDialog（同样是全表标签页，不是单表）。
 *
 * 第 4 列"最大字节长度"只显示不可回写——它是推断的中间量，改了不会改类型，
 * 只会让"改完看起来对、实际按新类型建表"这种错觉发生（Python 侧同样不回写）。
 */
public final class StructureDialog extends JDialog {

    private static final String[] COLUMNS = {"主键", "字段名(拼音/首字母)", "字段注释(中文表头)",
            "类型", "最大字节长度"};
    private static final String TIP =
            "勾选一列或多列作为主键（多列即组合主键，主键约束至多一个）；"
            + "主键需全列非空且组合唯一，全部不勾选则不设主键";

    /** 一个标签页背后的一份编辑状态。 */
    private static final class Editor {
        final TableOps.Table table;
        final List<List<Object>> rows;
        final DefaultTableModel model;

        Editor(TableOps.Table table, List<List<Object>> rows) {
            this.table = table;
            this.rows = rows;
            this.model = new DefaultTableModel(COLUMNS, 0) {
                @Override
                public Class<?> getColumnClass(int col) {
                    return col == 0 ? Boolean.class : String.class;
                }

                @Override
                public boolean isCellEditable(int row, int col) {
                    // 五列都可编辑，和 Python 侧一致；第 4 列"最大字节长度"改了也不回写，
                    // 但禁掉编辑会让人以为它是锁死的关键信息
                    return true;
                }
            };
            for (Infer.Column c : table.columns) {
                this.model.addRow(new Object[]{Boolean.valueOf(c.primaryKey), c.field, c.comment,
                        c.type, String.valueOf(c.maxLen)});
            }
        }

        /** 只收集到副本，不碰 table.columns——校验没过就不能让改动落到表定义上。 */
        List<Infer.Column> stage() {
            List<Infer.Column> staged = new ArrayList<Infer.Column>();
            for (int i = 0; i < table.columns.size(); i++) {
                Infer.Column c = table.columns.get(i).copied();
                Object field = model.getValueAt(i, 1);
                if (field != null && !String.valueOf(field).trim().isEmpty()) {
                    c.field = String.valueOf(field).trim();
                }
                c.comment = String.valueOf(model.getValueAt(i, 2));
                Object type = model.getValueAt(i, 3);
                if (type != null && !String.valueOf(type).trim().isEmpty()) {
                    c.type = String.valueOf(type).trim();
                }
                c.primaryKey = Boolean.TRUE.equals(model.getValueAt(i, 0));
                staged.add(c);
            }
            return staged;
        }
    }

    private final List<Editor> editors = new ArrayList<Editor>();
    private final LogPanel logPanel;
    private boolean confirmed;

    public StructureDialog(Frame ownerFrame, List<TableBuilder.Result> results, LogPanel logPanel) {
        super(ownerFrame, "表结构预览（可编辑）", true);
        this.logPanel = logPanel;

        JTabbedPane tabs = new JTabbedPane();
        // 表多时 Qt 是单行加左右箭头，不是换行堆两排标签
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        for (TableBuilder.Result r : results) {
            Editor ed = new Editor(r.table, r.rows);
            editors.add(ed);
            JTable table = new JTable(ed.model);
            table.setFillsViewportHeight(true);
            table.setRowHeight(22);
            // 主键列窄、其余列分摊剩余宽度：对应 Qt 的 ResizeToContents(0) + Stretch(1,2)，
            // Swing 没有"按内容自适应"这一档，用固定窄列 + 后续列均分来贴
            table.setAutoResizeMode(javax.swing.JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
            table.getColumnModel().getColumn(0).setPreferredWidth(46);
            table.getColumnModel().getColumn(4).setPreferredWidth(96);
            JScrollPane scroll = new JScrollPane(table);
            scroll.setPreferredSize(new Dimension(900, 420));
            tabs.addTab(r.table.tableName, scroll);
        }

        JButton ok = new JButton("确定");
        ok.addActionListener(e -> confirmClicked());
        JButton cancel = new JButton("取消");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 4));
        buttons.add(ok);
        buttons.add(cancel);

        JLabel tip = new JLabel(TIP);
        tip.setForeground(new java.awt.Color(0x66, 0x66, 0x66));

        JPanel body = new JPanel(new BorderLayout(8, 8));
        body.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        body.add(tip, BorderLayout.NORTH);
        body.add(tabs, BorderLayout.CENTER);
        body.add(buttons, BorderLayout.SOUTH);
        setContentPane(body);
        // Qt 的 QDialog 按 Esc 就是 reject；Swing 的 JDialog 默认什么都不做，
        // 得自己绑一条焦点期按键，否则"关了但没取消"和"取消"在用户眼里是一回事，代码里不是。
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                        "close");
        getRootPane().getActionMap().put("close", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                dispose();
            }
        });
        Windows.clientSize(getContentPane(), this, 980, 640);
    }

    /** Esc 是否绑到了"关掉且不写回"：自检不开框也能查（开框会把自检自己卡死）。 */
    public boolean closesOnEscape() {
        return getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0))
                != null;
    }

    private final List<String> problems = new ArrayList<String>();

    /**
     * 先把每张表的编辑收到副本并全部校验，通过后才统一写回：
     * 一张表的主键无效不能让前面已写回的表停在改了一半的状态。
     */
    private boolean applyEdits() {
        problems.clear();
        List<List<Infer.Column>> staged = new ArrayList<List<Infer.Column>>();
        for (Editor ed : editors) {
            List<Infer.Column> cols = ed.stage();
            staged.add(cols);
            String bad = TableBuilder.validatePrimaryKey(ed.rows, cols);
            if (!bad.isEmpty()) {
                problems.add("表「" + ed.table.tableName + "」：" + bad);
            }
        }
        if (!problems.isEmpty()) {
            for (String pr : problems) {
                if (logPanel != null) {
                    logPanel.error("主键设置无效：" + pr);
                }
            }
            return false;
        }
        for (int i = 0; i < editors.size(); i++) {
            TableOps.Table t = editors.get(i).table;
            t.columns.clear();
            t.columns.addAll(staged.get(i));
        }
        confirmed = true;
        dispose();
        return true;
    }

    /** 确定按钮的真实路径：校验不过要弹窗拦住，并让弹窗保持在编辑态。 */
    private void confirmClicked() {
        if (applyEdits()) {
            return;
        }
        javax.swing.JOptionPane.showMessageDialog(this,
                String.join("\n", problems) + "\n\n请调整主键勾选或字段类型后重试。",
                "主键设置无效", javax.swing.JOptionPane.WARNING_MESSAGE);
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    /** 供自检直接驱动"确定"背后的逻辑，不必真把模态框摆出来。 */
    public List<String> tabTitles() {
        List<String> out = new ArrayList<String>();
        for (Editor ed : editors) {
            out.add(ed.table.tableName);
        }
        return out;
    }

    /** 自检用：表头原文与"哪些格能改"都是界面属性，得能读回来才谈得上盯住。 */
    public String columnHeader(int tabIndex, int col) {
        return editors.get(tabIndex).model.getColumnName(col);
    }

    public boolean cellEditable(int tabIndex, int row, int col) {
        return editors.get(tabIndex).model.isCellEditable(row, col);
    }

    public void setModelValue(int tabIndex, int row, int col, Object value) {
        editors.get(tabIndex).model.setValueAt(value, row, col);
    }

    public String modelValue(int tabIndex, int row, int col) {
        Object v = editors.get(tabIndex).model.getValueAt(row, col);
        return v == null ? "" : String.valueOf(v);
    }

    public int rowCount(int tabIndex) {
        return editors.get(tabIndex).model.getRowCount();
    }

    /** 走同一条 applyEdits，只是不弹模态框——自检在 EDT 上，弹窗会把自己卡死。 */
    public boolean pressOk() {
        return applyEdits();
    }

    public List<String> problems() {
        return problems;
    }
}
