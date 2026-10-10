"""打包完整性与全功能用例集。

用途：证明"开发态能跑"与"打包后能跑"是同一件事。用 packtest.spec 以与
excel2mysql.spec 完全相同的 excludes/hiddenimports 口径冻结成控制台 exe 后运行，
任何一项失败即退出码非 0。

覆盖：
  A 依赖与静态资源    B Excel 解析与类型推断    C 主键
  D DDL 生成          E SQL 脚本处理            F GUI 构造与交互逻辑
  H ZIP 压缩包导入    I .xls 老格式（xlrd）     J 添加结果弹窗
  K 高度可拖拽(两页)  L 字段类型映射            M 字段命名规则
  N 合并单元格拒绝    O 模态进度框与子线程执行
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


import atexit
atexit.register(_crash_report)


def section(title):
    print(f"\n----- {title} -----")


def main_source():
    """读 main.py 源码用于文案静态检查；冻结态不含 .py 时返回 None。"""
    p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "main.py")
    return open(p, encoding="utf-8").read() if os.path.isfile(p) else None


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

import main as app_main

# QMessageBox 是模态的，离屏测试里会把进程挂死；换成记录弹窗内容的桩
POPUPS = []


class _RecDialog:
    @staticmethod
    def information(parent, title, text, *a, **k):
        POPUPS.append((title, text))
        return None

    warning = critical = question = about = information


app_main.QMessageBox = _RecDialog
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
)
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
check("B20 全空列被剔除", "丙" not in [c["comment"] for c in t5["columns"]],
      [c["comment"] for c in t5["columns"]])
check("B21 全空行被剔除", len(t5["df"]) == 2, len(t5["df"]))

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

gbk = os.path.join(_tmpdir, "gbk.sql")
open(gbk, "wb").write("CREATE TABLE `t` (`id` INT); -- 中文注释".encode("gbk"))
check("B32 GBK 编码脚本可读取", "CREATE TABLE" in app_main.read_sql_text(gbk))
bom = os.path.join(_tmpdir, "bom.sql")
open(bom, "wb").write(b"\xef\xbb\xbf" + "SET NAMES utf8mb4;".encode("utf-8"))
check("B33 UTF-8 BOM 脚本可读取（BOM 不残留）",
      app_main.read_sql_text(bom).startswith("SET NAMES"))


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
check("F2 两个页签齐全", [w.main_tabs.tabText(i) for i in range(2)]
      == ["Excel 导入", "SQL 脚本"])
w.cb_db.blockSignals(True); w.cb_db.clear()
w.cb_db.addItem("testdb", "testdb")
w.cb_db.blockSignals(False)
w._refresh_db_labels()
check("F3 目标库提示条：Excel 页显示导入措辞",
      "testdb" in w.lbl_imp_db.text() and "Excel 数据将导入到这个库" in w.lbl_imp_db.text(),
      w.lbl_imp_db.text())
check("F4 目标库提示条：SQL 页显示执行措辞",
      "SQL 脚本将执行到这个库" in w.lbl_sql_db.text(), w.lbl_sql_db.text())
w.ed_port.setValue(3307)
check("F5 改端口后提示条实时联动", "3307" in w.lbl_imp_db.text(), w.lbl_imp_db.text())
w.cb_db.setCurrentIndex(-1)
check("F6 未选库时提示条回落为告警态", w.lbl_imp_db.text().startswith("⚠"))
check("F6b 未选库提示在两页上完全一致", w.lbl_imp_db.text() == w.lbl_sql_db.text())
check("F6c 提示不再把公共连接区说成某个页签",
      "Excel 导入」页" not in w.lbl_imp_db.text() and "SQL 脚本」页" not in w.lbl_imp_db.text()
      and "上方" in w.lbl_imp_db.text(), w.lbl_imp_db.text())
src = main_source()
if src is not None:
    check("F6d main.py 里不存在指向页签的连接指引", "「Excel 导入」页" not in src)
else:
    skip("F6d main.py 源码文案静态检查", "冻结态不含 .py 源码，开发态会跑")

w.tables = [dict(t1)]
dlg = app_main.StructureDialog(w.tables, w)
check("F7 表结构预览对话框可构造", dlg.tabs.count() == 1, f"tabs={dlg.tabs.count()}")
check("F8 预览页每行有主键勾选框",
      dlg.editors and dlg.editors[0][1].cellWidget(0, 0) is not None)
ddl_dlg = app_main.DDLDialog(w.tables, True, w)
check("F9 DDL 预览对话框可构造", ddl_dlg.tabs.count() == 1)
check("F10 拖拽区已启用拖放", w.drop.acceptDrops())

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
check("F13 数据库异常展开为错误码+原文",
      "MySQL 错误码 1064" in app_main.describe_db_error(
          pymysql.err.ProgrammingError(1064, "syntax error near 'x'")),
      app_main.describe_db_error(pymysql.err.ProgrammingError(1064, "syntax error near 'x'")))
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
tip = w3.file_table.item(0, 0).toolTip()
check("H12 文件列表 tooltip 标出来自哪个包的哪个内部路径",
      tip.startswith("one.zip!") and "水库基础信息.xlsx" in tip, tip)
check("H13 日志报告展开与忽略数量",
      "[压缩包] one.zip：展开 2 个 Excel" in w3.log.toPlainText()
      and "忽略 3 个非 Excel 文件" in w3.log.toPlainText()
      and "跳过 1 个嵌套 zip" in w3.log.toPlainText(),
      [l for l in w3.log.toPlainText().splitlines() if l.startswith("[压缩包]")])
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
drop = app_main.DropArea()
check("H17b 拖拽区文案写明支持三种格式（运行时取 QLabel 实际文本）",
      ".xlsx" in drop.label.text() and ".xls" in drop.label.text()
      and ".zip" in drop.label.text(), drop.label.text().replace("\n", " / "))
check("H18 IMPORT_EXTS 覆盖三种扩展名",
      app_main.IMPORT_EXTS == (".xlsx", ".xls", ".zip"), app_main.IMPORT_EXTS)

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
      [l for l in w.log.toPlainText().splitlines() if l.startswith("[重复跳过]")])
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
check("J13 弹窗那句话同时打进日志", f"[添加结果] {text}" in log_text,
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
# ---- SQL 脚本页：待处理文件表格与日志之间同样可拖拽分配高度 ----
from PySide6.QtWidgets import QTableWidget

w.main_tabs.setCurrentIndex(1)
_app.processEvents()
sp2 = w.sql_split
check("K10 SQL 页用垂直分隔条承载「文件表格区」与「日志区」，且不可折叠",
      isinstance(sp2, QSplitter) and sp2.orientation() == Qt.Vertical
      and sp2.count() == 2 and sp2.childrenCollapsible() is False
      and sp2.handleWidth() == 6, sp2.sizes())
check("K11 分隔条两侧正是待处理文件表与 SQL 日志",
      sp2.widget(0).findChild(QTableWidget) is w.sql_table and sp2.widget(1) is w.sql_log,
      [type(sp2.widget(i)).__name__ for i in range(2)])
sp2.setSizes([600, 100])
_app.processEvents()
u = [sp2.widget(i).height() for i in range(2)]
sp2.setSizes([100, 600])
_app.processEvents()
d = [sp2.widget(i).height() for i in range(2)]
check("K12 往下拖：表格区高于日志；反向拖：日志高于表格且表格确实被压缩",
      u[0] > u[1] and d[1] > d[0] and d[0] < u[0], f"{u} -> {d}")
check("K13 两边此消彼长，总和守恒", abs(sum(u) - sum(d)) <= 2, f"{sum(u)} vs {sum(d)}")
check("K14 前缀行与操作条跟着表格区一起缩放，不会被分隔条单独挤掉",
      sp2.widget(0).isAncestorOf(w.ed_sql_prefix)
      and sp2.widget(0).isAncestorOf(w.btn_sql_run)
      and sp2.widget(0).isAncestorOf(w.btn_sql_clear_log))
check("K15 提示条与添加/移除按钮是固定件，不在分隔条里被挤压",
      w.lbl_sql_db.parent() is not sp2 and w.btn_sql_add.parent() is not sp2
      and not sp2.isAncestorOf(w.btn_sql_add))
check("K16 表格由分隔条控制高度后仍拿得到视口",
      w.sql_table.viewport().height() > 0, w.sql_table.viewport().height())
w.sql_log.appendPlainText("分隔条缩放后日志仍可写入")
check("K17 缩放到最小后日志区仍能显示内容（不会拖成 0 高）",
      min(d) > 0 and "分隔条缩放后" in w.sql_log.toPlainText(), d)
w.main_tabs.setCurrentIndex(0)
_app.processEvents()
w.close()

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
      [l for l in w.log.toPlainText().splitlines() if l.startswith("[类型修正]")])

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

from core.infer import _col_to_field, _FIELD_MAX_LEN

check("M0 上限常量就是 30", _FIELD_MAX_LEN == 30, _FIELD_MAX_LEN)
check("M1 中文表头→蛇形全拼", _col_to_field("订单金额") == "ding_dan_jin_e",
      _col_to_field("订单金额"))
check("M1b 逐音节下划线分隔", _col_to_field("员工编号") == "yuan_gong_bian_hao",
      _col_to_field("员工编号"))
check("M2 短的用全拼不降级", _col_to_field("单价") == "dan_jia"
      and _col_to_field("数量") == "shu_liang")
check("M3 全拼在 30 字符内即使较长也保留全拼",
      _col_to_field("总库容（亿立方米）") == "zong_ku_rong_yi_li_fang_mi"
      and len(_col_to_field("总库容（亿立方米）")) <= 30, _col_to_field("总库容（亿立方米）"))
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
check("M13b 单位字母同样保留", mm[0].endswith("mmpjrsd"), mm[0])
check("M13c 降级后仍不超上限", all(len(x) <= 30 for x in mm), [len(x) for x in mm])

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
section("N 合并单元格检测与拒绝导入")

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
lg = w.log.toPlainText()
check("N8 日志逐条说明原因并给出合并区位置",
      lg.count("[合并单元格]") == 3 and "「字段定义」1 处（A1:G1）" in lg
      and "「数据」2 处（A1:B1、A2:B2）" in lg and "取消合并" in lg,
      [l for l in lg.splitlines() if l.startswith("[合并单元格]")])
merge_lines = [i for i in range(w.log.document().blockCount())
               if w.log.document().findBlockByNumber(i).text().startswith("[合并单元格]")]
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
        _FakeProgress.made.append(self)

    cancel_after = None        # 设成 N 就模拟"主循环第 N 拍时用户点了取消"

    def set_progress(self, done, total, label):
        self.updates.append((done, total, label))

    def finish(self):
        self.accepted = True

    def exec(self):
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
      len(ow.tables) == 1 and ow.file_table.item(0, 0).text() == "甲工程.xlsx",
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
      and ow2.file_table.item(0, 1).text().startswith("dim_"),
      [ow2.file_table.item(i, 1).text() for i in range(ow2.file_table.rowCount())])

# SQL 页：保存与改名也走同一套（读盘/写盘这种大 IO 不能在主线程）
sql_a = os.path.join(tempfile.mkdtemp(prefix="pt_osql_"), "a.sql")
open(sql_a, "w", encoding="utf-8").write(
    "CREATE TABLE `t_a` (`id` INT);\nINSERT INTO `t_a` VALUES (1);\n")
ow2.sql_files.append({"path": sql_a, "text": open(sql_a, encoding="utf-8").read(),
                      "modified": True, "rename_map": {}})
_FakeProgress.made.clear()
ow2.on_sql_save_modified()
d5 = _last_dialog()
check("O27 「保存修改后脚本」在子线程写盘并弹框",
      d5 is not None and "保存" in d5.title and os.path.isfile(
          os.path.join(os.path.dirname(sql_a), "t_a_prefix.sql").replace("t_a_prefix", "a_prefix")),
      [p for p in os.listdir(os.path.dirname(sql_a))])
check("O28 保存后 modified 复位", ow2.sql_files[-1]["modified"] is False)

_FakeProgress.made.clear()
ow2.ed_sql_prefix.setText("ods_")
ow2.on_sql_apply_prefix()
d6 = _last_dialog()
check("O29 SQL「批量应用前缀」逐文件报进度并改写文本",
      len(d6.updates) == 2 and "`ods_t_a`" in ow2.sql_files[-1]["text"], d6.updates)

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


_wd = QTimer()
_wd.setInterval(6000)
_wd.setSingleShot(True)
_wd.timeout.connect(_watchdog)
_wd.start()
rw._add_files([jx1, ozip])
rw._add_files([jx2])                      # 同一轮里起第二个任务，验证状态被正确复位
_wd.stop()
check("O36 真模态进度框：exec() 自己返回，没靠看门狗救场",
      watchdog_fired[0] is False and len(_close_shots) == 2,
      f"看门狗触发={watchdog_fired[0]} 关框次数={len(_close_shots)}")
shot = _close_shots[-1] if _close_shots else {}
check("O37 关框那一刻进度条正好落在 100%", shot.get("bar") == 100, shot)
check("O38 关框那一刻当前项文字停在最后一项", "乙工程" in (shot.get("label") or ""), shot)
check("O39 关框后框确实不可见（不会残留半个模态窗）", shot.get("visible") is False, shot)
check("O40 两轮任务的结果都落到界面", len(rw.tables) == 4,
      [t["table_name"] for t in rw.tables])
check("O41 任务结束后窗口回到空闲态，能再起任务", rw.task_dialog is None)

app_main.MODAL_PROGRESS = False
_FakeProgress.made.clear()

section("P 使用说明（? 按钮 / F1 / 排版 / 文档不漂移）")

from PySide6.QtGui import QKeyEvent
from PySide6.QtCore import QEvent
from help_doc import SECTIONS as HELP_SECTIONS
from ui_help import HelpDialog, build_html

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
_labels = ["添加文件", "批量应用前缀", "替换首个前缀", "生成表结构预览", "预览 DDL",
           "开始导入", "添加 SQL 文件", "保存修改后脚本", "执行脚本到当前库",
           "连接并加载库"]
_btns = {b.text() for b in pw2.findChildren(QPushButton)}
_absent = [t for t in _labels if t not in _btns]
check("P15 说明里点名的按钮在界面上确实存在", not _absent, _absent)
_doc_all = "".join(b for _a, _t, b in HELP_SECTIONS)
_missing_in_doc = [t for t in _labels if t not in _doc_all]
check("P16 每个按钮都被使用说明覆盖到", not _missing_in_doc, _missing_in_doc)
check("P17 进度框章节写清了「后台线程 + 可取消 + 逐项进度」",
      "进度框与耗时" in _doc_all and "模态进度框" in _doc_all
      and "当前这一项做完" in _doc_all)

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


def _fake_connect(cfg):
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


_orig_connect = app_main.connect
app_main.connect = _fake_connect

# ---- 连接并加载库：成功路径 ----
qw = app_main.MainWindow()
qw.on_load_databases()
_dbs = [qw.cb_db.itemText(i) for i in range(qw.cb_db.count())]
check("Q1 「连接并加载库」成功后下拉框填充并可用",
      qw.cb_db.isEnabled() and _dbs == ["alpha", "beta"], _dbs)
check("Q2 加载结果写进日志", "[已加载] 可用数据库" in qw.log.toPlainText(),
      qw.log.toPlainText().splitlines()[-1:])
check("Q3 选定库后提示条从告警态转为正常态",
      "当前目标库" in qw.lbl_imp_db.text() and "⚠" not in qw.lbl_imp_db.text(),
      qw.lbl_imp_db.text())
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

# ---- 清空列表 / 两页日志各清各的 ----
cw = app_main.MainWindow()
cw._add_files([jx1])
cw.log.appendPlainText("Excel 页占位")
cw.sql_log.appendPlainText("SQL 页占位")
cw.btn_clear.click()
cw.progress.setValue(66)
cw.btn_clear.click()
check("Q19 「清空列表」清掉数据与界面并禁用导入按钮",
      cw.tables == [] and cw.file_table.rowCount() == 0 and cw.btn_import.isEnabled() is False)
check("Q19b 清空列表时进度条一起归零，不留上次跑到一半的痕迹",
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
_orig_gofn = app_main.QFileDialog.getOpenFileNames
app_main.QFileDialog.getOpenFileNames = staticmethod(lambda *a, **k: ([qsql], "SQL 脚本 (*.sql)"))
try:
    sw2.on_sql_add_files()
    sw2.on_sql_add_files()
finally:
    app_main.QFileDialog.getOpenFileNames = staticmethod(_orig_gofn)
check("Q24 「添加 SQL 文件」加载进列表并逐表展示",
      len(sw2.sql_files) == 1 and sw2.sql_table.rowCount() == 2, sw2.sql_table.rowCount())
check("Q25 重复添加同一脚本被拦下并写明原因",
      "[跳过] 已在列表" in sw2.sql_log.toPlainText(),
      [l for l in sw2.sql_log.toPlainText().splitlines() if l.startswith("[跳过]")])
check("Q26 表名清单随加载缓存（刷新表格不必回扫几十万行）",
      len(sw2.sql_files[0].get("tabs") or []) == 2, sw2.sql_files[0].get("tabs"))
sw2.ed_sql_prefix.setText("ods_")
sw2.on_sql_apply_prefix()
_cur = [sw2.sql_table.item(i, 3).text() for i in range(sw2.sql_table.rowCount())]
_orig = [sw2.sql_table.item(i, 1).text() for i in range(sw2.sql_table.rowCount())]
check("Q27 加前缀后「当前表名」更新而「原表名」保持原样",
      _cur == ["ods_t_one", "ods_t_two"] and _orig == ["t_one", "t_two"], (_orig, _cur))
sw2.sql_table.selectRow(0)
sw2.on_sql_remove_selected()
check("Q28 「移除选中文件」按选中行删除并留痕",
      sw2.sql_files == [] and "[移除] 1 个脚本文件" in sw2.sql_log.toPlainText(),
      sw2.sql_files)

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
check("Q29 无文件时预览入口只提示，不构造空对话框",
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
rw2.task_dialog = _FakeProgress("占位")
res_box.clear()
rw2._run_modal("不该起任务", _slow_task, (3,), on_done=lambda x: res_box.append(x))
check("Q38 已有任务在跑时再起任务被忽略，不叠第二个框",
      len(_FakeProgress.made) == 1 and res_box == [], (len(_FakeProgress.made), res_box))
rw2.task_dialog = None
app_main.MODAL_PROGRESS = False

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

# ---- 结构性守卫：不能有"点了没反应"的死按钮 ----
_probe_win = app_main.MainWindow()      # 必须持有引用：临时对象被回收会让子按钮先析构
_dead = [b.text() or type(b).__name__
         for b in _probe_win.findChildren(QPushButton)
         if b.receivers("2clicked()") == 0]
check("Q42 主窗口每个按钮都连着槽函数（没有点了没反应的死按钮）", not _dead, _dead)

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
rw3 = app_main.MainWindow()
rw3.cb_db.addItem("alpha", "alpha")
rw3._add_files([f1, f2, f3])
rw3.ed_prefix.setText("change")
POPUPS.clear()
rw3.btn_replace_prefix.click()
_names = [rw3.file_table.item(i, 1).text() for i in range(rw3.file_table.rowCount())]
check("R20 点「替换首个前缀」后表格里的表名按首段替换",
      _names == ["change_t_order", "change_table2", "users"], _names)
check("R21 逐条回报旧→新，并单独说明没改的那张",
      "[换前缀] ods_t_order → change_t_order" in rw3.log.toPlainText()
      and "表名里没有下划线，未改动：users" in rw3.log.toPlainText(),
      [l for l in rw3.log.toPlainText().splitlines() if l.startswith("[换前缀]")])
check("R22 汇总写明改了几张、跳过几张",
      "共改 2 张表，跳过 1 张" in rw3.log.toPlainText(),
      [l for l in rw3.log.toPlainText().splitlines() if "换前缀完成" in l])
rw3.ed_prefix.setText("dim_")
rw3.on_apply_prefix()
check("R23 替换后的表名成为新基础名，再加前缀基于它算",
      rw3.file_table.item(0, 1).text() == "dim_change_t_order",
      [rw3.file_table.item(i, 1).text() for i in range(3)])

rw4 = app_main.MainWindow()
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

# ---- SQL 脚本页 UI ----
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
_orig = [sw3.sql_table.item(i, 1).text() for i in range(sw3.sql_table.rowCount())]
check("R27 SQL 页替换后「当前表名」更新、「原表名」仍是最初的名字",
      _cur == ["change_t_order", "change_table2", "users"]
      and _orig == ["ods_t_order", "adb_table2", "users"], (_orig, _cur))
check("R28 脚本正文被改写且标记为已修改",
      "`change_t_order`" in sw3.sql_files[0]["text"]
      and sw3.sql_files[0]["modified"] is True)
check("R29 日志逐条回报改写处数与跳过原因",
      "[换前缀] 换前缀样本.sql：ods_t_order → change_t_order（3 处）"
      in sw3.sql_log.toPlainText()
      and "users" in sw3.sql_log.toPlainText(),
      [l for l in sw3.sql_log.toPlainText().splitlines() if l.startswith("[换前缀]")])
sw3.btn_sql_save.click()
saved = os.path.join(_tmpdir, "换前缀样本_prefix.sql")
check("R30 保存后落盘的脚本用的是新表名",
      os.path.isfile(saved) and "`change_t_order`" in open(saved, encoding="utf-8").read(),
      saved)

# ---- 进度框：与「批量应用前缀」同一套机制 ----
# O 组末尾把 ProgressDialog 换成了真类（端到端取证用），这里换回替身才能记到刷新
app_main.ProgressDialog = _FakeProgress
app_main.MODAL_PROGRESS = True
_FakeProgress.made.clear()
pw3 = app_main.MainWindow()
pw3.cb_db.addItem("alpha", "alpha")
pw3._add_files([f1, f2, f3])
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
dlg2 = _last_dialog()
check("R32 SQL 页替换同样走模态进度框，按文件计项",
      dlg2 is not None and dlg2.accepted and dlg2.updates[-1] == (1, 1, "换前缀样本.sql"),
      dlg2.updates if dlg2 else None)
app_main.MODAL_PROGRESS = False

section("S 文件名括号内容判定（不允许汉字表名 / 纯数字表名）")

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
check("S8 括号内是 v2 这种字母开头的名字：合法标识符，直接采用",
      _PT("人员信息表（v2）.xlsx")[0] == "v2")
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

check("T1 版本号是 X.Y.Z 语义化格式，且只有一个来源",
      _re.fullmatch(r"[0-9]+[.][0-9]+[.][0-9]+", APP_VERSION) is not None, APP_VERSION)
check("T2 appinfo 报的版本与 version.py 一致", _ai.build_info()["version"] == APP_VERSION)
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
check("T6 关于框三行标签齐备（版本/提交/构建日期）",
      all(t in _labels for t in ("版本", "提交", "构建日期")), _labels[:5])
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
check("T8 提交号可鼠标选中复制（要贴给别人时不用手抄）",
      bool(ad.rows["commit"].textInteractionFlags()))
ad.deleteLater()
check("T8b 未跟踪文件不把构建标成 dirty（只看已跟踪文件的改动）",
      _i["dirty"] is False or bool(_run_git_probe()), _i["dirty"])
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

section("G MySQL 真实读写（建表→导入→查询→回滚）")

_cfg = {"host": os.environ.get("PT_HOST", "127.0.0.1"),
        "port": int(os.environ.get("PT_PORT", "3306")),
        "user": os.environ.get("PT_USER", ""),
        "password": os.environ.get("PT_PASS", ""),
        "database": os.environ.get("PT_DB", "")}
if not _cfg["user"] or not _cfg["database"]:
    skip("G1-G9 全部 MySQL 真实读写用例",
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
    except Exception as e:
        check("G0 连接 MySQL 失败", False, f"{type(e).__name__}: {e}")
        traceback.print_exc()


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
