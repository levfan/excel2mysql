package com.excel2mysql.gui;

import com.excel2mysql.core.Infer;
import com.excel2mysql.core.MetaExport;
import com.excel2mysql.core.SqlScript;

import javax.swing.BorderFactory;
import javax.swing.GroupLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.LayoutStyle;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.Frame;
import java.util.Locale;

/** SQL 脚本页的表元数据弹窗：展示脚本里 CREATE TABLE 已解析出的字段定义，不回写脚本。 */
public final class SqlMetadataDialog extends JDialog {

    public SqlMetadataDialog(Frame owner, SqlScript.ParsedTable table,
                             String displayName, boolean upper) {
        super(owner, "表元数据（" + displayName + "）", false);
        JPanel content = new JPanel(new java.awt.BorderLayout(0, UiScale.px(6)));
        content.setBorder(BorderFactory.createEmptyBorder(
                UiScale.px(8), UiScale.px(10), UiScale.px(8), UiScale.px(10)));

        JPanel header = new JPanel();
        GroupLayout layout = new GroupLayout(header);
        header.setLayout(layout);
        layout.setAutoCreateGaps(true);
        layout.setAutoCreateContainerGaps(true);
        JLabel tableName = new JLabel("表名：" + displayName);
        JLabel tableComment = new JLabel("表注释："
                + (table.tableComment == null || table.tableComment.trim().isEmpty()
                ? "（无）" : table.tableComment.trim()));
        JLabel scope = new JLabel("这里按脚本里的 CREATE TABLE 原样展示；"
                + "勾选「表名与字段名转大写」只切换显示，不回写脚本正文。");
        tableName.getAccessibleContext().setAccessibleName("表名");
        layout.setHorizontalGroup(layout.createParallelGroup(GroupLayout.Alignment.LEADING)
                .addComponent(tableName)
                .addComponent(tableComment)
                .addComponent(scope));
        layout.setVerticalGroup(layout.createSequentialGroup()
                .addComponent(tableName)
                .addComponent(tableComment)
                .addComponent(scope));
        content.add(header, java.awt.BorderLayout.NORTH);

        String[] columns = {"字段名", "字段注释", "数据类型", "长度", "主键", "可空"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return false;
            }
        };
        for (Infer.Column c : table.columns) {
            String[] type = MetaExport.splitType(c.type == null ? "" : c.type);
            model.addRow(new Object[]{
                    upper ? c.field.toUpperCase(Locale.ROOT) : c.field,
                    c.comment == null ? "" : c.comment,
                    type[0], type[1] == null ? "" : type[1],
                    Boolean.TRUE.equals(c.primaryKey) ? "是" : "",
                    Boolean.FALSE.equals(c.nullable) ? "否" : "是"});
        }
        JTable fieldTable = new JTable(model);
        fieldTable.setRowHeight(UiScale.px(22));
        fieldTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        fieldTable.getTableHeader().setReorderingAllowed(false);
        fieldTable.getColumnModel().getColumn(0).setPreferredWidth(UiScale.px(230));
        fieldTable.getColumnModel().getColumn(1).setPreferredWidth(UiScale.px(280));
        fieldTable.getColumnModel().getColumn(2).setPreferredWidth(UiScale.px(130));
        fieldTable.getColumnModel().getColumn(3).setPreferredWidth(UiScale.px(80));
        JScrollPane scroll = new JScrollPane(fieldTable);
        content.add(scroll, java.awt.BorderLayout.CENTER);

        JPanel footer = new JPanel();
        footer.setLayout(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, UiScale.px(6), 0));
        JButton close = new JButton("关闭");
        close.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(close);
        footer.add(close);
        content.add(footer, java.awt.BorderLayout.SOUTH);

        setContentPane(content);
        Windows.clientSize(content, this, 980, 640);
        setLocationRelativeTo(owner);
    }
}
