package com.excel2mysql.gui;

import com.excel2mysql.core.TableOps;
import com.excel2mysql.core.TableBuilder;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件表：一行一个已添加的文件，不是"一行一个字段"。
 * 对应 Python 侧 main.py:898-909 的 file_table。
 *
 * 就地编辑是关掉的（和 Python 一致）——字段级的改动在双击打开的结构弹窗里做。
 */
public final class FileRowModel extends AbstractTableModel {

    private static final String[] COLUMNS =
            {"文件", "表名", "表注释", "字段数", "数据行数", "主键"};

    /** 一行文件。 */
    public static final class Item {
        public final TableOps.Table table;
        public final TableBuilder.Result result;

        public Item(TableOps.Table table, TableBuilder.Result result) {
            this.table = table;
            this.result = result;
        }
    }

    private final List<Item> items = new ArrayList<Item>();

    public int getRowCount() {
        return items.size();
    }

    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int col) {
        return COLUMNS[col];
    }

    /** 和 Python 侧一致：这张表不允许就地编辑，编辑走结构弹窗。 */
    @Override
    public boolean isCellEditable(int row, int col) {
        return false;
    }

    public Object getValueAt(int row, int col) {
        Item it = items.get(row);
        switch (col) {
            case 0:
                return baseName(it.table.filepath);
            case 1:
                return it.table.tableName;
            case 2:
                return it.table.tableComment;
            case 3:
                return String.valueOf(it.table.columns.size());
            case 4:
                return String.valueOf(it.result == null ? 0 : it.result.rows.size());
            case 5:
                return primaryKeyOf(it.table);
            default:
                return "";
            }
        }

    public void add(Item item) {
        items.add(item);
        fireTableDataChanged();
    }

    public void removeSelected(int[] selected) {
        List<Item> keep = new ArrayList<Item>();
        java.util.Set<Integer> drop = new java.util.HashSet<Integer>();
        for (int i : selected) {
            drop.add(Integer.valueOf(i));
        }
        for (int i = 0; i < items.size(); i++) {
            if (!drop.contains(Integer.valueOf(i))) {
                keep.add(items.get(i));
            }
        }
        items.clear();
        items.addAll(keep);
        fireTableDataChanged();
    }

    /** 主键列：勾了一列或多列就都列出来，一列都没勾写"无"，与 Python 侧同一口径。 */
    private static String primaryKeyOf(TableOps.Table t) {
        List<String> pk = new ArrayList<String>();
        for (com.excel2mysql.core.Infer.Column c : t.columns) {
            if (c.primaryKey) {
                pk.add(c.field);
            }
        }
        return pk.isEmpty() ? "无" : String.join(", ", pk);
    }

    public int indexOf(TableOps.Table t) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).table == t) {
                return i;
            }
        }
        return -1;
    }

    public void removeItems(java.util.Collection<TableOps.Table> ts) {
        List<Item> keep = new ArrayList<Item>();
        for (Item it : items) {
            if (!ts.contains(it.table)) {
                keep.add(it);
            }
        }
        items.clear();
        items.addAll(keep);
        fireTableDataChanged();
    }

    public Item itemAt(int row) {
        return items.get(row);
    }

    public List<Item> items() {
        return items;
    }

    /** 所有表的字段总数：状态栏要说"几张表 / 几个字段"。 */
    public int rowTotal() {
        int n = 0;
        for (Item it : items) {
            n += it.table.columns.size();
        }
        return n;
    }

    public void clear() {
        items.clear();
        fireTableDataChanged();
    }

    /** 表名会被前缀操作改掉，改完要整表刷新一次才能看到新名字。 */
    public void refresh() {
        fireTableDataChanged();
    }

    private static String baseName(String path) {
        String s = path == null ? "" : path.replace('\\', '/');
        int at = s.lastIndexOf('/');
        return at < 0 ? s : s.substring(at + 1);
    }
}
