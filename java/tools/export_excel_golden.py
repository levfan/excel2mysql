# -*- coding: utf-8 -*-
"""造 Excel 夹具并导出 Python 侧金标准，供 Java 线做逐格对撞。

期望值全部取自真实 pandas/openpyxl 与真实 core.build_table_def，不由本脚本重新实现规则。
浮点一律存 float.hex()：Python 的 repr 是十进制最短形（1e+25），Java 的 Double.toString
是另一种最短形（1.0E25），拿字符串比必然假红；存位级表示才能真比大小。

记录里的 sha256 是夹具文件本身的哈希，**不属于比对契约**：两个 venv 的 openpyxl 版本
不同，写出的 xlsx 字节就不同（实测 venv3.12 与 venv3.8 七个文件全不一致），
实测出来的读取层契约（Java 侧必须照做，别再靠猜）：

1. **整值浮点会被 pandas 转成 Python int**。`pandas/io/excel/_openpyxl.py::_convert_cell`
   对数字单元格做 `val = int(cell.value); if val == cell.value: return val`。
   所以写进去的 100.0 读出来是 int 100，1e20 读出来是 int
   10000000000000000905969664（浮点精确值，不是"1e20"）。
   ⇒ Java 侧数值单元格要按"能不能精确表示成整数"分 int/float 两支，
     且整数支必须用 BigInteger，long 装不下 1e20。
2. **重复表头会被 pandas 改名成 `X.1`、`X.2`**，而 infer_columns 用 `str(列名)` 当注释，
   于是第二个"重复名"转出来的字段是 `chong_fu_ming_1`（点被当非 \w 换成下划线）。
   金标准里 18 个表头对应 17 个字段，少的正是全空列被 dropna(axis=1) 丢掉。
3. **布尔单元格读成 True/False，最终推断成 INT**（`_looks_int` 对 bool 直接返回 True）。
4. 空白列（`   `、``、`  \t `）算空值不参与长度统计，但整列都空时仍出 VARCHAR(64) 而不是被丢——
   只要该列有过非空值。
"""
from __future__ import print_function

import datetime
import gzip
import hashlib
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, ROOT)

import pandas as pd  # noqa: E402
from openpyxl import Workbook  # noqa: E402

from core.infer import build_table_def  # noqa: E402

FIX = os.path.join(ROOT, "java", "verify", "fixtures")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def encode(v):
    """单元格值 -> (类型标签, 可比对的规范文本)。"""
    if v is None or v is pd.NA or v is pd.NaT:
        # pandas 3.0 的空缺标量是 pd.NA，它不是 float 且 `pd.NA != pd.NA` 会抛
        # "boolean value of NA is ambiguous"，必须先按身份判掉
        return ("none", "")
    if isinstance(v, float) and v != v:
        return ("none", "")
    if isinstance(v, bool):
        return ("bool", "1" if v else "0")
    if isinstance(v, int):
        return ("int", str(v))
    if isinstance(v, float):
        return ("float", float(v).hex())
    if isinstance(v, datetime.datetime):
        return ("datetime", v.isoformat())
    if isinstance(v, datetime.date):
        return ("date", v.isoformat())
    if isinstance(v, str):
        return ("str", v)
    return ("other", repr(v))


def make_basic(path):
    """第 1 类普通数据表：把推断的每个分支都塞进同一张表。

    列的选取原则是"每列只压一条规则"，出错时能直接定位到哪条规则，
    而不是造一张大表然后在一堆差异里猜。
    """
    wb = Workbook()
    ws = wb.active
    ws.title = u"水库基础信息"
    rows = [
        [u"编号", u"名称", u"金额", u"单价", u"利率", u"整数", u"小数", u"科学计数",
         u"前导零", u"日期串", u"日期对象", u"日期时间", u"布尔", u"空列",
         u"纯空白", u"超长文本", u"重复名", u"重复名"],
        [1, u"北京市水务局", 100, 12.5, 0.035, 7, 3.125, 1e25, u"007",
         u"2023-01-02", datetime.date(2023, 1, 2), datetime.datetime(2023, 1, 2, 3, 4, 5),
         True, None, u"   ", u"x" * 5000, 1, 1],
        [2, u"水库管理处", 200, 3.25, 0.5, 8, 1.5, 2.5e30, u"00123",
         u"2023-02-03", datetime.date(2023, 2, 3), datetime.datetime(2023, 2, 3, 6, 7, 8),
         False, None, u"", u"y" * 100, 2, 2],
        [3, u"水质监测站", 6.875, 88, 1.25, 9, 0.0, 1e20, u"0",
         u"2023/03/04", datetime.date(2023, 3, 4), datetime.datetime(2024, 12, 31, 23, 59, 59),
         True, None, u"  \t ", u"数据" * 2000, 3, 3],
        [4, None, 0, 0.005, 0, 2147483647, 100.0, 1e-7, 100,
         u"20241231", datetime.date(2024, 12, 31), datetime.datetime(2025, 1, 1, 0, 0, 0),
         False, None, None, u"z", 4, 4],
        [5, u"带,逗号", 1e6, 1234567890123456.75, 1e-3, -2147483648, 0.1, 0,
         u"-007", u"2020.01.01", datetime.date(2020, 1, 1), datetime.datetime(2020, 1, 1, 1, 1, 1),
         True, None, None, u"éアイ", None, None],
    ]
    for r in rows:
        ws.append(r)
    wb.save(path)


