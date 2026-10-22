# -*- coding: utf-8 -*-
"""把已解析出的表定义导出成「带元数据 Sheet 的标准 Excel」与「只有数据的简易 Excel」。

为什么要这两种格式：
- 标准件（元数据 + 数据 两个 Sheet）是本项目结构定义表那条路能直接吃的形状，
  列名与取值口径照 北京市政府公开数据 那批交付件（防空警报试鸣信息（dim_air_raid_siren）.xlsx）：
      序号 | 字段名(英文) | 字段注释(中文) | 数据类型 | 长度 | 是否主键 | 是否可为空
  类型写基础名（VARCHAR），长度另写一格（100），读取端 _norm_meta_type 会拼回 VARCHAR(100)；
  主键与可空用「是」，空着表示否——所以生成的文件必须能被 build_table_def 原样读回来，
  这一点由 round-trip 测试当着，不靠"看起来一样"。
- 简易件只有「数据」一个 Sheet，给只要数据不要建模说明的下游。

文件命名沿用工具自己解析输入时的那套：表注释（表名）.xlsx。
"""
from __future__ import print_function

import os
import re

META_SHEET = "元数据"
DATA_SHEET = "数据"
META_HEADERS = ("序号", "字段名(英文)", "字段注释(中文)", "数据类型", "长度",
                "是否主键", "是否可为空")

# 附件里日期是文本（'2008-05-19'），不是 Excel 序列号：写回文本可以避免
# 同一份数据在两次读写之间被时区/历元换算挪走一天。
_DATE_TYPES = ("DATE", "DATETIME", "TIMESTAMP")


def split_type(t):
    """VARCHAR(64) -> ('VARCHAR', '64')；DECIMAL(10,2) -> ('DECIMAL', '10,2')。"""
    s = (t or "").strip().upper().replace("（", "(").replace("）", ")")
    m = re.match(r"^([A-Z][A-Z0-9_]*)\s*\((.*)\)$", s)
    if not m:
        return s, None
    return m.group(1), m.group(2).strip() or None


def cell_value(v):
    """一个数据单元格 -> 可安全写进 xlsx 的值。"""
    if v is None:
        return None
    try:
        import pandas as pd
        if v is pd.NA or v is pd.NaT:
            return None
        if isinstance(v, float) and pd.isna(v):
            return None
    except Exception:      # pandas 不在（只走 openpyxl 的最小环境）
        pass
    if isinstance(v, bool):
        return "是" if v else None
    if hasattr(v, "isoformat"):
        return v.isoformat(sep=" ") if hasattr(v, "hour") else v.isoformat()
    return v


def file_name(table):
    """表注释（表名）.xlsx —— 与工具解析输入时的命名同一套，注释为空就只留表名。"""
    comment = (table.get("table_comment") or "").strip()
    name = table.get("table_name") or "table"
    safe = re.sub(r'[\\/:*?"<>|]', "_", comment if comment else name)
    return "%s（%s）.xlsx" % (safe, name) if comment else "%s.xlsx" % name


def meta_rows(columns):
    """元数据 Sheet 的每一行。

    可空这一格必须显式写：读取端把"空"当成可空，要声明 NOT NULL 得写「否」。
    口径跟建表一致——主键列 NOT NULL，其余可空。普通推断出来的表本来没有这个声明
    （nullable 是 None），这里是把"建表时实际会怎么做"落成文字写进交付件，
    所以回读能稳定还原，而不是把 None 原样搬过去。
    """
    rows = []
    for i, c in enumerate(columns, 1):
        base, length = split_type(c.get("type"))
        pk = bool(c.get("primary_key"))
        rows.append((i, c.get("field") or "", c.get("comment") or "", base, length,
                     "是" if pk else None, "否" if pk else "是"))
    return rows


