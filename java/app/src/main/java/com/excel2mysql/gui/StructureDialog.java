package com.excel2mysql.gui;

import com.excel2mysql.core.Infer;
import com.excel2mysql.core.TableBuilder;
import com.excel2mysql.core.TableOps;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 结构弹窗：一张表一个标签页，改字段名/注释/类型/主键，也能增删字段。
 * 对应 Python 侧 main.py 的 StructureDialog（同样是全表标签页，不是单表）。
 *
 * 第 4 列"表内数据最大字节长度"是这一列数据在表里的实测字节数，不是类型声明的容量，
 * 改了不会改类型，只会造出"看着改对了、建表仍按原类型"的错觉，所以两栈都把这一列设成不可编辑。
 * 列名写"表内数据"并挨着「数据类型」放，是防止只写"最大字节长度"被读成 VARCHAR(n) 那一档。
 *
 * 写回时 columns、数据行与表头必须同位置一起改：导入按列序号取数（DbClient.insertData
 * 拿 row.get(j)），错一格整份数据就串列。新增/删除字段就是会改到列数的操作，
 * 所以这里记"每一行对应的原列序号"（新增行记 -1），落盘时按这份对位重建数据行与表头。
 */
public final class StructureDialog extends JDialog {

    private static final String[] COLUMNS = {"主键", "字段名(拼音/首字母)", "字段注释(中文表头)",
            "数据类型", "表内数据最大字节长度"};
    private static final String TIP =
            "勾选一列或多列作为主键（多列即组合主键，主键约束至多一个）；"
            + "主键需全列非空且组合唯一，全部不勾选则不设主键。\n"
            + "「新增字段」「删除选中字段」改的是这张表的字段清单（表里选中行再按 Del 也是删）。";
    /** 列宽设计下限；富余宽度只摊给三列文字（1/2/3），主键框与字节数那两列不动。 */
    private static final int[] STRUCTURE_COL_FLOORS = {UiScale.px(46), UiScale.px(230),
            UiScale.px(280), UiScale.px(160), UiScale.px(96)};
    private static final int[] STRUCTURE_COL_STRETCH = {1, 2, 3};

    /** 一个标签页背后的一份编辑状态。 */
    private static final class Editor {
        final TableBuilder.Result result;
        final TableOps.Table table;
        final List<List<Object>> rows;
        final List<Object> headers;
        /** 每一行对应的原列序号；新增的行为 -1。 */
        final List<Integer> origin = new ArrayList<Integer>();
        final DefaultTableModel model;
        /** 这张表的滚动容器；自检要顺着它往上读"有没有再套一层标识"。 */
        JScrollPane scroll;
        JTable view;

        Editor(TableBuilder.Result result) {
            this.result = result;
            this.table = result.table;
            this.rows = result.rows;
            this.headers = result.headers;
            this.model = new DefaultTableModel(COLUMNS, 0) {
                @Override
                public Class<?> getColumnClass(int col) {
                    return col == 0 ? Boolean.class : String.class;
                }

                @Override
                public boolean isCellEditable(int row, int col) {
                    // 实测字节数那一列只给看不给改：改了不回写，留着可编辑就是静默 no-op
                    return col != 4;
                }
            };
            for (int i = 0; i < table.columns.size(); i++) {
                Infer.Column c = table.columns.get(i);
                this.model.addRow(new Object[]{Boolean.valueOf(c.primaryKey), c.field, c.comment,
                        c.type, String.valueOf(c.maxLen)});
                this.origin.add(Integer.valueOf(i));
            }
        }

        /** 新增一行：占位名 is_new_field，注释/类型给可改的初值，进表后直接编辑。 */
        void addRow() {
            model.addRow(new Object[]{Boolean.FALSE, freeField(), "新字段", "VARCHAR(255)", ""});
            origin.add(Integer.valueOf(-1));
        }

