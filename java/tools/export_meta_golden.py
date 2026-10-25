# -*- coding: utf-8 -*-
"""导出「生成标准/简易 excel 文件」这条路的 Python 侧金标准。

为什么单独一份金标准：这个功能的产物是给人继续导入用的 Excel，两条线必须写出
**同一个文件内容**——文件名、Sheet 名、七列元数据、数据区逐格取值、日期落成文本
还是数字，都得对得上。只让各自回读自证的话，两条线可以各自"一致地"写出不同东西。

所以这里把 Python 线真的写出来的文件用 openpyxl 原样读回（不经过 pandas，
免得列名去重、空行裁剪这类读取语义掺进来），逐格用 encode() 编码成可比文本；
Java 侧 MetaDiffer 写同一批夹具、读自己写出的文件，再和这份金标准逐格对撞。

跑法：.venv/Scripts/python.exe java/tools/export_meta_golden.py
"""
from __future__ import print_function

import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, ROOT)
sys.path.insert(0, HERE)

import export_excel_golden as g  # noqa: E402：复用 encode/sha256，别再实现一遍

from core.infer import build_table_def            # noqa: E402
from core.merge_check import MergedCellsError     # noqa: E402
from core import meta_export as mx                # noqa: E402

FIX = g.FIX


def _pandas_number(v):
    """与 pandas 的 openpyxl 引擎同一归一：整值浮点按整数记。

    Java 侧 MetaExport.dump 也是这个规则（d == rint(d) 记成 BigInteger），
    两边都归一之后比的是"这一格存的数"，而不是 openpyxl 两种读取模式的类型偏好。
    字面量 "5" 与 "5.0" 之差因此不在这里记账——写出侧对整数走的是精确整数文本，
    类型漂移的风险由"回读定义逐字段比对"兜住。
    """
    if isinstance(v, float) and v == v and abs(v) != float("inf"):
        if float(int(v)) == v:
            return int(v)
    return v


def raw_sheets(path):
    """按文件里实际写出的格子读回：不裁空列、不改列名，与 Java 侧 dump() 同一形状。"""
    import openpyxl

    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
    out = []
    try:
        for ws in wb.worksheets:
            width = 0
            rows = []
            for row in ws.iter_rows(values_only=True):
                vals = [_pandas_number(v) for v in row]
                while vals and (vals[-1] is None or vals[-1] == ""):
                    del vals[-1]
                width = max(width, len(vals))
                rows.append(vals)
            while rows and not rows[-1]:
                del rows[-1]
            for vals in rows:
                while len(vals) < width:
                    vals.append(None)
            out.append({"name": ws.title,
                        "rows": [[g.encode(v) for v in vals] for vals in rows]})
    finally:
        wb.close()
    return out


def slim_def(tdef):
    cols = []
    for c in tdef["columns"]:
        cols.append({"field": c.get("field"), "comment": c.get("comment"),
                     "type": c.get("type"), "primary_key": bool(c.get("primary_key")),
                     "nullable": c.get("nullable")})
    return {"table_name": tdef.get("table_name"),
            "base_table_name": tdef.get("base_table_name"),
            "table_comment": tdef.get("table_comment"),
            "source": tdef.get("source"),
            "primary_key": tdef.get("primary_key"),
            "pk_reason": tdef.get("pk_reason"),
            "columns": cols,
            "row_count": len(tdef["df"].index) if tdef.get("df") is not None else 0}


def dump_one(src, table, work, with_meta):
    kind = "std" if with_meta else "simple"
    out_root = os.path.join(work, kind, os.path.basename(src))
    os.makedirs(out_root)
    target, written = mx.export_tables([table], out_root, with_meta)
    if len(written) != 1:
        raise RuntimeError("%s 没写出文件（%s）" % (src, kind))
    gen = written[0]
    dir_name = os.path.basename(target)
    prefix = dir_name.rsplit("_", 1)[0]
    return {
        "src": os.path.basename(src),
        "kind": kind,
        "file": mx.file_name(table),
        "dir_prefix": prefix,
        "def": slim_def(table),
        "sheets": raw_sheets(gen),
        "back": slim_def(build_table_def(gen)),
    }


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        ROOT, "java", "verify", "golden", "meta.jsonl.gz")
    files = sorted(f for f in os.listdir(FIX) if f.lower().endswith((".xlsx", ".xls")))
    # 同上：一千多列的词表全量夹具走的是类型线，元数据导出这一路不吃它
    files = [f for f in files if u"词表全量" not in f]
    work = tempfile.mkdtemp(prefix="e2m-meta-golden-")
    records = []
    skipped = 0
    try:
        for name in files:
            src = os.path.join(FIX, name)
            try:
                table = build_table_def(src)
            except MergedCellsError:
                skipped += 1     # 含合并单元的夹具本来就该被拒，没有导出物可比
                continue
            records.append(dump_one(src, table, work, True))
            records.append(dump_one(src, table, work, False))
    finally:
        shutil.rmtree(work, ignore_errors=True)

    d = os.path.dirname(out)
    if not os.path.isdir(d):
        os.makedirs(d)
    import gzip
    import json
    with gzip.open(out, "wb") as gz:
        for rec in records:
            gz.write(json.dumps(rec, ensure_ascii=False,
                                separators=(",", ":")).encode("utf-8") + b"\n")
    print("- [INFO] 夹具 %d 个（跳过含合并单元 %d 个）| 记录 %d 条"
          % (len(files), skipped, len(records)))
    print("+ [OK] 导出生成金标准 | %s | sha256=%s" % (out, g.sha256(out)[:16]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
