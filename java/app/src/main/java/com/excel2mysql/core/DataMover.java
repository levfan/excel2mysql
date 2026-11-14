package com.excel2mysql.core;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 第五页的数据面：把源表数据搬成目标端的 INSERT——「创建表」勾不勾都走这里，
 * 区别只在调用方要不要先写建表语句。两个方向各有一套读写：
 *
 * <ul>
 *   <li><b>生成脚本</b>：逐行写成 INSERT 追加到调用方给的 Writer（流式，内存里不攒整表），
 *   与建表语句合成一份 .sql，供人审、存档、拿到客户端里跑；</li>
 *   <li><b>直接写入</b>：PreparedStatement + 批量 addBatch，边读边写，进度按表报、
 *   失败按表记账继续，不把几百万行拼成字符串。</li>
 * </ul>
 *
 * <p>三条硬口径：写入那条路是"按类型绑"而不是"拼字符串"；字面量渲染只服务脚本，转义按目标端
 * 各自的规则（MySQL 认反斜杠、达梦只双写单引号，换行用 {@code '||CHR(10)||'} 拼，免得一条语句
 * 被拆成两行）；二进制一律走十六进制，不猜编码、不吞字节。
 *
 * <p>达梦的自增列不认"直接塞值"，写之前要 {@code SET IDENTITY_INSERT … ON}：脚本里照写，
 * 写入时先试一句、不认就只记一笔继续——总比整张表因为主键列被拒而一行都进不去强。
 */
public final class DataMover {

    /** 写目标端时一批多少行：太小往返多，太大失败一次赔得多。 */
    static final int BATCH = 500;
    /** 长表在日志里报一次进度，免得进度框一动不动让人以为卡死。 */
    static final int LOG_EVERY = 20000;

    private DataMover() {
    }

    /**
     * 一张表的搬运计划：源表名/源列名照原样，目标表名/目标列名已按各自端的大小写规则折好。
     * {@code identityColumns} 是目标端里"要临时打开 IDENTITY_INSERT"的列（反向才有）。
     */
    public static final class Job {
        public final String sourceTable;
        public final String targetTable;
        public final List<String> sourceColumns;
        public final List<String> targetColumns;
        public final List<String> identityColumns;

        public Job(String sourceTable, String targetTable,
                   List<String> sourceColumns, List<String> targetColumns) {
            this(sourceTable, targetTable, sourceColumns, targetColumns,
                    new ArrayList<String>());
        }

        public Job(String sourceTable, String targetTable, List<String> sourceColumns,
                   List<String> targetColumns, List<String> identityColumns) {
            this.sourceTable = sourceTable;
            this.targetTable = targetTable;
            this.sourceColumns = sourceColumns;
            this.targetColumns = targetColumns;
            this.identityColumns = identityColumns;
        }
    }

    /** 一张表的收成。 */
    public static final class TableResult {
        public String table = "";
        public long rows;
        public long cleared;
        public String error = "";
    }

    /** 一趟搬运的收成：逐表 + 合计。 */
    public static final class Report {
        public final List<TableResult> tables = new ArrayList<TableResult>();
        public long rows;
        public long cleared;
        public boolean cancelled;
        public String error = "";
        /** 仅"生成脚本"这一路由调用方填：完整脚本落在哪儿。 */
        public java.io.File file;

        public int failed() {
            int n = 0;
            for (TableResult t : tables) {
                if (!t.error.isEmpty()) {
                    n++;
                }
            }
            return n;
        }
    }

    // ---------------------------------------------------------------- 脚本那一档

    /** 源是达梦、INSERT 往 Writer 里写（MySQL 语法）。 */
    public static Report scriptFromDm(DmReader src, String schema, List<Job> jobs, Writer w,
                                      ImportParse.Sink sink) {
        Report rep = new Report();
        sink.setTotal(jobs.size());
        for (Job job : jobs) {
            TableResult tr = new TableResult();
            tr.table = job.targetTable;
            rep.tables.add(tr);
            RowCursor cur = null;
            try {
                tableComment(w, job, "MySQL");
                identity(w, job, true, true);
                cur = RowCursor.openDm(src.connection(), schema, job.sourceTable,
                        job.sourceColumns);
                tr.rows = scriptRows(cur, job, w, true, sink, tr);
                rep.rows += tr.rows;
                identity(w, job, true, false);
            } catch (Exception e) {
                tr.error = LogText.describe(e);
                note(sink, "! [WARN] DATA | 表「" + job.sourceTable + "」没读完：" + tr.error);
            } finally {
                if (cur != null) {
                    cur.close();
                }
            }
            sink.advance(1, "表 " + job.sourceTable + "：" + tr.rows + " 行");
            if (sink.cancelled()) {
                rep.cancelled = true;
                break;
            }
        }
        finish(rep, sink);
        return rep;
    }

