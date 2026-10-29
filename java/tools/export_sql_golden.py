# -*- coding: utf-8 -*-
"""导出 SQL 脚本文本线（core/sql_script.py + core/naming.py）的 Python 金标准。

不自己实现规则，期望值一律调用 core 里的真实函数取。
"""
from __future__ import print_function

import gzip
import hashlib
import io
import datetime
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, ROOT)

sys.path.insert(0, HERE)

from ddl_corpus import DDL_SCRIPTS  # noqa: E402
from result_corpus import TABLE_CASES  # noqa: E402

from core.infer import build_table_def, apply_table_prefix, replace_first_segment  # noqa: E402
from core.mysql_io import build_ddl, build_insert_sql, _convert  # noqa: E402
from core.naming import clean_prefix, is_valid_prefix, swap_first_segment  # noqa: E402
from core.sql_execute import render_table  # noqa: E402
from core.sql_script import (  # noqa: E402
    apply_table_prefix_sql, edit_column, extract_create_tables, format_sql,
    replace_first_segment_sql, parse_create_tables, set_table_comment,
    split_sql_statements,
)
from sql_samples import SAMPLES  # noqa: E402

OUT_DIR = os.path.join(ROOT, "java", "verify", "golden")

SCRIPTS = [
    "CREATE TABLE `ods_t_order` (id INT);\nINSERT INTO `ods_t_order` VALUES (1);",
    "create table if not exists ods_t_order (id INT);\ndrop table ods_t_order;",
    "CREATE TABLE mydb.`x_a` (a INT);\nCREATE TABLE mydb.x_b (b INT);\n"
    "INSERT INTO x_a VALUES (1);\nINSERT mydb.x_b VALUES(2);",
    "CREATE TABLE `users` (name VARCHAR(10));\nALTER TABLE users ADD (age INT);\n"
    "TRUNCATE TABLE users;\n-- CREATE TABLE fake (x INT);\n/* ; CREATE TABLE y (z INT) */",
    "CREATE TABLE a_1 (x INT);\nCREATE TABLE _tmp (y INT);\nCREATE TABLE plain (z INT);",
    "CREATE TABLE `t_semi` (c VARCHAR(10) DEFAULT 'a;b');\n"
    "INSERT INTO `t_semi` VALUES ('x;y');\nINSERT INTO `t_semi` VALUES (\"p\"\"q;r\");\n"
    "INSERT INTO `t_semi` VALUES ('esc\\';still');",
    "CREATE TABLE `ods_t_order` (id INT);\nCREATE TABLE `ods_t_order_item` (id INT);\n"
    "CREATE INDEX idx ON ods_t_order (id);",
]

PREFIXES = ["ods", "ods_", "`ods`\\", "", "dim", "odsods"]
HEADS = ["change", "change_", "", "`x`", "ods"]

# 表注释改写（set_table_comment）的形状覆盖：两条线各写一份正则与括号配对，
# 少对一处就会写出跑不了的 DDL，所以逐个形状对字节比
COMMENT_SCRIPTS = [
    "CREATE TABLE `t_a` (`id` INT) ENGINE=InnoDB COMMENT='原注释';",
    "CREATE TABLE t_b (`id` INT) ENGINE=InnoDB;",
    'CREATE TABLE t_c (`id` INT) COMMENT="双引号写法" ENGINE=InnoDB;',
    "CREATE TABLE t_d LIKE t_b;",
    "CREATE TABLE t_e (`id` INT COMMENT '列注释', `n` VARCHAR(8)) COMMENT='表注释';",
    "CREATE TABLE t_f (`id` INT) COMMENT='第一处';\n"
    "CREATE TABLE t_f (`x` INT) COMMENT='第二处';",
    "CREATE TABLE `t_g` (`id` INT) COMMENT='带''翻倍引号''的';",
    "CREATE TABLE t_h (`c` VARCHAR(10) DEFAULT 'a;b') COMMENT='带分号';\n"
    "INSERT INTO t_h VALUES ('x;y');",
    "CREATE TABLE t_i (`id` INT) /* COMMENT='块注释里的不算' */;",
    "CREATE TABLE t_j (`id` INT);\nINSERT INTO t_j VALUES (1);",
]
COMMENT_VALUES = ["新注释", "带'单引号'", "", "  两端空格要削掉  ", "换行\n也可以放", "含;分号"]