        /** 占位名不跟已有字段撞：is_new_field、is_new_field_2、…… */
        private String freeField() {
            Set<String> used = new HashSet<String>();
            for (int i = 0; i < model.getRowCount(); i++) {
                Object v = model.getValueAt(i, 1);
                if (v != null) {
                    used.add(String.valueOf(v).trim().toLowerCase());
                }
            }
            String base = "is_new_field";
            if (!used.contains(base)) {
                return base;
            }
            for (int n = 2; ; n++) {
                String cand = base + "_" + n;
                if (!used.contains(cand)) {
                    return cand;
                }
            }
        }

        void removeRow(int row) {
            model.removeRow(row);
            origin.remove(row);
        }

        /** 只收集到副本，不碰 table.columns——校验没过就不能让改动落到表定义上。 */
        List<Infer.Column> stage() {
            List<Infer.Column> staged = new ArrayList<Infer.Column>();
            for (int i = 0; i < model.getRowCount(); i++) {
                int src = origin.get(i).intValue();
                Infer.Column c = src >= 0 ? table.columns.get(src).copied() : newColumn();
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

        private static Infer.Column newColumn() {
            Infer.Column c = new Infer.Column();
            c.field = "";
            c.comment = "";
            c.type = "VARCHAR(255)";
            c.maxLen = 0;
            c.primaryKey = false;
            return c;
        }
    }

    private final List<Editor> editors = new ArrayList<Editor>();
    private final LogPanel logPanel;
    private boolean confirmed;

    public StructureDialog(Frame ownerFrame, List<TableBuilder.Result> results, LogPanel logPanel) {
        super(ownerFrame, "表结构预览（可编辑）", true);
        WindowIcons.apply(this);
        this.logPanel = logPanel;

        JTabbedPane tabs = new JTabbedPane();
        // 表多时 Qt 是单行加左右箭头，不是换行堆两排标签
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        TabScrollers.apply(tabs);
        for (TableBuilder.Result r : results) {
            final Editor ed = new Editor(r);
            editors.add(ed);
            // 主键列窄、其余列"设计下限与内容需要取大者"：关掉自适应才有"放不下"这件事，
            // 而列宽随内容长，超长字段名才不会是被切掉的那一个（切掉的截滚不到）。
            // 视口比底宽宽时把富余摊给三列文字铺满弹窗（FillTable），末列不靠自适应补白
            // （Qt 侧对应 FillWidthTable + fill_widths，两栈同一算法）
            FillTable table = new FillTable(ed.model, STRUCTURE_COL_FLOORS,
                    STRUCTURE_COL_STRETCH);
            table.setFillsViewportHeight(true);
            Tables.style(table);
            table.fitColumns();   // 字体定下来之后重算一遍底宽（构造那一刻量的是默认字体）
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            JScrollPane scroll = new JScrollPane(table);
            ed.scroll = scroll;
            ed.view = table;
            scroll.setPreferredSize(new Dimension(UiScale.px(900), UiScale.px(420)));

            // 这张表不另加标题：tab 上写的就是表名，已经贴在表格正上方。
            // 增删字段这两颗放在表格上沿，与那一列"字段清单"同一组，不跟弹窗底部的保存挤一起。
            JButton addField = new JButton("新增字段");
            addField.setToolTipText("<html>在这张表的字段末尾补一列：先给一个 is_new_field 占位名，"
                    + "<br>再在表里把字段名、注释、数据类型改成要的"
                    + "<br>（新增的列没有数据值，导入时这一列按空值写）</html>");
            JButton delField = new JButton("删除选中字段");
            delField.setToolTipText("<html>把选中的行从字段清单里删掉（可多选，Del 键同一件事）；"
                    + "<br>删掉的列以后不再建，主键勾在别的列上照旧生效</html>");
            addField.addActionListener(e -> addField(ed));
            delField.addActionListener(e -> deleteSelectedFields(ed));
            Buttons.equalHeights(addField, delField);
            JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.px(6), UiScale.px(2)));
            tools.add(addField);
            tools.add(delField);

            // Del 与「删除选中字段」同一个动作：两条入口一套规则，不各写一遍
            table.getInputMap(JTable.WHEN_FOCUSED).put(
                    KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delField");
            table.getActionMap().put("delField", new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    deleteSelectedFields(ed);
                }
            });

