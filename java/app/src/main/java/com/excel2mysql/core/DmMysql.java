package com.excel2mysql.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 达梦（DM8）表结构 → MySQL 建表语句。
 *
 * <p>输入是达梦 ALL_TAB_COLUMNS / ALL_TAB_COMMENTS / ALL_COL_COMMENTS / ALL_CONSTRAINTS
 * 那几本目录视图原样给出的行（{@link DmReader} 负责读），输出与工具自己生成的 DDL 同形：
 * 反引号名、一行一列、表尾 {@code ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
 * COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='…'}。
 *
 * <p>两处刻意不对称：达梦的字符长度按字节计、MySQL 按字符计，所以长度 1:1 平移只会更宽
 * 不会装不下；达梦的 DATE 带时分秒，翻成 MySQL 的 DATE 会静默丢掉时间，所以落 DATETIME。
 */
public final class DmMysql {

    /** MySQL 的表注释与列注释上限都是 1024 字符，超了服务端直接报错而不是截断。 */
    static final int COMMENT_MAX = 1024;
    /** MySQL 一列 CHAR 最多 255 字节，达梦更长的定长列得降成变长。 */
    static final int CHAR_MAX = 255;
    /** utf8mb4 下 VARCHAR 的字符数上限，再大就只能改 TEXT（还有行宽那道线在等着）。 */
    static final int VARCHAR_MAX = 16383;

    public static final class Column {
        public String name = "";
        public String type = "";
        public int length;
        public int precision = -1;
        public int scale = -1;
        public boolean nullable = true;
        public String dbDefault = "";
        public String comment = "";
    }

    public static final class Table {
        public String schema = "";
        public String name = "";
        public String comment = "";
        public final List<Column> columns = new ArrayList<Column>();
        public final List<String> primaryKeys = new ArrayList<String>();
    }

    public static final class Output {
        public String sql = "";
        public final List<String> warnings = new ArrayList<String>();
        public final List<String> skipped = new ArrayList<String>();
        public int tables;
    }

    private DmMysql() {
    }

    /**
     * 一组表 → 一份可原样执行的脚本。
     *
     * @param targetDb 非空时在开头写 USE，让脚本落进指定的库
     * @param dropExisting 每个建表前加 DROP，用于重跑覆盖
     * @param lower 表名与字段名转小写（达梦默认全大写，MySQL 在 Linux 下区分大小写）
     */
    public static Output script(List<Table> tables, String targetDb,
                                boolean dropExisting, boolean lower) {
        Output out = new Output();
        StringBuilder sb = new StringBuilder();
        String db = targetDb == null ? "" : targetDb.trim();
        if (!db.isEmpty()) {
            if (!db.matches("^[A-Za-z0-9_]+$")) {
                out.warnings.add("目标库名「" + db + "」不合规（只允许字母、数字、下划线），USE 头没写");
            } else {
                sb.append("USE `").append(db).append("`;\n\n");
            }
        }
        Set<String> taken = new LinkedHashSet<String>();
        for (Table t : tables) {
            String one = ddl(t, taken, lower, out.warnings, dropExisting);
            if (one == null) {
                out.skipped.add(t.schema.isEmpty() ? t.name : t.schema + "." + t.name);
                continue;
            }
            out.tables++;
            sb.append(one).append(";\n\n");
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        out.sql = sb.toString();
        return out;
    }

    /** 一张表 → 一条 CREATE TABLE（不带结尾分号），译不出字段时返回 null。 */
    public static String ddl(Table t, boolean lower, List<String> warns) {
        return ddl(t, new LinkedHashSet<String>(), lower, warns, false);
    }

    private static String ddl(Table t, Set<String> taken, boolean lower,
                              List<String> warns, boolean dropExisting) {
        String who = t.schema.isEmpty() ? t.name : t.schema + "." + t.name;
        String table = name(t.name, lower);
        if (table == null) {
            warns.add("表「" + who + "」的表名不在允许范围（字母、数字、下划线），跳过不生成");
            return null;
        }
        if (!taken.add(table.toLowerCase(Locale.ROOT))) {
            warns.add("表「" + who + "」转小写后与前面某张表撞名，这一张按原名保留");
            table = t.name.trim();
            if (!taken.add(table.toLowerCase(Locale.ROOT))) {
                warns.add("表「" + who + "」还是与前面的表同名，跳过不生成");
                return null;
            }
        }
        List<String> pk = new ArrayList<String>();
        for (String k : t.primaryKeys) {
            String key = name(k, lower);
            if (key != null) {
                pk.add(key);
            }
        }
        Set<String> pkSet = new LinkedHashSet<String>(pk);
        StringBuilder body = new StringBuilder();
        for (Column c : t.columns) {
            String field = name(c.name, lower);
            if (field == null) {
                warns.add("表「" + who + "」的字段「" + c.name + "」名不在允许范围，这一列没进建表语句");
                continue;
            }
            String type = mysqlType(c, who, warns);
            boolean required = !c.nullable || pkSet.contains(field);
            String def = mysqlDefault(c, type, who, warns);
            StringBuilder line = new StringBuilder("  `").append(field).append("` ").append(type);
            if (required) {
                line.append(def == null ? " NOT NULL" : " NOT NULL DEFAULT " + def);
            } else {
                line.append(def == null ? " DEFAULT NULL" : " DEFAULT " + def);
            }
            line.append(" COMMENT '").append(comment(c.comment, who, field, warns)).append("'");
            if (body.length() > 0) {
                body.append(",\n");
            }
            body.append(line);
        }
        if (body.length() == 0) {
            warns.add("表「" + who + "」一个字段都没译出来，跳过不生成");
            return null;
        }
        if (!pk.isEmpty()) {
            body.append(",\n  PRIMARY KEY (").append(join(pk)).append(")");
        }
        StringBuilder ddl = new StringBuilder();
        if (dropExisting) {
            ddl.append("DROP TABLE IF EXISTS `").append(table).append("`;\n");
        }
        ddl.append("CREATE TABLE `").append(table).append("` (\n").append(body).append("\n) ")
                .append("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ")
                .append("COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='")
                .append(comment(t.comment, who, "", warns)).append("'");
        return ddl.toString();
    }

    private static String join(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append('`').append(n).append('`');
        }
        return sb.toString();
    }