# 某一列的改写（元数据弹窗那三列可编辑）：只动那一行的那一段，被主键/索引名单引用的
# 字段拒绝改名——放过去就是一句引用不存在列的 DDL，等建表时才炸比在这儿拒绝难查得多。
# 两条线各写一份"找那一行、换那一段"，少对一处就会写出跑不了的脚本，所以逐形状对字节比。
COLUMN_SCRIPTS = [
    "CREATE TABLE `t_c1` (\n"
    "  `id` INT NOT NULL COMMENT '主键',\n"
    "  `amount` DECIMAL(10,2) DEFAULT '0.00' COMMENT '金额',\n"
    "  `name` VARCHAR(64) NOT NULL,\n"
    "  PRIMARY KEY (`id`),\n"
    "  KEY `idx_name` (`name`)\n"
    ") ENGINE=InnoDB COMMENT='演示表';\n",
    "CREATE TABLE t_c2 (`id` INT, `note` VARCHAR(8) COMMENT '带''翻倍''的');",
    "CREATE TABLE t_c3 (`id` INT, `v` DECIMAL(10,2) UNSIGNED NOT NULL DEFAULT 0);",
    "CREATE TABLE t_c4 LIKE t_c2;",
    "CREATE TABLE t_c5 (`a` INT);\nCREATE TABLE t_c5 (`a` INT, `b` INT);",
    "CREATE TABLE `t_c6` (`x` INT COMMENT '行注释', -- `y` INT 是注释里的列\n  `y` INT);",
]
# (字段名, 新字段名, 新注释, 新类型)：None 表示这一项不改
COLUMN_EDITS = [
    ("id", "pk", None, None),
    ("id", None, "编号", None),
    ("id", None, None, "BIGINT"),
    ("amount", None, "金额（元）", None),
    ("amount", None, None, "DECIMAL(16,4)"),
    ("amount", "money", None, None),
    ("name", None, "姓名", None),
    ("name", "n", None, None),
    ("x", "y", "", "VARCHAR(2)"),
    ("note", None, "带'单引号'的", None),
    ("v", None, None, "DECIMAL(20,6)"),
    ("a", "b", None, None),
    ("nope", "x", None, None),
    ("id", "", None, None),
    ("id", "a b", None, None),
    ("id", "a`b", None, None),
    ("id", None, None, ""),
    ("id", None, None, "a;b"),
]

# 格式化语料：每条都踩住一条规则，两栈输出必须逐字节相同
FORMAT_INPUTS = [
    "", "   \n  ", ";", "select 1;",
    "SELECT 1",
    "select 1 where a<=2 and b<>3 or c!=4",
    "select a||b, c->'$.d' from t",
    "CREATE TABLE `t_a` (`id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',"
    " `amount` DECIMAL(20,4) DEFAULT NULL COMMENT '金额，含逗号',"
    " PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表';",
    "CREATE TABLE IF NOT EXISTS db.`tbl` (id INT PRIMARY KEY) DEFAULT CHARACTER SET utf8mb4;",
    "CREATE TEMPORARY TABLE tmp_x (cust_no VARCHAR(32) PRIMARY KEY);",
    "alter table t add column c int comment '丙', modify column d varchar(10) after c",
    "CREATE INDEX idx ON t (a, b)",
    "INSERT INTO t VALUES (1),(2);",
    "INSERT INTO t (a,b) VALUES ('x;1', 'y''2')",
    "select 'a;b' as x, `c;d` from t",
    "UPDATE t SET a=1, b=2 WHERE id=3",
    "DELETE FROM t LIMIT 1",
    "SELECT CASE WHEN a>1 THEN 'x' ELSE 'y' END AS c FROM t",
    "select a from (select b as a from t) x where a in (1,2,3)",
    "SELECT CONCAT(a, '-', b) AS k, GROUP_CONCAT(c ORDER BY d SEPARATOR ',') "
    "FROM t GROUP BY k HAVING COUNT(*) > 1",
    "SELECT * FROM t /* 块注释 */ WHERE a=1",
    "-- 头注\nselect 1; -- 尾注\n# 井号注\nselect 2;",
    "# 只有注释",
    "select @@version, @@global.max_connections, database(), count(1) from dual",
    "SELECT * FROM t_order o LEFT JOIN t_map m ON o.no = m.no AND o.k = 1 "
    "WHERE o.a IS NOT NULL AND (o.b LIKE '%x%' OR o.b LIKE '%y%') ORDER BY o.id DESC LIMIT 10",
    "select id, name from t_user where age > 18 and status = 1 order by id desc limit 10;",
    "SELECT " + ", ".join("c%d" % i for i in range(30)) + " FROM wide_table",
    "select a from t where " + " OR ".join("c%d = %d" % (i, i) for i in range(20)),
    "CREATE TABLE `t_b` (" + ", ".join("`c%d` INT" % i for i in range(12)) + ") ENGINE=InnoDB;",
]
# 工具自己生成的那份 DDL：格式化它必须是零改动（两栈同判）
FORMAT_INPUTS.append(build_ddl("ods_t_student", "学生基本信息", [
    {"field": "id", "type": "BIGINT UNSIGNED", "comment": "主键",
     "nullable": False, "primary_key": True},
    {"field": "student_name", "type": "VARCHAR(64)", "comment": "姓名", "nullable": False},
    {"field": "amount", "type": "DECIMAL(20,4)", "comment": "金额", "nullable": True},
], True))

