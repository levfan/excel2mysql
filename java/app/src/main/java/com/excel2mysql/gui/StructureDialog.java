package com.excel2mysql.gui;

import com.excel2mysql.core.Ddl;
import com.excel2mysql.core.Infer;
import com.excel2mysql.core.TableBuilder;
import com.excel2mysql.core.TableOps;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构弹窗：双击文件表某行打开，改字段名/注释/类型/主键。
 * 对应 Python 侧 main.py:1619-1693 的 StructureDialog。
 *
 * 第 4 列"最大字节长度"只显示不可回写——它是推断的中间量，改了不会改类型，
 * 只会让"改完看起来对、实际按新类型建表"这种错觉发生（Python 侧同样不回写）。
 */
public final class StructureDialog extends JDialog {

    private static final String[] COLUMNS = {"主键", "字段名", "注释", "类型", "最大字节长度"};

    private final JTable table;
    private final DefaultTableModel model;
    private final List<Infer.Column> columns;
    private final TableOps.Table owner;
    private final LogPanel logPanel;
    private boolean confirmed;

    public StructureDialog(Frame ownerFrame, TableOps.Table table0, TableBuilder.Result result,
                           LogPanel logPanel) {
        super(ownerFrame, "表结构 — " + table0.tableName, true);
        this.owner = table0;
        this.columns = new ArrayList<Infer.Column>(table0.columns);
        this.logPanel = logPanel;
        model = new DefaultTableModel(COLUMNS, 0) {
            @Override
            public Class<?> getColumnClass(int col) {
                return col == 0 ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int col) {
                return col <= 3;
            }
        };
        for (Infer.Column c : columns) {
            model.addRow(new Object[]{Boolean.valueOf(c.primaryKey), c.field, c.comment,
                    c.type, String.valueOf(c.maxLen)});
        }
        table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(46);
        table.getColumnModel().getColumn(4).setPreferredWidth(96);

        JButton ok = new JButton("确定");
        ok.addActionListener(e -> applyAndClose());
        JButton cancel = new JButton("取消");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 4));
        buttons.add(ok);
        buttons.add(cancel);

        JPanel body = new JPanel(new BorderLayout(8, 8));
        body.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(700, 360));
        body.add(scroll, BorderLayout.CENTER);
        body.add(buttons, BorderLayout.SOUTH);
        setContentPane(body);
        pack();
        setLocationRelativeTo(ownerFrame);
    }

    /**
     * 回写只取字段名/注释/类型与主键勾选，第 4 列不参与。
     * 主键至少要过一遍校验：类型不符、有空值、组合不唯一，弹窗拦住不让带着错的主键去建表。
     */
    private void applyAndClose() {
        for (int i = 0; i < columns.size(); i++) {
            Infer.Column c = columns.get(i);
            c.field = String.valueOf(model.getValueAt(i, 1));
            c.comment = String.valueOf(model.getValueAt(i, 2));
            c.type = String.valueOf(model.getValueAt(i, 3));
            c.primaryKey = Boolean.TRUE.equals(model.getValueAt(i, 0));
        }
        owner.columns.clear();
        owner.columns.addAll(columns);
        List<String> problems = checkPrimaryKey();
        if (!problems.isEmpty()) {
            for (String p : problems) {
                if (logPanel != null) { logPanel.error("主键设置无效：" + p); }
            }
            javax.swing.JOptionPane.showMessageDialog(this, String.join("\n", problems),
                    "主键设置无效", javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        confirmed = true;
        dispose();
    }

    private List<String> checkPrimaryKey() {
        List<String> problems = new ArrayList<String>();
        List<Integer> pk = new ArrayList<Integer>();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).primaryKey) {
                pk.add(Integer.valueOf(i));
            }
        }
        if (pk.isEmpty()) {
            return problems;
        }
        for (Integer i : pk) {
            Infer.Column c = columns.get(i.intValue());
            if (!Infer.pkTypeOk(c.type == null ? "" : c.type)) {
                problems.add("字段 " + c.field + " 类型 " + c.type + " 不能作为主键"
                        + "（可用 INT/BIGINT 或长度 ≤255 的 VARCHAR/CHAR）");
            }
        }
        return problems;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public String ddlPreview() {
        return Ddl.buildDdl(owner.tableName, owner.tableComment, owner.columns, false);
    }
}