    /** 源是 MySQL、INSERT 往 Writer 里写（达梦语法）。 */
    public static Report scriptFromMysql(Config srcCfg, String db, List<Job> jobs, Writer w,
                                         ImportParse.Sink sink) {
        Report rep = new Report();
        sink.setTotal(jobs.size());
        DbClient src = null;
        try {
            src = new DbClient(srcCfg);
            for (Job job : jobs) {
                TableResult tr = new TableResult();
                tr.table = job.targetTable;
                rep.tables.add(tr);
                RowCursor cur = null;
                try {
                    tableComment(w, job, "达梦");
                    identity(w, job, false, true);
                    cur = RowCursor.openMysql(src.connection(), db, job.sourceTable,
                            job.sourceColumns);
                    tr.rows = scriptRows(cur, job, w, false, sink, tr);
                    rep.rows += tr.rows;
                    identity(w, job, false, false);
                } catch (Exception e) {
                    tr.error = LogText.describe(e);
                    note(sink, "! [WARN] DATA | 表「" + job.sourceTable + "」没读完：" + tr.error);
                } finally {
                    if (cur != null) {
                        cur.close();
                    }
                }
                sink.advance(1, "表 " + job.sourceTable + "：" + tr.rows + " 行");
                if (sink.cancelled()) {
                    rep.cancelled = true;
                    break;
                }
            }
        } catch (Exception e) {
            rep.error = LogText.describe(e);
        } finally {
            if (src != null) {
                src.close();
            }
        }
        finish(rep, sink);
        return rep;
    }

    /**
     * 把一张游标读空，逐行写成 INSERT。
     *
     * <p>单独抽成 public 是为了能被自检直接喂假游标：转义、NULL、二进制、标度这一层
     * 不连库也判得动，否则"看着对、跑起来炸"的问题只能到现场才发现。
     */
    public static long scriptRows(RowCursor cur, Job job, Writer w, boolean mysql,
                                  ImportParse.Sink sink, TableResult tr) throws Exception {
        long n = 0;
        while (cur.next()) {
            if (sink != null && sink.cancelled()) {
                break;
            }
            StringBuilder line = new StringBuilder("INSERT INTO ");
            line.append(RowCursor.q(job.targetTable, mysql)).append(" (")
                .append(RowCursor.join(job.targetColumns, mysql)).append(") VALUES (");
            for (int i = 1; i <= job.sourceColumns.size(); i++) {
                if (i > 1) {
                    line.append(", ");
                }
                line.append(literal(cur.get(i), mysql));
            }
            line.append(");\n");
            w.write(line.toString());
            n++;
            if (tr != null) {
                tr.rows = n;
            }
            if (sink != null && n % LOG_EVERY == 0) {
                sink.log("- [INFO] DATA | 表 " + job.sourceTable + " 已读 " + n + " 行…");
            }
        }
        return n;
    }

    /** 目标端 INSERT 头：给写入那一档拼 PreparedStatement，也给脚本做形状断言。 */
    public static String insertSql(String table, List<String> cols, boolean mysql) {
        StringBuilder sb = new StringBuilder("INSERT INTO ")
                .append(RowCursor.q(table, mysql)).append(" (")
                .append(RowCursor.join(cols, mysql)).append(") VALUES (");
        for (int i = 0; i < cols.size(); i++) {
            sb.append(i == 0 ? "?" : ", ?");
        }
        return sb.append(")").toString();
    }

    // ---------------------------------------------------------------- 写入那一档

