"""性能基准：定位大文件（几十万行）导入的热点。

只测不优化；改完之后重跑同一份脚本对比。
"""
import cProfile
import io
import os
import pstats
import sys
import time
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pandas as pd
import numpy as np

ROWS = int(os.environ.get("BENCH_ROWS", "200000"))
COLS = 8

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
from core.infer import build_table_def, infer_columns, select_primary_key
from core.sql_script import split_sql_statements, extract_create_tables, apply_table_prefix_sql
from core.mysql_io import insert_data, build_ddl

tmp = tempfile.mkdtemp(prefix="bench_")


def hh(label, fn, repeat=1):
    best = None
    for _ in range(repeat):
        t0 = time.perf_counter()
        r = fn()
        dt = time.perf_counter() - t0
        best = dt if best is None else min(best, dt)
    print(f"  {label:44s} {best*1000:9.1f} ms")
    return r, best


print(f"===== 基准数据：{ROWS} 行 × {COLS} 列 =====")

df = pd.DataFrame({
    "员工编号": [f"EMP{i:08d}" for i in range(ROWS)],
    "姓名": ["张三丰" [:3] if i % 3 else "李四" for i in range(ROWS)],
    "订单金额": [float(i % 100000) + (0.5 if i % 2 else 0) for i in range(ROWS)],
    "建成年份": [1990 + (i % 35) for i in range(ROWS)],
    "水位日期": ["2023-05-01" if i % 2 else "2024-11-30" for i in range(ROWS)],
    "备注说明": ["正常" if i % 7 else "这是一段比较长的备注文本内容" for i in range(ROWS)],
    "系数值": [round(i * 1.0001, 3) for i in range(ROWS)],
    "空值列": [None if i % 2 else 0 for i in range(ROWS)],
})
xlsx = os.path.join(tmp, "基准表.xlsx")
hh("生成 xlsx（openpyxl 写入）", lambda: df.to_excel(xlsx, sheet_name="S", index=False))

sheets, read_s = hh("pd.read_excel 读取", lambda: pd.read_excel(xlsx, sheet_name=None, dtype=object))
print(f"    -> 读回 {len(next(iter(sheets.values())))} 行")

cols0, infer_s = hh("infer_columns 纯值推断（不含读文件）", lambda: infer_columns(df))
_, pk_s = hh("select_primary_key 主键挑选", lambda: select_primary_key(df, infer_columns(df)))
_, full_s = hh("build_table_def 端到端（读+推断+主键）", lambda: build_table_def(xlsx))

cols = infer_columns(df)
ddl = build_ddl("bench_t", "基准表", cols, False)
_, _ = hh("build_ddl 生成建表语句", lambda: build_ddl("bench_t", "基准表", cols, False))


class FakeCur:
    rowcount = 500

    def execute(self, *a):
        pass

    def executemany(self, sql, vals):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class FakeConn:
    def cursor(self):
        return FakeCur()

    def commit(self):
        pass


_, ins_s = hh("insert_data 20 万行（假游标，只算转换开销）",
              lambda: insert_data(FakeConn(), "bench_t", cols, df, batch=500))

sql_text = "\n".join(
    f"INSERT INTO `t_bench` VALUES ({i}, '员工{i}', {i * 1.5}, '2023-05-01');" for i in range(ROWS)
)
sqlf = os.path.join(tmp, "bench.sql")
open(sqlf, "w", encoding="utf-8").write(sql_text)
_, sp_s = hh(f"split_sql_statements（{len(sql_text)/1048576:.0f} MB / {ROWS} 条）",
             lambda: split_sql_statements(sql_text))
_, ex_s = hh("extract_create_tables 同上文件", lambda: extract_create_tables(sql_text))
_, pf_s = hh("apply_table_prefix_sql 同上文件", lambda: apply_table_prefix_sql(sql_text, "ods_"))

print("\n===== build_table_def 的 profile（前 18 行）=====")
pr = cProfile.Profile()
pr.runcall(build_table_def, xlsx)
st = pstats.Stats(pr)
st.sort_stats("cumulative").print_stats(18)

print("\n===== split_sql_statements 的 profile =====")
pr2 = cProfile.Profile()
pr2.runcall(split_sql_statements, sql_text)
pstats.Stats(pr2).sort_stats("tottime").print_stats(8)

print("\n===== 汇总 =====")
print(f"  read_excel      {read_s:8.2f} s")
print(f"  infer_columns   {infer_s:8.2f} s")
print(f"  +主键挑选       {pk_s:8.2f} s")
print(f"  build_table_def {full_s:8.2f} s")
print(f"  insert_data     {ins_s:8.2f} s")
print(f"  split_sql       {sp_s:8.2f} s")
