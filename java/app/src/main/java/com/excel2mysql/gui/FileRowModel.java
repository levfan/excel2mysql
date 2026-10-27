package com.excel2mysql.gui;

import com.excel2mysql.core.TableOps;
import com.excel2mysql.core.TableBuilder;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件表：一行一个已添加的文件，不是"一行一个字段"。
 * 对应 Python 侧 main.py 的 file_table。
 *
 * 「表名」与「表注释」两列可就地编辑（双击那一格进编辑器，与第二页同一交互）；
 * 字段级的改动仍走双击打开的结构弹窗。
 */
public final class FileRowModel extends AbstractTableModel {

    private static final String[] COLUMNS =
            {"序号", "文件", "表名", "表注释", "字段数", "数据行数", "主键"};

    /** 一行文件。 */
    public static final class Item {
        public final TableOps.Table table;
        public final TableBuilder.Result result;
        /** 压缩包展开出来的文件写"包名!包内路径"；散装文件留空，tooltip 退回完整路径。 */
        public String origin;

        public Item(TableOps.Table table, TableBuilder.Result result) {
            this.table = table;
            this.result = result;
        }
    }

    /** 改单张表名的校验与落库由窗口负责；模型只把编辑值转过去。 */
    public interface RenameHandler {
        void rename(Item item, String requested);
    }

    /** 表注释同样交给窗口落库：改完要刷清单、要在日志上留一句回执。 */
    public interface CommentHandler {
        void comment(Item item, String requested);
    }

    private final List<Item> items = new ArrayList<Item>();
    private RenameHandler renameHandler;
    private CommentHandler commentHandler;

    public void setRenameHandler(RenameHandler handler) {
        this.renameHandler = handler;
    }

    public void setCommentHandler(CommentHandler handler) {
        this.commentHandler = handler;
    }

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

    /** 表名与表注释两列可就地编辑：字段改动仍走双击的结构弹窗。 */
    @Override
    public boolean isCellEditable(int row, int col) {
        return (col == 2 || col == 3) && row >= 0 && row < items.size();
    }

    @Override
    public void setValueAt(Object value, int row, int col) {
        if ((col != 2 && col != 3) || row < 0 || row >= items.size()) {
            return;
        }
        Item it = items.get(row);
        String requested = value == null ? "" : String.valueOf(value);
        if (col == 2) {
            if (!requested.equals(it.table.tableName) && renameHandler != null) {
                renameHandler.rename(it, requested);
            }
        } else if (commentHandler != null) {
            // 注释允许改成空串，也允许"看着改了其实没改"（只多打了空格）：
            // 两种都由窗口给回执，模型不替它判断要不要说话
            commentHandler.comment(it, requested);
        }
        // 拒绝改名时模型不动，这里重绘一次让编辑器关掉后回显原值
        fireTableCellUpdated(row, col);
    }

    public Object getValueAt(int row, int col) {
        Item it = items.get(row);
        switch (col) {
            case 0:
                return String.valueOf(row + 1);
            case 1:
                return baseName(it.table.filepath);
            case 2:
                return it.table.tableName;
            case 3:
                return it.table.tableComment;
            case 4:
                return String.valueOf(it.table.columns.size());
            case 5:
                return String.valueOf(it.result == null ? 0 : it.result.rows.size());
            case 6:
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

    /** 第一列的悬停说明：散装文件看全路径，包内文件看是哪个包的哪一条。 */
    public String tooltipAt(int row) {
        // 渲染器拿到的行号可能已经不在模型里了：导入成功的文件会被移出列表，
        // 而 Swing 会在移除之后还按旧行数重绘一次，越界就是 EDT 上一个未捕获异常
        if (row < 0 || row >= items.size()) {
            return "";
        }
        Item it = items.get(row);
        if (it.origin != null && !it.origin.isEmpty()) {
            return it.origin;
        }
        return it.table.filepath == null ? "" : it.table.filepath;
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
