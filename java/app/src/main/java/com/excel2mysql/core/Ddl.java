package com.excel2mysql.core;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;

/**
 * core/mysql_io.py 里不碰数据库的那部分：DDL 生成、INSERT 模板、按目标类型转值。
 * 单独拆出来是为了让建表语句和取值转换能先离线对撞，真连库那部分留给 P3 的 G 组等价回归。
 */
public final class Ddl {

    private Ddl() {
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    /**
     * 主键约束至多一个，可由多个字段组成。空值规则：主键列一律 NOT NULL；
     * 结构定义模式里"是否可为空=否"的列 NOT NULL；其余 DEFAULT NULL。
     */
    public static String buildDdl(String tableName, String tableComment,
                                  List<Infer.Column> columns, boolean dropIfExists) {
        List<String> pk = new java.util.ArrayList<String>();
        for (Infer.Column c : columns) {
            if (c.primaryKey) {
                pk.add(c.field);
            }
        }
        StringBuilder lines = new StringBuilder();
        for (Infer.Column c : columns) {
            boolean notNull = pk.contains(c.field) || Boolean.FALSE.equals(c.nullable);
            String line = "  `" + c.field + "` " + c.type + " "
                    + (notNull ? "NOT NULL" : "DEFAULT NULL")
                    + " COMMENT '" + esc(c.comment == null ? "" : c.comment) + "'";
            if (lines.length() > 0) {
                lines.append(",\n");
            }
            lines.append(line);
        }
        if (!pk.isEmpty()) {
            StringBuilder cols = new StringBuilder();
            for (String f : pk) {
                if (cols.length() > 0) {
                    cols.append(", ");
                }
                cols.append('`').append(f).append('`');
            }
            if (lines.length() > 0) {
                lines.append(",\n");
            }
            lines.append("  PRIMARY KEY (").append(cols).append(")");
        }
        String ddl = "CREATE TABLE `" + tableName + "` (\n" + lines + "\n) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 "
                + "COLLATE=utf8mb4_general_ci COMMENT='" + esc(tableComment == null
                                                            ? "" : tableComment) + "'";
        return dropIfExists ? "DROP TABLE IF EXISTS `" + tableName + "`;\n" + ddl : ddl;
    }

    public static String buildInsertSql(String tableName, List<Infer.Column> columns) {
        StringBuilder fields = new StringBuilder();
        for (Infer.Column c : columns) {
            if (fields.length() > 0) {
                fields.append(", ");
            }
            fields.append('`').append(c.field).append('`');
        }
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                ph.append(", ");
            }
            ph.append("?");
        }
        return "INSERT INTO `" + tableName + "` (" + fields + ") VALUES (" + ph + ")";
    }

    /** 转换结果：value 为要写库的值，error 非空表示这一格转失败（上层收集成告警）。 */
    public static final class Converted {
        public final Object value;
        public final String error;

        public Converted(Object value, String error) {
            this.value = value;
            this.error = error;
        }
    }

    /**
     * 数值列转换失败不再静默置 NULL，由调用方收集告警——丢数据必须让人看见。
     * 无法解析的日期字符串原样交给 MySQL 裁决（严格模式会报错回滚）。
     */
    public static Converted convert(Object v, String typ) {
        if (v == null || (v instanceof Double && Double.isNaN(((Double) v).doubleValue()))) {
            return new Converted(null, null);
        }
        String t = typ == null ? "" : typ.toUpperCase();
        if (v instanceof String) {
            String s = Names.pyStrip((String) v);
            if (s.isEmpty()) {
                return new Converted(null, null);
            }
            if ("DATETIME".equals(t)) {
                LocalDateTime dt = PyStrptime.parseDatetime(s);
                return dt == null ? new Converted(s, null) : new Converted(dt, null);
            }
            if ("DATE".equals(t)) {
                LocalDateTime dt = PyStrptime.parseDate(s);
                return dt == null ? new Converted(s, null) : new Converted(dt.toLocalDate(), null);
            }
            if (t.contains("INT")) {
                try {
                    return new Converted(new BigInteger(s.replace(",", "").replace("，", "")), null);
                } catch (NumberFormatException e) {
                    return new Converted(null, "值 " + repr(s) + " 不是合法整数");
                }
            }
            if (t.startsWith("DECIMAL") || t.startsWith("NUMERIC")
                    || t.startsWith("FLOAT") || t.startsWith("DOUBLE")) {
                try {
                    return new Converted(Double.valueOf(
                            Double.parseDouble(s.replace(",", "").replace("，", ""))), null);
                } catch (NumberFormatException e) {
                    return new Converted(null, "值 " + repr(s) + " 不是合法数字");
                }
            }
            return new Converted(s, null);
        }
        if (v instanceof Boolean) {
            boolean b = ((Boolean) v).booleanValue();
            if (t.contains("INT")) {
                return new Converted(Integer.valueOf(b ? 1 : 0), null);
            }
            if (t.startsWith("DECIMAL") || t.startsWith("NUMERIC")
                    || t.startsWith("FLOAT") || t.startsWith("DOUBLE")) {
                return new Converted(Double.valueOf(b ? 1.0 : 0.0), null);
            }
        }
        return new Converted(v, null);
    }

    /** 对齐 Python 的 %r 对字符串的输出（单引号包裹）。 */
    private static String repr(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
