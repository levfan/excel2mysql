package com.excel2mysql.core;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * core/mysql_io.py 的连库部分：列库、建表、分批写入。
 *
 * 事务粒度与 Python 线对齐——autocommit 关掉，一个文件一次 commit，
 * 中途失败整文件回滚，不出现"半张表"。
 */
public final class DbClient implements AutoCloseable {

    private static final Set<String> HIDDEN_DATABASES = new LinkedHashSet<String>(
            Arrays.asList("information_schema", "performance_schema", "mysql", "sys"));

    private final Connection conn;

    public DbClient(Config cfg) throws SQLException {
        // 没选库时也允许连过去列库，所以 database 可为空
        this.conn = DriverManager.getConnection(cfg.jdbcUrl(), cfg.user, cfg.password);
        this.conn.setAutoCommit(false);
    }

    public Connection connection() {
        return conn;
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            System.out.println("! [WARN] DB | 关闭连接失败：" + e.getMessage());
        }
    }

    public List<String> listDatabases() throws SQLException {
        List<String> out = new ArrayList<String>();
        Statement st = conn.createStatement();
        try {
            ResultSet rs = st.executeQuery("SHOW DATABASES");
            try {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (!HIDDEN_DATABASES.contains(name.toLowerCase())) {
                        out.add(name);
                    }
                }
            } finally {
                rs.close();
            }
        } finally {
            st.close();
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** DDL 里可能带 DROP 语句，按 Python 侧同一个 ";\n" 分隔逐条执行。 */
    public void createTable(String ddl) throws SQLException {
        Statement st = conn.createStatement();
        try {
            for (String part : ddl.split(";\n")) {
                if (!part.trim().isEmpty()) {
                    st.execute(part.trim());
                }
            }
            conn.commit();
        } finally {
            st.close();
        }
    }

    /** 一个文件的写入；失败时整文件回滚后把异常抛给调用方继续下一个文件。 */
    public long insertData(String tableName, List<Infer.Column> columns,
                           List<Object> headers, List<List<Object>> rows,
                           WarnCallback warn, ProgressCallback progress) throws SQLException {
        List<String> types = new ArrayList<String>();
        for (Infer.Column c : columns) {
            types.add(c.type);
        }
        String sql = Ddl.buildInsertSql(tableName, columns);
        int total = rows.size();
        long ok = 0;
        // 列序号 -> {失败次数, 首个失败示例}，与 Python 侧的告警口径一致
        java.util.Map<Integer, Object[]> warnMap = new java.util.LinkedHashMap<Integer, Object[]>();
        PreparedStatement ps = conn.prepareStatement(sql);
        try {
            for (int start = 0; start < total; start += 500) {
                int end = Math.min(start + 500, total);
                for (int r = start; r < end; r++) {
                    List<Object> row = rows.get(r);
                    for (int j = 0; j < columns.size(); j++) {
                        Object raw = j < row.size() ? row.get(j) : null;
                        Ddl.Converted converted = Ddl.convert(raw, types.get(j));
                        if (converted.error != null) {
                            Object[] info = warnMap.get(Integer.valueOf(j));
                            if (info == null) {
                                info = new Object[]{Long.valueOf(0),
                                        "第 " + (r + 1) + " 行 " + reprOf(raw)};
                                warnMap.put(Integer.valueOf(j), info);
                            }
                            info[0] = Long.valueOf(((Long) info[0]).longValue() + 1);
                        }
                        ps.setObject(j + 1, toJdbc(converted.value));
                    }
                    ps.addBatch();
                }
                int[] counts = ps.executeBatch();
                int chunk = end - start;
                long counted = 0;
                for (int c : counts) {
                    counted += c > 0 ? c : (c == Statement.SUCCESS_NO_INFO ? 1 : 0);
                }
                ok += counted > 0 ? counted : chunk;
                if (progress != null) {
                    progress.onProgress(end, total);
                }
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            ps.close();
        }
        if (warn != null) {
            List<Integer> keys = new ArrayList<Integer>(warnMap.keySet());
            java.util.Collections.sort(keys);
            for (Integer j : keys) {
                Object[] info = warnMap.get(j);
                Infer.Column c = columns.get(j.intValue());
                warn.onWarn("[数据警告] `" + tableName + "`.`" + c.field + "`（" + c.type
                        + "）共 " + info[0] + " 个值无法转换，示例：" + info[1]
                        + "；已按 NULL 写入，如需保留原值请把该列类型改为文本后重新导入");
            }
        }
        return ok;
    }

    /**
     * LocalDate/LocalDateTime/BigInteger 要转成 JDBC 认得的形式。
     *
     * 这条就是"值怎么发进库"的契约，界面与命令行共用；
     * GuiMain 的自检直接断这里（超出 BIGINT 的那个分支），改坏了自检会红。
     */
    public static Object toJdbc(Object v) {
        if (v instanceof BigInteger) {
            BigInteger b = (BigInteger) v;
            // 装不进 64 位的整数必须原样交给服务端：Connector/J 对 BigInteger 走
            // longValue()，9223372036854775808 会被截成 -9223372036854775808，
            // 库那头看着完全合法，数已经在库里变了——现场查不出来，只能事后对账才发现。
            // 转成 BigDecimal 发出去，MySQL 按 BIGINT 上限判它越界，报 1264，
            // 与 Python 线（pymysql 发原始值）的结局和文案一致。
            if (b.bitLength() <= 63) {
                return Long.valueOf(b.longValue());
            }
            return new java.math.BigDecimal(b);
        }
        if (v instanceof java.time.LocalDateTime) {
            return java.sql.Timestamp.valueOf((java.time.LocalDateTime) v);
        }
        if (v instanceof java.time.LocalDate) {
            return java.sql.Date.valueOf((java.time.LocalDate) v);
        }
        return v;
    }

    private static String reprOf(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof String) {
            return "'" + v + "'";
        }
        return String.valueOf(v);
    }

    public interface WarnCallback {
        void onWarn(String message);
    }

    public interface ProgressCallback {
        void onProgress(int done, int total);
    }
}
