# -*- coding: utf-8 -*-
"""造 .xls（97-2003 老格式）夹具并导出 Python 侧金标准。

为什么单独一支：help_doc 里"两类 Excel 格式"是写给用户的功能承诺，
而 java/verify/fixtures 七个文件全是 .xlsx——读取层、合并单元格检查、类型推断
在 .xls 这条路上一次都没跑过。老格式不是"另一种扩展名"，它的存储模型不同：
所有数字都是 IEEE 双精度（没有整数型）、日期是序列号加单元格格式、
布尔是独立单元格类型、空白有 BLANK 与 EMPTY 两种、字符串直接内联而非共享表。
所以期望值仍然只能取自真实 pandas + xlrd 与真实 core.build_table_def，
本脚本不重新实现任何规则；写文件用 xlwt 只是因为 pandas 3.x 已不能写 .xls。
"""
from __future__ import print_function

import datetime
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, ROOT)
sys.path.insert(0, HERE)

import xlwt  # noqa: E402

from core.merge_check import detect_merged_cells  # noqa: E402
from core.merge_check import MergedCellsError  # noqa: E402

import export_excel_golden as g  # noqa: E402：复用 encode/dump_file/sha256，别再实现一遍

FIX = g.FIX

DATE_STYLE = "MM/DD/YYYY"


def _sheet(book, name):
    return book.add_sheet(name, cell_overwrite_ok=True)


# xlwt 1.3.0 的两个坑：style=None 会在 __adjust_height 里抛 AttributeError；
# easyxf 又不认格式段写法。所以默认给一个空 XFStyle，日期格式直接设 num_format_str。
DEFAULT_STYLE = xlwt.XFStyle()


def date_style(num_format):
    st = xlwt.XFStyle()
    st.num_format_str = num_format
    return st


def _put(ws, r, c, v, style=None):
    ws.write(r, c, v, style if style is not None else DEFAULT_STYLE)


def make_numbers(path):
    """整数型浮点、真小数、超大值、负零：老格式里全是 double，看 pandas 怎么分。"""
    book = xlwt.Workbook(encoding="utf-8")
    ws = _sheet(book, "s")
    _put(ws, 0, 0, u"序号")
    _put(ws, 0, 1, u"整值")
    _put(ws, 0, 2, u"小数")
    _put(ws, 0, 3, u"超大")
    _put(ws, 0, 4, u"负零")
    _put(ws, 1, 0, 1)
    _put(ws, 1, 1, 100.0)
    _put(ws, 1, 2, 1.5)
    _put(ws, 1, 3, 1e20)
    _put(ws, 1, 4, -0.0)
    _put(ws, 2, 0, 2)
    _put(ws, 2, 1, 7.0)
    _put(ws, 2, 2, 0.1)
    _put(ws, 2, 3, 1e25)
    _put(ws, 2, 4, 0.0)
    book.save(path)


def make_dates(path):
    """日期与时间：xlrd 要按单元格格式判成 datetime，判不出来就是浮点序列号。"""
    book = xlwt.Workbook(encoding="utf-8")
    ws = _sheet(book, "s")
    _put(ws, 0, 0, u"日期")
    _put(ws, 0, 1, u"裸序列号")
    _put(ws, 0, 2, u"文本日期")
    _put(ws, 1, 0, datetime.date(2026, 10, 13), date_style(DATE_STYLE))
    _put(ws, 1, 1, 46000.0)
    _put(ws, 1, 2, u"2026-10-13")
    _put(ws, 2, 0, datetime.datetime(2026, 10, 13, 8, 30, 0),
         date_style("DD/MM/YYYY HH:MM:SS"))
    _put(ws, 2, 1, 46000.5)
    _put(ws, 2, 2, u"")
    book.save(path)


def make_bool_blank(path):
    """布尔是独立类型；空白格、空串、缺列三种"空"要分开看。"""
    book = xlwt.Workbook(encoding="utf-8")
    ws = _sheet(book, "s")
    _put(ws, 0, 0, u"开关")
    _put(ws, 0, 1, u"空串")
    _put(ws, 0, 2, u"留空")
    _put(ws, 0, 3, u"全空列")
    _put(ws, 1, 0, True)
    _put(ws, 1, 1, u"")
    # 第 3 列整列留空：不写就是 EMPTY 格，xlwt 1.3.0 没有 write_blank
    _put(ws, 1, 2, u"")
    _put(ws, 2, 0, False)
    _put(ws, 2, 1, u"x")
    _put(ws, 2, 3, u"只有这一行有值")
    book.save(path)


