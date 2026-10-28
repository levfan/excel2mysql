package com.excel2mysql.gui;

/**
 * SQL 执行器页签的示例清单，由 java/tools/export_sql_samples.py
 * 从仓库根目录的 sql_samples.py 导出，不要在 Java 侧手改。
 * 两条图形线共用同一份来源，界面上那条「示例 (?)」按钮打开的就是这张表。
 *
 * 一条示例七个字段：目录用 group + title（短标题，长的那句在 desc 里），
 * 右侧介绍用 desc + scenario + problem，左侧模糊搜索按 keywords 与这几项、
 * 语句本身一起匹配（见 {@link #haystack}）。
 */
public final class SqlSamples {

    public static final String NL = String.valueOf((char) 10);

    /** 一条示例：分组、短标题、说明、场景、解决的问题、关键词、可执行语句。 */
    public static final class Sample {
        public final String group;
        public final String title;
        public final String desc;
        public final String scenario;
        public final String problem;
        public final String keywords;
        public final String sql;

        Sample(String group, String title, String desc, String scenario,
               String problem, String keywords, String sql) {
            this.group = group;
            this.title = title;
            this.desc = desc;
            this.scenario = scenario;
            this.problem = problem;
            this.keywords = keywords;
            this.sql = sql;
        }
    }