CONVERT_TYPES = ["INT", "BIGINT", "DECIMAL(10,2)", "VARCHAR(64)", "TEXT", "DATE", "DATETIME"]
CONVERT_VALUES = [None, "", "  ", "7", "7.0", "-007", "1,234", "abc", "2023-01-02",
                  "2023/1/2 3:4:5", "20230102", "2023-02-30", True, False,
                  7, 7.5, "0"]


def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def enc(v):
    """值 -> (类型标签, repr)，给 Java 侧做逐值比对的单位统一用。

    日期时间不能用 repr：Python 的 repr 会省略为 0 的秒
    （datetime(2023,1,2,0,0) 与 datetime(2023,1,2,3,4,5) 写法不一致），
    两边单位不统一必然假红，所以这两类改存 isoformat。
    """
    if v is None:
        return ["null", "None"]
    if isinstance(v, bool):
        return ["bool", repr(v)]
    if isinstance(v, datetime.datetime):
        return ["datetime", v.isoformat()]
    if isinstance(v, datetime.date):
        return ["date", v.isoformat()]
    return [type(v).__name__, repr(v)]


def copy_table(t):
    """浅拷贝一份表定义：改名要动 table_name/base_table_name，别把原对象改了。"""
    return {
        "table_name": t["table_name"],
        "base_table_name": t["base_table_name"],
        "table_comment": t["table_comment"],
        "columns": t["columns"],
    }


def deepcopy_table(t):
    import copy
    return copy.deepcopy(t)


def ddl_parse_lines(text):
    """表定义渲染成扁平文本行，Java 侧 SqlDiffer 用同一种渲染逐行对。

    渲染而不比结构：解析结果里类型、注释、主键、可空、近似说明哪一项都可能单边写错，
    拍成一行一行之后任何一格不同都会露出来，两边也就不用约定嵌套 JSON 的形状。
    """
    out = []
    for t in parse_create_tables(text):
        out.append("T|%s|%s" % (t["table_name"], t["table_comment"]))
        for c in t["columns"]:
            out.append("C|%s|%s|%s|%s|%s" % (
                c["field"], c["type"], c["comment"],
                "1" if c["primary_key"] else "0",
                "-" if c.get("nullable") is None else ("1" if c["nullable"] else "0")))
        for n in t["notes"]:
            out.append("N|%s" % n)
    return out


