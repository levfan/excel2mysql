package com.excel2mysql.gui;

import com.excel2mysql.core.Infer;
import com.excel2mysql.core.SqlScript;

import javax.swing.BorderFactory;
import javax.swing.GroupLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.LayoutStyle;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.Frame;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL 脚本页的表元数据弹窗：一张表的字段清单。
 *
 * 数据类型一列写完整形状（VARCHAR(64)、DECIMAL(10,2)），与第一页那一列同一个口径，
 * 不拆成"类型 + 长度"两格。给了 FieldEdit 回调时字段名 / 字段注释 / 数据类型三列可改，
 * 每改一格立刻回调（只把真正变了的那几项交出去），被拒绝时那一格填回原值；
 * 没给回调时整表只读（取证与自检走这一路，不去动脚本）。主键与可空两列恒只读。
 *
 * 给了 ColumnOps 回调时另外多两颗按钮：「新增字段」「删除选中字段」（Del 同效）。
 * 增删改的都是脚本正文那句 CREATE TABLE，删完由调用方决定要不要落盘。
 */
public final class SqlMetadataDialog extends JDialog {

    /** 一列改写的回调：三项里 null 表示那一项没改；返回 false 表示这次改动被拒绝。 */
    public interface FieldEdit {
        boolean apply(String field, String newField, String newComment, String newType);
    }

    /** 增删字段的回调：返回改完之后的列清单，null 表示这次操作被拒绝。 */
    public interface ColumnOps {
        List<Infer.Column> addField();

        List<Infer.Column> deleteFields(List<String> fields);
    }

    private static final String[] HEADERS = {"字段名", "字段注释", "数据类型", "主键", "可空"};
    /** 列宽设计下限；富余宽度只摊给字段名/注释/类型三列（0/1/2）。 */
    private static final int[] METADATA_COL_FLOORS = {UiScale.px(230), UiScale.px(280),
            UiScale.px(160), UiScale.px(70), UiScale.px(70)};
    private static final int[] METADATA_COL_STRETCH = {0, 1, 2};

    /** 字段表外面那层带标题的容器，标题里带列数；自检要读它。 */
    private final JPanel columnsBox;
    private FillTable fieldTable;
    private final DefaultTableModel model;

    private final boolean upper;
    private final boolean lower;
    private final FieldEdit edit;
    private final ColumnOps ops;

    /** 每行的 (字段名, 注释, 类型) 原值：判"改了哪一格"要用脚本里的名字，不能用大写开关改过的显示名。 */
    private final List<String[]> originals = new ArrayList<String[]>();

    public SqlMetadataDialog(Frame owner, SqlScript.ParsedTable table,
                             String displayName, boolean upper, boolean lower,
                             FieldEdit edit) {
        this(owner, table, displayName, upper, lower, edit, null);
    }

    public SqlMetadataDialog(Frame owner, SqlScript.ParsedTable table,
                             String displayName, boolean upper, boolean lower,
                             FieldEdit edit, ColumnOps ops) {
        super(owner, "表元数据（" + displayName + "）", false);
        WindowIcons.apply(this);
        this.upper = upper;
        this.lower = lower;
        this.edit = edit;
        this.ops = ops;
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
                        + "勾选「表名与字段名转大写」或「转小写」只切换显示，不回写脚本正文。"
                : "字段名、字段注释、数据类型这三列可以直接改，改完就写进脚本正文（还没落盘）；"
                        + "主键与可空照脚本里的定义显示，不在这里改。"
                        + "「表名与字段名转大写」或「转小写」只切换显示，不回写脚本正文。");
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