def make_merged(path):
    """合并单元格：老格式的合并区要靠 xlrd 的格式化信息才读得到。"""
    book = xlwt.Workbook(encoding="utf-8")
    ws = _sheet(book, "s")
    _put(ws, 0, 0, u"序号")
    _put(ws, 0, 1, u"合并列")
    _put(ws, 1, 0, 1)
    _put(ws, 2, 0, 2)
    ws.merge(1, 2, 1, 1)
    _put(ws, 1, 1, u"跨两行")
    book.save(path)


def make_dup_header(path):
    """重复表头：pandas 会改名成 X.1，字段名跟着多一个下划线。"""
    book = xlwt.Workbook(encoding="utf-8")
    ws = _sheet(book, "s")
    _put(ws, 0, 0, u"重复名")
    _put(ws, 0, 1, u"重复名")
    _put(ws, 0, 2, u"重复名")
    _put(ws, 1, 0, u"a")
    _put(ws, 1, 1, u"b")
    _put(ws, 1, 2, u"c")
    book.save(path)


def make_text_cn(path):
    """中文表头与数字样文本：老格式字符串是内联的，不走共享字符串表。"""
    book = xlwt.Workbook(encoding="utf-8")
    ws = _sheet(book, "s")
    _put(ws, 0, 0, u"序号")
    _put(ws, 0, 1, u"北京市住房和城乡建设委员会下属单位联系电话")
    _put(ws, 1, 0, 1)
    _put(ws, 1, 1, u"010-12345678")
    _put(ws, 2, 0, 2)
    _put(ws, 2, 1, u"010-87654321")
    book.save(path)


FIXTURES = [
    (u"老格式数值（ods_t_xls_num）.xls", make_numbers),
    (u"老格式日期（ods_t_xls_date）.xls", make_dates),
    (u"老格式布尔空值（ods_t_xls_bool）.xls", make_bool_blank),
    (u"老格式合并（ods_t_xls_merge）.xls", make_merged),
    (u"老格式重复表头（ods_t_xls_dup）.xls", make_dup_header),
    (u"老格式中文（ods_t_xls_cn）.xls", make_text_cn),
]


def dump_one(path):
    """一个夹具 -> 一条金标准。

    合并单元格那条夹具本来就该被 build_table_def 拒掉，所以它没有 def 可比，
    改成把 detect_merged_cells 的结果记下来——"拒绝"本身也是要对撞的契约。
    老格式的合并区要靠 POI / xlrd 各自把格式化信息读出来，是整条 .xls 路上最容易假通过的一段。
    """
    try:
        rec = g.dump_file(path)
    except MergedCellsError as e:
        rec = {"file": os.path.basename(path), "sha256": g.sha256(path), "grid": [],
               "def": {"source": "merged", "table_name": None, "columns": [],
                       "primary_key": None}}
        rec["rejected"] = True
        del e
    rec["merged"] = {k: list(v) for k, v in detect_merged_cells(path).items()}
    return rec


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        ROOT, "java", "verify", "golden", "xls.jsonl.gz")
    if not os.path.isdir(FIX):
        os.makedirs(FIX)
    import pandas as pd
    import xlrd
    print("- [INFO] pandas = %s | xlrd = %s | xlwt = %s"
          % (pd.__version__, xlrd.__version__, xlwt.__VERSION__))
    records = []
    for name, builder in FIXTURES:
        path = os.path.join(FIX, name)
        builder(path)
        rec = dump_one(path)
        records.append(rec)
        cols = rec["def"]["columns"]
        merged = sum(len(v) for v in rec["merged"].values())
        print("- [INFO] 夹具 %s | %d 列 | 主键 %s | 来源 %s | 合并区 %d"
              % (name, len(cols), rec["def"].get("primary_key"),
                 rec["def"]["source"], merged))
    d = os.path.dirname(out)
    if not os.path.isdir(d):
        os.makedirs(d)
    with g.gzip.open(out, "wb") as gz:
        for rec in records:
            gz.write(g.json.dumps(rec, ensure_ascii=False,
                                  separators=(",", ":")).encode("utf-8") + b"\n")
    print("+ [OK] 老格式金标准 | %d 条 | %s | sha256=%s"
          % (len(records), out, g.sha256(out)[:16]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
