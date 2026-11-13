package com.excel2mysql.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MySQL（8.0.x）表结构 → 达梦（DM8）建表语句。与 {@link DmMysql}（达梦 → MySQL）互为反向。
 *
 * <p>输入是 information_schema 取回的表结构（{@link MysqlReader} 负责读，复用
 * {@link DmMysql.Table}/{@link DmMysql.Column} 两个结构体），输出达梦可直接执行的 DDL：
 * 双引号标识符、一行一列、<code>COMMENT ON</code> 单独跟在建表后（达梦不认 MySQL 的行内
 * <code>COMMENT '…'</code>）。
 *
 * <p>三处换算要点：MySQL 的 VARCHAR 长度按<b>字符</b>、达梦按<b>字节</b>，所以按
 * utf8mb4 最坏 4 字节/字符放大（宁宽不窄，装不下比宽度贵）；MySQL 的 AUTO_INCREMENT 翻成
 * 达梦的 <code>IDENTITY(1,1)</code>；MySQL 的 TIME / YEAR / JSON 达梦没有同名类型，
 * 落最接近的宽类型并逐列点名，不悄悄丢列。
 */
public final class MysqlDm {

    /** 达梦 VARCHAR 的字节上限，再大只能落 CLOB（保守取 8000，别贴着 8182 的边）。 */
    static final int DM_VARCHAR_MAX = 8000;
    /** utf8mb4 一个字符最多 4 字节：MySQL 字符长度 → 达梦字节长度按 4 倍放。 */
    static final int BYTES_PER_CHAR = 4;

    private MysqlDm() {
    }