    /** 标识符：只放字母数字下划线，其余（含中文列名与引号）一律不搬——搬过去就是一句跑不了的 DDL。 */
    public static String name(String raw, boolean lower) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty() || !v.matches("^[A-Za-z0-9_]+$")) {
            return null;
        }
        return lower ? v.toLowerCase(Locale.ROOT) : v;
    }

    /** 注释：换行压成空格、单引号翻倍、超上限截断并记账（服务端对超长是直接报错）。 */
    public static String comment(String raw, String who, String field, List<String> warns) {
        String v = raw == null ? "" : raw.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (v.length() > COMMENT_MAX) {
            warns.add("表「" + who + "」的" + (field.isEmpty() ? "表注释" : "字段「" + field + "」注释")
                    + "超过 " + COMMENT_MAX + " 字符，已截断");
            v = v.substring(0, COMMENT_MAX);
        }
        return v.replace("'", "''");
    }

    /** 达梦类型 → MySQL 类型；认不出的落 LONGTEXT 并点名，宁可宽一点也不悄悄丢数据。 */
    static String mysqlType(Column c, String who, List<String> warns) {
        String t = c.type == null ? "" : c.type.trim().toUpperCase(Locale.ROOT);
        int len = c.length;
        if (t.startsWith("NVARCHAR") || t.startsWith("VARCHAR")) {
            return varchar(len, who, c, warns);
        }
        if (t.startsWith("NCHAR") || t.startsWith("CHAR")) {
            return len <= CHAR_MAX ? "CHAR(" + Math.max(len, 1) + ")" : varchar(len, who, c, warns);
        }
        // BOOLEAN 是 tinyint(1) 的别名；直接写 TINYINT(1) 在 MySQL 8.0.17+ 执行会报
        // "Integer display width is deprecated"
        if (t.equals("BIT") || t.equals("BOOL") || t.equals("BOOLEAN")) {
            return "BOOLEAN";
        }
        if (t.equals("TINYINT")) {
            return "TINYINT";
        }
        if (t.equals("SMALLINT")) {
            return "SMALLINT";
        }
        if (t.equals("INT") || t.equals("INTEGER")) {
            return "INT";
        }
        if (t.equals("BIGINT")) {
            return "BIGINT";
        }
        if (t.equals("NUMBER") || t.equals("DEC") || t.equals("DECIMAL") || t.equals("NUMERIC")) {
            int p = c.precision;
            int s = Math.max(c.scale, 0);
            if (p <= 0) {
                warns.add("表「" + who + "」字段「" + c.name + "」的 " + t
                        + " 没给精度，按 DECIMAL(38,10) 兜住");
                return "DECIMAL(38,10)";
            }
            if (p > 65) {
                warns.add("表「" + who + "」字段「" + c.name + "」的精度 " + p
                        + " 超了 MySQL 的 65，按 DECIMAL(65," + Math.min(s, 30) + ") 收");
            }
            int pp = Math.min(p, 65);
            int ss = Math.min(s, Math.min(pp, 30));
            if (ss == 0 && pp <= 19 && !t.equals("DECIMAL") && !t.equals("DEC")
                    && !t.equals("NUMERIC")) {
                return pp <= 9 ? "INT" : "BIGINT";
            }
            return "DECIMAL(" + pp + "," + ss + ")";
        }
        if (t.equals("FLOAT") || t.equals("DOUBLE") || t.equals("BINARY_DOUBLE")) {
            return "DOUBLE";
        }
        if (t.equals("REAL") || t.equals("BINARY_FLOAT")) {
            return "FLOAT";
        }
        if (t.startsWith("TIMESTAMP") || t.startsWith("DATETIME")) {
            int fsp = c.scale > 0 && c.scale <= 6 ? c.scale : 0;
            return fsp == 0 ? "DATETIME" : "DATETIME(" + fsp + ")";
        }
        if (t.equals("DATE")) {
            return "DATETIME";
        }
        if (t.startsWith("TIME")) {
            return "TIME";
        }
        if (t.startsWith("INTERVAL")) {
            warns.add("表「" + who + "」字段「" + c.name + "」的 " + t
                    + " 在 MySQL 没有对应类型，按 VARCHAR(64) 存字面值");
            return "VARCHAR(64)";
        }
        if (t.startsWith("VARBINARY")) {
            return "VARBINARY(" + Math.max(len <= 0 ? 1 : Math.min(len, VARCHAR_MAX), 1) + ")";
        }
        if (t.startsWith("BINARY")) {
            return len > 0 && len <= CHAR_MAX ? "BINARY(" + len + ")" : "LONGBLOB";
        }
        if (t.equals("TEXT") || t.equals("TINYTEXT") || t.equals("MEDIUMTEXT")) {
            return "TEXT";
        }
        if (t.equals("CLOB") || t.equals("NCLOB") || t.equals("LONGVARCHAR") || t.equals("LONG")) {
            return "LONGTEXT";
        }
        if (t.equals("BLOB") || t.equals("LONGVARBINARY") || t.equals("IMAGE")) {
            return "LONGBLOB";
        }
        if (t.equals("JSON")) {
            return "JSON";
        }
        if (t.equals("ROWID")) {
            warns.add("表「" + who + "」字段「" + c.name + "」是 ROWID，MySQL 没有这一类型，按 CHAR(18) 存");
            return "CHAR(18)";
        }
        warns.add("表「" + who + "」字段「" + c.name + "」的类型 " + t
                + " 不认识，先落成 LONGTEXT，请人工确认");
        return "LONGTEXT";
    }

    private static String varchar(int len, String who, Column c, List<String> warns) {
        if (len <= 0) {
            return "TEXT";
        }
        if (len > VARCHAR_MAX) {
            warns.add("表「" + who + "」字段「" + c.name + "」长 " + len
                    + " 超了 VARCHAR 上限，改落 LONGTEXT");
            return "LONGTEXT";
        }
        return "VARCHAR(" + len + ")";
    }

    /**
     * 默认值只搬 MySQL 也认的那几种字面量。
     *
     * <p>达梦的 DATA_DEFAULT 里会出现 SYSDATE、"NOW()"()、带双引号的字符串这类 MySQL
     * 不认或语义不同的写法；照抄进 CREATE TABLE 就是一句建不出来的 DDL，所以宁可丢掉
     * 默认值并在日志里点名，也不写出跑不了的语句。
     */
    static String mysqlDefault(Column c, String mysqlType, String who, List<String> warns) {
        String d = c.dbDefault == null ? "" : c.dbDefault.trim();
        if (d.isEmpty() || d.equalsIgnoreCase("NULL")) {
            return null;
        }
        if (mysqlType.startsWith("TEXT") || mysqlType.startsWith("LONGTEXT")
                || mysqlType.startsWith("MEDIUMTEXT") || mysqlType.startsWith("TINYTEXT")
                || mysqlType.contains("BLOB") || mysqlType.startsWith("JSON")) {
            warns.add("表「" + who + "」字段「" + c.name + "」是 " + mysqlType
                    + "，MySQL 不给这类列默认值（服务端报 1101），这条默认值没搬");
            return null;
        }
        if (d.matches("^-?\\d+(\\.\\d+)?$")
                && (mysqlType.startsWith("DECIMAL") || mysqlType.startsWith("INT")
                        || mysqlType.startsWith("BIGINT") || mysqlType.startsWith("TINYINT")
                        || mysqlType.startsWith("SMALLINT") || mysqlType.startsWith("DOUBLE")
                        || mysqlType.startsWith("FLOAT"))) {
            return d;
        }
        if (d.length() > 1 && d.charAt(0) == '\'' && d.endsWith("'")) {
            return mysqlType.startsWith("DATE") || mysqlType.startsWith("TIME") ? null : d;
        }
        String up = d.toUpperCase(Locale.ROOT);
        if (up.startsWith("CURRENT_TIMESTAMP") || up.startsWith("SYSDATE")
                || up.startsWith("NOW(") || up.startsWith("CURRENT_DATE")) {
            if (mysqlType.startsWith("DATETIME") || mysqlType.startsWith("TIMESTAMP")) {
                // 小数秒位数必须跟列定义一致：DATETIME(6) 配裸的 CURRENT_TIMESTAMP，
                // 服务端直接报 1067 Invalid default value（真库上撞出来的）
                int paren = mysqlType.indexOf('(');
                return "CURRENT_TIMESTAMP" + (paren > 0 ? mysqlType.substring(paren) : "");
            }
        }
        warns.add("表「" + who + "」字段「" + c.name + "」的默认值「" + d
                + "」MySQL 这边不认或语义不同，没搬过来");
        return null;
    }
}
