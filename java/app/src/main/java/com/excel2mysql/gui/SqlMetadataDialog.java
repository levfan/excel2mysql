package com.excel2mysql.gui;

import com.excel2mysql.core.Infer;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SQL 脚本页的表元数据弹窗：一张表的字段清单。
 *
 * 数据类型一列写完整形状（VARCHAR(64)、DECIMAL(10,2)），与第一页那一列同一个口径，
 * 不拆成"类型 + 长度"两格。给了 FieldEdit 回调时字段名 / 字段注释 / 数据类型三列可改，
 * 每改一格立刻回调（只把真正变了的那几项交出去），被拒绝时那一格填回原值；
 * 没给回调时整表只读（取证与自检走这一路，不去动脚本）。主键与可空两列恒只读。
 */
public final class SqlMetadataDialog extends JDialog {

    /** 一列改写的回调：三项里 null 表示那一项没改；返回 false 表示这次改动被拒绝。 */
    public interface FieldEdit {
        boolean apply(String field, String newField, String newComment, String newType);
    }

    private static final String[] HEADERS = {"字段名", "字段注释", "数据类型", "主键", "可空"};

    /** 字段表外面那层带标题的容器，标题里带列数；自检要读它。 */
    private final JPanel columnsBox;
    private final JTable fieldTable;

    public SqlMetadataDialog(Frame owner, SqlScript.ParsedTable table,
                             String displayName, boolean upper) {
        this(owner, table, displayName, upper, null);
    }

    public SqlMetadataDialog(Frame owner, SqlScript.ParsedTable table,
                             String displayName, boolean upper, FieldEdit edit) {
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
        JLabel scope = new JLabel(edit == null
                ? "这里按脚本里的 CREATE TABLE 原样展示；"
                        + "勾选「表名与字段名转大写」只切换显示，不回写脚本正文。"
                : "字段名、字段注释、数据类型这三列可以直接改，改完就写进脚本正文（还没落盘）；"
                        + "主键与可空照脚本里的定义显示，不在这里改。"
                        + "「表名与字段名转大写」只切换显示，不回写脚本正文。");
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

        // 每行的 (字段名, 注释, 类型) 原值：判"改了哪一格"要用脚本里的名字，
        // 不能用大写开关改过的显示名，否则勾着转大写时一格都改不动
        final List<String[]> originals = new ArrayList<String[]>();
        for (Infer.Column c : table.columns) {
            originals.add(new String[]{c.field, c.comment == null ? "" : c.comment,
                    c.type == null ? "" : c.type});
        }
        DefaultTableModel model = new DefaultTableModel(HEADERS, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return edit != null && col <= 2;
            }

            @Override
            public void setValueAt(Object value, int row, int col) {
                if (col > 2 || row >= originals.size()) {
                    super.setValueAt(value, row, col);
                    return;
                }
                String[] before = originals.get(row);
                String typed = String.valueOf(value == null ? "" : value);
                String[] after = new String[]{before[0], before[1], before[2]};
                after[col] = col == 1 ? typed : typed.trim();
                String newField = after[0].equals(before[0]) ? null : after[0];
                String newComment = after[1].equals(before[1]) ? null : after[1];
                String newType = after[2].equals(before[2]) ? null : after[2];
                if (newField == null && newComment == null && newType == null) {
                    return;
                }
                if (edit != null && edit.apply(before[0], newField, newComment, newType)) {
                    originals.set(row, after);
                    super.setValueAt(col == 1 ? after[1] : after[col], row, col);
                    return;
                }
                // 被拒绝：这一格填回原值（还是按大写开关显示），别留一个改不动的假象
                super.setValueAt(display(before[col], upper), row, col);
            }
        };
        for (int i = 0; i < originals.size(); i++) {
            String[] one = originals.get(i);
            Infer.Column c = table.columns.get(i);
            model.addRow(new Object[]{display(one[0], upper), one[1], one[2],
                    Boolean.TRUE.equals(c.primaryKey) ? "是" : "",
                    Boolean.FALSE.equals(c.nullable) ? "否" : "是"});
        }
        fieldTable = new JTable(model);
        Tables.style(fieldTable);
        fieldTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        fieldTable.getTableHeader().setReorderingAllowed(false);
        fieldTable.getColumnModel().getColumn(0).setPreferredWidth(UiScale.px(230));
        fieldTable.getColumnModel().getColumn(1).setPreferredWidth(UiScale.px(280));
        fieldTable.getColumnModel().getColumn(2).setPreferredWidth(UiScale.px(160));
        fieldTable.getColumnModel().getColumn(3).setPreferredWidth(UiScale.px(70));
        fieldTable.getColumnModel().getColumn(4).setPreferredWidth(UiScale.px(70));
        JScrollPane scroll = new JScrollPane(fieldTable);
        // 表格左上角的标识（与第一页「待导入文件」同一做法）：标题里带列数，
        // 不必滚到表格末尾数这一张表解析出了多少列
        JPanel colBox = new JPanel(new java.awt.BorderLayout());
        columnsBox = colBox;
        Tables.titled(colBox, Tables.COLUMN_TABLE_TITLE + "（共 " + table.columns.size() + " 列）");
        colBox.add(scroll, java.awt.BorderLayout.CENTER);
        content.add(colBox, java.awt.BorderLayout.CENTER);

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

    /** 字段表左上角那句标识（含列数），从真控件的标题边框上读。 */
    public String columnTitle() {
        return Tables.titledOf(columnsBox);
    }

    /** 自检用：列数、列头、某一格能不能改、某一格现在显示什么。 */
    public int columnTotal() {
        return fieldTable.getColumnCount();
    }

    public String headerAt(int col) {
        return fieldTable.getColumnName(col);
    }

    public boolean cellEditable(int row, int col) {
        return fieldTable.getModel().isCellEditable(row, col);
    }

    public String cellValue(int row, int col) {
        Object v = fieldTable.getValueAt(row, col);
        return v == null ? "" : String.valueOf(v);
    }

    /** 自检用：走和用户敲回车完全同一条路（TableModel.setValueAt），不碰内部行数据。 */
    public void editCell(int row, int col, String value) {
        fieldTable.setValueAt(value, row, col);
    }

    /** 字段名那一列按大写开关显示，其余两列原样。 */
    private static String display(String value, boolean upper) {
        String v = value == null ? "" : value;
        return upper ? v.toUpperCase(Locale.ROOT) : v;
    }
}