def make_overflow(path):
    """INT 溢出、DECIMAL 宽度上限、VARCHAR 行预算挤爆三条路径。"""
    wb = Workbook()
    ws = wb.active
    ws.title = "big"
    ws.append([u"序号", u"超大整数", u"巨宽小数", u"宽文本A", u"宽文本B"])
    for i in range(1, 131):
        # 9223372036854775807 + i 会超出 BIGINT 上限，MySQL 绕成负数且不报错，
        # 而推断只判"超没超 INT"就定了 BIGINT——那是两条线共有的丢数据路径。
        # 这里收进合法范围让回归测真正要测的东西；溢出本身由 overflow_probe 单独钉住。
        ws.append([i, 9223372036854775000 + i, 12345678901234.1234567890,
                   u"文" * 900, u"文" * 900])
    wb.save(path)


def make_meta(path):
    """第 2 类结构定义 Excel：元数据 Sheet + 数据 Sheet。"""
    wb = Workbook()
    meta = wb.active
    meta.title = u"字段定义"
    meta.append([u"字段名", u"字段注释", u"数据类型", u"长度", u"主键", u"可为空"])
    meta.append(["id", u"订单编号", "BIGINT", None, u"是", u"否"])
    meta.append(["code", u"代码", "VARCHAR", 64, u"否", u"是"])
    meta.append(["amount", u"金额", "decimal", "10,2", None, None])
    meta.append(["remark", u"说明", u"文本", None, None, None])
    meta.append([None, u"空字段名行", "VARCHAR", 20, None, None])
    meta.append(["id", u"重名字段", "INT", None, None, None])
    data = wb.create_sheet(u"订单数据")
    data.append(["ID", "CODE", "AMOUNT", "REMARK", u"多余列"])
    data.append([1, "A-1", 12.345, u"备注", u"忽略我"])
    data.append([2, "B-2", "9,999.50", None, None])
    data.append([3, None, None, u"三条", None])
    wb.save(path)


def make_cn_name(path):
    """文件名带中文括号、拼音超长降级、撞名三类命名契约。"""
    wb = Workbook()
    ws = wb.active
    ws.title = "s"
    ws.append([u"序号", u"北京市住房和城乡建设委员会下属单位联系电话"])
    ws.append([1, u"010-12345678"])
    ws.append([2, u"010-87654321"])
    wb.save(path)


FIXTURES = [
    (u"水库基础信息（dim_reservoir_info）.xlsx", make_basic),
    (u"大表压精度（ods_t_big）.xlsx", make_overflow),
    (u"结构定义订单（ods_t_order_meta）.xlsx", make_meta),
    (u"超长拼音名统计（a_very_long_table_name_that_keeps_going_and_going_x）.xlsx",
     make_cn_name),
    (u"人员信息表（人事处）.xlsx", make_cn_name),
    (u"人员信息表（财务部）.xlsx", make_cn_name),
    (u"纯数字括号（7）.xlsx", make_cn_name),
]


def dump_file(path):
    """一个夹具 -> 一条金标准记录：原始格值 + build_table_def 的产物。"""
    sheets = pd.read_excel(path, sheet_name=None, dtype=object)
    grid = []
    for name, sdf in sheets.items():
        headers = [encode(c) for c in sdf.columns]
        rows = [[encode(v) for v in row] for row in sdf.itertuples(index=False, name=None)]
        grid.append({"name": name, "headers": headers, "rows": rows})

    tdef = build_table_def(path)
    cols = [{k: v for k, v in c.items() if k != "max_len" or isinstance(v, (int, str, type(None)))}
            for c in tdef["columns"]]
    return {
        "file": os.path.basename(path),
        "sha256": sha256(path),
        "grid": grid,
        "def": {
            "table_name": tdef["table_name"],
            "base_table_name": tdef["base_table_name"],
            "table_comment": tdef["table_comment"],
            "columns": cols,
            "primary_key": tdef["primary_key"],
            "pk_reason": tdef["pk_reason"],
            "source": tdef["source"],
        },
    }


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        ROOT, "java", "verify", "golden", "excel.jsonl.gz")
    if not os.path.isdir(FIX):
        os.makedirs(FIX)
    print("- [INFO] pandas = %s | python = %s" % (pd.__version__, sys.version.split()[0]))
    records = []
    for name, builder in FIXTURES:
        path = os.path.join(FIX, name)
        builder(path)
        records.append(dump_file(path))
        print("- [INFO] 夹具 %s (%d 个 sheet)" % (name, len(records[-1]["grid"])))
    d = os.path.dirname(out)
    if not os.path.isdir(d):
        os.makedirs(d)
    with gzip.open(out, "wb") as gz:
        for rec in records:
            gz.write(json.dumps(rec, ensure_ascii=False,
                                separators=(",", ":")).encode("utf-8") + b"\n")
    print("+ [OK] 金标准导出 | %d 条 | %s | sha256=%s"
          % (len(records), out, sha256(out)[:16]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
