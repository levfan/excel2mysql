"""打包完整性与全功能用例集。

用途：证明"开发态能跑"与"打包后能跑"是同一件事。用 packtest.spec 以与
excel2mysql.spec 完全相同的 excludes/hiddenimports 口径冻结成控制台 exe 后运行，
任何一项失败即退出码非 0。

覆盖：
  A 依赖与静态资源    B Excel 解析与类型推断    C 主键
  D DDL 生成          E SQL 脚本处理            F GUI 构造与交互逻辑
  H ZIP 压缩包导入    I .xls 老格式（xlrd）     J 添加结果弹窗
  K 高度可拖拽(两页)  L 字段类型映射            M 字段命名规则
  N 合并表头展开/其余拒绝  O 模态进度框与子线程执行
  P 使用说明          Q 全部按钮入口与事务副作用（假连接实测）
  R 替换首个前缀
  G MySQL 真实读写（无凭据时显式记为 SKIPPED）

运行：
  开发态   python pack_test.py
  冻结态   dist\\packtest\\packtest.exe
"""
import collections
import glob
import io
import os
import re
import sys
import tempfile
import traceback
import time as _t_busy

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

# 输出被重定向到文件/管道时 Python 会退回系统 ANSI 码页，✓/✗ 与中文会抛
# UnicodeEncodeError；显式改成 UTF-8，使本脚本无论怎么调用都能跑。
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

RESULTS = {"pass": 0, "fail": 0, "skip": 0}
FAILURES = []
SKIPPED = []


def check(label, cond, detail=""):
    ok = bool(cond)
    RESULTS["pass" if ok else "fail"] += 1
    if not ok:
        FAILURES.append(label)
    print(("  OK   " if ok else "  FAIL ") + label + ("  | " + str(detail) if detail else ""))
    return ok


def skip(label, why):
    RESULTS["skip"] += 1
    SKIPPED.append((label, why))
    print(f"  SKIP {label}  | {why}")


SUMMARY_PRINTED = False


def _crash_report():
    """脚本中途抛异常时也要把已跑到的计数交出来，别让一次崩溃丢掉整轮结果。"""
    if not SUMMARY_PRINTED:
        print("\n" + "=" * 72)
        print(f"用例脚本异常中止：已跑 通过 {RESULTS['pass']}  失败 {RESULTS['fail']}  "
              f"跳过 {RESULTS['skip']}")
        if FAILURES:
            print("失败用例：")
            for f in FAILURES:
                print("  - " + f)
        print("=" * 72)


def _qt_teardown():
    """把用例留下的顶层窗口交还给还活着的 QApplication。

    上面几十处 `MainWindow()` 都故意不关（每次点按钮都要一个新鲜对象），但解释器清理模块时
    "QApplication 与窗口谁先析构"不由人定：Qt 对象排在 app 之后销毁会踩已释放的窗口资源，
    整轮跑完照样红，而是不是红全看内存布局（界面上多两颗按钮就能翻脸），等于一门没有判据的闸。
    注册在 atexit 上，所以中途抛异常那一条路也走同一道收尾，退出码只由用例结果决定。
    """
    try:
        from PySide6.QtCore import QEvent
        from PySide6.QtWidgets import QApplication
        app = QApplication.instance()
        tops = list(QApplication.topLevelWidgets()) if app else []
    except Exception:
        return
    for t in tops:
        try:
            t.close()
            t.setParent(None)
            t.deleteLater()
        except Exception:      # C++ 侧已经删掉的包装器：跳过就是，别把收尾本身搞崩
            pass
    if app is None:
        return
    app.processEvents()
    app.sendPostedEvents(None, QEvent.DeferredDelete)
    app.processEvents()


import atexit
atexit.register(_crash_report)
atexit.register(_qt_teardown)


def section(title):
    print(f"\n----- {title} -----")


def main_source():
    """读 main.py 源码用于文案静态检查；冻结态不含 .py 时返回 None。"""
    p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "main.py")
    return open(p, encoding="utf-8").read() if os.path.isfile(p) else None


def box_title(widget):
    """这张表格左上角那句标识：从真控件往上找到那层组框，读它自己的标题。

    读真控件而不是抄常量：抄来的那份改了，另一份不会红（对撞器同一口径）。
    """
    cur = widget
    while cur is not None and not isinstance(cur, app_main.QGroupBox):
        cur = cur.parentWidget()
    return cur.title() if cur is not None else ""


# ---------------------------------------------------------------------------
section("A 依赖与静态资源（打包缺库/缺资源直接在这里暴露）")

import pandas as pd
import numpy as np
import pymysql
import openpyxl
import xlrd
import et_xmlfile                      # openpyxl 运行时依赖，最容易被静态分析漏掉
from pypinyin import lazy_pinyin       # 触发 pinyin_dict.json 懒加载
import tzdata                          # pandas 在 Windows 上取时区靠它
from datetime import datetime, date

check("A1 运行时三方库全部可导入", True,
      f"pandas={pd.__version__} numpy={np.__version__} pymysql={pymysql.__version__} "
      f"openpyxl={openpyxl.__version__} xlrd={xlrd.__version__}")
check("A2 pypinyin 词典可用（懒加载 JSON 可读）",
      lazy_pinyin("水库") == ["shui", "ku"], lazy_pinyin("水库"))
check("A3 pandas 时区数据可用（tzdata 落包）",
      str(pd.Timestamp("2026-01-01").tz_localize("Asia/Shanghai").tz) == "Asia/Shanghai")
# MySQL 8 的 caching_sha2_password 在服务端缓存失效后的第一个连接要走 full auth，
# 那一步要 RSA。pymysql 在 import 时就算好了有没有 cryptography，缺包不会让导入报错，
# 只会在现场连不上库，所以在这里钉住。
import pymysql._auth as _pymysql_auth
check("A3b cryptography 在场（否则 MySQL 8 重启后首个连接连不上）",
      _pymysql_auth._have_cryptography,
      f"_have_cryptography={_pymysql_auth._have_cryptography}")

import main as app_main

# QMessageBox 是模态的，离屏测试里会把进程挂死；换成记录弹窗内容的桩。
# 桩同时支持旧的静态方法和新的 build_popup_box 实例接口。
POPUPS = []
_REAL_POPUP_BOX = app_main.PopupMessageBox


class _RecDialog:
    def __init__(self, parent=None, *a, **k):
        self.parent = parent
        self._title = ""
        self._text = ""

    def setWindowTitle(self, title):
        self._title = title

    def setText(self, text):
        self._text = text

    def setIcon(self, icon):
        pass

    def setStandardButtons(self, buttons):
        pass

    def findChildren(self, cls):
        return []

    def exec(self):
        POPUPS.append((self._title, self._text))
        return 0

    @staticmethod
    def information(parent, title, text, *a, **k):
        POPUPS.append((title, text))
        return None

    warning = critical = question = about = information


app_main.QMessageBox = _RecDialog
app_main._POPUP_MESSAGE_BOX = _RecDialog
# 进度框同样是模态的，离屏用例里 exec() 会挂住进程。关掉它之后长任务原地跑完，
# 用的还是同一个工作函数和同一个回报接口，所以断言依然对着线上那段逻辑。
app_main.MODAL_PROGRESS = False
PROGRESS_UPDATES = []          # 异步用例记录每次进度刷新
from core.infer import (
    build_table_def, make_unique_names, apply_table_prefix, validate_primary_key,
    select_primary_key, infer_columns, sanitize_table_name,
    parse_table_name_and_comment, _varchar_tier,
)
from core.sql_script import (
    extract_create_tables, apply_table_prefix_sql, split_sql_statements,
    has_executable_content as has_exec, prepend_drop_tables,
)
from core.sql_execute import executable_statements
from core.mysql_io import (
    build_ddl, build_insert_sql, connect, list_databases, create_table, insert_data,
)

check("A4 应用自身模块全部可导入", True, "main + core.infer/mysql_io/sql_script")

ico = app_main.resource_path(os.path.join("assets", "app_icon.ico"))
check("A5 静态资源 app_icon.ico 在包内", os.path.exists(ico), ico)

from PySide6.QtWidgets import QApplication
from PySide6.QtGui import QIcon
import PySide6

_app = QApplication.instance() or QApplication(sys.argv)
icon = QIcon(ico)
check("A6 Qt 平台插件可用（能建 QApplication）", _app is not None, f"PySide6 {PySide6.__version__}")
check("A7 图标资源能被 Qt 解码且非空", not icon.isNull() and bool(icon.availableSizes()),
      f"sizes={sorted(str(s) for s in icon.availableSizes())[:4]}")

_tmpdir = tempfile.mkdtemp(prefix="packtest_")
# 用例不依赖本机环境里有没有 E2M_*：这些变量会让图形版一开窗就自动连真库，
# 把"没配就不连"的断言变成随机结果。Q53c 与 G10 自己设、自己删。
for _ek in [k for k in os.environ if k.startswith("E2M_")]:
    del os.environ[_ek]


def make_xlsx(name, sheets):
    """sheets: {sheet名: DataFrame}"""
    p = os.path.join(_tmpdir, name)
    with pd.ExcelWriter(p, engine="openpyxl") as xw:
        for sn, df in sheets.items():
            df.to_excel(xw, sheet_name=sn, index=False)
    return p


probe = make_xlsx("probe.xlsx", {"S": pd.DataFrame({"甲": [1, 2]})})
check("A8 openpyxl 写 xlsx 成功", os.path.getsize(probe) > 0)
check("A9 pandas 回读 xlsx 成功",
      list(pd.read_excel(probe)["甲"]) == [1, 2])


# ---------------------------------------------------------------------------
section("B Excel 解析与类型推断")

p1 = make_xlsx("水库基础信息.xlsx", {"Sheet1": pd.DataFrame({
    "水库编号": ["SK001", "SK002", "SK003"],
    "水库名称": ["密云水库", "官厅水库", "潘家口水库"],
    "总库容（亿立方米）": [4.375, 4.160, 2.930],
    "建成年份": [1960, 1954, 1979],
    "水位日期": ["2023-05-01", "2023-06-02", "2023-07-03"],
})})
t1 = build_table_def(p1)
cmt = {c["comment"]: c["type"] for c in t1["columns"]}
check("B1 普通表：文件名汉字转拼音作表名",
      t1["table_name"] == "shui_ku_ji_chu_xin_xi", t1["table_name"])
check("B2 普通表：表注释=文件名", t1["table_comment"] == "水库基础信息", t1["table_comment"])
check("B3 字段名全 ASCII 拼音", all(c["field"].isascii() for c in t1["columns"]),
      [c["field"] for c in t1["columns"]])
check("B4 字段注释保留中文表头", len(cmt) == 5 and "水库编号" in cmt)
check("B5 整数列→INT", cmt["建成年份"] == "INT", cmt["建成年份"])
check("B6 小数列按实测精度定 DECIMAL（不再硬编码 20,6）",
      cmt["总库容（亿立方米）"] == "DECIMAL(7,3)", cmt["总库容（亿立方米）"])
check("B7 日期串→DATE", cmt["水位日期"] == "DATE", cmt["水位日期"])
check("B8 前导零编码列保持 VARCHAR（不丢零）",
      cmt["水库编号"].startswith("VARCHAR"), cmt["水库编号"])

p2 = make_xlsx("北京市水务局-水库基础信息（dim_reservoir_info）.xlsx",
               {"S": pd.DataFrame({"编号": [1, 2]})})
t2 = build_table_def(p2)
check("B9 括号内英文作表名", t2["table_name"] == "dim_reservoir_info", t2["table_name"])
check("B10 括号前中文作表注释", t2["table_comment"] == "北京市水务局-水库基础信息",
      t2["table_comment"])

meta = pd.DataFrame({
    "序号": [1, 2, 3, 4],
    "字段名(英文)": ["id", "name", "amount", "remark"],
    "字段注释(中文)": ["主键ID", "名称", "金额", "备注"],
    "数据类型": ["INT", "VARCHAR（50）", "DECIMAL", ""],
    "长度": ["", "", "10,2", ""],
    "是否主键": ["是", "", "", ""],
    "是否可为空": ["否", "否", "", ""],
})
data = pd.DataFrame({"id": [1, 2], "name": ["甲", "乙"], "amount": [1.5, 2.25],
                     "多余列": ["x", "y"]})
p3 = make_xlsx("结构定义（t_meta）.xlsx", {"数据": data, "字段定义": meta})
t3 = build_table_def(p3)
m3 = {c["field"]: c for c in t3["columns"]}
check("B11 结构定义表被识别（元数据 Sheet 不在首位也能找到）",
      t3.get("source") == "structured" and t3.get("meta_sheet") == "字段定义",
      f"source={t3.get('source')} meta={t3.get('meta_sheet')} data={t3.get('data_sheet')}")
check("B12 类型列可直接写 VARCHAR(50)（中文括号也识别）",
      m3["name"]["type"] == "VARCHAR(50)", m3["name"]["type"])
check("B13 长度 10,2 → DECIMAL(10,2)", m3["amount"]["type"] == "DECIMAL(10,2)",
      m3["amount"]["type"])
check("B14 缺类型定义的行被跳过", "remark" not in m3, list(m3))
check("B15 是否主键=是 → primary_key", [c["field"] for c in t3["columns"] if c["primary_key"]] == ["id"])
check("B16 是否可为空=否 → nullable False",
      m3["id"]["nullable"] is False and m3["name"]["nullable"] is False
      and m3["amount"]["nullable"] is True)
check("B17 数据 Sheet 多余列被忽略并记录", t3["extra_data_columns"] == ["多余列"],
      t3["extra_data_columns"])
check("B18 数据列按字段名对齐到元数据顺序",
      list(t3["df"].columns) == ["id", "name", "amount"], list(t3["df"].columns))

p4 = make_xlsx("空数据定义（t_empty）.xlsx", {"字段定义": meta.iloc[:2]})
t4 = build_table_def(p4)
check("B19 只有定义没有数据 → 建空表且保留列定义",
      len(t4["df"]) == 0 and len(t4["columns"]) == 2,
      f"rows={len(t4['df'])} cols={len(t4['columns'])}")

p5 = make_xlsx("空行空列.xlsx", {"S": pd.DataFrame({
    "甲": [1, 2, None], "乙": ["a", "b", None], "丙": [None, None, None]})})
t5 = build_table_def(p5)
check("B20 表头写了字、整列没值的字段保留（采集模板里必填项还没填就是这个形状）",
      "丙" in [c["comment"] for c in t5["columns"]] and t5["empty_columns"] == ["丙"],
      ([c["comment"] for c in t5["columns"]], t5["empty_columns"]))
p5b = make_xlsx("尾部空列.xlsx", {"S": pd.DataFrame({
    "甲": [1, 2], "乙": ["a", "b"], None: [None, None]})})
t5b = build_table_def(p5b)
check("B20b 表头没写字且整列没值的格式化垃圾列照旧丢掉",
      len(t5b["columns"]) == 2 and t5b["empty_columns"] == [],
      ([c["comment"] for c in t5b["columns"]], t5b["empty_columns"]))
check("B21 全空行被剔除", len(t5["df"]) == 2, len(t5["df"]))
p5c = make_xlsx("采集模板（t_tpl）.xlsx", {"S": pd.DataFrame({
    "*标准名称": [], "*标准级别": [], "发布日期": []})})
t5c = build_table_def(p5c)
check("B21b 只有表头的采集模板不再被拒：三个字段都在、0 行、不自动设主键",
      [c["field"] for c in t5c["columns"]]
      == ["biao_zhun_ming_cheng", "biao_zhun_ji_bie", "fa_bu_ri_qi"]
      and [c["comment"] for c in t5c["columns"]] == [u"*标准名称", u"*标准级别", u"发布日期"]
      and len(t5c["df"]) == 0 and t5c["no_data_rows"] and t5c["primary_key"] is None,
      ([(c["field"], c["comment"]) for c in t5c["columns"]], t5c["primary_key"],
       t5c["pk_reason"]))
check("B21c 星号只是必填标记：不进字段名，但原样留在字段注释里",
      all("*" not in c["field"] for c in t5c["columns"])
      and t5c["columns"][0]["comment"].startswith("*")
      and t5c["empty_columns"] == [u"*标准名称", u"*标准级别", u"发布日期"],
      t5c["empty_columns"])

# 表头整行都没写字：pandas 连列宽一起丢掉（实测 columns 剩 0），
# 但"这张表有几列"文件里还记着 —— 这种也要能导入，只是没有数据可插（用户 2026-10-20 第 3 件）
_pblank = os.path.join(_tmpdir, u"空表头模板（t_blankhead）.xlsx")
_wb = openpyxl.Workbook()
_ws = _wb.active
_ws.title = "数据"
_ws.append(["", "", ""])          # 三个格子都在，只是都没写字
_wb.save(_pblank)
try:
    t6 = build_table_def(_pblank)
    t6_fields = [c["field"] for c in t6["columns"]]
    t6_comments = [c["comment"] for c in t6["columns"]]
except ValueError as exc:
    # 兜底没生效时会走到"没有解析到任何字段"那条拒绝，那是一句异常；
    # 让它折成断言失败，别让一条用例抛异常把整轮中止、盖住是谁坏了
    t6, t6_fields, t6_comments = None, [], []
    t6_error = str(exc)
else:
    t6_error = ""
check("B21d 表头整行空着也能出表定义：按列宽兜底成 col、col_2、col_3，0 行",
      t6 is not None and t6_fields == ["col", "col_2", "col_3"]
      and len(t6["df"]) == 0 and t6["no_data_rows"] and t6["primary_key"] is None,
      (t6_fields, t6_error))
check("B21e 兜底出来的列注释是空串，不拿 Unnamed 之类的占位名糊使用者",
      t6 is not None and t6_comments == ["", "", ""], t6_comments)
check("B21h 兜底名单随表定义带出去，日志才点得出名",
      t6 is not None and t6["blank_headers"] == ["col", "col_2", "col_3"],
      t6 is not None and t6["blank_headers"])
_ptrue = os.path.join(_tmpdir, u"一格都没有（t_trulyempty）.xlsx")
_wb = openpyxl.Workbook()
_wb.active.title = "数据"
_wb.save(_ptrue)
try:
    build_table_def(_ptrue)
    _truly = None
except ValueError as exc:
    _truly = str(exc)
check("B21f 连一个格子都没有的真空表照旧拒绝（建出来是 CREATE TABLE x ( )，非法）",
      _truly is not None and "没有解析到任何字段" in _truly, _truly)
check("B21g 表头部分空着、那一列又整列没值：仍按垃圾列丢掉，其余照旧（不是全都空）",
      len(t5b["columns"]) == 2, len(t5b["columns"]))

# 表里没有数据时字段名本身就写着口径：按关键字定类型，不再一律 VARCHAR(64)
_empty_cols = infer_columns(pd.DataFrame({
    "发布日期": [], "更新时间": [], "订单金额": [], "数量": [], "备注说明": []}))
check("B22 空列按字段名关键字定类型：日期/时间/金额/数量各归各位",
      [c["type"] for c in _empty_cols]
      == ["DATE", "DATETIME", "DECIMAL(16,2)", "INT", "VARCHAR(64)"],
      [c["type"] for c in _empty_cols])
check("B22b 每个按名定的类型都带留痕文案，没命中的不带",
      all(c.get("type_note") for c in _empty_cols[:4])
      and not _empty_cols[4].get("type_note"),
      [c.get("type_note") for c in _empty_cols])
_mixed = infer_columns(pd.DataFrame({"数量": ["a", "b"], "发布日期": ["2020-01-01",
                                                                     "2020-01-02"]}))
check("B22c 有值时照旧数据判定，按名规则不参与",
      _mixed[0]["type"].startswith("VARCHAR") and _mixed[1]["type"] == "DATE"
      and not _mixed[0].get("type_note"),
      [(c["type"], c.get("type_note")) for c in _mixed])
_en = infer_columns(pd.DataFrame({"create_date": [], "update": [], "qty": [],
                                  "birthday": []}))
check("B22d 英文按成词匹配：create_date/qty 命中，update/birthday 不误中",
      [c["type"] for c in _en] == ["DATE", "VARCHAR(64)", "INT", "VARCHAR(64)"],
      [c["type"] for c in _en])
_kinds = infer_columns(pd.DataFrame({
    "年龄": [], "工龄": [], "系数": [], "毛利率": [], "评分": [], "经度": [],
    "长度": [], "数量": [], "序号": [], "年份": [], "是否有效": [],
    "备注说明": []}))
check("B22f 空列中文词表扩展：年龄/系数/评分/经纬度/计量/序号/年份/布尔各归各位",
      [c["type"] for c in _kinds]
      == ["INT", "INT", "DECIMAL(18,6)", "DECIMAL(18,6)", "DECIMAL(10,2)",
          "DECIMAL(18,8)", "DECIMAL(18,4)", "INT", "INT", "INT", "TINYINT(1)",
          "VARCHAR(64)"],
      [c["type"] for c in _kinds])
check("B22g 新分类留痕点名具体口径，没命中的不带文案",
      all(c.get("type_note") for c in _kinds[:11]) and not _kinds[11].get("type_note")
      and "比率/系数" in _kinds[2]["type_note"] and "评分" in _kinds[4]["type_note"]
      and "经纬度/坐标" in _kinds[5]["type_note"] and "计量" in _kinds[6]["type_note"]
      and "布尔" in _kinds[10]["type_note"],
      [c.get("type_note") for c in _kinds])
_priority = infer_columns(pd.DataFrame({
    "频率": [], "功率": [], "速率": [], "容积率": [], "毛利率": [], "订单金额": []}))
check("B22h 词表优先级：频率/功率/速率走计量，容积率/毛利率走比率，订单金额仍归金额",
      [c["type"] for c in _priority]
      == ["DECIMAL(18,4)", "DECIMAL(18,4)", "DECIMAL(18,4)", "DECIMAL(18,6)",
          "DECIMAL(18,6)", "DECIMAL(16,2)"],
      [c["type"] for c in _priority])
_en2 = infer_columns(pd.DataFrame({k: [] for k in (
    "age", "rate", "score", "longitude", "length", "is_deleted", "sort_no", "year",
    "update", "birthday", "remark", "status")}))
check("B22i 英文词表扩展且成词匹配：age/rate/score/longitude/length/is_/sort/year 命中，update/status 不误中",
      [c["type"] for c in _en2]
      == ["INT", "DECIMAL(18,6)", "DECIMAL(10,2)", "DECIMAL(18,8)",
          "DECIMAL(18,4)", "TINYINT(1)", "INT", "INT",
          "VARCHAR(64)", "VARCHAR(64)", "VARCHAR(64)", "VARCHAR(64)"],
      [(c["comment"], c["type"]) for c in _en2])
_valued = infer_columns(pd.DataFrame({"年龄": ["1", "2"], "系数": ["1.25", "2.5"]}))
check("B22j 有值时新词表同样让位数据判定，不套按名文案",
      _valued[0]["type"] == "INT" and _valued[1]["type"].startswith("DECIMAL")
      and not any(c.get("type_note") for c in _valued),
      [(c["type"], c.get("type_note")) for c in _valued])
_meta_empty = pd.DataFrame({
    "序号": [1],
    "字段名(英文)": ["publish_date"],
    "字段注释(中文)": ["发布日期"],
    "数据类型": ["VARCHAR（64）"],
    "长度": [""],
    "是否主键": [""],
    "是否可为空": ["是"],
})
_pe = make_xlsx("空定义（t_meta_empty）.xlsx", {"字段定义": _meta_empty})
_te = build_table_def(_pe)
check("B22e 结构定义模式人工类型优先：叫发布日期也不改成 DATE",
      len(_te["columns"]) == 1 and _te["columns"][0]["type"] == "VARCHAR(64)"
      and not _te["columns"][0].get("type_note"),
      [(c["field"], c["type"], c.get("type_note")) for c in _te["columns"]])

df_big = pd.DataFrame({"超长文本": ["啊" * 5000]})           # 15000 字节 > 16383 档位上限
check("B22 超 VARCHAR 档位上限的文本列 → TEXT", infer_columns(df_big)[0]["type"] == "TEXT",
      infer_columns(df_big)[0]["type"])
df_huge = pd.DataFrame({"超长文本": ["啊" * 20000]})         # 60000 字节，含余量超 65535
check("B23 超 65535 字节 → MEDIUMTEXT", infer_columns(df_huge)[0]["type"] == "MEDIUMTEXT",
      infer_columns(df_huge)[0]["type"])
wide = pd.DataFrame({**{f"短列{i}": ["abcdefgh"] for i in range(2)},
                     **{f"长列{i}": ["啊" * 4000] for i in range(6)}})
wt = infer_columns(wide)
kinds = [c["type"] for c in wt]
check("B24 VARCHAR 行内预算不足时退为 TEXT（保证建表不报 Row size too large）",
      kinds.count("TEXT") == 6 and all(k.startswith("VARCHAR") for k in kinds[:2]),
      f"前 2 列={kinds[:2]} 后 6 列={kinds[2:]}")
check("B25 8 位纯数字编码列按数值处理不误判 DATE",
      infer_columns(pd.DataFrame({"日期编码": [20230101, 20230102]}))[0]["type"] == "INT",
      infer_columns(pd.DataFrame({"日期编码": [20230101]}))[0]["type"])
check("B26 含时间的字符串 → DATETIME",
      infer_columns(pd.DataFrame({"时间": ["2026-01-01 10:20:30"] }))[0]["type"] == "DATETIME")
check("B27 INT 溢出 → BIGINT",
      infer_columns(pd.DataFrame({"大数": [9999999999, 1111111111]}))[0]["type"] == "BIGINT",
      infer_columns(pd.DataFrame({"大数": [9999999999]}))[0]["type"])
check("B27b 浮点整数超 INT → BIGINT（2.2e9 不能被 int(str(v)) 漏判）",
      infer_columns(pd.DataFrame({"单据号": [1.0, 2200000000.0]}))[0]["type"] == "BIGINT",
      infer_columns(pd.DataFrame({"单据号": [1.0, 2200000000.0]}))[0]["type"])
check("B27c 整数超 BIGINT → DECIMAL(n,0)，不是装不下的 BIGINT",
      infer_columns(pd.DataFrame({"大数": [9.3e18]}))[0]["type"] == "DECIMAL(21,0)",
      infer_columns(pd.DataFrame({"大数": [9.3e18]}))[0]["type"])
_big70 = infer_columns(pd.DataFrame({"大数": ["9" * 70]}))[0]
check("B27d 超过 DECIMAL 65 位的整数退文本并写明原因",
      _big70["type"].startswith("VARCHAR") and "DECIMAL" in _big70.get("type_note", ""),
      (_big70["type"], _big70.get("type_note")))
check("B28 表名全拼超 64 字符时整名降级为首字母（仿字段规则，每个汉字都还在名字里）",
      sanitize_table_name("北京市海淀区水务局下属大型水库枢纽工程基础信息汇总表")
      == "bjshdqswjxsdxsksngcjcxxhzb"
      and len(sanitize_table_name("北京市海淀区水务局下属大型水库枢纽工程基础信息汇总表")) <= 64,
      sanitize_table_name("北京市海淀区水务局下属大型水库枢纽工程基础信息汇总表"))
dupcols = pd.DataFrame([["a", "b", "c"]], columns=["名称", "名称", "名称"])
dfields = [c["field"] for c in infer_columns(dupcols)]
check("B29 重名字段自动加 _2/_3", dfields == ["ming_cheng", "ming_cheng_2", "ming_cheng_3"],
      dfields)
numstart = pd.DataFrame([["1"]], columns=["7号线"])
nf = infer_columns(numstart)[0]["field"]
check("B29b 字段名首字符为数字时补 f_ 前缀", not nf[0].isdigit(), nf)
dup = [{"table_name": n, "base_table_name": n, "columns": []} for n in ("users",) * 3]
check("B30 重名表名去重", [t["table_name"] for t in make_unique_names(dup)]
      == ["users", "users_2", "users_3"])
pre = [{"table_name": "reservoir", "base_table_name": "reservoir", "columns": []}]
apply_table_prefix(pre, "ods_")
first = pre[0]["table_name"]
apply_table_prefix(pre, "dim_")
second = pre[0]["table_name"]
apply_table_prefix(pre, "")
check("B31 表名前缀：应用/切换不叠加/清空可移除",
      (first, second, pre[0]["table_name"]) == ("ods_reservoir", "dim_reservoir", "reservoir"),
      f"{first} → {second} → {pre[0]['table_name']}")

sql_text = ("DROP TABLE IF EXISTS `t1`;\n"
            "CREATE TABLE IF NOT EXISTS `t1` (`id` INT);\n"
            "INSERT INTO t1 VALUES (1);\n"
            "CREATE TABLE `t1` (`id` INT);\n"
            "CREATE TABLE schema2.t2 (id INT);  -- 注释里有分号;\n"
            "INSERT INTO `t2` VALUES ('a;b');\n")
tabs = extract_create_tables(sql_text)
check("E1 提取 CREATE TABLE 表名（反引号/裸名/schema 限定，忽略 INSERT/DROP）",
      [t["name"] for t in tabs] == ["t1", "t2"], [t["name"] for t in tabs])
check("E2 同名表按 CREATE TABLE 出现次数统计", tabs[0]["count"] == 2, tabs[0]["count"])
new_text, renamed, skipped_l = apply_table_prefix_sql(sql_text, "ods_")
check("E3 加前缀：反引号引用与语句头裸名全部改写",
      "DROP TABLE IF EXISTS `ods_t1`" in new_text
      and "CREATE TABLE IF NOT EXISTS `ods_t1`" in new_text
      and "INSERT INTO ods_t1 VALUES" in new_text
      and "INSERT INTO `ods_t2`" in new_text,
      [f"{r['old']}→{r['new']} x{r['n']}" for r in renamed])
check("E4 字符串内的分号与内容不被误伤", "('a;b')" in new_text)
check("E4b schema 限定的裸表名不改写（文档化行为，需反引号写法）",
      "CREATE TABLE schema2.t2" in new_text)
again, _, skipped2 = apply_table_prefix_sql(new_text, "ods_")
check("E5 重复应用前缀不叠加（文本不变，已带前缀/未命中的都进 skipped）",
      again == new_text and skipped2 == ["ods_t1", "t2"], skipped2)
stmts = split_sql_statements(sql_text)
check("E6 语句切分正确跳过注释/字符串中的分号", len(stmts) == 6, len(stmts))
check("E7 纯分号不产生语句",
      all(s.lstrip().startswith("--") for s in split_sql_statements(";;  ;\n-- x\n")),
      split_sql_statements(";;  ;\n-- x\n"))
check("E8 反引号标识符内的分号不切", len(split_sql_statements("CREATE TABLE `a;b` (x INT);")) == 1)
check("E9 块注释内的分号不切",
      len(split_sql_statements("/* a; b; */ SELECT 1;")) == 1)
# 「只写注释也不算」这句文案的判据表。Java 侧 GuiMain 用同一张表断
# SqlScript.hasExecutableContent，两边判定不一样就会出现"一能说得出执行了 0 条、
# 另一边说执行了 1 条"。字符串里的 -- 不算注释，这条是最容易写错的一格。
_EXEC_TABLE = [
    ("-- 只是一句注释", False),
    ("# 老式行注释", False),
    ("/* 纯块注释 */", False),
    ("", False),
    ("   \n\t ", False),
    ("SELECT 1", True),
    ("SELECT '-- x'", True),
    ("SELECT `--a` FROM t", True),
    ("/* 头注释 */ SELECT 2", True),
    ("-- 头注释\nINSERT INTO t VALUES (1)", True),
    ("SELECT 'a''b' -- 尾注释", True),
    ("INSERT INTO t VALUES ('/* 不是块注释 */')", True),
    ("/* 注释; 里有分号 */ ", False),
]
check("E10 整句只剩注释的片段判为无可执行内容（字符串里的 -- 不算注释）",
      [(s, has_exec(s)) for s, _ in _EXEC_TABLE] == [(s, want) for s, want in _EXEC_TABLE],
      [(s, has_exec(s), want) for s, want in _EXEC_TABLE if has_exec(s) != want])
check("E11 执行器把纯注释整批剔掉：一句都不执行，也不报「已提交 1 条」",
      executable_statements("-- a\n; /* b */ ; SELECT 1;") == ["SELECT 1"],
      executable_statements("-- a\n; /* b */ ; SELECT 1;"))

# 覆盖执行：只认整句 CREATE TABLE，注释/字符串里的建表不触发删除；
# schema 限定名整段保留，同名去重，DROP 按出现顺序倒序排在执行副本最前
_drop_src = ("-- CREATE TABLE ghost (id INT);\n"
             "CREATE TABLE `db`.`t1` (id INT);\n"
             "create table if not exists t2 (id INT);\n"
             "CREATE TABLE t1 (id INT);\n"
             "INSERT INTO t1 VALUES ('CREATE TABLE nope (id INT);');\n")
_drop_text, _drop_names = prepend_drop_tables(_drop_src)
check("E12 覆盖执行只给整句 CREATE TABLE 的建表目标补 DROP",
      _drop_names == ["`db`.`t1`", "t2", "t1"]
      and _drop_text == ("DROP TABLE IF EXISTS t1;\n"
                         "DROP TABLE IF EXISTS t2;\n"
                         "DROP TABLE IF EXISTS `db`.`t1`;\n\n") + _drop_src,
      (_drop_names, _drop_text.splitlines()[:4]))
check("E13 没有建表语句时执行副本原样返回（不凭空多出 DROP）",
      prepend_drop_tables("-- only comment\nSELECT 1;\n") == ("-- only comment\nSELECT 1;\n", []))
check("E14 注释里的建表不会被当成删除目标",
      prepend_drop_tables("/* CREATE TABLE ghost (id INT); */ SELECT 1;")[1] == [])

gbk = os.path.join(_tmpdir, "gbk.sql")
open(gbk, "wb").write("CREATE TABLE `t` (`id` INT); -- 中文注释".encode("gbk"))
check("B32 GBK 编码脚本可读取", "CREATE TABLE" in app_main.read_sql_text(gbk))
bom = os.path.join(_tmpdir, "bom.sql")
open(bom, "wb").write(b"\xef\xbb\xbf" + "SET NAMES utf8mb4;".encode("utf-8"))
check("B33 UTF-8 BOM 脚本可读取（BOM 不残留）",
      app_main.read_sql_text(bom).startswith("SET NAMES"))

# 真实 dump 里会夹着原样导出的二进制字段：整份不再是合法 UTF-8，但正文里的中文
# 仍是 UTF-8 编的。这时必须按 UTF-8 替换坏字节；整份回落 GBK 会把表注释、字段注释
# 全变成「瀵嗛挜」。坏字节同时也不是合法 GBK（尾部 0xFF）。
mixed = os.path.join(_tmpdir, "mixed.sql")
mixed_head = ("CREATE TABLE `enc_mixed` (`id` INT COMMENT '密钥') COMMENT='密钥表';\n"
              .encode("utf-8"))
mixed_blob = bytes((0xCE, 0x4E, 0x5E, 0x5C, 0x30, 0x00, 0x02, 0xFF, 0x0A))
with open(mixed, "wb") as fh:
    fh.write(mixed_head + mixed_blob)
mixed_tabs = app_main.parse_create_tables(app_main.read_sql_text(mixed))
mixed_cols = mixed_tabs[0]["columns"] if mixed_tabs else []
check("B34 夹二进制字段的 UTF-8 脚本按 UTF-8 替换坏字节，不整份回落 GBK",
      len(mixed_tabs) == 1 and mixed_tabs[0]["table_comment"] == "密钥表"
      and mixed_cols and mixed_cols[0]["comment"] == "密钥",
      f"{mixed_tabs[0]['table_comment'] if mixed_tabs else '无表'} | "
      f"{mixed_cols[0]['comment'] if mixed_cols else '无字段'}")


# ---------------------------------------------------------------------------
section("C 主键自动选择与校验")

dfpk = pd.DataFrame({"用户编号": ["A1", "A2", "A3"], "姓名": ["甲", "乙", "丙"],
                     "备注": ["x", "x", "y"]})
colspk = infer_columns(dfpk)
pk, why = select_primary_key(dfpk, colspk)
check("C1 自动选中语义明确的唯一列", pk == colspk[0]["field"], f"{pk} | {why}")
pk2, why2 = select_primary_key(pd.DataFrame({"金额": [1.5, 2.5, 3.5]}),
                               infer_columns(pd.DataFrame({"金额": [1.5, 2.5, 3.5]})))
check("C2 无合适字段时不设主键", pk2 is None, why2)
dfdup = pd.DataFrame({"a": ["x", "x"], "b": [1, 2]})
cdup = [{"field": "a", "comment": "甲", "type": "VARCHAR(64)", "primary_key": True},
        {"field": "b", "comment": "乙", "type": "INT", "primary_key": False}]
okv, msg = validate_primary_key(dfdup, cdup)
check("C3 组合值重复的主键被拒绝", not okv, msg)
okv2, msg2 = validate_primary_key(dfdup, [dict(cdup[0], primary_key=False),
                                          dict(cdup[1], primary_key=True)])
check("C4 唯一非空列可作主键", okv2, msg2)
okv3, msg3 = validate_primary_key(pd.DataFrame({"a": [1, None]}),
                                  [{"field": "a", "comment": "甲", "type": "INT",
                                    "primary_key": True}])
check("C5 含空值的主键被拒绝", not okv3, msg3)
okv4, msg4 = validate_primary_key(pd.DataFrame({"a": ["x", "y"]}),
                                  [{"field": "a", "comment": "甲", "type": "TEXT",
                                    "primary_key": True}])
check("C6 TEXT 类型不能作主键", not okv4, msg4)
okv5, msg5 = validate_primary_key(dfdup, [dict(c, primary_key=True) for c in cdup])
check("C7 组合主键（多列）校验通过", okv5, msg5)
check("C8 未勾选任何主键时直接放行",
      validate_primary_key(dfdup, [dict(c, primary_key=False) for c in cdup])[0])
df_empty = pd.DataFrame({"编号": pd.Series(dtype=object)})
c_empty = [{"field": "bian_hao", "comment": "编号", "type": "VARCHAR(64)",
            "primary_key": True}]
okv6, msg6 = validate_primary_key(df_empty, c_empty)
check("C9 空表（0 行）手工指定合法主键放行", okv6, msg6)
okv7, msg7 = validate_primary_key(df_empty, [dict(c_empty[0], type="LONGTEXT")])
check("C10 空表仍校验主键类型（TEXT 被拒绝）", not okv7, msg7)
pk_empty, why_empty = select_primary_key(df_empty, c_empty)
check("C11 空表不自动选主键（自动选键仍需数据）",
      pk_empty is None and "没有数据行" in why_empty, f"{pk_empty} | {why_empty}")


# ---------------------------------------------------------------------------
section("D DDL 生成")

