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

    /** 共 100 条，顺序即对话框里的顺序。 */
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
        new Sample(            "元数据与治理体检",
                            "按库统计表数量与占用（先看库级分布再决定治理顺序）",
                            "SELECT TABLE_SCHEMA AS 库名, COUNT(*) AS 表数量, SUM(TABLE_ROWS) AS 估算总行数," + NL +
                    "       ROUND(SUM(DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 2) AS 占用MB" + NL +
                    "FROM information_schema.TABLES" + NL +
                    "WHERE TABLE_SCHEMA NOT IN ('mysql', 'sys', 'information_schema', 'performance_schema')" + NL +
                    "GROUP BY TABLE_SCHEMA ORDER BY 占用MB DESC;"),
        new Sample(            "元数据与治理体检",
                            "找出长期没变更的表（按最后更新时间排序，NULL 排最前）",
                            "SELECT TABLE_NAME AS 表名, UPDATE_TIME AS 最后更新," + NL +
                    "       DATEDIFF(NOW(), UPDATE_TIME) AS 未变更天数" + NL +
                    "FROM information_schema.TABLES" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'" + NL +
                    "ORDER BY UPDATE_TIME IS NULL DESC, UPDATE_TIME ASC;"),
        new Sample(            "数据质量检查",
                            "空值体检：关键字段的 NULL 与空串一次数清",
                            "SELECT COUNT(*) AS 总行数," + NL +
                    "       SUM(order_no IS NULL OR order_no = '') AS 订单号缺失," + NL +
                    "       SUM(amount IS NULL) AS 金额缺失," + NL +
                    "       SUM(cust_no IS NULL OR cust_no = '') AS 客户号缺失," + NL +
                    "       SUM(created_at IS NULL) AS 时间缺失" + NL +
                    "FROM t_order;"),
        new Sample(            "数据质量检查",
                            "主键唯一性体检（重复主键必须为 0）",
                            "SELECT COUNT(*) AS 总行数, COUNT(DISTINCT id) AS 主键去重数," + NL +
                    "       COUNT(*) - COUNT(DISTINCT id) AS 重复主键数" + NL +
                    "FROM t_order;"),
        new Sample(            "数据质量检查",
                            "业务键重复明细（同名订单的所有行一次列出）",
                            "SELECT order_no AS 订单编号, COUNT(*) AS 条数," + NL +
                    "       GROUP_CONCAT(id ORDER BY id) AS 主键清单" + NL +
                    "FROM t_order GROUP BY order_no HAVING COUNT(*) > 1;"),
        new Sample(            "数据质量检查",
                            "完全重复行体检（所有列都相同的行）",
                            "SELECT order_no, amount, cust_no, created_at, COUNT(*) AS 条数" + NL +
                    "FROM t_order GROUP BY order_no, amount, cust_no, created_at" + NL +
                    "HAVING COUNT(*) > 1;"),
        new Sample(            "数据质量检查",
                            "金额合法性：找出空值、零值与负数",
                            "SELECT id, order_no, amount" + NL +
                    "FROM t_order WHERE amount IS NULL OR amount <= 0;"),
        new Sample(            "数据质量检查",
                            "金额精度体检：小数位超过两位的行",
                            "SELECT id, order_no, amount" + NL +
                    "FROM t_order WHERE amount IS NOT NULL AND ROUND(amount, 2) <> amount;"),
        new Sample(            "数据质量检查",
                            "长度体检：客户号超长或过短",
                            "SELECT id, order_no, cust_no, CHAR_LENGTH(cust_no) AS 长度" + NL +
                    "FROM t_order WHERE cust_no IS NOT NULL" + NL +
                    "  AND CHAR_LENGTH(cust_no) NOT BETWEEN 1 AND 32;"),
        new Sample(            "数据质量检查",
                            "空白字符体检：首尾空格与全空格值",
                            "SELECT id, CONCAT('[', order_no, ']') AS 带括号原值" + NL +
                    "FROM t_order WHERE order_no <> TRIM(order_no) OR TRIM(order_no) = '';"),
        new Sample(            "数据质量检查",
                            "不可见字符体检（制表、换行、零宽空格）",
                            "SELECT id, order_no, HEX(order_no) AS 十六进制" + NL +
                    "FROM t_order" + NL +
                    "WHERE LOCATE(CHAR(9), order_no) + LOCATE(CHAR(10), order_no)" + NL +
                    "    + LOCATE(CHAR(13), order_no) > 0" + NL +
                    "   OR HEX(order_no) LIKE '%E2808B%';"),
        new Sample(            "数据质量检查",
                            "日期合法性：未来时间与超出业务边界的早期时间",
                            "SELECT id, order_no, created_at" + NL +
                    "FROM t_order" + NL +
                    "WHERE created_at IS NOT NULL" + NL +
                    "  AND (created_at > NOW() OR created_at < '2000-01-01');"),
        new Sample(            "数据质量检查",
                            "业务阈值体检（示例：A 类订单金额不超过 1 万，按现场规则改条件）",
                            "SELECT id, order_no, amount" + NL +
                    "FROM t_order WHERE order_no LIKE 'A%' AND amount >= 10000;"),
        new Sample(            "数据质量检查",
                            "参照完整性：订单里的客户号在客户维表找不到",
                            "CREATE TEMPORARY TABLE tmp_customer (cust_no VARCHAR(32) PRIMARY KEY);" + NL +
                    "INSERT INTO tmp_customer VALUES ('C1');" + NL +
                    "SELECT o.id, o.order_no, o.cust_no AS 缺失客户号" + NL +
                    "FROM t_order o LEFT JOIN tmp_customer c ON c.cust_no = o.cust_no" + NL +
                    "WHERE o.cust_no IS NOT NULL AND c.cust_no IS NULL;" + NL +
                    "DROP TEMPORARY TABLE tmp_customer;"),
        new Sample(            "数据质量检查",
                            "客户维度画像：订单数、金额合计与均值",
                            "SELECT cust_no AS 客户号, COUNT(*) AS 订单数," + NL +
                    "       SUM(amount) AS 金额合计, AVG(amount) AS 金额均值" + NL +
                    "FROM t_order GROUP BY cust_no ORDER BY 金额合计 DESC;"),
        new Sample(            "数据质量检查",
                            "金额分布体检：按区间分桶看是否异常集中",
                            "SELECT CASE WHEN amount < 1 THEN '0-1'" + NL +
                    "            WHEN amount < 10 THEN '1-10'" + NL +
                    "            WHEN amount < 100 THEN '10-100'" + NL +
                    "            ELSE '100+' END AS 金额区间," + NL +
                    "       COUNT(*) AS 条数" + NL +
                    "FROM t_order GROUP BY 金额区间 ORDER BY 条数 DESC;"),
        new Sample(            "数据质量检查",
                            "空表体检：找出没有任何数据的业务表（估算行数为 0）",
                            "SELECT TABLE_NAME AS 空表" + NL +
                    "FROM information_schema.TABLES" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'" + NL +
                    "  AND TABLE_ROWS = 0;"),
        new Sample(            "数据质量检查",
                            "列基数体检：每列不同值个数（基数过低要怀疑口径）",
                            "SELECT COUNT(DISTINCT order_no) AS 订单号基数," + NL +
                    "       COUNT(DISTINCT cust_no) AS 客户号基数," + NL +
                    "       COUNT(DISTINCT amount) AS 金额基数," + NL +
                    "       COUNT(DISTINCT DATE(created_at)) AS 日期基数" + NL +
                    "FROM t_order;"),
        new Sample(            "数据质量检查",
                            "唯一键体检：业务唯一键的空值与重复一起看",
                            "SELECT COUNT(*) AS 总行数, SUM(order_no IS NULL) AS 空值数," + NL +
                    "       COUNT(*) - COUNT(DISTINCT order_no) AS 重复数" + NL +
                    "FROM t_order;"),
        new Sample(            "数据质量检查",
                            "单表质量分：空值、业务键重复、金额异常按权重扣分",
                            "SELECT ROUND(100" + NL +
                    "  - 20 * (SUM(order_no IS NULL OR order_no = '') / COUNT(*))" + NL +
                    "  - 20 * ((COUNT(*) - COUNT(DISTINCT order_no)) / COUNT(*))" + NL +
                    "  - 10 * (SUM(amount IS NULL OR amount <= 0) / COUNT(*))" + NL +
                    "  - 10 * (SUM(created_at IS NULL OR created_at > NOW()) / COUNT(*)), 1) AS 质量分" + NL +
                    "FROM t_order;"),
        new Sample(            "数据质量检查",
                            "质量门禁：不达标的表列出来（CTE 先把指标算成一列）",
                            "WITH q AS (" + NL +
                    "  SELECT COUNT(*) AS n," + NL +
                    "         SUM(order_no IS NULL OR order_no = '') AS bad_order_no," + NL +
                    "         COUNT(*) - COUNT(DISTINCT order_no) AS dup_order_no" + NL +
                    "  FROM t_order" + NL +
                    ")" + NL +
                    "SELECT 't_order' AS 表名, n AS 总行数, bad_order_no AS 订单号缺失," + NL +
                    "       dup_order_no AS 业务键重复," + NL +
                    "       CASE WHEN bad_order_no = 0 AND dup_order_no = 0" + NL +
                    "            THEN '通过' ELSE '不通过' END AS 门禁结论" + NL +
                    "FROM q;"),
        new Sample(            "数据质量检查",
                            "把质量规则落成配置表，巡检时按表驱动执行",
                            "CREATE TEMPORARY TABLE tmp_dq_rule (" + NL +
                    "  table_name VARCHAR(64), column_name VARCHAR(64)," + NL +
                    "  rule_name VARCHAR(64), rule_sql VARCHAR(500)" + NL +
                    ");" + NL +
                    "INSERT INTO tmp_dq_rule VALUES" + NL +
                    " ('t_order', 'order_no', '非空且唯一'," + NL +
                    "  'SELECT COUNT(*) FROM t_order WHERE order_no IS NULL" + NL +
                    "   OR CHAR_LENGTH(TRIM(order_no)) = 0')," + NL +
                    " ('t_order', 'amount', '大于零'," + NL +
                    "  'SELECT COUNT(*) FROM t_order WHERE amount IS NULL OR amount <= 0');" + NL +
                    "SELECT rule_name AS 规则, rule_sql AS 校验语句 FROM tmp_dq_rule;" + NL +
                    "DROP TEMPORARY TABLE tmp_dq_rule;"),
        new Sample(            "数据标准化与清洗",
                            "客户号标准化：去空格并转大写，先看会改哪些行",
                            "SELECT id, cust_no AS 原值, UPPER(TRIM(cust_no)) AS 标准值" + NL +
                    "FROM t_order" + NL +
                    "WHERE cust_no IS NOT NULL AND cust_no <> UPPER(TRIM(cust_no));"),
        new Sample(            "数据标准化与清洗",
                            "事务内批量标准化客户号，看结果后回滚（确认无误再去掉 ROLLBACK）",
                            "START TRANSACTION;" + NL +
                    "UPDATE t_order SET cust_no = UPPER(TRIM(cust_no)) WHERE cust_no IS NOT NULL;" + NL +
                    "SELECT id, cust_no FROM t_order ORDER BY id;" + NL +
                    "ROLLBACK;"),
        new Sample(            "数据标准化与清洗",
                            "日期输出标准化：统一成 YYYY-MM-DD",
                            "SELECT id, created_at AS 原值, DATE_FORMAT(created_at, '%Y-%m-%d') AS 标准日期" + NL +
                    "FROM t_order WHERE created_at IS NOT NULL;"),
        new Sample(            "数据标准化与清洗",
                            "空串与纯空格统一成 NULL（用 NULLIF 只读预览）",
                            "SELECT id, cust_no AS 原值, NULLIF(TRIM(cust_no), '') AS 清洗后" + NL +
                    "FROM t_order;"),
        new Sample(            "数据标准化与清洗",
                            "金额统一保留两位小数（先确认舍入规则是否为四舍五入）",
                            "SELECT id, amount AS 原值," + NL +
                    "       CAST(ROUND(amount, 2) AS DECIMAL(18,2)) AS 两位小数" + NL +
                    "FROM t_order WHERE amount IS NOT NULL;"),
        new Sample(            "数据标准化与清洗",
                            "枚举映射：把客户号映射成统一等级码（按现场码表改 WHEN 分支）",
                            "SELECT id, cust_no," + NL +
                    "       CASE WHEN cust_no = 'C1' THEN 'VIP'" + NL +
                    "            WHEN cust_no LIKE 'C%' THEN 'NORMAL'" + NL +
                    "            ELSE 'UNKNOWN' END AS 客户等级" + NL +
                    "FROM t_order;"),
        new Sample(            "数据标准化与清洗",
                            "全角括号转半角（名称类字段清洗的常见一步）",
                            "SELECT id, order_no," + NL +
                    "       REPLACE(REPLACE(order_no, '（', '('), '）', ')') AS 半角化" + NL +
                    "FROM t_order;"),
        new Sample(            "数据标准化与清洗",
                            "脱敏展示：客户号保留前 3 后 4，中间打星",
                            "SELECT id, cust_no," + NL +
                    "       CASE WHEN CHAR_LENGTH(cust_no) >= 7" + NL +
                    "            THEN CONCAT(LEFT(cust_no, 3)," + NL +
                    "                        REPEAT('*', CHAR_LENGTH(cust_no) - 7)," + NL +
                    "                        RIGHT(cust_no, 4))" + NL +
                    "            ELSE '**' END AS 脱敏客户号" + NL +
                    "FROM t_order;"),
        new Sample(            "数据标准化与清洗",
                            "时间口径统一：按天截断做日汇总",
                            "SELECT DATE(created_at) AS 日期, COUNT(*) AS 订单数, SUM(amount) AS 金额" + NL +
                    "FROM t_order GROUP BY DATE(created_at) ORDER BY 日期;"),
        new Sample(            "数据标准化与清洗",
                            "连续空格合并为一个（正则替换，8.0 才有的 REGEXP_REPLACE）",
                            "SELECT id, order_no AS 原值," + NL +
                    "       REGEXP_REPLACE(TRIM(order_no), ' +', ' ') AS 规范化" + NL +
                    "FROM t_order;"),
        new Sample(            "数据标准化与清洗",
                            "空值填充策略：数值补 0、文本补占位符",
                            "SELECT id, COALESCE(amount, 0) AS 金额," + NL +
                    "       COALESCE(cust_no, 'UNKNOWN') AS 客户号" + NL +
                    "FROM t_order;"),
        new Sample(            "数据标准化与清洗",
                            "标准化前后对照：临时表留底，列出真正变化的行",
                            "CREATE TEMPORARY TABLE tmp_before AS SELECT id, cust_no FROM t_order;" + NL +
                    "UPDATE t_order SET cust_no = UPPER(TRIM(cust_no)) WHERE cust_no IS NOT NULL;" + NL +
                    "SELECT b.id, b.cust_no AS 清洗前, t.cust_no AS 清洗后" + NL +
                    "FROM tmp_before b JOIN t_order t ON t.id = b.id" + NL +
                    "WHERE NOT (BINARY b.cust_no <=> BINARY t.cust_no);" + NL +
                    "DROP TEMPORARY TABLE tmp_before;"),
        new Sample(            "主数据与重复数据",
                            "业务主键候选体检：空值与重复一起看",
                            "SELECT order_no, COUNT(*) AS 条数" + NL +
                    "FROM t_order GROUP BY order_no" + NL +
                    "HAVING order_no IS NULL OR COUNT(*) > 1;"),
        new Sample(            "主数据与重复数据",
                            "重复组内排序：按更新时间保留最新一条",
                            "SELECT id, order_no, created_at," + NL +
                    "       ROW_NUMBER() OVER (PARTITION BY order_no" + NL +
                    "                          ORDER BY created_at DESC, id DESC) AS 组内序号" + NL +
                    "FROM t_order;"),
        new Sample(            "主数据与重复数据",
                            "删除前先标记：找出每组里除第一条之外的待删主键",
                            "WITH ranked AS (" + NL +
                    "  SELECT id, order_no," + NL +
                    "         ROW_NUMBER() OVER (PARTITION BY order_no ORDER BY id) AS rn" + NL +
                    "  FROM t_order" + NL +
                    ")" + NL +
                    "SELECT id AS 待删主键, order_no AS 业务键 FROM ranked WHERE rn > 1;"),
        new Sample(            "主数据与重复数据",
                            "大小写变体识别：同一业务键的不同写法",
                            "SELECT LOWER(order_no) AS 规范化订单号," + NL +
                    "       COUNT(DISTINCT BINARY order_no) AS 变体数," + NL +
                    "       GROUP_CONCAT(DISTINCT BINARY order_no) AS 变体" + NL +
                    "FROM t_order GROUP BY LOWER(order_no)" + NL +
                    "HAVING COUNT(DISTINCT BINARY order_no) > 1;"),
        new Sample(            "主数据与重复数据",
                            "主数据缺口：订单客户号在主数据表里未登记",
                            "CREATE TEMPORARY TABLE tmp_master_customer (" + NL +
                    "  cust_no VARCHAR(32) PRIMARY KEY, cust_name VARCHAR(64)" + NL +
                    ");" + NL +
                    "INSERT INTO tmp_master_customer VALUES ('C1', '客户甲'), ('C2', '客户乙');" + NL +
                    "SELECT DISTINCT o.cust_no AS 未登记客户号" + NL +
                    "FROM t_order o" + NL +
                    "LEFT JOIN tmp_master_customer m ON m.cust_no = o.cust_no" + NL +
                    "WHERE o.cust_no IS NOT NULL AND m.cust_no IS NULL;" + NL +
                    "DROP TEMPORARY TABLE tmp_master_customer;"),
        new Sample(            "主数据与重复数据",
                            "新旧编码映射：给现有客户号批量生成新码",
                            "SELECT DISTINCT cust_no AS 旧编码, CONCAT('K_', cust_no) AS 新编码" + NL +
                    "FROM t_order WHERE cust_no IS NOT NULL ORDER BY 旧编码;"),
        new Sample(            "主数据与重复数据",
                            "重复规模盘点：重复组数、涉及行数与可回收行数",
                            "SELECT COUNT(*) AS 重复组数, SUM(条数) AS 涉及行数," + NL +
                    "       SUM(条数 - 1) AS 可回收行数" + NL +
                    "FROM (SELECT order_no, COUNT(*) AS 条数" + NL +
                    "      FROM t_order GROUP BY order_no HAVING COUNT(*) > 1) d;"),
        new Sample(            "主数据与重复数据",
                            "合并后的黄金记录：每组按时间取最新一条",
                            "WITH ranked AS (" + NL +
                    "  SELECT t.*, ROW_NUMBER() OVER (PARTITION BY order_no" + NL +
                    "                                 ORDER BY created_at DESC, id DESC) AS rn" + NL +
                    "  FROM t_order t" + NL +
                    ")" + NL +
                    "SELECT id, order_no, amount, cust_no, created_at" + NL +
                    "FROM ranked WHERE rn = 1;"),
        new Sample(            "数据血缘与影响分析",
                            "外键依赖清单：子表列指向哪张父表",
                            "SELECT TABLE_NAME AS 子表, COLUMN_NAME AS 外键列," + NL +
                    "       REFERENCED_TABLE_NAME AS 父表," + NL +
                    "       REFERENCED_COLUMN_NAME AS 父列" + NL +
                    "FROM information_schema.KEY_COLUMN_USAGE" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL" + NL +
                    "ORDER BY 子表, 外键列;"),
        new Sample(            "数据血缘与影响分析",
                            "视图依赖了哪些表（改表前先看视图定义）",
                            "SELECT TABLE_NAME AS 视图, VIEW_DEFINITION AS 定义" + NL +
                    "FROM information_schema.VIEWS WHERE TABLE_SCHEMA = DATABASE();"),
        new Sample(            "数据血缘与影响分析",
                            "存储过程、函数与触发器清点（改表前看谁在用它）",
                            "SELECT ROUTINE_TYPE AS 类型, ROUTINE_NAME AS 名称" + NL +
                    "FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()" + NL +
                    "UNION ALL" + NL +
                    "SELECT 'TRIGGER', TRIGGER_NAME" + NL +
                    "FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE();"),
        new Sample(            "数据血缘与影响分析",
                            "关键字段影响面：同名列出现在哪些表",
                            "SELECT COLUMN_NAME AS 列名, COUNT(*) AS 表数," + NL +
                    "       GROUP_CONCAT(TABLE_NAME ORDER BY TABLE_NAME) AS 涉及表" + NL +
                    "FROM information_schema.COLUMNS" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME IN ('cust_no', 'order_no')" + NL +
                    "GROUP BY COLUMN_NAME;"),
        new Sample(            "数据血缘与影响分析",
                            "改列前评估：依赖该列的索引与组合顺序",
                            "SELECT TABLE_NAME AS 表名, INDEX_NAME AS 索引名," + NL +
                    "       COLUMN_NAME AS 列名, SEQ_IN_INDEX AS 序号" + NL +
                    "FROM information_schema.STATISTICS" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'order_no'" + NL +
                    "ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;"),
        new Sample(            "数据血缘与影响分析",
                            "字段级血缘登记：上下游表、列与转换规则",
                            "CREATE TEMPORARY TABLE tmp_lineage (" + NL +
                    "  source_table VARCHAR(64), source_column VARCHAR(64)," + NL +
                    "  target_table VARCHAR(64), target_column VARCHAR(64)," + NL +
                    "  transform VARCHAR(200)" + NL +
                    ");" + NL +
                    "INSERT INTO tmp_lineage VALUES" + NL +
                    " ('t_order', 'order_no', 'dwd_order', 'order_no', '直接映射')," + NL +
                    " ('t_order', 'amount', 'dwd_order', 'pay_amount', '元转分')," + NL +
                    " ('t_order', 'created_at', 'dwd_order', 'order_date', '截断到天');" + NL +
                    "SELECT source_table AS 上游表, source_column AS 上游列," + NL +
                    "       target_table AS 下游表, target_column AS 下游列, transform AS 转换" + NL +
                    "FROM tmp_lineage ORDER BY 上游列;" + NL +
                    "DROP TEMPORARY TABLE tmp_lineage;"),
        new Sample(            "数据血缘与影响分析",
                            "用递归 CTE 展开血缘链（ods → dwd → dws）",
                            "WITH RECURSIVE lineage (lvl, node, upstream) AS (" + NL +
                    "  SELECT 0, CAST('ods_order' AS CHAR(32)), CAST(NULL AS CHAR(32))" + NL +
                    "  UNION ALL" + NL +
                    "  SELECT lvl + 1," + NL +
                    "         CAST(CASE lvl WHEN 0 THEN 'dwd_order'" + NL +
                    "                      WHEN 1 THEN 'dws_order_d' END AS CHAR(32))," + NL +
                    "         node" + NL +
                    "  FROM lineage WHERE lvl < 2" + NL +
                    ")" + NL +
                    "SELECT lvl AS 层级, node AS 表, COALESCE(upstream, '（源头）') AS 上游表" + NL +
                    "FROM lineage ORDER BY lvl;"),
        new Sample(            "安全与权限治理",
                            "账号授权总览：每个账号拿了哪些全局权限",
                            "SELECT GRANTEE AS 账号, PRIVILEGE_TYPE AS 权限" + NL +
                    "FROM information_schema.USER_PRIVILEGES" + NL +
                    "ORDER BY GRANTEE, PRIVILEGE_TYPE;"),
        new Sample(            "安全与权限治理",
                            "库级权限清单：谁对哪个库有权限",
                            "SELECT GRANTEE AS 账号, TABLE_SCHEMA AS 库, PRIVILEGE_TYPE AS 权限" + NL +
                    "FROM information_schema.SCHEMA_PRIVILEGES" + NL +
                    "ORDER BY 账号, 库;"),
        new Sample(            "安全与权限治理",
                            "表级授权清单：逐表核对最小权限",
                            "SELECT GRANTEE AS 账号, TABLE_SCHEMA AS 库, TABLE_NAME AS 表," + NL +
                    "       PRIVILEGE_TYPE AS 权限" + NL +
                    "FROM information_schema.TABLE_PRIVILEGES" + NL +
                    "ORDER BY 账号, 库, 表;"),
        new Sample(            "安全与权限治理",
                            "高权限账号盘点：SUPER、GRANT OPTION、FILE 等",
                            "SELECT GRANTEE AS 账号, PRIVILEGE_TYPE AS 权限" + NL +
                    "FROM information_schema.USER_PRIVILEGES" + NL +
                    "WHERE PRIVILEGE_TYPE IN ('SUPER', 'GRANT OPTION', 'SYSTEM_USER', 'FILE')" + NL +
                    "ORDER BY 账号, 权限;"),
        new Sample(            "安全与权限治理",
                            "越权体检：业务库账号不该有 DROP、DELETE、ALTER",
                            "SELECT GRANTEE AS 账号, TABLE_SCHEMA AS 库, PRIVILEGE_TYPE AS 越权项" + NL +
                    "FROM information_schema.SCHEMA_PRIVILEGES" + NL +
                    "WHERE PRIVILEGE_TYPE IN ('DROP', 'DELETE', 'ALTER')" + NL +
                    "ORDER BY 账号, 库;"),
        new Sample(            "安全与权限治理",
                            "当前连接清单：谁连进来、连的哪个库、闲了多久",
                            "SELECT ID AS 连接, USER AS 账号, HOST AS 来源, DB AS 库," + NL +
                    "       COMMAND AS 状态, TIME AS 持续秒" + NL +
                    "FROM information_schema.PROCESSLIST ORDER BY TIME DESC;"),
        new Sample(            "生命周期与归档",
                            "表年龄盘点：创建时间、最后变更与表龄",
                            "SELECT TABLE_NAME AS 表名, CREATE_TIME AS 创建时间," + NL +
                    "       UPDATE_TIME AS 最后变更, DATEDIFF(NOW(), CREATE_TIME) AS 表龄天" + NL +
                    "FROM information_schema.TABLES" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'" + NL +
                    "ORDER BY 表龄天 DESC;"),
        new Sample(            "生命周期与归档",
                            "冷数据识别：180 天前的订单规模与时间边界",
                            "SELECT COUNT(*) AS 冷数据行数, MIN(created_at) AS 最早, MAX(created_at) AS 最晚" + NL +
                    "FROM t_order WHERE created_at < DATE_SUB(NOW(), INTERVAL 180 DAY);"),
        new Sample(            "生命周期与归档",
                            "归档表命名规范检查（_bak/_his/_tmp 必须带日期）",
                            "SELECT TABLE_NAME AS 表名" + NL +
                    "FROM information_schema.TABLES" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'" + NL +
                    "  AND (TABLE_NAME LIKE '%_bak%' OR TABLE_NAME LIKE '%_his%'" + NL +
                    "       OR TABLE_NAME LIKE '%_tmp%')" + NL +
                    "  AND TABLE_NAME NOT REGEXP '_(bak|his|tmp)_[0-9]{8}$';"),
        new Sample(            "生命周期与归档",
                            "归档留底：按一年前的数据建归档表（演示完即删）",
                            "CREATE TABLE IF NOT EXISTS t_order_archive LIKE t_order;" + NL +
                    "INSERT INTO t_order_archive" + NL +
                    "SELECT * FROM t_order WHERE created_at < DATE_SUB(NOW(), INTERVAL 365 DAY);" + NL +
                    "SELECT COUNT(*) AS 已归档行数 FROM t_order_archive;" + NL +
                    "DROP TABLE IF EXISTS t_order_archive;"),
        new Sample(            "生命周期与归档",
                            "分区表体检：哪些表按什么方式分区",
                            "SELECT TABLE_NAME AS 表名, PARTITION_NAME AS 分区," + NL +
                    "       PARTITION_METHOD AS 分区方式, TABLE_ROWS AS 估算行数" + NL +
                    "FROM information_schema.PARTITIONS" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND PARTITION_NAME IS NOT NULL" + NL +
                    "ORDER BY TABLE_NAME, PARTITION_ORDINAL_POSITION;"),
        new Sample(            "生命周期与归档",
                            "按保留期生成处置清单（只查不删，人工确认后再动手）",
                            "SELECT DATE(created_at) AS 日期, COUNT(*) AS 条数," + NL +
                    "       CASE WHEN DATE(created_at) < DATE_SUB(CURDATE(), INTERVAL 365 DAY)" + NL +
                    "            THEN '待归档' ELSE '保留' END AS 处置" + NL +
                    "FROM t_order GROUP BY DATE(created_at) ORDER BY 日期;"),
        new Sample(            "性能与容量治理",
                            "表与索引占用 TOP：找出体积最大的表",
                            "SELECT TABLE_NAME AS 表名, TABLE_ROWS AS 估算行数," + NL +
                    "       ROUND(DATA_LENGTH / 1024 / 1024, 2) AS 数据MB," + NL +
                    "       ROUND(INDEX_LENGTH / 1024 / 1024, 2) AS 索引MB" + NL +
                    "FROM information_schema.TABLES" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE()" + NL +
                    "ORDER BY (DATA_LENGTH + INDEX_LENGTH) DESC LIMIT 20;"),
        new Sample(            "性能与容量治理",
                            "从未被使用的索引（sys 视图直接给清单）",
                            "SELECT object_schema AS 库, object_name AS 表, index_name AS 未使用索引" + NL +
                    "FROM sys.schema_unused_indexes WHERE object_schema = DATABASE();"),
        new Sample(            "性能与容量治理",
                            "全表扫描最多的语句（按扫描行数排）",
                            "SELECT SCHEMA_NAME AS 库, LEFT(DIGEST_TEXT, 80) AS 语句摘要," + NL +
                    "       SUM_SELECT_SCAN AS 全表扫描行数, COUNT_STAR AS 执行次数" + NL +
                    "FROM performance_schema.events_statements_summary_by_digest" + NL +
                    "WHERE SCHEMA_NAME = DATABASE()" + NL +
                    "ORDER BY SUM_SELECT_SCAN DESC LIMIT 10;"),
        new Sample(            "性能与容量治理",
                            "平均延迟最高的语句（毫秒）",
                            "SELECT SCHEMA_NAME AS 库, LEFT(DIGEST_TEXT, 80) AS 语句摘要," + NL +
                    "       COUNT_STAR AS 执行次数," + NL +
                    "       ROUND(AVG_TIMER_WAIT / 1000000000, 2) AS 平均毫秒" + NL +
                    "FROM performance_schema.events_statements_summary_by_digest" + NL +
                    "WHERE SCHEMA_NAME IS NOT NULL" + NL +
                    "ORDER BY AVG_TIMER_WAIT DESC LIMIT 10;"),
        new Sample(            "性能与容量治理",
                            "磁盘临时表大户：排序与分组把结果落到了磁盘",
                            "SELECT SCHEMA_NAME AS 库, LEFT(DIGEST_TEXT, 80) AS 语句摘要," + NL +
                    "       SUM_CREATED_TMP_TABLES AS 内存临时表," + NL +
                    "       SUM_CREATED_TMP_DISK_TABLES AS 磁盘临时表" + NL +
                    "FROM performance_schema.events_statements_summary_by_digest" + NL +
                    "WHERE SUM_CREATED_TMP_DISK_TABLES > 0" + NL +
                    "ORDER BY SUM_CREATED_TMP_DISK_TABLES DESC LIMIT 10;"),
        new Sample(            "性能与容量治理",
                            "连接与线程体检：按状态汇总当前连接",
                            "SELECT COMMAND AS 状态, COUNT(*) AS 连接数" + NL +
                    "FROM information_schema.PROCESSLIST GROUP BY COMMAND" + NL +
                    "ORDER BY 连接数 DESC;"),
        new Sample(            "性能与容量治理",
                            "InnoDB 关键计数器：缓冲池读与增删改行数",
                            "SELECT VARIABLE_NAME AS 指标, VARIABLE_VALUE AS 值" + NL +
                    "FROM performance_schema.global_status" + NL +
                    "WHERE VARIABLE_NAME IN ('Innodb_buffer_pool_read_requests'," + NL +
                    "                        'Innodb_buffer_pool_reads'," + NL +
                    "                        'Innodb_rows_read', 'Innodb_rows_inserted'," + NL +
                    "                        'Innodb_rows_updated', 'Innodb_rows_deleted');"),
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
                            "TRUNCATE TABLE t_order_bak_20261020;"),
        new Sample(            "数据修正",
                            "修正留痕：事务内先快照命中行再更新，确认后回滚",
                            "START TRANSACTION;" + NL +
                    "CREATE TEMPORARY TABLE tmp_fix_bak AS" + NL +
                    "SELECT * FROM t_order WHERE amount IS NULL OR amount <= 0;" + NL +
                    "UPDATE t_order SET amount = 0 WHERE amount IS NULL;" + NL +
                    "SELECT COUNT(*) AS 留底行数 FROM tmp_fix_bak;" + NL +
                    "ROLLBACK;" + NL +
                    "DROP TEMPORARY TABLE tmp_fix_bak;"),
        new Sample(            "数据修正",
                            "修正确认：对比更新前后的行数与金额合计",
                            "CREATE TEMPORARY TABLE tmp_fix_before AS" + NL +
                    "SELECT COUNT(*) AS 行数, SUM(amount) AS 金额合计 FROM t_order;" + NL +
                    "UPDATE t_order SET amount = ROUND(amount, 2) WHERE amount IS NOT NULL;" + NL +
                    "SELECT b.行数 AS 修正前行数, a.行数 AS 修正后行数," + NL +
                    "       b.金额合计 AS 修正前金额, a.金额合计 AS 修正后金额" + NL +
                    "FROM tmp_fix_before b" + NL +
                    "JOIN (SELECT COUNT(*) AS 行数, SUM(amount) AS 金额合计 FROM t_order) a;" + NL +
                    "DROP TEMPORARY TABLE tmp_fix_before;")
    };

    private SqlSamples() {
    }
}