        model = new DefaultTableModel(HEADERS, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return SqlMetadataDialog.this.edit != null && col <= 2;
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
                if (SqlMetadataDialog.this.edit != null
                        && SqlMetadataDialog.this.edit.apply(before[0], newField, newComment,
                        newType)) {
                    originals.set(row, after);
                    super.setValueAt(col == 1 ? after[1] : after[col], row, col);
                    return;
                }
                // 被拒绝：这一格填回原值（仍按当前大小写档显示），别留一个改不动的假象
                super.setValueAt(display(before[col]), row, col);
            }
        };
        fill(table.columns);

        fieldTable = new FillTable(model, METADATA_COL_FLOORS, METADATA_COL_STRETCH);
        Tables.style(fieldTable);
        fieldTable.fitColumns();   // 字体定下来之后重算底宽
        fieldTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        fieldTable.getTableHeader().setReorderingAllowed(false);
        // 关掉列自适应：超长字段名不会被切进省略号，表格比弹窗宽时横向滚动条把尾巴带出来；
        // 视口比底宽宽时把富余摊给字段名/注释/类型三列铺满弹窗
        // （Qt 侧同一个做法：FillWidthTable + fill_widths）
        fieldTable.setAutoResizeMode(javax.swing.JTable.AUTO_RESIZE_OFF);
        JScrollPane scroll = new JScrollPane(fieldTable);
        // 表格左上角的标识（与第一页「待导入文件」同一做法）：标题里带列数，
        // 不必滚到表格末尾数这一张表解析出了多少列
        JPanel colBox = new JPanel(new java.awt.BorderLayout());
        columnsBox = colBox;
        Tables.titled(colBox, Tables.COLUMN_TABLE_TITLE + "（共 "
                + table.columns.size() + " 列）");
        colBox.add(scroll, java.awt.BorderLayout.CENTER);

        // 增删字段挂在表格上沿：与第一页结构弹窗同一个位置、同一套措辞
        JPanel tools = new JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, UiScale.px(6), UiScale.px(2)));
        JButton addField = new JButton("新增字段");
        addField.setToolTipText("<html>往这句 CREATE TABLE 末尾补一列（先叫 is_new_field），"
                + "<br>再在表里把名字、注释、类型改成要的</html>");
        addField.addActionListener(e -> addClicked());
        JButton delField = new JButton("删除选中字段");
        delField.setToolTipText("<html>把选中的行从这句 CREATE TABLE 里删掉（可多选，Del 同效）；"
                + "<br>主键/索引对它的引用一并清掉，INSERT 不动</html>");
        delField.addActionListener(e -> deleteClicked());
        addField.setVisible(edit != null && ops != null);
        delField.setVisible(edit != null && ops != null);
        tools.add(addField);
        tools.add(delField);
        JPanel center = new JPanel(new java.awt.BorderLayout(0, UiScale.px(4)));
        center.add(tools, java.awt.BorderLayout.NORTH);
        center.add(colBox, java.awt.BorderLayout.CENTER);
        content.add(center, java.awt.BorderLayout.CENTER);

        // Del 与「删除选中字段」同一个动作：两条入口一套规则，不各写一遍
        fieldTable.getInputMap(JTable.WHEN_FOCUSED).put(
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0),
                "delField");
        fieldTable.getActionMap().put("delField", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                deleteClicked();
            }
        });

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

    /** 按一份列清单整表重铺：增删字段之后列数变了，行与列标题都要跟着换。 */
    private void fill(List<Infer.Column> columns) {
        originals.clear();
        model.setRowCount(0);
        for (Infer.Column c : columns) {
            originals.add(new String[]{c.field, c.comment == null ? "" : c.comment,
                    c.type == null ? "" : c.type});
        }
        for (int i = 0; i < originals.size(); i++) {
            String[] one = originals.get(i);
            Infer.Column c = columns.get(i);
            model.addRow(new Object[]{display(one[0]), one[1], one[2],
                    Boolean.TRUE.equals(c.primaryKey) ? "是" : "",
                    Boolean.FALSE.equals(c.nullable) ? "否" : "是"});
        }
        if (columnsBox != null) {
            Tables.titled(columnsBox, Tables.COLUMN_TABLE_TITLE + "（共 "
                    + columns.size() + " 列）");
        }
        if (fieldTable != null) {
            fieldTable.fitColumns();
        }
    }

    /** 「新增字段」：补一列占位定义，成功后整表重铺（新行在最后一行）。 */
    private void addClicked() {
        if (ops == null) {
            return;
        }
        List<Infer.Column> now = ops.addField();
        if (now == null) {
            return;
        }
        fill(now);
        if (fieldTable.getRowCount() > 0) {
            int row = fieldTable.getRowCount() - 1;
            fieldTable.setRowSelectionInterval(row, row);
            fieldTable.scrollRectToVisible(fieldTable.getCellRect(row, 0, true));
        }
    }

    /** 「删除选中字段」/ Del：先问一次——删的是脚本正文那一列，主键/索引引用会一并清掉。 */
    private void deleteClicked() {
        if (ops == null) {
            return;
        }
        int[] rows = fieldTable.getSelectedRows();
        if (rows.length == 0) {
            MessageDialog.show(this, "删除字段",
                    "先在表里选中要删的行（可以按住 Ctrl 多选），再点这颗按钮。", null,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<String> fields = new ArrayList<String>();
        for (int row : rows) {
            if (row >= 0 && row < originals.size()) {
                fields.add(originals.get(row)[0]);
            }
        }
        if (fields.isEmpty()) {
            return;
        }
        String text = "要从这句 CREATE TABLE 里删掉这些字段：\n  " + String.join("、", fields)
                + "\n\n主键与索引对它们的引用会一并清掉；脚本里的 INSERT 不动"
                + "（执行到库时可能因列数对不上报 1136）。\n删完记得点「保存」才落盘。";
        Object[] options = {"删除", "取消"};
        int pick = JOptionPane.showOptionDialog(this, text, "删除字段",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE, null,
                options, options[1]);
        if (pick != 0) {
            return;
        }
        List<Infer.Column> now = ops.deleteFields(fields);
        if (now == null) {
            return;
        }
        fill(now);
        fieldTable.clearSelection();
    }

    /**
     * 自检用：那张字段表是不是"装不下就横向滚动"。列自适应一关，视口放不下时
     * 滚动容器自己会出横向条（JScrollPane 默认按需），不会被挤成一片省略号。
     */
    public boolean scrollsHorizontally() {
        return fieldTable.getAutoResizeMode() == javax.swing.JTable.AUTO_RESIZE_OFF;
    }

    /** 自检用：这一列摆出来的宽度够不够装下它真需要的宽度（与结构弹窗同一条判据）。 */
    public boolean columnFitsContent(int col) {
        return Tables.columnFitsContent(fieldTable, col);
    }

    /** 自检用：内容装得下时列宽总和是不是正好铺满视口（右侧不留白条）。 */
    public boolean columnsFillViewport() {
        return Tables.fillsViewport(fieldTable);
    }

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

    /** 自检用：行数（字段数），增删之后要能读回来。 */
    public int fieldCount() {
        return model.getRowCount();
    }

    /** 自检用：那两颗增删按钮在只读档下不该出现。 */
    public boolean hasColumnOps() {
        return edit != null && ops != null;
    }

    /** 自检用：直接走增删背后的那条路，不弹确认框（自检在 EDT 上，弹窗会把自己卡死）。 */
    public void pressAddForTest() {
        if (ops == null) {
            return;
        }
        List<Infer.Column> now = ops.addField();
        if (now != null) {
            fill(now);
        }
    }

    public void pressDeleteForTest(int... rows) {
        if (ops == null) {
            return;
        }
        List<String> fields = new ArrayList<String>();
        for (int row : rows) {
            if (row >= 0 && row < originals.size()) {
                fields.add(originals.get(row)[0]);
            }
        }
        List<Infer.Column> now = ops.deleteFields(fields);
        if (now != null) {
            fill(now);
        }
    }

    /** 字段名那一列按大写开关显示，其余两列原样。 */
    private String display(String value) {
        // 大小写档只有一个落点，与生成物那条路共用 SqlPage.caseName
        return SqlPage.caseName(value == null ? "" : value, upper, lower);
    }
}
