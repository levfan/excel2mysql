package com.excel2mysql.gui;

/**
 * SQL 执行器页签的示例清单，由 java/tools/export_sql_samples.py
 * 从仓库根目录的 sql_samples.py 导出，不要在 Java 侧手改。
 * 两条图形线共用同一份来源，界面上那条「示例 (?)」按钮打开的就是这张表。
 */
public final class SqlSamples {

    public static final String NL = String.valueOf((char) 10);

    /** 一条示例：所属分组、做什么用、可执行语句。 */
    public static final class Sample {
        public final String group;
        public final String desc;
        public final String sql;

        Sample(String group, String desc, String sql) {
            this.group = group;
            this.desc = desc;
            this.sql = sql;
        }
    }

    /** 共 30 条，顺序即对话框里的顺序。 */
    public static final Sample[] ALL = new Sample[] {
        new Sample(            "建库与选库",
                            "建库（字符集与排序规则一次写全，避免事后改表）",
                            "CREATE DATABASE IF NOT EXISTS ods_data" + NL +
                    "  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"),
        new Sample(            "建库与选库",
                            "切换当前会话使用的库",
                            "USE ods_data;"),
        new Sample(            "建库与选库",
                            "列出所有库（看有没有连错实例）",
                            "SHOW DATABASES;"),
        new Sample(            "建库与选库",
                            "看当前库与服务器关键参数",
                            "SELECT DATABASE() AS 当前库, @@version AS 版本," + NL +
                    "       @@character_set_server AS 服务端字符集," + NL +
                    "       @@collation_server AS 服务端排序规则;"),
        new Sample(            "建表与改表",
                            "建表（InnoDB + utf8mb4 + 表注释与列注释，注释会进数据字典）",
                            "CREATE TABLE IF NOT EXISTS t_order (" + NL +
                    "  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键'," + NL +
                    "  order_no VARCHAR(64) NOT NULL COMMENT '订单编号'," + NL +
                    "  amount DECIMAL(18,2) DEFAULT NULL COMMENT '金额'," + NL +
                    "  created_at DATETIME DEFAULT NULL COMMENT '创建时间'," + NL +
                    "  PRIMARY KEY (id), UNIQUE KEY uk_order_no (order_no)" + NL +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';"),
        new Sample(            "建表与改表",
                            "从查询结果建快照表（治理时留一份改数前的底稿）",
                            "CREATE TABLE t_order_bak_20261020 AS SELECT * FROM t_order;"),
        new Sample(            "建表与改表",
                            "加列",
                            "ALTER TABLE t_order ADD COLUMN remark VARCHAR(255) DEFAULT NULL COMMENT '备注';"),
        new Sample(            "建表与改表",
                            "改列类型（先确认不会截断已有数据）",
                            "ALTER TABLE t_order MODIFY COLUMN amount DECIMAL(20,4) DEFAULT NULL COMMENT '金额';"),
        new Sample(            "建表与改表",
                            "改列名（8.0 用 RENAME COLUMN，不动类型）",
                            "ALTER TABLE t_order RENAME COLUMN remark TO note;"),
        new Sample(            "建表与改表",
                            "加普通索引（按查询条件建，别一上来给每列都建）",
                            "ALTER TABLE t_order ADD INDEX idx_created_at (created_at);"),
        new Sample(            "建表与改表",
                            "建唯一索引（同一列组合不能重复，用于业务主键去重）",
                            "CREATE UNIQUE INDEX uk_order_cust ON t_order (order_no, created_at);"),
        new Sample(            "建表与改表",
                            "删索引（先 SHOW INDEX 确认名字与是否被约束依赖）",
                            "ALTER TABLE t_order DROP INDEX idx_created_at;"),
        new Sample(            "建表与改表",
                            "改表注释",
                            "ALTER TABLE t_order COMMENT='订单主表';"),
        new Sample(            "建表与改表",
                            "重命名表（治理时统一前缀）",
                            "RENAME TABLE t_order TO ods_t_order;"),
        new Sample(            "建表与改表",
                            "删表前先留一手：只在确认不需要时执行",
                            "DROP TABLE IF EXISTS ods_t_order_bak;"),
        new Sample(            "元数据与治理体检",
                            "本库里每张表的行数与占用空间（治理前先盘家底）",
                            "SELECT TABLE_NAME AS 表名, TABLE_ROWS AS 估算行数," + NL +
                    "       ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 2) AS 占用MB," + NL +
                    "       ENGINE AS 引擎, TABLE_COLLATION AS 排序规则, TABLE_COMMENT AS 表注释" + NL +
                    "FROM information_schema.TABLES" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() ORDER BY (DATA_LENGTH + INDEX_LENGTH) DESC;"),
        new Sample(            "元数据与治理体检",
                            "找出没有列注释的列（数据字典缺口清单）",
                            "SELECT TABLE_NAME AS 表名, COLUMN_NAME AS 列名, DATA_TYPE AS 类型" + NL +
                    "FROM information_schema.COLUMNS" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND (COLUMN_COMMENT IS NULL OR COLUMN_COMMENT = '')" + NL +
                    "ORDER BY TABLE_NAME, ORDINAL_POSITION;"),
        new Sample(            "元数据与治理体检",
                            "找出字符集不是 utf8mb4 的列（中文乱码与生僻字隐患）",
                            "SELECT TABLE_NAME AS 表名, COLUMN_NAME AS 列名, CHARACTER_SET_NAME AS 字符集" + NL +
                    "FROM information_schema.COLUMNS" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND CHARACTER_SET_NAME IS NOT NULL" + NL +
                    "  AND CHARACTER_SET_NAME <> 'utf8mb4';"),
        new Sample(            "元数据与治理体检",
                            "找出主键缺失的表（InnoDB 无主键会隐式生成，更新与同步都不好做）",
                            "SELECT t.TABLE_NAME AS 表名" + NL +
                    "FROM information_schema.TABLES t" + NL +
                    "LEFT JOIN (SELECT DISTINCT TABLE_NAME FROM information_schema.TABLE_CONSTRAINTS" + NL +
                    "           WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'PRIMARY KEY') k" + NL +
                    "  ON k.TABLE_NAME = t.TABLE_NAME" + NL +
                    "WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_TYPE = 'BASE TABLE' AND k.TABLE_NAME IS NULL;"),
        new Sample(            "元数据与治理体检",
                            "看某张表的建表语句（对齐字段定义与索引）",
                            "SHOW CREATE TABLE t_order;"),
        new Sample(            "元数据与治理体检",
                            "看列定义与注释",
                            "SHOW FULL COLUMNS FROM t_order;"),
        new Sample(            "元数据与治理体检",
                            "看索引与唯一约束",
                            "SHOW INDEX FROM t_order;"),
        new Sample(            "元数据与治理体检",
                            "跨表找同名同类型列（判断能否统一成维度表）",
                            "SELECT COLUMN_NAME AS 列名, COUNT(*) AS 出现表数," + NL +
                    "       GROUP_CONCAT(TABLE_NAME ORDER BY TABLE_NAME) AS 所在表" + NL +
                    "FROM information_schema.COLUMNS" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() GROUP BY COLUMN_NAME HAVING COUNT(*) > 1" + NL +
                    "ORDER BY COUNT(*) DESC;"),
        new Sample(            "查询与执行计划",
                            "按业务主键查重（先看清有几批重复）",
                            "SELECT order_no, COUNT(*) AS 条数 FROM t_order GROUP BY order_no HAVING COUNT(*) > 1;"),
        new Sample(            "查询与执行计划",
                            "空串与 NULL 混用的统计（治理时最常见的一类脏数据）",
                            "SELECT SUM(CASE WHEN remark IS NULL THEN 1 ELSE 0 END) AS NULL条数," + NL +
                    "       SUM(CASE WHEN remark = '' THEN 1 ELSE 0 END) AS 空串条数" + NL +
                    "FROM t_order;"),
        new Sample(            "查询与执行计划",
                            "看执行计划（type 为 ALL 说明在全表扫）",
                            "EXPLAIN SELECT * FROM t_order WHERE created_at >= '2026-01-01';"),
        new Sample(            "查询与执行计划",
                            "分页取数（大表按主键排序，避免 OFFSET 很大时越翻越慢）",
                            "SELECT id, order_no, amount FROM t_order ORDER BY id LIMIT 200 OFFSET 0;"),
        new Sample(            "数据修正",
                            "把空串统一成 NULL（口径一致，聚合与判空才有意义）",
                            "UPDATE t_order SET remark = NULL WHERE remark = '';"),
        new Sample(            "数据修正",
                            "去掉完全重复的行，只保留 id 最小的一条",
                            "DELETE t1 FROM t_order t1" + NL +
                    "JOIN t_order t2 ON t1.order_no = t2.order_no AND t1.id > t2.id;"),
        new Sample(            "数据修正",
                            "清全表并重置自增（比 DELETE 快，且不进事务回滚）",
                            "TRUNCATE TABLE t_order_bak_20261020;")
    };

    private SqlSamples() {
    }
}
