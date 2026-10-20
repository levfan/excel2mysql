package com.excel2mysql.core;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * SQL 执行器页签的函数体：把编辑区里的一段文本切成语句，逐条执行并把结果写成文本。
 *
 * 与「SQL 脚本改名入库」页那条路（SqlRunner）刻意保持同一套事务模型：连接是手动提交，
 * 全部成功才 commit，中途出错整体 rollback，并在失败那句后面附一句 DDL 隐式提交的提醒
 * ——两个页签如果回滚语义不一样，现场按其中一个页签的经验去理解另一个就会出错。
 * 差别只在支持结果集：这里是给人当查询窗用的，SELECT 要能看到列名与前若干行。
 */
public final class SqlExecute {

    /** 查询结果最多打这么多行：再多就不是给人看的了，也让"截断"显式可见。 */
    public static final int PREVIEW_ROWS = 200;

    public interface Out {
        void line(String text);
    }

    public static final class Report {
        public int statements;
        public int executed;
        /** 三个状态位供界面与真库回归读：谁失败了、这批提了没、回滚走没走。 */
        public boolean failed;
        public String error = "";
        public boolean committed;
        public boolean rolledBack;
    }

    private SqlExecute() {
    }

    public static Report run(DbClient db, String text, Out out) {
        Report rep = new Report();
        List<String> statements = SqlScript.executableStatements(text);
        rep.statements = statements.size();
        if (statements.isEmpty()) {
            out.line("! [WARN] 没有可执行的语句：编辑区是空的（只写注释也不算）");
            return rep;
        }
        Statement st = null;
        try {
            st = db.connection().createStatement();
            for (int i = 0; i < statements.size(); i++) {
                String sql = statements.get(i);
                out.line("- [INFO] 第 " + (i + 1) + "/" + statements.size() + " 条："
                        + oneLine(sql));
                try {
                    boolean hasRs = st.execute(sql);
                    rep.executed = i + 1;
                    if (hasRs) {
                        printResultSet(st.getResultSet(), out);
                    } else {
                        int n = st.getUpdateCount();
                        out.line("+ [OK] 影响 " + (n < 0 ? 0 : n) + " 行");
                    }
                } catch (SQLException e) {
                    rep.failed = true;
                    rep.error = LogText.describe(e);
                    out.line("x [ERROR] 第 " + (i + 1) + " 条被拒绝 | " + rep.error);
                    out.line("      " + LogText.ROLLBACK_NOTE);
                    break;
                }
            }
            if (!rep.failed) {
                db.connection().commit();
                rep.committed = true;
                out.line("+ [OK] 全部完成 | " + rep.executed + " 条语句已提交");
            } else {
                try {
                    db.connection().rollback();
                    rep.rolledBack = true;
                    out.line("- [INFO] 已回滚 | 本次未提交，库里看不到刚才这批语句的效果");
                } catch (SQLException e) {
                    out.line("! [WARN] 回滚也失败了 | " + LogText.describe(e));
                }
            }
        } catch (SQLException e) {
            rep.failed = true;
            rep.error = LogText.describe(e);
            out.line("x [ERROR] 执行失败 | " + rep.error);
        } finally {
            close(st);
        }
        return rep;
    }

    private static void printResultSet(ResultSet rs, Out out) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        StringBuilder head = new StringBuilder();
        for (int i = 1; i <= cols; i++) {
            if (i > 1) {
                head.append(" | ");
            }
            head.append(md.getColumnLabel(i));
        }
        out.line("      " + head);
        int printed = 0;
        int total = 0;
        while (rs.next()) {
            total++;
            if (printed >= PREVIEW_ROWS) {
                continue;
            }
            StringBuilder row = new StringBuilder();
            for (int i = 1; i <= cols; i++) {
                if (i > 1) {
                    row.append(" | ");
                }
                String v = rs.getString(i);
                row.append(v == null ? "NULL" : v);
            }
            out.line("      " + row);
            printed++;
        }
        out.line("      共 " + total + " 行"
                + (total > printed ? "（只打印前 " + printed + " 行，其余没显示）" : ""));
    }

    private static String oneLine(String sql) {
        String s = sql == null ? "" : sql.replace((char) 10, ' ').replace((char) 13, ' ');
        s = s.replaceAll(" +", " ").trim();
        return s.length() <= 160 ? s : s.substring(0, 160) + "…（共 " + s.length() + " 字符）";
    }

    private static void close(Statement st) {
        if (st == null) {
            return;
        }
        try {
            st.close();
        } catch (SQLException ignored) {
            // 关不上不影响结论，连接由调用方 close
        }
    }
}