cols_ddl = [{"field": "id", "comment": "主键ID", "type": "INT", "primary_key": True},
            {"field": "name", "comment": "名称", "type": "VARCHAR(50)", "nullable": False},
            {"field": "remark", "comment": "备注", "type": "TEXT"}]
ddl_nodrop = build_ddl("t_demo", "演示表", cols_ddl, False)
ddl_drop = build_ddl("t_demo", "演示表", cols_ddl, True)
check("D1 默认不生成 DROP", not ddl_nodrop.startswith("DROP"))
check("D2 勾选覆盖时生成 DROP", ddl_drop.startswith("DROP TABLE IF EXISTS `t_demo`;"))
check("D3 表注释与字段注释写入", "COMMENT='演示表'" in ddl_drop
      and "COMMENT '主键ID'" in ddl_drop)
check("D4 主键列 NOT NULL + PRIMARY KEY 子句",
      "`id` INT NOT NULL" in ddl_nodrop and "PRIMARY KEY (`id`)" in ddl_nodrop)
check("D5 结构定义 nullable=False 的列 NOT NULL",
      "`name` VARCHAR(50) NOT NULL" in ddl_nodrop)
check("D6 其余列 DEFAULT NULL", "`remark` TEXT DEFAULT NULL" in ddl_nodrop)
check("D7 InnoDB + utf8mb4 + 排序规则",
      "ENGINE=InnoDB" in ddl_nodrop and "CHARSET=utf8mb4" in ddl_nodrop
      and "COLLATE=utf8mb4_general_ci" in ddl_nodrop)
comp = build_ddl("t", "c", [{"field": "a", "comment": "A", "type": "INT", "primary_key": True},
                            {"field": "b", "comment": "B", "type": "INT", "primary_key": True}])
check("D8 组合主键生成单条 PRIMARY KEY(a, b)", "PRIMARY KEY (`a`, `b`)" in comp)
quote = build_ddl("t", "含'引号", [{"field": "a", "comment": "注'释", "type": "INT"}])
check("D9 注释中的单引号被转义（不产生语法错误）",
      "COMMENT \\'" not in quote and "COMMENT '\\''" not in quote
      and "注\\'释" in quote, [l for l in quote.splitlines() if "注" in l])
