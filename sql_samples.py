"""SQL 执行器页签的示例语句：单一来源，两条图形线共用。

Java 侧由 java/tools/export_sql_samples.py 把这张表导出成 SqlSamples.java，
和 HelpDoc 同一个套路——两边各写一份必然各说各话。

共 100 条、12 组，其中数据治理主题（元数据体检、质量检查、标准化清洗、
主数据去重、血缘影响、权限安全、生命周期、性能容量）占七成以上。
分组顺序就是界面上「示例」对话框里的分组顺序。语句都按 MySQL 8.0 写，
表名/库名统一用 t_order、ods_ 这类占位，现场改完再执行。

示例之间默认互不依赖：需要前置结构的几条（改列名、删索引、引用 remark 列等）
由 check_sql_samples.py 里的 PREREQ 声明，自检时先跑前置再跑本句。
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
    ("元数据与治理体检", "按库统计表数量与占用（先看库级分布再决定治理顺序）",
     "SELECT TABLE_SCHEMA AS 库名, COUNT(*) AS 表数量, SUM(TABLE_ROWS) AS 估算总行数,\n"
     "       ROUND(SUM(DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 2) AS 占用MB\n"
     "FROM information_schema.TABLES\n"
     "WHERE TABLE_SCHEMA NOT IN ('mysql', 'sys', 'information_schema', 'performance_schema')\n"
     "GROUP BY TABLE_SCHEMA ORDER BY 占用MB DESC;"),
    ("元数据与治理体检", "找出长期没变更的表（按最后更新时间排序，NULL 排最前）",
     "SELECT TABLE_NAME AS 表名, UPDATE_TIME AS 最后更新,\n"
     "       DATEDIFF(NOW(), UPDATE_TIME) AS 未变更天数\n"
     "FROM information_schema.TABLES\n"
     "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'\n"
     "ORDER BY UPDATE_TIME IS NULL DESC, UPDATE_TIME ASC;"),

    ("数据质量检查",
     "空值体检：关键字段的 NULL 与空串一次数清",
     "SELECT COUNT(*) AS 总行数,\n"
     "       SUM(order_no IS NULL OR order_no = '') AS 订单号缺失,\n"
     "       SUM(amount IS NULL) AS 金额缺失,\n"
     "       SUM(cust_no IS NULL OR cust_no = '') AS 客户号缺失,\n"
     "       SUM(created_at IS NULL) AS 时间缺失\n"
     "FROM t_order;"),
    ("数据质量检查", "主键唯一性体检（重复主键必须为 0）",
     "SELECT COUNT(*) AS 总行数, COUNT(DISTINCT id) AS 主键去重数,\n"
     "       COUNT(*) - COUNT(DISTINCT id) AS 重复主键数\n"
     "FROM t_order;"),
    ("数据质量检查", "业务键重复明细（同名订单的所有行一次列出）",
     "SELECT order_no AS 订单编号, COUNT(*) AS 条数,\n"
     "       GROUP_CONCAT(id ORDER BY id) AS 主键清单\n"
     "FROM t_order GROUP BY order_no HAVING COUNT(*) > 1;"),
    ("数据质量检查", "完全重复行体检（所有列都相同的行）",
     "SELECT order_no, amount, cust_no, created_at, COUNT(*) AS 条数\n"
     "FROM t_order GROUP BY order_no, amount, cust_no, created_at\n"
     "HAVING COUNT(*) > 1;"),
    ("数据质量检查", "金额合法性：找出空值、零值与负数",
     "SELECT id, order_no, amount\n"
     "FROM t_order WHERE amount IS NULL OR amount <= 0;"),
    ("数据质量检查", "金额精度体检：小数位超过两位的行",
     "SELECT id, order_no, amount\n"
     "FROM t_order WHERE amount IS NOT NULL AND ROUND(amount, 2) <> amount;"),
    ("数据质量检查", "长度体检：客户号超长或过短",
     "SELECT id, order_no, cust_no, CHAR_LENGTH(cust_no) AS 长度\n"
     "FROM t_order WHERE cust_no IS NOT NULL\n"
     "  AND CHAR_LENGTH(cust_no) NOT BETWEEN 1 AND 32;"),
    ("数据质量检查", "空白字符体检：首尾空格与全空格值",
     "SELECT id, CONCAT('[', order_no, ']') AS 带括号原值\n"
     "FROM t_order WHERE order_no <> TRIM(order_no) OR TRIM(order_no) = '';"),
    ("数据质量检查", "不可见字符体检（制表、换行、零宽空格）",
     "SELECT id, order_no, HEX(order_no) AS 十六进制\n"
     "FROM t_order\n"
     "WHERE LOCATE(CHAR(9), order_no) + LOCATE(CHAR(10), order_no)\n"
     "    + LOCATE(CHAR(13), order_no) > 0\n"
     "   OR HEX(order_no) LIKE '%E2808B%';"),
    ("数据质量检查", "日期合法性：未来时间与超出业务边界的早期时间",
     "SELECT id, order_no, created_at\n"
     "FROM t_order\n"
     "WHERE created_at IS NOT NULL\n"
     "  AND (created_at > NOW() OR created_at < '2000-01-01');"),
    ("数据质量检查", "业务阈值体检（示例：A 类订单金额不超过 1 万，按现场规则改条件）",
     "SELECT id, order_no, amount\n"
     "FROM t_order WHERE order_no LIKE 'A%' AND amount >= 10000;"),
    ("数据质量检查", "参照完整性：订单里的客户号在客户维表找不到",
     "CREATE TEMPORARY TABLE tmp_customer (cust_no VARCHAR(32) PRIMARY KEY);\n"
     "INSERT INTO tmp_customer VALUES ('C1');\n"
     "SELECT o.id, o.order_no, o.cust_no AS 缺失客户号\n"
     "FROM t_order o LEFT JOIN tmp_customer c ON c.cust_no = o.cust_no\n"
     "WHERE o.cust_no IS NOT NULL AND c.cust_no IS NULL;\n"
     "DROP TEMPORARY TABLE tmp_customer;"),
    ("数据质量检查", "客户维度画像：订单数、金额合计与均值",
     "SELECT cust_no AS 客户号, COUNT(*) AS 订单数,\n"
     "       SUM(amount) AS 金额合计, AVG(amount) AS 金额均值\n"
     "FROM t_order GROUP BY cust_no ORDER BY 金额合计 DESC;"),
    ("数据质量检查", "金额分布体检：按区间分桶看是否异常集中",
     "SELECT CASE WHEN amount < 1 THEN '0-1'\n"
     "            WHEN amount < 10 THEN '1-10'\n"
     "            WHEN amount < 100 THEN '10-100'\n"
     "            ELSE '100+' END AS 金额区间,\n"
     "       COUNT(*) AS 条数\n"
     "FROM t_order GROUP BY 金额区间 ORDER BY 条数 DESC;"),
    ("数据质量检查", "空表体检：找出没有任何数据的业务表（估算行数为 0）",
     "SELECT TABLE_NAME AS 空表\n"
     "FROM information_schema.TABLES\n"
     "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'\n"
     "  AND TABLE_ROWS = 0;"),
    ("数据质量检查", "列基数体检：每列不同值个数（基数过低要怀疑口径）",
     "SELECT COUNT(DISTINCT order_no) AS 订单号基数,\n"
     "       COUNT(DISTINCT cust_no) AS 客户号基数,\n"
     "       COUNT(DISTINCT amount) AS 金额基数,\n"
     "       COUNT(DISTINCT DATE(created_at)) AS 日期基数\n"
     "FROM t_order;"),
    ("数据质量检查", "唯一键体检：业务唯一键的空值与重复一起看",
     "SELECT COUNT(*) AS 总行数, SUM(order_no IS NULL) AS 空值数,\n"
     "       COUNT(*) - COUNT(DISTINCT order_no) AS 重复数\n"
     "FROM t_order;"),
    ("数据质量检查", "单表质量分：空值、业务键重复、金额异常按权重扣分",
     "SELECT ROUND(100\n"
     "  - 20 * (SUM(order_no IS NULL OR order_no = '') / COUNT(*))\n"
     "  - 20 * ((COUNT(*) - COUNT(DISTINCT order_no)) / COUNT(*))\n"
     "  - 10 * (SUM(amount IS NULL OR amount <= 0) / COUNT(*))\n"
     "  - 10 * (SUM(created_at IS NULL OR created_at > NOW()) / COUNT(*)), 1) AS 质量分\n"
     "FROM t_order;"),
    ("数据质量检查", "质量门禁：不达标的表列出来（CTE 先把指标算成一列）",
     "WITH q AS (\n"
     "  SELECT COUNT(*) AS n,\n"
     "         SUM(order_no IS NULL OR order_no = '') AS bad_order_no,\n"
     "         COUNT(*) - COUNT(DISTINCT order_no) AS dup_order_no\n"
     "  FROM t_order\n"
     ")\n"
     "SELECT 't_order' AS 表名, n AS 总行数, bad_order_no AS 订单号缺失,\n"
     "       dup_order_no AS 业务键重复,\n"
     "       CASE WHEN bad_order_no = 0 AND dup_order_no = 0\n"
     "            THEN '通过' ELSE '不通过' END AS 门禁结论\n"
     "FROM q;"),
    ("数据质量检查", "把质量规则落成配置表，巡检时按表驱动执行",
     "CREATE TEMPORARY TABLE tmp_dq_rule (\n"
     "  table_name VARCHAR(64), column_name VARCHAR(64),\n"
     "  rule_name VARCHAR(64), rule_sql VARCHAR(500)\n"
     ");\n"
     "INSERT INTO tmp_dq_rule VALUES\n"
     " ('t_order', 'order_no', '非空且唯一',\n"
     "  'SELECT COUNT(*) FROM t_order WHERE order_no IS NULL\n"
     "   OR CHAR_LENGTH(TRIM(order_no)) = 0'),\n"
     " ('t_order', 'amount', '大于零',\n"
     "  'SELECT COUNT(*) FROM t_order WHERE amount IS NULL OR amount <= 0');\n"
     "SELECT rule_name AS 规则, rule_sql AS 校验语句 FROM tmp_dq_rule;\n"
     "DROP TEMPORARY TABLE tmp_dq_rule;"),

    ("数据标准化与清洗",
     "客户号标准化：去空格并转大写，先看会改哪些行",
     "SELECT id, cust_no AS 原值, UPPER(TRIM(cust_no)) AS 标准值\n"
     "FROM t_order\n"
     "WHERE cust_no IS NOT NULL AND cust_no <> UPPER(TRIM(cust_no));"),
    ("数据标准化与清洗",
     "事务内批量标准化客户号，看结果后回滚（确认无误再去掉 ROLLBACK）",
     "START TRANSACTION;\n"
     "UPDATE t_order SET cust_no = UPPER(TRIM(cust_no)) WHERE cust_no IS NOT NULL;\n"
     "SELECT id, cust_no FROM t_order ORDER BY id;\n"
     "ROLLBACK;"),
    ("数据标准化与清洗", "日期输出标准化：统一成 YYYY-MM-DD",
     "SELECT id, created_at AS 原值, DATE_FORMAT(created_at, '%Y-%m-%d') AS 标准日期\n"
     "FROM t_order WHERE created_at IS NOT NULL;"),
    ("数据标准化与清洗", "空串与纯空格统一成 NULL（用 NULLIF 只读预览）",
     "SELECT id, cust_no AS 原值, NULLIF(TRIM(cust_no), '') AS 清洗后\n"
     "FROM t_order;"),
    ("数据标准化与清洗", "金额统一保留两位小数（先确认舍入规则是否为四舍五入）",
     "SELECT id, amount AS 原值,\n"
     "       CAST(ROUND(amount, 2) AS DECIMAL(18,2)) AS 两位小数\n"
     "FROM t_order WHERE amount IS NOT NULL;"),
    ("数据标准化与清洗", "枚举映射：把客户号映射成统一等级码（按现场码表改 WHEN 分支）",
     "SELECT id, cust_no,\n"
     "       CASE WHEN cust_no = 'C1' THEN 'VIP'\n"
     "            WHEN cust_no LIKE 'C%' THEN 'NORMAL'\n"
     "            ELSE 'UNKNOWN' END AS 客户等级\n"
     "FROM t_order;"),
    ("数据标准化与清洗", "全角括号转半角（名称类字段清洗的常见一步）",
     "SELECT id, order_no,\n"
     "       REPLACE(REPLACE(order_no, '（', '('), '）', ')') AS 半角化\n"
     "FROM t_order;"),
    ("数据标准化与清洗", "脱敏展示：客户号保留前 3 后 4，中间打星",
     "SELECT id, cust_no,\n"
     "       CASE WHEN CHAR_LENGTH(cust_no) >= 7\n"
     "            THEN CONCAT(LEFT(cust_no, 3),\n"
     "                        REPEAT('*', CHAR_LENGTH(cust_no) - 7),\n"
     "                        RIGHT(cust_no, 4))\n"
     "            ELSE '**' END AS 脱敏客户号\n"
     "FROM t_order;"),
    ("数据标准化与清洗", "时间口径统一：按天截断做日汇总",
     "SELECT DATE(created_at) AS 日期, COUNT(*) AS 订单数, SUM(amount) AS 金额\n"
     "FROM t_order GROUP BY DATE(created_at) ORDER BY 日期;"),
    ("数据标准化与清洗", "连续空格合并为一个（正则替换，8.0 才有的 REGEXP_REPLACE）",
     "SELECT id, order_no AS 原值,\n"
     "       REGEXP_REPLACE(TRIM(order_no), ' +', ' ') AS 规范化\n"
     "FROM t_order;"),
    ("数据标准化与清洗", "空值填充策略：数值补 0、文本补占位符",
     "SELECT id, COALESCE(amount, 0) AS 金额,\n"
     "       COALESCE(cust_no, 'UNKNOWN') AS 客户号\n"
     "FROM t_order;"),
    ("数据标准化与清洗", "标准化前后对照：临时表留底，列出真正变化的行",
     "CREATE TEMPORARY TABLE tmp_before AS SELECT id, cust_no FROM t_order;\n"
     "UPDATE t_order SET cust_no = UPPER(TRIM(cust_no)) WHERE cust_no IS NOT NULL;\n"
     "SELECT b.id, b.cust_no AS 清洗前, t.cust_no AS 清洗后\n"
     "FROM tmp_before b JOIN t_order t ON t.id = b.id\n"
     "WHERE NOT (BINARY b.cust_no <=> BINARY t.cust_no);\n"
     "DROP TEMPORARY TABLE tmp_before;"),

    ("主数据与重复数据",
     "业务主键候选体检：空值与重复一起看",
     "SELECT order_no, COUNT(*) AS 条数\n"
     "FROM t_order GROUP BY order_no\n"
     "HAVING order_no IS NULL OR COUNT(*) > 1;"),
    ("主数据与重复数据", "重复组内排序：按更新时间保留最新一条",
     "SELECT id, order_no, created_at,\n"
     "       ROW_NUMBER() OVER (PARTITION BY order_no\n"
     "                          ORDER BY created_at DESC, id DESC) AS 组内序号\n"
     "FROM t_order;"),
    ("主数据与重复数据", "删除前先标记：找出每组里除第一条之外的待删主键",
     "WITH ranked AS (\n"
     "  SELECT id, order_no,\n"
     "         ROW_NUMBER() OVER (PARTITION BY order_no ORDER BY id) AS rn\n"
     "  FROM t_order\n"
     ")\n"
     "SELECT id AS 待删主键, order_no AS 业务键 FROM ranked WHERE rn > 1;"),
    ("主数据与重复数据", "大小写变体识别：同一业务键的不同写法",
     "SELECT LOWER(order_no) AS 规范化订单号,\n"
     "       COUNT(DISTINCT BINARY order_no) AS 变体数,\n"
     "       GROUP_CONCAT(DISTINCT BINARY order_no) AS 变体\n"
     "FROM t_order GROUP BY LOWER(order_no)\n"
     "HAVING COUNT(DISTINCT BINARY order_no) > 1;"),
    ("主数据与重复数据", "主数据缺口：订单客户号在主数据表里未登记",
     "CREATE TEMPORARY TABLE tmp_master_customer (\n"
     "  cust_no VARCHAR(32) PRIMARY KEY, cust_name VARCHAR(64)\n"
     ");\n"
     "INSERT INTO tmp_master_customer VALUES ('C1', '客户甲'), ('C2', '客户乙');\n"
     "SELECT DISTINCT o.cust_no AS 未登记客户号\n"
     "FROM t_order o\n"
     "LEFT JOIN tmp_master_customer m ON m.cust_no = o.cust_no\n"
     "WHERE o.cust_no IS NOT NULL AND m.cust_no IS NULL;\n"
     "DROP TEMPORARY TABLE tmp_master_customer;"),
    ("主数据与重复数据", "新旧编码映射：给现有客户号批量生成新码",
     "SELECT DISTINCT cust_no AS 旧编码, CONCAT('K_', cust_no) AS 新编码\n"
     "FROM t_order WHERE cust_no IS NOT NULL ORDER BY 旧编码;"),
    ("主数据与重复数据", "重复规模盘点：重复组数、涉及行数与可回收行数",
     "SELECT COUNT(*) AS 重复组数, SUM(条数) AS 涉及行数,\n"
     "       SUM(条数 - 1) AS 可回收行数\n"
     "FROM (SELECT order_no, COUNT(*) AS 条数\n"
     "      FROM t_order GROUP BY order_no HAVING COUNT(*) > 1) d;"),
    ("主数据与重复数据", "合并后的黄金记录：每组按时间取最新一条",
     "WITH ranked AS (\n"
     "  SELECT t.*, ROW_NUMBER() OVER (PARTITION BY order_no\n"
     "                                 ORDER BY created_at DESC, id DESC) AS rn\n"
     "  FROM t_order t\n"
     ")\n"
     "SELECT id, order_no, amount, cust_no, created_at\n"
     "FROM ranked WHERE rn = 1;"),

    ("数据血缘与影响分析",
     "外键依赖清单：子表列指向哪张父表",
     "SELECT TABLE_NAME AS 子表, COLUMN_NAME AS 外键列,\n"
     "       REFERENCED_TABLE_NAME AS 父表,\n"
     "       REFERENCED_COLUMN_NAME AS 父列\n"
     "FROM information_schema.KEY_COLUMN_USAGE\n"
     "WHERE TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL\n"
     "ORDER BY 子表, 外键列;"),
    ("数据血缘与影响分析", "视图依赖了哪些表（改表前先看视图定义）",
     "SELECT TABLE_NAME AS 视图, VIEW_DEFINITION AS 定义\n"
     "FROM information_schema.VIEWS WHERE TABLE_SCHEMA = DATABASE();"),
    ("数据血缘与影响分析", "存储过程、函数与触发器清点（改表前看谁在用它）",
     "SELECT ROUTINE_TYPE AS 类型, ROUTINE_NAME AS 名称\n"
     "FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()\n"
     "UNION ALL\n"
     "SELECT 'TRIGGER', TRIGGER_NAME\n"
     "FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE();"),
    ("数据血缘与影响分析", "关键字段影响面：同名列出现在哪些表",
     "SELECT COLUMN_NAME AS 列名, COUNT(*) AS 表数,\n"
     "       GROUP_CONCAT(TABLE_NAME ORDER BY TABLE_NAME) AS 涉及表\n"
     "FROM information_schema.COLUMNS\n"
     "WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME IN ('cust_no', 'order_no')\n"
     "GROUP BY COLUMN_NAME;"),
    ("数据血缘与影响分析", "改列前评估：依赖该列的索引与组合顺序",
     "SELECT TABLE_NAME AS 表名, INDEX_NAME AS 索引名,\n"
     "       COLUMN_NAME AS 列名, SEQ_IN_INDEX AS 序号\n"
     "FROM information_schema.STATISTICS\n"
     "WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'order_no'\n"
     "ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;"),
    ("数据血缘与影响分析", "字段级血缘登记：上下游表、列与转换规则",
     "CREATE TEMPORARY TABLE tmp_lineage (\n"
     "  source_table VARCHAR(64), source_column VARCHAR(64),\n"
     "  target_table VARCHAR(64), target_column VARCHAR(64),\n"
     "  transform VARCHAR(200)\n"
     ");\n"
     "INSERT INTO tmp_lineage VALUES\n"
     " ('t_order', 'order_no', 'dwd_order', 'order_no', '直接映射'),\n"
     " ('t_order', 'amount', 'dwd_order', 'pay_amount', '元转分'),\n"
     " ('t_order', 'created_at', 'dwd_order', 'order_date', '截断到天');\n"
     "SELECT source_table AS 上游表, source_column AS 上游列,\n"
     "       target_table AS 下游表, target_column AS 下游列, transform AS 转换\n"
     "FROM tmp_lineage ORDER BY 上游列;\n"
     "DROP TEMPORARY TABLE tmp_lineage;"),
    ("数据血缘与影响分析", "用递归 CTE 展开血缘链（ods → dwd → dws）",
     "WITH RECURSIVE lineage (lvl, node, upstream) AS (\n"
     "  SELECT 0, CAST('ods_order' AS CHAR(32)), CAST(NULL AS CHAR(32))\n"
     "  UNION ALL\n"
     "  SELECT lvl + 1,\n"
     "         CAST(CASE lvl WHEN 0 THEN 'dwd_order'\n"
     "                      WHEN 1 THEN 'dws_order_d' END AS CHAR(32)),\n"
     "         node\n"
     "  FROM lineage WHERE lvl < 2\n"
     ")\n"
     "SELECT lvl AS 层级, node AS 表, COALESCE(upstream, '（源头）') AS 上游表\n"
     "FROM lineage ORDER BY lvl;"),

    ("安全与权限治理",
     "账号授权总览：每个账号拿了哪些全局权限",
     "SELECT GRANTEE AS 账号, PRIVILEGE_TYPE AS 权限\n"
     "FROM information_schema.USER_PRIVILEGES\n"
     "ORDER BY GRANTEE, PRIVILEGE_TYPE;"),
    ("安全与权限治理", "库级权限清单：谁对哪个库有权限",
     "SELECT GRANTEE AS 账号, TABLE_SCHEMA AS 库, PRIVILEGE_TYPE AS 权限\n"
     "FROM information_schema.SCHEMA_PRIVILEGES\n"
     "ORDER BY 账号, 库;"),
    ("安全与权限治理", "表级授权清单：逐表核对最小权限",
     "SELECT GRANTEE AS 账号, TABLE_SCHEMA AS 库, TABLE_NAME AS 表,\n"
     "       PRIVILEGE_TYPE AS 权限\n"
     "FROM information_schema.TABLE_PRIVILEGES\n"
     "ORDER BY 账号, 库, 表;"),
    ("安全与权限治理", "高权限账号盘点：SUPER、GRANT OPTION、FILE 等",
     "SELECT GRANTEE AS 账号, PRIVILEGE_TYPE AS 权限\n"
     "FROM information_schema.USER_PRIVILEGES\n"
     "WHERE PRIVILEGE_TYPE IN ('SUPER', 'GRANT OPTION', 'SYSTEM_USER', 'FILE')\n"
     "ORDER BY 账号, 权限;"),
    ("安全与权限治理", "越权体检：业务库账号不该有 DROP、DELETE、ALTER",
     "SELECT GRANTEE AS 账号, TABLE_SCHEMA AS 库, PRIVILEGE_TYPE AS 越权项\n"
     "FROM information_schema.SCHEMA_PRIVILEGES\n"
     "WHERE PRIVILEGE_TYPE IN ('DROP', 'DELETE', 'ALTER')\n"
     "ORDER BY 账号, 库;"),
    ("安全与权限治理", "当前连接清单：谁连进来、连的哪个库、闲了多久",
     "SELECT ID AS 连接, USER AS 账号, HOST AS 来源, DB AS 库,\n"
     "       COMMAND AS 状态, TIME AS 持续秒\n"
     "FROM information_schema.PROCESSLIST ORDER BY TIME DESC;"),

    ("生命周期与归档",
     "表年龄盘点：创建时间、最后变更与表龄",
     "SELECT TABLE_NAME AS 表名, CREATE_TIME AS 创建时间,\n"
     "       UPDATE_TIME AS 最后变更, DATEDIFF(NOW(), CREATE_TIME) AS 表龄天\n"
     "FROM information_schema.TABLES\n"
     "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'\n"
     "ORDER BY 表龄天 DESC;"),
    ("生命周期与归档", "冷数据识别：180 天前的订单规模与时间边界",
     "SELECT COUNT(*) AS 冷数据行数, MIN(created_at) AS 最早, MAX(created_at) AS 最晚\n"
     "FROM t_order WHERE created_at < DATE_SUB(NOW(), INTERVAL 180 DAY);"),
    ("生命周期与归档", "归档表命名规范检查（_bak/_his/_tmp 必须带日期）",
     "SELECT TABLE_NAME AS 表名\n"
     "FROM information_schema.TABLES\n"
     "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'\n"
     "  AND (TABLE_NAME LIKE '%_bak%' OR TABLE_NAME LIKE '%_his%'\n"
     "       OR TABLE_NAME LIKE '%_tmp%')\n"
     "  AND TABLE_NAME NOT REGEXP '_(bak|his|tmp)_[0-9]{8}$';"),
    ("生命周期与归档", "归档留底：按一年前的数据建归档表（演示完即删）",
     "CREATE TABLE IF NOT EXISTS t_order_archive LIKE t_order;\n"
     "INSERT INTO t_order_archive\n"
     "SELECT * FROM t_order WHERE created_at < DATE_SUB(NOW(), INTERVAL 365 DAY);\n"
     "SELECT COUNT(*) AS 已归档行数 FROM t_order_archive;\n"
     "DROP TABLE IF EXISTS t_order_archive;"),
    ("生命周期与归档", "分区表体检：哪些表按什么方式分区",
     "SELECT TABLE_NAME AS 表名, PARTITION_NAME AS 分区,\n"
     "       PARTITION_METHOD AS 分区方式, TABLE_ROWS AS 估算行数\n"
     "FROM information_schema.PARTITIONS\n"
     "WHERE TABLE_SCHEMA = DATABASE() AND PARTITION_NAME IS NOT NULL\n"
     "ORDER BY TABLE_NAME, PARTITION_ORDINAL_POSITION;"),
    ("生命周期与归档", "按保留期生成处置清单（只查不删，人工确认后再动手）",
     "SELECT DATE(created_at) AS 日期, COUNT(*) AS 条数,\n"
     "       CASE WHEN DATE(created_at) < DATE_SUB(CURDATE(), INTERVAL 365 DAY)\n"
     "            THEN '待归档' ELSE '保留' END AS 处置\n"
     "FROM t_order GROUP BY DATE(created_at) ORDER BY 日期;"),

    ("性能与容量治理",
     "表与索引占用 TOP：找出体积最大的表",
     "SELECT TABLE_NAME AS 表名, TABLE_ROWS AS 估算行数,\n"
     "       ROUND(DATA_LENGTH / 1024 / 1024, 2) AS 数据MB,\n"
     "       ROUND(INDEX_LENGTH / 1024 / 1024, 2) AS 索引MB\n"
     "FROM information_schema.TABLES\n"
     "WHERE TABLE_SCHEMA = DATABASE()\n"
     "ORDER BY (DATA_LENGTH + INDEX_LENGTH) DESC LIMIT 20;"),
    ("性能与容量治理", "从未被使用的索引（sys 视图直接给清单）",
     "SELECT object_schema AS 库, object_name AS 表, index_name AS 未使用索引\n"
     "FROM sys.schema_unused_indexes WHERE object_schema = DATABASE();"),
    ("性能与容量治理", "全表扫描最多的语句（按扫描行数排）",
     "SELECT SCHEMA_NAME AS 库, LEFT(DIGEST_TEXT, 80) AS 语句摘要,\n"
     "       SUM_SELECT_SCAN AS 全表扫描行数, COUNT_STAR AS 执行次数\n"
     "FROM performance_schema.events_statements_summary_by_digest\n"
     "WHERE SCHEMA_NAME = DATABASE()\n"
     "ORDER BY SUM_SELECT_SCAN DESC LIMIT 10;"),
    ("性能与容量治理", "平均延迟最高的语句（毫秒）",
     "SELECT SCHEMA_NAME AS 库, LEFT(DIGEST_TEXT, 80) AS 语句摘要,\n"
     "       COUNT_STAR AS 执行次数,\n"
     "       ROUND(AVG_TIMER_WAIT / 1000000000, 2) AS 平均毫秒\n"
     "FROM performance_schema.events_statements_summary_by_digest\n"
     "WHERE SCHEMA_NAME IS NOT NULL\n"
     "ORDER BY AVG_TIMER_WAIT DESC LIMIT 10;"),
    ("性能与容量治理", "磁盘临时表大户：排序与分组把结果落到了磁盘",
     "SELECT SCHEMA_NAME AS 库, LEFT(DIGEST_TEXT, 80) AS 语句摘要,\n"
     "       SUM_CREATED_TMP_TABLES AS 内存临时表,\n"
     "       SUM_CREATED_TMP_DISK_TABLES AS 磁盘临时表\n"
     "FROM performance_schema.events_statements_summary_by_digest\n"
     "WHERE SUM_CREATED_TMP_DISK_TABLES > 0\n"
     "ORDER BY SUM_CREATED_TMP_DISK_TABLES DESC LIMIT 10;"),
    ("性能与容量治理", "连接与线程体检：按状态汇总当前连接",
     "SELECT COMMAND AS 状态, COUNT(*) AS 连接数\n"
     "FROM information_schema.PROCESSLIST GROUP BY COMMAND\n"
     "ORDER BY 连接数 DESC;"),
    ("性能与容量治理", "InnoDB 关键计数器：缓冲池读与增删改行数",
     "SELECT VARIABLE_NAME AS 指标, VARIABLE_VALUE AS 值\n"
     "FROM performance_schema.global_status\n"
     "WHERE VARIABLE_NAME IN ('Innodb_buffer_pool_read_requests',\n"
     "                        'Innodb_buffer_pool_reads',\n"
     "                        'Innodb_rows_read', 'Innodb_rows_inserted',\n"
     "                        'Innodb_rows_updated', 'Innodb_rows_deleted');"),

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
    ("数据修正", "修正留痕：事务内先快照命中行再更新，确认后回滚",
     "START TRANSACTION;\n"
     "CREATE TEMPORARY TABLE tmp_fix_bak AS\n"
     "SELECT * FROM t_order WHERE amount IS NULL OR amount <= 0;\n"
     "UPDATE t_order SET amount = 0 WHERE amount IS NULL;\n"
     "SELECT COUNT(*) AS 留底行数 FROM tmp_fix_bak;\n"
     "ROLLBACK;\n"
     "DROP TEMPORARY TABLE tmp_fix_bak;"),
    ("数据修正", "修正确认：对比更新前后的行数与金额合计",
     "CREATE TEMPORARY TABLE tmp_fix_before AS\n"
     "SELECT COUNT(*) AS 行数, SUM(amount) AS 金额合计 FROM t_order;\n"
     "UPDATE t_order SET amount = ROUND(amount, 2) WHERE amount IS NOT NULL;\n"
     "SELECT b.行数 AS 修正前行数, a.行数 AS 修正后行数,\n"
     "       b.金额合计 AS 修正前金额, a.金额合计 AS 修正后金额\n"
     "FROM tmp_fix_before b\n"
     "JOIN (SELECT COUNT(*) AS 行数, SUM(amount) AS 金额合计 FROM t_order) a;\n"
     "DROP TEMPORARY TABLE tmp_fix_before;"),
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