    /** 共 126 条，顺序即对话框里目录的顺序。 */
    public static final Sample[] ALL = new Sample[] {
        new Sample("建库与选库",
                "建库", "建库（字符集与排序规则一次写全，避免事后改表）",
                "新环境第一次建 ods 库，或补一个业务域的库。",
                "字符集与排序规则漏写时，事后要为中文乱码回头改表。",
                "建库 字符集 utf8mb4 排序规则 CREATE DATABASE",
                            "CREATE DATABASE IF NOT EXISTS ods_data" + NL +
                    "  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"),
        new Sample("建库与选库",
                "切库", "切换当前会话使用的库",
                "同一连接要在几个库之间来回切，或跑脚本前先落到目标库。",
                "不 USE 就直接建表，表会建到上一次停留的库里。",
                "切库 选库 USE 当前库",
                            "USE ods_data;"),
        new Sample("建库与选库",
                "列出所有库", "列出所有库（看有没有连错实例）",
                "连上之后先确认这台实例是不是自己要找的那一套。",
                "主机名相近、端口记错时把治理做在了空库上。",
                "库清单 SHOW DATABASES 实例 连错",
                            "SHOW DATABASES;"),
        new Sample("建库与选库",
                "看关键参数", "看当前库与服务器关键参数",
                "接手一个陌生库，先摸清版本与字符集底座。",
                "服务端字符集与表不一致时，注释和中文数据会写花。",
                "版本 字符集 排序参数 DATABASE 会话变量",
                            "SELECT DATABASE() AS 当前库, @@version AS 版本," + NL +
                    "       @@character_set_server AS 服务端字符集," + NL +
                    "       @@collation_server AS 服务端排序规则;"),
        new Sample("建表与改表",
                "建表", "建表（InnoDB + utf8mb4 + 表注释与列注释，注释会进数据字典）",
                "按交付口径新建一张业务表，注释要进数据字典。",
                "缺注释的表下游只能靠猜列名，字段口径传不下去。",
                "建表 表注释 列注释 InnoDB utf8mb4 CREATE TABLE",
                            "CREATE TABLE IF NOT EXISTS t_order (" + NL +
                    "  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键'," + NL +
                    "  order_no VARCHAR(64) NOT NULL COMMENT '订单编号'," + NL +
                    "  amount DECIMAL(18,2) DEFAULT NULL COMMENT '金额'," + NL +
                    "  created_at DATETIME DEFAULT NULL COMMENT '创建时间'," + NL +
                    "  PRIMARY KEY (id), UNIQUE KEY uk_order_no (order_no)" + NL +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';"),
        new Sample("建表与改表",
                "治理落地表", "建映射/异常/快照类治理中间表（与业务表同引擎同字符集，注释即登记）",
                "第一轮编码映射开工前，先给映射结果建一张能落地的表。",
                "映射只停在查询里，下一轮从头再跑，也比对不出这一轮改了什么。",
                "落地表 中间表 CREATE TABLE 列注释 match_status 治理",
                            "CREATE TABLE IF NOT EXISTS t_map_order_code (" + NL +
                    "  old_code VARCHAR(64) NOT NULL COMMENT '系统A旧编码'," + NL +
                    "  name VARCHAR(64) DEFAULT NULL COMMENT '源端名称'," + NL +
                    "  new_code VARCHAR(64) DEFAULT NULL COMMENT '系统B新编码'," + NL +
                    "  match_status VARCHAR(16) DEFAULT NULL COMMENT '成功/未匹配/歧义'," + NL +
                    "  conf_level VARCHAR(16) DEFAULT NULL COMMENT '精确/模糊/未匹配'," + NL +
                    "  check_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '治理时间'," + NL +
                    "  PRIMARY KEY (old_code)," + NL +
                    "  INDEX idx_status (match_status)" + NL +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='编码映射落地表';"),
        new Sample("建表与改表",
                "建快照表", "从查询结果建快照表（治理时留一份改数前的底稿）",
                "批量改数之前先留一份改数前的底稿。",
                "改坏了没有对照，说不清哪些行被动过。",
                "快照 底稿 备份 CTAS CREATE TABLE AS SELECT",
                            "CREATE TABLE t_order_bak_20261020 AS SELECT * FROM t_order;"),
        new Sample("建表与改表",
                "加列", "给现有表补一列（不动建表脚本，原地扩字段）",
                "业务多了个属性，要给现有表补一列。",
                "直接改建表脚本会让线上表和脚本对不上，加列才是原地扩。",
                "加列 补字段 ALTER TABLE ADD COLUMN",
                            "ALTER TABLE t_order ADD COLUMN remark VARCHAR(255) DEFAULT NULL COMMENT '备注';"),
        new Sample("建表与改表",
                "改列类型", "改列类型（先确认不会截断已有数据）",
                "金额精度不够、编号长度要放宽。",
                "类型改小会静默截断已有数据，改前先确认取值范围。",
                "改类型 精度 截断 MODIFY COLUMN DECIMAL",
                            "ALTER TABLE t_order MODIFY COLUMN amount DECIMAL(20,4) DEFAULT NULL COMMENT '金额';"),
        new Sample("建表与改表",
                "改列名", "改列名（8.0 用 RENAME COLUMN，不动类型）",
                "字段要按命名规范换名，但类型和位置不动。",
                "用 MODIFY 改名会顺手把类型定义覆盖掉。",
                "改列名 重命名 RENAME COLUMN 8.0",
                            "ALTER TABLE t_order RENAME COLUMN remark TO note;"),
        new Sample("建表与改表",
                "加普通索引", "加普通索引（按查询条件建，别一上来给每列都建）",
                "某个查询条件反复走全表，先给它建一条索引。",
                "没有索引的过滤条件每查一次扫一次全表。",
                "索引 加索引 ADD INDEX 查询条件",
                            "ALTER TABLE t_order ADD INDEX idx_created_at (created_at);"),
        new Sample("建表与改表",
                "建唯一索引", "建唯一索引（同一列组合不能重复，用于业务主键去重）",
                "业务上不允许重复的列组合，要在库侧兜住。",
                "只靠应用层判重，并发写入照样能落进重复行。",
                "唯一索引 业务主键 去重 UNIQUE INDEX",
                            "CREATE UNIQUE INDEX uk_order_cust ON t_order (order_no, created_at);"),
        new Sample("建表与改表",
                "删索引", "删索引（先 SHOW INDEX 确认名字与是否被约束依赖）",
                "索引建错了、或确认长期没人用要回收。",
                "盲删会删掉约束依赖的索引，也可能删错同名索引。",
                "删索引 DROP INDEX SHOW INDEX 约束",
                            "ALTER TABLE t_order DROP INDEX idx_created_at;"),
        new Sample("建表与改表",
                "改表注释", "改表注释（表名留英文缩写，业务含义写进注释）",
                "表名保留英文缩写，业务含义要写进注释。",
                "数据字典里表注释为空，交接时没人知道这张表管什么。",
                "表注释 COMMENT 数据字典 ALTER TABLE",
                            "ALTER TABLE t_order COMMENT='订单主表';"),
        new Sample("建表与改表",
                "重命名表", "重命名表（治理时统一前缀）",
                "分层规范落地，给 ods 层的表统一前缀。",
                "表名不带层信息，下游分不清源表还是加工表。",
                "改表名 重命名 RENAME TABLE 前缀 分层",
                            "RENAME TABLE t_order TO ods_t_order;"),
        new Sample("建表与改表",
                "删表", "删表前先留一手：只在确认不需要时执行",
                "确认备份表不再需要，回收空间。",
                "手滑删错表；IF EXISTS 也避免脚本因为表不在而中断。",
                "删表 DROP TABLE IF EXISTS 回收",
                            "DROP TABLE IF EXISTS ods_t_order_bak;"),
        new Sample("元数据与治理体检",
                "盘家底", "本库里每张表的行数与占用空间（治理前先盘家底）",
                "治理开工第一天，先看清本库有哪些表、各占多大。",
                "不知道从哪张表下手，也容易漏掉体积异常的那几张。",
                "行数 占用空间 information_schema 家底 盘点",
                            "SELECT TABLE_NAME AS 表名, TABLE_ROWS AS 估算行数," + NL +
                    "       ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 2) AS 占用MB," + NL +
                    "       ENGINE AS 引擎, TABLE_COLLATION AS 排序规则, TABLE_COMMENT AS 表注释" + NL +
                    "FROM information_schema.TABLES" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() ORDER BY (DATA_LENGTH + INDEX_LENGTH) DESC;"),
        new Sample("元数据与治理体检",
                "缺列注释清单", "找出没有列注释的列（数据字典缺口清单）",
                "数据字典体检，把所有没写注释的列拉成清单。",
                "字段含义只存在于前任脑子里，交接后没人敢动。",
                "列注释缺失 数据字典 体检 COLUMN_COMMENT",
                            "SELECT TABLE_NAME AS 表名, COLUMN_NAME AS 列名, DATA_TYPE AS 类型" + NL +
                    "FROM information_schema.COLUMNS" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND (COLUMN_COMMENT IS NULL OR COLUMN_COMMENT = '')" + NL +
                    "ORDER BY TABLE_NAME, ORDINAL_POSITION;"),
        new Sample("元数据与治理体检",
                "字符集异常列", "找出字符集不是 utf8mb4 的列（中文乱码与生僻字隐患）",
                "中文乱码、生僻字丢失时先查列字符集。",
                "latin1 或 utf8mb3 的列装不下生僻字与 emoji。",
                "字符集 utf8mb4 乱码 生僻字 CHARACTER_SET_NAME",
                            "SELECT TABLE_NAME AS 表名, COLUMN_NAME AS 列名, CHARACTER_SET_NAME AS 字符集" + NL +
                    "FROM information_schema.COLUMNS" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND CHARACTER_SET_NAME IS NOT NULL" + NL +
                    "  AND CHARACTER_SET_NAME <> 'utf8mb4';"),
        new Sample("元数据与治理体检",
                "缺主键表", "找出主键缺失的表（InnoDB 无主键会隐式生成，更新与同步都不好做）",
                "同步、更新与在线改表都要求表有显式主键。",
                "InnoDB 无主键会隐式生成，主键过长时更新和复制都吃亏。",
                "主键缺失 隐式主键 数据同步 TABLE_CONSTRAINTS",
                            "SELECT t.TABLE_NAME AS 表名" + NL +
                    "FROM information_schema.TABLES t" + NL +
                    "LEFT JOIN (SELECT DISTINCT TABLE_NAME FROM information_schema.TABLE_CONSTRAINTS" + NL +
                    "           WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'PRIMARY KEY') k" + NL +
                    "  ON k.TABLE_NAME = t.TABLE_NAME" + NL +
                    "WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_TYPE = 'BASE TABLE' AND k.TABLE_NAME IS NULL;"),
        new Sample("元数据与治理体检",
                "看建表语句", "看某张表的建表语句（对齐字段定义与索引）",
                "对齐字段定义与索引，或把结构抄给下游。",
                "只看列清单看不到索引与表选项，比对结构会漏项。",
                "建表语句 SHOW CREATE TABLE 结构比对 索引",
                            "SHOW CREATE TABLE t_order;"),
        new Sample("元数据与治理体检",
                "看列定义", "看列定义与注释",
                "核对某一列的类型、注释与权限。",
                "列注释与默认值在普通 DESC 里看不到。",
                "列定义 列注释 SHOW FULL COLUMNS 默认值",
                            "SHOW FULL COLUMNS FROM t_order;"),
        new Sample("元数据与治理体检",
                "看索引", "看索引与唯一约束",
                "改索引、查慢 SQL 之前先看现在有什么。",
                "不知道索引叫什么名字、被哪个约束占用。",
                "索引清单 SHOW INDEX 唯一约束 基数",
                            "SHOW INDEX FROM t_order;"),
        new Sample("元数据与治理体检",
                "同名同类型列", "跨表找同名同类型列（判断能否统一成维度表）",
                "判断能不能把散落的字段收敛成统一维度。",
                "同名列分散在几十张表里，口径是否一致没人说得清。",
                "同名 列 维度 收敛 GROUP_CONCAT 复用",
                            "SELECT COLUMN_NAME AS 列名, COUNT(*) AS 出现表数," + NL +
                    "       GROUP_CONCAT(TABLE_NAME ORDER BY TABLE_NAME) AS 所在表" + NL +
                    "FROM information_schema.COLUMNS" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() GROUP BY COLUMN_NAME HAVING COUNT(*) > 1" + NL +
                    "ORDER BY COUNT(*) DESC;"),
        new Sample("元数据与治理体检",
                "库级分布", "按库统计表数量与占用（先看库级分布再决定治理顺序）",
                "多库环境先按库统计表数与容量。",
                "只看单库会低估治理范围，容量也找不到大头在哪。",
                "库级 表数量 容量 分布 TABLE_SCHEMA",
                            "SELECT TABLE_SCHEMA AS 库名, COUNT(*) AS 表数量, SUM(TABLE_ROWS) AS 估算总行数," + NL +
                    "       ROUND(SUM(DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 2) AS 占用MB" + NL +
                    "FROM information_schema.TABLES" + NL +
                    "WHERE TABLE_SCHEMA NOT IN ('mysql', 'sys', 'information_schema', 'performance_schema')" + NL +
                    "GROUP BY TABLE_SCHEMA ORDER BY 占用MB DESC;"),
        new Sample("元数据与治理体检",
                "长期没变更的表", "找出长期没变更的表（按最后更新时间排序，NULL 排最前）",
                "生命周期盘点，先找出很久没动过的表。",
                "没人认领的历史表一直占空间，也没人敢删。",
                "冷表 最后更新时间 UPDATE_TIME 生命周期 僵尸表",
                            "SELECT TABLE_NAME AS 表名, UPDATE_TIME AS 最后更新," + NL +
                    "       DATEDIFF(NOW(), UPDATE_TIME) AS 未变更天数" + NL +
                    "FROM information_schema.TABLES" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'" + NL +
                    "ORDER BY UPDATE_TIME IS NULL DESC, UPDATE_TIME ASC;"),
        new Sample("元数据与治理体检",
                "字典自审", "字典表自审：一张字典里同名对应多个编码（下游关联会数据膨胀）",
                "字典表交给下游之前先自审一轮：一个名称是不是只对应一枚编码。",
                "字典自身一名多码，下游一关联就整批翻倍。",
                "字典 自审 一名多码 GROUP_CONCAT HAVING 膨胀",
                            "SELECT dict_name AS 字典项名, COUNT(DISTINCT dict_code) AS 编码数," + NL +
                    "       GROUP_CONCAT(DISTINCT dict_code) AS 冲突编码" + NL +
                    "FROM t_dict" + NL +
                    "GROUP BY dict_name HAVING COUNT(DISTINCT dict_code) > 1" + NL +
                    "ORDER BY 编码数 DESC;"),
        new Sample("元数据与治理体检",
                "字典孤码", "孤码检查：字典里有、业务表里完全没人用的编码（可精简字典）",
                "想知道字典里哪些编码其实完全没人用。",
                "孤码一直留在字典里，选值列表越拖越长，还容易被误选。",
                "孤码 未使用 LEFT JOIN IS NULL 精简字典",
                            "SELECT d.dict_code, d.dict_name" + NL +
                    "FROM t_dict d" + NL +
                    "LEFT JOIN t_order o ON TRIM(d.dict_name) = TRIM(o.order_no)" + NL +
                    "WHERE o.order_no IS NULL;"),
        new Sample("数据质量检查",
                "空值体检", "空值体检：关键字段的 NULL 与空串一次数清",
                "关键字段先数一遍 NULL 与空串。",
                "NULL 与空串混着用，聚合与判空的结果两边不一致。",
                "空值 NULL 空串 计数 SUM 判空",
                            "SELECT COUNT(*) AS 总行数," + NL +
                    "       SUM(order_no IS NULL OR order_no = '') AS 订单号缺失," + NL +
                    "       SUM(amount IS NULL) AS 金额缺失," + NL +
                    "       SUM(cust_no IS NULL OR cust_no = '') AS 客户号缺失," + NL +
                    "       SUM(created_at IS NULL) AS 时间缺失" + NL +
                    "FROM t_order;"),
        new Sample("数据质量检查",
                "空值与空串混用", "空串与 NULL 口径混用统计（治理时最常见的一类脏数据）",
                "同一列里 NULL、空串与纯空格三种都没填的情况混着用，先各数一遍。",
                "判空条件写漏一半，统计结果忽大忽小。",
                "NULL 空串 纯空格 CASE WHEN 统计 混用",
                            "SELECT SUM(CASE WHEN remark IS NULL THEN 1 ELSE 0 END) AS 空值条数," + NL +
                    "       SUM(CASE WHEN remark = '' THEN 1 ELSE 0 END) AS 空串条数," + NL +
                    "       SUM(CASE WHEN TRIM(remark) = '' THEN 1 ELSE 0 END) AS 纯空格条数" + NL +
                    "FROM t_order;"),
        new Sample("数据质量检查",
                "主键唯一性", "主键唯一性体检（重复主键必须为 0）",
                "同步或迁移之后核对主键有没有重复。",
                "主键重复会让按主键更新的作业把两行合成一行的假象。",
                "主键 重复 COUNT DISTINCT 唯一性",
                            "SELECT COUNT(*) AS 总行数, COUNT(DISTINCT id) AS 主键去重数," + NL +
                    "       COUNT(*) - COUNT(DISTINCT id) AS 重复主键数" + NL +
                    "FROM t_order;"),
        new Sample("数据质量检查",
                "业务键重复明细", "业务键重复明细（同名订单的所有行一次列出）",
                "订单号这类业务键出现重复时，要看到具体是哪几行。",
                "只报重复条数没法定位，改数时不知道该留哪条。",
                "业务键 重复 明细 GROUP_CONCAT 定位",
                            "SELECT order_no AS 订单编号, COUNT(*) AS 条数," + NL +
                    "       GROUP_CONCAT(id ORDER BY id) AS 主键清单" + NL +
                    "FROM t_order GROUP BY order_no HAVING COUNT(*) > 1;"),
        new Sample("数据质量检查",
                "完全重复行", "完全重复行体检（所有列都相同的行）",
                "整行字段都一样，判断是不是重复导入。",
                "同一批数据被跑了两遍，统计值直接翻倍。",
                "完全重复 重复导入 GROUP BY HAVING",
                            "SELECT order_no, amount, cust_no, created_at, COUNT(*) AS 条数" + NL +
                    "FROM t_order GROUP BY order_no, amount, cust_no, created_at" + NL +
                    "HAVING COUNT(*) > 1;"),
        new Sample("数据质量检查",
                "金额异常", "金额合法性：找出空值、零值与负数",
                "金额字段的空值、零值、负数一次筛出来。",
                "负数与空金额会让合计失真，报表对不上账。",
                "金额 负数 零值 空值 异常校验",
                            "SELECT id, order_no, amount" + NL +
                    "FROM t_order WHERE amount IS NULL OR amount <= 0;"),
        new Sample("数据质量检查",
                "金额精度", "金额精度体检：小数位超过两位的行",
                "小数位超过业务约定精度的行要挑出来。",
                "多余的小数位让分摊与合计出现分位差。",
                "精度 小数位 TRUNC 金额 ROUND",
                            "SELECT id, order_no, amount" + NL +
                    "FROM t_order WHERE amount IS NOT NULL AND ROUND(amount, 2) <> amount;"),
        new Sample("数据质量检查",
                "长度异常", "长度体检：客户号超长或过短",
                "编号类字段过长过短都说明采集口径没守住。",
                "超长值被截断、过短值是残缺录入。",
                "长度 CHAR_LENGTH 超长 过短 编号",
                            "SELECT id, order_no, cust_no, CHAR_LENGTH(cust_no) AS 长度" + NL +
                    "FROM t_order WHERE cust_no IS NOT NULL" + NL +
                    "  AND CHAR_LENGTH(cust_no) NOT BETWEEN 1 AND 32;"),
        new Sample("数据质量检查",
                "首尾空格", "空白字符体检：首尾空格与全空格值",
                "看起来一样却匹配不上的值，先查空格。",
                "首尾空格让等值关联与去重全部失效。",
                "空格 TRIM 不可见 LTRIM RTRIM 匹配不上",
                            "SELECT id, CONCAT('[', order_no, ']') AS 带括号原值" + NL +
                    "FROM t_order WHERE order_no <> TRIM(order_no) OR TRIM(order_no) = '';"),
        new Sample("数据质量检查",
                "不可见字符", "不可见字符体检（制表、换行、零宽空格；8.0 可用 REGEXP_REPLACE 清洗）",
                "从网页、Excel 粘进来的文本常带制表与零宽字符。",
                "肉眼看不出差别，比对却永远不相等。",
                "不可见字符 零宽 制表符 HEX CHAR_LENGTH",
                            "SELECT id, order_no, HEX(order_no) AS 十六进制" + NL +
                    "FROM t_order" + NL +
                    "WHERE LOCATE(CHAR(9), order_no) + LOCATE(CHAR(10), order_no)" + NL +
                    "    + LOCATE(CHAR(13), order_no) > 0" + NL +
                    "   OR HEX(order_no) LIKE '%E2808B%';"),
        new Sample("数据质量检查",
                "日期越界", "日期合法性：未来时间与超出业务边界的早期时间",
                "未来时间与早于业务起始日的记录要单独看。",
                "错填的日期会把按时间的统计窗口整个带偏。",
                "日期 未来时间 越界 边界 created_at",
                            "SELECT id, order_no, created_at" + NL +
                    "FROM t_order" + NL +
                    "WHERE created_at IS NOT NULL" + NL +
                    "  AND (created_at > NOW() OR created_at < '2000-01-01');"),
        new Sample("数据质量检查",
                "业务阈值", "业务阈值体检（示例：A 类订单金额不超过 1 万，按现场规则改条件）",
                "现场有明确规则（如 A 类订单限额）时按规则扫一遍。",
                "规则只写在制度文档里，库里有没有违反没人核。",
                "业务规则 阈值 限额 LIKE 条件",
                            "SELECT id, order_no, amount" + NL +
                    "FROM t_order WHERE order_no LIKE 'A%' AND amount >= 10000;"),
        new Sample("数据质量检查",
                "孤儿外键", "参照完整性：订单里的客户号在客户维表找不到",
                "订单里的客户号在客户维表找不到，就是断链。",
                "关联查询时这些行会整批消失，报表少数却查不出原因。",
                "参照完整性 孤儿 LEFT JOIN IS NULL 外键 维表",
                            "CREATE TEMPORARY TABLE tmp_customer (cust_no VARCHAR(32) PRIMARY KEY);" + NL +
                    "INSERT INTO tmp_customer VALUES ('C1');" + NL +
                    "SELECT o.id, o.order_no, o.cust_no AS 缺失客户号" + NL +
                    "FROM t_order o LEFT JOIN tmp_customer c ON c.cust_no = o.cust_no" + NL +
                    "WHERE o.cust_no IS NOT NULL AND c.cust_no IS NULL;" + NL +
                    "DROP TEMPORARY TABLE tmp_customer;"),
        new Sample("数据质量检查",
                "客户画像", "客户维度画像：订单数、金额合计与均值",
                "按客户汇总订单数与金额，看头部与长尾。",
                "看不出哪些客户贡献集中，也看不出异常大客户。",
                "画像 汇总 分组 GROUP BY 客户 合计",
                            "SELECT cust_no AS 客户号, COUNT(*) AS 订单数," + NL +
                    "       SUM(amount) AS 金额合计, AVG(amount) AS 金额均值" + NL +
                    "FROM t_order GROUP BY cust_no ORDER BY 金额合计 DESC;"),
        new Sample("数据质量检查",
                "金额分桶", "金额分布体检：按区间分桶看是否异常集中",
                "把金额按区间分桶，看分布是否异常集中。",
                "平均值会掩盖极端值，分桶才看得出堆积在哪一档。",
                "分布 分桶 CASE WHEN 区间 直方图",
                            "SELECT CASE WHEN amount < 1 THEN '0-1'" + NL +
                    "            WHEN amount < 10 THEN '1-10'" + NL +
                    "            WHEN amount < 100 THEN '10-100'" + NL +
                    "            ELSE '100+' END AS 金额区间," + NL +
                    "       COUNT(*) AS 条数" + NL +
                    "FROM t_order GROUP BY 金额区间 ORDER BY 条数 DESC;"),
        new Sample("数据质量检查",
                "空表清单", "空表体检：找出没有任何数据的业务表（估算行数为 0）",
                "巡检时先把一张数据都没有的业务表列出来。",
                "空表要么是废弃接口，要么是没跑成功的作业。",
                "空表 TABLE_ROWS 巡检 无数据",
                            "SELECT TABLE_NAME AS 空表" + NL +
                    "FROM information_schema.TABLES" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'" + NL +
                    "  AND TABLE_ROWS = 0;"),
        new Sample("数据质量检查",
                "列基数", "列基数体检：每列不同值个数（基数过低要怀疑口径）",
                "每列有多少不同值，基数过低的列要怀疑口径。",
                "性别列出现一万个不同值，说明这列被挪作他用。",
                "基数 不同值 COUNT DISTINCT 唯一值 口径",
                            "SELECT COUNT(DISTINCT order_no) AS 订单号基数," + NL +
                    "       COUNT(DISTINCT cust_no) AS 客户号基数," + NL +
                    "       COUNT(DISTINCT amount) AS 金额基数," + NL +
                    "       COUNT(DISTINCT DATE(created_at)) AS 日期基数" + NL +
                    "FROM t_order;"),
        new Sample("数据质量检查",
                "唯一键体检", "唯一键体检：业务唯一键的空值与重复一起看",
                "业务唯一键同时看空值与重复。",
                "只查重复会漏掉用空值绕开唯一性的那批行。",
                "唯一键 空值 重复 业务主键",
                            "SELECT COUNT(*) AS 总行数, SUM(order_no IS NULL) AS 空值数," + NL +
                    "       COUNT(*) - COUNT(DISTINCT order_no) AS 重复数" + NL +
                    "FROM t_order;"),
        new Sample("数据质量检查",
                "匹配率", "映射匹配率统计（治理前后汇报用：总数 / 未匹配 / 命中率）",
                "一轮映射跑完，对外要报总数、未匹配数与命中率。",
                "只说跑完了没有数字，效果没法比，也定不下下一轮该治哪些。",
                "匹配率 命中率 未匹配 CASE WHEN ROUND 统计",
                            "SELECT COUNT(*) AS 总数," + NL +
                    "       SUM(CASE WHEN new_code IS NULL THEN 1 ELSE 0 END) AS 未匹配数," + NL +
                    "       ROUND(100 * SUM(CASE WHEN new_code IS NOT NULL THEN 1 ELSE 0 END) / COUNT(*), 2) AS 匹配率" + NL +
                    "FROM t_map_order_code;"),
        new Sample("数据质量检查",
                "单表质量分", "单表质量分：空值、业务键重复、金额异常按权重扣分",
                "给一张表打个分，好在不同表之间横向比。",
                "各条指标零散看着没有整体感，优先级排不出来。",
                "质量分 评分 权重 横向对比 汇总",
                            "SELECT ROUND(100" + NL +
                    "  - 20 * (SUM(order_no IS NULL OR order_no = '') / COUNT(*))" + NL +
                    "  - 20 * ((COUNT(*) - COUNT(DISTINCT order_no)) / COUNT(*))" + NL +
                    "  - 10 * (SUM(amount IS NULL OR amount <= 0) / COUNT(*))" + NL +
                    "  - 10 * (SUM(created_at IS NULL OR created_at > NOW()) / COUNT(*)), 1) AS 质量分" + NL +
                    "FROM t_order;"),
        new Sample("数据质量检查",
                "质量门禁", "质量门禁：不达标的表列出来（CTE 先把指标算成一列）",
                "上线前设一道门禁，不达标的表列出来。",
                "没有门禁时质量检查只是看，看不出该拦谁。",
                "门禁 CTE WITH 阈值 不达标 清单",
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
        new Sample("数据质量检查",
                "规则配置表", "把质量规则落成配置表，巡检时按表驱动执行",
                "把质量规则写进表，巡检按表驱动批量跑。",
                "规则散在几十条 SQL 里，改一条要翻半天。",
                "规则 配置表 巡检 驱动 临时表",
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
        new Sample("数据标准化与清洗",
                "标准化预览", "客户号标准化：去空格并转大写，先看会改哪些行",
                "清洗之前先看会改动哪些行。",
                "直接 UPDATE 改错方向，回滚要连数据一起重灌。",
                "标准化 预览 UPPER TRIM 对照",
                            "SELECT id, cust_no AS 原值, UPPER(TRIM(cust_no)) AS 标准值" + NL +
                    "FROM t_order" + NL +
                    "WHERE cust_no IS NOT NULL AND cust_no <> UPPER(TRIM(cust_no));"),
        new Sample("数据标准化与清洗",
                "名称大小写归一", "名称标准化：去空格 + 大小写归一（提升跨系统关联命中率）",
                "名称类字段先去掉首尾空格、统一小写，看会动到哪些行。",
                "大小写与空格不一致时，等值关联与去重都会把同一个值当成两个。",
                "标准化 LOWER TRIM 大小写 名称 预览",
                            "SELECT id, order_no AS 原值, LOWER(TRIM(order_no)) AS 标准值" + NL +
                    "FROM t_order" + NL +
                    "WHERE order_no IS NOT NULL AND LOWER(TRIM(order_no)) <> LOWER(order_no);"),
        new Sample("数据标准化与清洗",
                "全角转半角", "特殊符号归一：全角括号转半角（名称类字段清洗常见一步）",
                "名称类字段里的全角括号要转成半角，顺带去掉首尾空格。",
                "全半角混写让等值匹配与去重都判成不同值。",
                "全角 半角 REPLACE TRIM 括号 清洗",
                            "SELECT id, order_no," + NL +
                    "       REPLACE(REPLACE(TRIM(order_no), '（', '('), '）', ')') AS 半角化" + NL +
                    "FROM t_order;"),
        new Sample("数据标准化与清洗",
                "事务内清洗", "事务内批量标准化客户号，看结果后回滚（确认无误再去掉 ROLLBACK）",
                "批量改口径时放在事务里，看完结果再决定。",
                "一条 UPDATE 打全表，改坏了没有反悔的机会。",
                "事务 START TRANSACTION 回滚 ROLLBACK 批量更新",
                            "START TRANSACTION;" + NL +
                    "UPDATE t_order SET cust_no = UPPER(TRIM(cust_no)) WHERE cust_no IS NOT NULL;" + NL +
                    "SELECT id, cust_no FROM t_order ORDER BY id;" + NL +
                    "ROLLBACK;"),
        new Sample("数据标准化与清洗",
                "日期格式化", "日期输出标准化：统一成 YYYY-MM-DD",
                "对外交付要把时间统一成 YYYY-MM-DD。",
                "带时分秒的原值让按天对账多出重复行。",
                "日期格式 DATE_FORMAT 标准化 输出",
                            "SELECT id, created_at AS 原值, DATE_FORMAT(created_at, '%Y-%m-%d') AS 标准日期" + NL +
                    "FROM t_order WHERE created_at IS NOT NULL;"),
        new Sample("数据标准化与清洗",
                "空串转NULL", "空串与纯空格统一成 NULL（用 NULLIF 只读预览）",
                "空串与 NULL 混用时，先统一成 NULL。",
                "聚合与判空对两者的处理不同，指标会莫名对不上。",
                "空串 NULL NULLIF TRIM 统一口径",
                            "SELECT id, cust_no AS 原值, NULLIF(TRIM(cust_no), '') AS 清洗后" + NL +
                    "FROM t_order;"),
        new Sample("数据标准化与清洗",
                "金额舍入", "金额统一保留两位小数（先确认舍入规则是否为四舍五入）",
                "金额统一保留两位小数，先确认舍入规则。",
                "多余小数位在分摊与合计里累积成分位差。",
                "舍入 两位小数 ROUND CAST DECIMAL",
                            "SELECT id, amount AS 原值," + NL +
                    "       CAST(ROUND(amount, 2) AS DECIMAL(18,2)) AS 两位小数" + NL +
                    "FROM t_order WHERE amount IS NOT NULL;"),
        new Sample("数据标准化与清洗",
                "枚举映射", "枚举映射：把客户号映射成统一等级码（按现场码表改 WHEN 分支）",
                "把散乱的取值映射成统一等级码。",
                "同一含义写成 VIP、vip、会员三种样，分组统计被拆开。",
                "枚举 映射 CASE WHEN 码表 等级",
                            "SELECT id, cust_no," + NL +
                    "       CASE WHEN cust_no = 'C1' THEN 'VIP'" + NL +
                    "            WHEN cust_no LIKE 'C%' THEN 'NORMAL'" + NL +
                    "            ELSE 'UNKNOWN' END AS 客户等级" + NL +
                    "FROM t_order;"),
        new Sample("数据标准化与清洗",
                "合并空格", "连续空格合并为一个（正则替换，8.0 才有的 REGEXP_REPLACE）",
                "文本里连续多个空格压成一个。",
                "空格数量不一致让同一句话匹配出多个不同值。",
                "正则替换 REGEXP_REPLACE 空格 压缩 8.0",
                            "SELECT id, order_no AS 原值," + NL +
                    "       REGEXP_REPLACE(TRIM(order_no), ' +', ' ') AS 规范化" + NL +
                    "FROM t_order;"),
        new Sample("数据标准化与清洗",
                "不可见字符清洗", "不可见字符清洗：换行/制表符直接剔除（8.0+，低版本放应用层做）",
                "字段里夹着制表符、换行，肉眼看不出来，用正则直接剔掉。",
                "不可见字符让看着一样的两个值匹配不上，导到别处还会串成多行。",
                "不可见字符 制表符 换行 REGEXP_REPLACE 清洗 8.0",
                            "UPDATE t_order" + NL +
                    "SET order_no = REGEXP_REPLACE(order_no, '[\\t\\n\\r]', '')" + NL +
                    "WHERE order_no REGEXP '[\\t\\n\\r]';"),
        new Sample("数据标准化与清洗",
                "空值填充", "空值填充策略：数值补 0、文本补占位符",
                "报表口径要求数值补 0、文本补占位符。",
                "NULL 参与计算会让整行结果为空，合计凭空少一截。",
                "空值填充 COALESCE 占位符 默认值",
                            "SELECT id, COALESCE(amount, 0) AS 金额," + NL +
                    "       COALESCE(cust_no, 'UNKNOWN') AS 客户号" + NL +
                    "FROM t_order;"),
        new Sample("数据标准化与清洗",
                "脱敏展示", "脱敏展示：客户号保留前 3 后 4，中间打星",
                "对外展示客户号，中间几位打星。",
                "整串明文出现在截图与导出文件里，泄露无从追溯。",
                "脱敏 掩码 CONCAT LEFT RIGHT 隐私",
                            "SELECT id, cust_no," + NL +
                    "       CASE WHEN CHAR_LENGTH(cust_no) >= 7" + NL +
                    "            THEN CONCAT(LEFT(cust_no, 3)," + NL +
                    "                        REPEAT('*', CHAR_LENGTH(cust_no) - 7)," + NL +
                    "                        RIGHT(cust_no, 4))" + NL +
                    "            ELSE '**' END AS 脱敏客户号" + NL +
                    "FROM t_order;"),
        new Sample("数据标准化与清洗",
                "按天截断", "时间口径统一：按天截断做日汇总",
                "明细要汇成日粒度才能对上日报。",
                "带时分秒的明细直接汇总，同一天被拆成多行。",
                "日汇总 DATE 截断 时间口径 分组",
                            "SELECT DATE(created_at) AS 日期, COUNT(*) AS 订单数, SUM(amount) AS 金额" + NL +
                    "FROM t_order GROUP BY DATE(created_at) ORDER BY 日期;"),
        new Sample("数据标准化与清洗",
                "前后对照", "标准化前后对照：临时表留底，列出真正变化的行",
                "清洗完要能列出真正发生变化的行。",
                "改动量说不清，验收只能靠抽样肉眼比。",
                "对照 底稿 临时表 diff 变化行数",
                            "CREATE TEMPORARY TABLE tmp_before AS SELECT id, cust_no FROM t_order;" + NL +
                    "UPDATE t_order SET cust_no = UPPER(TRIM(cust_no)) WHERE cust_no IS NOT NULL;" + NL +
                    "SELECT b.id, b.cust_no AS 清洗前, t.cust_no AS 清洗后" + NL +
                    "FROM tmp_before b JOIN t_order t ON t.id = b.id" + NL +
                    "WHERE NOT (BINARY b.cust_no <=> BINARY t.cust_no);" + NL +
                    "DROP TEMPORARY TABLE tmp_before;"),
        new Sample("主数据与重复数据",
                "主键候选", "业务主键候选体检：空值与重复一起看",
                "从字段里挑业务主键，先看空值与重复。",
                "选错主键会让后续所有去重与同步建在沙上。",
                "业务主键 候选 空值 重复 GROUP BY",
                            "SELECT order_no, COUNT(*) AS 条数" + NL +
                    "FROM t_order GROUP BY order_no" + NL +
                    "HAVING order_no IS NULL OR COUNT(*) > 1;"),
        new Sample("主数据与重复数据",
                "组内排序", "重复组内排序：按更新时间保留最新一条",
                "重复组内按更新时间排，确定该留哪条。",
                "没有排序规则时，保留哪条全凭偶然。",
                "窗口函数 ROW_NUMBER PARTITION BY 保留最新",
                            "SELECT id, order_no, created_at," + NL +
                    "       ROW_NUMBER() OVER (PARTITION BY order_no" + NL +
                    "                          ORDER BY created_at DESC, id DESC) AS 组内序号" + NL +
                    "FROM t_order;"),
        new Sample("主数据与重复数据",
                "待删标记", "删除前先标记：找出每组里除第一条之外的待删主键",
                "删重复之前先把待删主键标出来核对。",
                "直接 DELETE 删错行，数据找不回来。",
                "标记 待删 WITH ranked 主键 核对",
                            "WITH ranked AS (" + NL +
                    "  SELECT id, order_no," + NL +
                    "         ROW_NUMBER() OVER (PARTITION BY order_no ORDER BY id) AS rn" + NL +
                    "  FROM t_order" + NL +
                    ")" + NL +
                    "SELECT id AS 待删主键, order_no AS 业务键 FROM ranked WHERE rn > 1;"),
        new Sample("主数据与重复数据",
                "大小写变体", "大小写变体识别：同一业务键的不同写法",
                "同一编号写成大小写两种，先归一化再数。",
                "默认排序规则不区分大小写，重复会被判成一条。",
                "大小写 LOWER BINARY 变体 规范化",
                            "SELECT LOWER(order_no) AS 规范化订单号," + NL +
                    "       COUNT(DISTINCT BINARY order_no) AS 变体数," + NL +
                    "       GROUP_CONCAT(DISTINCT BINARY order_no) AS 变体" + NL +
                    "FROM t_order GROUP BY LOWER(order_no)" + NL +
                    "HAVING COUNT(DISTINCT BINARY order_no) > 1;"),
        new Sample("主数据与重复数据",
                "主数据缺口", "主数据缺口：订单客户号在主数据表里未登记",
                "订单客户号在主数据表里没登记的清单。",
                "主数据不全，按客户归口的报表会漏人。",
                "主数据 缺口 未登记 LEFT JOIN 临时表",
                            "CREATE TEMPORARY TABLE tmp_master_customer (" + NL +
                    "  cust_no VARCHAR(32) PRIMARY KEY, cust_name VARCHAR(64)" + NL +
                    ");" + NL +
                    "INSERT INTO tmp_master_customer VALUES ('C1', '客户甲'), ('C2', '客户乙');" + NL +
                    "SELECT DISTINCT o.cust_no AS 未登记客户号" + NL +
                    "FROM t_order o" + NL +
                    "LEFT JOIN tmp_master_customer m ON m.cust_no = o.cust_no" + NL +
                    "WHERE o.cust_no IS NOT NULL AND m.cust_no IS NULL;" + NL +
                    "DROP TEMPORARY TABLE tmp_master_customer;"),
        new Sample("主数据与重复数据",
                "新旧编码映射", "新旧编码映射：给现有客户号批量生成新码",
                "换编码体系时要一份新旧对照。",
                "没有映射表，历史数据与新系统对不上。",
                "编码映射 换码 CONCAT 对照 迁移",
                            "SELECT DISTINCT cust_no AS 旧编码, CONCAT('K_', cust_no) AS 新编码" + NL +
                    "FROM t_order WHERE cust_no IS NOT NULL ORDER BY 旧编码;"),
        new Sample("主数据与重复数据",
                "重复规模", "重复规模盘点：重复组数、涉及行数与可回收行数",
                "先量清重复的规模，再决定治不治、怎么治。",
                "不知道能回收多少行，投入产出算不出来。",
                "规模 重复组数 涉及行数 可回收 盘点",
                            "SELECT COUNT(*) AS 重复组数, SUM(条数) AS 涉及行数," + NL +
                    "       SUM(条数 - 1) AS 可回收行数" + NL +
                    "FROM (SELECT order_no, COUNT(*) AS 条数" + NL +
                    "      FROM t_order GROUP BY order_no HAVING COUNT(*) > 1) d;"),
        new Sample("主数据与重复数据",
                "黄金记录", "合并后的黄金记录：每组按时间取最新一条",
                "每组只留一条最可信的记录，作为主数据出口。",
                "多来源记录字段互相打架，下游不知信哪条。",
                "黄金记录 golden 主数据 ROW_NUMBER 最新",
                            "WITH ranked AS (" + NL +
                    "  SELECT t.*, ROW_NUMBER() OVER (PARTITION BY order_no" + NL +
                    "                                 ORDER BY created_at DESC, id DESC) AS rn" + NL +
                    "  FROM t_order t" + NL +
                    ")" + NL +
                    "SELECT id, order_no, amount, cust_no, created_at" + NL +
                    "FROM ranked WHERE rn = 1;"),
        new Sample("主数据与重复数据",
                "同名歧义", "同名歧义标记：同一名称挂着多个旧编码，需业务人工确认",
                "主数据合并之前，把同一个名称挂着多枚编码的那批挑出来。",
                "名称相同而编码不同，机器判不了该留哪一枚，硬合一定合错。",
                "歧义 同名 COUNT DISTINCT 人工确认 主数据",
                            "SELECT order_no AS 名称, COUNT(DISTINCT id) AS 编码数" + NL +
                    "FROM t_order" + NL +
                    "GROUP BY order_no" + NL +
                    "HAVING COUNT(DISTINCT id) > 1;"),
        new Sample("编码映射与关联",
                "精确匹配", "精确匹配映射：名称一致即关联，LEFT JOIN 保留源端全量",
                "两边名称口径基本一致时，先按名称精确关联一轮。",
                "换成内连接会把没匹配上的行整批吞掉，连少了多少都看不出来。",
                "精确匹配 LEFT JOIN TRIM 名称 关联 全量",
                            "SELECT a.old_code AS 旧编码, a.name AS 名称, b.new_code AS 新编码" + NL +
                    "FROM t_order a" + NL +
                    "LEFT JOIN t_dict b ON TRIM(a.name) = TRIM(b.name);"),
        new Sample("编码映射与关联",
                "模糊兜底", "模糊兜底匹配：字典名包含源端名（加长度门槛防误命中，需人工复核）",
                "精确没命中的，再用字典名包含源端名兜一轮。",
                "模糊命中不可靠，短名称尤其容易误接，要带长度门槛并交人工复核。",
                "模糊匹配 LIKE CONCAT 长度门槛 复核 兜底",
                            "SELECT a.name AS 源端名称, b.name AS 字典名称, b.new_code" + NL +
                    "FROM t_order a" + NL +
                    "LEFT JOIN t_dict b ON b.name LIKE CONCAT('%', TRIM(a.name), '%')" + NL +
                    "  AND CHAR_LENGTH(TRIM(a.name)) > 2;"),
        new Sample("编码映射与关联",
                "联合键关联", "联合键关联：名称 + 分类码双条件收敛，规避同名一对多",
                "不同分类下有同名的，用名称加分类码两个条件一起收敛。",
                "只按名称关联会一对多，一行源端数据配出好几枚编码。",
                "联合键 分类码 一对多 JOIN 收敛",
                            "SELECT a.name, a.cat_code, b.new_code" + NL +
                    "FROM t_order a" + NL +
                    "JOIN t_dict b ON a.cat_code = b.cat_code" + NL +
                    "             AND TRIM(a.name) = TRIM(b.name);"),
        new Sample("编码映射与关联",
                "别名表桥接", "同义词/别名映射：经别名表把术语不一致的两边接起来",
                "两边叫法本来就不一致时，经别名表把术语接起来。",
                "硬靠字面匹配接不上，术语差异会长期留在数据里。",
                "同义词 别名表 术语 桥接 t_synonym",
                            "SELECT a.name AS 源端, b.new_code" + NL +
                    "FROM t_order a" + NL +
                    "JOIN t_synonym s ON TRIM(a.name) = TRIM(s.alias)" + NL +
                    "JOIN t_dict b ON TRIM(s.std_name) = TRIM(b.name);"),
        new Sample("编码映射与关联",
                "父级编码兜底", "树形层级兜底：本级未匹配时向上取父级编码",
                "明细项在本级查不到字典时，向上取父级的编码。",
                "本级没有字典项就直接判未匹配，其实父级编码是能用的。",
                "层级 父级 COALESCE 兜底 树形",
                            "SELECT a.name, COALESCE(b.new_code, p.new_code) AS 最终编码" + NL +
                    "FROM t_order a" + NL +
                    "LEFT JOIN t_dict b ON TRIM(a.name) = TRIM(b.name)" + NL +
                    "LEFT JOIN t_dict p ON a.parent_name = p.name;"),
        new Sample("编码映射与关联",
                "前缀规则校验", "编码反解校验：新编码是否符合旧编码前缀规则（回写前检查）",
                "回写之前先看新编码还守不守旧编码的前缀规则。",
                "前缀一变，下游按前缀分流的作业会静默走错分支。",
                "前缀 编码规则 LIKE LEFT 校验 回写前",
                            "SELECT old_code, new_code" + NL +
                    "FROM t_map_order_code" + NL +
                    "WHERE new_code NOT LIKE CONCAT(LEFT(old_code, 2), '%');"),
        new Sample("编码映射与关联",
                "未匹配按频次", "未匹配明细导出：按频次排序，高频未匹配优先推业务确认",
                "把没匹配上的名称按出现次数排下来，先推最高的那批。",
                "按条数看未匹配是一堆长尾，按频次才知道先解决哪几条最划算。",
                "未匹配 频次 GROUP BY 排序 明细",
                            "SELECT a.name, COUNT(*) AS 次数" + NL +
                    "FROM t_order a" + NL +
                    "LEFT JOIN t_dict b ON TRIM(a.name) = TRIM(b.name)" + NL +
                    "WHERE b.new_code IS NULL" + NL +
                    "GROUP BY a.name ORDER BY 次数 DESC;"),
        new Sample("编码映射与关联",
                "置信度分级", "精确 + 模糊双兜底生成置信度（精确/模糊/未匹配三级）",
                "一轮里同时做精确与模糊匹配，给每条结果标上置信度。",
                "匹配结果不分层级，人工复核只能全量重看，排不出优先级。",
                "置信度 精确 模糊 COALESCE CASE WHEN 分级",
                            "SELECT a.old_code, a.name," + NL +
                    "       COALESCE(b1.new_code, b2.new_code) AS new_code," + NL +
                    "       CASE WHEN b1.new_code IS NOT NULL THEN '精确'" + NL +
                    "            WHEN b2.new_code IS NOT NULL THEN '模糊'" + NL +
                    "            ELSE '未匹配' END AS conf_level" + NL +
                    "FROM t_order a" + NL +
                    "LEFT JOIN t_dict b1 ON TRIM(a.name) = TRIM(b1.name)" + NL +
                    "LEFT JOIN t_dict b2 ON b1.new_code IS NULL" + NL +
                    "  AND b2.name LIKE CONCAT('%', TRIM(a.name), '%');"),
        new Sample("编码映射与关联",
                "映射去重落地", "映射去重落地：每枚旧编码只留一条，精确优先于模糊",
                "一枚旧编码命中多条映射时，落地只留置信度最高的那条。",
                "落地表里一枚旧码带出多枚新码，下游关联当场行数翻倍。",
                "去重 ROW_NUMBER PARTITION BY FIELD 窗口函数 8.0",
                            "INSERT INTO t_map_order_code_clean (old_code, name, new_code, conf_level)" + NL +
                    "SELECT old_code, name, new_code, conf_level FROM (" + NL +
                    "  SELECT *, ROW_NUMBER() OVER (" + NL +
                    "    PARTITION BY old_code ORDER BY FIELD(conf_level, '精确', '模糊', '未匹配')) AS rn" + NL +
                    "  FROM t_map_order_code" + NL +
                    ") t WHERE rn = 1;"),
        new Sample("编码映射与关联",
                "映射宽表", "生成映射宽表（DWD 层，不污染 ODS，带匹配状态可重复跑）",
                "把源端与匹配结果并成一张宽表，落在 DWD 层。",
                "直接改 ODS 会污染贴源层，出了问题也回不到原样。",
                "宽表 CTAS DWD 贴源 match_status 可重复跑",
                            "CREATE TABLE IF NOT EXISTS t_map_order_code AS" + NL +
                    "SELECT a.*, b.new_code," + NL +
                    "       CASE WHEN b.new_code IS NULL THEN '未匹配' ELSE '成功' END AS match_status" + NL +
                    "FROM t_order a" + NL +
                    "LEFT JOIN t_dict b ON TRIM(a.name) = TRIM(b.name);"),
        new Sample("编码映射与关联",
                "历史版本追溯", "字典版本快照追溯：业务日期落在字典生效区间内，历史数据可回放",
                "回放历史单据时，按业务日期落在字典的生效区间里取编码。",
                "字典只留当前版，历史单据会被按新码重算，结果对不上当年。",
                "版本 生效区间 BETWEEN 回放 快照 追溯",
                            "SELECT a.*, b.new_code, b.dict_version" + NL +
                    "FROM t_order a" + NL +
                    "LEFT JOIN t_dict b ON TRIM(a.name) = TRIM(b.name)" + NL +
                    "  AND a.biz_date BETWEEN b.eff_date AND b.exp_date;"),
        new Sample("编码映射与关联",
                "只取生效字典", "有效版本映射：只取生效期内字典，避免拿到历史旧码",
                "只让当前仍在生效期内的字典项参与匹配。",
                "过期项也留在候选里，会把已经停用的编码重新发出去。",
                "生效期 COALESCE 过期 过滤 字典版本",
                            "SELECT a.*, b.new_code, b.dict_version" + NL +
                    "FROM t_order a" + NL +
                    "LEFT JOIN t_dict b ON TRIM(a.name) = TRIM(b.name)" + NL +
                    "  AND a.biz_date BETWEEN b.eff_date AND COALESCE(b.exp_date, '9999-12-31');"),
        new Sample("数据血缘与影响分析",
                "外键依赖", "外键依赖清单：子表列指向哪张父表",
                "改表、下线表之前先看谁引用它。",
                "删掉被引用的父表，子表数据当场断链。",
                "外键 依赖 REFERENCED_TABLE_NAME 子表",
                            "SELECT TABLE_NAME AS 子表, COLUMN_NAME AS 外键列," + NL +
                    "       REFERENCED_TABLE_NAME AS 父表," + NL +
                    "       REFERENCED_COLUMN_NAME AS 父列" + NL +
                    "FROM information_schema.KEY_COLUMN_USAGE" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL" + NL +
                    "ORDER BY 子表, 外键列;"),
        new Sample("数据血缘与影响分析",
                "视图依赖", "视图依赖了哪些表（改表前先看视图定义）",
                "改列名之前先看有哪些视图在用。",
                "视图定义里的列一改就失效，报错还在下一次查询时。",
                "视图 VIEW_DEFINITION 依赖 改列",
                            "SELECT TABLE_NAME AS 视图, VIEW_DEFINITION AS 定义" + NL +
                    "FROM information_schema.VIEWS WHERE TABLE_SCHEMA = DATABASE();"),
        new Sample("数据血缘与影响分析",
                "程序对象清点", "存储过程、函数与触发器清点（改表前看谁在用它）",
                "改表前列出存储过程、函数与触发器。",
                "触发器里写死了旧表名，改完表才在运行时炸。",
                "存储过程 触发器 ROUTINE_TYPE 清点 依赖",
                            "SELECT ROUTINE_TYPE AS 类型, ROUTINE_NAME AS 名称" + NL +
                    "FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()" + NL +
                    "UNION ALL" + NL +
                    "SELECT 'TRIGGER', TRIGGER_NAME" + NL +
                    "FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = DATABASE();"),
        new Sample("数据血缘与影响分析",
                "字段影响面", "关键字段影响面：同名列出现在哪些表",
                "一个关键字段出现在哪些表，先量影响面。",
                "只改一张表，其余同名表口径就与主表分叉。",
                "影响面 同名字段 表数 GROUP_CONCAT 血缘",
                            "SELECT COLUMN_NAME AS 列名, COUNT(*) AS 表数," + NL +
                    "       GROUP_CONCAT(TABLE_NAME ORDER BY TABLE_NAME) AS 涉及表" + NL +
                    "FROM information_schema.COLUMNS" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME IN ('cust_no', 'order_no')" + NL +
                    "GROUP BY COLUMN_NAME;"),
        new Sample("数据血缘与影响分析",
                "索引依赖", "改列前评估：依赖该列的索引与组合顺序",
                "改列类型前看它被哪些索引占用。",
                "被索引依赖的列改类型会失败或悄悄改执行计划。",
                "索引依赖 改列 组合顺序 STATISTICS",
                            "SELECT TABLE_NAME AS 表名, INDEX_NAME AS 索引名," + NL +
                    "       COLUMN_NAME AS 列名, SEQ_IN_INDEX AS 序号" + NL +
                    "FROM information_schema.STATISTICS" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'order_no'" + NL +
                    "ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;"),
        new Sample("数据血缘与影响分析",
                "血缘登记", "字段级血缘登记：上下游表、列与转换规则",
                "把字段级上下游与转换规则登记成表。",
                "血缘只存在口头，改动无法评估波及范围。",
                "血缘 登记 上下游 转换规则 临时表",
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
        new Sample("数据血缘与影响分析",
                "血缘链展开", "用递归 CTE 展开血缘链（ods → dwd → dws）",
                "从一张源表出发，把 ods→dwd→dws 的链展开。",
                "逐层手查依赖，链路一长就漏。",
                "递归 CTE WITH RECURSIVE 链路 分层",
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
        new Sample("安全与权限治理",
                "授权总览", "账号授权总览：每个账号拿了哪些全局权限",
                "安全体检第一天，先看每个账号的全局权限。",
                "权限散在几十个账号里，谁有什么说不清。",
                "权限 授权 全局 SCHEMA_PRIVILEGES 账号",
                            "SELECT GRANTEE AS 账号, PRIVILEGE_TYPE AS 权限" + NL +
                    "FROM information_schema.USER_PRIVILEGES" + NL +
                    "ORDER BY GRANTEE, PRIVILEGE_TYPE;"),
        new Sample("安全与权限治理",
                "库级权限", "库级权限清单：谁对哪个库有权限",
                "看谁对哪个库有权限，按库核最小授权。",
                "整库权限过宽，业务账号能碰到不该碰的库。",
                "库权限 TABLE_SCHEMA 最小权限 授权",
                            "SELECT GRANTEE AS 账号, TABLE_SCHEMA AS 库, PRIVILEGE_TYPE AS 权限" + NL +
                    "FROM information_schema.SCHEMA_PRIVILEGES" + NL +
                    "ORDER BY 账号, 库;"),
        new Sample("安全与权限治理",
                "表级授权", "表级授权清单：逐表核对最小权限",
                "逐表核对业务账号到底需要哪些权限。",
                "表级授权缺失或过宽，审计时拿不出依据。",
                "表级 授权 逐表 核对 PRIVILEGE_TYPE",
                            "SELECT GRANTEE AS 账号, TABLE_SCHEMA AS 库, TABLE_NAME AS 表," + NL +
                    "       PRIVILEGE_TYPE AS 权限" + NL +
                    "FROM information_schema.TABLE_PRIVILEGES" + NL +
                    "ORDER BY 账号, 库, 表;"),
        new Sample("安全与权限治理",
                "高权限账号", "高权限账号盘点：SUPER、GRANT OPTION、FILE 等",
                "SUPER、GRANT OPTION、FILE 这类权限要单独盯。",
                "普通账号被赋予管理能力，越权无人拦。",
                "高权限 SUPER GRANT OPTION FILE 盘点",
                            "SELECT GRANTEE AS 账号, PRIVILEGE_TYPE AS 权限" + NL +
                    "FROM information_schema.USER_PRIVILEGES" + NL +
                    "WHERE PRIVILEGE_TYPE IN ('SUPER', 'GRANT OPTION', 'SYSTEM_USER', 'FILE')" + NL +
                    "ORDER BY 账号, 权限;"),
        new Sample("安全与权限治理",
                "越权体检", "越权体检：业务库账号不该有 DROP、DELETE、ALTER",
                "业务库账号不该有 DROP、DELETE、ALTER。",
                "误删误改的口子一直开着，事故只是没来。",
                "越权 DROP DELETE ALTER 业务账号 检查",
                            "SELECT GRANTEE AS 账号, TABLE_SCHEMA AS 库, PRIVILEGE_TYPE AS 越权项" + NL +
                    "FROM information_schema.SCHEMA_PRIVILEGES" + NL +
                    "WHERE PRIVILEGE_TYPE IN ('DROP', 'DELETE', 'ALTER')" + NL +
                    "ORDER BY 账号, 库;"),
        new Sample("安全与权限治理",
                "当前连接", "当前连接清单：谁连进来、连的哪个库、闲了多久",
                "看谁连进来、在跑什么、闲了多久。",
                "连接被占满时不知道是谁握着不放。",
                "连接 SHOW PROCESSLIST 空闲 来源 会话",
                            "SELECT ID AS 连接, USER AS 账号, HOST AS 来源, DB AS 库," + NL +
                    "       COMMAND AS 状态, TIME AS 持续秒" + NL +
                    "FROM information_schema.PROCESSLIST ORDER BY TIME DESC;"),
        new Sample("生命周期与归档",
                "表年龄", "表年龄盘点：创建时间、最后变更与表龄",
                "按创建与最后变更时间排一遍表龄。",
                "老表一直在线，没人记得它是否还在用。",
                "表龄 CREATE_TIME UPDATE_TIME 生命周期 盘点",
                            "SELECT TABLE_NAME AS 表名, CREATE_TIME AS 创建时间," + NL +
                    "       UPDATE_TIME AS 最后变更, DATEDIFF(NOW(), CREATE_TIME) AS 表龄天" + NL +
                    "FROM information_schema.TABLES" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'" + NL +
                    "ORDER BY 表龄天 DESC;"),
        new Sample("生命周期与归档",
                "冷数据规模", "冷数据识别：180 天前的订单规模与时间边界",
                "先量清 180 天前的数据有多少。",
                "归档方案没有规模依据，冷热边界拍脑袋。",
                "冷数据 180 天 规模 时间边界 DATEDIFF",
                            "SELECT COUNT(*) AS 冷数据行数, MIN(created_at) AS 最早, MAX(created_at) AS 最晚" + NL +
                    "FROM t_order WHERE created_at < DATE_SUB(NOW(), INTERVAL 180 DAY);"),
        new Sample("生命周期与归档",
                "归档命名规范", "归档表命名规范检查（_bak/_his/_tmp 必须带日期）",
                "带 _bak/_his/_tmp 的表必须写清日期。",
                "半年后没人知道哪份备份可以删。",
                "命名规范 归档 REGEXP 备份表 LIKE",
                            "SELECT TABLE_NAME AS 表名" + NL +
                    "FROM information_schema.TABLES" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'" + NL +
                    "  AND (TABLE_NAME LIKE '%_bak%' OR TABLE_NAME LIKE '%_his%'" + NL +
                    "       OR TABLE_NAME LIKE '%_tmp%')" + NL +
                    "  AND TABLE_NAME NOT REGEXP '_(bak|his|tmp)_[0-9]{8}$';"),
        new Sample("生命周期与归档",
                "归档建表", "归档留底：按一年前的数据建归档表（演示完即删）",
                "把一年前的数据搬到归档表，先建结构。",
                "手工写建表语句容易漏列，结构对不上。",
                "归档 CREATE TABLE LIKE 结构一致 留底",
                            "CREATE TABLE IF NOT EXISTS t_order_archive LIKE t_order;" + NL +
                    "INSERT INTO t_order_archive" + NL +
                    "SELECT * FROM t_order WHERE created_at < DATE_SUB(NOW(), INTERVAL 365 DAY);" + NL +
                    "SELECT COUNT(*) AS 已归档行数 FROM t_order_archive;" + NL +
                    "DROP TABLE IF EXISTS t_order_archive;"),
        new Sample("生命周期与归档",
                "分区体检", "分区表体检：哪些表按什么方式分区",
                "看哪些表分了区、按什么方式分。",
                "以为按时间分区了，实际全在一个分区里。",
                "分区 PARTITION_NAME 表体检 范围分区",
                            "SELECT TABLE_NAME AS 表名, PARTITION_NAME AS 分区," + NL +
                    "       PARTITION_METHOD AS 分区方式, TABLE_ROWS AS 估算行数" + NL +
                    "FROM information_schema.PARTITIONS" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE() AND PARTITION_NAME IS NOT NULL" + NL +
                    "ORDER BY TABLE_NAME, PARTITION_ORDINAL_POSITION;"),
        new Sample("生命周期与归档",
                "处置清单", "按保留期生成处置清单（只查不删，人工确认后再动手）",
                "按保留期生成待处置清单，只查不删。",
                "直接按时间 DELETE 容易删掉还要留用的数据。",
                "保留期 处置 清单 CASE WHEN 人工确认 派生表",
                            "SELECT 日期, 条数," + NL +
                    "       CASE WHEN 日期 < DATE_SUB(CURDATE(), INTERVAL 365 DAY)" + NL +
                    "            THEN '待归档' ELSE '保留' END AS 处置" + NL +
                    "FROM (SELECT DATE(created_at) AS 日期, COUNT(*) AS 条数" + NL +
                    "      FROM t_order GROUP BY DATE(created_at)) t" + NL +
                    "ORDER BY 日期;"),
        new Sample("性能与容量治理",
                "占用TOP", "表与索引占用 TOP：找出体积最大的表",
                "找出体积最大的表，先看容量花在哪。",
                "磁盘告警时不知道该找哪张表谈。",
                "容量 占用 DATA_LENGTH INDEX_LENGTH TOP",
                            "SELECT TABLE_NAME AS 表名, TABLE_ROWS AS 估算行数," + NL +
                    "       ROUND(DATA_LENGTH / 1024 / 1024, 2) AS 数据MB," + NL +
                    "       ROUND(INDEX_LENGTH / 1024 / 1024, 2) AS 索引MB" + NL +
                    "FROM information_schema.TABLES" + NL +
                    "WHERE TABLE_SCHEMA = DATABASE()" + NL +
                    "ORDER BY (DATA_LENGTH + INDEX_LENGTH) DESC LIMIT 20;"),
        new Sample("性能与容量治理",
                "未使用索引", "从未被使用的索引（sys 视图直接给清单）",
                "长期没被走过的索引可以回收。",
                "索引只增不减，写入变慢还没人敢删。",
                "未使用索引 sys.schema_unused_indexes 写入开销",
                            "SELECT object_schema AS 库, object_name AS 表, index_name AS 未使用索引" + NL +
                    "FROM sys.schema_unused_indexes WHERE object_schema = DATABASE();"),
        new Sample("性能与容量治理",
                "全表扫描语句", "全表扫描最多的语句（按扫描行数排）",
                "从语句汇总里挑扫描行数最大的。",
                "慢是慢在库上还是应用上，没有数据支撑。",
                "全表扫描 慢查询 events_statements_summary 扫描行数",
                            "SELECT SCHEMA_NAME AS 库, LEFT(DIGEST_TEXT, 80) AS 语句摘要," + NL +
                    "       SUM_SELECT_SCAN AS 全表扫描行数, COUNT_STAR AS 执行次数" + NL +
                    "FROM performance_schema.events_statements_summary_by_digest" + NL +
                    "WHERE SCHEMA_NAME = DATABASE()" + NL +
                    "ORDER BY SUM_SELECT_SCAN DESC LIMIT 10;"),
        new Sample("性能与容量治理",
                "高延迟语句", "平均延迟最高的语句（毫秒）",
                "按平均延迟排，找最该优化的语句。",
                "凭感觉优化，改完延迟照旧。",
                "延迟 慢语句 平均执行时间 DIGEST_TEXT",
                            "SELECT SCHEMA_NAME AS 库, LEFT(DIGEST_TEXT, 80) AS 语句摘要," + NL +
                    "       COUNT_STAR AS 执行次数," + NL +
                    "       ROUND(AVG_TIMER_WAIT / 1000000000, 2) AS 平均毫秒" + NL +
                    "FROM performance_schema.events_statements_summary_by_digest" + NL +
                    "WHERE SCHEMA_NAME IS NOT NULL" + NL +
                    "ORDER BY AVG_TIMER_WAIT DESC LIMIT 10;"),
        new Sample("性能与容量治理",
                "磁盘临时表", "磁盘临时表大户：排序与分组把结果落到了磁盘",
                "排序与分组把中间结果落到了磁盘。",
                "内存不够时语句慢一个量级，却看不出原因。",
                "临时表 磁盘排序 SUM_CREATED_TMP_DISK_TABLES",
                            "SELECT SCHEMA_NAME AS 库, LEFT(DIGEST_TEXT, 80) AS 语句摘要," + NL +
                    "       SUM_CREATED_TMP_TABLES AS 内存临时表," + NL +
                    "       SUM_CREATED_TMP_DISK_TABLES AS 磁盘临时表" + NL +
                    "FROM performance_schema.events_statements_summary_by_digest" + NL +
                    "WHERE SUM_CREATED_TMP_DISK_TABLES > 0" + NL +
                    "ORDER BY SUM_CREATED_TMP_DISK_TABLES DESC LIMIT 10;"),
        new Sample("性能与容量治理",
                "连接体检", "连接与线程体检：按状态汇总当前连接",
                "按状态汇总当前连接，看空闲与活跃比例。",
                "连接池配置不合理，会话被闲置占位。",
                "连接 状态 COMMAND 空闲 汇总",
                            "SELECT COMMAND AS 状态, COUNT(*) AS 连接数" + NL +
                    "FROM information_schema.PROCESSLIST GROUP BY COMMAND" + NL +
                    "ORDER BY 连接数 DESC;"),
        new Sample("性能与容量治理",
                "InnoDB计数器", "InnoDB 关键计数器：缓冲池读与增删改行数",
                "看缓冲池读与增删改行数，判断内存够不够。",
                "缓冲池命中率低，读全走磁盘却没人察觉。",
                "InnoDB 缓冲池 GLOBAL_STATUS 计数器 命中率",
                            "SELECT VARIABLE_NAME AS 指标, VARIABLE_VALUE AS 值" + NL +
                    "FROM performance_schema.global_status" + NL +
                    "WHERE VARIABLE_NAME IN ('Innodb_buffer_pool_read_requests'," + NL +
                    "                        'Innodb_buffer_pool_reads'," + NL +
                    "                        'Innodb_rows_read', 'Innodb_rows_inserted'," + NL +
                    "                        'Innodb_rows_updated', 'Innodb_rows_deleted');"),
        new Sample("查询与执行计划",
                "按业务键查重", "按业务主键查重（先看清有几批重复）",
                "先看清有几批重复，再决定怎么合。",
                "上来就删，容易把不同业务的同号订单合掉。",
                "查重 GROUP BY HAVING 业务键 批次",
                            "SELECT order_no, COUNT(*) AS 条数 FROM t_order GROUP BY order_no HAVING COUNT(*) > 1;"),
        new Sample("查询与执行计划",
                "执行计划", "看执行计划（type 为 ALL 说明在全表扫）",
                "看语句是不是走了索引。",
                "type 为 ALL 说明全表扫，加索引才有依据。",
                "执行计划 EXPLAIN type ALL 索引 rows",
                            "EXPLAIN SELECT * FROM t_order WHERE created_at >= '2026-01-01';"),
        new Sample("查询与执行计划",
                "分页取数", "分页取数（大表按主键排序，避免 OFFSET 很大时越翻越慢）",
                "大表翻页，按主键排序取下一页。",
                "OFFSET 越大越慢，翻到后面直接超时。",
                "分页 LIMIT OFFSET 游标 主键排序",
                            "SELECT id, order_no, amount FROM t_order ORDER BY id LIMIT 200 OFFSET 0;"),
        new Sample("数据修正",
                "空串改NULL", "把空串统一成 NULL（口径一致，聚合与判空才有意义）",
                "把历史空串统一改成 NULL。",
                "同一含义两种写法，判空条件永远写不全。",
                "空串 UPDATE NULL 口径一致",
                            "UPDATE t_order SET remark = NULL WHERE remark = '';"),
        new Sample("数据修正",
                "删重复行", "去掉完全重复的行，只保留 id 最小的一条",
                "完全重复的行只保留 id 最小的一条。",
                "重复行让计数翻倍，报表长期对不上。",
                "删除重复 自连接 DELETE JOIN 保留一条",
                            "DELETE t1 FROM t_order t1" + NL +
                    "JOIN t_order t2 ON t1.order_no = t2.order_no AND t1.id > t2.id;"),
        new Sample("数据修正",
                "清全表", "清全表并重置自增（比 DELETE 快，且不进事务回滚）",
                "临时表或备份表要整张清空重来。",
                "DELETE 慢且不清自增，还占着事务日志。",
                "TRUNCATE 清空 自增 重置 比 DELETE 快",
                            "TRUNCATE TABLE t_order_bak_20261020;"),
        new Sample("数据修正",
                "修正留痕", "修正留痕：事务内先快照命中行再更新，确认后回滚",
                "改数前先快照命中行，确认后再提交。",
                "改错没有底稿，无法回答改了哪些行。",
                "留痕 事务 快照 UPDATE 回滚 底稿",
                            "START TRANSACTION;" + NL +
                    "CREATE TEMPORARY TABLE tmp_fix_bak AS" + NL +
                    "SELECT * FROM t_order WHERE amount IS NULL OR amount <= 0;" + NL +
                    "UPDATE t_order SET amount = 0 WHERE amount IS NULL;" + NL +
                    "SELECT COUNT(*) AS 留底行数 FROM tmp_fix_bak;" + NL +
                    "ROLLBACK;" + NL +
                    "DROP TEMPORARY TABLE tmp_fix_bak;"),
        new Sample("数据修正",
                "修正核对", "修正确认：对比更新前后的行数与金额合计",
                "对比更新前后的行数与金额合计。",
                "改完不知道对不对，只能等业务方来反馈。",
                "核对 前后对比 COUNT SUM 临时表 验收",
                            "CREATE TEMPORARY TABLE tmp_fix_before AS" + NL +
                    "SELECT COUNT(*) AS 行数, SUM(amount) AS 金额合计 FROM t_order;" + NL +
                    "UPDATE t_order SET amount = ROUND(amount, 2) WHERE amount IS NOT NULL;" + NL +
                    "SELECT b.行数 AS 修正前行数, a.行数 AS 修正后行数," + NL +
                    "       b.金额合计 AS 修正前金额, a.金额合计 AS 修正后金额" + NL +
                    "FROM tmp_fix_before b" + NL +
                    "JOIN (SELECT COUNT(*) AS 行数, SUM(amount) AS 金额合计 FROM t_order) a;" + NL +
                    "DROP TEMPORARY TABLE tmp_fix_before;"),
        new Sample("数据修正",
                "只填空位回写", "安全回写新编码：只填空位、不覆盖已有值",
                "把新编码回写进业务表，只补那些本来为空的行。",
                "无条件覆盖会把已经人工确认过的编码改回机器判的结果。",
                "回写 UPDATE JOIN IS NULL 不覆盖",
                            "UPDATE t_order a JOIN t_dict b ON TRIM(a.name) = TRIM(b.name)" + NL +
                    "SET a.new_code = b.new_code" + NL +
                    "WHERE a.new_code IS NULL;"),
        new Sample("数据修正",
                "回写冲突检测", "回写后冲突检测：同一旧编码不能对应多个新编码，发现即拦截",
                "回写之后检查同一旧编码是否被写进了多枚新码。",
                "冲突没在下游取数前拦掉，脏映射会一路带进报表。",
                "冲突 COUNT DISTINCT HAVING 拦截 回写后",
                            "SELECT old_code, COUNT(DISTINCT new_code) AS 冲突数" + NL +
                    "FROM t_order" + NL +
                    "GROUP BY old_code HAVING 冲突数 > 1;"),
        new Sample("数据修正",
                "未匹配落异常表", "未匹配数据落异常表（附原因与时间，便于跟踪治理）",
                "没匹配上的行不进映射结果，单独落到异常表挂着。",
                "未匹配混在成功里，治理没有跟踪落点，也没人认领这批。",
                "异常表 INSERT SELECT 未匹配 原因 跟踪",
                            "INSERT INTO t_exception_order (old_code, name, reason, check_time)" + NL +
                    "SELECT a.old_code, a.name, '字典未匹配', NOW()" + NL +
                    "FROM t_order a" + NL +
                    "LEFT JOIN t_dict b ON TRIM(a.name) = TRIM(b.name)" + NL +
                    "WHERE b.new_code IS NULL;"),
        new Sample("数据修正",
                "事务内回写", "事务包裹回写（预览影响行数，无误 COMMIT，否则 ROLLBACK）",
                "回写放在事务里，先看影响行数再决定提交还是回滚。",
                "一条 UPDATE 直接打全表，写坏了没有反悔的机会。",
                "事务 START TRANSACTION ROW_COUNT COMMIT ROLLBACK",
                            "START TRANSACTION;" + NL +
                    "UPDATE t_order a JOIN t_dict b ON TRIM(a.name) = TRIM(b.name)" + NL +
                    "SET a.new_code = b.new_code WHERE a.new_code IS NULL;" + NL +
                    "SELECT ROW_COUNT() AS 影响行数;  -- 确认后再 COMMIT / ROLLBACK" + NL +
                    "COMMIT;"),
        new Sample("数据修正",
                "增量比对", "增量比对：找出新增或名称已变的旧编码，只跑增量",
                "源端又变了一批，只挑新增和改名的旧编码重跑。",
                "每轮全量重跑既慢，又会把上一轮已经人工确认的结果覆盖掉。",
                "增量 比对 LEFT JOIN 变化检测 只跑增量",
                            "SELECT a.* FROM t_order a" + NL +
                    "LEFT JOIN t_map_order_code m ON a.old_code = m.old_code" + NL +
                    "WHERE m.old_code IS NULL OR TRIM(a.name) <> TRIM(m.name);"),
        new Sample("数据修正",
                "对账报告", "治理结果对账报告：按置信度汇总占比",
                "按置信度汇总条数与占比，出一版能交出去的对账数。",
                "只有明细没有汇总，交付时说不清这一轮治到了什么程度。",
                "对账 占比 汇总 窗口函数 OVER 8.0",
                            "SELECT conf_level, COUNT(*) AS 条数," + NL +
                    "       ROUND(100 * COUNT(*) / SUM(COUNT(*)) OVER (), 2) AS 占比" + NL +
                    "FROM t_map_order_code GROUP BY conf_level;"),
        new Sample("数据修正",
                "未匹配趋势", "长期治理监控：按天看未匹配数量趋势（需定时跑映射）",
                "映射定时跑起来之后，按天看未匹配数量的走势。",
                "单轮命中率会偶然变好，按天的趋势才看得出治理真的在收敛。",
                "监控 趋势 按天 DATE 未匹配 GROUP BY",
                            "SELECT DATE(check_time) AS 日期, COUNT(*) AS 未匹配数" + NL +
                    "FROM t_exception_order" + NL +
                    "GROUP BY DATE(check_time) ORDER BY 日期 DESC;")
    };

    private SqlSamples() {
    }

    /**
     * 一条示例可被搜到的全部文本（统一转小写），与 Python 侧 sql_samples.haystack
     * 同一份字段拼法：搜索框里同一个词，两条线必须命中同一批条目。
     * 匹配就是一句子串包含，不引分词与打分——示例是给人挑的，不是检索引擎。
     */
    public static String haystack(Sample s) {
        return (s.group + " " + s.title + " " + s.desc + " " + s.scenario + " "
                + s.problem + " " + s.keywords + " " + s.sql)
                .toLowerCase(java.util.Locale.ROOT);
    }

    /** 关键词过滤：空串等于不过滤。 */
    public static boolean matches(Sample s, String needle) {
        String key = needle == null ? "" : needle.trim().toLowerCase(java.util.Locale.ROOT);
        return key.isEmpty() || haystack(s).contains(key);
    }

    /**
     * 右侧上方那段介绍的正文，与 Python 侧 SqlSamplesDialog.intro_text 逐字同形：
     * 纯文本五行，不引 HTML 引擎。
     */
    public static String introText(Sample s) {
        return "【" + s.group + " · " + s.title + "】" + NL
                + "说明：" + s.desc + NL
                + "场景：" + s.scenario + NL
                + "解决的问题：" + s.problem + NL
                + "关键词：" + s.keywords;
    }
}
