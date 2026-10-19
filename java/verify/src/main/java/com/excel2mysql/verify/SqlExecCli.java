package com.excel2mysql.verify;

import com.excel2mysql.core.Config;
import com.excel2mysql.core.DbClient;
import com.excel2mysql.core.LogText;
import com.excel2mysql.core.SqlExecute;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 「SQL 执行器」页签函数体（core.SqlExecute）的真库回归。
 *
 * 断的是落库副作用，不是"没抛异常"：回滚没生效时语句照样一条条写着"执行成功"，
 * 只有回查表里的行数才能看出那批 INSERT 到底提没提交。
 *
 * 三段各测一件事：
 *   1 建表（单独提交，给后面两段一张干净的探针表）
 *   2 先写两行再撞一句坏语句 → 整批回滚，表里必须还是 0 行
 *   3 写一行 + 一句带结果集的查询 → 提交，表里 1 行，结果集文本要能数出行数
 *
 * 同时把执行器发出的每一行原文打到标准输出（前缀 LINE|），Python 侧用同一段输入
 * 跑 core/sql_execute.py，两份行序列逐行对——两个页签/两条线说的话不一样，
 * 现场就会按一边的经验去理解另一边。
 *
 * 只动 e2msqlexecprobe 这一张表；跑前跑后库里的表清单必须一张不多一张不少。
 * 口令只走环境变量 E2M_PASS，不落任何文件。
 */
public final class SqlExecCli {

    private static final String PROBE = "e2msqlexecprobe";

    /** 收集执行器发出的每一行，既用于断言也用于打印。 */
    private static final class Capture implements SqlExecute.Out {
        final List<String> lines = new ArrayList<String>();

