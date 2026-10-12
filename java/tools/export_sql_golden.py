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

from core.infer import build_table_def, apply_table_prefix, replace_first_segment  # noqa: E402
from core.mysql_io import build_ddl, build_insert_sql, _convert  # noqa: E402
from core.naming import clean_prefix, is_valid_prefix, swap_first_segment  # noqa: E402
from core.sql_script import (  # noqa: E402
    apply_table_prefix_sql, extract_create_tables, replace_first_segment_sql,
    split_sql_statements,
)

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


def sql_cases():
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


def ddl_cases():
    fix_dir = os.path.join(ROOT, "java", "verify", "fixtures")
    for name in sorted(os.listdir(fix_dir)):
        if not name.lower().endswith(".xlsx"):
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