    /** 源是达梦、直接写进 MySQL：批量 INSERT，出错按表记账继续。 */
    public static Report writeToMysql(DmReader src, String schema, List<Job> jobs,
                                      Config target, String targetDb, boolean clearFirst,
                                      ImportParse.Sink sink) {
        Report rep = new Report();
        sink.setTotal(jobs.size());
        DbClient db = null;
        try {
            db = new DbClient(target);
            Connection conn = db.connection();
            String use = targetDb == null ? "" : targetDb.trim();
            for (Job job : jobs) {
                TableResult tr = new TableResult();
                tr.table = job.targetTable;
                rep.tables.add(tr);
                PreparedStatement ins = null;
                RowCursor cur = null;
                try {
                    String qualified = (use.isEmpty() ? "" : RowCursor.q(use, true) + ".")
                            + RowCursor.q(job.targetTable, true);
                    tr.cleared = clear(conn, "DELETE FROM " + qualified, clearFirst, sink,
                            job.targetTable);
                    rep.cleared += tr.cleared;
                    ins = conn.prepareStatement(insertSql(job.targetTable, job.targetColumns, true));
                    cur = RowCursor.openDm(src.connection(), schema, job.sourceTable,
                            job.sourceColumns);
                    tr.rows = pump(cur, ins, job, rep, conn, sink);
                } catch (Exception e) {
                    tr.error = LogText.describe(e);
                    note(sink, "! [WARN] DATA | 表「" + job.sourceTable + "」写入中断（已提交 "
                            + tr.rows + " 行，这张表不完整）：" + tr.error);
                    try {
                        conn.rollback();
                    } catch (SQLException ignored) {
                        // 回滚不成也不影响"这张表算失败"这个结论
                    }
                } finally {
                    if (cur != null) {
                        cur.close();
                    }
                    closeQuietly(ins);
                }
                sink.advance(1, "表 " + job.sourceTable + "：" + tr.rows + " 行");
                if (rep.cancelled) {
                    break;
                }
            }
        } catch (Exception e) {
            rep.error = LogText.describe(e);
        } finally {
            if (db != null) {
                db.close();
            }
        }
        finish(rep, sink);
        return rep;
    }

    /** 源是 MySQL、直接写进达梦：批量 INSERT，出错按表记账继续。 */
    public static Report writeToDm(Config srcCfg, String db, List<Job> jobs, DmReader target,
                                   String dmSchema, boolean clearFirst, ImportParse.Sink sink) {
        Report rep = new Report();
        sink.setTotal(jobs.size());
        DbClient src = null;
        boolean autoCommit = true;
        try {
            src = new DbClient(srcCfg);
            Connection conn = target.connection();
            autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            for (Job job : jobs) {
                TableResult tr = new TableResult();
                tr.table = job.targetTable;
                rep.tables.add(tr);
                PreparedStatement ins = null;
                RowCursor cur = null;
                boolean identityOn = false;
                try {
                    String qualified = RowCursor.q(dmSchema, false) + "."
                            + RowCursor.q(job.targetTable, false);
                    tr.cleared = clear(conn, "DELETE FROM " + qualified, clearFirst, sink,
                            job.targetTable);
                    rep.cleared += tr.cleared;
                    identityOn = identityOn(conn, job, qualified, sink);
                    ins = conn.prepareStatement(insertSql(job.targetTable, job.targetColumns, false));
                    cur = RowCursor.openMysql(src.connection(), db, job.sourceTable,
                            job.sourceColumns);
                    tr.rows = pump(cur, ins, job, rep, conn, sink);
                } catch (Exception e) {
                    tr.error = LogText.describe(e);
                    note(sink, "! [WARN] DATA | 表「" + job.sourceTable + "」写入中断（已提交 "
                            + tr.rows + " 行，这张表不完整）：" + tr.error);
                    try {
                        conn.rollback();
                    } catch (SQLException ignored) {
                        // 同上：这张表已判失败
                    }
                } finally {
                    if (cur != null) {
                        cur.close();
                    }
                    closeQuietly(ins);
                    if (identityOn) {
                        identityOff(conn, job, dmSchema);
                    }
                }
                sink.advance(1, "表 " + job.sourceTable + "：" + tr.rows + " 行");
                if (rep.cancelled) {
                    break;
                }
            }
        } catch (Exception e) {
            rep.error = LogText.describe(e);
        } finally {
            if (src != null) {
                src.close();
            }
            try {
                if (target.connection().getAutoCommit() != autoCommit) {
                    target.connection().setAutoCommit(autoCommit);
                }
            } catch (SQLException ignored) {
                // 连接收了就算了
            }
        }
        finish(rep, sink);
        return rep;
    }

