package com.excel2mysql.core;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 读 MySQL（8.0.x）的表结构：表注释、列定义、主键，灌进 {@link DmMysql.Table} 那套结构体，
 * 供 {@link MysqlDm} 翻成达梦 DDL。与 {@link DmReader}（读达梦）互为反向。
 *
 * <p>类型取 information_schema 的 COLUMN_TYPE 原文（如 <code>int unsigned</code>、
 * <code>varchar(255)</code>）：无符号、字符集尾巴这些信息只有原文里有，翻译层要看得到。
 * AUTO_INCREMENT 从 EXTRA 里认，翻到达梦那边落 IDENTITY。
 */
public final class MysqlReader {

    private static final String SQL_TABLES =
            "SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME";
    private static final String SQL_COLUMNS =
            "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT "
                    + "FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
    private static final String SQL_PRIMARY_KEY =
            "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND CONSTRAINT_NAME = 'PRIMARY' "
                    + "ORDER BY ORDINAL_POSITION";

    /** 一次读回的全部结果：表结构 + 服务端版本（版本只进摘要，不占第二行）。 */
    public static final class Result {
        public final List<DmMysql.Table> tables = new ArrayList<DmMysql.Table>();
        public String version = "";
    }

    private MysqlReader() {
    }

    /** 把一个库的全部基表读回来；progress 逐表报一句，cancelled 为真时中途收摊返回已读部分。 */
    public static Result readAll(Config cfg, String schema, Consumer<String> progress,
                                 BooleanSupplier cancelled) throws SQLException {
        Result out = new Result();
        DbClient db = new DbClient(cfg);
        try {
            out.version = serverVersion(db);
            List<String> names = listTables(db, schema);
            for (String n : names) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    return out;
                }
                if (progress != null) {
                    progress.accept(n);
                }
                out.tables.add(readTable(db, schema, n));
            }
            return out;
        } finally {
            db.close();
        }
    }

    private static String serverVersion(DbClient db) {
        try {
            PreparedStatement ps = db.connection().prepareStatement("SELECT VERSION()");
            try {
                ResultSet rs = ps.executeQuery();
                try {
                    return rs.next() ? rs.getString(1) : "";
                } finally {
                    rs.close();
                }
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            return "";
        }
    }

    private static List<String> listTables(DbClient db, String schema) throws SQLException {
        List<String> out = new ArrayList<String>();
        PreparedStatement ps = db.connection().prepareStatement(SQL_TABLES);
        try {
            ps.setString(1, schema);
            ResultSet rs = ps.executeQuery();
            try {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
        return out;
    }

    private static DmMysql.Table readTable(DbClient db, String schema, String table)
            throws SQLException {
        DmMysql.Table t = new DmMysql.Table();
        t.schema = schema == null ? "" : schema;
        t.name = table;
        PreparedStatement ps = db.connection().prepareStatement(SQL_TABLES);
        try {
            ps.setString(1, schema);
            ResultSet rs = ps.executeQuery();
            try {
                while (rs.next()) {
                    if (table.equals(rs.getString(1))) {
                        t.comment = rs.getString(2) == null ? "" : rs.getString(2);
                    }
                }
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
        ps = db.connection().prepareStatement(SQL_COLUMNS);
        try {
            ps.setString(1, schema);
            ps.setString(2, table);
            ResultSet rs = ps.executeQuery();
            try {
                while (rs.next()) {
                    DmMysql.Column c = new DmMysql.Column();
                    c.name = rs.getString(1);
                    c.type = rs.getString(2) == null ? "" : rs.getString(2);
                    c.nullable = "YES".equalsIgnoreCase(rs.getString(3));
                    String def = rs.getString(4);
                    c.dbDefault = def == null ? "" : def;
                    String extra = rs.getString(5);
                    c.autoIncrement = extra != null
                            && extra.toLowerCase(Locale.ROOT).contains("auto_increment");
                    c.comment = rs.getString(6) == null ? "" : rs.getString(6);
                    t.columns.add(c);
                }
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
        ps = db.connection().prepareStatement(SQL_PRIMARY_KEY);
        try {
            ps.setString(1, schema);
            ps.setString(2, table);
            ResultSet rs = ps.executeQuery();
            try {
                while (rs.next()) {
                    t.primaryKeys.add(rs.getString(1));
                }
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
        return t;
    }
}
