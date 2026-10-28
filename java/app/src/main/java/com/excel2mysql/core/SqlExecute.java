package com.excel2mysql.core;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL 执行器页签的函数体：把编辑区里的一段文本切成语句，逐条执行并把结果写成文本。
 *
 * 与「SQL 脚本改名」页那条路（SqlRunner）刻意保持同一套事务模型：连接是手动提交，
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

    /** 一条语句返回的结果集：图形界面拿它填表格控件。格子已按 {@link #cell} 格式化。 */
    public static final class ResultSetData {
        public int seq;
        public String sql = "";
        public java.util.List<String> columns = new java.util.ArrayList<String>();
        /** 与 columns 等长的字段注释，取不到就是空串——表头靠它当 title。 */
        public java.util.List<String> comments = new java.util.ArrayList<String>();
        public java.util.List<List<String>> rows = new java.util.ArrayList<List<String>>();
        public int total;
        public int printed;
    }

    /**
     * 结果集交给谁：只有图形界面传。传了就不再往文本里画 ASCII 表格——
     * 文本框里中英文混排时字宽不是整数倍关系必错行，而且结果一长就被自动滚到底，
     * 用户只看得见"共 N 行"那句尾巴。不传就是命令行原来那份逐字输出。
     */
    public interface Results {
        void accept(ResultSetData data);
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

    public static Report run(DbClient db, String text, Out out, Progress progress,
                             Cancel cancelled, Results results) {
        return run(db.connection(), text, out, progress, cancelled, results);
    }

    /** 真正的一层：收 Connection 而不是 DbClient，没有库也能拿假连接验到这段线上代码。 */
    public static Report run(Connection conn, String text, Out out, Progress progress,
                             Cancel cancelled) {
        return run(conn, text, out, progress, cancelled, null);
    }

    public static Report run(Connection conn, String text, Out out, Progress progress,
                             Cancel cancelled, Results results) {
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
                        printResultSet(st.getResultSet(), out, results, i + 1, oneLine(sql));
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

    private static void printResultSet(ResultSet rs, Out out, Results results,
                                       int seq, String sql) throws SQLException {
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
        if (results != null) {
            ResultSetData data = new ResultSetData();
            data.seq = seq;
            data.sql = sql;
            data.total = total;
            data.printed = printed;
            for (String c : head) {
                data.columns.add(cell(c));
            }
            // 表头 title 用的字段注释：只有界面这条通道才去反查，命令行的文本一个字节不变。
            // 连接要从结果集自己回溯，假连接/包装结果集给不出连接时就当没查到，不影响数据展示。
            for (String c : columnComments(ownerConn(rs), md, cols)) {
                data.comments.add(c);
            }
            for (List<String> r : rows) {
                java.util.List<String> cells = new java.util.ArrayList<String>(r.size());
                for (String v : r) {
                    cells.add(cell(v));
                }
                data.rows.add(cells);
            }
            results.accept(data);
            return;
        }
        for (String line : renderTable(head, rows)) {
            out.line(TABLE_INDENT + line);
        }
        out.line("      共 " + total + " 行"
                + (total > printed ? "（只打印前 " + printed + " 行，其余没显示）" : ""));
    }

    /**
     * 结果集每列的字段注释：与列等长，取不到就是空串。
     *
     * MySQL 的列定义里根本不带注释，注释只能拿"哪张表的哪个列"回 information_schema 查——
     * 与 Python 侧 column_comments 同一套口径（键的构成、大小写规则、分组方式都对齐着写）。
     * 表名与列名要取 Connector/J 的**原始名**：`FROM 表 t` 里标准 getTableName 给的是别名 t，
     * 拿去查一定查不到，getOriginalTableName 才是真表名；驱动不是 Connector/J 时退回标准名，
     * 那时带别名的列只是少一句提示，不影响数据。
     * 整段兜异常退化成"没有注释"：这一步失败不该让用户看不到查询结果，
     * 也不能往文本里多打一行——两栈的执行器文本是逐行对撞的。
     */
    static List<String> columnComments(Connection conn, ResultSetMetaData md, int cols) {
        List<String> blank = new ArrayList<String>();
        for (int i = 0; i < cols; i++) {
            blank.add("");
        }
        String[] db = new String[cols];
        String[] tb = new String[cols];
        String[] col = new String[cols];
        try {
            com.mysql.cj.result.Field[] fs = cjFields(md);
            String connDb = nz(conn.getCatalog());
            for (int i = 0; i < cols; i++) {
                if (fs != null) {
                    db[i] = nz(fs[i].getDatabaseName());
                    tb[i] = nz(fs[i].getOriginalTableName());
                    col[i] = nz(fs[i].getOriginalName());
                } else {
                    db[i] = nz(md.getCatalogName(i + 1));
                    tb[i] = nz(md.getTableName(i + 1));
                    col[i] = nz(md.getColumnName(i + 1));
                }
                if (db[i].isEmpty()) {
                    db[i] = connDb;
                }
            }
            java.util.Map<String, java.util.Set<String>> want =
                    new java.util.LinkedHashMap<String, java.util.Set<String>>();
            for (int i = 0; i < cols; i++) {
                if (!tb[i].isEmpty() && !col[i].isEmpty()) {
                    java.util.Set<String> tables = want.get(db[i]);
                    if (tables == null) {
                        tables = new java.util.TreeSet<String>();
                        want.put(db[i], tables);
                    }
                    tables.add(tb[i]);
                }
            }
            if (want.isEmpty()) {
                return blank;
            }
            java.util.Map<String, String> found = new java.util.HashMap<String, String>();
            for (java.util.Map.Entry<String, java.util.Set<String>> e : want.entrySet()) {
                List<String> names = new ArrayList<String>(e.getValue());
                StringBuilder sql = new StringBuilder(
                        "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_COMMENT FROM information_schema.COLUMNS"
                                + " WHERE TABLE_SCHEMA=? AND TABLE_NAME IN (");
                for (int i = 0; i < names.size(); i++) {
                    sql.append(i == 0 ? "?" : ",?");
                }
                sql.append(")");
                java.sql.PreparedStatement ps = conn.prepareStatement(sql.toString());
                try {
                    ps.setString(1, e.getKey());
                    for (int i = 0; i < names.size(); i++) {
                        ps.setString(i + 2, names.get(i));
                    }
                    ResultSet mrs = ps.executeQuery();
                    try {
                        while (mrs.next()) {
                            found.put(commentKey(e.getKey(), nz(mrs.getString(1)),
                                    nz(mrs.getString(2)).toLowerCase()), nz(mrs.getString(3)));
                        }
                    } finally {
                        mrs.close();
                    }
                } finally {
                    ps.close();
                }
            }
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < cols; i++) {
                String v = tb[i].isEmpty() || col[i].isEmpty() ? ""
                        : found.get(commentKey(db[i], tb[i], col[i].toLowerCase()));
                out.add(v == null ? "" : v);
            }
            return out;
        } catch (Exception ignore) {
            return blank;
        }
    }

    /** 结果集是谁查出来的：拿不到连接（假结果集、包装层不给）就当没有，不抛。 */
    private static Connection ownerConn(ResultSet rs) {
        try {
            Statement st = rs.getStatement();
            return st == null ? null : st.getConnection();
        } catch (Exception e) {
            return null;
        }
    }

    /** 键的三条按位拼：库名不分大小写，表名按服务端原样，列名由调用方转小写。 */
    private static String commentKey(String db, String table, String lowerColumn) {
        return db.toLowerCase() + "\u0000" + table + "\u0000" + lowerColumn;
    }

    /** 驱动给的是 null 而不是空串（表达式列就是这样），统一成空串再拼键。 */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static com.mysql.cj.result.Field[] cjFields(ResultSetMetaData md) {
        if (md instanceof com.mysql.cj.jdbc.result.ResultSetMetaData) {
            return ((com.mysql.cj.jdbc.result.ResultSetMetaData) md).getFields();
        }
        return null;
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