    /** 一只游标按批灌进目标：500 行一批、每批提交，取消在批边界生效。 */
    private static long pump(RowCursor cur, PreparedStatement ins, Job job, Report rep,
                             Connection conn, ImportParse.Sink sink) throws SQLException {
        long done = 0;
        int inBatch = 0;
        while (cur.next()) {
            if (sink.cancelled()) {
                rep.cancelled = true;
                break;
            }
            bindRow(ins, cur, job.sourceColumns.size());
            ins.addBatch();
            if (++inBatch >= BATCH) {
                ins.executeBatch();
                conn.commit();
                done += inBatch;
                rep.rows += inBatch;
                inBatch = 0;
                if (done % LOG_EVERY == 0) {
                    sink.log("- [INFO] DATA | 表 " + job.sourceTable + " 已写入 " + done + " 行…");
                }
            }
        }
        if (inBatch > 0) {
            ins.executeBatch();
            conn.commit();
            done += inBatch;
            rep.rows += inBatch;
        }
        return done;
    }

    private static long clear(Connection conn, String sql, boolean clearFirst,
                              ImportParse.Sink sink, String table) throws SQLException {
        if (!clearFirst) {
            return 0L;
        }
        java.sql.Statement st = conn.createStatement();
        try {
            long n = st.executeUpdate(sql);
            conn.commit();
            sink.log("- [INFO] DATA | 已清空「" + table + "」：" + n + " 行");
            return n;
        } finally {
            st.close();
        }
    }

    // ---------------------------------------------------------------- 公共零件