def sql_cases():
    for text in SCRIPTS:
        yield {"in": text,
               "extract": extract_create_tables(text),
               "split": split_sql_statements(text)}
    # 格式化：期望值一律由 Python 侧真函数产出，Java 的 SqlScript.formatSql 必须
    # 逐字节相同。语料取三处——脚本夹具、126 条示例正文、以及几条专门踩规则的输入
    # （类型括号、表选项等号、条件链、注释与引号里的分号）。
    # 幂等与"切分不变"这两条性质在 Python 用例集里钉（pack_test 的 Q 组），
    # 这里只钉"两栈输出相同"——相同 + Python 侧正确，就传递出 Java 侧正确。
    for text in FORMAT_INPUTS:
        yield {"op": "format", "in": text, "out": format_sql(text)}
    for s in SAMPLES:
        yield {"op": "format", "in": s.sql, "out": format_sql(s.sql)}
    for text in DDL_SCRIPTS:
        yield {"op": "ddl-parse", "in": text, "out": ddl_parse_lines(text)}
    for cols, rows in TABLE_CASES:
        # 结果集渲染成表格是两条线各写一份的渲染器：拍成一行一行逐字符对，
        # 宽度算错、边框少画、截断位置不同、NULL 与空串混起来都会露出来
        yield {"op": "table-render", "in": "列[%s] 行%d" % (" ".join(cols), len(rows)),
               "columns": cols, "rows": rows, "out": render_table(cols, rows)}
    for text in SCRIPTS:
        yield {"in": text,
               "extract": extract_create_tables(text),
               "split": split_sql_statements(text)}
        for p in PREFIXES:
            r1, ren1, sk1 = apply_table_prefix_sql(text, p)
            yield {"op": "prefix", "in": text, "arg": p, "out": r1,
                   "renamed": ren1, "skipped": sk1}
        for h in HEADS:
            r2, ren2, sk2 = replace_first_segment_sql(text, h)
            yield {"op": "head", "in": text, "arg": h, "out": r2,
                   "renamed": ren2, "skipped": sk2}
    for raw in PREFIXES + HEADS + ["  a b ", "a__b", "_", "中文"]:
        yield {"op": "clean", "in": raw, "out": clean_prefix(raw),
               "valid": is_valid_prefix(clean_prefix(raw))}
        yield {"op": "swap", "in": raw, "out": swap_first_segment(raw, "ch")}
    for text in COMMENT_SCRIPTS:
        for t in extract_create_tables(text):
            for c in COMMENT_VALUES:
                new_text, changed = set_table_comment(text, t["name"], c)
                yield {"op": "comment", "in": text, "table": t["name"], "comment": c,
                       "out": new_text, "changed": changed}
    for text in COLUMN_SCRIPTS:
        for t in extract_create_tables(text):
            for fld, nf, nc, nt in COLUMN_EDITS:
                new_text, changed, reason = edit_column(text, t["name"], fld, nf, nc, nt)
                # changed 存 0/1：金标准里 Boolean 与 Number 是两种取值，
                # Java 侧按 int 读，这里统一成整数才不会出现单边 Cast 失败
                yield {"op": "col-edit", "in": text, "table": t["name"], "field": fld,
                       "new_field": nf, "new_comment": nc, "new_type": nt,
                       "out": new_text, "changed": 1 if changed else 0, "reason": reason}


def ddl_cases():
    fix_dir = os.path.join(ROOT, "java", "verify", "fixtures")
    for name in sorted(os.listdir(fix_dir)):
        if not name.lower().endswith(".xlsx"):
            continue
        # 词表全量夹具有一千多列，会把 DDL 的行内预算与插入列数推到极限——
        # 那是另一条规则的对撞面，不该混在词表扩充这一轮里；它由类型线（excel 金标准）覆盖
        if u"词表全量" in name:
            continue
        t = build_table_def(os.path.join(fix_dir, name))
        cols = t["columns"]
        for drop in (False, True):
            yield {"op": "ddl", "in": name, "drop": drop,
                   "out": build_ddl(t["table_name"], t["table_comment"], cols, drop)}
        yield {"op": "insert", "in": name, "out": build_insert_sql(t["table_name"], cols)}
        # 前缀与换首段会走同一张表定义，改名后再建一次 DDL 才覆盖得到撞名去重
        tables = [dict(t)]
        for p in ("ods", "ods", ""):
            apply_table_prefix(tables, p)
            yield {"op": "prefix-def", "in": name, "arg": p,
                   "out": [x["table_name"] for x in tables]}
        tables = [dict(t)]
        replace_first_segment(tables, "chg")
        yield {"op": "head-def", "in": name, "out": [x["table_name"] for x in tables]}
    for typ in CONVERT_TYPES:
        for v in CONVERT_VALUES:
            got, err = _convert(v, typ)
            yield {"op": "convert", "type": typ, "value": enc(v),
                   "out": enc(got), "error": err}


def main():
    if not os.path.isdir(OUT_DIR):
        os.makedirs(OUT_DIR)
    out = os.path.join(OUT_DIR, "sql.jsonl.gz")
    n_sql = n_ddl = 0
    with gzip.open(out, "wb") as gz:
        for rec in sql_cases():
            gz.write(json.dumps(rec, ensure_ascii=False,
                                separators=(",", ":")).encode("utf-8") + b"\n")
            n_sql += 1
        for rec in ddl_cases():
            gz.write(json.dumps(rec, ensure_ascii=False,
                                separators=(",", ":")).encode("utf-8") + b"\n")
            n_ddl += 1
    print("+ [OK] SQL 金标准 | 脚本线 %d 条 + DDL/转换 %d 条 | %s | sha=%s"
          % (n_sql, n_ddl, out, sha(out)[:16]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
