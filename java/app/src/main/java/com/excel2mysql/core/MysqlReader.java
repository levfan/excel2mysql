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
    private static final String SQL_ALL_COLUMNS =
            "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, "
                    + "COLUMN_COMMENT FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME, ORDINAL_POSITION";
    private static final String SQL_ALL_PRIMARY_KEY =
            "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                    + "WHERE TABLE_SCHEMA = ? AND CONSTRAINT_NAME = 'PRIMARY' "
                    + "ORDER BY TABLE_NAME, ORDINAL_POSITION";

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
        return readAll(cfg, schema, progress, null, cancelled);
    }

    /**
     * 同上，多一路总数回报（给进度条用）。
     *
     * <p>读法从"每张表三条查询"改成<b>整库三条查询</b>：几百张表的库里，
     * 原来的 3N 次往返要按分钟算，进度框一路干等，用户以为卡死（2026-11-14 反馈的
     * 「根本没法用」里就有这一条）。三条语句一次把表、列、主键取齐，再在内存里按表归堆。
     */
    public static Result readAll(Config cfg, String schema, Consumer<String> progress,
                                 java.util.function.IntConsumer total,
                                 BooleanSupplier cancelled) throws SQLException {
        Result out = new Result();
        DbClient db = new DbClient(cfg);
        try {
            out.version = serverVersion(db);
            java.util.LinkedHashMap<String, DmMysql.Table> byName = tableShells(db, schema);
            if (total != null) {
                total.accept(byName.size());
            }
            if (cancelled != null && cancelled.getAsBoolean()) {
                return out;
            }
            readColumns(db, schema, byName);
            if (cancelled != null && cancelled.getAsBoolean()) {
                return out;
            }
            readPrimaryKeys(db, schema, byName);
            for (DmMysql.Table t : byName.values()) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    return out;
                }
                if (progress != null) {
                    progress.accept(t.name);
                }
                out.tables.add(t);
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

    /** 表名与表注释，按名字排好；后面的列与主键都往这几只壳子里填。 */
    private static java.util.LinkedHashMap<String, DmMysql.Table> tableShells(DbClient db,
                                                                             String schema)
            throws SQLException {
        java.util.LinkedHashMap<String, DmMysql.Table> out =
                new java.util.LinkedHashMap<String, DmMysql.Table>();
        PreparedStatement ps = db.connection().prepareStatement(SQL_TABLES);
        try {
            ps.setString(1, schema);
            ResultSet rs = ps.executeQuery();
            try {
                while (rs.next()) {
                    DmMysql.Table t = new DmMysql.Table();
                    t.schema = schema == null ? "" : schema;
                    t.name = rs.getString(1);
                    t.comment = rs.getString(2) == null ? "" : rs.getString(2);
                    out.put(t.name, t);
                }
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
        return out;
    }

    /** 整库列定义一次取回，按 TABLE_NAME 归到各自的壳子里（顺序就是 ORDINAL_POSITION）。 */
    private static void readColumns(DbClient db, String schema,
                                    java.util.LinkedHashMap<String, DmMysql.Table> byName)
            throws SQLException {
        PreparedStatement ps = db.connection().prepareStatement(SQL_ALL_COLUMNS);
        try {
            ps.setString(1, schema);
            ResultSet rs = ps.executeQuery();
            try {
                while (rs.next()) {
                    DmMysql.Table t = byName.get(rs.getString(1));
                    if (t == null) {
                        continue;       // 不是基表（视图、系统表）的行，本来就不在清单里
                    }
                    DmMysql.Column c = new DmMysql.Column();
                    c.name = rs.getString(2);
                    c.type = rs.getString(3) == null ? "" : rs.getString(3);
                    c.nullable = "YES".equalsIgnoreCase(rs.getString(4));
                    String def = rs.getString(5);
                    c.dbDefault = def == null ? "" : def;
                    String extra = rs.getString(6);
                    c.autoIncrement = extra != null
                            && extra.toLowerCase(Locale.ROOT).contains("auto_increment");
                    c.comment = rs.getString(7) == null ? "" : rs.getString(7);
                    t.columns.add(c);
                }
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    /** 整库主键一次取回；复合主键靠 ORDER BY ORDINAL_POSITION 保住列序。 */
    private static void readPrimaryKeys(DbClient db, String schema,
                                        java.util.LinkedHashMap<String, DmMysql.Table> byName)
            throws SQLException {
        PreparedStatement ps = db.connection().prepareStatement(SQL_ALL_PRIMARY_KEY);
        try {
            ps.setString(1, schema);
            ResultSet rs = ps.executeQuery();
            try {
                while (rs.next()) {
                    DmMysql.Table t = byName.get(rs.getString(1));
                    if (t != null) {
                        t.primaryKeys.add(rs.getString(2));
                    }
                }
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }
}