        @Override
        public void line(String text) {
            lines.add(text);
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> problems = new ArrayList<String>();
        List<String> emitted = new ArrayList<String>();
        Config cfg = Config.discover(new ArrayList<String>());
        if (!cfg.hasDatabase()) {
            System.out.println("x [ERROR] SQL-EXEC | 没指定目标库（E2M_DB），拒绝在未知库上执行");
            System.exit(2);
        }
        System.out.println("- [INFO] SQL-EXEC | 目标 " + cfg);
        DbClient db;
        try {
            db = new DbClient(cfg);
        } catch (Exception ex) {
            System.out.println("x [ERROR] SQL-EXEC | 连不上 " + cfg + "：" + LogText.describe(ex));
            System.exit(3);
            return;
        }
        Set<String> before = tables(db);
        for (String t : before) {
            if (t.startsWith("e2msqlexecprobe")) {
                System.out.println("x [ERROR] SQL-EXEC | 库里已有同名探针表，先清掉再跑：" + t);
                System.exit(4);
            }
        }
        try {
            // ---- 1 建表：单独一批，给后面两段一张干净的表 ----
            Capture c1 = new Capture();
            SqlExecute.Report r1 = SqlExecute.run(db,
                    "CREATE TABLE " + PROBE + " (id INT PRIMARY KEY, name VARCHAR(20));", c1);
            emitted.addAll(c1.lines);
            if (r1.failed || !r1.committed || r1.executed != 1) {
                problems.add("建表那批应成功提交 1 条，实际 executed=" + r1.executed
                        + " committed=" + r1.committed + " failed=" + r1.failed);
            }
            if (!tableExists(db, PROBE)) {
                problems.add("建表说成功了，库里查不到 " + PROBE);
            }

            // ---- 2 两行写入后撞坏语句：整批回滚，行数必须还是 0 ----
            Capture c2 = new Capture();
            SqlExecute.Report r2 = SqlExecute.run(db,
                    "INSERT INTO " + PROBE + " VALUES (1, '甲');" + (char) 10
                            + "INSERT INTO " + PROBE + " VALUES (2, '乙');" + (char) 10
                            + "SELECT * FROM no_such_table_e2m;", c2);
            emitted.addAll(c2.lines);
            if (!r2.failed || !r2.rolledBack || r2.committed) {
                problems.add("坏语句那批应失败并回滚，实际 failed=" + r2.failed
                        + " rolledBack=" + r2.rolledBack + " committed=" + r2.committed);
            }
            if (r2.executed != 2) {
                problems.add("坏语句是第 3 条，已执行的计数应为 2，实际 " + r2.executed);
            }
            if (!contains(c2.lines, "第 3 条被拒绝")) {
                problems.add("没定位到出错的那一条：" + tail(c2.lines));
            }
            if (!contains(c2.lines, LogText.ROLLBACK_NOTE)) {
                problems.add("失败那句后面没附 DDL 隐式提交的提醒：" + tail(c2.lines));
            }
            if (!contains(c2.lines, "已回滚")) {
                problems.add("回滚了却没说：" + tail(c2.lines));
            }
            if (count(db) != 0) {
                problems.add("回滚没生效：探针表里有 " + count(db) + " 行（应为 0）");
            }

            // ---- 3 一行写入 + 一句带结果集的查询：提交，结果集要数得出行数 ----
            Capture c3 = new Capture();
            SqlExecute.Report r3 = SqlExecute.run(db,
                    "INSERT INTO " + PROBE + " VALUES (3, '丙');" + (char) 10
                            + "SELECT id, name FROM " + PROBE + " ORDER BY id;", c3);
            emitted.addAll(c3.lines);
            if (r3.failed || !r3.committed || r3.executed != 2) {
                problems.add("好批应提交 2 条，实际 executed=" + r3.executed
                        + " committed=" + r3.committed + " failed=" + r3.failed);
            }
            if (count(db) != 1) {
                problems.add("提交后应查到 1 行，实际 " + count(db));
            }
            if (!contains(c3.lines, "共 1 行")) {
                problems.add("结果集没报出行数：" + tail(c3.lines));
            }
            if (!contains(c3.lines, "丙")) {
                problems.add("结果集没把行内容打出来：" + tail(c3.lines));
            }

            // ---- 4 只写注释的一批：什么都不执行，也不能当成成功提交 ----
            Capture c4 = new Capture();
            SqlExecute.Report r4 = SqlExecute.run(db, "-- 只是一句注释", c4);
            emitted.addAll(c4.lines);
            if (r4.statements != 0 || r4.committed || r4.executed != 0) {
                problems.add("纯注释不该有语句计数，实际 statements=" + r4.statements
                        + " executed=" + r4.executed + " committed=" + r4.committed);
            }
            if (!contains(c4.lines, "没有可执行的语句")) {
                problems.add("空输入没给出那句话：" + tail(c4.lines));
            }
        } finally {
            try (Statement st = db.connection().createStatement()) {
                st.execute("DROP TABLE IF EXISTS `" + PROBE + "`");
                db.connection().commit();
            } catch (Exception ignore) {
                problems.add("探针表没清掉：" + PROBE);
            }
            Set<String> after = tables(db);
            if (!before.equals(after)) {
                Set<String> diff = new TreeSet<String>(after);
                diff.removeAll(before);
                Set<String> gone = new TreeSet<String>(before);
                gone.removeAll(after);
                problems.add("跑完库里表清单变了，多出 " + diff + " 少了 " + gone
                        + LogText.NL + "    前后：" + before.size() + " -> " + after.size());
            }
            db.close();
        }

        for (String l : emitted) {
            System.out.println("LINE|" + l.replace((char) 13, ' ').replace((char) 10, ' '));
        }
        for (String p : problems) {
            System.out.println("x [ERROR] SQL-EXEC | " + p);
        }
        if (!problems.isEmpty()) {
            System.out.println("x [ERROR] SQL-EXEC | 真库回归未通过（" + problems.size() + " 处）");
            System.exit(1);
        }
        System.out.println("+ [OK] SQL-EXEC | 建表提交、坏批回滚后表里 0 行、好批提交后 1 行、"
                + "纯注释零执行；执行器发出 " + emitted.size() + " 行文本");
    }

    private static boolean contains(List<String> lines, String needle) {
        for (String l : lines) {
            if (l.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String tail(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l.replace((char) 10, ' ')).append(" | ");
        }
        String s = sb.toString();
        return s.length() <= 300 ? s : s.substring(s.length() - 300);
    }

    private static Set<String> tables(DbClient db) throws Exception {
        Set<String> out = new LinkedHashSet<String>();
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery("SHOW TABLES")) {
            while (rs.next()) {
                out.add(rs.getString(1).toLowerCase());
            }
        }
        return out;
    }

    private static boolean tableExists(DbClient db, String name) throws Exception {
        return tables(db).contains(name.toLowerCase());
    }

    private static int count(DbClient db) throws Exception {
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + PROBE + "`")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private SqlExecCli() {
    }
}