ins = build_insert_sql("t_demo", cols_ddl)
colpart = ins[ins.index("(") + 1: ins.index(")")]
check("D10 INSERT 模板列数与占位符数一致",
      colpart.count("`") // 2 == ins.count("%s") == len(cols_ddl), ins)


# ---------------------------------------------------------------------------
section("F GUI 构造与交互逻辑（冻结态）")

w = app_main.MainWindow()
check("F1 MainWindow 构造成功，标题带版本号",
      w.windowTitle().startswith("Excel → MySQL 批量导入工具 v")
      and app_main.APP_VERSION in w.windowTitle(), w.windowTitle())
check("F2 前两个页签齐全", [w.main_tabs.tabText(i) for i in range(2)]
      == ["Excel 导入", "SQL 脚本改名入库"])
w.cb_db.blockSignals(True); w.cb_db.clear()
w.cb_db.addItem("testdb", "testdb")
w.cb_db.blockSignals(False)
w._refresh_db_labels()
check("F3 目标库提示条：Excel 页显示导入措辞",
      "testdb" in w.lbl_imp_db.text() and "Excel 数据将导入到这个库" in w.lbl_imp_db.text(),
      w.lbl_imp_db.text())
check("F4 目标库提示条：SQL 页显示执行措辞",
      "SQL 脚本将执行到这个库" in w.lbl_sql_db.text(), w.lbl_sql_db.text())
check("F4b 目标库提示条：SQL 执行器页说的是编辑区那一段",
      "编辑区里的 SQL 将执行到这个库" in w.lbl_run_db.text()
      and "testdb" in w.lbl_run_db.text(), w.lbl_run_db.text())
w.ed_port.setValue(3307)
check("F5 改端口后状态栏的悬停提示实时联动（host:port 收在这条里）",
      "3307" in w.lbl_conn_state.toolTip(), w.lbl_conn_state.toolTip())
check("F5b 页内三条指向不再重复 host:port（只说这一页要写到哪个库）",
      "3307" not in w.lbl_imp_db.text() and "127.0.0.1" not in w.lbl_imp_db.text()
      and "3307" not in w.lbl_run_db.text(), (w.lbl_imp_db.text(), w.lbl_run_db.text()))
w.cb_db.setCurrentIndex(-1)
check("F6 未选库时三页提示条清空并隐藏，不再重复顶部状态栏那条告警",
      w.lbl_imp_db.text() == w.lbl_sql_db.text() == w.lbl_run_db.text() == ""
      and w.lbl_imp_db.isHidden() and w.lbl_sql_db.isHidden() and w.lbl_run_db.isHidden(),
      (w.lbl_imp_db.text(), w.lbl_sql_db.text(), w.lbl_run_db.text(),
       w.lbl_imp_db.isHidden(), w.lbl_sql_db.isHidden(), w.lbl_run_db.isHidden()))
check("F6b 唯一的告警在顶部状态栏，指向此刻真能点到的按钮，话术只有一套",
      w.lbl_conn_state.text().startswith("⚠ 未选择目标库")
      and "设置数据源信息" in w.lbl_conn_state.text()
      and app_main.NO_DB_HINT in w.lbl_conn_state.toolTip()
      and app_main.NO_DB_HINT.startswith("未选择目标库"),
      (w.lbl_conn_state.text(), w.lbl_conn_state.toolTip()))
check("F6c 提示不再把公共连接区说成某个页签",
      "上方连接区" in w.lbl_conn_state.toolTip()
      and "Excel 导入」页" not in w.lbl_conn_state.toolTip()
      and "SQL 脚本」页" not in w.lbl_conn_state.toolTip(),
      w.lbl_conn_state.toolTip())
check("F6e 告警只染顶部状态栏，页内三条不残留红色样式",
      "#c62828" in w.lbl_conn_state.styleSheet()
      and all("#c62828" not in lbl.styleSheet()
              for lbl in (w.lbl_imp_db, w.lbl_sql_db, w.lbl_run_db)),
      (w.lbl_conn_state.styleSheet(), w.lbl_imp_db.styleSheet()))

# 连接区折叠：跑业务时只留一条"当前是哪个库"，要改连接再展开（用户口径）
_wc = app_main.MainWindow()
_wc.resize(1100, 780)
_wc.show()
_app.processEvents()
check("F6f 启动即收起连接区：首屏只留状态栏，告警直接指向「设置数据源信息」",
      not _wc.conn_form.isVisible() and _wc.btn_conn_edit.isVisible()
      and "设置数据源信息" in _wc.lbl_conn_state.text(),
      (_wc.conn_form.isVisible(), _wc.btn_conn_edit.isVisible(),
       _wc.lbl_conn_state.text()))
_wc.btn_conn_edit.click()
_app.processEvents()
check("F6f2 点「设置数据源信息」展开五行表单，那颗按钮让位给「收起」",
      _wc.conn_form.isVisible() and not _wc.btn_conn_edit.isVisible()
      and _wc.btn_conn_collapse.isVisible(),
      (_wc.conn_form.isVisible(), _wc.btn_conn_edit.isVisible(),
       _wc.btn_conn_collapse.isVisible()))
_wc.cb_db.addItem("testdb", "testdb")      # 走真信号：选定库应当自动收起
_app.processEvents()
check("F6g 选定目标库后自动收起，状态栏只报库名，三页提示条恢复显示",
      not _wc.conn_form.isVisible() and _wc.btn_conn_edit.isVisible()
      and _wc.lbl_conn_state.text() == "● 目标库 testdb"
      and not _wc.lbl_imp_db.isHidden() and "testdb" in _wc.lbl_imp_db.text(),
      (_wc.conn_form.isVisible(), _wc.lbl_conn_state.text(),
       _wc.lbl_imp_db.isHidden(), _wc.lbl_imp_db.text()))
check("F6h 收起后 host:port 与用户名退到悬停提示里，信息没丢",
      "127.0.0.1" in _wc.lbl_conn_state.toolTip()
      and "3306" in _wc.lbl_conn_state.toolTip() and "root" in _wc.lbl_conn_state.toolTip(),
      _wc.lbl_conn_state.toolTip())
_wc.btn_conn_edit.click()
_app.processEvents()
check("F6i 点「设置数据源信息」重新展开填表，那颗按钮自己让位给「收起」",
      _wc.conn_form.isVisible() and not _wc.btn_conn_edit.isVisible()
      and _wc.btn_conn_collapse.isVisible(),
      (_wc.conn_form.isVisible(), _wc.btn_conn_edit.isVisible(),
       _wc.btn_conn_collapse.isVisible()))
_wc.btn_conn_collapse.click()
_app.processEvents()
check("F6j 点「收起」回到一条状态栏",
      not _wc.conn_form.isVisible() and _wc.btn_conn_edit.isVisible(),
      (_wc.conn_form.isVisible(), _wc.btn_conn_edit.isVisible()))
check("F6k 收起的只是表单本体，页签与表格不受牵连",
      _wc.file_table.isVisible() and _wc.main_tabs.isVisible(),
      (_wc.file_table.isVisible(), _wc.main_tabs.isVisible()))
# 默认窗口首屏的高度分配（用户口径：日志区低一些、主表格高一些）。
# 断实测视口而不是只断配置值：文件表那段还装着组框标题与两排操作按钮，
# 只按比例给高度时表格视口可能只剩一两行，那正是这次要治的样子。
check("F6k1 默认窗口首屏：Excel 页表格段高于日志段，表格视口至少露 5 行",
      _wc.excel_split.sizes()[1] > _wc.excel_split.sizes()[2]
      and _wc.file_table.viewport().height()
      >= 5 * _wc.file_table.verticalHeader().defaultSectionSize(),
      (_wc.excel_split.sizes(), _wc.file_table.viewport().height(),
       _wc.file_table.verticalHeader().defaultSectionSize()))
_wc.main_tabs.setCurrentIndex(1)
_app.processEvents()
check("F6k2 默认窗口首屏：SQL 页同样表格段高于日志段、表格视口至少露 5 行",
      _wc.sql_split.sizes()[1] > _wc.sql_split.sizes()[2]
      and _wc.sql_table.viewport().height()
      >= 5 * _wc.sql_table.verticalHeader().defaultSectionSize(),
      (_wc.sql_split.sizes(), _wc.sql_table.viewport().height(),
       _wc.sql_table.verticalHeader().defaultSectionSize()))
_wc.close()
_wc.deleteLater()
_app.processEvents()
src = main_source()
if src is not None:
    check("F6d main.py 里不存在指向页签的连接指引", "「Excel 导入」页" not in src)
else:
    skip("F6d main.py 源码文案静态检查", "冻结态不含 .py 源码，开发态会跑")

w.tables = [dict(t1)]
dlg = app_main.StructureDialog(w.tables, w)
check("F7 表结构预览对话框可构造", dlg.tabs.count() == 1, f"tabs={dlg.tabs.count()}")
check("F7b 结构弹窗不重复加标识：tab 标题就是表名，tab 内容直接是那张表",
      isinstance(dlg.tabs.widget(0), app_main.QTableWidget)
      and box_title(dlg.tabs.widget(0)) == "",
      box_title(dlg.tabs.widget(0)))
check("F8 预览页每行有主键勾选框",
      dlg.editors and dlg.editors[0][1].cellWidget(0, 0) is not None)
ddl_dlg = app_main.DDLDialog(w.tables, True, w)
check("F9 DDL 预览对话框可构造", ddl_dlg.tabs.count() == 1)
check("F10 拖拽区已启用拖放", w.drop.acceptDrops())
check("F10b 三个日志框的最小高度都至少装得下 4 行（截图复核抓到的：没最小高度时实测只剩两行）",
      all(v.minimumHeight() >= 4 * v.fontMetrics().height()
          for v in (w.log, w.sql_log, w.sql_run_log)),
      [(v.minimumHeight(), v.fontMetrics().height())
       for v in (w.log, w.sql_log, w.sql_run_log)])

from PySide6.QtWidgets import QPlainTextEdit
from PySide6.QtGui import QTextCursor, QColor
v = QPlainTextEdit()
app_main.append_log(v, "普通行")
app_main.append_log(v, "错误第一行\n错误第二行", error=True)
app_main.append_log(v, "又一普通行")


def line_color(view, i):
    blk = view.document().findBlockByNumber(i)
    cur = QTextCursor(view.document())
    cur.setPosition(blk.position())
    fr = cur.charFormat().foreground()
    return fr.color().name() if fr.style() else "<默认>"


check("F11 错误日志整条标红（含多行的每一行）",
      line_color(v, 1) == app_main.LOG_ERROR_COLOR
      and line_color(v, 2) == app_main.LOG_ERROR_COLOR,
      f"{line_color(v,1)} / {line_color(v,2)}")
check("F12 普通日志不残留红色",
      line_color(v, 0) != app_main.LOG_ERROR_COLOR
      and line_color(v, 3) != app_main.LOG_ERROR_COLOR,
      f"{line_color(v,0)} / {line_color(v,3)}")

# ---- 日志一行的形状：年-月-日 时:分:秒 + 符号 + 大写分级标签（三个页签的日志框共用这一套） ----
import re as _re_log
LOG_HEAD_RE = _re_log.compile(r"^\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\] ([+\-!x]) \[(OK|INFO|WARN|ERROR)\] ")


def log_body(view_or_text):
    """把日志里的行还原成正文：抹掉行首的时间戳+分级前缀，再退掉续行补上的 4 格缩进。

    断言"日志与弹窗是同一句话"要用这一份——带前缀的那一行比的是排版不是文案，
    而排版（年月日时分秒）本来就不可能和弹窗一致。
    """
    text = (view_or_text.toPlainText()
            if hasattr(view_or_text, "toPlainText") else view_or_text)
    out = []
    for ln in (text or "").splitlines():
        ln = LOG_HEAD_RE.sub("", ln)
        out.append(ln[4:] if ln.startswith("    ") else ln)
    return "\n".join(out)


def log_lines(view_or_text, prefix):
    """按正文开头挑日志行。行首多了 [年-月-日 时:分:秒] 与分级标签，直接 startswith 会全挑空。"""
    return [ln for ln in log_body(view_or_text).splitlines() if ln.startswith(prefix)]


def log_block(view, prefix):
    """按正文开头找日志块的行号（颜色要按块号取），找不到返回 -1。"""
    doc = view.document()
    for i in range(doc.blockCount()):
        if log_body(doc.findBlockByNumber(i).text()).startswith(prefix):
            return i
    return -1


v2 = QPlainTextEdit()
app_main.append_log(v2, "[跳过] 已在列表：a.xlsx")
app_main.append_log(v2, "[已解析] b.xlsx → 表名预置 b")
app_main.append_log(v2, "! [WARN] 说明 | 近似掉的列")
app_main.append_log(v2, "      | 1 | 2 |")
def log_head(line):
    """取一行的 (符号, 级别)；没带前缀就返回哨兵值，别让守卫自己抛异常把整轮打断。"""
    m = LOG_HEAD_RE.match(line)
    return (m.group(1), m.group(2)) if m else ("<无前缀>", "<无前缀>")


_lines = v2.toPlainText().splitlines()
check("F12b 每条记录首行带 [年-月-日 时:分:秒] 与符号+大写分级标签",
      all(LOG_HEAD_RE.match(l) for l in _lines[:3]),
      _lines[:3])
check("F12c 级别由标签定：跳过是警告、已解析是成功",
      log_head(_lines[0])[1] == "WARN" and log_head(_lines[1])[1] == "OK",
      [log_head(l) for l in _lines[:2]])
check("F12d 符号跟级别走（! 配 WARN、+ 配 OK），不出现 ! [OK] 这种自相矛盾的行",
      log_head(_lines[0]) == ("!", "WARN") and log_head(_lines[1]) == ("+", "OK"),
      [log_head(l) for l in _lines[:2]])
check("F12e 命令行搬来的那句本来就带大写标签，行首不会叠出第二个标签",
      _lines[2].endswith("! [WARN] 说明 | 近似掉的列")
      and _lines[2].count("[WARN]") == 1, _lines[2])
check("F12f 缩进的续行（结果表格那几行）不另起一条，也不补时间戳",
      _lines[3] == "      | 1 | 2 |", _lines[3])
check("F12g 告警级琥珀加粗、成功级绿色、错误级仍是那支红",
      line_color(v2, 0) == app_main.LOG_WARN_COLOR
      and line_color(v2, 1) == app_main.LOG_OK_COLOR
      and line_color(v2, 2) == app_main.LOG_WARN_COLOR,
      [line_color(v2, i) for i in range(3)])
_fmt_multi = app_main.format_log_entry("[添加结果] 第一行\n第二行")[0]
check("F12h 多行记录只有首行带前缀，其余行缩进 4 格挂在同一条下面",
      len(_fmt_multi.splitlines()) == 2
      and bool(LOG_HEAD_RE.match(_fmt_multi.splitlines()[0]))
      and _fmt_multi.splitlines()[1] == "    第二行", repr(_fmt_multi))
check("F12i 添加结果里有拒绝时那一行算警告，不是成功",
      app_main.log_level("[添加结果] 添加 1 项文件\n另有 1 项解析失败") == "warn"
      and app_main.log_level("[添加结果] 添加 1 项文件") == "ok",
      [app_main.log_level("[添加结果] 添加 1 项文件\n另有 1 项解析失败"),
       app_main.log_level("[添加结果] 添加 1 项文件")])
check("F12j 定级看整条记录，不是只看首行（「另有」就写在续行上）",
      app_main.format_log_entry("[添加结果] 添加 1 项文件\n另有 1 项解析失败")[1] == "warn",
      app_main.format_log_entry("[添加结果] 添加 1 项文件\n另有 1 项解析失败"))
check("F13 数据库异常展开为错误码+原文",
      "MySQL 错误码 1064" in app_main.describe_db_error(
          pymysql.err.ProgrammingError(1064, "syntax error near 'x'")),
      app_main.describe_db_error(pymysql.err.ProgrammingError(1064, "syntax error near 'x'")))
# 口令里有中文时 pymysql 抛的是 UnicodeEncodeError，跟数据库毫无关系；
# 不打一句人话，现场就会拿着它去翻 my.cnf
from core.log_text import PASSWORD_ENCODING_HINT as _PW_HINT
_enc_err = app_main.describe_db_error(
    UnicodeEncodeError("latin-1", "测试口令", 0, 2, "ordinal not in range(256)"))
check("F13b 口令编码那句给了可操作的出口（改 ASCII 口令 / 换 Java 版）",
      "latin-1" in _PW_HINT and "ASCII" in _PW_HINT and "Java" in _PW_HINT, _PW_HINT)
check("F13c UnicodeEncodeError 展开里带着那句指引",
      "多半出在口令上" in _enc_err and "JDBC" in _enc_err, _enc_err)
_io_err = app_main.describe_db_error(FileNotFoundError(2, "No such file or directory"))
check("F13d 文件读写异常的 errno 不被冒充成 MySQL 错误码",
      "MySQL" not in _io_err and "FileNotFoundError" in _io_err, _io_err)
check("F14 超长语句截断但标注原文长度",
      "已截断" in app_main.format_statement("INSERT INTO t VALUES (" + "1," * 3000 + ")"))
wk = app_main.ImportWorker({"host": "h", "port": 3306, "user": "u", "password": "",
                            "database": "d"}, [], False)
sigs = [n for n in ("log", "log_error", "table_progress", "table_done", "finished_all")
        if hasattr(wk, n)]
check("F15 ImportWorker 五路信号齐备", len(sigs) == 5, sigs)

sqlfile = os.path.join(_tmpdir, "x.sql")
open(sqlfile, "w", encoding="utf-8").write(sql_text)
w.sql_files = []
w.sql_files.append({"path": sqlfile, "text": new_text, "modified": True,
                    "rename_map": {"t1": "ods_t1"}})
w._refresh_sql_table()
check("F16 SQL 页表格正确展示提取到的表", w.sql_table.rowCount() == 2, w.sql_table.rowCount())
out_path = os.path.join(_tmpdir, "x_prefix.sql")
app_main.MainWindow.on_sql_save_modified(w)
check("F17 保存改名后的脚本落在源文件旁且不覆盖原文件",
      os.path.exists(out_path) and open(out_path, encoding="utf-8").read() == new_text
      and open(sqlfile, encoding="utf-8").read() == sql_text)
check("F18 保存后 modified 标记复位，重复保存不再产生新写入",
      w.sql_files[-1]["modified"] is False)

# ---- 第三个页签「SQL 执行器」：编辑区、三颗按钮、示例对话框与执行语义 ----
check("F19 页签顺序为 Excel 导入 / SQL 脚本改名入库 / SQL 执行器",
      [w.main_tabs.tabText(i) for i in range(w.main_tabs.count())]
      == ["Excel 导入", "SQL 脚本改名入库", "SQL 执行器"],
      [w.main_tabs.tabText(i) for i in range(w.main_tabs.count())])
check("F20 编辑区开场就是那句连通检查且不折行",
      w.sql_run_input.toPlainText() == app_main.SQL_RUN_DEFAULT
      and w.sql_run_input.lineWrapMode() == QPlainTextEdit.NoWrap,
      w.sql_run_input.toPlainText())
_run_btns = {w.btn_sql_run_samples.text(): w.btn_sql_run_samples,
             w.btn_sql_run_exec.text(): w.btn_sql_run_exec,
             w.btn_sql_run_clear_input.text(): w.btn_sql_run_clear_input,
             w.btn_sql_run_clear_log.text(): w.btn_sql_run_clear_log}
check("F20b 编辑区与结果区同一支等宽字体、字号与第一页日志同档",
      w.sql_run_log.font().family() == w.sql_run_input.font().family()
      and w.sql_run_log.font().pointSize() == w.sql_run_input.font().pointSize()
      and w.sql_run_input.font().pointSize() == w.log.font().pointSize(),
      (w.sql_run_log.font().family(), w.sql_run_log.font().pointSize(),
       w.log.font().pointSize()))
from core.sql_execute import render_table as _rt
_TABLE = _rt(["id", "名称"], [["1", "某公司"], [None, "x"]])
check("F20c 结果集排成等宽表格：列宽按显示宽度补齐、NULL 显式写出",
      _TABLE == ["+------+--------+",
                 "| id   | 名称   |",
                 "+------+--------+",
                 "| 1    | 某公司 |",
                 "| NULL | x      |",
                 "+------+--------+"], _TABLE)
check("F20d 零行的表格只有表头那一框，不重复画收口线",
      _rt(["a"], []) == ["+---+", "| a |", "+---+"], _rt(["a"], []))
check("F21 四颗按钮齐备且可点（顶部两颗 + 两个 pane 各一颗清空）",
      len(_run_btns) == 4 and set(_run_btns) == {u"示例 (?)", u"执行",
                                                 u"清空编辑区", u"清空结果区"}
      and all(b.isEnabled() for b in _run_btns.values()), sorted(_run_btns))
check("F22 执行按钮的 tooltip 把事务语义写全",
      "全部成功才提交，中途出错整批回滚" in w.btn_sql_run_exec.toolTip(),
      w.btn_sql_run_exec.toolTip())


class _RunCursor:
    """顶替 pymysql 游标：记下每条 execute 的语句，按 fail_on 造一条报错。"""

    def __init__(self, owner):
        self.owner = owner
        self.description = None

    def execute(self, sql):
        self.owner.calls.append(sql)
        if sql == self.owner.fail_on:
            raise RuntimeError("(1064, '语法错误')")
        return 2


class _RunConn:
    def __init__(self, fail_on=None):
        self.calls = []
        self.fail_on = fail_on

    def cursor(self):
        return _RunCursor(self)

    def commit(self):
        self.calls.append("commit")

    def rollback(self):
        self.calls.append("rollback")

    def close(self):
        self.calls.append("close")


_real_connect = app_main.connect
w.main_tabs.setCurrentIndex(2)
POPUPS.clear()
w.sql_run_input.setPlainText("SELECT 1 AS 连通检查;")
w.sql_run_log.appendPlainText("上一次留下的结果")
w.btn_sql_run_clear_input.click()
check("F23 清空编辑区只清左边，结果区那行旧结果不动",
      w.sql_run_input.toPlainText() == ""
      and w.sql_run_log.toPlainText() == "上一次留下的结果",
      (w.sql_run_input.toPlainText(), w.sql_run_log.toPlainText()))
w.sql_run_input.setPlainText("SELECT 1 AS 连通检查;")
w.btn_sql_run_clear_log.click()
check("F23b 清空结果区只清右边，编辑区那段不动",
      w.sql_run_log.toPlainText() == ""
      and w.sql_run_input.toPlainText() == "SELECT 1 AS 连通检查;",
      (w.sql_run_log.toPlainText(), w.sql_run_input.toPlainText()))
check("F23c 两颗清空的 tooltip 各说清自己管哪一边",
      "右侧结果区不动" in w.btn_sql_run_clear_input.toolTip()
      and "左边编辑区不动" in w.btn_sql_run_clear_log.toolTip(),
      (w.btn_sql_run_clear_input.toolTip(), w.btn_sql_run_clear_log.toolTip()))
w.on_sql_run_clear_input()
w.on_sql_run()
check("F24 空编辑区被拦下：弹窗与「已拦下」日志双写同一句",
      any(t == "提示" and "编辑区是空的" in x for t, x in POPUPS)
      and "已拦下：没有可执行的 SQL" in w.log.toPlainText(), (POPUPS, w.log.toPlainText()))

_conn1 = _RunConn()
app_main.connect = lambda cfg: _conn1
POPUPS.clear()
w.sql_run_log.clear()
w.sql_run_input.setPlainText("CREATE TABLE t_a (id INT);\nINSERT INTO t_a VALUES (1);")
w.on_sql_run()
_run_log = w.sql_run_log.toPlainText()
check("F25 落到库上的语句序列与提交次序正确",
      _conn1.calls == ["CREATE TABLE t_a (id INT)", "INSERT INTO t_a VALUES (1)",
                       "commit", "close"], _conn1.calls)
check("F26 逐条进度与汇总都写进结果区",
      "第 1/2 条" in _run_log and "第 2/2 条" in _run_log
      and "全部完成 | 2 条语句已提交" in _run_log, _run_log)
check("F26b 结果区每条记录首行带年月日+钟点（缩进的表格行归上一条，不另打时间戳）",
      all(LOG_HEAD_RE.match(l) for l in _run_log.splitlines()
          if l.strip() and not l.startswith("      ")),
      [l for l in _run_log.splitlines()
       if l.strip() and not l.startswith("      ") and not LOG_HEAD_RE.match(l)][:4])
_conn2 = _RunConn(fail_on="INSERT INTO t_a VALUES (2)")
app_main.connect = lambda cfg: _conn2
w.sql_run_log.clear()
w.sql_run_input.setPlainText("CREATE TABLE t_b (id INT);\nINSERT INTO t_a VALUES (2);")
w.on_sql_run()
_run_log = w.sql_run_log.toPlainText()
check("F27 中途出错整批回滚：全程一次都不提交，失败那句直接 rollback",
      _conn2.calls == ["CREATE TABLE t_b (id INT)",
                       "INSERT INTO t_a VALUES (2)", "rollback", "close"], _conn2.calls)
check("F28 失败行标红并附 DDL 隐式提交提醒",
      "第 2 条被拒绝" in _run_log and "隐式提交" in _run_log, _run_log)
check("F29 执行结果不额外弹窗（这一页是给人来回试语句的）", not POPUPS, POPUPS)

# ---- 执行器任务的进度与取消：一屏上千条语句时，这两件决定用户是等还是走 ----
class _PCur:
    def __init__(self, log):
        self.description = None
        self._log = log

    def execute(self, sql):
        self._log.append(sql)
        return 0


class _PConn:
    """假连接：记下执行了哪几句、有没有提交、有没有回滚。"""

    def __init__(self):
        self.executed = []
        self.committed = False
        self.rolled = False

    def cursor(self):
        return _PCur(self.executed)

    def commit(self):
        self.committed = True

    def rollback(self):
        self.rolled = True

    def close(self):
        pass


from core.sql_execute import run as _run_exec

_THREE = ("CREATE TABLE pa (i INT);\n"
          "CREATE TABLE pb (i INT);\n"
          "CREATE TABLE pc (i INT);\n")
_pc = _PConn()
_steps = []
_run_exec(_pc, _THREE, lambda t: None,
          progress=lambda d, t: _steps.append((d, t)), cancelled=lambda: False)
check("F29b 执行器逐条回报进度：开工先报 0/3，收尾落到 3/3",
      _steps[0] == (0, 3) and _steps[-1] == (3, 3) and len(_steps) >= 4, _steps)
check("F29c 全部成功才提交，进度回报不改事务语义",
      _pc.committed and not _pc.rolled and len(_pc.executed) == 3,
      (_pc.committed, _pc.rolled, _pc.executed))
_pc2 = _PConn()
_stop = {"now": False}


def _stop_after_one(done, total):
    if done >= 1:
        _stop["now"] = True


_run_exec(_pc2, _THREE, lambda t: None, progress=_stop_after_one,
          cancelled=lambda: _stop["now"])
check("F29d 点了停止就在下一条之前收手：只执行 1 句、整批回滚、一次都不提交",
      len(_pc2.executed) == 1 and _pc2.rolled and not _pc2.committed,
      (_pc2.executed, _pc2.rolled, _pc2.committed))
_pc3 = _PConn()
_run_exec(_pc3, _THREE, lambda t: None)
check("F29e 命令行那条线不传进度与取消，行为与改前一致",
      len(_pc3.executed) == 3 and _pc3.committed, _pc3.executed)


def _boom(cfg):
    raise RuntimeError("(2003, \"Can't connect to MySQL server\")")


app_main.connect = _boom
POPUPS.clear()
w.sql_run_log.clear()
w.sql_run_input.setPlainText("SELECT 1;")
w.on_sql_run()
check("F30 连不上库只写结果区、不弹任务异常",
      "连不上库" in w.sql_run_log.toPlainText()
      and not any(t == "任务异常" for t, _ in POPUPS),
      (w.sql_run_log.toPlainText()[-120:], POPUPS))
app_main.connect = _real_connect

_groups = []
for _s in app_main.SQL_SAMPLES:
    if not _groups or _groups[-1] != _s[0]:
        _groups.append(_s[0])
check("F31 示例库 100 条、12 个分组、说明不重样，且每条都带分组/说明/语句",
      len(app_main.SQL_SAMPLES) == 100 and len(_groups) == 12
      and len({s[1] for s in app_main.SQL_SAMPLES}) == 100
      and all(len(s) == 3 and s[0] and s[1] and s[2] for s in app_main.SQL_SAMPLES),
      (len(app_main.SQL_SAMPLES), _groups))
check("F31b 分组按治理主题排布，治理类不少于 70 条",
      _groups == ["建库与选库", "建表与改表", "元数据与治理体检", "数据质量检查",
                  "数据标准化与清洗", "主数据与重复数据", "数据血缘与影响分析",
                  "安全与权限治理", "生命周期与归档", "性能与容量治理",
                  "查询与执行计划", "数据修正"]
      and sum(1 for s in app_main.SQL_SAMPLES if s[0] in _groups[2:10]) >= 70,
      _groups)
_dlg = app_main.SqlSamplesDialog(w.sql_run_input)
check("F32 示例对话框标题带条数、清单与示例表同条数",
      _dlg.windowTitle() == "SQL 示例（100 条）" and _dlg.list.count() == 100,
      (_dlg.windowTitle(), _dlg.list.count()))
check("F33 条目文案为「【分组】说明」，打开即选中第一条",
      _dlg.list.item(0).text() == "【建库与选库】%s" % app_main.SQL_SAMPLES[0][1]
      and _dlg.preview.toPlainText() == app_main.SQL_SAMPLES[0][2],
      _dlg.list.item(0).text())
check("F33b 示例全文是日志档 10 号等宽字体（与左侧清单同观感，不吃编辑区那档 16 号）",
      _dlg.preview.font().family() == w.sql_run_input.font().family()
      and _dlg.preview.font().pointSize() == w.log.font().pointSize()
      and _dlg.preview.font().pointSize() == 10,
      (_dlg.preview.font().family(), _dlg.preview.font().pointSize()))
check("F34 每条示例都能切成至少一句可执行语句",
      all(split_sql_statements(s[2]) for s in app_main.SQL_SAMPLES),
      [i for i, s in enumerate(app_main.SQL_SAMPLES) if not split_sql_statements(s[2])])
_dlg.list.setCurrentRow(7)
check("F35 切换选中行，右侧全文跟着换",
      _dlg.preview.toPlainText() == app_main.SQL_SAMPLES[7][2], _dlg.list.currentRow())
# 换一条要把右侧滚回开头。先人为把预览弄成"能滚且已经滚走"，
# 否则这条断言在内容本来就放得下时是恒真的（量不到复位这件事）。
# 只要求横向滚得动：预览不折行，示例里有比可视区宽的行；纵向放得下是常态，
# 拿它当前提会让用例在一台分辨率更高的机器上假红
_dlg.preview.setLineWrapMode(type(_dlg.preview).NoWrap)
_dlg.preview.verticalScrollBar().setValue(9999)
_dlg.preview.horizontalScrollBar().setValue(9999)
_v0 = _dlg.preview.verticalScrollBar().value()
_h0 = _dlg.preview.horizontalScrollBar().value()
_dlg.list.setCurrentRow(2)
check("F35b 换一条时右侧滚回开头，上一行留下的滚动位置不许留着",
      _h0 > 0 and _dlg.preview.horizontalScrollBar().value() == 0
      and _dlg.preview.verticalScrollBar().value() == 0,
      (_h0, _v0, _dlg.preview.horizontalScrollBar().value(),
       _dlg.preview.verticalScrollBar().value()))
_dlg.preview.setLineWrapMode(type(_dlg.preview).WidgetWidth)
_dlg.show()
w.sql_run_input.setPlainText(app_main.SQL_RUN_DEFAULT)
_dlg.list.setCurrentRow(3)
_dlg.on_insert()
check("F36 编辑区还是默认那句时，插入直接换成正题并收起对话框",
      w.sql_run_input.toPlainText() == app_main.SQL_SAMPLES[3][2]
      and not _dlg.isVisible(), w.sql_run_input.toPlainText())
_dlg.show()
w.sql_run_input.setPlainText("SELECT 1")
_dlg.list.setCurrentRow(1)
_dlg.on_insert()
check("F37 已有内容时插入会补分号换行再接，不覆盖原句",
      w.sql_run_input.toPlainText()
      == "SELECT 1;\n" + app_main.SQL_SAMPLES[1][2], w.sql_run_input.toPlainText())
_dlg.show()
_dlg.list.setCurrentRow(2)
_dlg.on_copy()
check("F38 复制到剪贴板在标题上给回执",
      _dlg.windowTitle().endswith("· 已复制到剪贴板"), _dlg.windowTitle())
_dlg.deleteLater()


# ---------------------------------------------------------------------------
section("H ZIP 压缩包导入")

import zipfile
from core.zip_import import extract_excel_archive, ZipArchiveError, decode_name, safe_relpath
from core.merge_check import detect_merged_cells, MergedCellsError


def make_xlsx_bytes(cols=("编号", "数值"), rows=(("A1", 1), ("A2", 2))):
    buf = io.BytesIO()
    pd.DataFrame(rows, columns=list(cols)).to_excel(buf, sheet_name="S", index=False)
    return buf.getvalue()


def build_zip2(path, names):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for n in names:
            z.writestr(n, make_xlsx_bytes())


ztmp = tempfile.mkdtemp(prefix="packtest_zip_")

zp1 = os.path.join(ztmp, "one.zip")
build_zip2(zp1, ["水库基础信息.xlsx", "北京/河道水文站.xlsx", "说明.txt", "数据.csv",
                 "内层包.zip", "~$临时锁.xlsx"])
dest = os.path.join(ztmp, "out1")
r = extract_excel_archive(zp1, dest)
check("H1 展开包内 Excel", len(r["files"]) == 2, [f["origin"] for f in r["files"]])
check("H2 递归子目录并保留包内结构",
      os.path.isfile(os.path.join(dest, "北京", "河道水文站.xlsx")))
check("H3 非 Excel 文件被忽略并计数", r["ignored"] == ["说明.txt", "数据.csv", "~$临时锁.xlsx"],
      r["ignored"])
check("H4 嵌套 zip 不递归展开，只计数", r["nested_zips"] == 1 and
      not any("内层包" in f["origin"] for f in r["files"]), r["nested_zips"])
check("H5 展开出的文件可被正常解析",
      build_table_def(r["files"][0]["path"])["table_name"] == "shui_ku_ji_chu_xin_xi",
      build_table_def(r["files"][0]["path"])["table_name"])

def build_gbk_zip(path, names):
    """造一个与 Windows 自带"发送到压缩文件夹"同格式的包：名字按 GBK 存、不打 UTF-8 标记。

    Python 的 zipfile 对非 ASCII 名字一律写 UTF-8 并置 bit11，没法直接产出这种包。
    这里先用等长纯 ASCII 占位名打包（保证字节数与偏移不变），再按头部结构把
    名字字段原地换成 GBK 字节并清掉 bit11。
    """
    LFH_NAME_OFF, LFH_NAMELEN_OFF = 30, 26
    CDH_NAME_OFF, CDH_NAMELEN_OFF = 46, 28
    plan = {}
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for n in names:
            gbk = n.encode("gbk")
            ph = ("P" * len(gbk)).encode("ascii")
            plan[ph] = gbk
            z.writestr(ph.decode("ascii"), make_xlsx_bytes())
    raw = bytearray(open(path, "rb").read())
    for sig, name_off, len_off in ((b"PK\x03\x04", LFH_NAME_OFF, LFH_NAMELEN_OFF),
                                   (b"PK\x01\x02", CDH_NAME_OFF, CDH_NAMELEN_OFF)):
        pos = 0
        while True:
            j = bytes(raw).find(sig, pos)
            if j < 0:
                break
            nlen = int.from_bytes(raw[j + len_off:j + len_off + 2], "little")
            field = bytes(raw[j + name_off:j + name_off + nlen])
            if field in plan:
                raw[j + name_off:j + name_off + nlen] = plan[field]
            raw[j + 6 if sig == b"PK\x03\x04" else j + 8] &= ~0x08   # 清 bit11
            pos = j + 4
    open(path, "wb").write(bytes(raw))


zgbk = os.path.join(ztmp, "gbk.zip")
build_gbk_zip(zgbk, ["水库基础信息.xlsx"])
rg = extract_excel_archive(zgbk, os.path.join(ztmp, "out_gbk"))
check("H6 GBK 中文文件名还原不乱码（否则表名/注释全废）",
      rg["files"][0]["origin"] == "水库基础信息.xlsx"
      and os.path.basename(rg["files"][0]["path"]) == "水库基础信息.xlsx",
      rg["files"][0]["origin"])
check("H6a GBK 包展开出的表名也是中文正确解码的结果",
      build_table_def(rg["files"][0]["path"])["table_name"] == "shui_ku_ji_chu_xin_xi",
      build_table_def(rg["files"][0]["path"])["table_name"])
check("H6b 已带 UTF-8 标记的包名不做二次解码",
      extract_excel_archive(zp1, os.path.join(ztmp, "x"))["files"][0]["origin"]
      == "水库基础信息.xlsx")
u = zipfile.ZipInfo("水库基础信息.xlsx")
u.flag_bits = 0x800
check("H6c decode_name 对 UTF-8 标记条目原样返回",
      decode_name(u) == "水库基础信息.xlsx", decode_name(u))
u2 = zipfile.ZipInfo("水库基础信息.xlsx".encode("gbk").decode("cp437"))
u2.flag_bits = 0
check("H6d decode_name 对无标记条目按 cp437→gbk 回退",
      decode_name(u2) == "水库基础信息.xlsx", decode_name(u2))

# 同名不同目录不得互相覆盖
zdup = os.path.join(ztmp, "dup.zip")
build_zip2(zdup, ["北京/水库.xlsx", "河北/水库.xlsx"])
rd = extract_excel_archive(zdup, os.path.join(ztmp, "out_dup"))
check("H7 包内同名不同目录的文件都保留", len(rd["files"]) == 2
      and os.path.isfile(rd["files"][0]["path"]) and os.path.isfile(rd["files"][1]["path"]),
      [f["origin"] for f in rd["files"]])
w_dup = app_main.MainWindow()
w_dup._add_files([zdup])
check("H7b 同名不同目录的表经 GUI 加载后表名去重",
      len({t["table_name"] for t in w_dup.tables}) == 2,
      [t["table_name"] for t in w_dup.tables])

# zip slip：包内写 ../ 不得逃出去向目录
zevil = os.path.join(ztmp, "evil.zip")
with zipfile.ZipFile(zevil, "w") as z:
    z.writestr("../越界.xlsx", make_xlsx_bytes())
    z.writestr("/绝对路径.xlsx", make_xlsx_bytes())
re_ = extract_excel_archive(zevil, os.path.join(ztmp, "out_evil"))
esc = [f["path"] for f in re_["files"]
       if not os.path.abspath(f["path"]).startswith(os.path.abspath(os.path.join(ztmp, "out_evil")))]
check("H8 zip slip 路径穿越被挡住", not esc, esc)
check("H8b 越界条目被安全化而非静默丢弃", len(re_["files"]) == 2,
      [f["origin"] for f in re_["files"]])
check("H8c safe_relpath 剥掉 .. 与绝对路径",
      safe_relpath("../a/b.xlsx") == os.path.join("a", "b.xlsx")
      and safe_relpath("/x.xlsx") == "x.xlsx" and safe_relpath("././..") is None)

fake = os.path.join(ztmp, "fake.zip")
open(fake, "wb").write(b"not a zip file at all")
try:
    extract_excel_archive(fake, os.path.join(ztmp, "o"))
    check("H9 非 zip 文件报错", False)
except ZipArchiveError as e:
    check("H9 非 zip 文件报清晰错误", "ZIP" in str(e), str(e)[:80])

zempty = os.path.join(ztmp, "empty.zip")
with zipfile.ZipFile(zempty, "w") as z:
    z.writestr("readme.md", "# 没有 Excel")
rz = extract_excel_archive(zempty, os.path.join(ztmp, "out_empty"))
check("H10 包内没有 Excel 时返回空列表", rz["files"] == [] and len(rz["ignored"]) == 1,
      rz["ignored"])

w3 = app_main.MainWindow()
w3._add_files([zp1])
check("H11 GUI 层：拖入 zip 后展开成待导入条目", len(w3.tables) == 2,
      [t["table_name"] for t in w3.tables])
tip = w3.file_table.item(0, 1).toolTip()
check("H12 文件列表 tooltip 标出来自哪个包的哪个内部路径",
      tip.startswith("one.zip!") and "水库基础信息.xlsx" in tip, tip)
check("H13 日志报告展开与忽略数量",
      "[压缩包] one.zip：展开 2 个 Excel" in w3.log.toPlainText()
      and "忽略 3 个非 Excel 文件" in w3.log.toPlainText()
      and "跳过 1 个嵌套 zip" in w3.log.toPlainText(),
      log_lines(w3.log.toPlainText(), "[压缩包]"))
w3._add_files([zempty])
check("H14 空包给出红色告警而不是静默", "压缩包为空" in w3.log.toPlainText())
w3._add_files([fake])
check("H15 坏包报错且不中断", "压缩包失败" in w3.log.toPlainText())
zip_dir = w3._zip_tmp
w3.close()
check("H16 关闭窗口清理 ZIP 临时目录",
      zip_dir and not os.path.isdir(zip_dir), zip_dir)
from PySide6.QtWidgets import QFileDialog
dlg_args = {}
_orig_dialog = QFileDialog.getOpenFileNames


def _fake_dialog(parent, title, directory, filt, *a, **k):
    dlg_args.clear()
    dlg_args.update(title=title, filter=filt)
    return [], filt


QFileDialog.getOpenFileNames = staticmethod(_fake_dialog)
try:
    app_main.MainWindow().on_add_files()
finally:
    QFileDialog.getOpenFileNames = staticmethod(_orig_dialog)
check("H17 文件选择框标题与过滤器都含 zip（运行时实测，非 grep 源码）",
      "ZIP" in dlg_args.get("title", "") and "*.zip" in dlg_args.get("filter", ""),
      f"{dlg_args.get('title')} | {dlg_args.get('filter')}")
from PySide6.QtCore import Qt as _Qt, QEvent as _QEvent, QPoint as _QPoint
from PySide6.QtGui import QMouseEvent as _QMouseEvent
from PySide6.QtWidgets import QPushButton as _QPushButton
_click_win = app_main.MainWindow()
_click_args = {}


def _fake_click_dialog(parent, title, directory, filt, *a, **k):
    _click_args.update(title=title, filter=filt)
    return [], filt


QFileDialog.getOpenFileNames = staticmethod(_fake_click_dialog)
try:
    _click_event = _QMouseEvent(_QEvent.MouseButtonRelease, _QPoint(10, 10),
                                _Qt.LeftButton, _Qt.LeftButton, _Qt.NoModifier)
    _click_win.drop.mouseReleaseEvent(_click_event)
finally:
    QFileDialog.getOpenFileNames = staticmethod(_orig_dialog)
check("H17c 点击虚线区就弹文件选择器（走真实鼠标事件）",
      "ZIP" in _click_args.get("title", "") and "*.zip" in _click_args.get("filter", ""),
      _click_args)
check("H17d 首屏不再有「添加文件」按钮",
      all(b.text() != "添加文件" for b in _click_win.findChildren(_QPushButton)),
      [b.text() for b in _click_win.findChildren(_QPushButton)][:8])
drop = app_main.DropArea()
check("H17b 拖拽区文案写明三种格式且带点击提示（运行时取 QLabel 实际文本）",
      all(x in drop.label.text() for x in (".xlsx", ".xls", ".zip", "点击选择文件")),
      drop.label.text().replace("\n", " / "))
check("H18 IMPORT_EXTS 覆盖三种扩展名",
      app_main.IMPORT_EXTS == (".xlsx", ".xls", ".zip"), app_main.IMPORT_EXTS)
check("H18b 首屏日志 10 号等宽字体（9 号现场判过小、14 号只露三行；表格那一档比它大）",
      w3.log.font().family() == "Consolas"
      and w3.log.font().pointSize() == app_main.LOG_FONT_SIZE == 10,
      (w3.log.font().family(), w3.log.font().pointSize()))
check("H18c SQL 脚本页日志与首屏同一档字号",
      w3.sql_log.font().family() == w3.log.font().family()
      and w3.sql_log.font().pointSize() == w3.log.font().pointSize(),
      (w3.sql_log.font().family(), w3.sql_log.font().pointSize()))
check("H18d 表格内容与表头同为 11 号且严格大于日志（用户口径：内容比日志大一档）",
      w3.file_table.font().pointSize() > w3.log.font().pointSize()
      and w3.file_table.horizontalHeader().font().pointSize()
      == w3.file_table.font().pointSize()
      and w3.sql_table.font().pointSize() > w3.log.font().pointSize()
      and w3.file_table.font().pointSize() == app_main.TABLE_FONT_SIZE == 11,
      (w3.file_table.font().pointSize(),
       w3.file_table.horizontalHeader().font().pointSize(),
       w3.sql_table.font().pointSize(), w3.log.font().pointSize()))
_h18_row = w3.file_table.verticalHeader().defaultSectionSize()
_h18_line = w3.file_table.fontMetrics().height()
check("H18e 表格行高按字高加留白算出来，不是写死的 30px",
      _h18_line + 4 <= _h18_row <= _h18_line + 8
      and w3.sql_table.verticalHeader().defaultSectionSize() == _h18_row,
      (_h18_row, _h18_line, w3.sql_table.verticalHeader().defaultSectionSize()))

section("I .xls 老格式支持（xlrd）")

check("I1 xlrd 已安装且可导入", True, f"xlrd {xlrd.__version__}")
check("I2 pandas 能把 .xls 分派给 xlrd 引擎",
      "xlrd" in pd.io.excel._base.ExcelFile._engines,
      sorted(pd.io.excel._base.ExcelFile._engines))
try:
    build_table_def(os.path.join(ztmp, "不存在.xls"))
    ext_ok = False
except ValueError as e:
    ext_ok = "不支持的文件类型" not in str(e)
except Exception:
    ext_ok = True
check("I3 .xls 不再被判为不支持的类型", ext_ok)
xls_path = os.environ.get("PT_XLS", "")
if xls_path and os.path.isfile(xls_path):
    # xlrd 端到端读取能力先单独确认（与合并单元格策略无关）
    try:
        raw_sheets = pd.read_excel(xls_path, sheet_name=None, dtype=object)
        check("I4 真实 .xls 可被 xlrd 读出", bool(raw_sheets),
              f"{len(raw_sheets)} 个 Sheet")
    except Exception as e:
        check("I4 真实 .xls 可被 xlrd 读出", False, f"{type(e).__name__}: {e}")
        raw_sheets = {}
    # 能否进 build_table_def 取决于该样本本身有没有合并区
    has_merge = bool(detect_merged_cells(xls_path))
    try:
        tx = build_table_def(xls_path)
        check("I5 build_table_def 对该 .xls 的处置与合并区检测结果一致", not has_merge,
              f"有 {sum(len(v) for v in detect_merged_cells(xls_path).values())} 处合并却放行了")
        if not has_merge:
            check("I5b 无合并的 .xls 正常解析", bool(tx["columns"]), tx["table_name"])
    except MergedCellsError:
        check("I5 build_table_def 对该 .xls 的处置与合并区检测结果一致", has_merge,
              "无合并区却被拒绝")
else:
    skip("I4/I5 真实 .xls 端到端", "未设 PT_XLS=<.xls 文件路径>")


# ---------------------------------------------------------------------------
section("J 添加结果弹窗")

jdir = tempfile.mkdtemp(prefix="packtest_pop_")
jx1 = make_xlsx("甲工程.xlsx", {"S": pd.DataFrame({"编号": ["A1", "A2"]})})
jx2 = make_xlsx("乙工程.xlsx", {"S": pd.DataFrame({"编号": ["B1", "B2"]})})
jzip = os.path.join(jdir, "打包.zip")
with zipfile.ZipFile(jzip, "w") as z:
    z.writestr("一号.xlsx", open(jx1, "rb").read())
    z.writestr("子目录/二号.xlsx", open(jx2, "rb").read())


def last_popup():
    return POPUPS[-1] if POPUPS else ("", "")


def new_win():
    POPUPS.clear()
    return app_main.MainWindow()


w = new_win()
w._add_files([jx1, jx2])
title, text = last_popup()
check("J1 只拖散文件：一行且 xlsx/xls 计数正确",
      text == "添加 2 项文件，包含 2 项 xlsx 文件、0 项 xls 文件", text)
check("J2 弹窗标题为「添加结果」", title == "添加结果", title)
check("J3 全部成功时不出现「另有」", "另有" not in text, text)

w = new_win()
w._add_files([jzip])
title, text = last_popup()
check("J4 只拖 zip：zip 行计数按展开成功算",
      text == "添加 1 项 zip 文件，包含 2 项 xlsx 文件、0 项 xls 文件", text)

w = new_win()
w._add_files([jx1, jzip])
title, text = last_popup()
check("J5 混合输入：两行分别成文",
      text.splitlines() == ["添加 1 项文件，包含 1 项 xlsx 文件、0 项 xls 文件",
                            "添加 1 项 zip 文件，包含 2 项 xlsx 文件、0 项 xls 文件"], text)

w = new_win()
w._add_files([jx1, jx2])
w._add_files([jx1, jx2])
title, text = last_popup()
check("J6 重复添加报出跳过数", "另有 2 项重复已跳过，详见日志" in text, text)
check("J6b 重复跳过在日志里有对应行（数字可追溯）",
      w.log.toPlainText().count("[重复跳过]") == 2,
      log_lines(w.log.toPlainText(), "[重复跳过]"))
check("J6c 重复时不谎报新增", text.startswith("添加 2 项文件") or "另有" in text, text)

w = new_win()
bad = os.path.join(jdir, "坏文件.xlsx")
open(bad, "wb").write(b"not a workbook")
w._add_files([bad, jx1])
title, text = last_popup()
check("J7 解析失败计入异常行且不虚报成功",
      "添加 1 项文件，包含 1 项 xlsx 文件、0 项 xls 文件" in text
      and "另有 1 项解析失败" in text, text)

w = new_win()
w._add_files([jzip, fake])
title, text = last_popup()
check("J8 打不开的压缩包单独报数", "1 个压缩包无法展开" in text, text)

xlsx_n, xls_n = app_main.MainWindow._split_by_ext(
    ["a.xlsx", "b.xls", "c.xlsx", "d.XLS"])
check("J9 .xlsx 不会被重复计入 .xls 计数", (xlsx_n, xls_n) == (2, 2), (xlsx_n, xls_n))

w = new_win()
w._add_files([zempty])
title, text = last_popup()
check("J11 只拖空包也要弹窗（否则界面看着像没反应）",
      text == "另有 1 个压缩包内没有 Excel，详见日志", text)

w = new_win()
w._add_files([])
check("J12 没有输入时不弹空窗", not POPUPS, POPUPS)

w = new_win()
w._add_files([jx1, jx2, jzip, bad])
title, text = last_popup()
log_text = w.log.toPlainText()
check("J13 弹窗那句话同时打进日志", f"[添加结果] {text}" in log_body(log_text),
      log_text.splitlines()[-3:] if log_text else "")
check("J13b 日志里的汇总行位于逐条 [已解析] 之后",
      "[添加结果]" in log_text and "[已解析]" in log_text
      and log_text.rindex("[已解析]") < log_text.rindex("[添加结果]"))
check("J13c 混合输入时日志汇总含两行与异常行",
      "添加 2 项文件，包含 2 项 xlsx 文件、0 项 xls 文件" in log_text
      and "添加 1 项 zip 文件，包含 2 项 xlsx 文件、0 项 xls 文件" in log_text
      and "另有 1 项解析失败" in log_text)
first_log_line = (log_text.split("[添加结果] ", 1)[1].splitlines()[0]
                  if "[添加结果] " in log_text else None)
check("J13d 日志与弹窗文本逐字一致（只差 [添加结果] 前缀）",
      first_log_line == text.splitlines()[0], f"{first_log_line!r} vs {text.splitlines()[0]!r}")
check("J13e 日志里每条记录的首行都带年月日+钟点（缩进的明细行除外）",
      all(LOG_HEAD_RE.match(l) for l in log_text.splitlines()
          if l.strip() and not l.startswith("    ")),
      [l for l in log_text.splitlines()
       if l.strip() and not l.startswith("    ") and not LOG_HEAD_RE.match(l)][:3])

w = new_win()
w._add_files([zempty])
check("J14 只拖空包时日志同样有汇总行",
      "[添加结果] 另有 1 个压缩包内没有 Excel，详见日志" in w.log.toPlainText(),
      w.log.toPlainText().splitlines()[-1])

w = new_win()
w._add_files([])
check("J14b 无输入时日志不产生汇总行", "[添加结果]" not in w.log.toPlainText())

xls_src = os.environ.get("PT_XLS", "")
if xls_src and os.path.isfile(xls_src) and not detect_merged_cells(xls_src):
    jzip2 = os.path.join(jdir, "含xls.zip")
    with zipfile.ZipFile(jzip2, "w") as z:
        z.writestr("一号.xlsx", open(jx1, "rb").read())
        z.writestr("老格式.xls", open(xls_src, "rb").read())
    w = new_win()
    w._add_files([jzip2])
    title, text = last_popup()
    check("J10 zip 内混有真实 .xls 时分类计数正确",
          text == "添加 1 项 zip 文件，包含 1 项 xlsx 文件、1 项 xls 文件", text)
elif xls_src and os.path.isfile(xls_src):
    skip("J10 zip 内混有真实 .xls 时分类计数",
         f"PT_XLS 指向的样本含 {sum(len(v) for v in detect_merged_cells(xls_src).values())} "
         "处合并区会被拒绝导入，需换一份无合并的 .xls")
else:
    skip("J10 zip 内混有真实 .xls 时分类计数", "未设 PT_XLS=<.xls 文件路径>")


# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
section("K 高度可拖拽（Excel 页三段 + SQL 页两栏）")

from PySide6.QtWidgets import QSplitter
from PySide6.QtCore import Qt
w = app_main.MainWindow()
# 用 900 高而不是默认的 780：反向拖请求的是「表格 120 / 日志 600」，
# 780 高的窗口里分隔条只有约 434 可用高度，日志物理上就超不过表格，
# 断言会变成"窗口不够高"而不是"拖不动"。
w.resize(1100, 900)
w.show()
_app.processEvents()
sp = w.excel_split
check("K1 Excel 导入页用垂直分隔条承载三段",
      isinstance(sp, QSplitter) and sp.count() == 3, f"count={sp.count()}")
check("K1b 分隔条方向为垂直", sp.orientation() == Qt.Vertical)
check("K2 三段依次是拖拽区 / 待导入文件表 / 日志面板",
      sp.widget(0) is w.drop and isinstance(sp.widget(1), app_main.QGroupBox)
      and sp.widget(2).findChild(app_main.QPlainTextEdit) is w.log,
      [type(sp.widget(i)).__name__ for i in range(3)])
check("K3 不允许把任何一段拖到 0 高", sp.childrenCollapsible() is False)
check("K4 拖拽手柄可见（宽度 6）", sp.handleWidth() == 6, sp.handleWidth())
check("K0 首屏默认：表格段高于日志段，表格视口至少露 5 行（用户报过表格只剩不到两行）",
      sp.sizes()[1] > sp.sizes()[2]
      and w.file_table.viewport().height()
      >= 5 * w.file_table.verticalHeader().defaultSectionSize(),
      (sp.sizes(), w.file_table.viewport().height(),
       w.file_table.verticalHeader().defaultSectionSize()))
# 量的是日志「段」（含上方清除日志那排），不是里面那个文本框：默认高度口径是
# 用户看到的那一段，面板高度还包含按钮行，只量文本框会被按钮行差出十几像素
_log_panel1 = sp.widget(2)
_log_min1 = max(_log_panel1.minimumHeight(), _log_panel1.minimumSizeHint().height())
check("K0b 首屏日志默认压在自己的最小高度（4 行），不是 200px",
      abs(_log_panel1.height() - _log_min1) <= 4,
      (_log_panel1.height(), _log_min1, sp.sizes()))

sp.setSizes([100, 600, 100])
_app.processEvents()
h = [sp.widget(i).height() for i in range(3)]
check("K5 把分隔条往下拖：待导入表高于日志面板，且吃到让出的空间",
      h[1] > h[2] and h[0] > 0, h)
sp.setSizes([100, 120, 600])
_app.processEvents()
h2 = [sp.widget(i).height() for i in range(3)]
check("K5b 反向拖：日志高于表格，表格确实被压缩",
      h2[2] > h2[1] and h2[1] < h[1], f"{h} -> {h2}")
check("K5c 三段高度此消彼长，总和守恒",
      abs(sum(h) - sum(h2)) <= 2, f"{sum(h)} vs {sum(h2)}")
check("K6 表格高度由分隔条控制后仍拿得到视口",
      w.file_table.viewport().height() > 0, w.file_table.viewport().height())
check("K7 清除日志按钮随日志面板一起缩放，不会掉出可视区",
      sp.widget(2).findChild(app_main.QPushButton) is w.btn_clear_log)
check("K8 固定件不在分隔条内（提示条/操作条/进度条不跟着被挤压）",
      w.lbl_imp_db.parent() is not sp and w.progress.parent() is not sp
      and w.btn_import.parent() is not sp)
check("K9 初始三段高度都大于 0", all(x > 0 for x in sp.sizes()), sp.sizes())
# ---- SQL 脚本改名入库页：三段可拖拽（拖拽区 / 表格区 / 日志），与第一页同一形状 ----
from PySide6.QtWidgets import QTableWidget

w.main_tabs.setCurrentIndex(1)
_app.processEvents()
sp2 = w.sql_split
check("K10 SQL 页用垂直分隔条承载「拖拽区 / 文件表格区 / 日志区」三段，且不可折叠",
      isinstance(sp2, QSplitter) and sp2.orientation() == Qt.Vertical
      and sp2.count() == 3 and sp2.childrenCollapsible() is False
      and sp2.handleWidth() == 6, sp2.sizes())
check("K11 三段正是拖拽区、待处理文件表与 SQL 日志（顺序与第一页一致）",
      sp2.widget(0) is w.sql_drop
      and sp2.widget(1).findChild(QTableWidget) is w.sql_table
      and sp2.widget(2).findChild(app_main.QPlainTextEdit) is w.sql_log,
      [type(sp2.widget(i)).__name__ for i in range(3)])
check("K11b SQL 页首屏默认：表格段高于日志段，表格视口至少露 5 行",
      sp2.sizes()[1] > sp2.sizes()[2]
      and w.sql_table.viewport().height()
      >= 5 * w.sql_table.verticalHeader().defaultSectionSize(),
      (sp2.sizes(), w.sql_table.viewport().height(),
       w.sql_table.verticalHeader().defaultSectionSize()))
# 与 K0b 同一口径：量日志段（含上方清除日志那排），不是里面那个文本框
_log_panel2 = sp2.widget(2)
_log_min2 = max(_log_panel2.minimumHeight(), _log_panel2.minimumSizeHint().height())
check("K11c SQL 页日志默认也压在自己的最小高度（4 行），与第一页同一口径",
      abs(_log_panel2.height() - _log_min2) <= 4,
      (_log_panel2.height(), _log_min2, sp2.sizes()))
sp2.setSizes([100, 600, 100])
_app.processEvents()
u = [sp2.widget(i).height() for i in range(3)]
sp2.setSizes([100, 100, 600])
_app.processEvents()
d = [sp2.widget(i).height() for i in range(3)]
check("K12 往上拖：日志最高、表格区确实被压缩；反向拖：表格区高于日志",
      d[2] > d[1] and d[1] < u[1] and u[1] > u[2], f"{u} -> {d}")
check("K13 三段此消彼长，总和守恒", abs(sum(u) - sum(d)) <= 2, f"{sum(u)} vs {sum(d)}")
check("K14 前缀行与大写开关留在表格区；保存/执行/生成与覆盖开关已挪到分隔条外的底部动作条",
      sp2.widget(1).isAncestorOf(w.ed_sql_prefix)
      and sp2.widget(1).isAncestorOf(w.chk_sql_upper)
      and not sp2.widget(1).isAncestorOf(w.btn_sql_run)
      and not sp2.isAncestorOf(w.btn_sql_run)
      and not sp2.isAncestorOf(w.chk_sql_drop))
check("K15 清空按钮排在表格区动作条最左（与第一页那颗同位置同文案），清除日志随日志段，拖拽区在分隔条里",
      sp2.widget(1).isAncestorOf(w.btn_sql_clear)
      and w.btn_sql_clear.text() == "清空待上传文件"
      and sp2.widget(2).findChild(app_main.QPushButton) is w.btn_sql_clear_log
      and w.lbl_sql_db.parent() is not sp2 and sp2.isAncestorOf(w.sql_drop))
check("K16 表格由分隔条控制高度后仍拿得到视口",
      w.sql_table.viewport().height() > 0, w.sql_table.viewport().height())
w.sql_log.appendPlainText("分隔条缩放后日志仍可写入")
check("K17 缩放到最小后每段仍能显示内容（不会拖成 0 高）",
      min(d) > 0 and min(u) > 0 and "分隔条缩放后" in w.sql_log.toPlainText(), d)
check("K18 SQL 页的拖拽区收 .sql，点它就是打开选择器（与第一页同一套交互）",
      w.sql_drop.acceptDrops()
      and w.sql_drop.label.text() == app_main.DropArea.SQL_HINT
      and "SQL" in w.sql_drop.toolTip(), w.sql_drop.label.text())


check("K19 两张主表格左上都有标识，标题里带这一张表怎么操作",
      box_title(w.file_table) == app_main.FILE_TABLE_TITLE
      and box_title(w.sql_table) == app_main.SQL_TABLE_TITLE
      and "待导入文件" in box_title(w.file_table)
      and "待处理的脚本表" in box_title(w.sql_table),
      (box_title(w.file_table), box_title(w.sql_table)))
check("K20 表格区那层组框不影响三段分栏：中间那一段仍是装着表格的组框",
      isinstance(sp2.widget(1), app_main.QGroupBox)
      and sp2.widget(1).findChild(app_main.QTableWidget) is w.sql_table,
      type(sp2.widget(1)).__name__)
# 底部动作条与第一页同一形状：覆盖开关在左，四颗动作按钮在右；只重排，没有新增按钮。
# 四颗按钮包在横向滚动区里（窄屏才出滚动条），所以要先穿过那一层再读按钮顺序
_sql_page = w.main_tabs.widget(1)
_op = _sql_page.layout().itemAt(_sql_page.layout().count() - 1).layout()
_op_items = [_op.itemAt(i).widget() for i in range(_op.count())]
_run_box = _op_items[-1]
_run_row = _run_box.widget().layout()
_run_items = [_run_row.itemAt(i).widget() for i in range(_run_row.count())]
check("K21 底部动作条：覆盖开关在最左，保存/执行/生成四颗在最右（与第一页同一形状）",
      _op_items[0] is w.chk_sql_drop
      and all(x is None for x in _op_items[1:-1])
      and all(x is None for x in _run_items[:-4])
      and _run_items[-4:] == [w.btn_sql_save, w.btn_sql_run,
                              w.btn_sql_gen_std, w.btn_sql_gen_simple],
      [type(x).__name__ if x is not None else "弹簧" for x in _op_items]
      + ["|"] + [b.text() if hasattr(b, "text") else "弹簧"
                 for b in _run_items])
check("K22 覆盖开关默认不勾选，提示写清只影响执行、不改脚本正文",
      w.chk_sql_drop.isChecked() is False
      and "只影响执行" in w.chk_sql_drop.toolTip()
      and "CREATE TABLE" in w.chk_sql_drop.toolTip())
_sql_btns = sorted(b.text() for b in _sql_page.findChildren(app_main.QPushButton) if b.text())
check("K23 第二页按钮集合与改动前完全一致（只重排布局，没有新增按钮）",
      _sql_btns == sorted([
          "批量应用前缀", "替换首个前缀", "清空待上传文件", "清除日志",
          "保存修改后ddl脚本", "执行修改后ddl脚本到当前库",
          "生成标准excel文件", "生成简易excel文件"]), _sql_btns)
w.main_tabs.setCurrentIndex(0)
_app.processEvents()
w.close()

# ---------------------------------------------------------------------------
section("V 覆盖已存在表（执行副本先 DROP，不改脚本正文）")

_vdir = tempfile.mkdtemp(prefix="pt_drop_")
_vsql = os.path.join(_vdir, "v.sql")
_vsrc = ("-- CREATE TABLE ghost (id INT);\n"
         "/* CREATE TABLE ghost2 (id INT); */\n"
         "CREATE TABLE `db`.`t_v1` (id INT);\n"
         "CREATE TABLE t_v2 (id INT);\n"
         "CREATE TABLE t_v1 (id INT);\n")
# newline="" 钉住字节形状：Windows 文本模式会把 \n 写成 \r\n，
# 而断言比的是脚本原文；不钉住的话，读回来带 CRLF 会被误判成"执行副本改了正文"
open(_vsql, "w", encoding="utf-8", newline="").write(_vsrc)
vw = app_main.MainWindow()
vw.on_sql_files_dropped([_vsql])
vw.cb_db.addItem("fakedb", "fakedb")
vw.cb_db.setCurrentIndex(vw.cb_db.count() - 1)
_cap = {}
_orig_run_modal = vw._run_modal


def _cap_modal(title, work, args=(), on_done=None, log_view=None):
    _cap["items"] = args[0]


vw._run_modal = _cap_modal
vw.chk_sql_drop.setChecked(True)
vw.on_sql_execute()
_vitem = _cap.get("items") or []
_vtext = _vitem[0][1] if _vitem else ""
check("V1 勾选后执行副本按出现顺序倒序补 DROP，schema 限定名整段保留",
      _vtext.startswith("DROP TABLE IF EXISTS t_v1;\n"
                        "DROP TABLE IF EXISTS t_v2;\n"
                        "DROP TABLE IF EXISTS `db`.`t_v1`;\n\n"
                        + _vsrc),
      _vtext.splitlines()[:4])
check("V2 注释里的建表不触发删除；日志点名要删的表与原脚本仍原样",
      "DROP TABLE IF EXISTS ghost" not in _vtext
      and vw.sql_files[0]["text"] == _vsrc
      and "[覆盖] v.sql：执行前先 DROP `db`.`t_v1`、t_v2、t_v1"
      in vw.sql_log.toPlainText(),
      log_lines(vw.sql_log.toPlainText(), "[覆盖]"))
check("V3 勾选后的开始执行那一行写明覆盖口径",
      "已勾选「覆盖已存在表」，执行副本先 DROP 脚本要建的表"
      in vw.sql_log.toPlainText())
_cap.clear()
vw.chk_sql_drop.setChecked(False)
vw.on_sql_execute()
_vitem2 = _cap.get("items") or []
_last_header = [l for l in vw.sql_log.toPlainText().splitlines()
                if "开始执行脚本" in l][-1]
check("V4 不勾选时执行副本与脚本正文完全一致，开始执行那一行不写覆盖口径",
      _vitem2 and _vitem2[0][1] == _vsrc
      and "覆盖已存在表" not in _last_header,
      (_vitem2[0][1][:20] if _vitem2 else None, _last_header))
vw._run_modal = _orig_run_modal
vw.close()

# ---------------------------------------------------------------------------
section("L 字段类型映射（金额语义 + 精度按数据校核）")

from core.infer import _is_money


def t_of(data):
    return infer_columns(pd.DataFrame(data))


def one(data):
    cols = t_of(data)
    return cols[0]["type"], cols[0].get("type_note", "")


t, note = one({"订单金额": [1000, 20000]})
check("L1 金额列样本为整数也不给 INT", t == "DECIMAL(16,2)", t)
check("L1b 该修正写明理由", "金额语义" in note and "DECIMAL(16,2)" in note, note)
check("L2 单价统一 DECIMAL(16,2)", one({"单价": [12.34, 8.5]})[0] == "DECIMAL(16,2)")
check("L2b 小数列不再一律给 DECIMAL(20,6)",
      one({"实测两位小数": [1.5, 2.25]})[0] != "DECIMAL(20,6)",
      one({"实测两位小数": [1.5, 2.25]})[0])
check("L3 金额实测 4 位小数时放宽精度而非截断",
      one({"结算金额": [1.2345, 9.9]})[0] == "DECIMAL(18,4)", one({"结算金额": [1.2345]})[0])
check("L4 非金额小数按实测精度定宽", t_of({"系数": [1.5, 2.25]})[0]["type"] == "DECIMAL(6,2)",
      t_of({"系数": [1.5, 2.25]})[0]["type"])
check("L5 经纬度 7 位小数被完整保住",
      one({"中心点纬度": [39.9046897, 39.1]})[0] == "DECIMAL(11,7)",
      one({"中心点纬度": [39.9046897]})[0])
check("L6 普通数量列仍是 INT", one({"数量": [10, 20]})[0] == "INT")
check("L6b 编号列仍是 VARCHAR（前导零保护未被破坏）",
      one({"编号": ["007", "008"]})[0].startswith("VARCHAR"))
check("L7 英文金额字段名命中",
      all(one({k: [100, 200]})[0] == "DECIMAL(16,2)"
          for k in ("amount", "unit_price", "total_cost", "tax_fee")))
check("L7b total_count 不因 total 误判为金额",
      one({"total_count": [10, 20]})[0] == "INT", one({"total_count": [10]})[0])
check("L8 地名「费县」不被误判成金额", one({"费县": [1, 2]})[0] == "INT")
check("L8b「评价」不被误判成金额", one({"评价": [5, 4]})[0] == "INT")
check("L8c 利率是比率不是金额，按实测小数位定宽",
      one({"年利率": [0.0325, 0.05]})[0] == "DECIMAL(8,4)", one({"年利率": [0.0325]})[0])
check("L8d interest_rate 同样否决金额语义", one({"interest_rate": [0.05]})[0] == "DECIMAL(6,2)",
      one({"interest_rate": [0.05]})[0])
check("L8e 否决只对率生效：利息 仍算金额", one({"利息": [100, 200]})[0] == "DECIMAL(16,2)")
check("L8f 非金额小数列不产生修正说明（避免日志噪音）",
      not t_of({"系数": [1.5, 2.25]})[0].get("type_note"),
      t_of({"系数": [1.5, 2.25]})[0].get("type_note"))
check("L9 超大金额自动放宽精度", one({"总金额": [1234567890123456.78, 1.0]})[0] == "DECIMAL(18,2)",
      one({"总金额": [1234567890123456.78]})[0])
check("L9b 科学计数法大数按展开后的位数放宽（不按字面切分）",
      one({"极端金额": [1e25, 1.0]})[0] == "DECIMAL(28,2)", one({"极端金额": [1e25]})[0])
check("L9c 小数按实测位数给宽（1.1 → DECIMAL(5,1)）",
      one({"精度样本": [1.10]})[0] == "DECIMAL(5,1)", one({"精度样本": [1.10]})[0])
check("L9d 非数值内容不参与位数统计", one({"混合": ["abc", "def"]})[0].startswith("VARCHAR"),
      one({"混合": ["abc", "def"]})[0])
check("L9e 千分位字符串按去逗号后的位数算（整数 4 位 + 2 位余量 + 2 位小数）",
      one({"含千分位": ["1,234.56", "12.3"]})[0] == "DECIMAL(8,2)",
      one({"含千分位": ["1,234.56"]})[0])
check("L9f 金额列同时有极小小数与 14 位整数时不被 30 位上限挤掉整数位（曾在写入时报 1264）",
      one({"unit_price_amount": [1e-18, 12345678901234.5]})[0] == "DECIMAL(32,18)",
      one({"unit_price_amount": [1e-18, 12345678901234.5]})[0])
check("L9g 18 位金额字符串按字面位数定宽",
      one({"unit_price_amount": ["999999999999999999.99"]})[0] == "DECIMAL(20,2)",
      one({"unit_price_amount": ["999999999999999999.99"]})[0])
_l9h = one({"单价": [0.00000000000000000000000000000000000000001]})
check("L9h 小数位超 30 位退文本并留痕（DECIMAL 装不下就别硬塞）",
      _l9h[0].startswith("VARCHAR") and "DECIMAL" in _l9h[1], _l9h)

from core.mysql_io import _convert as _conv
_dv, _de = _conv("999999999999999999.99", "DECIMAL(20,2)")
check("L9i DECIMAL 字符串按 Decimal 转换，不经 float 进位",
      _de is None and str(_dv) == "999999999999999999.99", (_dv, _de))
check("L9j NaN 文本在转换阶段就按非法数字告警（不交给 MySQL 报 1366）",
      _conv("NaN", "DECIMAL(10,2)") == (None, "值 'NaN' 不是合法数字"),
      _conv("NaN", "DECIMAL(10,2)"))
check("L9k 无穷大数值按非法数字告警（避免 Out of range）",
      _conv(float("inf"), "DOUBLE")[0] is None, _conv(float("inf"), "DOUBLE"))
check("L10 _is_money 直接判定符合预期",
      _is_money("订单金额", "dingdanjine") and not _is_money("员工编号", "yuangongbianhao"))

money_ddl = build_ddl("t_order", "订单表",
                      [{"field": "dingdanjine", "comment": "订单金额", "type": "DECIMAL(16,2)"},
                       {"field": "danjia", "comment": "单价", "type": "DECIMAL(16,2)"}],
                      False)
check("L11 DDL 里两个金额列口径一致",
      money_ddl.count("DECIMAL(16,2)") == 2, money_ddl)

w = app_main.MainWindow()
xm = make_xlsx("订单表.xlsx", {"S": pd.DataFrame(
    {"订单编号": ["A1", "A2"], "订单金额": [100, 200], "单价": [9.5, 10.0]})})
POPUPS.clear()
w._add_files([xm])
check("L12 日志出现 [类型修正] 且点明列名",
      "[类型修正]" in w.log.toPlainText() and "订单金额" in w.log.toPlainText(),
      log_lines(w.log.toPlainText(), "[类型修正]"))

w = app_main.MainWindow()
POPUPS.clear()
w._add_files([make_xlsx("无金额.xlsx", {"S": pd.DataFrame({"编号": ["a"], "名": ["b"]})})])
check("L13 没有金额列时不产生 [类型修正] 噪声",
      "[类型修正]" not in w.log.toPlainText())

meta_only = pd.DataFrame({
    "序号": [1], "字段名(英文)": ["amount"], "字段注释(中文)": ["金额"],
    "数据类型": ["INT"], "长度": [""], "是否主键": [""], "是否可为空": [""]})
w_meta = make_xlsx("人工定义（t_manual）.xlsx", {"字段定义": meta_only})
tm = build_table_def(w_meta)
check("L14 结构定义模式以人工类型为准，语义层不插手",
      tm["columns"][0]["type"] == "INT" and not tm["columns"][0].get("type_note"),
      tm["columns"][0]["type"])

# ---------------------------------------------------------------------------
section("M 字段命名规则（蛇形拼音 / 首字母降级 / 30 字符上限）")

from core.infer import _col_to_field, _FIELD_MAX_LEN, parse_table_name_and_comment, \
    sanitize_table_name

check("M0 上限常量就是 30", _FIELD_MAX_LEN == 30, _FIELD_MAX_LEN)
check("M1 中文表头→蛇形全拼", _col_to_field("订单金额") == "ding_dan_jin_e",
      _col_to_field("订单金额"))
check("M1b 逐音节下划线分隔", _col_to_field("员工编号") == "yuan_gong_bian_hao",
      _col_to_field("员工编号"))
check("M2 短的用全拼不降级", _col_to_field("单价") == "dan_jia"
      and _col_to_field("数量") == "shu_liang")
check("M3 全拼在 30 字符内即使较长也保留全拼",
      _col_to_field("水库基础信息表") == "shui_ku_ji_chu_xin_xi_biao"
      and len(_col_to_field("水库基础信息表")) == 26, _col_to_field("水库基础信息表"))
long_name = _col_to_field("北京市海淀区水务局下属大型水库枢纽工程基础信息汇总表")
check("M4 全拼超 30 则整列强制首字母",
      "_" not in long_name and long_name == "bjshdqswjxsdxsksngcjcxxhzb", long_name)
check("M4b 首字母形式紧凑无下划线，比逐字母加下划线省一半长度",
      len(long_name) <= 30 and len(long_name) < len("_".join(long_name)), len(long_name))
check("M5 任何输入产出都不超过 30 字符",
      all(len(_col_to_field(x)) <= 30 for x in [
          "订单金额", "北京市海淀区水务局下属大型水库枢纽工程基础信息汇总表",
          "甲" * 40, "a" * 100, "7号线", "（）", "", "  ", "名称/编号、代码"]),
      [len(_col_to_field(x)) for x in ["a" * 100, "甲" * 40]])
check("M5b 40 个汉字也只占 30 字符（首字母再截断）",
      len(_col_to_field("甲" * 40)) == 30, _col_to_field("甲" * 40))
check("M6 纯英文表头原样蛇形，不做拼音转换",
      _col_to_field("station_code") == "station_code"
      and _col_to_field("ID") == "id" and _col_to_field("Remark") == "remark",
      [_col_to_field("station_code"), _col_to_field("ID")])
check("M6b 英文带空格/斜杠也切成下划线", _col_to_field("Station Code") == "station_code",
      _col_to_field("Station Code"))
check("M7 数字开头补 f_ 前缀", _col_to_field("7号线") == "f_7_hao_xian",
      _col_to_field("7号线"))
check("M8 空表头兜底为 col", _col_to_field("") == "col" and _col_to_field("  ") == "col"
      and _col_to_field("（）") == "col")
check("M9 产出都是合法 MySQL 标识符（小写字母/数字/下划线，非数字开头）",
      all(re.fullmatch(r"[a-z_][a-z0-9_]*", _col_to_field(x)) for x in
          ["订单金额", "7号线", "甲" * 40, "station_code", "名称/编号、代码"]))
cols_e2e = t_of({"订单编号": ["A1"], "订单金额": [1.5], "供应商名称": ["x"]})
check("M10 端到端：infer_columns 产出的字段名符合新规则",
      [c["field"] for c in cols_e2e] == ["ding_dan_bian_hao", "ding_dan_jin_e",
                                         "gong_ying_shang_ming_cheng"],
      [c["field"] for c in cols_e2e])
check("M11 字段名与中文注释仍然并存（注释不受命名规则影响）",
      [c["comment"] for c in cols_e2e] == ["订单编号", "订单金额", "供应商名称"])
dup3 = infer_columns(pd.DataFrame([["a", "b", "c"]], columns=["名称", "名称", "名称"]))
check("M12 首字母撞名时仍靠 _2/_3 去重",
      [c["field"] for c in dup3] == ["ming_cheng", "ming_cheng_2", "ming_cheng_3"],
      [c["field"] for c in dup3])
same = infer_columns(pd.DataFrame([["a", "b"]], columns=["时间", "实际"]))
check("M12b 全拼相近但不同的列不会被错误合并",
      [c["field"] for c in same] == ["shi_jian", "shi_ji"], [c["field"] for c in same])

mm = [_col_to_field(f"累年月日降水量≥{v}mm平均日数(d)")
      for v in ("1.0", "10.0", "100.0", "150.0")]
check("M13 首字母模式下数字音节整段保留（否则 1.0/10.0/100.0 全塌成同一个名）",
      len(set(mm)) == 4, mm)
check("M13b 括号外的单位字母同样保留（括号里的 (d) 按注释剥掉）",
      mm[0].endswith("mmpjrs"), mm[0])
check("M13c 降级后仍不超上限", all(len(x) <= 30 for x in mm), [len(x) for x in mm])

# 表头里成对括号包起来的是给填表人看的注释，不是字段名的一部分（用户 2026-10-20 点名）：
# 「发布日期（yyyy-MM-dd）」要出 fa_bu_ri_qi，而不是 fa_bu_ri_qi_yyyy_mm_dd。
check("M15 括号注释不进字段名（半角与全角都不进）",
      _col_to_field("发布日期（yyyy-MM-dd）") == "fa_bu_ri_qi"
      and _col_to_field("发布日期(yyyy-MM-dd)") == "fa_bu_ri_qi",
      [_col_to_field("发布日期（yyyy-MM-dd）"), _col_to_field("发布日期(yyyy-MM-dd)")])
check("M15b 单位与取值注释同样不进名字",
      _col_to_field("金额（元）") == "jin_e"
      and _col_to_field("是否已婚【是/否】") == "shi_fou_yi_hun"
      and _col_to_field("总库容（亿立方米）") == "zong_ku_rong",
      [_col_to_field("金额（元）"), _col_to_field("是否已婚【是/否】"),
       _col_to_field("总库容（亿立方米）")])
check("M15c 必填标记加括号注释一起剥净",
      _col_to_field("★（必填）联系电话") == "lian_xi_dian_hua",
      _col_to_field("★（必填）联系电话"))
check("M15d 嵌套括号不留半个尾巴",
      _col_to_field("单价（含税（13%））") == "dan_jia", _col_to_field("单价（含税（13%））"))
check("M15e 整格就是括号时不剥成空（否则退化成 col）",
      _col_to_field("（备注说明）") == "bei_zhu_shuo_ming", _col_to_field("（备注说明）"))
head_note = infer_columns(pd.DataFrame([["1", "2"]],
                                       columns=["发布日期（yyyy-MM-dd）", "发布日期"]))
check("M16 剥完撞名仍靠 _2 去重，两张列不会合并成一列",
      [c["field"] for c in head_note] == ["fa_bu_ri_qi", "fa_bu_ri_qi_2"],
      [c["field"] for c in head_note])
check("M17 列注释保留原始表头（括号里的口径不能丢）",
      [c["comment"] for c in head_note] == ["发布日期（yyyy-MM-dd）", "发布日期"],
      [c["comment"] for c in head_note])

# 同一句口径，手写的人不一定会把括号配齐，也可能干脆用冒号/空格挂着
check("M18 日期格式串没括号也照样剥（冒号、空格、连字符、斜杠都算分隔）",
      [_col_to_field(h) for h in [
          "发布日期：yyyy-MM-dd", "发布日期:yyyy-MM-dd", "发布日期 yyyy-MM-dd",
          "发布日期-yyyy-MM-dd", "发布日期/yyyy/MM/dd", "发布日期 YYYYMMDD"]]
      == ["fa_bu_ri_qi"] * 6
      and _col_to_field("填表日期 yyyymmdd") == "tian_biao_ri_qi",
      [_col_to_field(h) for h in ["发布日期：yyyy-MM-dd", "发布日期 yyyy-MM-dd",
                                  "发布日期/yyyy/MM/dd", "填表日期 yyyymmdd"]])
check("M18b 括号没配全也剥（漏右半边就从左括号起整段剥，漏左半边靠掩码识别）",
      _col_to_field("发布日期（yyyy-MM-dd") == "fa_bu_ri_qi"
      and _col_to_field("发布日期(YYYYMMDD") == "fa_bu_ri_qi"
      and _col_to_field("发布日期 yyyy-MM-dd）") == "fa_bu_ri_qi",
      [_col_to_field("发布日期（yyyy-MM-dd"), _col_to_field("发布日期 yyyy-MM-dd）")])
check("M18c 方头括号〖〗同样是注释",
      _col_to_field("发布日期〖yyyy-MM-dd〗") == "fa_bu_ri_qi",
      _col_to_field("发布日期〖yyyy-MM-dd〗"))
check("M18d 剥完一个字段都不剩时不剥（宁可留着原名，也不能变成 col）",
      _col_to_field("yyyy-MM-dd") == "yyyy_mm_dd", _col_to_field("yyyy-MM-dd"))
# 反向守卫：掩码判定很容易顺手吃掉真名字，这几条钉住"不许扩大"
check("M19 掩码规则不误伤单位字母与英文列名",
      _col_to_field("累年月日降水量≥1.0mm平均日数") == "lnyrjsl10mmpjrs"
      and _col_to_field("station_code") == "station_code"
      and _col_to_field("create_time") == "create_time"
      and _col_to_field("ID") == "id",
      [_col_to_field("累年月日降水量≥1.0mm平均日数"), _col_to_field("create_time")])
check("M19b 不含汉字的表头整条不参与掩码剥离",
      _col_to_field("MM-dd") == "mm_dd" and _col_to_field("qty") == "qty",
      [_col_to_field("MM-dd"), _col_to_field("qty")])
check("M19c 尾部拉丁词没配冒号分隔时不猜它是注释（数量 qty / 编号 No.照旧）",
      _col_to_field("数量 qty") == "shu_liang_qty"
      and _col_to_field("身份证ID") == "shen_fen_zheng_id",
      [_col_to_field("数量 qty"), _col_to_field("身份证ID")])

# 命名开关：表名与字段名取拼音首字母（用户 2026-10-20 点名要的开关，默认全拼照旧）
check("M21 首字母开关：汉字音节一律取首字母，默认全拼不受影响",
      _col_to_field("订单金额", initials=True) == "ddje"
      and _col_to_field("订单金额") == "ding_dan_jin_e",
      [_col_to_field("订单金额", initials=True), _col_to_field("订单金额")])
check("M21b 开关下英文/数字段整段保留，数字开头照旧补 f_",
      _col_to_field("订单order金额", initials=True) == "ddorderje"
      and _col_to_field("7号线", initials=True) == "f_7hx",
      [_col_to_field("订单order金额", initials=True),
       _col_to_field("7号线", initials=True)])
check("M21c 表名同用一套开关，且与超长降级形式一致",
      sanitize_table_name("水库基础信息", initials=True) == "skjcxx"
      and _col_to_field("北京市海淀区水务局下属大型水库枢纽工程基础信息汇总表",
                        initials=True) == "bjshdqswjxsdxsksngcjcxxhzb",
      [sanitize_table_name("水库基础信息", initials=True),
       _col_to_field("北京市海淀区水务局下属大型水库枢纽工程基础信息汇总表",
                     initials=True)])
check("M21d 文件名括号里的英文名不受开关影响，无括号文件名受影响",
      parse_table_name_and_comment("水库基础信息（dim_reservoir_info）.xlsx",
                                   initials=True)[0] == "dim_reservoir_info"
      and parse_table_name_and_comment("水库信息表.xlsx", initials=True)[0] == "skxxb",
      [parse_table_name_and_comment("水库基础信息（dim_reservoir_info）.xlsx",
                                    initials=True)[0],
       parse_table_name_and_comment("水库信息表.xlsx", initials=True)[0]])
_ini = build_table_def(make_xlsx("水库信息表.xlsx", {"S": pd.DataFrame({"订单金额": []})}),
                       initials=True)
check("M21e 端到端：开关从 build_table_def 一路传到表名与字段名",
      _ini["table_name"] == "skxxb"
      and [c["field"] for c in _ini["columns"]] == ["ddje"],
      (_ini["table_name"], [c["field"] for c in _ini["columns"]]))

# 大小写开关：默认小写照旧，勾上后表名与字段名整体转大写（用户 2026-10-21 点名）
check("M22 大小写开关：字段名与表名转大写，默认小写不受影响",
      _col_to_field("订单金额", upper=True) == "DING_DAN_JIN_E"
      and _col_to_field("订单金额") == "ding_dan_jin_e"
      and sanitize_table_name("水库基础信息", upper=True) == "SHUI_KU_JI_CHU_XIN_XI",
      [_col_to_field("订单金额", upper=True), sanitize_table_name("水库基础信息", upper=True)])
check("M22b 与首字母开关叠加：先定首字母再转大写，英文/数字段与降级口径不变",
      _col_to_field("订单金额", initials=True, upper=True) == "DDJE"
      and _col_to_field("订单order金额", initials=True, upper=True) == "DDORDERJE"
      and _col_to_field("7号线", initials=True, upper=True) == "F_7HX",
      [_col_to_field("订单金额", initials=True, upper=True),
       _col_to_field("订单order金额", initials=True, upper=True)])
check("M22c 括号里显式写下的英文表名同样跟大小写开关，无括号文件名也转",
      parse_table_name_and_comment("水库基础信息（dim_reservoir_info）.xlsx", upper=True)[0]
      == "DIM_RESERVOIR_INFO"
      and parse_table_name_and_comment("水库信息表.xlsx", upper=True)[0] == "SHUI_KU_XIN_XI_BIAO",
      [parse_table_name_and_comment("水库基础信息（dim_reservoir_info）.xlsx", upper=True)[0],
       parse_table_name_and_comment("水库信息表.xlsx", upper=True)[0]])
check("M22d 兜底标识符也跟着转：空名表名与空表头字段不留小写尾巴",
      sanitize_table_name("", upper=True) == "T_IMPORT_DATA"
      and _col_to_field("", upper=True) == "COL"
      and _col_to_field("（备注说明）", upper=True) == "BEI_ZHU_SHUO_MING",
      [sanitize_table_name("", upper=True), _col_to_field("", upper=True)])
_up = build_table_def(make_xlsx("大写开关表（t_upper_switch）.xlsx",
                                {"S": pd.DataFrame({"订单金额": [], "发布日期": []})}),
                      upper=True)
check("M22e 端到端：大小写开关传到表名、字段名与空列的按名定类型",
      _up["table_name"] == "T_UPPER_SWITCH"
      and [c["field"] for c in _up["columns"]] == ["DING_DAN_JIN_E", "FA_BU_RI_QI"]
      and [c["type"] for c in _up["columns"]] == ["DECIMAL(16,2)", "DATE"]
      and all(c.get("type_note") for c in _up["columns"]),
      (_up["table_name"], [(c["field"], c["type"]) for c in _up["columns"]]))
_te_up = build_table_def(
    make_xlsx("空定义（t_meta_up）.xlsx", {"字段定义": _meta_empty}), upper=True)
check("M22f 结构定义模式：人工写的字段名原样，表名跟开关转大写",
      _te_up["table_name"] == "T_META_UP"
      and [c["field"] for c in _te_up["columns"]] == ["publish_date"],
      (_te_up["table_name"], [c["field"] for c in _te_up["columns"]]))

# 图形版默认口径（用户 2026-10-21）：两颗开关启动即勾上，新加文件直接首字母 + 大写
_dw = app_main.MainWindow()
check("M23 图形版两颗命名开关默认勾选",
      _dw.chk_initials.isChecked() and _dw.chk_upper.isChecked(),
      (_dw.chk_initials.isChecked(), _dw.chk_upper.isChecked()))
POPUPS.clear()
_dw._add_files([make_xlsx("水库信息表.xlsx",
                          {"S": pd.DataFrame({"订单金额": [12.5]})})])
check("M23b 默认口径端到端：表名 SKXXB、字段名 DDJE",
      len(_dw.tables) == 1 and _dw.tables[0]["table_name"] == "SKXXB"
      and [c["field"] for c in _dw.tables[0]["columns"]] == ["DDJE"],
      [(t["table_name"], [c["field"] for c in t["columns"]]) for t in _dw.tables])
_dw.chk_initials.setChecked(False)
_dw.chk_upper.setChecked(False)
check("M23c 取消勾选各留一行切换日志",
      "[提示] 命名规则切换为：全拼" in _dw.log.toPlainText()
      and "[提示] 表名与字段名大小写切换为：小写" in _dw.log.toPlainText())
POPUPS.clear()
_dw._add_files([make_xlsx("水库信息表（n_lowercheck）.xlsx",
                          {"S": pd.DataFrame({"订单金额": [12.5]})})])
check("M23d 取消后回到全拼、小写",
      len(_dw.tables) == 2 and _dw.tables[-1]["table_name"] == "n_lowercheck"
      and _dw.tables[-1]["columns"][0]["field"] == "ding_dan_jin_e",
      (_dw.tables[-1]["table_name"], _dw.tables[-1]["columns"][0]["field"]))

# 真实语料：从内部数据包的 create_table*.sql 中文列注释里取，量化碰撞率。
# 语料在仓库之外，不设环境变量时显式记为 SKIP，不影响用例集全绿。
corpus = []
try:
    for p in glob.glob(os.path.join(os.environ.get("CORPUS_DDL", ""), "*/create_table*.sql")):
        for m in re.finditer(r"COMMENT\s+'([^']+)'", open(p, encoding="utf-8").read()):
            if re.search(r"[\u4e00-\u9fff]", m.group(1)):
                corpus.append(m.group(1))
except OSError:
    corpus = []
if len(set(corpus)) >= 100:
    names = [_col_to_field(c) for c in sorted(set(corpus))]
    dup = [n for n, k in collections.Counter(names).items() if k > 1]
    check("M14 真实中文列名语料碰撞率为 0", not dup,
          f"{len(set(corpus))} 条语料, 碰撞 {len(dup)} 组: {dup[:3]}")
else:
    skip("M14 真实中文列名语料碰撞率", "未设 CORPUS_DDL=<数据包路径>，用合成用例 M13 代替")

# ---------------------------------------------------------------------------
section("N 合并单元格：标准两行表头展开，其余照旧拒绝")

from openpyxl import Workbook as _WB
from core.merge_check import (
    detect_merged_cells, MergedCellsError, _col_letter, ensure_no_merged_cells,
)

mdir = tempfile.mkdtemp(prefix="packtest_merge_")


def mk_merged(name, merges=(), merges2=(), sheets2=True):
    """merges 作用在第一个 Sheet（字段定义），merges2 作用在第二个 Sheet（数据）。"""
    wb = _WB()
    w = wb.active
    w.title = "字段定义"
    w.append(["序号", "字段名(英文)", "字段注释(中文)", "数据类型", "长度", "是否主键", "是否可为空"])
    w.append([1, "id", "主键ID", "INT", "", "是", "否"])
    for r in merges:
        w.merge_cells(r)
    if sheets2:
        d = wb.create_sheet("数据")
        d.append(["id", "name"])
        d.append([1, "甲"])
        for r in merges2:
            d.merge_cells(r)
    p = os.path.join(mdir, name)
    wb.save(p)
    return p


p_clean = mk_merged("干净.xlsx")
p_m1 = mk_merged("元数据合并.xlsx", merges=("A1:G1",))
p_m2 = mk_merged("数据合并.xlsx", merges2=("A1:B1",))
p_m3 = mk_merged("两页都合并.xlsx", merges=("A1:G1",), merges2=("A1:B1", "A2:B2"))

check("N1 干净文件不误伤", detect_merged_cells(p_clean) == {}, detect_merged_cells(p_clean))
check("N2 检出元数据 Sheet 的合并区",
      detect_merged_cells(p_m1) == {"字段定义": ["A1:G1"]}, detect_merged_cells(p_m1))
check("N2b 检出数据 Sheet 的合并区",
      detect_merged_cells(p_m2) == {"数据": ["A1:B1"]}, detect_merged_cells(p_m2))
check("N2c 两个 Sheet 都有时全部列出",
      detect_merged_cells(p_m3) == {"字段定义": ["A1:G1"], "数据": ["A1:B1", "A2:B2"]},
      detect_merged_cells(p_m3))
p_order = mk_merged("乱序合并.xlsx", merges2=("A10:B10", "A2:B2", "B1:C1"))
check("N2d 合并区按左上角坐标行优先排序（列名不能按字符串排，A10 会跑到 A2 前）",
      detect_merged_cells(p_order)["数据"] == ["B1:C1", "A2:B2", "A10:B10"],
      detect_merged_cells(p_order)["数据"])
check("N3 单格伪合并不算合并",
      detect_merged_cells(mk_merged("单格.xlsx", merges=("A1:A1",))) == {})
check("N4 _col_letter 进位正确",
      (_col_letter(0), _col_letter(25), _col_letter(26), _col_letter(27)) == ("A", "Z", "AA", "AB"))

rejected = []
for bad_p in (p_m1, p_m2, p_m3):
    try:
        build_table_def(bad_p)
        rejected.append(os.path.basename(bad_p))
    except MergedCellsError:
        pass
check("N5 三种合并文件都在 build_table_def 处被拒", not rejected, f"漏放行: {rejected}")

w = app_main.MainWindow()
POPUPS.clear()
w._add_files([p_clean, p_m1, p_m2, p_m3])
check("N6 含合并的文件不进待导入列表",
      len(w.tables) == 1 and w.tables[0]["filepath"] == p_clean,
      [os.path.basename(t["filepath"]) for t in w.tables])
title, text = last_popup()
check("N7 汇总计入合并拒绝数", "另有 3 项含合并单元格已拒绝，详见日志" in text, text)
check("N7b 干净文件仍正常统计", text.startswith("添加 1 项文件，包含 1 项 xlsx 文件"), text)
check("N7c 有拒绝的「添加结果」在日志里按告警级上色（截图复核抓到的那条：只看首行会报成绿色成功）",
      "另有" in text
      and line_color(w.log, log_block(w.log, "[添加结果]")) == app_main.LOG_WARN_COLOR,
      [line_color(w.log, log_block(w.log, "[添加结果]")), text.replace("\n", " / ")])
lg = w.log.toPlainText()
check("N8 日志逐条说明原因并给出合并区位置",
      lg.count("[合并单元格]") == 3 and "「字段定义」1 处（A1:G1）" in lg
      and "「数据」2 处（A1:B1、A2:B2）" in lg and "取消合并" in lg,
      log_lines(lg, "[合并单元格]"))
merge_lines = [i for i in range(w.log.document().blockCount())
               if LOG_HEAD_RE.sub("",
                                  w.log.document().findBlockByNumber(i).text())
                   .startswith("[合并单元格]")]
check("N8b 合并拒绝日志整条标红",
      merge_lines and all(line_color(w.log, i) == app_main.LOG_ERROR_COLOR
                          for i in merge_lines),
      [(i, line_color(w.log, i)) for i in merge_lines])

zip_m = os.path.join(mdir, "含合并包.zip")
with zipfile.ZipFile(zip_m, "w") as z:
    z.writestr("干净.xlsx", open(p_clean, "rb").read())
    z.writestr("子目录/合并表.xlsx", open(p_m3, "rb").read())
w = app_main.MainWindow()
POPUPS.clear()
w._add_files([zip_m])
check("N9 zip 内的合并文件同样被拒绝，同包干净文件正常导入",
      len(w.tables) == 1 and "另有 1 项含合并单元格已拒绝" in last_popup()[1],
      [os.path.basename(t["filepath"]) for t in w.tables] + [last_popup()[1]])

xls_p = os.environ.get("PT_XLS", "")
if xls_p and os.path.isfile(xls_p):
    found = detect_merged_cells(xls_p)
    check("N10 真实 .xls 能检出合并区（需 xlrd formatting_info=True）",
          any(v for v in found.values()), f"{found}")
else:
    skip("N10 真实 .xls 合并区检测", "未设 PT_XLS=<.xls 文件路径>")

bad_zip = os.path.join(mdir, "假zip.xlsx")
open(bad_zip, "wb").write(b"not a workbook")
check("N11 损坏文件不在合并检测处炸掉", detect_merged_cells(bad_zip) == {})
check("N12 非 Excel 扩展名直接返回空", detect_merged_cells(os.path.join(mdir, "x.csv")) == {})

# 两行合并表头：只放行"第一个 Sheet 的顶部表头块"，压成一行后照常导入
RAW_TOP = ["序号", "单位名称", "坐落号", "省/直辖市", "市/直辖市区", "区/县",
           "是否主城区", "区位类型", "总计", "技术公司", None, None, None,
           "财务公司", None, None, None]
RAW_SECOND = [None] * 9 + ["小计", "高层", "中层", "普通员工",
                          "小计", "高层", "中层", "普通员工"]
RAW_MERGES = ("A1:A2", "B1:B2", "C1:C2", "D1:D2", "E1:E2", "F1:F2", "G1:G2",
              "H1:H2", "I1:I2", "J1:M1", "N1:Q1")
RAW_DATA = [
    [1, "市水务局", "001", "北京市", "北京市", "海淀区", "是", "主城区",
     100, 60, 20, 20, 20, 40, 10, 10, 20],
    [2, "区水务站", "002", "北京市", "北京市", "朝阳区", "否", "副中心",
     80, 45, 15, 15, 15, 35, 10, 10, 15],
    [3, "水库管理处", "003", "河北省", "石家庄市", "长安区", "是", "一般",
     50, 30, 10, 10, 10, 20, 5, 5, 10],
]
FLAT_HEADERS = ["序号", "单位名称", "坐落号", "省/直辖市", "市/直辖市区", "区/县",
                "是否主城区", "区位类型", "总计", "技术公司-小计", "技术公司-高层",
                "技术公司-中层", "技术公司-普通员工", "财务公司-小计",
                "财务公司-高层", "财务公司-中层", "财务公司-普通员工"]


def mk_raw_two_row(name, extra_merges=(), other_sheet_merge=False, single_row=False):
    wb = _WB()
    w = wb.active
    w.title = "原始表"
    if single_row:
        w.append(["组A", None, "姓名"])
        w.append(["甲", "乙", "丙"])
        w.merge_cells("A1:B1")
    else:
        w.append(RAW_TOP)
        w.append(RAW_SECOND)
        for box in RAW_MERGES:
            w.merge_cells(box)
        for row in RAW_DATA:
            w.append(row)
        for box in extra_merges:
            w.merge_cells(box)
    if other_sheet_merge:
        d = wb.create_sheet("其他")
        d.append(["a", "b"])
        d.append([1, 2])
        d.merge_cells("A1:B1")
    p = os.path.join(mdir, name)
    wb.save(p)
    return p


p_raw = mk_raw_two_row("两行合并表头.xlsx")
p_raw_data_merge = mk_raw_two_row("数据区合并.xlsx",
                                  extra_merges=("A4:B4",))
p_raw_overrun = mk_raw_two_row("表头外溢合并.xlsx",
                               extra_merges=("R2:S3",))
p_raw_other = mk_raw_two_row("其他页也合并.xlsx", other_sheet_merge=True)
p_raw_one_row = mk_raw_two_row("单行合并表头.xlsx", single_row=True)


def mk_raw_alt_header(name, top, second, merges, third=None, data=(1, 10, 20)):
    wb = _WB()
    w = wb.active
    w.title = "原始表"
    w.append(top)
    w.append(second)
    if third is not None:
        w.append(third)
    for box in merges:
        w.merge_cells(box)
    w.append(list(data))
    p = os.path.join(mdir, name)
    wb.save(p)
    return p


# 只认标准两行：下面三种都是"表头里有合并，但不是工作簿 1 那一种"
p_raw_three_row = mk_raw_alt_header(
    "三行合并表头.xlsx",
    ["序号", "技术公司", None],
    [None, "华北", None],
    ("A1:A3", "B1:C1", "B2:C2"),
    third=[None, "小计", "高层"],
    data=[1, 10, 20])
p_raw_child_merge = mk_raw_alt_header(
    "子行合并表头.xlsx",
    ["序号", "技术公司", None, "财务公司", None],
    [None, "小计", "高层", "小计", "高层"],
    ("A1:A2", "B1:C1", "D1:E1", "B2:C2"),
    data=[1, 10, 20, 30, 40])
p_raw_block_merge = mk_raw_alt_header(
    "2x2合并表头.xlsx",
    ["序号", "技术公司", None, "财务公司"],
    [None, "小计", "高层", "小计"],
    ("A1:A2", "B1:C2", "D1:D2"),
    data=[1, 10, 20, 30])

t_raw = build_table_def(p_raw)
check("N13 两行合并表头压成一行，列名与参考表逐字一致",
      [c["field"] for c in t_raw["columns"]]
      == ["xu_hao", "dan_wei_ming_cheng", "zuo_luo_hao", "sheng_zhi_xia_shi",
          "shi_zhi_xia_shi_qu", "qu_xian", "shi_fou_zhu_cheng_qu",
          "qu_wei_lei_xing", "zong_ji", "ji_shu_gong_si_xiao_ji",
          "ji_shu_gong_si_gao_ceng", "ji_shu_gong_si_zhong_ceng", "jsgsptyg",
          "cai_wu_gong_si_xiao_ji", "cai_wu_gong_si_gao_ceng",
          "cai_wu_gong_si_zhong_ceng", "cwgsptyg"],
      [(c["field"], c["type"]) for c in t_raw["columns"]])
check("N13b 合并表头的注释是「父-子」全路径",
      [c["comment"] for c in t_raw["columns"]] == FLAT_HEADERS,
      [c["comment"] for c in t_raw["columns"]])
check("N14 纵向合并只留一份标签，不会出现「序号-序号」",
      all("-" not in c["comment"] for c in t_raw["columns"][:9])
      and len(t_raw["columns"]) == 17,
      [c["comment"] for c in t_raw["columns"][:9]])
check("N15 横向合并的组名铺到组内每列，再与子标签连接",
      [c["comment"] for c in t_raw["columns"][9:13]]
      == ["技术公司-小计", "技术公司-高层", "技术公司-中层", "技术公司-普通员工"]
      and [c["comment"] for c in t_raw["columns"][13:]]
      == ["财务公司-小计", "财务公司-高层", "财务公司-中层", "财务公司-普通员工"],
      [c["comment"] for c in t_raw["columns"][9:]])
check("N16 展开后的数据行逐值保留，类型与主键照常推断",
      len(t_raw["df"]) == 3
      and list(t_raw["df"].iloc[0]) == RAW_DATA[0]
      and list(t_raw["df"].iloc[2]) == RAW_DATA[2]
      and t_raw["primary_key"] == "xu_hao"
      and t_raw["columns"][9]["type"] == "INT",
      (len(t_raw["df"]), list(t_raw["df"].iloc[0]) if len(t_raw["df"]) else None,
       t_raw["primary_key"], t_raw["columns"][9]["type"]))
check("N17 合并表头元数据如实回报 Sheet 与行数",
      t_raw["merged_header"] == {"sheet": "原始表", "depth": 2},
      t_raw["merged_header"])
for label, bad_p in (("数据区合并", p_raw_data_merge), ("表头外溢合并", p_raw_overrun),
                     ("其他页也合并", p_raw_other), ("单行合并表头", p_raw_one_row),
                     ("三行合并表头", p_raw_three_row), ("第 2 行子合并", p_raw_child_merge),
                     ("2x2 合并块", p_raw_block_merge)):
    try:
        build_table_def(bad_p)
        ok = False
        detail = "被放行了"
    except MergedCellsError:
        ok = True
        detail = ""
    check("N18 只有标准两行合并表头才放行：" + label, ok, detail)

w = app_main.MainWindow()
POPUPS.clear()
w._add_files([p_raw])
check("N19 合并表头文件进入待导入列表",
      len(w.tables) == 1 and w.tables[0]["merged_header"] == {"sheet": "原始表", "depth": 2},
      [(t["table_name"], t.get("merged_header")) for t in w.tables])
lg = w.log.toPlainText()
check("N19b [已解析] 行说明表头按几行展开",
      "[已解析]" in lg and "合并表头按 2 行展开" in lg,
      log_lines(lg, "[已解析]"))

zip_raw = os.path.join(mdir, "合并表头包.zip")
with zipfile.ZipFile(zip_raw, "w") as z:
    z.writestr("两行合并表头.xlsx", open(p_raw, "rb").read())
    z.writestr("干净.xlsx", open(p_clean, "rb").read())
w = app_main.MainWindow()
POPUPS.clear()
w._add_files([zip_raw])
lg = w.log.toPlainText()
check("N20 zip 内的两行合并表头同样展开，同包其他文件不受影响",
      len(w.tables) == 2 and "合并表头按 2 行展开" in lg
      and any(t["merged_header"] is None for t in w.tables),
      [(t["table_name"], t.get("merged_header")) for t in w.tables])

section("O 模态进度框与子线程执行（长任务不能卡 UI）")

import threading
import time
from PySide6.QtCore import QTimer

seen = []
sink = app_main.ProgressSink(on_progress=lambda d, t, l: seen.append((d, t, l)))
sink.set_total(5)
for lab in ("a", "b", "c"):
    sink.step(lab)
sink.advance(2, "d")          # 按块推进（逐条 SQL 那种大批量计数）
check("O1 set_total + step 每次都回报 (done,total,label)",
      seen == [(0, 5, ""), (1, 5, "a"), (2, 5, "b"), (3, 5, "c"), (5, 5, "d")], seen)
check("O2 advance 一次推进多项，不会发出上百条信号", len(seen) == 5, seen)
n_note = []
sink2 = app_main.ProgressSink(on_progress=lambda *a: n_note.append(a))
sink2.set_total(3)
sink2.note("准备中")
sink2.step("x")
check("O3 note 只换文字不推进，step 才推进",
      [i[0] for i in n_note] == [0, 0, 1] and n_note[1][2] == "准备中", n_note)
zero = []
sink3 = app_main.ProgressSink(on_progress=lambda *a: zero.append(a))
sink3.step("没有总数")
check("O4 总数未知时不除零、百分比可用", zero == [(1, 1, "没有总数")], zero)
sink4 = app_main.ProgressSink(on_progress=lambda *a: None)
sink4.set_total(9)
sink4.set_total(2)            # 中途改小不允许把进度条拽回去
check("O5 set_total 只增不减，进度不回退", sink4.total == 9, sink4.total)
canc = []
sink5 = app_main.ProgressSink(is_cancelled=lambda: True)
check("O6 取消标志透传给工作函数", sink5.cancelled is True and not canc)

dlg = app_main.ProgressDialog("测试进度", None)
dlg.set_progress(50, 100, "第 50 项")
dlg._animate()
one_tick = dlg.bar.value()
for _ in range(30):
    dlg._animate()
check("O7 进度条按目标值平滑逼近而不是瞬间跳格",
      0 < one_tick < 50 and dlg.bar.value() == 50, f"一步={one_tick} 收敛后={dlg.bar.value()}")
check("O8 当前项文字含 i/N 与标签",
      "50/100" in dlg.lbl_step.text() and "第 50 项" in dlg.lbl_step.text(), dlg.lbl_step.text())
check("O9 进度框没有标题栏关闭按钮（只能走取消）",
      not dlg.windowFlags() & Qt.WindowCloseButtonHint
      and bool(dlg.windowFlags() & Qt.CustomizeWindowHint), int(dlg.windowFlags()))
dlg.btn_cancel.click()
check("O10 点取消后按钮立刻失效并提示收尾",
      not dlg.btn_cancel.isEnabled() and "取消" in dlg.lbl_step.text(), dlg.lbl_step.text())
dlg.deleteLater()


class _Sig:
    def __init__(self):
        self.slots = []

    def connect(self, fn):
        self.slots.append(fn)

    def emit(self):
        for fn in self.slots:
            fn()


def _last_dialog():
    return _FakeProgress.made[-1] if _FakeProgress.made else None


def _wait_until(pred, seconds):
    """有限等待：超时返回 False 让用例红，绝不从这里抛出去（一抛整轮中止，红就找不着了）。

    放在进度框替身这一片，是因为凡走真线程（MODAL_PROGRESS=True）的场景都可能撞上
    "上一步还没腾出资源位，这一步就被同页互斥挡下"——那是取证的时机问题，不是产品缺陷。
    """
    deadline = _t_busy.time() + seconds
    while _t_busy.time() < deadline:
        QApplication.processEvents()
        if pred():
            return True
        _t_busy.sleep(0.005)
    QApplication.processEvents()
    return False


def _pump(seconds):
    """干等这么久，但事件循环照常泵：站在主线程 sleep 就等于自己把界面掐死了。"""
    return _wait_until(lambda: False, seconds)


class _FakeBtn:
    def __init__(self):
        self.clicked = _Sig()
        self.text = "取消"

    def setText(self, t):
        self.text = t


class _FakeProgress:
    """替身进度框：把真实异步链路跑通来验。

    exec() 里持续泵主线程事件循环，直到工作线程把任务收尾（finish），
    同时记录主循环的 tick 数 —— 这就是"活儿在子线程、UI 没有卡死"的证据。
    """

    made = []

    def __init__(self, title, parent=None):
        self.title = title
        self.updates = []
        self.accepted = False
        self.ui_ticks = 0
        self.threads = []
        self.btn_cancel = _FakeBtn()
        self.moved = None              # _place_task_dialog 落的位置
        _FakeProgress.made.append(self)

    cancel_after = None        # 设成 N 就模拟"主循环第 N 拍时用户点了取消"

    # 产品会按"此刻有几个框在屏"错开摆放，替身得把这些控件接口一并顶上：
    # 少一个就是 AttributeError，红成"用例坏了"而不是"布局错了"
    def move(self, x, y):
        self.moved = (x, y)

    def width(self):
        return 480

    def height(self):
        return 140

    def isVisible(self):
        return True

    def set_progress(self, done, total, label):
        self.updates.append((done, total, label))

    def finish(self):
        self.accepted = True

    def show(self):
        """非模态框的入口。用例里由它代用户等这一轮跑完：边泵主循环边看框有没有被关闭。

        真程序里 show() 立刻返回，等待的是用户；这里必须等，否则后面的断言读到的
        是"任务还在跑"的中间态。等不到就是任务把主循环挡住了，照样判失败。
        """
        timer = QTimer()
        timer.setInterval(1)
        timer.timeout.connect(lambda: self.__setattr__("ui_ticks", self.ui_ticks + 1))
        timer.start()
        for _ in range(4000):                  # 最多等 8s，超时就判定为卡死
            QApplication.processEvents()
            if self.accepted:
                break
            if _FakeProgress.cancel_after is not None and self.ui_ticks >= _FakeProgress.cancel_after:
                _FakeProgress.cancel_after = None
                self.btn_cancel.clicked.emit()
            time.sleep(0.002)
        else:
            raise AssertionError(f"进度框没被关闭，任务把主线程挡住了：{self.title}")
        timer.stop()
        self.threads.append(threading.current_thread().name)

    def raise_(self):
        pass

    def activateWindow(self):
        pass

    def isVisible(self):
        return not self.accepted
        return 1


def _slow_task(n, sink):
    """故意做得很慢的纯任务：既证明主线程没被占用，也证明能中途取消。"""
    sink.set_total(n)
    for i in range(n):
        if sink.cancelled:
            break
        time.sleep(0.03)
        sink.step(f"第 {i + 1} 项")
    return {"thread": threading.current_thread().name, "done": sink.done,
            "cancelled": sink.cancelled}


REAL_PROGRESS = app_main.ProgressDialog      # 稍后用真模态框再跑一遍端到端
app_main.ProgressDialog = _FakeProgress
app_main.MODAL_PROGRESS = True
ow = app_main.MainWindow()
res_box = []
ow._run_modal("慢任务", _slow_task, (6,), on_done=lambda r: res_box.append(r))
MAIN = threading.main_thread().name
d0 = _last_dialog()
check("O11 任务跑在子线程，不是主线程",
      bool(res_box) and res_box[0]["thread"] != MAIN,
      [(r.get("thread"), MAIN) for r in res_box])
check("O12 工作期间主线程事件循环一直在转（UI 不假死）", d0.ui_ticks >= 5, d0.ui_ticks)
check("O13 每一项都实时回报进度", len(d0.updates) == 7 and d0.updates[-1][0] == 6,
      d0.updates[:3])
check("O14 任务结束后自动关框并把结果交给回调",
      d0.accepted and d0.threads == [MAIN] and len(res_box) == 1)

# 取消：主循环里点掉取消按钮，子线程在下一次检查时停手，部分结果仍回主线程
res_box.clear()
_FakeProgress.cancel_after = 2
ow._run_modal("可取消任务", _slow_task, (200,), on_done=lambda r: res_box.append(r))
d1 = _last_dialog()
check("O15 点取消后子线程停手、主线程拿到部分结果",
      len(res_box) == 1 and res_box[0]["done"] < 200 and res_box[0]["cancelled"],
      res_box)
check("O16 取消后进度框一样被关闭", d1.accepted)


def _boom(sink):
    raise RuntimeError("工作函数自己炸了")


POPUPS.clear()
res_box.clear()
ow._run_modal("异常任务", _boom, (), on_done=lambda r: res_box.append(("不该回调", r)))
check("O17 工作函数抛异常时报错而不是挂住，且不回调成功分支",
      any(t == "任务异常" for t, _ in POPUPS) and not any(x[0] == "不该回调" for x in res_box),
      POPUPS[-1][0] if POPUPS else "")
check("O18 异常写进日志且标了错误级别", "[任务异常]" in ow.log.toPlainText())

# 生产入口在真·异步模式下同样跑通：添加文件确实走的是子线程 + 模态框
_FakeProgress.made.clear()
ow._add_files([jx1])
d2 = _last_dialog()
check("O19 「添加文件」弹了进度框并等任务收尾",
      d2 is not None and "解析" in d2.title and d2.accepted,
      [d.title for d in _FakeProgress.made])
check("O20 解析结果照常落到待导入列表",
      len(ow.tables) == 1 and ow.file_table.item(0, 1).text() == "甲工程.xlsx",
      [t["table_name"] for t in ow.tables])
check("O21 单个散装文件按解析阶段计项，做完正好 100%",
      {u[1] for u in d2.updates} == {5}
      and [u[0] for u in d2.updates] == [0, 1, 2, 3, 4, 5, 5]
      and d2.updates[-1] == (5, 5, "甲工程.xlsx"), d2.updates)

# 压缩包：解出每个 Excel 和解析每个 Excel 各占一项，总数一次算准不回退
import zipfile as _zf
ozip = os.path.join(tempfile.mkdtemp(prefix="pt_ozip_"), "两表.zip")
with _zf.ZipFile(ozip, "w") as z:
    z.writestr("甲工程.xlsx", open(jx1, "rb").read())
    z.writestr("sub/乙工程.xlsx", open(jx2, "rb").read())
ow2 = app_main.MainWindow()
_FakeProgress.made.clear()
ow2._add_files([ozip])
d3 = _last_dialog()
tot = {u[1] for u in d3.updates}
steps = [u[0] for u in d3.updates]
adv = [d for i, (d, _t, _l) in enumerate(d3.updates) if i == 0 or d != steps[i - 1]]
check("O22 压缩包按「1 包 + 2 解出 + 2×5 解析阶段」共 13 项计进度",
      tot == {13} and adv == list(range(14)), adv)
check("O23 进度只前进不回退，且每个文件的解析阶段都实时报了",
      all(b >= a for a, b in zip(steps, steps[1:]))
      and any("解出" in (u[2] or "") for u in d3.updates)
      and any("读取工作簿" in (u[2] or "") for u in d3.updates)
      and any("推断字段类型" in (u[2] or "") for u in d3.updates),
      [u[2] for u in d3.updates])
check("O24 异步路径下 zip 仍展开成两张表", len(ow2.tables) == 2, len(ow2.tables))

# 前缀批量应用：异步 + 落表
ow2.ed_prefix.setText("dim_")
_FakeProgress.made.clear()
ow2.on_apply_prefix()
d4 = _last_dialog()
check("O25 「批量应用前缀」走模态进度框",
      d4 is not None and "前缀" in d4.title and d4.accepted)
check("O26 前缀按表计项（2 张表 + 1 次去重）并在完成后刷新列表",
      len(d4.updates) == 4 and d4.updates[-1][0] == d4.updates[-1][1]
      and ow2.file_table.item(0, 2).text().startswith("dim_"),
      [ow2.file_table.item(i, 2).text() for i in range(ow2.file_table.rowCount())])

# SQL 页：保存与改名也走同一套（读盘/写盘这种大 IO 不能在主线程）
sql_a = os.path.join(tempfile.mkdtemp(prefix="pt_osql_"), "a.sql")
open(sql_a, "w", encoding="utf-8").write(
    "CREATE TABLE `t_a` (`id` INT);\nINSERT INTO `t_a` VALUES (1);\n")
ow2.sql_files.append({"path": sql_a, "text": open(sql_a, encoding="utf-8").read(),
                      "modified": True, "rename_map": {}})
_FakeProgress.made.clear()
ow2.on_sql_save_modified()
d5 = _last_dialog()
check("O27 「保存修改后ddl脚本」在子线程写盘并弹框",
      d5 is not None and "保存" in d5.title and os.path.isfile(
          os.path.join(os.path.dirname(sql_a), "t_a_prefix.sql").replace("t_a_prefix", "a_prefix")),
      [p for p in os.listdir(os.path.dirname(sql_a))])
check("O28 保存后 modified 复位", ow2.sql_files[-1]["modified"] is False)

_FakeProgress.made.clear()
ow2.ed_sql_prefix.setText("ods_")
ow2.on_sql_apply_prefix()
check("O29 SQL「批量应用前缀」只改页面显示：不弹进度框、脚本正文一个字不动",
      _FakeProgress.made == [] and ow2.sql_table.item(0, 3).text() == "ods_t_a"
      and "`ods_t_a`" not in ow2.sql_files[-1]["text"]
      and ow2.sql_files[-1]["modified"] is False,
      (_FakeProgress.made, ow2.sql_table.item(0, 3).text()))
POPUPS.clear()
_FakeProgress.made.clear()
ow2.on_sql_save_modified()
d6 = _last_dialog()
check("O29b 点「保存修改后ddl脚本」才把显示名写进脚本，并给出进度与结果",
      d6 is not None and d6.accepted and "`ods_t_a`" in ow2.sql_files[-1]["text"]
      and ow2.sql_files[-1]["modified"] is False
      and any(t == "保存完成" for t, _ in POPUPS),
      (ow2.sql_files[-1]["text"], [t for t, _ in POPUPS]))
_save_pops = [t for title, t in POPUPS if title == "保存完成"]
_save_flat = _save_pops[-1].replace("\n", "") if _save_pops else ""
_out_a = os.path.join(os.path.dirname(sql_a), "a_prefix.sql")
check("O29c 保存完成弹窗列出副本完整路径",
      ("[已保存] %s" % _out_a) in _save_flat, _save_flat[:120])

# 未选库/无文件时不该弹框（守卫在起线程之前就返回）
_FakeProgress.made.clear()
ow3 = app_main.MainWindow()
ow3.on_apply_prefix()
ow3.on_sql_execute()
ow3.on_sql_save_modified()
ow3.on_start_import()
check("O30 前置校验不过时一个进度框都不弹", _FakeProgress.made == [], _FakeProgress.made)

# 连不上库时：模态框必须被关闭，不能留一个永远转圈的框
ow3.cb_db.addItem("nosuchdb", "nosuchdb")
ow3.ed_host.setText("127.0.0.1")
ow3.ed_port.setValue(1)                       # 端口 1 必然拒绝连接，不会碰到真实库
_FakeProgress.made.clear()
ow3.sql_files.append({"path": sql_a, "text": "SELECT 1;\n", "modified": False,
                      "rename_map": {}})
ow3.on_sql_execute()
d7 = _last_dialog()
check("O31 SQL 执行连不上库时进度框照样收尾", d7 is not None and d7.accepted)
check("O32 连不上库的详情进了 SQL 日志", "[连接失败]" in ow3.sql_log.toPlainText(),
      [l for l in ow3.sql_log.toPlainText().splitlines() if "连接失败" in l][:1])
ow3._add_files([jx1])
# 同 R31 那处：这一段 MODAL_PROGRESS 是 True，_add_files 走真线程。不等它腾出资源位就点
# 「开始导入」，会被同页互斥挡下、不弹框，下面三条一起拿到上一步那颗 SQL 执行的框
_wait_until(lambda: not ow3.task_busy((ow3.RES_EXCEL,)), 20)
_FakeProgress.made.clear()
ow3.on_start_import()
d8 = _last_dialog()
check("O33 导入连不上库时进度框收尾、按钮恢复可用",
      d8 is not None and d8.accepted and ow3.btn_stop.isEnabled() is False
      and ow3.btn_import.isEnabled() is bool(ow3.tables), d8 and d8.title)
check("O34b 导入框的取消按钮写的是「停止」而不是笼统的取消",
      d8 is not None and "停止" in d8.btn_cancel.text, d8 and d8.btn_cancel.text)
check("O35 导入的失败原因仍按标红日志输出",
      "[连接失败]" in ow3.log.toPlainText())

# 最后一关：用真·ProgressDialog（不换成替身）跑一遍。exec() 是嵌套事件循环，只有
# 工作线程把任务收尾它才会返回，所以配一条 6s 看门狗：看门狗响了就等于"真框挂住了"，
# 用例判失败并强制收框，不会把整个用例集拖死。
# 框是 _run_modal 的局部对象，返回后就被回收了（Qt 会自动断开它还连着的信号，安全），
# 所以用一个只做"关框瞬间留影"的子类来取证据，行为完全继承真类。
_close_shots = []


class _SpyProgress(REAL_PROGRESS):
    def finish(self):
        super().finish()
        _close_shots.append({"bar": self.bar.value(), "label": self.lbl_step.text(),
                             "visible": self.isVisible(), "title": self.windowTitle()})


app_main.ProgressDialog = _SpyProgress
app_main.MODAL_PROGRESS = True
rw = app_main.MainWindow()
watchdog_fired = [False]


def _watchdog():
    watchdog_fired[0] = True
    for d in QApplication.topLevelWidgets():
        if isinstance(d, REAL_PROGRESS) and d.isVisible():
            d.finish()


def wait_idle(win, seconds=8.0):
    """非模态之后由用例自己等这一轮跑完：主循环照常泵，超时就是任务把界面挡住了。"""
    deadline = time.time() + seconds
    while time.time() < deadline:
        QApplication.processEvents()
        if not win._tasks:
            return True
        time.sleep(0.002)
    raise AssertionError("任务超时没收尾：" + " | ".join(t.describe() for t in win._tasks))


_wd = QTimer()
_wd.setInterval(6000)
_wd.setSingleShot(True)
_wd.timeout.connect(_watchdog)
_wd.start()
rw._add_files([jx1, ozip])
wait_idle(rw)
rw._add_files([jx2])                      # 同一轮里起第二个任务，验证状态被正确复位
wait_idle(rw)
_wd.stop()
check("O36 真进度框：任务自己收尾，没靠看门狗救场",
      watchdog_fired[0] is False and len(_close_shots) == 2,
      f"看门狗触发={watchdog_fired[0]} 关框次数={len(_close_shots)}")
shot = _close_shots[-1] if _close_shots else {}
check("O37 关框那一刻进度条正好落在 100%", shot.get("bar") == 100, shot)
check("O38 关框那一刻当前项文字停在最后一项", "乙工程" in (shot.get("label") or ""), shot)
check("O39 关框后框确实不可见（不会残留半个模态窗）", shot.get("visible") is False, shot)
check("O40 两轮任务的结果都落到界面", len(rw.tables) == 4,
      [t["table_name"] for t in rw.tables])
check("O41 任务结束后窗口回到空闲态，能再起任务", rw.task_dialog is None and rw._tasks == [])

app_main.MODAL_PROGRESS = False
_FakeProgress.made.clear()

section("P 使用说明（? 按钮 / F1 / 排版 / 文档不漂移）")

from PySide6.QtGui import QKeyEvent
from PySide6.QtCore import QEvent
from help_doc import SECTIONS as HELP_SECTIONS
from ui_help import HelpDialog, build_html, AUTHOR

import re as _re


def _h2(body):
    m2 = _re.search(r"<h2>(.*?)</h2>", body)
    return m2.group(1) if m2 else ""


check("P1 每节都是 (锚点, 目录标题, 正文) 且锚点唯一、正文有 h2",
      all(len(x) == 3 and x[0] and x[1] and _h2(x[2]) for x in HELP_SECTIONS)
      and len({x[0] for x in HELP_SECTIONS}) == len(HELP_SECTIONS),
      [x[0] for x in HELP_SECTIONS])
_html = build_html()
check("P2 每节锚点都写进了同一份文档（目录跳转的前提）",
      all(f'<a name="{x[0]}"></a>' in _html for x in HELP_SECTIONS), len(_html))
check("P3 样式表带上了（表格/代码块/提示条靠它排版）",
      "table.t td" in _html and "p.tip" in _html and "p.warn" in _html)
# 说明正文里写的是 Windows 路径，字符串没转义的话 \b 会被当成退格符吃掉一个字母，
# 现场照着这句敲命令就会撞"找不到文件"——正文里不许出现控制字符，路径要按字面留存
check("P3b 说明正文没有控制字符，jre\\bin\\java.exe 按字面写着",
      all((not any(ord(c) < 9 or 11 <= ord(c) < 32 for c in body))
          for _k, _t, body in HELP_SECTIONS)
      and r"jre\bin\java.exe" in _html,
      [(_k, [ord(c) for c in body if ord(c) < 9]) for _k, _t, body in HELP_SECTIONS
       if any(ord(c) < 9 for c in body)])
check("P3c 第三个页签「SQL 执行器」在使用说明里有专节",
      any(x[0] == "sqlrun" and "SQL 执行器页" in x[1] for x in HELP_SECTIONS)
      and "示例 (?)" in _html and "全部成功才提交" in _html,
      [x[0] for x in HELP_SECTIONS])

pw = app_main.MainWindow()
hd = HelpDialog(pw)
hd.resize(960, 700)
hd.show()
_app.processEvents()
check("P4 对话框构造成功，目录条数与章节数一致",
      hd.toc.count() == len(HELP_SECTIONS), hd.toc.count())
_sb = hd.browser.verticalScrollBar()
_pos = []
for i in range(len(HELP_SECTIONS)):
    hd.toc.setCurrentRow(i)
    _app.processEvents()
    _pos.append(_sb.value())
check("P5 点目录确实逐节跳到位（滚动位置严格递增，不是空跳）",
      all(b > a for a, b in zip(_pos, _pos[1:])), _pos)
_txt = hd.browser.toPlainText()
_lost = [x[0] for x in HELP_SECTIONS if _h2(x[2]) not in _txt]
check("P6 每节标题都真的渲染出来了（没被 Qt 的 HTML 子集吞掉）",
      not _lost, _lost)
check("P7 表格内容也在正文里（不是只剩标题）", len(_txt) > 4000, len(_txt))
hd.keyPressEvent(QKeyEvent(QEvent.KeyPress, Qt.Key_Escape, Qt.NoModifier))
check("P8 Esc 能关闭说明窗", hd.result() == 0 and not hd.isVisible(), hd.result())

# 主窗口上的「?」：位置、提示、F1 入口
pw2 = app_main.MainWindow()
pw2.resize(1000, 700)
pw2.show()
_app.processEvents()
hb = pw2.btn_help
geo = hb.geometry()
check("P9 主窗口有「?」按钮且文案就是一个问号", hb.text() == "?", hb.text())
check("P10 「?」贴在标题栏下方的右上角",
      geo.top() < 20 and geo.right() > pw2.width() - 40, (geo.getRect(), pw2.width()))
check("P11 悬停提示写明可用 F1", "F1" in (hb.toolTip() or ""), hb.toolTip())
_opened = []


class _StubHelp:
    def __init__(self, parent=None):
        _opened.append(parent)

    def exec(self):
        return 1


_real_help = app_main.HelpDialog
app_main.HelpDialog = _StubHelp
hb.click()
check("P12 点「?」打开使用说明", len(_opened) == 1 and _opened[0] is pw2, _opened)
pw2.keyPressEvent(QKeyEvent(QEvent.KeyPress, Qt.Key_F1, Qt.NoModifier))
check("P13 按 F1 同样打开使用说明", len(_opened) == 2, len(_opened))
pw2.keyPressEvent(QKeyEvent(QEvent.KeyPress, Qt.Key_F2, Qt.NoModifier))
check("P14 其他按键不被拦截", len(_opened) == 2, len(_opened))
app_main.HelpDialog = _real_help

# 文档漂移守卫：说明里写到的按钮名必须是界面上真实存在的文案
from PySide6.QtWidgets import QPushButton
_labels = ["批量应用前缀", "替换首个前缀", "生成表结构预览", "预览 DDL",
           "开始导入", "清空待上传文件", "保存修改后ddl脚本", "执行修改后ddl脚本到当前库",
           "连接并加载库", "生成标准excel文件", "生成简易excel文件",
           "示例 (?)", "清空编辑区", "清空结果区", "设置数据源信息", "收起"]
_btns = {b.text() for b in pw2.findChildren(QPushButton)}
_absent = [t for t in _labels if t not in _btns]
check("P15 说明里点名的按钮在界面上确实存在", not _absent, _absent)
_doc_all = "".join(b for _a, _t, b in HELP_SECTIONS)
_missing_in_doc = [t for t in _labels if t not in _doc_all]
check("P16 每个按钮都被使用说明覆盖到", not _missing_in_doc, _missing_in_doc)
check("P16b 说明交代点击虚线区就能选文件",
      "点击虚线区" in _doc_all or "点一下虚线区" in _doc_all)
check("P17 进度框章节写清了「后台线程 + 可取消 + 逐项进度」",
      "进度框与耗时" in _doc_all and "非模态" in _doc_all
      and "当前这一项做完" in _doc_all)
check("P17b 进度框章节交代了各页可以并行、互斥按占用的东西判",
      "另外两个页照常能用" in _doc_all and "互斥按占用的东西判" in _doc_all
      and "各写自己的目录" in _doc_all and "sql_meta_data_file_年月日" in _doc_all,
      [t for t in ("另外两个页照常能用", "互斥按占用的东西判", "各写自己的目录",
                   "sql_meta_data_file_年月日") if t not in _doc_all])
check("P17c 说明交代了两个进度框错开摆、两颗生成按钮不必互相等",
      "往右下错开一格" in _doc_all and "两颗按钮也不必互相等" in _doc_all,
      [t for t in ("往右下错开一格", "两颗按钮也不必互相等") if t not in _doc_all])
check("P17d 快速上手交代了 conn.ini 预填、优先级与三种说法",
      "程序所在目录" in _doc_all and "E2M_PASS" in _doc_all
      and "找到了但读不了" in _doc_all and "内置默认值" in _doc_all,
      [t for t in ("程序所在目录", "E2M_PASS", "找到了但读不了", "内置默认值")
       if t not in _doc_all])

section("Q 入口全覆盖（每个按钮点下去都要有可见痕迹）")

import time as _time
from PySide6.QtWidgets import QPushButton

# 用假连接把「连接并加载库 / 开始导入」的成功路径也跑起来：不碰任何真实 MySQL，
# 但要真的看到 begin / CREATE / executemany / commit 发生，才算这条链路通了。


class _QCursor:
    def __init__(self, conn):
        self.conn = conn
        self.rowcount = 0
        self.description = None
        self._last = ""

    def __enter__(self):
        return self

    def __exit__(self, *_a):
        return False

    def execute(self, sql, params=None):
        self._last = sql
        self.conn.calls.append(sql)
        if self.conn.boom_create and "CREATE" in sql:
            raise RuntimeError("模拟：建表语句被服务端拒绝")
        self.rowcount = 0

    def executemany(self, sql, vals):
        self.conn.calls.append(sql)
        if self.conn.boom_insert:
            raise RuntimeError("模拟：数据写入被服务端拒绝")
        self.rowcount = len(vals)
        self.conn.rows += len(vals)

    def fetchall(self):
        return [(d,) for d in self.conn.dbs] if self._last.startswith("SHOW DATABASES") else []

    def close(self):
        pass


class _QConn:
    dbs = ("alpha", "beta")

    def __init__(self, cfg):
        self.cfg = cfg
        self.calls = []
        self.rows = 0
        self.commits = 0
        self.rollbacks = 0
        self.begins = 0
        self.closed = False
        self.boom_create = False
        self.boom_insert = False

    def cursor(self):
        return _QCursor(self)

    def begin(self):
        self.begins += 1

    def commit(self):
        self.commits += 1

    def rollback(self):
        self.rollbacks += 1

    def close(self):
        self.closed = True


_CONNS = []


def _fake_connect(cfg, **_kw):
    c = _QConn(cfg)
    _CONNS.append(c)
    return c


def _wait_worker(win, limit=250):
    """等导入线程退出，并把排队中的信号送回主线程。"""
    for _ in range(limit):
        _app.processEvents()
        if win.worker is not None and not win.worker.isRunning():
            _app.processEvents()
            return True
        _time.sleep(0.02)
    return False


def _wait_auto(win, limit=300):
    """等启动自动连接收尾：showEvent 里的 QTimer 先跑起来，线程退出后信号回主线程。"""
    for _ in range(limit):
        _app.processEvents()
        if win._auto_conn_started and win._auto_conn_worker is None:
            return True
        _time.sleep(0.02)
    return False


_orig_connect = app_main.connect
app_main.connect = _fake_connect

# ---- 连接并加载库：成功路径 ----
qw = app_main.MainWindow()
# 启动默认收起；先点开，才能验证"重新填列表不算选定、不该收起"
check("Q0 未做任何操作时连接区是收起的，三页提示条也不出现",
      qw.conn_form.isHidden() and not qw.btn_conn_edit.isHidden()
      and qw.lbl_imp_db.isHidden() and qw.lbl_sql_db.isHidden()
      and qw.lbl_run_db.isHidden(),
      (qw.conn_form.isHidden(), qw.btn_conn_edit.isHidden(),
       qw.lbl_imp_db.isHidden(), qw.lbl_sql_db.isHidden(), qw.lbl_run_db.isHidden()))
qw.btn_conn_edit.click()
qw.on_load_databases()
_dbs = [qw.cb_db.itemText(i) for i in range(qw.cb_db.count())]
check("Q1 「连接并加载库」成功后下拉框填充并可用",
      qw.cb_db.isEnabled() and _dbs == ["alpha", "beta"], _dbs)
check("Q2 加载结果写进日志", "[已加载] 可用数据库" in qw.log.toPlainText(),
      qw.log.toPlainText().splitlines()[-1:])
check("Q3 加载完库提示条从隐藏恢复为正常态，但连接区先不收起（下一步就是在这下拉里挑库）",
      "目标库：alpha ——" in qw.lbl_imp_db.text() and "⚠" not in qw.lbl_imp_db.text()
      and not qw.lbl_imp_db.isHidden()
      # 这一组的窗口没 show 过，isVisible() 恒为假：只问"是不是被显式收起来了"
      and not qw.conn_form.isHidden() and qw.btn_conn_edit.isHidden(),
      (qw.lbl_imp_db.text(), qw.lbl_imp_db.isHidden(),
       qw.conn_form.isHidden(), qw.btn_conn_edit.isHidden()))
qw.cb_db.setCurrentText("beta")
check("Q3b 真在目标库里选了一个才自动收起",
      qw.conn_form.isHidden() and not qw.btn_conn_edit.isHidden(),
      (qw.conn_form.isHidden(), qw.btn_conn_edit.isHidden()))
qw.cb_db.setCurrentText("alpha")
check("Q4 导入取的就是下拉选中的那个库",
      qw._conn_cfg()["database"] == "alpha", qw._conn_cfg()["database"])

# ---- 连接并加载库：失败路径（端口 1 必然被拒，不会碰到真实库） ----
app_main.connect = _orig_connect
qw2 = app_main.MainWindow()
qw2.ed_port.setValue(1)
POPUPS.clear()
qw2.on_load_databases()
check("Q5 连不上库时弹窗报错，不静默",
      any(t == "连接失败" for t, _ in POPUPS), POPUPS[-1][0] if POPUPS else "无弹窗")
check("Q6 连不上库的详情进日志并写明库列表未加载",
      "[连接失败]" in qw2.log.toPlainText() and "库列表未加载" in qw2.log.toPlainText(),
      [l for l in qw2.log.toPlainText().splitlines() if "连接失败" in l][:1])
check("Q7 失败后下拉框保持不可用，不留半个可用状态", qw2.cb_db.isEnabled() is False)
app_main.connect = _fake_connect

# ---- 程序同级 conn.ini 预填连接信息：现场配一次，不用每次手填 ----
_cdir = os.path.join(_tmpdir, "conn预填")
os.makedirs(_cdir, exist_ok=True)
with io.open(os.path.join(_cdir, "conn.ini"), "w", encoding="utf-8") as _cf:
    _cf.write("[mysql]\nhost = 10.0.0.8\nport = 3307\nuser = ops\n"
              "password = pw-file\ndatabase = alpha\n")
_pdir0 = app_main.program_dir
app_main.program_dir = lambda: _cdir
cw = app_main.MainWindow()
_cwlog = cw.log.toPlainText()
check("Q53 有 conn.ini 时连接信息按它预填，日志说得出来源",
      cw.ed_host.text() == "10.0.0.8" and cw.ed_port.value() == 3307
      and cw.ed_user.text() == "ops" and cw.ed_pass.text() == "pw-file"
      and "连接信息预填 ops@10.0.0.8:3307/alpha" in _cwlog
      and "自动发现" in _cwlog,
      [l for l in _cwlog.splitlines() if "连接信息" in l][:1])
check("Q53g 密码不回显也不写进日志正文",
      "pw-file" not in _cwlog and cw.ed_pass.echoMode() != 0,
      [l for l in _cwlog.splitlines() if "密码" in l][:1])
cw.on_load_databases()
check("Q53b 库列表出来后按配置预选目标库",
      cw.cb_db.currentText() == "alpha"
      and "目标库按 conn.ini 选中" in cw.log.toPlainText(),
      (cw.cb_db.currentText(), cw.cb_db.count()))
os.environ["E2M_HOST"] = "192.168.9.9"
cw2 = app_main.MainWindow()
del os.environ["E2M_HOST"]
check("Q53c 环境变量 E2M_* 盖过 conn.ini，来源里点名是哪个变量",
      cw2.ed_host.text() == "192.168.9.9"
      and "环境变量 E2M_HOST" in cw2.log.toPlainText(),
      [l for l in cw2.log.toPlainText().splitlines() if "连接信息" in l][:1])
_bdir = os.path.join(_tmpdir, "conn写坏")
os.makedirs(_bdir, exist_ok=True)
with io.open(os.path.join(_bdir, "conn.ini"), "w", encoding="utf-8") as _bf:
    _bf.write("host = 1.2.3.4\n")          # 没有小节名，读不了
app_main.program_dir = lambda: _bdir
cw3 = app_main.MainWindow()
_log3 = cw3.log.toPlainText()
check("Q53d conn.ini 读不了时按默认值起界面，并说清是「找到了但读不了」",
      cw3.ed_host.text() == "127.0.0.1" and "但读不了" in _log3
      and "没找到 conn.ini" not in _log3,
      [l for l in _log3.splitlines() if "conn.ini" in l][:1])
_edir = os.path.join(_tmpdir, "conn没有")
os.makedirs(_edir, exist_ok=True)
app_main.program_dir = lambda: _edir
cw4 = app_main.MainWindow()
check("Q53e 没有 conn.ini 时照旧用内置默认值起界面，并说一句怎么预填",
      cw4.ed_host.text() == "127.0.0.1" and cw4.ed_user.text() == "root"
      and "没找到 conn.ini" in cw4.log.toPlainText(),
      [l for l in cw4.log.toPlainText().splitlines() if "conn.ini" in l][:1])
# ---- 启动自动连接：配了连接信息就不再点「连接并加载库」 ----
app_main.program_dir = lambda: _cdir
_CONNS.clear()
aw = app_main.MainWindow()
check("Q53h 建窗本身不发连接请求（连接发生在窗口显示之后）",
      not _CONNS and aw._auto_conn_worker is None, len(_CONNS))
aw.show()
_ok_auto = _wait_auto(aw)
_al = aw.log.toPlainText()
check("Q53i 有 conn.ini 时窗口一显示就自动连接，并选中配置里的目标库",
      _ok_auto and aw.cb_db.count() == 2 and aw.cb_db.currentText() == "alpha"
      and "[连接中] 启动自动连接 10.0.0.8:3307（用户 ops）" in _al
      and "[已加载] 目标库按 conn.ini 选中：alpha" in _al
      and bool(_CONNS) and _CONNS[-1].closed,
      (_ok_auto, aw.cb_db.currentText(), _CONNS[-1].closed if _CONNS else None,
       [l for l in _al.splitlines() if "自动连接" in l][:1]))
aw.close()


def _auto_fail_connect(cfg, **_kw):
    raise RuntimeError("(2003, \"Can't connect to MySQL server on '10.0.0.8'\")")


app_main.connect = _auto_fail_connect
POPUPS.clear()
af = app_main.MainWindow()
af.show()
_ok_fail = _wait_auto(af)
_afl = af.log.toPlainText()
check("Q53j 自动连接失败只写日志不弹窗，红色状态栏留着可手动重试",
      _ok_fail and not POPUPS
      and "[连接失败] 启动自动连接失败" in _afl and "库列表未加载" in _afl
      and "设置数据源信息" in _afl
      and "⚠" in af.lbl_conn_state.text()
      and af.cb_db.isEnabled() is False,
      (POPUPS, [l for l in _afl.splitlines() if "连接失败" in l][:1]))
af.close()
app_main.connect = _fake_connect

app_main.program_dir = lambda: _edir
nc = app_main.MainWindow()
nc.show()
_wait_auto(nc)
check("Q53k 没配连接信息就不去连（不制造一条本机 3306 的假告警）",
      "[连接中] 启动自动连接" not in nc.log.toPlainText()
      and nc._auto_conn_worker is None,
      [l for l in nc.log.toPlainText().splitlines() if "连接" in l][:2])
nc.close()

# 随包 conn.ini 是空模板（database 留空）：读得到，但没点名目标库就不该去连
_tdir = os.path.join(_tmpdir, "conn没点库")
os.makedirs(_tdir, exist_ok=True)
with io.open(os.path.join(_tdir, "conn.ini"), "w", encoding="utf-8") as _tf:
    _tf.write("[mysql]\nhost = 10.0.0.8\nport = 3307\nuser = ops\n"
              "password = pw-file\ndatabase =\n")
app_main.program_dir = lambda: _tdir
tw = app_main.MainWindow()
tw.show()
_wait_auto(tw)
check("Q53n 配置里没点名目标库就不自动连（随包空模板不该让新装用户一开界面就见失败）",
      not tw._conn_auto and tw._auto_conn_worker is None
      and "[连接中] 启动自动连接" not in tw.log.toPlainText()
      and tw.ed_host.text() == "10.0.0.8",
      (tw._conn_auto, [l for l in tw.log.toPlainText().splitlines() if "连接" in l][:2]))
tw.close()

# 自动连接还在路上时用户自己点了「连接并加载库」：迟到的自动结果不许覆盖手动结果
app_main.program_dir = lambda: _cdir
lw = app_main.MainWindow()
lw._db_load_seq += 1                  # 模拟 _auto_connect 已发车
lw._auto_conn_seq = lw._db_load_seq
lw.on_load_databases()                # 手动接手，序号再 +1
_lw_before = [lw.cb_db.itemText(i) for i in range(lw.cb_db.count())]
_lw_log0 = lw.log.toPlainText()
lw._auto_connect_done(["beta", "gamma"])   # 迟到的自动结果
_lw_after = [lw.cb_db.itemText(i) for i in range(lw.cb_db.count())]
check("Q53l 手动加载之后，迟到的自动连接结果作废（不覆盖用户刚选的库）",
      _lw_before == ["alpha", "beta"] and _lw_after == _lw_before
      and lw.cb_db.currentText() == "alpha"
      and "gamma" not in lw.log.toPlainText()[len(_lw_log0):],
      (_lw_before, _lw_after, lw.cb_db.currentText()))
lw._auto_connect_failed("(2003, \"late\")")
check("Q53l2 迟到的自动失败也作废，不在用户已连上的窗口里留红字",
      "[连接失败] 启动自动连接失败" not in lw.log.toPlainText())
lw.close()

_mdir = os.path.join(_tmpdir, "conn库里没有")
os.makedirs(_mdir, exist_ok=True)
with io.open(os.path.join(_mdir, "conn.ini"), "w", encoding="utf-8") as _mf:
    _mf.write("[mysql]\nhost = 10.0.0.8\nport = 3307\nuser = ops\n"
              "password = pw-file\ndatabase = omega\n")
app_main.program_dir = lambda: _mdir
mw = app_main.MainWindow()
mw.on_load_databases()
check("Q53m 配置里的目标库不在服务器上时说清楚并让人手选，不静默落到第一个库",
      "[跳过] conn.ini 指定的目标库 omega 不在服务器上" in mw.log.toPlainText()
      and mw.cb_db.count() == 2 and mw.cb_db.currentText() != "omega"
      and mw.cb_db.isEnabled(),
      (mw.cb_db.currentText(),
       [l for l in mw.log.toPlainText().splitlines() if "跳过" in l][:1]))
mw.close()

app_main.program_dir = _pdir0
for _w in (cw, cw2, cw3, cw4, aw, af, nc, tw, lw, mw):
    _w.close()

# ---- 开始导入：成功路径 ----
iw = app_main.MainWindow()
iw.cb_db.addItem("alpha", "alpha")
iw._add_files([jx1, jx2])
n_tables = len(iw.tables)
POPUPS.clear()
iw.on_start_import()
done = _wait_worker(iw)
conn = _CONNS[-1]
app_main.connect = _orig_connect
check("Q8 「开始导入」真的建表并写入数据（假连接实测副作用）",
      done and conn.rows == 4 and conn.commits >= 2, f"rows={conn.rows} commits={conn.commits}")
check("Q9 每张表一个事务：begin 次数等于表数", conn.begins == n_tables,
      f"{conn.begins} vs {n_tables}")
check("Q10 导入线程正常收尾并关闭连接",
      conn.closed is True and not iw.worker.isRunning(), conn.closed)
check("Q11 成功的文件自动移出待导入列表",
      iw.tables == [] and iw.file_table.rowCount() == 0, iw.tables)
check("Q12 汇总同时写日志与弹窗",
      "全部完成：成功 2 张表" in iw.log.toPlainText()
      and any(t == "导入完成" for t, _ in POPUPS),
      [l for l in iw.log.toPlainText().splitlines() if "全部完成" in l])
check("Q13 列表空后导入按钮自动禁用", iw.btn_import.isEnabled() is False)
check("Q13b 导入结束后进度条回到初始 0%，不留 100% 残留",
      iw.progress.value() == 0, iw.progress.value())


# ---- 开始导入：建表失败要保留文件、逐条标红 ----
class _BoomConn(_QConn):
    def cursor(self):
        c = _QCursor(self)
        c.conn.boom_create = True
        return c


app_main.connect = lambda cfg: _BoomConn(cfg)
fw = app_main.MainWindow()
fw.cb_db.addItem("alpha", "alpha")
fw._add_files([jx1, jx2])
POPUPS.clear()
fw.on_start_import()
_wait_worker(fw)
app_main.connect = _orig_connect
check("Q14 建表失败的文件留在列表里可重试", len(fw.tables) == 2, len(fw.tables))
check("Q14b 导入失败同样把进度条归零（不停在半途也不停在 100%）",
      fw.progress.value() == 0, fw.progress.value())
check("Q15 失败逐条标红且汇总记为失败",
      "[建表失败]" in fw.log.toPlainText() and "失败 2 张表" in fw.log.toPlainText(),
      [l for l in fw.log.toPlainText().splitlines() if "建表失败" in l][:1])

# ---- 停止导入 ----
app_main.connect = _fake_connect
sw = app_main.MainWindow()
sw.cb_db.addItem("alpha", "alpha")
sw._add_files([jx1, jx2])
sw.on_start_import()
sw.on_stop()
check("Q16 点「停止」立刻给出可见反馈", "[正在中止" in sw.log.toPlainText(),
      sw.log.toPlainText().splitlines()[-1:])
_wait_worker(sw)
app_main.connect = _orig_connect
check("Q17 任务结束后停止按钮恢复禁用", sw.btn_stop.isEnabled() is False)

aw = app_main.MainWindow()
aw.worker = app_main.ImportWorker({"host": "h", "port": 1, "user": "u", "password": "",
                                   "database": "d"}, [], False)
sink_probe = app_main.ProgressSink(is_cancelled=lambda: aw.worker._abort)
aw.worker.abort()
check("Q18 abort 置位后工作侧立刻能读到取消标志",
      aw.worker._abort is True and sink_probe.cancelled is True)

# ---- 清空待上传文件 / 两页日志各清各的 ----
cw = app_main.MainWindow()
cw._add_files([jx1])
cw.log.appendPlainText("Excel 页占位")
cw.sql_log.appendPlainText("SQL 页占位")
cw.btn_clear.click()
cw.progress.setValue(66)
cw.btn_clear.click()
check("Q19a 按钮文案已改为「清空待上传文件」",
      cw.btn_clear.text() == "清空待上传文件", cw.btn_clear.text())
check("Q19 「清空待上传文件」清掉数据与界面并禁用导入按钮",
      cw.tables == [] and cw.file_table.rowCount() == 0 and cw.btn_import.isEnabled() is False)
check("Q19b 清空待上传文件时进度条一起归零，不留上次跑到一半的痕迹",
      cw.progress.value() == 0, cw.progress.value())
cw.btn_clear_log.click()
check("Q20 「清除日志」只清本页，不影响另一页",
      cw.log.toPlainText() == "" and "SQL 页占位" in cw.sql_log.toPlainText(),
      cw.sql_log.toPlainText())
cw.log.appendPlainText("Excel 页保留")
cw.sql_log.appendPlainText("SQL 页占位")
cw.btn_sql_clear_log.click()
check("Q21 SQL 页清日志不影响另一页",
      cw.sql_log.toPlainText() == "" and "Excel 页保留" in cw.log.toPlainText(),
      cw.log.toPlainText())

# ---- 拖拽入口 ----
dw = app_main.MainWindow()
dw.on_files_dropped(["C:\\某处\\说明.txt", "D:\\图片.png"])
check("Q22 拖入非 Excel/ZIP 时明确告知已忽略",
      "已忽略" in dw.log.toPlainText() and dw.tables == [],
      dw.log.toPlainText().splitlines()[-1:])
POPUPS.clear()
dw.on_files_dropped([jx1, "C:\\readme.md"])
check("Q23 混合拖入只收合法文件，且不再报「全部忽略」",
      len(dw.tables) == 1 and dw.log.toPlainText().count("已忽略") == 1,
      [t["table_name"] for t in dw.tables])

# ---- SQL 页：添加 / 去重 / 前缀 / 移除 ----
qsql = os.path.join(_tmpdir, "两表脚本.sql")
open(qsql, "w", encoding="utf-8").write(
    "CREATE TABLE `t_one` (`id` INT);\nCREATE TABLE `t_two` (`id` INT);\n")
sw2 = app_main.MainWindow()
POPUPS.clear()
_orig_gofn = app_main.QFileDialog.getOpenFileNames
app_main.QFileDialog.getOpenFileNames = staticmethod(lambda *a, **k: ([qsql], "SQL 脚本 (*.sql)"))
try:
    sw2.on_sql_add_files()
    sw2.on_sql_add_files()
finally:
    app_main.QFileDialog.getOpenFileNames = staticmethod(_orig_gofn)
check("Q24 选进来的脚本加载进列表并逐表展示",
      len(sw2.sql_files) == 1 and sw2.sql_table.rowCount() == 2, sw2.sql_table.rowCount())
_sql_serials = [sw2.sql_table.item(i, 0).text() for i in range(sw2.sql_table.rowCount())]
check("Q24a 建模语句表首列是连续序号，最后一行就是解析出的表数",
      sw2.sql_table.horizontalHeaderItem(0).text() == "序号"
      and _sql_serials == ["1", "2"]
      and _sql_serials[-1] == str(sw2.sql_table.rowCount()), _sql_serials)
check("Q25 重复添加同一脚本被拦下并写明原因",
      "[跳过] 已在列表" in sw2.sql_log.toPlainText(),
      log_lines(sw2.sql_log.toPlainText(), "[跳过]"))
_add_pops = [t for title, t in POPUPS if title == "添加结果"]
check("Q25a 添加结果弹窗列出完整路径，重复项也写全路径",
      len(_add_pops) >= 2 and qsql in _add_pops[0].replace("\n", "")
      and ("[跳过] %s：已在列表" % qsql) in _add_pops[-1].replace("\n", ""),
      [x.replace("\n", " ")[:80] for x in _add_pops])
check("Q25b 添加结束在日志里留一行汇总",
      ("===== 添加完成：1 个脚本，0 个读取失败，0 个重复 =====" in sw2.sql_log.toPlainText()
       and "===== 添加完成：0 个脚本，0 个读取失败，1 个重复 =====" in sw2.sql_log.toPlainText()),
      log_lines(sw2.sql_log.toPlainText(), "====="))
check("Q25c2 第二页的日志框也每条带年月日+钟点、按级别打标",
      all(LOG_HEAD_RE.match(l) for l in sw2.sql_log.toPlainText().splitlines()
          if l.strip() and not l.startswith("    "))
      and any("] ! [WARN] [跳过]" in l for l in sw2.sql_log.toPlainText().splitlines()),
      sw2.sql_log.toPlainText().splitlines()[:4])
POPUPS.clear()
_missing_sql = os.path.join(_tmpdir, "不存在的脚本.sql")
app_main.QFileDialog.getOpenFileNames = staticmethod(
    lambda *a, **k: ([_missing_sql], "SQL 脚本 (*.sql)"))
try:
    sw2.on_sql_add_files()
finally:
    app_main.QFileDialog.getOpenFileNames = staticmethod(_orig_gofn)
_fail_pop = [t for title, t in POPUPS if title == "添加结果"]
check("Q25c 读取失败也进添加结果弹窗，写明完整路径与原因",
      _fail_pop and ("[读取失败] %s：" % _missing_sql) in _fail_pop[-1].replace("\n", "")
      and "读取失败" in sw2.sql_log.toPlainText()
      and len(sw2.sql_files) == 1,
      _fail_pop[-1:])
check("Q26 表名清单随加载缓存（刷新表格不必回扫几十万行）",
      len(sw2.sql_files[0].get("tabs") or []) == 2, sw2.sql_files[0].get("tabs"))
# 拖拽那一路：与第一页同一套（只收本类型的文件，收不到的当场说清）。
# 放在这一组 sw2 用例之后，是因为另起窗口添加脚本也会往 POPUPS 里写"添加结果"，
# 混在中间会让上面 Q25a 那条"最后一个弹窗是重复项"读到的不是 sw2 的那颗
qsql2 = os.path.join(_tmpdir, "拖进来的.sql")
open(qsql2, "w", encoding="utf-8").write("CREATE TABLE `t_drag` (`id` INT);\n")
qtxt = os.path.join(_tmpdir, "说明.txt")
open(qtxt, "w", encoding="utf-8").write("不是脚本\n")
dw = app_main.MainWindow()
dw.on_sql_files_dropped([qsql2, qtxt])
check("Q26b 拖进来的 .sql 直接读入，混在里面的别的文件点名跳过",
      len(dw.sql_files) == 1 and os.path.basename(qsql2) in dw.sql_files[0]["path"]
      and "[跳过] 不是 SQL 脚本，未读入：说明.txt" in dw.sql_log.toPlainText(),
      log_lines(dw.sql_log.toPlainText(), "[跳过]"))
_dw_before = len(dw.sql_files)
dw.on_sql_files_dropped([qtxt])
check("Q26c 一个 .sql 都没拖进来时给一句已忽略，不静默不动",
      len(dw.sql_files) == _dw_before
      and "[提示] 拖入的不是 SQL 脚本文件，已忽略。" in dw.sql_log.toPlainText(),
      dw.sql_log.toPlainText().splitlines()[-1:])
check("Q26d 第二页已经没有「添加 SQL 文件」那颗按钮，入口只剩拖拽区与点击",
      not any(b.text() == "添加 SQL 文件"
              for b in dw.findChildren(app_main.QPushButton))
      and dw.sql_drop.acceptDrops(),
      [b.text() for b in dw.findChildren(app_main.QPushButton)][:6])
dw.close()
POPUPS.clear()
sw2.ed_sql_prefix.setText("ods_")
sw2.on_sql_apply_prefix()
_cur = [sw2.sql_table.item(i, 3).text() for i in range(sw2.sql_table.rowCount())]
_orig = [sw2.sql_table.item(i, 2).text() for i in range(sw2.sql_table.rowCount())]
check("Q27 加前缀后「当前表名」更新而「原表名」保持原样，脚本未改",
      _cur == ["ods_t_one", "ods_t_two"] and _orig == ["t_one", "t_two"]
      and "`ods_t_one`" not in sw2.sql_files[0]["text"]
      and sw2.sql_files[0]["modified"] is False, (_orig, _cur))
_qmeta = sw2._build_sql_metadata_dialog(0)
check("Q27a 前缀只改显示时，双击行看到的元数据标题也用显示名",
      _qmeta is not None and _qmeta.windowTitle() == "表元数据（ods_t_one）",
      None if _qmeta is None else _qmeta.windowTitle())
if _qmeta is not None:
    _qmeta.close()
sw2.on_sql_apply_prefix()                     # 同一前缀再点一次
_cur = [sw2.sql_table.item(i, 3).text() for i in range(sw2.sql_table.rowCount())]
check("Q27d 同一前缀重复点不叠加，始终只多一段",
      _cur == ["ods_t_one", "ods_t_two"], _cur)
sw2.ed_sql_prefix.setText("change")
sw2.on_sql_replace_prefix()                   # 换首段接在当前显示名之后
_cur = [sw2.sql_table.item(i, 3).text() for i in range(sw2.sql_table.rowCount())]
check("Q27e 换首段基于当前显示名：ods_t_one → change_t_one",
      _cur == ["change_t_one", "change_t_two"]
      and "`change_t_one`" not in sw2.sql_files[0]["text"], _cur)
sw2.ed_sql_prefix.setText("")
sw2.on_sql_apply_prefix()
_cur = [sw2.sql_table.item(i, 3).text() for i in range(sw2.sql_table.rowCount())]
check("Q27b 前缀清空后再点一次，表名显示恢复脚本原名且正文仍未改",
      _cur == ["t_one", "t_two"] and "前缀已清空" in sw2.sql_log.toPlainText()
      and sw2.sql_files[0]["modified"] is False, _cur)
sw2.ed_sql_prefix.setText("ods_")
sw2.on_sql_apply_prefix()
sw2.sql_table.item(0, 3).setText("t_one")
_app.processEvents()
check("Q27c 手动把显示名改回脚本里的原名 = 只撤掉这一行的显示前缀",
      sw2.sql_table.item(0, 3).text() == "t_one"
      and "显示前缀已移除，脚本未改写" in sw2.sql_log.toPlainText()
      and "t_one" not in sw2.sql_files[0].get("display_map", {}), 
      sw2.sql_files[0].get("display_map"))
_n_before = len(sw2.sql_files)
sw2.on_sql_clear_files()
check("Q28 「清空待上传文件」整份清出清单并留痕，生成按钮跟着熄灭",
      _n_before == 1 and sw2.sql_files == [] and sw2.sql_table.rowCount() == 0
      and not sw2.btn_sql_gen_std.isEnabled() and not sw2.btn_sql_gen_simple.isEnabled()
      and "[清空] 已清空 1 个脚本文件" in sw2.sql_log.toPlainText(),
      (_n_before, sw2.sql_files))
sw2.on_sql_clear_files()
check("Q28a 空清单再点清空也留一句话，不静默不动",
      "清单里没有脚本文件" in sw2.sql_log.toPlainText(),
      sw2.sql_log.toPlainText()[-120:])
POPUPS.clear()
sw2._finish_sql_execute({
    "conn_failed": False, "ok": 1, "fail": 1, "skipped": 1, "cancelled": False,
    "target": "testdb@127.0.0.1:3306",
    "files": [{"path": qsql, "status": "ok", "detail": "2 条语句已提交"},
              {"path": _missing_sql, "status": "fail",
               "detail": "第 1/2 条语句报错，本文件事务已回滚"},
              {"path": os.path.join(_tmpdir, "空脚本.sql"), "status": "skip",
               "detail": "没有可执行的语句"}]})
_exec_pop = [t for title, t in POPUPS if title == "执行完成"]
_exec_flat = _exec_pop[-1].replace("\n", "") if _exec_pop else ""
check("Q28b 执行结果弹窗列目标库与每个文件的完整路径、结局",
      _exec_flat.startswith("成功 1 个文件，失败 1 个文件。")
      and "目标库 testdb@127.0.0.1:3306" in _exec_flat
      and ("[成功] %s：2 条语句已提交" % qsql) in _exec_flat
      and ("[失败] %s：" % _missing_sql) in _exec_flat
      and "[跳过]" in _exec_flat, _exec_flat[:120])
_ser_sql_a = os.path.join(tempfile.mkdtemp(prefix="pt_oser_"), "a.sql")
_ser_sql_b = os.path.join(tempfile.mkdtemp(prefix="pt_oser_"), "b.sql")
open(_ser_sql_a, "w", encoding="utf-8").write(
    "CREATE TABLE `t_a` (`id` INT);\nCREATE TABLE `t_a2` (`id` INT);\n")
open(_ser_sql_b, "w", encoding="utf-8").write("CREATE TABLE `t_b` (`id` INT);\n")
for _p in (_ser_sql_a, _ser_sql_b):
    sw2.sql_files.append({"path": _p, "text": open(_p, encoding="utf-8").read(),
                          "modified": False, "rename_map": {}})
sw2._refresh_sql_table()
_serials = [sw2.sql_table.item(i, 0).text() for i in range(sw2.sql_table.rowCount())]
check("Q28a 多个脚本文件的建模语句共用一套连续序号，最后一行即总数",
      _serials == ["1", "2", "3"]
      and _serials[-1] == str(sw2.sql_table.rowCount()),
      _serials)
sw2.sql_files = []
sw2._refresh_sql_table()

# ---- 空脚本 / 空列表时两颗前缀按钮：弹窗说明，不静默报"0 个表名" ----
_empty_sql = os.path.join(_tmpdir, "空脚本.sql")
open(_empty_sql, "w", encoding="utf-8").write("")
ew2 = app_main.MainWindow()
POPUPS.clear()
app_main.QFileDialog.getOpenFileNames = staticmethod(
    lambda *a, **k: ([_empty_sql], "SQL 脚本 (*.sql)"))
try:
    ew2.on_sql_add_files()
finally:
    app_main.QFileDialog.getOpenFileNames = staticmethod(_orig_gofn)
check("Q29 空脚本加载日志不再套两层括号",
      "[已加载] 空脚本.sql：0 个表（未发现 CREATE TABLE 语句）" in ew2.sql_log.toPlainText(),
      log_lines(ew2.sql_log.toPlainText(), "[已加载]"))
POPUPS.clear()
ew2.ed_sql_prefix.setText("dim_")
ew2.on_sql_apply_prefix()
check("Q29a 空脚本点「批量应用前缀」弹窗说明没有可处理的表",
      any(t == "提示" and "没有解析出 CREATE TABLE" in x for t, x in POPUPS), POPUPS)
POPUPS.clear()
ew2.on_sql_replace_prefix()
check("Q29b 空脚本点「替换首个前缀」同样弹窗说明",
      any(t == "提示" and "没有解析出 CREATE TABLE" in x for t, x in POPUPS), POPUPS)
_empty_list = app_main.MainWindow()
POPUPS.clear()
_empty_list.ed_sql_prefix.setText("dim_")
_empty_list.on_sql_apply_prefix()
check("Q29c 没有加任何脚本时点加前缀，弹窗要求先添加文件",
      any(t == "提示" and x == "请先添加 SQL 脚本文件。" for t, x in POPUPS), POPUPS)
POPUPS.clear()
_empty_list.ed_sql_prefix.setText("")
_empty_list.on_sql_replace_prefix()
check("Q29d 没有脚本且前缀为空时点替换，也先弹窗说明",
      any(t == "提示" and x == "请先添加 SQL 脚本文件。" for t, x in POPUPS), POPUPS)

# ---- 空列表时的预览入口：只提示，不弹空窗 ----
ew = app_main.MainWindow()
POPUPS.clear()
_built = []
_real_sd, _real_dd = app_main.StructureDialog, app_main.DDLDialog


class _NoDialog:
    def __init__(self, *a, **k):
        _built.append(self)

    def exec(self):
        return 0


app_main.StructureDialog = _NoDialog
app_main.DDLDialog = _NoDialog
ew.on_preview_structure()
ew.on_preview_ddl()
app_main.StructureDialog, app_main.DDLDialog = _real_sd, _real_dd
check("Q52 无文件时预览入口只提示，不构造空对话框",
      not _built and sum(1 for t, _ in POPUPS if t == "提示") == 2, (_built, POPUPS))
check("Q30 提示文案指向「添加 Excel 文件或 ZIP 压缩包」",
      all("ZIP" in x for t, x in POPUPS if t == "提示"), POPUPS[:1])

# ---- 压缩包预扫计数与跨批次目录 ----
from core.zip_import import count_excel_entries, classify

check("Q31 预扫条目数与真正解出的数量一致（进度总数的前提）",
      count_excel_entries(zp1) == len(r["files"]),
      (count_excel_entries(zp1), len(r["files"])))
check("Q32 打不开或不存在的包预扫返回 0，错误留给解压时报",
      count_excel_entries(fake) == 0
      and count_excel_entries(os.path.join(_tmpdir, "不存在.zip")) == 0)
zonly = os.path.join(_tmpdir, "只有嵌套包.zip")
with zipfile.ZipFile(zonly, "w") as z:
    z.writestr("in/inner.zip", b"PK\x03\x04fake")
    z.writestr("readme.md", "# nothing")
check("Q33 只有嵌套 zip 与非 Excel 时预扫为 0", count_excel_entries(zonly) == 0)
check("Q34 classify 把锁文件与嵌套包分别归类",
      classify(zipfile.ZipInfo("~$a.xlsx")) == "lock"
      and classify(zipfile.ZipInfo("b.zip")) == "nested"
      and classify(zipfile.ZipInfo("c.xlsx")) == "excel")
zw2 = app_main.MainWindow()
zw2._add_files([zdup])
paths1 = [t["filepath"] for t in zw2.tables]
n1 = len(zw2.tables)
zw2._add_files([zdup])
names2 = [t["table_name"] for t in zw2.tables]
check("Q35 同一个压缩包再加一次解到别的子目录，不覆盖第一批文件",
      n1 == 2 and len(zw2.tables) == 4 and all(os.path.isfile(x) for x in paths1),
      (n1, len(zw2.tables)))
check("Q35b 两批同包产出的表名互不冲突", len(set(names2)) == 4, names2)

# ---- 解析中途取消：部分结果 + 弹窗首行标注 ----
_cancel = {"v": False}


def _watch(done, total, label):
    if done >= 2:
        _cancel["v"] = True


s2 = app_main.ProgressSink(on_progress=_watch, is_cancelled=lambda: _cancel["v"])
res_c = app_main.parse_import_files([probe, jx1, jx2], set(), None, 0, s2)
check("Q36 取消后工作函数提前收尾并如实标记",
      res_c["cancelled"] is True and 0 < len(res_c["tables"]) < 3,
      (res_c["cancelled"], len(res_c["tables"])))
POPUPS.clear()
cwin = app_main.MainWindow()
cwin._finish_add_files(res_c)
check("Q37 取消添加的弹窗首行写明只完成了部分解析",
      any("已取消" in x.splitlines()[0] for _, x in POPUPS),
      POPUPS[-1][1].splitlines()[0] if POPUPS else "无弹窗")

# ---- 进度框重入守卫 ----
app_main.MODAL_PROGRESS = True
_FakeProgress.made.clear()
rw2 = app_main.MainWindow()
_ph = app_main.TaskInfo(rw2, (app_main.MainWindow.RES_EXCEL,), "占位任务",
                        rw2.log, None)
_ph.total = 121
_ph.done = 45
_ph.label = "第 45/121 条"
_ph.started = time.time() - 7
rw2._tasks.append(_ph)
res_box.clear()
rw2._run_modal("不该起任务", _slow_task, (3,), on_done=lambda x: res_box.append(x))
check("Q38 已有任务在跑时再起任务被忽略，不叠第二个框",
      _FakeProgress.made == [] and res_box == [], (_FakeProgress.made, res_box))
POPUPS.clear()
rw2._run_modal("不该起任务2", _slow_task, (3,), on_done=lambda x: res_box.append(x))
_busy = [x for _t, x in POPUPS if "已有任务在跑" in x]
check("Q38b 被挡下时说得出谁在跑、跑到几成、跑了多久（不是只回一句请稍等）",
      _busy and "占位任务" in _busy[0] and "37%" in _busy[0]
      and "（45/121）" in _busy[0] and "已 7 秒" in _busy[0]
      and "第 45/121 条" in _busy[0],
      _busy[:1])
check("Q38c 挡下这件事同时写进日志（弹窗会被关掉，日志要留痕）",
      "已有任务在跑" in rw2.log.toPlainText(),
      log_lines(rw2.log.toPlainText(), "已拦下")[:2])

# ---- 并发：不同页可以一起跑，同一页仍然互斥 ----
_MW = app_main.MainWindow
app_main.MODAL_PROGRESS = True
cwin2 = app_main.MainWindow()
t_excel = app_main.TaskInfo(cwin2, (_MW.RES_EXCEL,), "正在解析文件…", cwin2.log, None)
cwin2._tasks.append(t_excel)
cwin2._run_modal("正在读取 SQL 脚本…", _slow_task, (3,), log_view=cwin2.sql_log)
check("Q51 别的页在跑不挡这一页：SQL 页的任务照样起得来",
      len(cwin2._tasks) == 2 and any(t.title.startswith("正在读取") for t in cwin2._tasks),
      [t.title for t in cwin2._tasks])
POPUPS.clear()
cwin2._run_modal("第二个 SQL 页任务", _slow_task, (3,), log_view=cwin2.sql_log)
check("Q51b 同一页的第二个任务仍然被挡，且说得出共用的是哪一份数据",
      len(cwin2._tasks) == 2 and any("SQL 脚本改名入库页的脚本列表" in x
                                     for _t, x in POPUPS),
      [x.replace("\n", " ")[:120] for _t, x in POPUPS])
check("Q51c 非模态：起任务这一句立刻返回，不等框关闭",
      cwin2._tasks and cwin2.task_dialog is not None
      and cwin2.task_dialog.isVisible() is True, len(cwin2._tasks))
t_run = app_main.TaskInfo(cwin2, (_MW.RES_RUN,), "正在执行 SQL…", cwin2.sql_run_log, None)
cwin2._tasks.append(t_run)
check("Q51d 三个资源各自被占：查谁挡了就报谁，没被占的资源不挡路",
      cwin2.task_busy((_MW.RES_EXCEL,)) and cwin2.task_busy((_MW.RES_SQL,))
      and cwin2.task_busy((_MW.RES_RUN,))
      and cwin2._conflict((_MW.RES_RUN,)).title == "正在执行 SQL…",
      [t.title for t in cwin2._tasks])
check("Q51e 两页各占自己那一位：两页的「生成」不再共用产物目录，可以并行",
      app_main.MainWindow._resources_for(cwin2, cwin2.log) == ("excel",)
      and app_main.MainWindow._resources_for(cwin2, cwin2.sql_log) == ("sql",)
      and cwin2._conflict((_MW.RES_SQL,)) is not None
      and cwin2._conflict((_MW.RES_EXCEL,)) is not None,
      [t.resources for t in cwin2._tasks])
from core import meta_export as _mx
check("Q51f 两页的生成件确实落在两个不同目录（sql_ 前缀那档）",
      os.path.basename(_mx.output_dir("R", True)) == _mx.STD_PREFIX + "_"
      + _time.strftime("%Y%m%d")
      and os.path.basename(_mx.output_dir("R", True, dir_tag=_mx.SQL_DIR_TAG))
      == "sql_meta_data_file_" + _time.strftime("%Y%m%d")
      and os.path.basename(_mx.output_dir("R", False, dir_tag=_mx.SQL_DIR_TAG))
      == "sql_simple_meta_data_file_" + _time.strftime("%Y%m%d"),
      [_mx.output_dir("R", True), _mx.output_dir("R", True, dir_tag=_mx.SQL_DIR_TAG)])
for _t in list(cwin2._tasks):
    cwin2._tasks.remove(_t)
cwin2.close()
app_main.MODAL_PROGRESS = False
rw2._tasks.remove(_ph)
app_main.MODAL_PROGRESS = False

# ==== 真线程并发：生产那条路（真进度框 + 子线程 + 真写盘），两条任务同时在跑 ====
# 上面 Q51 那组用替身任务验的是"抢位规则"；这一组验的是"两个真任务真的在同时干活、
# 界面还转得动"——用户要的正是这个，替身验不出来。
_PD_BEFORE = app_main.ProgressDialog
_app_pdir = app_main.program_dir
xwin = app_main.MainWindow()


def _make_big_xlsx(tag, rows):
    """openpyxl 流式写（比 pandas 快得多），只为造几个真要读上半秒的文件。

    拆成好几个而不是一张大表：解析是按阶段计项的，一张几十万行的表总共只有 5 项，
    "读取工作簿"那一项能占三秒不动，进度推进采不到样；八个七千行的文件是 40 项，
    每零点一秒就动一格，"两条任务都在推进"这条断言才咬得住。
    """
    from openpyxl import Workbook
    p = os.path.join(_tmpdir, "并发大表%s_%d.xlsx" % (tag, rows))
    if os.path.exists(p):
        return p
    wb = Workbook(write_only=True)
    ws = wb.create_sheet("数据")
    ws.append(["编号", "名称", "金额", "数量", "日期", "备注"])
    for i in range(rows):
        ws.append(["N%08d" % i, "%s名称%d" % (tag, i), 3.14 + i % 100, i % 997,
                   "2026-03-%02d" % (i % 28 + 1), "备注%d" % (i % 50)])
    wb.save(p)
    return p


def _make_gen_sql(tables):
    p = os.path.join(_tmpdir, "并发生成_%d.sql" % tables)
    if not os.path.exists(p):
        with io.open(p, "w", encoding="utf-8") as f:
            for i in range(tables):
                f.write("CREATE TABLE t_par_%05d (\n" % i)
                for c in range(8):
                    f.write("  col_%02d VARCHAR(64) DEFAULT NULL COMMENT '字段%d',\n" % (c, c))
                f.write("  PRIMARY KEY (id)\n) ENGINE=InnoDB COMMENT='并发表%d';\n" % i)
    return p


BIG_FILES, BIG_ROWS, GEN_TABLES = 8, 7500, 150
big_xlsx = [_make_big_xlsx(chr(ord("A") + k), BIG_ROWS) for k in range(BIG_FILES)]
gen_sql = _make_gen_sql(GEN_TABLES)
gen_root = os.path.join(_tmpdir, "并发输出")
app_main.MODAL_PROGRESS = True               # 这一组全程走真那条路：起线程 + 挂真框
app_main.program_dir = lambda: gen_root
xwin._run_modal("正在读取 SQL 脚本…", app_main.sql_load_task, ([gen_sql], []),
                on_done=xwin._finish_sql_load, log_view=xwin.sql_log)
check("Y0 前置：SQL 页脚本先读进来（这条走的是同一个任务框架）",
      _wait_until(lambda: not xwin._tasks and xwin.sql_files, 20),
      [t.title for t in xwin._tasks])

app_main.ProgressDialog = REAL_PROGRESS      # 这一组必须是真框，替身验不出"框没挡住窗口"
_ticks = [0]
_tick_timer = QTimer()
_tick_timer.setInterval(10)
_tick_timer.timeout.connect(lambda: _ticks.__setitem__(0, _ticks[0] + 1))
_tick_timer.start()
POPUPS.clear()
_t0 = _time.time()
xwin._add_files(big_xlsx)                    # 页 1：解析八个七千行（真子线程）
_launch_a = _time.time() - _t0
_t1 = _time.time()
xwin.on_sql_gen_excel("标准", True)           # 页 2：写 150 个 xlsx（真子线程）
_launch_b = _time.time() - _t1
both = _wait_until(lambda: len(xwin._tasks) == 2, 5)
_a = [t for t in xwin._tasks if t.resources[0] == _MW.RES_EXCEL]
_b = [t for t in xwin._tasks if _MW.RES_SQL in t.resources]
_pa = _a[0].done if _a else -1
_pb = _b[0].done if _b else -1
tk0 = _ticks[0]
_pump(0.4)
_pa2 = _a[0].done if _a else -1
_pb2 = _b[0].done if _b else -1
tk1 = _ticks[0]
dialogs = [t.dialog for t in xwin._tasks]
check("Y1 起任务那两句立刻返回（页 1 解析几万行也没占住主线程）",
      _launch_a < 0.5 and _launch_b < 0.5, (_launch_a, _launch_b))
check("Y1b 两个真任务同时在跑：Excel 页解析与 SQL 页写盘撞在一起",
      both and len(xwin._tasks) == 2, [t.describe() for t in xwin._tasks])
check("Y1c 两条任务各自都在推进（不是只有一根条在动）",
      _pa >= 0 and _pb >= 0 and _pa2 > _pa and _pb2 > _pb, (_pa, _pa2, _pb, _pb2))
check("Y1d 两个真进度框同时在屏，且都不是模态的",
      len(dialogs) == 2 and all(d is not None and isinstance(d, REAL_PROGRESS)
                                and d.isVisible() and d.isModal() is False for d in dialogs),
      [None if d is None else (d.isVisible(), d.isModal()) for d in dialogs])
check("Y1e 任务在跑时主线程事件循环一直在转（界面没被框挡住）",
      tk1 - tk0 >= 5, (tk0, tk1))

# 同一页再点一次：被挡下，但这一页被挡不该影响另一页
POPUPS.clear()
xwin.on_sql_gen_excel("标准", True)
check("Y1f SQL 页的第二个任务被挡下，Excel 页那个照常活着",
      any("已有任务在跑" in x for _t, x in POPUPS)
      and len(xwin._tasks) == 2 and _a[0] in xwin._tasks,
      [x.replace("\n", " ")[:90] for _t, x in POPUPS][:1])

# 光看事件循环在转不够：真点一下第三页的按钮，看不看得到东西冒出来
xwin.main_tabs.setCurrentIndex(2)
POPUPS.clear()
xwin.btn_sql_run_samples.click()
_app.processEvents()
_samp = xwin.sql_run_samples
check("Y1l 两条任务在跑时切到第三页点「示例 (?)」，非模态清单照样开得出来",
      xwin.main_tabs.currentIndex() == 2 and len(xwin._tasks) == 2
      and _samp is not None and _samp.isVisible() and _samp.isModal() is False
      and _samp.list.count() == len(app_main.SQL_SAMPLES),
      (xwin.main_tabs.currentIndex(), len(xwin._tasks),
       None if _samp is None else (_samp.isVisible(), _samp.isModal(),
                                   _samp.list.count())))
if _samp is not None:
    _samp.close()
_r1 = dialogs[0].geometry() if len(dialogs) == 2 else None
_r2 = dialogs[1].geometry() if len(dialogs) == 2 else None
_ov = _r1.intersected(_r2) if _r1 is not None and _r2 is not None else None
check("Y1m 两个进度框不叠在一起（叠了后面那颗的「取消」就点不到）",
      _ov is not None and _ov.isEmpty(),
      None if len(dialogs) != 2 else [d.geometry().getRect() for d in dialogs])


def _gen_count():
    """已经写到盘上的交付件个数（返回数字，别让调用方各自记着要 len()）。"""
    if not os.path.isdir(gen_root):
        return 0
    n = 0
    for _base, _dirs, names in os.walk(gen_root):
        n += sum(1 for x in names if x.endswith(".xlsx"))
    return n


# 谁先收尾不作弊：等到只剩一条，再确认活着那条仍在推进（收尾那条没把它一起带走）
one = _wait_until(lambda: len(xwin._tasks) == 1, 30)
surv = xwin._tasks[0] if one else None
_sd0 = surv.done if surv is not None else -1
_pump(0.4)
_sd1 = surv.done if surv is not None else -1
check("Y1g 一条先收尾，另一条不受牵连、仍在推进",
      one and ((_sd1 > _sd0) or (surv is not None and surv.finished)),
      (one, _sd0, _sd1, None if surv is None else surv.describe()))
files_1 = _gen_count()
tables_1 = len(xwin.tables)
check("Y1h 先收尾那条的结果按事实落了地（文件写出来或表进列表）",
      files_1 > 0 or tables_1 > 0, (files_1, tables_1))

# 掐掉还在跑的那条：它提前收尾要留痕，另一条已经做完的结果一个都不能少
victim = surv
victim.cancel()
gone = _wait_until(lambda: not xwin._tasks, 30)
files_2 = _gen_count()
tables_2 = len(xwin.tables)
whole_gen = files_2 == GEN_TABLES
whole_parse = tables_2 == BIG_FILES
check("Y1i 取消只中止被掐的那一条，另一条的完整结果不受牵连",
      gone and (whole_gen != whole_parse),
      [len(xwin._tasks), files_2, tables_2])
check("Y1j 被取消的那条在自己那一页写明只完成了部分",
      # 资源位是先还的、收尾留痕是随后写的，只读一次会读到空的（Java 那条同一轮踩过）
      _wait_until(lambda: "已取消" in victim.log_view.toPlainText()
                  or any("已取消" in x for _t, x in POPUPS), 15),
      log_lines(victim.log_view.toPlainText(), "已取消")[:1])
check("Y1k 收尾状态干净：没有任务残留、进度条不挂着",
      not xwin._tasks and xwin.progress.value() == 0,
      (len(xwin._tasks), xwin.progress.value()))
_tick_timer.stop()
app_main.ProgressDialog = _PD_BEFORE
app_main.program_dir = _app_pdir
app_main.MODAL_PROGRESS = False
xwin.close()

# ---- 结构预览保存：主键校验不过要挡在写回之前 ----
vw = app_main.MainWindow()
vw._add_files([make_xlsx("重复主键.xlsx", {"S": pd.DataFrame(
    {"编号": ["A", "A"], "名称": ["甲", "乙"]})})])
vd = _real_sd(vw.tables, vw)
cols_before = [dict(c) for c in vd.tables[0]["columns"]]
_real_validate = app_main.validate_primary_key
app_main.validate_primary_key = lambda df, cols: (False, "组合值存在重复")
POPUPS.clear()
vd._on_accept()
check("Q39 主键校验不过时拒绝保存并提示原因",
      any(t == "主键设置无效" for t, _ in POPUPS) and vd.result() == 0,
      POPUPS[-1][1].splitlines()[0] if POPUPS else "无弹窗")
check("Q40 校验不过不会把非法主键写回数据",
      [c.get("primary_key") for c in vd.tables[0]["columns"]]
      == [c.get("primary_key") for c in cols_before],
      [c.get("primary_key") for c in vd.tables[0]["columns"]])
app_main.validate_primary_key = _real_validate
vd._on_accept()
check("Q41 校验放行后正常 accept 并写回", vd.result() == 1)
vd.deleteLater()

# ---- 空表（只有表头）手工勾主键：保存放行并写回 ----
ew2 = app_main.MainWindow()
ew2._add_files([make_xlsx("空表主键.xlsx", {"S": pd.DataFrame(
    {"编号": pd.Series(dtype=object), "名称": pd.Series(dtype=object)})})])
ed2 = _real_sd(ew2.tables, ew2)
tw2 = ed2.tabs.widget(0)
check("Q41a 空表结构弹窗里可以勾选主键", tw2.cellWidget(0, 0) is not None)
tw2.cellWidget(0, 0).setChecked(True)
POPUPS.clear()
ed2._on_accept()
check("Q41b 空表手工主键保存通过并写回",
      ed2.result() == 1 and ed2.tables[0]["columns"][0]["primary_key"] is True,
      (ed2.result(), [c.get("primary_key") for c in ed2.tables[0]["columns"]]))
check("Q41c 空表保存不再弹「没有数据行」", not POPUPS, POPUPS)
ed2.deleteLater()

# ---- 结构性守卫：不能有"点了没反应"的死按钮 ----
_probe_win = app_main.MainWindow()      # 必须持有引用：临时对象被回收会让子按钮先析构
_dead = [b.text() or type(b).__name__
         for b in _probe_win.findChildren(QPushButton)
         if b.receivers("2clicked()") == 0]
check("Q42 主窗口每个按钮都连着槽函数（没有点了没反应的死按钮）", not _dead, _dead)

# ---- 「生成标准/简易excel文件」两颗按钮：启用条件、真落点、日志与弹窗双写 ----
_ew = app_main.MainWindow()
check("Q43 没有表时两颗生成按钮是灰的",
      _ew.btn_gen_std.isEnabled() is False and _ew.btn_gen_simple.isEnabled() is False)
_ew._add_files([jx1, jx2])
_nt = len(_ew.tables)
check("Q44 有表之后两颗生成按钮自动可点",
      _nt > 0 and _ew.btn_gen_std.isEnabled() and _ew.btn_gen_simple.isEnabled(),
      _ew.btn_gen_std.isEnabled())
check("Q45 两颗按钮与「开始导入」在同一个容器里（同一行动作条）",
      _ew.btn_gen_std.parentWidget() is _ew.btn_import.parentWidget()
      and _ew.btn_gen_simple.parentWidget() is _ew.btn_import.parentWidget())
_ew._tasks.append(app_main.TaskInfo(_ew, (app_main.MainWindow.RES_EXCEL,),
                                    "占位", _ew.log, None))   # 假装有个任务在跑
_ew._sync_gen_buttons()
check("Q46 任务在跑时两颗生成按钮变灰，不会两个任务踩同一份表列表",
      _ew.btn_gen_std.isEnabled() is False and _ew.btn_gen_simple.isEnabled() is False)
_ew._tasks.pop()
_ew._sync_gen_buttons()

_out_root = tempfile.mkdtemp(prefix="pack-meta-out-")
import shutil as _shutil
from openpyxl import load_workbook as _lw
_real_pdir = app_main.program_dir
app_main.program_dir = lambda: _out_root
POPUPS.clear()
_ew._gen_excel(u"标准", True)
_std = os.path.join(_out_root, "meta_data_file_" + _time.strftime("%Y%m%d"))
_files = sorted(os.listdir(_std)) if os.path.isdir(_std) else []
check("Q47 标准件落在程序同级的 meta_data_file_年月日 目录，一张表一个文件",
      len(_files) == _nt, _files)
if _files:
    _sn = list(_lw(os.path.join(_std, _files[0]), read_only=True).sheetnames)
    check("Q48 标准件是元数据+数据两个 Sheet", _sn == [u"元数据", u"数据"], _sn)
check("Q49 逐文件日志与收尾汇总都留痕，弹窗把目录名原样报出来",
      "- [INFO] 导出 | 正在写 " in _ew.log.toPlainText()
      and ("===== 生成完成：%d/%d 个文件，目录 %s =====" % (_nt, _nt, _std))
      in _ew.log.toPlainText()
      and any(t == "生成结果" and _std in m.replace("\n", "") for t, m in POPUPS),
      [l for l in _ew.log.toPlainText().splitlines() if "生成完成" in l])
_ew._gen_excel(u"简易", False)
_simd = os.path.join(_out_root, "simple_meta_data_file_" + _time.strftime("%Y%m%d"))
_sfiles = sorted(os.listdir(_simd)) if os.path.isdir(_simd) else []
check("Q50 简易件目录是 simple_meta_data_file_年月日 且只有一个数据 Sheet",
      len(_sfiles) == _nt and _sfiles and
      list(_lw(os.path.join(_simd, _sfiles[0]), read_only=True).sheetnames) == [u"数据"],
      _sfiles)
app_main.program_dir = _real_pdir
_shutil.rmtree(_out_root, ignore_errors=True)
_ew.deleteLater()

app_main.connect = _orig_connect

section("R 替换首个前缀（ods_t_order → change_t_order）")

from core.naming import clean_prefix, is_valid_prefix, swap_first_segment
from core.infer import replace_first_segment as _rfs
from core.sql_script import replace_first_segment_sql

# ---- 切分口径 ----
check("R1 换掉第一个下划线之前的段落，后面原样保留",
      swap_first_segment("ods_t_order", "change") == "change_t_order"
      and swap_first_segment("adb_table2", "change") == "change_table2"
      and swap_first_segment("ods_a_b", "change") == "change_a_b",
      swap_first_segment("ods_a_b", "change"))
check("R2 表名里没有下划线时不改（换前缀不等于改名）",
      swap_first_segment("users", "change") is None)
check("R3 以下划线开头的首段为空，不算前缀，不改",
      swap_first_segment("_tmp", "change") is None)
check("R4 已经是该首段时返回 None，重复点不叠加",
      swap_first_segment("change_t_order", "change") is None)
check("R5 末尾下划线不用写，也不会出现双下划线",
      clean_prefix("change_") == "change" and clean_prefix("`change_ `") == "change"
      and swap_first_segment("ods_a", clean_prefix("change_")) == "change_a")
check("R6 非法字符被识别出来（不会生成非法表名）",
      is_valid_prefix("change") and not is_valid_prefix("cha-ge")
      and not is_valid_prefix("变更") and not is_valid_prefix("")
      and not is_valid_prefix("a b"))

# ---- 表定义层 ----
_t = lambda name: {"table_name": name, "base_table_name": name, "columns": []}
tl = [_t("ods_a"), _t("x_a"), _t("users"), _t("adb_t2")]
renamed, skipped = _rfs(tl, "change")
check("R7 批量替换：该改的改了，不该改的原样保留并进跳过列表",
      [t["table_name"] for t in tl] == ["change_a", "change_a_2", "users", "change_t2"]
      and skipped == ["users"], [t["table_name"] for t in tl])
check("R8 撞名自动去重（两张表换成同一个首段不会互相覆盖）",
      len({t["table_name"] for t in tl}) == 4, [t["table_name"] for t in tl])
check("R9 改名列表回报最终表名（含去重后缀）",
      [(r["old"], r["new"]) for r in renamed]
      == [("ods_a", "change_a"), ("x_a", "change_a_2"), ("adb_t2", "change_t2")],
      [(r["old"], r["new"]) for r in renamed])
check("R10 base_table_name 同步更新，之后再应用前缀基于新名算",
      tl[0]["base_table_name"] == "change_a"
      and apply_table_prefix(tl, "dim_")[0]["table_name"] == "dim_change_a",
      tl[0]["table_name"])
tl2 = [_t("ods_a")]
_rfs(tl2, "change")
again, _ = _rfs(tl2, "change")
check("R11 重复点「替换首个前缀」不叠加、不重复改名",
      tl2[0]["table_name"] == "change_a" and again == [], tl2[0]["table_name"])
tl3 = [_t("ods_a"), _t("ods_b")]
_rfs(tl3, "   ")
check("R12 空输入不动任何表名", [t["table_name"] for t in tl3] == ["ods_a", "ods_b"])

# ---- SQL 脚本文本层 ----
rsql = (
    "CREATE TABLE `ods_t_order` (`id` INT);\n"
    "INSERT INTO `ods_t_order` VALUES (1);\n"
    "DROP TABLE IF EXISTS ods_t_order;\n"
    "CREATE TABLE `adb_table2` (`id` INT);\n"
    "INSERT INTO adb_table2 VALUES (2);\n"
    "CREATE TABLE `users` (`id` INT);\n"
    "SELECT 'ods_t_order 出现在字符串里不该被改' AS c FROM dual;\n"
)
new_text, r_renamed, r_skipped = replace_first_segment_sql(rsql, "change")
check("R13 反引号引用与语句头裸名一并改写",
      "`change_t_order`" in new_text and "DROP TABLE IF EXISTS change_t_order;" in new_text
      and "INSERT INTO adb_table2" not in new_text, new_text)
check("R14 每个表名的改写处数被统计出来",
      {r["old"]: (r["new"], r["n"]) for r in r_renamed}
      == {"ods_t_order": ("change_t_order", 3), "adb_table2": ("change_table2", 2)},
      [{r["old"]: r["n"] for r in r_renamed}])
check("R15 没有下划线的表名进跳过列表", r_skipped == ["users"], r_skipped)
check("R16 字符串字面量里的同名内容不被误伤",
      "SELECT 'ods_t_order 出现在字符串里不该被改'" in new_text)
check("R17 未改写的表名保持原样", "CREATE TABLE `users`" in new_text)
t2, rn2, _ = replace_first_segment_sql(new_text, "change")
check("R18 SQL 页重复点不叠加", t2 == new_text and rn2 == [], len(rn2))
t3, rn3, sk3 = replace_first_segment_sql(rsql, "")
check("R19 空输入不改写脚本，也不报任何跳过（什么都没尝试）",
      t3 == rsql and not rn3 and not sk3, (len(rn3), len(sk3)))

# ---- Excel 导入页 UI ----
f1 = make_xlsx("订单表（ods_t_order）.xlsx", {"S": pd.DataFrame({"编号": ["A1"]})})
f2 = make_xlsx("测试表（adb_table2）.xlsx", {"S": pd.DataFrame({"编号": ["B1"]})})
f3 = make_xlsx("用户表（users）.xlsx", {"S": pd.DataFrame({"编号": ["C1"]})})
def _legacy_naming(win):
    """R 组验的是前缀改写：先关掉两颗命名开关，别让默认大写改写期望值。"""
    win.chk_initials.setChecked(False)
    win.chk_upper.setChecked(False)
    return win


rw3 = _legacy_naming(app_main.MainWindow())
rw3.cb_db.addItem("alpha", "alpha")
rw3._add_files([f1, f2, f3])
rw3.ed_prefix.setText("change")
POPUPS.clear()
rw3.btn_replace_prefix.click()
_names = [rw3.file_table.item(i, 2).text() for i in range(rw3.file_table.rowCount())]
check("R20 点「替换首个前缀」后表格里的表名按首段替换",
      _names == ["change_t_order", "change_table2", "users"], _names)
check("R21 逐条回报旧→新，并单独说明没改的那张",
      "[换前缀] ods_t_order → change_t_order" in rw3.log.toPlainText()
      and "表名里没有下划线，未改动：users" in rw3.log.toPlainText(),
      log_lines(rw3.log.toPlainText(), "[换前缀]"))
check("R22 汇总写明改了几张、跳过几张",
      "共改 2 张表，跳过 1 张" in rw3.log.toPlainText(),
      [l for l in rw3.log.toPlainText().splitlines() if "换前缀完成" in l])
rw3.ed_prefix.setText("dim_")
rw3.on_apply_prefix()
check("R23 替换后的表名成为新基础名，再加前缀基于它算",
      rw3.file_table.item(0, 2).text() == "dim_change_t_order",
      [rw3.file_table.item(i, 2).text() for i in range(3)])

rw4 = _legacy_naming(app_main.MainWindow())
rw4._add_files([f1])
rw4.ed_prefix.setText("cha-nge")
POPUPS.clear()
rw4.btn_replace_prefix.click()
check("R24 非法前缀被拒绝并说明原因，表名不变",
      any(t == "提示" and "非法字符" in x for t, x in POPUPS)
      and rw4.tables[0]["table_name"] == "ods_t_order",
      POPUPS[-1][1] if POPUPS else "无弹窗")
rw4.ed_prefix.setText("")
POPUPS.clear()
rw4.btn_replace_prefix.click()
check("R25 空输入提示先填内容", any("请先输入新的前缀" in x for _, x in POPUPS),
      POPUPS[-1][1] if POPUPS else "无弹窗")
rw5 = app_main.MainWindow()
POPUPS.clear()
rw5.btn_replace_prefix.click()
check("R26 没有文件时只提示，不报错",
      any(x == app_main.NO_FILE_HINT for _, x in POPUPS) and rw5.tables == [])

# ---- SQL 脚本改名入库页 UI ----
rpath = os.path.join(_tmpdir, "换前缀样本.sql")
open(rpath, "w", encoding="utf-8").write(rsql)
sw3 = app_main.MainWindow()
_orig_gofn2 = app_main.QFileDialog.getOpenFileNames
app_main.QFileDialog.getOpenFileNames = staticmethod(lambda *a, **k: ([rpath], "SQL (*.sql)"))
try:
    sw3.on_sql_add_files()
finally:
    app_main.QFileDialog.getOpenFileNames = staticmethod(_orig_gofn2)
sw3.ed_sql_prefix.setText("change")
sw3.btn_sql_replace.click()
_cur = [sw3.sql_table.item(i, 3).text() for i in range(sw3.sql_table.rowCount())]
_orig = [sw3.sql_table.item(i, 2).text() for i in range(sw3.sql_table.rowCount())]
check("R27 SQL 页替换后「当前表名」更新、「原表名」仍是最初的名字",
      _cur == ["change_t_order", "change_table2", "users"]
      and _orig == ["ods_t_order", "adb_table2", "users"], (_orig, _cur))
check("R28 替换只落在显示层：脚本正文与修改标记都没动",
      "`change_t_order`" not in sw3.sql_files[0]["text"]
      and sw3.sql_files[0]["modified"] is False
      and sw3.sql_files[0].get("display_map")
      == {"ods_t_order": "change_t_order", "adb_table2": "change_table2"},
      sw3.sql_files[0].get("display_map"))
check("R29 日志逐条回报显示名与跳过原因，并写明正文未改",
      "[换前缀] 换前缀样本.sql：ods_t_order → change_t_order（显示）、"
      "adb_table2 → change_table2（显示）" in sw3.sql_log.toPlainText()
      and "users" in sw3.sql_log.toPlainText()
      and "脚本正文未改，点保存/执行/生成时才写入" in sw3.sql_log.toPlainText(),
      log_lines(sw3.sql_log.toPlainText(), "[换前缀"))
POPUPS.clear()
sw3.btn_sql_save.click()
saved = os.path.join(_tmpdir, "换前缀样本_prefix.sql")
check("R30 点保存才把替换后的表名写进脚本并落盘",
      os.path.isfile(saved) and "`change_t_order`" in open(saved, encoding="utf-8").read()
      and "`change_t_order`" in sw3.sql_files[0]["text"]
      and sw3.sql_files[0]["modified"] is False
      and any(t == "保存完成" for t, _ in POPUPS),
      (saved, [t for t, _ in POPUPS]))

# ---- 进度框：与「批量应用前缀」同一套机制 ----
# O 组末尾把 ProgressDialog 换成了真类（端到端取证用），这里换回替身才能记到刷新
app_main.ProgressDialog = _FakeProgress
app_main.MODAL_PROGRESS = True
_FakeProgress.made.clear()
pw3 = _legacy_naming(app_main.MainWindow())
pw3.cb_db.addItem("alpha", "alpha")
pw3._add_files([f1, f2, f3])
# 这一支把 MODAL_PROGRESS 换回 True，_add_files 因此走真线程。机器被别的闸门同时压满时
# 它还没收工，下一句点「替换首个前缀」就被同页互斥挡下、压根不弹进度框
# （实测红过一次：拿到的 dlg 是上一步那颗，total=15 而不是 4）。等它腾出资源位再点。
_wait_until(lambda: not pw3.task_busy((pw3.RES_EXCEL,)), 20)
pw3.ed_prefix.setText("ods2")
pw3.btn_replace_prefix.click()
dlg = _last_dialog()
check("R31 「替换首个前缀」走模态进度框，按表计项且跳过的表也算一项",
      dlg is not None and "替换" in dlg.title and dlg.accepted
      and dlg.updates[0][1] == 4                      # 3 张表 + 1 次重名去重
      and [u[0] for u in dlg.updates] == [0, 1, 2, 3, 4]
      and "users" in [u[2] for u in dlg.updates],     # 跳过的表也报了一格
      dlg.updates if dlg else None)
_FakeProgress.made.clear()
sw3.ed_sql_prefix.setText("ods3")
sw3.btn_sql_replace.click()
_cur = [sw3.sql_table.item(i, 3).text() for i in range(sw3.sql_table.rowCount())]
check("R32 SQL 页替换同样只改显示：不弹进度框、脚本正文保持保存时的样子",
      _FakeProgress.made == [] and _cur == ["ods3_t_order", "ods3_table2", "users"]
      and "`change_t_order`" in sw3.sql_files[0]["text"]
      and sw3.sql_files[0]["modified"] is False,
      (_FakeProgress.made, _cur))
# 执行按钮：动作用的是脚本正文，所以点下去也必须先把显示名写进脚本；
# 目标库故意指向必然拒绝连接的端口，验证"先落名、再连库"的顺序。
sw3.cb_db.addItem("alpha", "alpha")
sw3.ed_host.setText("127.0.0.1")
sw3.ed_port.setValue(1)
_FakeProgress.made.clear()
sw3.btn_sql_run.click()
_dlg3 = _last_dialog()
check("R32b 点「执行修改后ddl脚本到当前库」时显示名先写进脚本，连库失败也不回退",
      _dlg3 is not None and "`ods3_t_order`" in sw3.sql_files[0]["text"]
      and "[生效]" in sw3.sql_log.toPlainText()
      and "[连接失败]" in sw3.sql_log.toPlainText(),
      (sw3.sql_files[0]["text"][:60], _FakeProgress.made))
app_main.MODAL_PROGRESS = False

# ---- 第二个页签的「生成标准/简易excel文件」：表定义来自脚本正文，不是来自 Excel ----
gsql = os.path.join(_tmpdir, "gen_probe.sql")
with open(gsql, "w", encoding="utf-8") as _gfh:
    _gfh.write("CREATE TABLE `t_gen` (\n"
               "  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',\n"
               "  `name` varchar(16) NOT NULL COMMENT '名称',\n"
               "  `st` enum('a','b') DEFAULT 'a',\n"
               "  PRIMARY KEY (`id`)\n"
               ") ENGINE=InnoDB COMMENT='生成检查表';\n"
               "CREATE TABLE t_dup9 (x INT);\n"
               "CREATE TABLE t_dup9 (y INT);\n"
               "CREATE TABLE t_like9 LIKE t_dup9;\n")
gw = app_main.MainWindow()
check("R33 没加脚本时两颗生成按钮是灰的",
      not gw.btn_sql_gen_std.isEnabled() and not gw.btn_sql_gen_simple.isEnabled())
_orig_gofn4 = app_main.QFileDialog.getOpenFileNames
app_main.QFileDialog.getOpenFileNames = staticmethod(lambda *a, **k: ([gsql], "SQL (*.sql)"))
try:
    gw.on_sql_add_files()
finally:
    app_main.QFileDialog.getOpenFileNames = staticmethod(_orig_gofn4)
check("R34 有脚本后两颗按钮亮起，tooltip 说清数据 Sheet 只有表头",
      gw.btn_sql_gen_std.isEnabled() and gw.btn_sql_gen_simple.isEnabled()
      and "只有表头" in gw.btn_sql_gen_std.toolTip()
      and "英文名表头" in gw.btn_sql_gen_simple.toolTip(), gw.btn_sql_gen_std.toolTip())
gout = os.path.join(_tmpdir, "sqlgen_out")
_pdir = app_main.program_dir
app_main.program_dir = lambda: gout
POPUPS.clear()
try:
    gw.btn_sql_gen_std.click()
    gw.btn_sql_gen_simple.click()
finally:
    app_main.program_dir = _pdir
_glog = gw.sql_log.toPlainText()
check("R35 近似掉的类型逐列点名（UNSIGNED 与 ENUM 各一条）",
      "! [WARN] 说明 | `t_gen`：列 id 的" in _glog and "（丢了 UNSIGNED）" in _glog
      and "取值列表 'a','b'" in _glog,
      [l for l in _glog.splitlines() if "说明" in l])
check("R36 同名表第二处与没有列定义的 LIKE 都给出说明",
      "表名 t_dup9 在本脚本里被定义了多次" in _glog
      and "后面没有列定义" in _glog,
      [l for l in _glog.splitlines() if "WARN" in l])
def _lsd(p):
    """目录不在就当空列表：产物没生成要红一条用例，不该 FileNotFoundError 中止整轮。"""
    return sorted(os.listdir(p)) if os.path.isdir(p) else []


_std = [d for d in _lsd(gout) if d.startswith("sql_meta_data_file_")]
_sim = [d for d in _lsd(gout) if d.startswith("sql_simple_meta_data_file_")]
_std0 = _std[0] if _std else ""
_sim0 = _sim[0] if _sim else ""
_want_files = {"t_dup9.xlsx", "生成检查表（t_gen）.xlsx"}
check("R37 标准件与简易件各建一个日期目录，文件名沿用 表注释（表名）.xlsx",
      len(_std) == 1 and len(_sim) == 1
      and set(_lsd(os.path.join(gout, _std0))) == _want_files
      and set(_lsd(os.path.join(gout, _sim0))) == _want_files,
      (_lsd(gout), _lsd(os.path.join(gout, _std0))))
from core.infer import build_table_def as _btd
_gpath = os.path.join(gout, _std0, "生成检查表（t_gen）.xlsx")
_bt = _btd(_gpath) if os.path.isfile(_gpath) else {}
_gcols = _bt.get("columns") or []
check("R38 标准件能被本工具原样读回（走结构定义那条路），主键与近似类型都对得上",
      _bt.get("source") == "structured" and len(_gcols) == 3
      and _bt.get("table_name") == "t_gen" and _bt.get("table_comment") == "生成检查表"
      and _gcols[:1] and _gcols[0]["field"] == "id" and _gcols[0]["primary_key"]
      and _gcols[2:3] and _gcols[2]["type"] == "TEXT",
      (_bt.get("source"), _bt.get("table_name"), _bt.get("table_comment"),
       [(c["field"], c["type"], c["primary_key"]) for c in _gcols]))
import openpyxl as _gxl
_gpath2 = os.path.join(gout, _sim0, "生成检查表（t_gen）.xlsx")
_gwb = _gxl.load_workbook(_gpath2) if os.path.isfile(_gpath2) else None
check("R39 简易件只有「数据」一个 Sheet，且脚本里没有数据行（只剩表头那一行）",
      _gwb is not None and _gwb.sheetnames == ["数据"] and _gwb["数据"].max_row == 1
      and [c.value for c in _gwb["数据"][1]] == ["id", "name", "st"],
      (None if _gwb is None else _gwb.sheetnames,
       None if _gwb is None else _gwb["数据"].max_row))
check("R40 两次生成各弹一次「生成结果」，正文里带目录名",
      sum(1 for t, _b in POPUPS if t == "生成结果") == 2
      and all("已生成 2 个文件" in b for t, b in POPUPS if t == "生成结果"),
      [(t, b[:40]) for t, b in POPUPS])
gw.ed_sql_prefix.setText("ods_")
gw.on_sql_apply_prefix()
app_main.program_dir = lambda: os.path.join(_tmpdir, "sqlgen_out2")
try:
    gw.btn_sql_gen_std.click()
finally:
    app_main.program_dir = _pdir
_g2 = os.path.join(_tmpdir, "sqlgen_out2")
_std2 = [d for d in _lsd(_g2) if d.startswith("sql_meta_data_file_")]
_std20 = _std2[0] if _std2 else ""
check("R41 先改前缀再生成：产物里的表名是改过之后的",
      len(_std2) == 1 and set(_lsd(os.path.join(_g2, _std20)))
      == {"ods_t_dup9.xlsx", "生成检查表（ods_t_gen）.xlsx"},
      (_lsd(_g2), _lsd(os.path.join(_g2, _std20))))
gw2 = app_main.MainWindow()
POPUPS.clear()
gw2.on_sql_gen_excel("标准", True)
check("R42 没有脚本时点生成按钮当场拦下：日志与弹窗同一句",
      any(t == "提示" and "请先添加 SQL 脚本文件" in b for t, b in POPUPS)
      and "已拦下：请先添加 SQL 脚本文件。" in gw2.log.toPlainText(),
      [(t, b[:40]) for t, b in POPUPS])

# ---- 第二个页签的大小写开关：只留大写一项，清单/元数据/生成物同步，脚本正文默认不动 ----
nsql = os.path.join(_tmpdir, "name_probe.sql")
with open(nsql, "w", encoding="utf-8") as _nfh:
    _nfh.write("CREATE TABLE `t_case9` (\n"
               "  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',\n"
               "  `订单金额` decimal(10,2) DEFAULT NULL COMMENT '金额',\n"
               "  `客户名称` varchar(32) DEFAULT NULL COMMENT '客户',\n"
               "  PRIMARY KEY (`id`)\n"
               ") ENGINE=InnoDB COMMENT='大小写检查表';\n")
nw = app_main.MainWindow()
check("R43 第一页两颗命名开关默认勾选，第二页只留大小写开关且默认不勾选",
      nw.chk_initials.isChecked() and nw.chk_upper.isChecked()
      and not nw.chk_sql_upper.isChecked() and not hasattr(nw, "chk_sql_initials"))
_orig_gofn5 = app_main.QFileDialog.getOpenFileNames
app_main.QFileDialog.getOpenFileNames = staticmethod(lambda *a, **k: ([nsql], "SQL (*.sql)"))
try:
    nw.on_sql_add_files()
finally:
    app_main.QFileDialog.getOpenFileNames = staticmethod(_orig_gofn5)
check("R44 未勾大写时清单沿用脚本原名",
      nw.sql_table.item(0, 3).text() == "t_case9"
      and nw._sql_rows[0]["actual"] == "t_case9",
      (nw.sql_table.item(0, 3).text(), nw._sql_rows[0]["actual"]))
POPUPS.clear()
nw.chk_sql_upper.setChecked(True)
_nlog = nw.sql_log.toPlainText()
check("R44b 第二页大小写切换写进日志：清单、元数据、生成物一起变，脚本正文不改",
      "[提示] 表名与字段名大小写切换为：大写"
      "（当前表名清单、表元数据和生成的 Excel 一起变；脚本正文不改）" in _nlog,
      _nlog.splitlines()[-1:])
check("R44c 已加载的行切换大写后立即刷新，实际脚本名仍保持原样",
      nw.sql_table.item(0, 3).text() == "T_CASE9"
      and nw._sql_rows[0]["actual"] == "t_case9",
      (nw.sql_table.item(0, 3).text(), nw._sql_rows[0]["actual"]))
_meta = nw._build_sql_metadata_dialog(0)
check("R45 双击 SQL 行可查看该表元数据，字段随大写开关显示",
      _meta is not None and _meta.tableWidget().rowCount() == 3
      and _meta.tableWidget().item(0, 0).text() == "ID"
      and _meta.tableWidget().item(1, 0).text() == "订单金额",
      None if _meta is None else [
          _meta.tableWidget().item(r, 0).text() for r in range(_meta.tableWidget().rowCount())])
check("R45a2 元数据弹窗的字段表左上也有标识，标题里带列数",
      _meta is not None
      and box_title(_meta.tableWidget()) == "字段清单（共 3 列）",
      None if _meta is None else box_title(_meta.tableWidget()))
_nrow_text_before = nw.sql_files[0]["text"]
_sql_headers = [nw.sql_table.horizontalHeaderItem(c).text()
                for c in range(nw.sql_table.columnCount())]
check("R45b 第二页列为 序号/文件/原表名/当前表名/表注释，没有「CREATE 出现次数」",
      _sql_headers == ["序号", "文件", "原表名", "当前表名", "表注释"], _sql_headers)
check("R45c 脚本一导入就把表注释摆在清单里，取的是 CREATE TABLE 那句 COMMENT",
      nw.sql_table.item(0, 4).text() == "大小写检查表"
      and nw._sql_rows[0]["comment"] == "大小写检查表",
      nw.sql_table.item(0, 4).text())
_sql_flags = [bool(nw.sql_table.item(0, c).flags() & Qt.ItemIsEditable)
              for c in range(nw.sql_table.columnCount())]
check("R45d 第二页可编辑的是「当前表名」与「表注释」两列",
      _sql_flags == [False, False, False, True, True], _sql_flags)
# 表注释这格双击进编辑器；双击其余列才是"看这一行的元数据"
_seen_meta = []
_orig_show_meta = nw.show_sql_table_metadata
nw.show_sql_table_metadata = lambda r: _seen_meta.append(r)
try:
    nw.on_sql_table_double_clicked(nw.sql_table.item(0, 2))
    nw.on_sql_table_double_clicked(nw.sql_table.item(0, 1))
finally:
    nw.show_sql_table_metadata = _orig_show_meta
check("R45e 双击原表名与文件名仍然只打开这一行的元数据", _seen_meta == [0, 0], _seen_meta)
# 表注释这一列在没有 COMMENT 的脚本上必须是空的：sw2 那份表名被前面的用例改过，另起一份
cw9 = app_main.MainWindow()
app_main.QFileDialog.getOpenFileNames = staticmethod(lambda *a, **k: ([qsql], "SQL (*.sql)"))
try:
    cw9.on_sql_add_files()
finally:
    app_main.QFileDialog.getOpenFileNames = staticmethod(_orig_gofn)
check("R45f 没有表注释的脚本这一列是空的，不编一个占位文字出来",
      cw9.sql_table.item(0, 4).text() == "" and cw9._sql_rows[0]["comment"] == "",
      repr(cw9.sql_table.item(0, 4).text()))

# ---- 表注释手动改：写回那句 CREATE TABLE 的表选项 ----
csql = os.path.join(_tmpdir, "注释改写.sql")
with io.open(csql, "w", encoding="utf-8") as _cfh:
    _cfh.write("CREATE TABLE `t_cm1` (`id` INT) ENGINE=InnoDB COMMENT='原注释';\n"
               "CREATE TABLE t_cm2 (`id` INT);\n"
               "CREATE TABLE t_cm3 LIKE t_cm2;\n")
cmw = app_main.MainWindow()
_orig_gofn8 = app_main.QFileDialog.getOpenFileNames
app_main.QFileDialog.getOpenFileNames = staticmethod(lambda *a, **k: ([csql], "SQL (*.sql)"))
try:
    cmw.on_sql_add_files()
finally:
    app_main.QFileDialog.getOpenFileNames = staticmethod(_orig_gofn8)
_crcm = cmw.sql_files[0]
check("R45g 三种写法都读到位：已有注释、没有注释、LIKE 那句",
      [r["comment"] for r in cmw._sql_rows] == ["原注释", "", ""],
      [r["comment"] for r in cmw._sql_rows])
POPUPS.clear()
cmw.sql_table.item(0, 4).setText("改过的注释")
check("R45h 改表注释写进那句 CREATE TABLE 的表选项，脚本先留在内存",
      "COMMENT='改过的注释'" in _crcm["text"] and "COMMENT='原注释'" not in _crcm["text"]
      and _crcm["modified"] is True and cmw.sql_table.item(0, 4).text() == "改过的注释",
      _crcm["text"].splitlines()[0])
_cdlg = cmw._build_sql_metadata_dialog(0)
check("R45i 改完立刻能在元数据弹窗里看到新注释（读的是同一份缓存）",
      _cdlg is not None and "改过的注释" in _cdlg.findChild(app_main.QLabel).text()
      and "[表注释] t_cm1 → 改过的注释（已写进脚本；未落盘）" in cmw.sql_log.toPlainText(),
      log_lines(cmw.sql_log.toPlainText(), "[表注释]"))
cmw.sql_table.item(1, 4).setText("补上的注释")
check("R45j 原本没有 COMMENT 的，在列定义右括号后补一句，读回来就是它",
      "CREATE TABLE t_cm2 (`id` INT) COMMENT='补上的注释';" in _crcm["text"]
      and cmw.sql_table.item(1, 4).text() == "补上的注释", _crcm["text"].splitlines()[1])
cmw.sql_table.item(0, 4).setText("带'单引号'的")
check("R45k 注释里的单引号按 MySQL 口径翻倍写，再读回来一模一样",
      "COMMENT='带''单引号''的'" in _crcm["text"]
      and cmw.sql_table.item(0, 4).text() == "带'单引号'的",
      (_crcm["text"].splitlines()[0], cmw.sql_table.item(0, 4).text()))
cmw.sql_table.item(0, 4).setText("")
check("R45l 清空注释写的是 COMMENT=''，清单这一格跟着变空",
      "COMMENT=''" in _crcm["text"] and cmw.sql_table.item(0, 4).text() == ""
      and "[表注释] t_cm1 → （空）（已写进脚本；未落盘）" in cmw.sql_log.toPlainText(),
      (_crcm["text"].splitlines()[0], cmw.sql_table.item(0, 4).text()))
_text_before_like = _crcm["text"]
POPUPS.clear()
cmw.sql_table.item(2, 4).setText("LIKE 那句改不动")
check("R45m LIKE 写法没有表选项可挂：弹窗明说、脚本一个字不动、日志不记假成功",
      _crcm["text"] == _text_before_like
      and any(t == "提示" and "没有可改写的表选项" in b for t, b in POPUPS)
      and "[表注释] t_cm3" not in cmw.sql_log.toPlainText(),
      (POPUPS, log_lines(cmw.sql_log.toPlainText(), "[表注释]")))
# 只多打了一个空格：Qt 看得到文本变了会发 itemChanged，去空格后内容其实没变——
# 这条路必须有回执，且刷回清单时把多打的空格去掉（静默 no-op 等于界面在骗人）
cmw.sql_table.item(0, 4).setText(" ")
check("R45n 注释实际没变化也留一句回执并原样刷回清单（不静默 no-op）",
      cmw.sql_log.toPlainText().count("[表注释] t_cm1 未发生变化，脚本未改写") == 1
      and cmw.sql_table.item(0, 4).text() == "",
      log_lines(cmw.sql_log.toPlainText(), "[表注释]"))
# 改过的注释一路走到产物文件名：文件名是"表注释（表名）.xlsx"这一路读的就是脚本正文
cmw.sql_table.item(0, 4).setText("改过的注释")
cout = os.path.join(_tmpdir, "sqlgen_comment_out")
app_main.program_dir = lambda: cout
try:
    cmw.btn_sql_gen_std.click()
finally:
    app_main.program_dir = _pdir
_cd = [d for d in _lsd(cout) if d.startswith("sql_meta_data_file_")]
_cfiles = _lsd(os.path.join(cout, _cd[0])) if _cd else []
check("R45o 手改的表注释一路带到标准件文件名（产物读的是改写后的脚本正文）",
      "改过的注释（t_cm1）.xlsx" in _cfiles and "补上的注释（t_cm2）.xlsx" in _cfiles,
      (_cd, _cfiles))
from PySide6.QtWidgets import QAbstractItemDelegate as _QDelegate


def _edit_sql_name(row, name):
    cell = nw.sql_table.item(row, 3)
    nw.sql_table.setCurrentItem(cell)
    nw.sql_table.editItem(cell)
    _app.processEvents()
    editor = nw.sql_table.findChildren(app_main.QLineEdit)
    if not editor:
        return False
    nw.sql_table.closeEditor(editor[0], _QDelegate.NoHint)
    _app.processEvents()
    nw.sql_table.item(row, 3).setText(name)
    _app.processEvents()
    return True


check("R46a 当前表名单元格确实能进入编辑器", _edit_sql_name(0, "manual_case"))
check("R46 双击当前表名可直接编辑，并同步改写这一行脚本的 CREATE 引用",
      nw.sql_files[0]["text"] != _nrow_text_before
      and "CREATE TABLE `manual_case`" in nw.sql_files[0]["text"]
      and nw._sql_rows[0]["actual"] == "manual_case"
      and nw.sql_table.item(0, 3).text() == "MANUAL_CASE",
      (nw._sql_rows[0]["actual"], nw.sql_table.item(0, 3).text()))
_edit_sql_name(0, "t_case9")
_ntext = _nrow_text_before
nout = os.path.join(_tmpdir, "sqlgen_name_out")
app_main.program_dir = lambda: nout
try:
    nw.btn_sql_gen_std.click()
finally:
    app_main.program_dir = _pdir
_nd = [d for d in _lsd(nout) if d.startswith("sql_meta_data_file_")]
_nd0 = _nd[0] if _nd else ""
_nfiles = _lsd(os.path.join(nout, _nd0))
check("R47 只勾大写：生成物表名转大写、中文列名原样保留，脚本正文一个字不动",
      _nfiles == ["大小写检查表（T_CASE9）.xlsx"]
      and nw.sql_files[0]["text"] == _ntext,
      (_nfiles, nw.sql_files[0]["text"] != _ntext))
_np = os.path.join(nout, _nd0, "大小写检查表（T_CASE9）.xlsx")
# 结构定义读回时表名取自文件名，默认会转小写；要验大写就带上 upper=True 读
_nt = _btd(_np, upper=True) if os.path.isfile(_np) else {}
_ncols = _nt.get("columns") or []
check("R48 回读：表名 T_CASE9、字段保留原中文名，列注释原样保留",
      _nt.get("table_name") == "T_CASE9"
      and [c["field"] for c in _ncols] == ["ID", "订单金额", "客户名称"]
      and [c["comment"] for c in _ncols] == ["主键", "金额", "客户"],
      (_nt.get("table_name"), [(c["field"], c["comment"]) for c in _ncols]))
check("R49 生成日志写明命名口径（与 Java 同一句）",
      "[命名] 生成物表名与字段名：转大写" in nw.sql_log.toPlainText())
_repl_sql = os.path.join(_tmpdir, "replacement_utf8.sql")
with open(_repl_sql, "wb") as _rfh:
    _rfh.write("CREATE TABLE `t_repl` (`id` int NOT NULL COMMENT '编号')"
               " COMMENT='编号表 ';".encode("utf-8"))
_repl_text = app_main.read_sql_text(_repl_sql)
_repl_tab = app_main.parse_create_tables(_repl_text)[0]
check("R49b 正文自带 U+FFFD 的 UTF-8 脚本仍按 UTF-8 解码",
      _repl_tab["table_comment"] == "编号表 "
      and _repl_tab["columns"][0]["comment"] == "编号",
      (_repl_tab["table_comment"], _repl_tab["columns"][0]["comment"]))

# ---- SQL 页：双击一行的耗时（读入时整份解析一次，点开只取那一张表）----
# 现场反馈是"双击一行后要等一下才出元数据"。慢的不是建对话框，是那一刻把整份
# 脚本重新解析一遍，所以夹具要够大：大到整份解析仍是"人眼等得到"的一档。
_big_sql = os.path.join(_tmpdir, "big_script.sql")
with io.open(_big_sql, "w", encoding="utf-8") as _bfh:
    for _bt in range(40):
        _bfh.write("CREATE TABLE t_big_%02d (\n" % _bt)
        for _bc in range(20 + _bt % 5):
            _bfh.write("  col_%02d VARCHAR(64) DEFAULT NULL COMMENT '字段%d',\n"
                       % (_bc, _bc))
        _bfh.write("  PRIMARY KEY (col_00)\n) ENGINE=InnoDB COMMENT='大脚本%d';\n" % _bt)
        for _br in range(120):
            # CREATE 之间夹大量 INSERT，行数是靠它们堆上去的（与现场那份同形）
            _bfh.write("INSERT INTO t_big_%02d VALUES ('v%d');\n" % (_bt, _br))
bw = app_main.MainWindow()
_orig_gofn7 = app_main.QFileDialog.getOpenFileNames
app_main.QFileDialog.getOpenFileNames = staticmethod(
    lambda *a, **k: ([_big_sql], "SQL (*.sql)"))
try:
    bw.on_sql_add_files()
finally:
    app_main.QFileDialog.getOpenFileNames = staticmethod(_orig_gofn7)
_brec = bw.sql_files[0]
_bcols = ((_brec.get("parsed") or {}).get("t_big_07") or {}).get("columns") or []
check("R59 读入大脚本时把每张表的字段定义都缓存好，一张不落",
      len(_brec.get("parsed") or {}) == 40 and len(_bcols) == 22,
      (len(_brec.get("parsed") or {}), len(_bcols)))
_t0 = _t_busy.monotonic()
app_main.parse_create_tables(_brec["text"])
_full_ms = (_t_busy.monotonic() - _t0) * 1000.0
app_main.ensure_parsed(_brec).get("t_big_07")     # 先走一次，量的是稳态不是首次调用
_t0 = _t_busy.monotonic()
_hit = app_main.ensure_parsed(_brec).get("t_big_07")
_look_ms = (_t_busy.monotonic() - _t0) * 1000.0
bw._build_sql_metadata_dialog(7)                  # 建窗也先热一次
_t0 = _t_busy.monotonic()
_bdlg = bw._build_sql_metadata_dialog(7)
_open_ms = (_t_busy.monotonic() - _t0) * 1000.0
check("R60 整份重解析测得出耗时（不然下面那条比值是拿 0 除 0）",
      _full_ms >= 1.0, "%.0f 毫秒" % _full_ms)
# 缓存失效时"只取一张"就退化成整份重解析，比值当场就红——判据自己不依赖夹具多大，
# 也不依赖机器快慢（建对话框那部分单列为下面那条绝对上限，不混进比值）
check("R61 双击一行只从缓存取那一张：比整份重解析快一个数量级以上",
      _hit is not None and len(_hit["columns"]) == 22 and _look_ms * 10 < _full_ms,
      "取一张 %.2f 毫秒 / 整份 %.0f 毫秒" % (_look_ms, _full_ms))
check("R61b 到对话框真建出来也不到人等得到的那一档（低于 300 毫秒）",
      _open_ms < 300.0 and _bdlg is not None and _bdlg.tableWidget().rowCount() == 22,
      "建对话框 %.1f 毫秒" % _open_ms)
bw.sql_table.item(7, 3).setText("t_big_renamed")
check("R62 改表名把缓存里那一格一起改名，不留旧键",
      "t_big_renamed" in (_brec.get("parsed") or {})
      and "t_big_07" not in (_brec.get("parsed") or {})
      and _brec["parsed"]["t_big_renamed"]["table_name"] == "t_big_renamed",
      sorted((_brec.get("parsed") or {}))[:3])
_t0 = _t_busy.monotonic()
_bdlg2 = bw._build_sql_metadata_dialog(7)
_open2_ms = (_t_busy.monotonic() - _t0) * 1000.0
check("R63 改名后点开仍是毫秒级，字段一个没少",
      _open2_ms < 300.0 and _bdlg2 is not None
      and _bdlg2.tableWidget().rowCount() == 22, "%.1f 毫秒" % _open2_ms)
# 撞车：新名和另一张表同名。缓存留着可用（刷新清单要读它，丢了就等于把整份重解析
# 搬回主线程），同名两行并成一行。改名那一步本身要重写正文+重取表名清单，
# 拿它的耗时去比整份解析比不出东西，所以这里只断缓存的形状，快不快交给 R66
bw.sql_table.item(0, 3).setText("t_big_01")
_parsed_after = _brec.get("parsed") or {}
check("R64 改成与另一张表同名：缓存留着可用，旧名那一张不留在键里",
      _parsed_after and "t_big_00" not in _parsed_after and len(_parsed_after) == 39,
      "键数 %d" % len(_parsed_after))
check("R65 撞名后两张同名表并成一行，注释与字段都取先出现那一张",
      _parsed_after.get("t_big_01", {}).get("table_name") == "t_big_01"
      and len(_parsed_after["t_big_01"]["columns"]) == 20
      and bw.sql_table.rowCount() == 39
      and bw.sql_table.item(0, 3).text() == "t_big_01"
      and bw.sql_table.item(0, 4).text() == "大脚本0",
      (bw.sql_table.rowCount(), bw.sql_table.item(0, 4).text()))
_t0 = _t_busy.monotonic()
_bdlg3 = bw._build_sql_metadata_dialog(0)
check("R66 撞名后点开仍是毫秒级，字段取的是先出现那一张",
      (_t_busy.monotonic() - _t0) * 1000.0 < 100.0 and _bdlg3 is not None
      and _bdlg3.tableWidget().rowCount() == 20, _brec.get("parsed"))
# 前缀「生效」一次改多张表：整份改名走同一套重挂规则
_rk = app_main.rekey_parsed({"a": {"table_name": "a"}, "b": {"table_name": "b"}},
                            {"a": "x", "b": "y"})
check("R67 一次改多张表：每张各改各的键，不做链式二次改名",
      sorted(_rk) == ["x", "y"] and _rk["y"]["table_name"] == "y", _rk)
check("R68 要改的名字不在缓存里、或本就没缓存 → 丢掉整份；新键撞车 → 留先出现那一张",
      app_main.rekey_parsed({"a": {}}, {"c": "d"}) is None
      and app_main.rekey_parsed(None, {"a": "b"}) is None
      and app_main.rekey_parsed({"a": {"table_name": "a"},
                                 "b": {"table_name": "b"}}, {"a": "b"})
      == {"b": {"table_name": "b"}})
bw.close()
bw = None

# ---- Excel 导入页：单击表名单独改一张表 ----
rw6 = _legacy_naming(app_main.MainWindow())
rw6._add_files([f1, f2, f3])
_file_serials = [rw6.file_table.item(i, 0).text() for i in range(rw6.file_table.rowCount())]
check("R49c 文件表首列是连续序号，行数就是当前待导入文件数",
      rw6.file_table.horizontalHeaderItem(0).text() == "序号"
      and _file_serials == ["1", "2", "3"]
      and len(_file_serials) == len(rw6.tables), _file_serials)
_file_flags = [bool(rw6.file_table.item(0, c).flags() & Qt.ItemIsEditable) for c in range(7)]
check("R50 文件表只有「表名」列可编辑，其余列仍只读",
      _file_flags == [False, False, True, False, False, False, False], _file_flags)
_enter_old = rw6.tables[0]["table_name"]
rw6.on_file_table_clicked(0, 2)
_app.processEvents()
_editors = rw6.file_table.findChildren(app_main.QLineEdit)
check("R51 单击「表名」直接进入编辑器", bool(_editors),
      len(_editors))
# 真实编辑器里按回车提交：itemChanged 在编辑器收尾前触发整表刷新，别把编辑器留在半开状态
if _editors:
    _editors[0].setText("enter_name")
    # 不用 QTest.keyClick：冻结包里没有 PySide6.QtTest，直接派发按键事件走同一条编辑器路径
    for _etype in (QEvent.KeyPress, QEvent.KeyRelease):
        _app.sendEvent(_editors[0], QKeyEvent(_etype, Qt.Key_Return, Qt.NoModifier))
    _app.processEvents()
    _app.processEvents()
check("R51c 编辑器里回车提交改名，编辑器随后关闭",
      rw6.tables[0]["table_name"] == "enter_name"
      and rw6.file_table.item(0, 2).text() == "enter_name"
      and not rw6.file_table.findChildren(app_main.QLineEdit),
      (rw6.tables[0]["table_name"], rw6.file_table.item(0, 2).text(),
       len(rw6.file_table.findChildren(app_main.QLineEdit))))
_ed_count = len(rw6.file_table.findChildren(app_main.QLineEdit))
rw6.on_file_table_clicked(0, 3)
_app.processEvents()
check("R51b 单击其他列不会弹编辑器",
      len(rw6.file_table.findChildren(app_main.QLineEdit)) == _ed_count)
rw6.file_table.item(0, 2).setText(_enter_old)
_app.processEvents()
rw6.file_table.item(1, 2).setText("manual_orders")
_app.processEvents()
check("R52 改一张表的表名只动这一张，基础表名同步更新",
      [t["table_name"] for t in rw6.tables] == ["ods_t_order", "manual_orders", "users"]
      and rw6.tables[1]["base_table_name"] == "manual_orders",
      [t["table_name"] for t in rw6.tables])
check("R52b 改名写进日志，说明后续前缀基于新名算",
      "[表名] adb_table2 → manual_orders（手动修改，后续批量前缀基于新名计算）"
      in rw6.log.toPlainText())
rw6.ed_prefix.setText("dim_")
rw6.on_apply_prefix()
_app.processEvents()
check("R53 手动改过的名字成为新基础名：再加前缀基于它算",
      [t["table_name"] for t in rw6.tables]
      == ["dim_ods_t_order", "dim_manual_orders", "dim_users"],
      [t["table_name"] for t in rw6.tables])
rw6.ed_prefix.setText("")
rw6.on_apply_prefix()
_app.processEvents()
check("R53b 清空前缀恢复基础名（手动名保留）",
      [t["table_name"] for t in rw6.tables] == ["ods_t_order", "manual_orders", "users"],
      [t["table_name"] for t in rw6.tables])
POPUPS.clear()
rw6.file_table.item(1, 2).setText("")
_app.processEvents()
check("R54 空表名被拦下并恢复原名",
      rw6.tables[1]["table_name"] == "manual_orders"
      and any("表名不能为空" in x and "已恢复原表名「manual_orders」" in x
              for _, x in POPUPS), POPUPS[-1] if POPUPS else "无弹窗")
POPUPS.clear()
rw6.file_table.item(1, 2).setText("bad name!")
_app.processEvents()
check("R55 含空格/标点的表名被拦下并恢复",
      rw6.tables[1]["table_name"] == "manual_orders"
      and any("含非法字符" in x for _, x in POPUPS), POPUPS[-1] if POPUPS else "无弹窗")
POPUPS.clear()
rw6.file_table.item(1, 2).setText(rw6.tables[0]["table_name"])
_app.processEvents()
check("R56 与其他表重名被拦下并恢复",
      rw6.tables[1]["table_name"] == "manual_orders"
      and any("重名" in x for _, x in POPUPS), POPUPS[-1] if POPUPS else "无弹窗")
rw6.file_table.item(1, 2).setText("  `manual_orders`  ")
_app.processEvents()
check("R57 粘贴带反引号/空白的名字被规范成裸表名",
      rw6.tables[1]["table_name"] == "manual_orders"
      and rw6.file_table.item(1, 2).text() == "manual_orders",
      rw6.file_table.item(1, 2).text())
_real_sd = app_main.StructureDialog
_opened_sd = []


class _ProbeStructure(object):
    def __init__(self, *a, **k):
        _opened_sd.append(a)

    def exec(self):
        return 0


app_main.StructureDialog = _ProbeStructure
try:
    rw6.on_edit_structure_row(0, 2)
    _name_col_only = not _opened_sd
    rw6.on_edit_structure_row(0, 0)
    _other_col_opens = len(_opened_sd) == 1
finally:
    app_main.StructureDialog = _real_sd
check("R58 双击表名不叠结构窗，双击其他列仍打开这一行的结构",
      _name_col_only and _other_col_opens, (_name_col_only, len(_opened_sd)))

section("S 文件名括号内容判定（不允许汉字表名 / 纯数字表名 / 版本号表名）")

from core.infer import parse_table_name_and_comment as _PT, _col_to_field as _CF

_n1, _c1 = _PT("人员信息表（人事处人员）.xlsx")
check("S1 括号内是中文：表名由全部中文一起转拼音，绝不含汉字",
      _n1 == "ren_yuan_xin_xi_biao_ren_shi_chu_ren_yuan"
      and all(ord(c) < 128 for c in _n1), _n1)
check("S2 表注释保留完整文件名（人事处/财务部不会塌成同名）", _c1 == "人员信息表（人事处人员）", _c1)
_n2, _c2 = _PT("人员信息表（7）.xlsx")
check("S3 括号内是纯数字序号：表名转拼音并保留序号，不是只含数字的名字",
      _n2 == "ren_yuan_xin_xi_biao_7" and not _n2.isdigit(), _n2)
check("S4 括号内是年份数字同样回退（不产出 t_2026 这种无意义表名）",
      _PT("人员信息表（2026）.xlsx")[0] == "ren_yuan_xin_xi_biao_2026",
      _PT("人员信息表（2026）.xlsx")[0])
check("S5 括号内以数字开头的英文不算可用标识符，回退拼音",
      _PT("人员信息表（7_dim）.xlsx")[0] == "ren_yuan_xin_xi_biao_7_dim",
      _PT("人员信息表（7_dim）.xlsx")[0])
_n6, _c6 = _PT("北京市水务局-水库基础信息（dim_reservoir_info）.xlsx")
check("S6 括号内是合法英文标识符：仍按老规则取括号内、注释取括号前（回归）",
      _n6 == "dim_reservoir_info" and _c6 == "北京市水务局-水库基础信息", (_n6, _c6))
check("S7 括号内大写英文统一转小写（Linux 下表名区分大小写）",
      _PT("人员信息表（DIM_7）.xlsx")[0] == "dim_7", _PT("人员信息表（DIM_7）.xlsx")[0])
_v2n, _v2c = _PT("订单表（V2）.xlsx")
check("S8 括号内是版本号：不当表名，回退成完整文件名拼音并保留版本段",
      _v2n == "ding_dan_biao_v2" and _v2c == "订单表（V2）"
      and _PT("订单表（v2）.xlsx")[0] == "ding_dan_biao_v2"
      and _PT("订单表（V2_1）.xlsx")[0] == "ding_dan_biao_v2_1",
      (_v2n, _v2c))
check("S8b 版本号回退跟首字母/大小写开关（DDBV2），order2/A1 这类真名字仍直接采用",
      _PT("订单表（V2）.xlsx", initials=True)[0] == "ddbv2"
      and _PT("订单表（V2）.xlsx", initials=True, upper=True)[0] == "DDBV2"
      and _PT("订单表（order2）.xlsx")[0] == "order2"
      and _PT("订单表（A1）.xlsx")[0] == "a1",
      [_PT("订单表（V2）.xlsx", initials=True)[0],
       _PT("订单表（V2）.xlsx", initials=True, upper=True)[0],
       _PT("订单表（order2）.xlsx")[0], _PT("订单表（A1）.xlsx")[0]])
check("S9 空括号与纯符号括号都回退，不产出畸形名",
      _PT("水库（）.xlsx")[0] == "shui_ku" and _PT("水库（-）.xlsx")[0] == "shui_ku",
      (_PT("水库（）.xlsx")[0], _PT("水库（-）.xlsx")[0]))
check("S10 整个文件名只有数字（没中文可转）时补 t_ 前缀，这是唯一可行解",
      _PT("7.xlsx")[0] == "t_7" and _PT("2026.xlsx")[0] == "t_2026",
      (_PT("7.xlsx")[0], _PT("2026.xlsx")[0]))
_a = _PT("人员信息表（人事处）.xlsx")[0]
_b = _PT("人员信息表（财务部）.xlsx")[0]
check("S11 中文括号里写不同部门，得到两个不同表名而不是同名加 _2", _a != _b, (_a, _b))
sf1 = make_xlsx("人员信息表（人事处人员）.xlsx", {"S": pd.DataFrame({"编号": ["A1"]})})
sf2 = make_xlsx("人员信息表（7）.xlsx", {"S": pd.DataFrame({"编号": ["B1"]})})
td_s1 = build_table_def(sf1)
td_s2 = build_table_def(sf2)
check("S12 端到端：真实文件走完整条链路后表名与纯解析一致",
      td_s1["table_name"] == _n1 and td_s2["table_name"] == _n2
      and td_s1["base_table_name"] == td_s1["table_name"],
      (td_s1["table_name"], td_s2["table_name"]))
check("S13 表注释能看出是哪个文件（导入前可核对）",
      td_s1["table_comment"] == "人员信息表（人事处人员）", td_s1["table_comment"])
check("S14 字段规则回归：全拼、超长降级首字母、数字音节整段保留都没被改动带偏",
      _CF("订单金额") == "ding_dan_jin_e"
      and _CF("北京市住房和城乡建设委员会") == "bjszfhcxjswyh"
      and _CF("1.0mm管道日平均入渗量") != _CF("100.0mm管道日平均入渗量"),
      (_CF("订单金额"), _CF("北京市住房和城乡建设委员会")))


section("T 版本号与「关于」框")

import re as _re
from PySide6.QtWidgets import QLabel
from version import APP_VERSION
import appinfo as _ai

# 闸门最后一步会留下上一次构建的指纹（build_stamp.py 是那次构建的凭证，故意不删）。
# 必须在**本组任何一次 build_info() 之前**把它刷成当前树的状态，否则下面 T7/T8b
# 会拿旧指纹跟当前 git 实况比出假红——"刚打完包就单独跑用例集"这条正常序列必红。
# 还要弹掉 sys.modules 里的缓存：本文件更早处可能已经 import 过 build_stamp。
_be = None
if not getattr(sys, "frozen", False):
    import build_exe as _be
    _be.write_build_stamp()
    sys.modules.pop("build_stamp", None)
_i = _ai.build_info()

check("T1 版本号是 X.Y.Z 语义化格式（可带 -alpha.N/-beta.N/-rc.N 阶段后缀），且只有一个来源",
      _re.fullmatch(r"[0-9]+[.][0-9]+[.][0-9]+"
                    r"(-(?:alpha|beta|rc)[.][0-9]+)?", APP_VERSION) is not None, APP_VERSION)
check("T2 appinfo 报的版本与 version.py 一致", _ai.build_info()["version"] == APP_VERSION)

# 版本号除了 version.py，还写死在三个 pom、构建脚本里的 jar 名、随包说明那句命令里。
# 改版本漏一处就会出现"关于框 1.0.3、jar 还叫 1.0.2"这种交付事故，所以逐处核回来。
# 每处都按整段取（含阶段后缀）：只截到 X.Y.Z 的话，"1.1.0-rc.1 vs 1.1.0"会判成一致。
_ROOT = os.path.dirname(os.path.abspath(__file__))
_VERSION_SITES = [
    ("java/pom.xml", r"<app\.version>\s*([0-9][0-9A-Za-z.\-]*)\s*</app\.version>"),
    ("java/pom.xml", r"<project[\s\S]{0,400}?<version>\s*([0-9][0-9A-Za-z.\-]*)\s*</version>"),
    ("java/app/pom.xml", r"<parent>[\s\S]{0,200}?<version>\s*([0-9][0-9A-Za-z.\-]*)\s*</version>"),
    ("java/verify/pom.xml", r"<parent>[\s\S]{0,200}?<version>\s*([0-9][0-9A-Za-z.\-]*)\s*</version>"),
    ("java/构建验证.bat", r"excel2mysql-([0-9][0-9A-Za-z.\-]*)\.jar"),
    ("help_doc.py", r"excel2mysql-([0-9][0-9A-Za-z.\-]*)\.jar"),
    ("java/assets/conn.ini", r"excel2mysql-([0-9][0-9A-Za-z.\-]*)\.jar"),
    ("README.md", r"当前版本 v([0-9][0-9A-Za-z.\-]*)"),
    ("README.md", r"excel2mysql-cli ([0-9][0-9A-Za-z.\-]*)"),
    ("README.md", r"excel2mysql-java-([0-9][0-9A-Za-z.\-]*)-win64"),
    ("README.md", r"excel2mysql-([0-9][0-9A-Za-z.\-]*)\.jar"),
]
_drift = []
_missing_site = []
for _vf, _pat in _VERSION_SITES:
    _vp = os.path.join(_ROOT, *_vf.split("/"))
    if not os.path.isfile(_vp):
        _missing_site.append(_vf)          # 冻结态里没有源码，这条按未覆盖记账
        continue
    _txt = open(_vp, encoding="utf-8").read()
    _got = _re.findall(_pat, _txt)
    if not _got:
        _drift.append("%s 里找不到版本号写法" % _vf)
    elif any(v != APP_VERSION for v in _got):
        _drift.append("%s=%s" % (_vf, "/".join(_got)))
if _missing_site:
    skip("T15 各处写死的版本号与 version.py 同值", "冻结态不含 %s" % "、".join(_missing_site))
else:
    check("T15 各处写死的版本号与 version.py 同值（漏改一处就是交付事故）",
          not _drift, _drift)
tw = app_main.MainWindow()
check("T3 窗口标题带版本号", APP_VERSION in tw.windowTitle(), tw.windowTitle())
check("T4 「i」按钮存在且提示写明是关于",
      tw.btn_about.text() == "i" and "关于" in tw.btn_about.toolTip(), tw.btn_about.toolTip())
_opened_about = []
_real_about = app_main.AboutDialog


class _StubAbout:
    def __init__(self, parent=None):
        _opened_about.append(parent)

    def exec(self):
        return 1


app_main.AboutDialog = _StubAbout
tw.btn_about.click()
check("T5 点「i」打开关于框", len(_opened_about) == 1 and _opened_about[0] is tw)
app_main.AboutDialog = _real_about
ad = _real_about(tw)
_labels = [x.text() for x in ad.findChildren(QLabel)]
check("T6 关于框四行标签齐备（版本/提交/构建日期/作者）",
      all(t in _labels for t in ("版本", "提交", "构建日期", "作者")), _labels[:6])
_be = None
_i = _ai.build_info()


def _run_git_probe():
    """已跟踪文件是否真有未提交改动（-uno 忽略未跟踪），与 appinfo 同一口径。"""
    import subprocess
    try:
        return bool(subprocess.run(["git", "status", "--porcelain", "-uno"],
                                   cwd=os.path.dirname(os.path.abspath(app_main.__file__)),
                                   capture_output=True, text=True, timeout=10).stdout.strip())
    except Exception:
        return True
check("T7 三行的值来自 appinfo，不是写死的字符串",
      ad.rows["version"].text() == "v" + APP_VERSION
      and ad.rows["commit"].text() == _ai.commit_display(_i)
      and ad.rows["built"].text() == _i["built_at"],
      {k: v.text() for k, v in ad.rows.items()})
check("T7b 作者这一行有署名，取的是 ui_help.AUTHOR（Java 侧同一常量，对撞比行名）",
      ad.rows["author"].text() == AUTHOR and bool(AUTHOR.strip()),
      (ad.rows["author"].text(), AUTHOR))
check("T8 提交号可鼠标选中复制（要贴给别人时不用手抄）",
      bool(ad.rows["commit"].textInteractionFlags()))
ad.deleteLater()
check("T8b 构建的 dirty 判定只看已跟踪文件的改动（未跟踪遗留不算）",
      _i["dirty"] == _run_git_probe(),
      "烧入 %s / 现取 %s（工作区里有未跟踪文件时这一对比才有意义）"
      % (_i["dirty"], _run_git_probe()))
check("T9 提交号来源被如实标注（bundle=打包烧入 / git=开发态现取 / unknown）",
      _i["source"] in ("bundle", "git", "unknown"), _i["source"])
check("T10 任何一项取不到都有占位文本，不会显示空白也不抛异常",
      bool(_i["commit"]) and bool(_i["built_at"]), (_i["commit"], _i["built_at"]))
# T11-T13 只能在开发态跑：build_exe 不在 exe 包内（产品入口不 import 它），
# 而且它们会真的写 build_stamp.py / 读 .gitignore，冻结态两样都不存在。
_had_stamp = os.path.isfile(os.path.join(os.path.dirname(os.path.abspath(app_main.__file__)),
                                         "build_stamp.py"))
if not getattr(sys, "frozen", False):
    import build_exe as _be
    _be.write_build_stamp()
    import build_stamp as _bs
    check("T11 打包前生成的 build_stamp 字段齐备、版本与 version.py 一致、时间是完整时刻",
          all(hasattr(_bs, k) for k in ("APP_VERSION", "COMMIT", "BRANCH", "DIRTY", "BUILT_AT"))
          and _bs.APP_VERSION == APP_VERSION
          and _re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}",
                            _bs.BUILT_AT) is not None,
          (getattr(_bs, "COMMIT", "?"), getattr(_bs, "BUILT_AT", "?")))
    check("T12 生成后 appinfo 改口为 bundle，读的就是烧进去的提交号",
          _ai.build_info()["source"] == "bundle" and _ai.build_info()["commit"] == _bs.COMMIT,
          _ai.build_info())
    _gi = io.open(os.path.join(os.path.dirname(os.path.abspath(app_main.__file__)),
                               ".gitignore"), encoding="utf-8").read()
    check("T13 build_stamp.py 属生成物，不入库", "build_stamp.py" in _gi)