    /**
     * 一个值 → 目标端可执行的字面量。
     *
     * <p>MySQL 认反斜杠转义，所以 {@code \ ' 换行 回车 NUL Ctrl-Z} 都按它的规矩写；
     * 达梦不认反斜杠，只有单引号要双写，换行用 {@code '||CHR(10)||'} 拼。二进制两个方向
     * 都走十六进制（{@code X'..'} / {@code HEXTORAW('..')}），不猜字符集。
     */
    public static String literal(Object v, boolean mysql) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof byte[]) {
            String hex = hex((byte[]) v);
            return mysql ? "X'" + hex + "'" : "HEXTORAW('" + hex + "')";
        }
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue() ? "1" : "0";
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).toPlainString();
        }
        if (v instanceof Float || v instanceof Double) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return "NULL";     // 目标端没有 NaN 这个值，宁可空着让人看见
            }
            return v.toString();
        }
        if (v instanceof Number) {
            return v.toString();
        }
        if (v instanceof java.sql.Timestamp) {
            String s = v.toString();
            return quote(s.endsWith(".0") ? s.substring(0, s.length() - 2) : s, mysql);
        }
        if (v instanceof java.sql.Date || v instanceof java.sql.Time) {
            return quote(v.toString(), mysql);
        }
        if (v instanceof Date) {
            return quote(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((Date) v), mysql);
        }
        return quote(String.valueOf(v), mysql);
    }

    private static String quote(String s, boolean mysql) {
        return mysql ? quoteMysql(s) : quoteDm(s);
    }

    static String quoteMysql(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\'':
                    sb.append("''");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case 0:
                    sb.append("\\0");
                    break;
                case 26:
                    sb.append("\\Z");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.append('\'').toString();
    }

    static String quoteDm(String s) {
        if (s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return "'" + s.replace("'", "''") + "'";
        }
        StringBuilder sb = new StringBuilder();
        StringBuilder part = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                sb.append("'").append(part.toString().replace("'", "''")).append("'")
                  .append("||CHR(").append(c == '\n' ? 10 : 13).append(")||");
                part.setLength(0);
            } else {
                part.append(c);
            }
        }
        return sb.append("'").append(part.toString().replace("'", "''")).append("'").toString();
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16))
              .append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString().toUpperCase(java.util.Locale.ROOT);
    }

    /** 一行绑进 PreparedStatement：按值的真类型挑 setXxx，不拼字符串。 */
    static void bindRow(PreparedStatement ps, RowCursor cur, int columns) throws SQLException {
        for (int i = 1; i <= columns; i++) {
            Object v = cur.get(i);
            if (v == null) {
                // 不用 setNull(Types.NULL)：部分驱动（含达梦）不认这个"无类型"，
                // setObject(null) 才是两边都收的写法
                ps.setObject(i, null);
            } else if (v instanceof byte[]) {
                ps.setBytes(i, (byte[]) v);
            } else if (v instanceof java.sql.Timestamp) {
                ps.setTimestamp(i, (java.sql.Timestamp) v);
            } else if (v instanceof java.sql.Date) {
                ps.setDate(i, (java.sql.Date) v);
            } else if (v instanceof java.sql.Time) {
                ps.setTime(i, (java.sql.Time) v);
            } else if (v instanceof BigDecimal) {
                ps.setBigDecimal(i, (BigDecimal) v);
            } else if (v instanceof Boolean) {
                ps.setBoolean(i, ((Boolean) v).booleanValue());
            } else if (v instanceof String) {
                ps.setString(i, (String) v);
            } else {
                ps.setObject(i, v);
            }
        }
    }

    /** 脚本里的自增列开关：只在目标端是达梦、且这张表真有自增列时出现。 */
    private static void identity(Writer w, Job job, boolean mysql, boolean on)
            throws IOException {
        if (mysql || job.identityColumns.isEmpty()) {
            return;
        }
        w.write("SET IDENTITY_INSERT " + RowCursor.q(job.targetTable, false) + " "
                + (on ? "ON" : "OFF") + ";\n");
    }

    /** 写入时打开 IDENTITY_INSERT：达梦不认就只记一笔，不因为一句开关放弃整张表。 */
    private static boolean identityOn(Connection conn, Job job, String qualified,
                                      ImportParse.Sink sink) {
        if (job.identityColumns.isEmpty()) {
            return false;
        }
        java.sql.Statement st = null;
        try {
            st = conn.createStatement();
            st.execute("SET IDENTITY_INSERT " + qualified + " ON");
            return true;
        } catch (SQLException e) {
            sink.log("! [WARN] DATA | 这台达梦不认 SET IDENTITY_INSERT（" + LogText.describe(e)
                    + "），自增列按原值插入可能被拒");
            return false;
        } finally {
            closeQuietly(st);
        }
    }

    private static void identityOff(Connection conn, Job job, String schema) {
        java.sql.Statement st = null;
        try {
            st = conn.createStatement();
            st.execute("SET IDENTITY_INSERT " + RowCursor.q(schema, false) + "."
                    + RowCursor.q(job.targetTable, false) + " OFF");
            conn.commit();
        } catch (SQLException ignored) {
            // 关不掉也不影响已经灌进去的行
        } finally {
            closeQuietly(st);
        }
    }

    private static void tableComment(Writer w, Job job, String targetKind) throws IOException {
        if (!job.sourceTable.equals(job.targetTable)) {
            w.write("-- 源表 " + job.sourceTable + " → " + targetKind + " " + job.targetTable
                    + "\n");
        }
    }

    private static void finish(Report rep, ImportParse.Sink sink) {
        if (rep.cancelled) {
            note(sink, "! [WARN] DATA | 已取消：本次搬了 " + rep.rows + " 行，"
                    + "已完成的表保留，未读的表没动");
            return;
        }
        note(sink, "+ [OK] DATA | 共 " + rep.tables.size() + " 张表，" + rep.rows + " 行"
                + (rep.cleared > 0 ? "，清空旧数据 " + rep.cleared + " 行" : "")
                + "，失败 " + rep.failed() + " 张");
    }

    private static void note(ImportParse.Sink sink, String text) {
        if (sink != null) {
            sink.log(text);
        }
    }

    private static void closeQuietly(PreparedStatement ps) {
        if (ps != null) {
            try {
                ps.close();
            } catch (SQLException ignored) {
                // 收尾不抢业务的错
            }
        }
    }

    private static void closeQuietly(java.sql.Statement st) {
        if (st != null) {
            try {
                st.close();
            } catch (SQLException ignored) {
                // 同上
            }
        }
    }
}
