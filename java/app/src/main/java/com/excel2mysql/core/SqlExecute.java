package com.excel2mysql.core;

import java.sql.Connection;
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
    /** 单个格子最多占这么多显示列（与 Python 侧 CELL_MAX 同一个数）。 */
    public static final int CELL_MAX = 40;
    /** 表格整体缩在语句行下面，与 Python 侧 TABLE_INDENT 一致。 */
    static final String TABLE_INDENT = "      ";

    public interface Out {
        void line(String text);
    }

    /** 逐条回报进度：图形界面的进度条与任务条靠它，命令行不传就是没有。 */
    public interface Progress {
        void on(int done, int total);
    }

    /** 每句之前查一次：不查的话点了「停止」也要等整批跑完才生效。 */
    public interface Cancel {
        boolean requested();
    }

    public static final class Report {
        public int statements;
        public int executed;
        /** 三个状态位供界面与真库回归读：谁失败了、这批提了没、回滚走没走。 */
        public boolean failed;
        public String error = "";
        public boolean committed;
        public boolean rolledBack;
        public boolean cancelled;
    }

    private SqlExecute() {
    }

    public static Report run(DbClient db, String text, Out out) {
        return run(db.connection(), text, out, null, null);
    }

    public static Report run(DbClient db, String text, Out out, Progress progress,
                             Cancel cancelled) {
        return run(db.connection(), text, out, progress, cancelled);
    }

    /** 真正的一层：收 Connection 而不是 DbClient，没有库也能拿假连接验到这段线上代码。 */
    public static Report run(Connection conn, String text, Out out, Progress progress,
                             Cancel cancelled) {
        Report rep = new Report();
        List<String> statements = SqlScript.executableStatements(text);
        rep.statements = statements.size();
        if (statements.isEmpty()) {
            out.line("! [WARN] 没有可执行的语句：编辑区是空的（只写注释也不算）");
            return rep;
        }
        int total = statements.size();
        if (progress != null) {
            progress.on(0, total);
        }
        Statement st = null;
        try {
            st = conn.createStatement();
            for (int i = 0; i < total; i++) {
                String sql = statements.get(i);
                if (cancelled != null && cancelled.requested()) {
                    rep.cancelled = true;
                    out.line("! [WARN] 已取消 | 第 " + (i + 1) + "/" + total
                            + " 条未执行，本批未提交（已回滚）");
                    try {
                        conn.rollback();
                        rep.rolledBack = true;
                        out.line("- [INFO] 已回滚 | 前面 " + rep.executed + " 条的效果看不到");
                    } catch (SQLException e) {
                        out.line("! [WARN] 回滚也失败了 | " + LogText.describe(e));
                    }
                    if (progress != null) {
                        progress.on(total, total);
                    }
                    return rep;
                }
                out.line("- [INFO] 第 " + (i + 1) + "/" + total + " 条："
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
                if (progress != null) {
                    progress.on(rep.executed, total);
                }
            }
            if (!rep.failed) {
                conn.commit();
                rep.committed = true;
                out.line("+ [OK] 全部完成 | " + rep.executed + " 条语句已提交");
            } else {
                try {
                    conn.rollback();
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
            if (progress != null) {
                progress.on(total, total);
            }
        }
        return rep;
    }

    private static void printResultSet(ResultSet rs, Out out) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        java.util.List<String> head = new java.util.ArrayList<String>();
        for (int i = 1; i <= cols; i++) {
            head.add(md.getColumnLabel(i));
        }
        java.util.List<List<String>> rows = new java.util.ArrayList<List<String>>();
        int printed = 0;
        int total = 0;
        while (rs.next()) {
            total++;
            if (printed >= PREVIEW_ROWS) {
                continue;
            }
            java.util.List<String> r = new java.util.ArrayList<String>(cols);
            for (int i = 1; i <= cols; i++) {
                r.add(rs.getString(i));
            }
            rows.add(r);
            printed++;
        }
        for (String line : renderTable(head, rows)) {
            out.line(TABLE_INDENT + line);
        }
        out.line("      共 " + total + " 行"
                + (total > printed ? "（只打印前 " + printed + " 行，其余没显示）" : ""));
    }

    /**
     * 一个取值 -> 格子里那段文字：NULL 显式写出来（空着分不清是 NULL 还是空串），
     * 换行与制表符换成可见转义，否则一条带换行的备注会把整张表顶散。
     */
    static String cell(Object v) {
        if (v == null) {
            return "NULL";
        }
        String s = String.valueOf(v);
        s = s.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
        return TextWidth.clip(s.replace("\t", "\\t"), CELL_MAX);
    }

    /**
     * 列名 + 若干行 -> 等宽表格的各行文本（不带缩进，由调用方补）。
     *
     * 结果区是个纯文本框，能靠的只有对齐：按**显示宽度**（汉字占两列）把每列补到同宽，
     * 上下与表头下面各画一条分隔线。原来那种 {@code " | "} 直接拼一行，列宽一不一样就错行，
     * 混进中文之后连列名都对不上是哪一列。与 Python 侧 render_table 逐字符一致，
     * 由金标准差分的 table-render 分支对撞。
     */
    public static java.util.List<String> renderTable(java.util.List<String> columns,
                                                     java.util.List<java.util.List<String>> rows) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (columns == null || columns.isEmpty()) {
            return out;
        }
        final int n = columns.size();
        java.util.List<String> head = new java.util.ArrayList<String>(n);
        for (String c : columns) {
            head.add(cell(c));
        }
        java.util.List<java.util.List<String>> body =
                new java.util.ArrayList<java.util.List<String>>();
        if (rows != null) {
            for (java.util.List<String> r : rows) {
                java.util.List<String> cells = new java.util.ArrayList<String>(n);
                int have = r == null ? 0 : r.size();
                for (int i = 0; i < n; i++) {
                    // 行比列少时补空格，不写 NULL：真实的游标不会少给值，
                    // 少给是夹具形状，写成 NULL 会把"没有这一列的值"说成"值是 NULL"
                    cells.add(i < have ? cell(r.get(i)) : "");
                }
                body.add(cells);
            }
        }
        int[] w = new int[n];
        for (int i = 0; i < n; i++) {
            w[i] = TextWidth.width(head.get(i));
        }
        for (java.util.List<String> r : body) {
            for (int i = 0; i < n; i++) {
                w[i] = Math.max(w[i], TextWidth.width(r.get(i)));
            }
        }
        StringBuilder ruleSb = new StringBuilder("+");
        for (int i = 0; i < n; i++) {
            int span = (w[i] > 0 ? w[i] : 1) + 2;
            for (int k = 0; k < span; k++) {
                ruleSb.append('-');
            }
            ruleSb.append('+');
        }
        final String rule = ruleSb.toString();
        out.add(rule);
        out.add(rowLine(head, w));
        out.add(rule);
        for (java.util.List<String> r : body) {
            out.add(rowLine(r, w));
        }
        if (!body.isEmpty()) {
            out.add(rule);   // 零行的时候表头下面那条线就是收口，不重复画
        }
        return out;
    }

    private static String rowLine(java.util.List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < widths.length; i++) {
            int target = widths[i] > 0 ? widths[i] : 1;
            sb.append(' ').append(TextWidth.pad(cells.get(i), target)).append(" |");
        }
        return sb.toString();
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