else:
    skip("T11-T13 build_stamp 生成与入库排除",
         "冻结态包里没有 build_exe 模块与 .gitignore，只在开发态验")
# 用例自己造的生成物要自己清掉：否则开发态下次启动会误报"读到打包烧入的提交号"
if not _had_stamp:
    try:
        os.remove(os.path.join(os.path.dirname(os.path.abspath(app_main.__file__)),
                               "build_stamp.py"))
    except OSError:
        pass
else:
    _ai.build_info()
if getattr(sys, "frozen", False):
    check("T14 冻结态必须读到打包时烧进去的提交号",
          _ai.build_info()["source"] == "bundle"
          and _ai.build_info()["commit"] not in ("unknown", "无 git 信息"), _ai.build_info())
else:
    skip("T14 冻结态提交号取证", "开发态跑不到 exe 环境，打包闸门里会跑")

section("U 弹窗尺寸与折行（首次打开就得看得见全部文字）")

# 折行不再自己算：正文原样交给弹窗，折点由内容区的实际宽度决定，装不下就出滚动条。
# 所以这一组量的是真弹窗里的真控件，期望值全是量出来的相对关系，不引用产品常量。
_LONG_ONE_LINE = ("任务异常：读取 C:/数据/很长的一个文件名防空警报试鸣信息"
                  "（dim_air_raid_siren）.xlsx 时失败，内部单元格 D17 的类型与推断结果冲突，"
                  "需要人工确认这一列到底是文本还是数字，详见日志里的完整堆栈信息。")
