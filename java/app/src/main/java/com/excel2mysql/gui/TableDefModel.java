package com.excel2mysql.gui;

import com.excel2mysql.core.Infer;
import com.excel2mysql.core.TableOps;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * 表定义表格的模型：一行 = 一个字段。
 *
 * 主键那一列给的是可勾选的 Boolean 而不是字符串，界面要能直接改；
 * 类型那一列可编辑，因为用户经常要把手填的 INT 改成 VARCHAR 再导。
 */
public final class TableDefModel extends AbstractTableModel {

    private static final String[] COLUMNS = {"表名", "字段名", "注释", "类型", "主键", "字节宽度", "来源文件"};

    /** 一行：所属表 + 该表的列定义引用。 */
    public static final class Row {
        public final TableOps.Table table;
        public final Infer.Column column;

        public Row(TableOps.Table table, Infer.Column column) {
            this.table = table;
            this.column = column;
        }
    }

    private final List<Row> rows = new ArrayList<Row>();

    public int getRowCount() {
        return rows.size();
    }

    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int col) {
        return COLUMNS[col];
    }

    @Override
    public Class<?> getColumnClass(int col) {
        return col == 4 ? Boolean.class : String.class;
    }

    @Override
    public boolean isCellEditable(int row, int col) {
        return col == 3 || col == 4;
    }

    public Object getValueAt(int row, int col) {
        Row r = rows.get(row);
        switch (col) {
            case 0:
                return r.table.tableName;
            case 1:
                return r.column.field;
            case 2:
                return r.column.comment;
            case 3:
                return r.column.type;
            case 4:
                return Boolean.valueOf(r.column.primaryKey);
            case 5:
                return String.valueOf(r.column.maxLen);
            default:
                return baseName(r.table.filepath);
        }
    }

    @Override
    public void setValueAt(Object value, int row, int col) {
        Row r = rows.get(row);
        if (col == 3) {
            r.column.type = String.valueOf(value);
        } else if (col == 4) {
            r.column.primaryKey = Boolean.TRUE.equals(value);
        }
        fireTableCellUpdated(row, col);
    }

    public void addTable(TableOps.Table table) {
        for (Infer.Column c : table.columns) {
            rows.add(new Row(table, c));
        }
        fireTableDataChanged();
    }

    /** 按表为单位删除：表格是"一行一字段"，但用户删的是"一张表"。 */
    public void removeTable(TableOps.Table table) {
        List<Row> keep = new ArrayList<Row>();
        for (Row r : rows) {
            if (r.table != table) {
                keep.add(r);
            }
        }
        rows.clear();
        rows.addAll(keep);
        fireTableDataChanged();
    }

    public void clear() {
        rows.clear();
        fireTableDataChanged();
    }

    public Row rowAt(int index) {
        return rows.get(index);
    }

    public List<Row> rows() {
        return rows;
    }

    private static String baseName(String path) {
        String s = path == null ? "" : path.replace('\\', '/');
        int at = s.lastIndexOf('/');
        return at < 0 ? s : s.substring(at + 1);
    }
}