            JPanel page = new JPanel(new BorderLayout(0, UiScale.px(4)));
            page.add(tools, BorderLayout.NORTH);
            page.add(scroll, BorderLayout.CENTER);
            tabs.addTab(r.table.tableName, page);
        }

        // 按钮写成中文的「保存」「取消」：以前是 OK / Cancel，中文界面里一颗写着 OK 的按钮，
        // 让人拿不准点了到底存不存（与 Python 侧同口径）
        JButton ok = new JButton("保存");
        ok.addActionListener(e -> confirmClicked());
        JButton cancel = new JButton("取消");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiScale.px(6), UiScale.px(4)));
        buttons.add(ok);
        buttons.add(cancel);

        JLabel tip = new JLabel("<html>" + TIP.replace("\n", "<br>") + "</html>");
        tip.setForeground(new java.awt.Color(0x66, 0x66, 0x66));

        JPanel body = new JPanel(new BorderLayout(UiScale.px(8), UiScale.px(8)));
        body.setBorder(BorderFactory.createEmptyBorder(
                UiScale.px(10), UiScale.px(10), UiScale.px(10), UiScale.px(10)));
        body.add(tip, BorderLayout.NORTH);
        body.add(tabs, BorderLayout.CENTER);
        body.add(buttons, BorderLayout.SOUTH);
        setContentPane(body);
        // Qt 的 QDialog 按 Esc 就是 reject；Swing 的 JDialog 默认什么都不做，
        // 得自己绑一条焦点期按键，否则"关了但没取消"和"取消"在用户眼里是一回事，代码里不是。
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        getRootPane().getActionMap().put("close", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        Windows.clientSize(getContentPane(), this, 980, 640);
    }

    /** Esc 是否绑到了"关掉且不写回"：自检不开框也能查（开框会把自检自己卡死）。 */
    public boolean closesOnEscape() {
        return getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)) != null;
    }

    /** 同一排按钮的高度统一取最大那一颗：参差 1~2 像素时文字会被切掉半行。 */
    // ---- 增删字段 ----

    /** 「新增字段」：末尾补一行，选中并进入编辑态（占位名就在那一格里改）。 */
    void addField(Editor ed) {
        ed.addRow();
        JTable t = ed.view;
        int row = t.getRowCount() - 1;
        t.clearSelection();
        t.setRowSelectionInterval(row, row);
        t.scrollRectToVisible(t.getCellRect(row, 1, true));
        t.editCellAt(row, 1);
        t.changeSelection(row, 1, false, false);
        t.requestFocusInWindow();
    }

    /** 「删除选中字段」/ Del：整表删光会被拦下——删完就没有列定义可建表了。 */
    void deleteSelectedFields(Editor ed) {
        TreeSet<Integer> picked = new TreeSet<Integer>();
        for (int row : ed.view.getSelectedRows()) {
            picked.add(Integer.valueOf(row));
        }
        if (picked.isEmpty()) {
            MessageDialog.show(this, "删除字段",
                    "先在表里选中要删的行（可以按住 Ctrl 多选），再点这颗按钮。", null,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (picked.size() >= ed.model.getRowCount()) {
            MessageDialog.show(this, "删除字段",
                    "这张表只剩这些字段了，全删掉就没有列定义可建表。\n"
                            + "要整张表不要，请在清单里把这一行删掉（Del）。", null,
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<Integer> rows = new ArrayList<Integer>(picked);
        for (int i = rows.size() - 1; i >= 0; i--) {
            ed.removeRow(rows.get(i).intValue());
        }
        ed.view.clearSelection();
    }

    /**
     * 把编辑回填成与列同位置对齐的数据行：新增的列没有数据，补 null 占位。
     * 导入按列序号取数（DbClient.insertData 的 j），这里错一格整份数据就串列。
     */
    private static List<List<Object>> alignedRows(List<List<Object>> old, List<Integer> origin) {
        List<List<Object>> out = new ArrayList<List<Object>>();
        for (List<Object> row : old) {
            List<Object> one = new ArrayList<Object>(origin.size());
            for (Integer src : origin) {
                int at = src.intValue();
                one.add(at >= 0 && at < row.size() ? row.get(at) : null);
            }
            out.add(one);
        }
        return out;
    }

    /** 表头同位置对齐：新增列的表头就用它的字段名（生成件按表头写列名）。 */
    private static List<Object> alignedHeaders(List<Object> old, List<Integer> origin,
                                               List<Infer.Column> cols) {
        List<Object> out = new ArrayList<Object>();
        for (int i = 0; i < origin.size(); i++) {
            int at = origin.get(i).intValue();
            out.add(at >= 0 && at < old.size() ? old.get(at) : cols.get(i).field);
        }
        return out;
    }

    private final List<String> problems = new ArrayList<String>();

    /**
     * 先把每张表的编辑收到副本并全部校验，通过后才统一写回：
     * 一张表的主键无效不能让前面已写回的表停在改了一半的状态。
     *
     * 整段包在 try/catch 里：以前异常一抛，弹窗不关也不说话，用户只看到
     * "改了、点保存、没反应"，再打开还是老样子（用户 2026-11-09 点名）。
     */
    private boolean applyEdits() {
        problems.clear();
        // 编辑器还开着时模型里是旧值：先把那一格提交，否则"敲完直接点保存"的改动会静默丢掉
        for (Editor ed : editors) {
            if (ed.view.getCellEditor() != null) {
                ed.view.getCellEditor().stopCellEditing();
            }
        }
        List<List<Infer.Column>> staged = new ArrayList<List<Infer.Column>>();
        List<List<List<Object>>> stagedRows = new ArrayList<List<List<Object>>>();
        List<List<Object>> stagedHeaders = new ArrayList<List<Object>>();
        for (Editor ed : editors) {
            List<Infer.Column> cols = ed.stage();
            staged.add(cols);
            List<List<Object>> rows = alignedRows(ed.rows, ed.origin);
            stagedRows.add(rows);
            stagedHeaders.add(alignedHeaders(ed.headers, ed.origin, cols));
            for (int i = 0; i < cols.size(); i++) {
                if (cols.get(i).primaryKey && ed.origin.get(i).intValue() < 0) {
                    problems.add("表「" + ed.table.tableName + "」：新增字段「"
                            + (cols.get(i).field.isEmpty() ? "（空）" : cols.get(i).field)
                            + "」还没有数据，不能作主键");
                }
            }
            String bad = TableBuilder.validatePrimaryKey(rows, cols);
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
            Editor ed = editors.get(i);
            TableOps.Table t = ed.table;
            t.columns.clear();
            t.columns.addAll(staged.get(i));
            // 数据行与表头跟着列一起换：只换 columns 会让导入按新列数去读旧行
            ed.rows.clear();
            ed.rows.addAll(stagedRows.get(i));
            ed.headers.clear();
            ed.headers.addAll(stagedHeaders.get(i));
        }
        confirmed = true;
        dispose();
        return true;
    }

    /** 保存按钮的真实路径：校验不过要弹窗拦住，并让弹窗保持在编辑态。 */
    private void confirmClicked() {
        boolean ok;
        try {
            ok = applyEdits();
        } catch (RuntimeException e) {
            MessageDialog.show(this, "保存失败",
                    "保存时出错，改动没有写回：\n" + e.getClass().getSimpleName()
                            + "：" + e.getMessage(), null, JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (ok) {
            return;
        }
        String text = String.join("\n", problems) + "\n\n请调整主键勾选或字段类型后重试。";
        MessageDialog.show(this, "主键设置无效", text, null, JOptionPane.WARNING_MESSAGE);
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    /** 供自检直接驱动"保存"背后的逻辑，不必真把模态框摆出来。 */
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

    /** 自检用：这张字段表是不是"焦点一离开就提交"（与 Qt 同一档）。 */
    public boolean commitsOnFocusLost(int tabIndex) {
        return Tables.terminatesEditOnFocusLost(editors.get(tabIndex).view);
    }

    /**
     * 自检用：像用户那样开一格编辑器把字敲进去，但不提交——编辑器还开着。
     *
     * 自检在 EDT 上抢不到焦点，所以这条只走"挂着未提交的编辑器直接点保存"那一路。
     */
    public void typeIntoCell(int tabIndex, int row, int col, String text) {
        JTable t = editors.get(tabIndex).view;
        if (!t.editCellAt(row, col)) {
            throw new IllegalStateException("第 " + col + " 列开不了编辑器");
        }
        java.awt.Component c = t.getEditorComponent();
        if (c instanceof javax.swing.JTextField) {
            ((javax.swing.JTextField) c).setText(text);
        }
    }

    public int rowCount(int tabIndex) {
        return editors.get(tabIndex).model.getRowCount();
    }

    /** 自检用：点「新增字段」这颗按钮（与用户点同一颗，不走旁路）。 */
    public void pressAddField(int tabIndex) {
        addField(editors.get(tabIndex));
    }

    /** 自检用：选中若干行后点「删除选中字段」。 */
    public void pressDeleteFields(int tabIndex, int... rows) {
        Editor ed = editors.get(tabIndex);
        ed.view.clearSelection();
        for (int row : rows) {
            ed.view.addRowSelectionInterval(row, row);
        }
        deleteSelectedFields(ed);
    }

    /** 自检用：数据行宽度必须与字段数一致——错一格，导入就整表串列。 */
    public int dataRowWidth(int tabIndex) {
        Editor ed = editors.get(tabIndex);
        return ed.rows.isEmpty() ? 0 : ed.rows.get(0).size();
    }

    /** 自检用：某个数据格里现在是什么（判"删中间一列后值还在原位"）。 */
    public String dataCell(int tabIndex, int row, int col) {
        Editor ed = editors.get(tabIndex);
        if (row < 0 || row >= ed.rows.size()) {
            return "";
        }
        List<Object> one = ed.rows.get(row);
        if (col < 0 || col >= one.size()) {
            return "";
        }
        return String.valueOf(one.get(col));
    }

    public int headerWidth(int tabIndex) {
        return editors.get(tabIndex).headers.size();
    }

    /** 自检用：弹窗里到底还有没有表注释输入框（入口已挪回清单，留着就是回归）。 */
    public boolean hasCommentField() {
        return !collect(getContentPane(), JTextField.class).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> collect(java.awt.Container root, Class<T> kind) {
        List<T> out = new ArrayList<T>();
        for (java.awt.Component c : root.getComponents()) {
            if (kind.isInstance(c)) {
                out.add((T) c);
            }
            if (c instanceof java.awt.Container) {
                out.addAll(collect((java.awt.Container) c, kind));
            }
        }
        return out;
    }

    /** 自检用：那张字段表是不是"装不下就横向滚动"（与第二页元数据弹窗同一档）。 */
    public boolean scrollsHorizontally(int tabIndex) {
        java.awt.Component view = editors.get(tabIndex).scroll.getViewport().getView();
        return view instanceof JTable
                && ((JTable) view).getAutoResizeMode() == JTable.AUTO_RESIZE_OFF;
    }

    /** 自检用：这一列摆出来的宽度够不够装下它真需要的宽度（超长字段名不许被切进省略号）。 */
    public boolean columnFitsContent(int tabIndex, int col) {
        java.awt.Component view = editors.get(tabIndex).scroll.getViewport().getView();
        return view instanceof JTable && Tables.columnFitsContent((JTable) view, col);
    }

    /** 自检用：内容装得下时列宽总和是不是正好铺满视口（右侧不留白条）。 */
    public boolean columnsFillViewport(int tabIndex) {
        java.awt.Component view = editors.get(tabIndex).scroll.getViewport().getView();
        return view instanceof JTable && Tables.fillsViewport((JTable) view);
    }

    /** 自检用：某个 tab 里那张表的容器，用来读它外面有没有再套一层标识。 */
    public javax.swing.JComponent tabTable(int tabIndex) {
        return editors.get(tabIndex).scroll;
    }

    /** 走同一条 applyEdits，只是不弹模态框——自检在 EDT 上，弹窗会把自己卡死。 */
    public boolean pressOk() {
        return applyEdits();
    }

    public List<String> problems() {
        return problems;
    }
}