_TALL_TEXT = "\n".join("第 %d 行：这一行本身就不短，加上中文标点和路径信息会更长一些一些一些。"
                       % i for i in range(1, 31))
from PySide6.QtWidgets import QScrollArea as _QScrollArea


def _popup_shown(text, kind="information", title="执行完成"):
    """把真弹窗摆出来，返回窗口与装正文的那个标签（用例桩会绕过真控件，量不到折行）。"""
    box = app_main.build_popup_box(None, title, text, kind, box_class=_REAL_POPUP_BOX)
    box.show()
    _app.processEvents()
    return box, box.body_label()


_box1, _lab1 = _popup_shown(_LONG_ONE_LINE)
_fm1 = _lab1.fontMetrics()
check("U1 弹窗显示的正文就是原文（不预先插换行，窗口拉大才会跟着重排）",
      _lab1.text() == _LONG_ONE_LINE, _lab1.text()[:60])
check("U2 长句不靠横向撑宽窗口：弹窗仍在设计宽内，标签必须靠折行放字",
      _box1.width() <= 880 and _lab1.width() < _fm1.horizontalAdvance(_LONG_ONE_LINE),
      (_box1.width(), _lab1.width(), _fm1.horizontalAdvance(_LONG_ONE_LINE)))
check("U3 折点贴着右边界（最宽一行不小于内容区宽的 90%，不再空着半幅）",
      max(_fm1.horizontalAdvance(ln) for ln in _lab1.text().split("\n"))
      >= 0.9 * _lab1.width(),
      (max(_fm1.horizontalAdvance(ln) for ln in _lab1.text().split("\n")), _lab1.width()))
