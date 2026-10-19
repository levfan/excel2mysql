"""SQL 执行器页签的示例语句：单一来源，两条图形线共用。

Java 侧由 java/tools/export_sql_samples.py 把这张表导出成 SqlSamples.java，
和 HelpDoc 同一个套路——两边各写一份必然各说各话。

分组顺序就是界面上「示例」对话框里的分组顺序。语句都按 MySQL 8.0 写，
表名/库名统一用 t_order、ods_ 这类占位，现场改完再执行。
"""

# (分组, 说明, 可执行语句)
SAMPLES = [
    ("建库与选库",
     "建库（字符集与排序规则一次写全，避免事后改表）",
     "CREATE DATABASE IF NOT EXISTS ods_data\n"
     "  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"),
    ("建库与选库", "切换当前会话使用的库", "USE ods_data;"),
    ("建库与选库", "列出所有库（看有没有连错实例）", "SHOW DATABASES;"),
    ("建库与选库", "看当前库与服务器关键参数",
     "SELECT DATABASE() AS 当前库, @@version AS 版本,\n"
     "       @@character_set_server AS 服务端字符集,\n"
     "       @@collation_server AS 服务端排序规则;"),

    ("建表与改表",
     "建表（InnoDB + utf8mb4 + 表注释与列注释，注释会进数据字典）",
     "CREATE TABLE IF NOT EXISTS t_order (\n"
     "  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',\n"
     "  order_no VARCHAR(64) NOT NULL COMMENT '订单编号',\n"
     "  amount DECIMAL(18,2) DEFAULT NULL COMMENT '金额',\n"
     "  created_at DATETIME DEFAULT NULL COMMENT '创建时间',\n"
     "  PRIMARY KEY (id), UNIQUE KEY uk_order_no (order_no)\n"
     ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';"),
    ("建表与改表", "从查询结果建快照表（治理时留一份改数前的底稿）",
     "CREATE TABLE t_order_bak_20261020 AS SELECT * FROM t_order;"),
    ("建表与改表", "加列", "ALTER TABLE t_order ADD COLUMN remark VARCHAR(255) DEFAULT NULL COMMENT '备注';"),
    ("建表与改表", "改列类型（先确认不会截断已有数据）",
     "ALTER TABLE t_order MODIFY COLUMN amount DECIMAL(20,4) DEFAULT NULL COMMENT '金额';"),
    ("建表与改表", "改列名（8.0 用 RENAME COLUMN，不动类型）",
     "ALTER TABLE t_order RENAME COLUMN remark TO note;"),
    ("建表与改表", "加普通索引（按查询条件建，别一上来给每列都建）",
     "ALTER TABLE t_order ADD INDEX idx_created_at (created_at);"),
    ("建表与改表", "建唯一索引（同一列组合不能重复，用于业务主键去重）",
     "CREATE UNIQUE INDEX uk_order_cust ON t_order (order_no, created_at);"),
    ("建表与改表", "删索引（先 SHOW INDEX 确认名字与是否被约束依赖）",
     "ALTER TABLE t_order DROP INDEX idx_created_at;"),
    ("建表与改表", "改表注释", "ALTER TABLE t_order COMMENT='订单主表';"),
    ("建表与改表", "重命名表（治理时统一前缀）", "RENAME TABLE t_order TO ods_t_order;"),
    ("建表与改表", "删表前先留一手：只在确认不需要时执行", "DROP TABLE IF EXISTS ods_t_order_bak;"),

    ("元数据与治理体检",
     "本库里每张表的行数与占用空间（治理前先盘家底）",
     "SELECT TABLE_NAME AS 表名, TABLE_ROWS AS 估算行数,\n"
     "       ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 2) AS 占用MB,\n"
     "       ENGINE AS 引擎, TABLE_COLLATION AS 排序规则, TABLE_COMMENT AS 表注释\n"
     "FROM information_schema.TABLES\n"
     "WHERE TABLE_SCHEMA = DATABASE() ORDER BY (DATA_LENGTH + INDEX_LENGTH) DESC;"),
    ("元数据与治理体检", "找出没有列注释的列（数据字典缺口清单）",
     "SELECT TABLE_NAME AS 表名, COLUMN_NAME AS 列名, DATA_TYPE AS 类型\n"
     "FROM information_schema.COLUMNS\n"
     "WHERE TABLE_SCHEMA = DATABASE() AND (COLUMN_COMMENT IS NULL OR COLUMN_COMMENT = '')\n"
     "ORDER BY TABLE_NAME, ORDINAL_POSITION;"),
    ("元数据与治理体检", "找出字符集不是 utf8mb4 的列（中文乱码与生僻字隐患）",
     "SELECT TABLE_NAME AS 表名, COLUMN_NAME AS 列名, CHARACTER_SET_NAME AS 字符集\n"
     "FROM information_schema.COLUMNS\n"
     "WHERE TABLE_SCHEMA = DATABASE() AND CHARACTER_SET_NAME IS NOT NULL\n"
     "  AND CHARACTER_SET_NAME <> 'utf8mb4';"),
    ("元数据与治理体检", "找出主键缺失的表（InnoDB 无主键会隐式生成，更新与同步都不好做）",
     "SELECT t.TABLE_NAME AS 表名\n"
     "FROM information_schema.TABLES t\n"
     "LEFT JOIN (SELECT DISTINCT TABLE_NAME FROM information_schema.TABLE_CONSTRAINTS\n"
     "           WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'PRIMARY KEY') k\n"
     "  ON k.TABLE_NAME = t.TABLE_NAME\n"
     "WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_TYPE = 'BASE TABLE' AND k.TABLE_NAME IS NULL;"),
    ("元数据与治理体检", "看某张表的建表语句（对齐字段定义与索引）", "SHOW CREATE TABLE t_order;"),
    ("元数据与治理体检", "看列定义与注释", "SHOW FULL COLUMNS FROM t_order;"),
    ("元数据与治理体检", "看索引与唯一约束", "SHOW INDEX FROM t_order;"),
    ("元数据与治理体检", "跨表找同名同类型列（判断能否统一成维度表）",
     "SELECT COLUMN_NAME AS 列名, COUNT(*) AS 出现表数,\n"
     "       GROUP_CONCAT(TABLE_NAME ORDER BY TABLE_NAME) AS 所在表\n"
     "FROM information_schema.COLUMNS\n"
     "WHERE TABLE_SCHEMA = DATABASE() GROUP BY COLUMN_NAME HAVING COUNT(*) > 1\n"
     "ORDER BY COUNT(*) DESC;"),

    ("查询与执行计划", "按业务主键查重（先看清有几批重复）",
     "SELECT order_no, COUNT(*) AS 条数 FROM t_order GROUP BY order_no HAVING COUNT(*) > 1;"),
    ("查询与执行计划", "空串与 NULL 混用的统计（治理时最常见的一类脏数据）",
     "SELECT SUM(CASE WHEN remark IS NULL THEN 1 ELSE 0 END) AS NULL条数,\n"
     "       SUM(CASE WHEN remark = '' THEN 1 ELSE 0 END) AS 空串条数\n"
     "FROM t_order;"),
    ("查询与执行计划", "看执行计划（type 为 ALL 说明在全表扫）",
     "EXPLAIN SELECT * FROM t_order WHERE created_at >= '2026-01-01';"),
    ("查询与执行计划", "分页取数（大表按主键排序，避免 OFFSET 很大时越翻越慢）",
     "SELECT id, order_no, amount FROM t_order ORDER BY id LIMIT 200 OFFSET 0;"),

    ("数据修正",
     "把空串统一成 NULL（口径一致，聚合与判空才有意义）",
     "UPDATE t_order SET remark = NULL WHERE remark = '';"),
    ("数据修正", "去掉完全重复的行，只保留 id 最小的一条",
     "DELETE t1 FROM t_order t1\n"
     "JOIN t_order t2 ON t1.order_no = t2.order_no AND t1.id > t2.id;"),
    ("数据修正", "清全表并重置自增（比 DELETE 快，且不进事务回滚）", "TRUNCATE TABLE t_order_bak_20261020;"),
]


def groups():
    """按出现顺序返回 [(分组名, [(说明, 语句), ...]), ...]。"""
    out = []
    for g, desc, sql in SAMPLES:
        if not out or out[-1][0] != g:
            out.append((g, []))
        out[-1][1].append((desc, sql))
    return out


if __name__ == "__main__":
    print("示例 %d 条，分 %d 组" % (len(SAMPLES), len(groups())))
    for g, items in groups():
        print("  %s：%d 条" % (g, len(items)))