def write_table(table, path, with_meta):
    """写一张表。with_meta 为假时只写数据 Sheet（简易件）。"""
    from openpyxl import Workbook

    wb = Workbook()
    if with_meta:
        ws = wb.active
        ws.title = META_SHEET
        ws.append(list(META_HEADERS))
        for row in meta_rows(table.get("columns") or []):
            ws.append(list(row))
        data = wb.create_sheet(DATA_SHEET)
    else:
        data = wb.active
        data.title = DATA_SHEET

    cols = table.get("columns") or []
    df = table.get("df")
    data.append([c.get("field") or "" for c in cols])
    if df is not None:
        # 数据 Sheet 的表头写英文字段名，但取数要按 df 里真实存在的列：
        # 普通推断出来的表，df 的列头还是中文注释（field 是拼音），
        # 直接按 field 取会一列都取不到，补出来的全空列在回读时会被整行 dropna 丢掉。
        wanted = []
        for c in cols:
            field = c.get("field")
            comment = c.get("comment")
            if field in df.columns:
                wanted.append(field)
            elif comment in df.columns:
                wanted.append(comment)
            else:
                wanted.append(None)       # 元数据里有、数据里没有的列：写空
        missing = [w for w in wanted if w is not None and w not in df.columns]
        frame = df
        if missing:
            import pandas as pd
            frame = df.reindex(columns=list(df.columns) + missing,
                               fill_value=pd.NA if hasattr(pd, "NA") else None)
        for row in frame.itertuples(index=False, name=None):
            lookup = {h: v for h, v in zip(list(frame.columns), row)}
            data.append([cell_value(lookup.get(w)) if w is not None else None
                         for w in wanted])
    wb.save(path)
    return path


STD_PREFIX = "meta_data_file"
SIMPLE_PREFIX = "simple_meta_data_file"
# 「SQL 脚本改名入库」那一页的生成物另起一个目录：两页都往程序同级的同一个日期目录里写时，
# 同名表会互相覆盖，两颗按钮也只好互相等。加一个目录前缀，两边就各写各的。
SQL_DIR_TAG = "sql_"


def output_dir(out_root, with_meta, day=None, dir_tag=""):
    """日期目录名收在这里生成：标准件 meta_data_file_年月日、简易件 simple_meta_data_file_年月日。

    命名规则只写一处，界面那边就不可能拼错成两层目录。
    `dir_tag` 只用来把不同页的产物分开（SQL 页传 sql_），日期口径两边共用。
    """
    import datetime

    day = day or datetime.date.today().strftime("%Y%m%d")
    prefix = STD_PREFIX if with_meta else SIMPLE_PREFIX
    return os.path.join(out_root, "%s%s_%s" % (dir_tag, prefix, day))


def export_tables(tables, out_root, with_meta, sink=None, dir_tag=""):
    """一批表 -> `out_root/<前缀>_<年月日>/` 下的若干 xlsx。返回 (目录, 写成的文件列表)。

    进度按文件计项；写失败只记错误不中断其余，
    与导入那条路同一口径。取消在两个文件之间生效：正在写的这个写完，剩下的不再开工，
    不然收尾那句"已取消，剩余文件未生成"就是假话。
    """
    target = output_dir(out_root, with_meta, dir_tag=dir_tag)
    os.makedirs(target, exist_ok=True)
    if sink:
        sink.set_total(len(tables))
    written = []
    for t in tables:
        if sink is not None and sink.cancelled:
            break
        name = file_name(t)
        path = os.path.join(target, name)
        if sink:
            sink.log("- [INFO] 导出 | 正在写 %s" % name)
        try:
            write_table(t, path, with_meta)
        except Exception as e:
            if sink:
                sink.log_error("x [ERROR] 导出 | %s 写入失败：%s" % (name, e))
            if sink:
                sink.step(name)
            continue
        written.append(path)
        if sink:
            sink.log("+ [OK] 导出 | %s（%d 列 / %d 行）"
                     % (name, len(t.get("columns") or []),
                        len(t.get("df").index) if t.get("df") is not None else 0))
            sink.step(name)
    return target, written