_box1.close()
_box1.deleteLater()

_box2, _lab2 = _popup_shown(_TALL_TEXT, "critical", "任务异常")
check("U4 30 行长报错整份都在弹窗里，不再换成一句『见日志』",
      "见底部日志" not in _lab2.text()
      and all(("第 %d 行" % i) in _lab2.text() for i in (1, 15, 30)),
      _lab2.text()[-60:])
check("U5 正文装在滚动区里，弹窗高度夹在 420~640 之间",
      bool(_box2.findChildren(_QScrollArea))
      and 420 <= _box2.height() <= 640, _box2.height())
_box2.close()
_box2.deleteLater()

_box3, _lab3 = _popup_shown("请先添加 Excel 文件或 ZIP 压缩包。", "warning", "提示")
check("U6 短文案原样不动（不给正常提示加多余换行）",
      _lab3.text() == "请先添加 Excel 文件或 ZIP 压缩包。", _lab3.text())
_box3.close()
_box3.deleteLater()
_app.processEvents()

_geo = _app.primaryScreen().availableGeometry()
_suites = [("主窗口", app_main.MainWindow(), "1100x780"),
           ("使用说明", app_main.HelpDialog(), "960x700"),
           ("关于", app_main.AboutDialog(), "380x190"),
           ("进度框", REAL_PROGRESS("正在解析文件…"), "480x140"),
           ("表结构预览", app_main.StructureDialog([]), "980x640"),
           ("DDL 预览", app_main.DDLDialog([], False), "900x600")]