    /**
     * 一组表 → 一份可原样执行的达梦脚本。
     *
     * @param dropExisting 每个建表前加 DROP TABLE，用于重跑覆盖
     * @param lower 表名与字段名转小写（MySQL 常是小写，达梦默认大写；转小写后靠引号保住）
     */
    public static DmMysql.Output script(List<DmMysql.Table> tables,
                                        boolean dropExisting, boolean lower) {
        DmMysql.Output out = new DmMysql.Output();
        StringBuilder sb = new StringBuilder();
        Set<String> taken = new LinkedHashSet<String>();
        for (DmMysql.Table t : tables) {
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

    /** 一张表 → 一段达梦 DDL（CREATE + COMMENT ON，不带结尾分号），译不出字段时返回 null。 */
    public static String ddl(DmMysql.Table t, boolean lower, List<String> warns) {
        return ddl(t, new LinkedHashSet<String>(), lower, warns, false);
    }

    private static String ddl(DmMysql.Table t, Set<String> taken, boolean lower,
                              List<String> warns, boolean dropExisting) {
        String who = t.schema.isEmpty() ? t.name : t.schema + "." + t.name;
        String table = name(t.name, lower);
        if (table == null) {
            warns.add("表「" + who + "」的表名为空或含换行/引号控制符，跳过不生成");
            return null;
        }
        if (!taken.add(table.toLowerCase(Locale.ROOT))) {
            warns.add("表「" + who + "」转小写后与前面某张表撞名，这一张按原名保留");
            table = t.name == null ? "" : t.name.trim();
            if (table.isEmpty() || !taken.add(table.toLowerCase(Locale.ROOT))) {
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
        List<String[]> comments = new ArrayList<String[]>();   // {列名, 注释}
        for (DmMysql.Column c : t.columns) {
            String field = name(c.name, lower);
            if (field == null) {
                warns.add("表「" + who + "」的字段「" + c.name + "」名为空或含换行/引号控制符，"
                        + "这一列没进建表语句");
                continue;
            }
            String type = dmType(c, who, warns);
            boolean required = !c.nullable || pkSet.contains(field);
            String def = c.autoIncrement ? null : dmDefault(c, type, who, warns);
            StringBuilder line = new StringBuilder("  ").append(q(field)).append(" ").append(type);
            if (c.autoIncrement) {
                line.append(" IDENTITY(1,1)");
                if (required) {
                    line.append(" NOT NULL");
                }
            } else if (required) {
                line.append(def == null ? " NOT NULL" : " NOT NULL DEFAULT " + def);
            } else {
                line.append(def == null ? " DEFAULT NULL" : " DEFAULT " + def);
            }
            if (body.length() > 0) {
                body.append(",\n");
            }
            body.append(line);
            String cm = comment(c.comment, who, c.name, warns);
            if (!cm.isEmpty()) {
                comments.add(new String[]{field, cm});
            }
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
            ddl.append("DROP TABLE ").append(q(table)).append(";\n");
        }
        ddl.append("CREATE TABLE ").append(q(table)).append(" (\n").append(body).append("\n)");
        String tc = comment(t.comment, who, "", warns);
        if (!tc.isEmpty()) {
            ddl.append(";\nCOMMENT ON TABLE ").append(q(table)).append(" IS ").append(lit(tc));
        }
        for (String[] cm : comments) {
            ddl.append(";\nCOMMENT ON COLUMN ").append(q(table)).append(".").append(q(cm[0]))
               .append(" IS ").append(lit(cm[1]));
        }
        return ddl.toString();
    }

    /**
     * 标识符：MySQL 的表名/字段名常带中文，达梦双引号标识符照收；只拦空名与换行，
     * 引号翻倍转义。转小写由调用方决定（达梦默认大写，加引号才保得住小写）。
     */
    public static String name(String raw, boolean lower) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty() || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) {
            return null;
        }
        return lower ? v.toLowerCase(Locale.ROOT) : v;
    }

    /** 达梦的双引号标识符；内部双引号翻倍。 */
    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    /** 达梦的字符串字面量：单引号翻倍。 */
    private static String lit(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /** 注释：换行压成空格、超长截断记账；达梦没有 MySQL 那个 1024 硬上限，仍别写超长。 */
    public static String comment(String raw, String who, String field, List<String> warns) {
        String v = raw == null ? "" : raw.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (v.length() > DmMysql.COMMENT_MAX) {
            warns.add("表「" + who + "」的" + (field.isEmpty() ? "表注释" : "字段「" + field + "」注释")
                    + "超过 " + DmMysql.COMMENT_MAX + " 字符，已截断");
            v = v.substring(0, DmMysql.COMMENT_MAX);
        }
        return v;
    }

    /**
     * MySQL 类型串（information_schema 的 COLUMN_TYPE，如 <code>int unsigned</code>、
     * <code>varchar(255)</code>）→ 达梦类型。认不出的落 CLOB 并点名，宁可宽一点也不悄悄丢数据。
     */
    static String dmType(DmMysql.Column c, String who, List<String> warns) {
        String raw = c.type == null ? "" : c.type.trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            warns.add("表「" + who + "」字段「" + c.name + "」没有类型信息，先落 CLOB，请人工确认");
            return "CLOB";
        }
        boolean unsigned = raw.contains("unsigned");
        String base = raw;
        int paren = raw.indexOf('(');
        String params = "";
        if (paren >= 0) {
            base = raw.substring(0, paren).trim();
            int close = raw.indexOf(')', paren);
            params = raw.substring(paren + 1, close > paren ? close : raw.length());
        }
        int sp = base.indexOf(' ');
        if (sp > 0) {
            base = base.substring(0, sp);
        }
        int n = firstInt(params, -1);
        int m = secondInt(params, -1);
        String t = base;
        if (t.equals("tinyint")) {
            warnUnsigned(unsigned, who, c.name, "TINYINT", warns);
            return "TINYINT";
        }
        if (t.equals("smallint")) {
            warnUnsigned(unsigned, who, c.name, "SMALLINT", warns);
            return "SMALLINT";
        }
        if (t.equals("mediumint") || t.equals("int") || t.equals("integer")) {
            warnUnsigned(unsigned, who, c.name, "INT", warns);
            return "INT";
        }
        if (t.equals("bigint")) {
            if (unsigned) {
                warns.add("表「" + who + "」字段「" + c.name + "」是 BIGINT UNSIGNED，"
                        + "达梦没有无符号类型，按 DECIMAL(20,0) 保住取值范围");
                return "DECIMAL(20,0)";
            }
            return "BIGINT";
        }
        if (t.equals("decimal") || t.equals("numeric") || t.equals("dec")) {
            int p = n > 0 ? n : 38;
            int s = m >= 0 ? m : 0;
            if (p > 38) {
                warns.add("表「" + who + "」字段「" + c.name + "」的精度 " + p
                        + " 超了达梦的 38，按 DECIMAL(38," + Math.min(s, 38) + ") 收");
                p = 38;
            }
            s = Math.min(s, p);
            return "DECIMAL(" + p + "," + s + ")";
        }
        if (t.equals("float")) {
            return "FLOAT";
        }
        if (t.equals("double") || t.equals("real")) {
            return "DOUBLE";
        }
        if (t.equals("bit") || t.equals("bool") || t.equals("boolean")) {
            warns.add("表「" + who + "」字段「" + c.name + "」的 " + t.toUpperCase(Locale.ROOT)
                    + " 按 0/1 语义落 TINYINT");
            return "TINYINT";
        }
        if (t.equals("char") || t.equals("varchar") || t.equals("nchar") || t.equals("nvarchar")) {
            return charType(t, n, who, c, warns);
        }
        if (t.equals("tinytext") || t.equals("text") || t.equals("mediumtext")
                || t.equals("longtext")) {
            return "CLOB";
        }
        if (t.equals("binary")) {
            int b = n > 0 ? n : 1;
            return b <= 8000 ? "BINARY(" + b + ")" : "BLOB";
        }
        if (t.equals("varbinary") || t.equals("tinyblob") || t.equals("blob")
                || t.equals("mediumblob") || t.equals("longblob")) {
            if (t.equals("varbinary")) {
                int b = n > 0 ? n : 1;
                return b <= DM_VARCHAR_MAX ? "VARBINARY(" + b + ")" : "BLOB";
            }
            return "BLOB";
        }
        if (t.equals("date")) {
            return "DATE";
        }
        if (t.equals("datetime") || t.equals("timestamp")) {
            return "TIMESTAMP";
        }
        if (t.equals("time")) {
            warns.add("表「" + who + "」字段「" + c.name + "」的 TIME 达梦没有对应类型，"
                    + "按 VARCHAR(12) 存字面值");
            return "VARCHAR(12)";
        }
        if (t.equals("year")) {
            warns.add("表「" + who + "」字段「" + c.name + "」的 YEAR 按 SMALLINT 存年份数值");
            return "SMALLINT";
        }
        if (t.equals("json")) {
            warns.add("表「" + who + "」字段「" + c.name + "」的 JSON 达梦没有原生类型，落 CLOB");
            return "CLOB";
        }
        if (t.equals("enum") || t.equals("set")) {
            warns.add("表「" + who + "」字段「" + c.name + "」的 " + t.toUpperCase(Locale.ROOT)
                    + " 按 VARCHAR(255) 存字面值");
            return "VARCHAR(255)";
        }
        if (t.equals("geometry") || t.endsWith("point") || t.endsWith("linestring")
                || t.endsWith("polygon") || t.endsWith("geometrycollection")) {
            warns.add("表「" + who + "」字段「" + c.name + "」的空间类型 " + t
                    + " 达梦没有对应类型，落 BLOB");
            return "BLOB";
        }
        warns.add("表「" + who + "」字段「" + c.name + "」的类型 " + t
                + " 不认识，先落成 CLOB，请人工确认");
        return "CLOB";
    }

    /** 字符类：MySQL 按字符给长度、达梦按字节，按 4 字节/字符放大；放不下落 CLOB。 */
    private static String charType(String t, int chars, String who, DmMysql.Column c,
                                   List<String> warns) {
        int n = chars > 0 ? chars : 1;
        long bytes = (long) n * BYTES_PER_CHAR;
        if (bytes > DM_VARCHAR_MAX) {
            warns.add("表「" + who + "」字段「" + c.name + "」的 " + t.toUpperCase(Locale.ROOT)
                    + "(" + n + ") 按 4 字节/字符放大后超了达梦 VARCHAR 上限，落 CLOB");
            return "CLOB";
        }
        boolean fixed = t.equals("char") || t.equals("nchar");
        return (fixed ? "CHAR(" : "VARCHAR(") + bytes + ")";
    }

    private static void warnUnsigned(boolean unsigned, String who, String field,
                                     String target, List<String> warns) {
        if (unsigned) {
            warns.add("表「" + who + "」字段「" + field + "」是无符号整数，达梦没有无符号类型，"
                    + "按 " + target + " 存（取值范围收窄到有符号区间）");
        }
    }

    /**
     * 默认值只搬达梦也认的字面量。information_schema 的 COLUMN_DEFAULT 给的是值本身
     * （字符串不带引号），所以字符串默认值要补上引号；函数式默认值只认 CURRENT_TIMESTAMP
     * 一族（达梦有 SYSDATE/CURRENT_TIMESTAMP），其余宁可丢掉并点名，不写出跑不了的 DDL。
     */
    static String dmDefault(DmMysql.Column c, String dmType, String who, List<String> warns) {
        String d = c.dbDefault == null ? "" : c.dbDefault.trim();
        if (d.isEmpty() || d.equalsIgnoreCase("NULL")) {
            return null;
        }
        if (c.autoIncrement) {
            return null;
        }
        String up = d.toUpperCase(Locale.ROOT);
        if (up.startsWith("CURRENT_TIMESTAMP") || up.startsWith("NOW(")
                || up.startsWith("SYSDATE") || up.startsWith("CURRENT_DATE")) {
            return dmType.startsWith("TIMESTAMP") || dmType.startsWith("DATE") ? "SYSDATE" : null;
        }
        if (d.indexOf('(') >= 0) {
            warns.add("表「" + who + "」字段「" + c.name + "」的默认值「" + d
                    + "」是表达式，达梦这版不照搬，没写默认值");
            return null;
        }
        boolean numericType = dmType.startsWith("DECIMAL") || dmType.startsWith("INT")
                || dmType.startsWith("TINYINT") || dmType.startsWith("SMALLINT")
                || dmType.startsWith("BIGINT") || dmType.startsWith("FLOAT")
                || dmType.startsWith("DOUBLE");
        if (numericType && d.matches("^-?\\d+(\\.\\d+)?$")) {
            return d;
        }
        if (dmType.startsWith("BLOB") || dmType.startsWith("CLOB")
                || dmType.startsWith("VARBINARY") || dmType.startsWith("BINARY")) {
            warns.add("表「" + who + "」字段「" + c.name + "」是 " + dmType
                    + "，达梦不给这类列默认值，这条默认值没搬");
            return null;
        }
        if (numericType) {
            warns.add("表「" + who + "」字段「" + c.name + "」的默认值「" + d
                    + "」不是数值，数值列没搬这条默认值");
            return null;
        }
        if (d.length() >= 2 && d.charAt(0) == '\'' && d.endsWith("'")) {
            d = d.substring(1, d.length() - 1).replace("''", "'");
        }
        return lit(d);
    }

    private static int firstInt(String params, int fallback) {
        try {
            String p = params.split(",")[0].trim();
            return p.isEmpty() ? fallback : Integer.parseInt(p);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int secondInt(String params, int fallback) {
        String[] parts = params.split(",");
        if (parts.length < 2) {
            return fallback;
        }
        try {
            return Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String join(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(q(n));
        }
        return sb.toString();
    }
}
