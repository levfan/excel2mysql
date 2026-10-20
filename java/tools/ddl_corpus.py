# -*- coding: utf-8 -*-
"""「CREATE TABLE 正文解析」的金标准语料：两条线各写一份解析器，必须逐行对得上。

语料挑的都是现场脚本里真会出现、而且一写错就静默出错 shape 的写法：
表级复合主键、UNSIGNED、字符集修饰、ENUM、注释里带分号与双写引号、
schema 限定名、约束行、LIKE / AS SELECT、同名表定义两次、反引号里再套反引号、
以及"注释里写的 CREATE TABLE 不算数"。
"""

DDL_SCRIPTS = [
    # 一张典型业务表：表级复合主键 + 各种修饰 + 注释里带分号和转义引号
    "CREATE TABLE `t_order` (\n"
    "  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',\n"
    "  `no` varchar(64) CHARACTER SET utf8mb4 NOT NULL COMMENT '编号;带分号',\n"
    "  `amount` decimal(10, 2) DEFAULT NULL,\n"
    "  `status` enum('new','paid') DEFAULT 'new',\n"
    "  `note` varchar(20) DEFAULT 'it''s ok',\n"
    "  PRIMARY KEY (`id`, `no`),\n"
    "  UNIQUE KEY `uk` (`no`),\n"
    "  CONSTRAINT c1 CHECK (`amount` > 0)\n"
    ") ENGINE=InnoDB COMMENT='订单表';",

    # 注释里的 CREATE TABLE 不算数：行注释与块注释各藏一条
    "CREATE TABLE fake2 (a INT);\n"
    "-- CREATE TABLE fake3 (b INT);\n"
    "/* CREATE TABLE fake4 (c INT) */",

    # 裸表名 + schema 限定名 + 大写关键字 + index 行
    "create table db9.plain (A int, b TEXT, c DECIMAL(8) NOT NULL DEFAULT 0, index (b));",

    # 同名表定义两次只按第一处；LIKE / AS SELECT 没有列定义；反引号里套反引号
    "CREATE TABLE t_dup (x INT);\n"
    "CREATE TABLE t_dup (y INT);\n"
    "CREATE TABLE t_like LIKE t_dup;\n"
    "CREATE TABLE t_as AS SELECT * FROM t_dup;\n"
    "CREATE TABLE t_esc (`we``ird` INT) COMMENT='怪名';",

    # 空表名列表与只有约束的极端形状：不能崩，也不能凭空造出列
    "CREATE TABLE t_empty ();",

    # 一条语句里括号不配对（未闭合），解析器要跳过而不是把后面的都吞掉
    "CREATE TABLE t_broken (a INT, b VARCHAR(3\n"
    "CREATE TABLE t_after (c INT);",
]