for _nm, _win, _want in _suites:
    _win.show()
    _app.processEvents()
    check("U7 %s 的客户区按配置值摆，且整窗落在屏幕可用区内" % _nm,
          _win.property("e2mWant") == _want and _geo.contains(_win.geometry()),
          (_win.property("e2mWant"), _win.geometry().getRect(), _geo.getRect()))
    _win.close()
    _win.deleteLater()
_app.processEvents()

_copy_box = app_main.build_popup_box(
    None, "生成结果",
    "已生成 124 个文件（共 124 张表）。\n目录：\n"
    "C:\\Users\\acliu\\Desktop\\excel2mysql-1021-三端产物\\03-Java-图形与命令行-带JRE\\sql_meta_data_file_20261022",
    "information", box_class=_REAL_POPUP_BOX)
_copy_box.show()
_app.processEvents()
_copy_body = _copy_box.body_label()
check("U8 结果弹窗正文可用鼠标选中并 Ctrl+C 复制",
      bool(_copy_body.textInteractionFlags() & _Qt.TextSelectableByMouse)
      and _copy_body.text().startswith("已生成 124 个文件"),
      (_copy_body.text()[:12], str(_copy_body.textInteractionFlags())))
check("U8b 结果弹窗至少 820x420，产物路径与文件清单看得完整",
      _copy_box.width() >= 820 and _copy_box.height() >= 420,
      (_copy_box.width(), _copy_box.height()))
