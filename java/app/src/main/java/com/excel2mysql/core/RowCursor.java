package com.excel2mysql.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Reader;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Blob;
import java.util.List;

/**
 * 一张表的只读游标：按给定的列清单流式取行，取出来的值先归一成基础 Java 类型。
 *
 * <p>数据搬运（第五页「创建表」不勾那一档）两个方向都从这儿拿行：源是 MySQL 时用
 * Connector/J 的流式开关（{@code setFetchSize(Integer.MIN_VALUE)}），源是达梦时给
 * 500 一批的游标；两边都不把整表读进内存，几百万行也只是"一行一行过去"。
 *
 * <p>归一这一步是刻意的：驱动给的 CLOB/BLOB 绑在当前连接上，出了 ResultSet 就读不到了，
 * 所以取值的当场就读成 String / byte[]；剩下的数值、时间、布尔保持 JDBC 的本来类型，
 * 写目标端时按类型挑 setXxx，不靠字符串拼接收场。
 */
public interface RowCursor extends AutoCloseable {

    /** 达梦游标一批取多少行：太大占内存，太小往返多。 */
    int DM_FETCH = 500;

    boolean next() throws SQLException;

    Object get(int columnOneBased) throws SQLException;

    @Override
    void close();

    /** 源是 MySQL：库名与表名都按反引号引，列清单用表结构里那批原名。 */
    static RowCursor openMysql(Connection conn, String db, String table, List<String> columns)
            throws SQLException {
        String sql = "SELECT " + join(columns, true) + " FROM "
                + q(db, true) + "." + q(table, true);
        PreparedStatement ps = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY);
        // Connector/J 的流式开关：不设它，驱动会把整份结果集先拉进内存
        ps.setFetchSize(Integer.MIN_VALUE);
        ps.setFetchDirection(ResultSet.FETCH_FORWARD);
        try {
            return new JdbcRows(ps, ps.executeQuery(), columns.size());
        } catch (SQLException e) {
            ps.close();
            throw e;
        }
    }

    /** 源是达梦：模式与表名按双引号引（库里存的是大写标识符）。 */
    static RowCursor openDm(Connection conn, String schema, String table, List<String> columns)
            throws SQLException {
        String sql = "SELECT " + join(columns, false) + " FROM "
                + q(schema, false) + "." + q(table, false);
        PreparedStatement ps = conn.prepareStatement(sql);
        ps.setFetchSize(DM_FETCH);
        try {
            return new JdbcRows(ps, ps.executeQuery(), columns.size());
        } catch (SQLException e) {
            ps.close();
            throw e;
        }
    }

    static String q(String ident, boolean mysql) {
        if (mysql) {
            return "`" + ident.replace("`", "``") + "`";
        }
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    static String join(List<String> cols, boolean mysql) {
        StringBuilder sb = new StringBuilder();
        for (String c : cols) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(q(c, mysql));
        }
        return sb.toString();
    }

    /** 真 JDBC 游标：只管取值与归一，判断"读到头了"交给 next()。 */
    final class JdbcRows implements RowCursor {
        private final PreparedStatement ps;
        private final ResultSet rs;
        private final int columns;

        JdbcRows(PreparedStatement ps, ResultSet rs, int columns) {
            this.ps = ps;
            this.rs = rs;
            this.columns = columns;
        }

        @Override
        public boolean next() throws SQLException {
            return rs.next();
        }

        @Override
        public Object get(int i) throws SQLException {
            if (i < 1 || i > columns) {
                throw new SQLException("取值下标越界：" + i + "（共 " + columns + " 列）");
            }
            return normalize(rs.getObject(i));
        }

        @Override
        public void close() {
            try {
                rs.close();
            } catch (SQLException ignored) {
                // 收尾不抢业务的错
            }
            try {
                ps.close();
            } catch (SQLException ignored) {
                // 同上
            }
        }
    }

    /** 驱动给的东西归一成基础类型：LOB 当场读出来，其余原样。 */
    static Object normalize(Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        if (v instanceof Clob) {
            Clob c = (Clob) v;
            return readAll(c);
        }
        if (v instanceof Blob) {
            Blob b = (Blob) v;
            return readAll(b);
        }
        if (v instanceof java.io.Serializable && !(v instanceof String)
                && !(v instanceof Number) && !(v instanceof Boolean)
                && !(v instanceof java.util.Date) && !(v instanceof byte[])) {
            // 驱动偶尔回自己那一套包装类型（例如达梦的 DmdbTimestamp）：按字符串收，
            // 目标端的 setObject 认不出来，硬塞只会到执行期才炸
            return String.valueOf(v);
        }
        return v;
    }

    static String readAll(Clob c) throws SQLException {
        Reader r = c.getCharacterStream();
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        try {
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
        } catch (java.io.IOException e) {
            throw new SQLException("读 CLOB 失败：" + e.getMessage(), e);
        } finally {
            try {
                r.close();
            } catch (java.io.IOException ignored) {
                // 关不掉也不影响已经读到的内容
            }
        }
        return sb.toString();
    }

    static byte[] readAll(Blob b) throws SQLException {
        InputStream in = b.getBinaryStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (java.io.IOException e) {
            throw new SQLException("读 BLOB 失败：" + e.getMessage(), e);
        } finally {
            try {
                in.close();
            } catch (java.io.IOException ignored) {
                // 同上
            }
        }
        return out.toByteArray();
    }
}
