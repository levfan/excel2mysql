package com.excel2mysql.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * core/infer.py 里对"表定义列表"做整体改名的三个操作。
 * 表名是使用者拿去建库的可见契约，改名必须与 Python 线逐条一致，
 * 尤其是撞名去重和"基于 base_table_name 重算所以重复应用不叠加"这两条。
 */
public final class TableOps {

    /** 一张待导入的表。 */
    public static final class Table {
        public String tableName;
        public String baseTableName;
        public String tableComment;
        public List<Infer.Column> columns = new ArrayList<Infer.Column>();
        public String source = "normal";
        public String filepath;

        public Table copy() {
            Table t = new Table();
            t.tableName = tableName;
            t.baseTableName = baseTableName;
            t.tableComment = tableComment;
            t.columns = columns;
            t.source = source;
            t.filepath = filepath;
            return t;
        }
    }

    public static final class RenameResult {
        public final List<Map<String, String>> renamed = new ArrayList<Map<String, String>>();
        public final List<String> skipped = new ArrayList<String>();
    }

    private TableOps() {
    }

    /**
     * 重复表名加 _2 _3。已考虑历史后缀：列表里已有 users 与 users_2 时，
     * 再来的重复 users 命名为 users_3 而不是又生成 users_2，所以重复调用是幂等的。
     */
    public static void makeUniqueNames(List<Table> tables) {
        Set<String> used = new LinkedHashSet<String>();
        Map<String, Integer> counter = new LinkedHashMap<String, Integer>();
        for (Table t : tables) {
            String name = t.tableName;
            if (!used.contains(name)) {
                used.add(name);
                counter.put(name, Integer.valueOf(1));
                continue;
            }
            int next = counter.get(name).intValue() + 1;
            String candidate = name + "_" + next;
            while (used.contains(candidate)) {
                next++;
                candidate = name + "_" + next;
            }
            counter.put(name, Integer.valueOf(next));
            t.tableName = candidate;
            used.add(candidate);
        }
    }

    public static void applyPrefix(List<Table> tables, String prefix) {
        String p = (prefix == null ? "" : prefix).replace("`", "");
        p = Names.pyStrip(p);
        for (Table t : tables) {
            String base = t.baseTableName == null || t.baseTableName.isEmpty()
                    ? t.tableName : t.baseTableName;
            t.baseTableName = base;
            t.tableName = p.isEmpty() ? base : p + base;
        }
        makeUniqueNames(tables);
    }

    public static RenameResult replaceFirstSegment(List<Table> tables, String newHead) {
        RenameResult out = new RenameResult();
        String head = Prefix.clean(newHead);
        if (head.isEmpty()) {
            for (Table t : tables) {
                out.skipped.add(t.tableName);
            }
            return out;
        }
        List<Table> staged = new ArrayList<Table>();
        List<String> stagedOld = new ArrayList<String>();
        for (Table t : tables) {
            String old = t.tableName;
            String rep = Prefix.swapFirstSegment(old, head);
            if (rep == null) {
                out.skipped.add(old);
                continue;
            }
            t.tableName = rep;
            t.baseTableName = rep;
            staged.add(t);
            stagedOld.add(old);
        }
        makeUniqueNames(tables);
        // 改名清单要在去重之后取最终名字，否则撞名场景下报的是中间值而不是实际建表名
        for (int i = 0; i < staged.size(); i++) {
            Map<String, String> item = new LinkedHashMap<String, String>();
            item.put("old", stagedOld.get(i));
            item.put("new", staged.get(i).tableName);
            out.renamed.add(item);
        }
        return out;
    }

    /** 只取表名序列，差分与日志都按这个口径对。 */
    public static List<String> namesOf(List<Table> tables) {
        List<String> out = new ArrayList<String>();
        for (Table t : tables) {
            out.add(t.tableName);
        }
        return out;
    }
}