_copy_box.close()
_copy_box.deleteLater()
_app.processEvents()

# 故意要一个装不下的尺寸：夹取逻辑没生效的话这条会红。
# 本机屏幕大，"没超屏"那种断言在这台机器上咬不住东西
_huge = REAL_PROGRESS("夹取检查")
app_main.fit_client(_huge, 99999, 99999)
_huge.show()
_app.processEvents()
check("U10 要求超大尺寸时被夹回屏幕可用区内",
      _geo.contains(_huge.geometry()) and _huge.width() < 99999,
      (_huge.geometry().getRect(), _geo.getRect()))
_huge.close()
_huge.deleteLater()
_app.processEvents()

# U11 入口在 import 期就压掉了两类第三方警告（openpyxl 读"最小样式表"的每张表一行、
# 冻结态 cryptography 的 Python 3.8 弃用一行）。这里只钉"过滤器还在"，
# 真的行为断言在 3.8 线 W37c：抓 stderr 跑一次命令行，看上面还有没有警告行。
import warnings as _warnings

_pats = [getattr(_f[1], "pattern", str(_f[1])) for _f in _warnings.filters if _f[0] == "ignore"]
check("U11 入口装了压掉第三方警告的过滤器（行为断言见 3.8 线 W37c）",
      any("no default style" in str(_p) for _p in _pats)
      and any("core team" in str(_p) for _p in _pats),
      [str(_p)[:40] for _p in _pats if _p][:4])

section("G MySQL 真实读写（建表→导入→查询→回滚）")

_cfg = {"host": os.environ.get("PT_HOST", "127.0.0.1"),
        "port": int(os.environ.get("PT_PORT", "3306")),
        "user": os.environ.get("PT_USER", ""),
        "password": os.environ.get("PT_PASS", ""),
        "database": os.environ.get("PT_DB", "")}
if not _cfg["user"] or not _cfg["database"]:
    skip("G1-G10 全部 MySQL 真实读写用例",
         "未提供凭据（设 PT_USER / PT_PASS / PT_DB 环境变量后重跑本用例集）")
else:
    try:
        conn = connect(_cfg)
        check("G1 建立连接", True)
        dbs = list_databases(conn)
        check("G2 列出库", _cfg["database"] in dbs, f"{len(dbs)} 个库")
        tname = "packtest_probe"
        create_table(conn, f"DROP TABLE IF EXISTS `{tname}`")
        probe_cols = [{"field": "id", "comment": "主键ID", "type": "INT", "primary_key": True},
                      {"field": "name", "comment": "名称", "type": "VARCHAR(64)"},
                      # 小数位要和写入的数据对得上：写 3 位小数却建 DECIMAL(10,2)，
                      # MySQL 不报错而是静默四舍五入（见 G5b），断言就会对不上
                      {"field": "amount", "comment": "金额", "type": "DECIMAL(10,3)"},
                      {"field": "d", "comment": "日期", "type": "DATE"}]
        create_table(conn, build_ddl(tname, "打包探针表", probe_cols, False))
        check("G3 建表成功", True)
        pdf = pd.DataFrame({"id": [1, 2, 3], "name": ["甲", "乙", "丙"],
                            "amount": [1.5, 2.25, 3.125],
                            "d": ["2026-01-01", "2026-02-02", "2026-03-03"]})
        n = insert_data(conn, tname, probe_cols, pdf, batch=2)
        check("G4 分批插入并落库", n == 3, f"{n} 行")
        with conn.cursor() as c:
            c.execute(f"SELECT `id`, `name`, `amount`, `d` FROM `{tname}` ORDER BY `id`")
            got = [(r[0], r[1], float(r[2]), str(r[3])) for r in c.fetchall()]
        check("G5 逐值回查确认写进去的就是原值（数量、文本、小数、日期都不走样）",
              got == [(1, "甲", 1.5, "2026-01-01"), (2, "乙", 2.25, "2026-02-02"),
                      (3, "丙", 3.125, "2026-03-03")], got)
        conn.rollback()
        with conn.cursor() as c:
            c.execute(f"SELECT COUNT(*) FROM `{tname}`")
            check("G6 已提交的数据不受后续 rollback 影响", c.fetchone()[0] == 3)
        # 小数位比列定义多时 MySQL 静默四舍五入（连 STRICT 模式都不报错），这是真实的
        # 数据丢失模式；两头都验：服务端确实会圆，而类型推断按实测小数位定宽不会建出窄列
        sc_cols = [{"field": "id", "comment": "主键ID", "type": "INT", "primary_key": True},
                   {"field": "v", "comment": "金额", "type": "DECIMAL(10,2)"}]
        create_table(conn, build_ddl(tname + "_sc", "小数位探针表", sc_cols, False))
        insert_data(conn, tname + "_sc", sc_cols,
                    pd.DataFrame({"id": [1], "v": [3.125]}), batch=5)
        with conn.cursor() as c:
            c.execute(f"SELECT `v` FROM `{tname}_sc`")
            rounded = float(c.fetchone()[0])
        wide = {cc["comment"]: cc["type"] for cc in infer_columns(
            pd.DataFrame({"金额": [1.5, 2.25, 3.125], "编号": ["A", "B", "C"]}))}
        check("G6b 小数位不足时服务端静默四舍五入(3.125→3.13)；类型推断按实测给 3 位不踩这个坑",
              rounded == 3.13 and wide["金额"].endswith(",3)"), (rounded, wide["金额"]))
        create_table(conn, f"DROP TABLE IF EXISTS `{tname}_sc`")
        # G6c 1264 回归：旧推断把 2.2e9 判成 INT、把 14 位整数 + 18 位小数压成
        # DECIMAL(30,18)，建表都成功，第一条超界数据才报 "Out of range value"，
        # 现场看到的就是"有时候"导入失败
        _t1264 = tname + "_1264"
        _df1264 = pd.DataFrame({"order_id": [1.0, 2200000000.0],
                                "unit_price_amount": [1e-18, 12345678901234.5]})
        _cols1264 = infer_columns(_df1264)
        create_table(conn, build_ddl(_t1264, "1264回归", _cols1264, False))
        try:
            _n1264 = insert_data(conn, _t1264, _cols1264, _df1264, batch=10)
            with conn.cursor() as c:
                c.execute(f"SELECT `order_id`, `unit_price_amount` FROM `{_t1264}` "
                          f"ORDER BY `order_id`")
                _rows1264 = c.fetchall()
            check("G6c 浮点整数溢出与金额精度不再触发 1264",
                  _n1264 == 2 and [int(r[0]) for r in _rows1264] == [1, 2200000000],
                  _rows1264)
        except Exception as e:
            conn.rollback()
            check("G6c 浮点整数溢出与金额精度不再触发 1264", False,
                  app_main.describe_db_error(e)[:160])
        finally:
            create_table(conn, f"DROP TABLE IF EXISTS `{_t1264}`")
        _t1264s = tname + "_1264s"
        _df1264s = pd.DataFrame({"unit_price_amount": ["999999999999999999.99"]})
        _cols1264s = infer_columns(_df1264s)
        create_table(conn, build_ddl(_t1264s, "1264字符串金额", _cols1264s, False))
        try:
            insert_data(conn, _t1264s, _cols1264s, _df1264s, batch=10)
            with conn.cursor() as c:
                c.execute(f"SELECT `unit_price_amount` FROM `{_t1264s}`")
                _v1264 = c.fetchone()[0]
            check("G6d 18 位金额字符串按字面值落库（不经 float 进位）",
                  str(_v1264) == "999999999999999999.99", _v1264)
        except Exception as e:
            conn.rollback()
            check("G6d 18 位金额字符串按字面值落库（不经 float 进位）", False,
                  app_main.describe_db_error(e)[:160])
        finally:
            create_table(conn, f"DROP TABLE IF EXISTS `{_t1264s}`")
        bad_cols = [{"field": "id", "comment": "主键ID", "type": "INT", "primary_key": True},
                    {"field": "v", "comment": "值", "type": "VARCHAR(2)"}]
        try:
            create_table(conn, build_ddl(tname + "_bad", "坏表", bad_cols, False))
            insert_data(conn, tname + "_bad", bad_cols,
                        pd.DataFrame({"id": [1, 2], "v": ["ok", "超长值"]}), batch=5)
            check("G7 超长值应报错但没有", False)
        except Exception as e:
            conn.rollback()
            check("G7 数据超长触发 MySQL 报错并可回滚", True,
                  app_main.describe_db_error(e)[:120])
        try:
            cur = conn.cursor()
            cur.execute("SELECT * FROM `packtable_not_exist_xyz`")
            check("G8 表不存在应报错但没有", False)
        except Exception as e:
            check("G8 表不存在报错，错误码可展开", getattr(e, "args", [None])[0] == 1146,
                  app_main.describe_db_error(e)[:120])
        create_table(conn, f"DROP TABLE IF EXISTS `{tname}`")
        create_table(conn, f"DROP TABLE IF EXISTS `{tname}_bad`")
        conn.close()
        check("G9 探针表已清理", True)

        # G10 真库启动自动连接：E2M_* 是"用户配过"的口径（口令不落任何文件），
        # 断言窗口显示后自己连上、下拉里真有这个库、并且按配置选中的就是它
        _gdir = os.path.join(_tmpdir, "G10无配置")
        os.makedirs(_gdir, exist_ok=True)
        _gpdir = app_main.program_dir
        _genv = {"E2M_HOST": _cfg["host"], "E2M_PORT": str(_cfg["port"]),
                 "E2M_USER": _cfg["user"], "E2M_PASS": _cfg["password"],
                 "E2M_DB": _cfg["database"]}
        try:
            app_main.program_dir = lambda: _gdir
            os.environ.update(_genv)
            gw = app_main.MainWindow()
            gw.show()
            _gok = _wait_auto(gw)
            _glog = gw.log.toPlainText()
            check("G10 真库启动自动连接：窗口出来后自己连上并按配置选中目标库",
                  _gok and gw.cb_db.isEnabled()
                  and gw.cb_db.findText(_cfg["database"]) >= 0
                  and gw.cb_db.currentText() == _cfg["database"]
                  and ("启动自动连接 %s:%s" % (_cfg["host"], _cfg["port"])) in _glog
                  and ("[已加载] 目标库按 conn.ini 选中：%s" % _cfg["database"]) in _glog,
                  (_gok, gw.cb_db.currentText(), gw.cb_db.count(),
                   [l for l in _glog.splitlines() if "自动连接" in l][:1]))
            gw.close()
        finally:
            for _k in _genv:
                os.environ.pop(_k, None)
            app_main.program_dir = _gpdir
    except Exception as e:
        check("G0 连接 MySQL 失败", False, f"{type(e).__name__}: {e}")
        traceback.print_exc()


# ---------------------------------------------------------------------------
# 收尾：走的是 atexit 上注册的同一道 `_qt_teardown`，Z1 是它自己的哨兵
section("Z 收尾与退出码")

_qt_teardown()
import gc as _gc
_gc.collect()
_left = [x for x in QApplication.topLevelWidgets() if x.isWindow()]
check("Z1 用例留下的顶层窗口全部显式析构（退出码只由用例结果决定）",
      not _left, [(type(x).__name__, x.windowTitle()[:24]) for x in _left][:6])

# ---------------------------------------------------------------------------
print("\n" + "=" * 72)
SUMMARY_PRINTED = True
print(f"通过 {RESULTS['pass']}  失败 {RESULTS['fail']}  跳过 {RESULTS['skip']}")
if FAILURES:
    print("\n失败用例：")
    for f in FAILURES:
        print("  - " + f)
if SKIPPED:
    print("\n未覆盖（显式记账，不算通过）：")
    for label, why in SKIPPED:
        print(f"  - {label}: {why}")
print("=" * 72)
sys.exit(1 if RESULTS["fail"] else 0)
