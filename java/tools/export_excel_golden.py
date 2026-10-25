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
import re
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


def make_star_form(path):
    """采集表单：表头带必填星号，且有一列声明了但整列没填。

    压四条规则：`*标准名称` 有值（星号只是标记，转写时去掉、注释里留原文）；
    `*填报人` 整列没值但**表头写了字**，必须保留并进 empty_columns；
    表头空着且整列没值的那列是 Excel 尾部格式化拖出来的，丢掉；
    整列空字符串的列不算空列（照旧保留，但不进 empty_columns）。
    """
    wb = Workbook()
    ws = wb.active
    ws.title = "数据"
    ws.append([u"*标准名称", u"*填报人", u"备注", None, u"占位"])
    ws.append([u"GB/T 1234", None, u"甲", None, u""])
    ws.append([u"DB11/567", None, None, None, u""])
    wb.save(path)


def make_star_template(path):
    """只有表头、一行数据都没有的空模板：以前会被判"没解析到字段"而整体拒收。"""
    wb = Workbook()
    ws = wb.active
    ws.title = "数据"
    ws.append([u"*标准名称", u"*标准级别", u"发布日期", u"＊填报单位"])
    wb.save(path)


# 空列按字段名定类型的词表：每个类别都放代表词，另放复合表头与"钱味词根但存名字"
# 的否决样本。这张表没有数据行，两侧的类型全部来自词表，所以它是词表的跨线逐列对撞面
NAME_KIND_HEADERS = [
    u"总计", u"小计", u"合计", u"共计", u"累计", u"汇总", u"总量", u"总值",
    u"订单金额", u"交易额", u"成交价", u"租金", u"水电费", u"增值税", u"净利润",
    u"总资产", u"应收款", u"保证金", u"投资总额", u"分红", u"工资", u"销售额",
    u"资产名称", u"付款单位", u"缴费方式", u"工资名单", u"预算说明",
    u"工期", u"周期", u"付款周期", u"结算周期", u"时长", u"间隔",
    u"打卡时刻", u"发车时点", u"发布日期", u"更新时间", u"日期时间",
    u"是否有效", u"有无附件", u"标识", u"开关",
    u"系数", u"毛利率", u"合格率", u"容积率", u"概率", u"占比", u"权重",
    u"满意度", u"星级", u"平均得分", u"绩点",
    u"经度", u"纬度", u"坐标", u"高程",
    u"长度", u"强度", u"硬度", u"能耗", u"吨位", u"跨度", u"坡度", u"粒径",
    u"负荷", u"含沙量", u"扬程", u"温度", u"频率", u"功率", u"速率",
    u"数量", u"票数", u"名额", u"课时", u"学分", u"场次", u"访问量", u"下载量",
    u"销量", u"产量", u"存量", u"粉丝数", u"订单数", u"企业数", u"库存",
    u"年龄", u"工龄", u"年限",
    u"序号", u"排名", u"优先级", u"页码",
    u"年份", u"月份", u"季度", u"天数",
    u"官网网址", u"附件地址", u"文件路径", u"域名", u"下载链接",
    u"备注说明", u"详细描述", u"经营范围", u"注意事项", u"案情", u"问题",
    u"项目名称", u"联系人", u"所属部门", u"规格型号", u"详细地址", u"工艺",
    u"数量说明", u"金额区间", u"编号", u"身份证", u"电话",
    u"total_amount", u"sub_total", u"gross_profit", u"rent_fee", u"project_name",
    u"home_url", u"file_path", u"remark_text", u"user_count", u"avg_score",
    u"is_active", u"duration_seconds", u"created_date", u"update", u"birthday",
    u"phone_number", u"order_status", u"material_code",
    # 第二轮扩充：大计数、计量与计数的新词、金额新词、文本三档新词、英文新词根
    u"播放量", u"阅读量", u"曝光量", u"浏览量", u"订阅数", u"分享量", u"投票数",
    u"人次", u"班次", u"车次", u"趟次", u"轮次", u"根数", u"块数", u"栋数",
    u"行数", u"列数", u"站点数", u"接口数", u"遍数",
    u"壁厚", u"净高", u"埋深", u"振幅", u"挠度", u"沉降", u"位移", u"噪声",
    u"分贝", u"总磷", u"开间", u"进深", u"含水",
    u"违约金", u"本金", u"借款", u"月供", u"尾款", u"造价", u"概算", u"水费",
    u"装卸费", u"措施费", u"规费", u"价差", u"退税", u"客单价", u"收费标准",
    u"位次", u"座次", u"顺位", u"时延", u"可否", u"能否", u"配比", u"扣分",
    u"月数", u"上旬", u"短链", u"留言", u"条款", u"笔录", u"诉求", u"称谓",
    u"职称", u"岗位", u"科室", u"楼盘", u"街道", u"线路", u"品名", u"语种",
    u"page_views", u"impressions", u"shares", u"principal", u"premium",
    u"latency_ms", u"alias_name", u"ordinal", u"message_body", u"shares_held",
    # 第三轮：部队用户的领域口径（勤务保障、兵力装备台账、数据中台元数据）
    u"伙食费", u"油料费", u"维修费", u"值班费", u"过路费", u"取暖费", u"被装费",
    u"出库数", u"入库数", u"在库数", u"在册数", u"在位数", u"配发数", u"消耗数",
    u"报废数", u"维修数", u"领用数", u"盘盈数", u"官兵数", u"兵员数", u"装备数",
    u"台套数", u"辆数", u"参与数",
    u"工时", u"机时", u"台时", u"工时费",
    u"考核分", u"理论分", u"实操分", u"体能分", u"综合分", u"总评",
    u"航程", u"航速", u"转速", u"亩产", u"在位率", u"完好率", u"参考率",
    u"创建人", u"审核人", u"负责人", u"密级", u"级别", u"建制", u"番号", u"部队",
    u"owner",
    u"处置情况", u"主要做法", u"工作纪要", u"整改期限", u"统计口径", u"装备名称",
    u"维修费合计", u"出库数量",
    # 第四轮：卫勤健康、训练考核、装备车炮、中台治理，加两条规则缺口的样本
    u"心率", u"脉搏", u"剂次", u"环数", u"圈数", u"血糖", u"视力", u"剂量",
    u"用药量", u"收缩压", u"舒张压", u"血型", u"过敏史", u"体检结论",
    u"课目", u"驾驶员", u"附件", u"状态", u"标记", u"记录状态", u"校验标记",
    u"药品批号", u"装备附件", u"射击环数", u"接种剂次",
    u"有效期", u"到期日", u"证件有效期", u"整改期限", u"完成时限", u"保密期限",
]


def make_name_kinds(path):
    """整列无值的词表夹具：类型全靠字段名定，两栈必须逐列给同一个类型词。"""
    wb = Workbook()
    ws = wb.active
    ws.title = "数据"
    ws.append(NAME_KIND_HEADERS)
    wb.save(path)


def all_word_headers():
    """把 Python 词表里的每一条词都变成一列，排成第二张夹具。

    为什么不靠人肉挑样本：词表现在有近千条，人工样本永远只覆盖点到的那几个，
    「两栈逐条同序」就成了口头承诺。全量词条进夹具后，每一条都必须在 Java 线
    给出同一个类型词，少一条词、错一个顺序、Java 漏抄一条都会在这里露出来。
    """
    import core.infer as infer

    words = []
    for name in sorted(dir(infer)):
        if name == "_MONEY_CN" or (name.startswith("_NAME_") and name.endswith("_CN")):
            value = getattr(infer, name)
            words.extend([value] if isinstance(value, str) else list(value))
    for name in sorted(dir(infer)):
        if name in ("_MONEY_EN", "_RATE_EN") or (
                name.startswith("_NAME_") and name.endswith("_EN")):
            for hit in re.finditer(r"\?:([a-z_|]+)\)", getattr(infer, name).pattern):
                words.extend(hit.group(1).split("|"))
    seen, out = set(), []
    for w in words:
        w = str(w).strip()
        if w and w not in seen:
            seen.add(w)
            out.append(w)
    return out


def make_name_words(path):
    """词表全量夹具：一条词都不漏，代价是这张表有一千来列。"""
    wb = Workbook()
    ws = wb.active
    ws.title = "数据"
    ws.append(all_word_headers())
    wb.save(path)


def make_head_note(path):
    """表头带注释：成对括号、括号没配全、只挂日期格式串这三类写法都在一张表里。

    压的规则：注释整段不进字段名（fa_bu_ri_qi 而不是 fa_bu_ri_qi_yyyy_mm_dd）；
    没配右半边的左括号从它起整段丢；`：`/空格/`-`/`/` 挂着的 yyyy-MM-dd、yyyymmdd
    这类日期时间掩码串不算名字的一部分；剥完同名靠 _2/_3 去重而不是合并成一列；
    列注释仍是原始表头，口径信息一个不丢。
    反向也要盯住：`1.0mm` 里的 mm 与纯英文表头里的 MM 都不许当掩码剥掉。
    """
    wb = Workbook()
    ws = wb.active
    ws.title = "数据"
    ws.append([u"标准名称（必填）", u"发布日期（yyyy-MM-dd）", u"发布日期",
               u"金额（元）", u"是否已婚【是/否】", u"平均日数(d)", u"（备注说明）",
               u"发布日期：yyyy-MM-dd", u"填表日期 yyyymmdd", u"发布日期(YYYYMMDD",
               u"登记日期〖yyyy/MM/dd〗", u"累年月日降水量≥1.0mm平均日数"])
    ws.append([u"SL 1", u"2008-05-19", u"2008-05-20", u"12.50", u"是", u"3", u"x",
               u"2008-05-21", u"20080522", u"20080523", u"2008-05-24", u"5"])
    ws.append([u"SL 2", u"2009-01-02", u"2009-01-03", u"8", u"否", u"12", u"y",
               u"2009-01-04", u"20090105", u"20090106", u"2009-01-07", u"15"])
    wb.save(path)


def make_merged_header(path):
    """两行合并表头：原始表按「父-子」压成一行，与第二张参考表的扁平表头逐字一致。

    压四条规则：纵向合并（A1:A2）只留一份标签，不能展开成「序号-序号」；
    横向合并（J1:M1）先把组名铺到组内每列，再与第二行的子标签用「-」连接；
    表头块之外的合并区一个都不许有，否则照旧按拒绝记账；
    带数据行，保证展开后的列顺序、取值、类型、主键与参考表完全对得上。
    """
    wb = Workbook()
    ws = wb.active
    ws.title = u"原始表"
    top = [u"序号", u"单位名称", u"坐落号", u"省/直辖市", u"市/直辖市区", u"区/县",
           u"是否主城区", u"区位类型", u"总计", u"技术公司", None, None, None,
           u"财务公司", None, None, None]
    second = [None] * 9 + [u"小计", u"高层", u"中层", u"普通员工",
                          u"小计", u"高层", u"中层", u"普通员工"]
    ws.append(top)
    ws.append(second)
    for box in ("A1:A2", "B1:B2", "C1:C2", "D1:D2", "E1:E2", "F1:F2", "G1:G2",
                "H1:H2", "I1:I2", "J1:M1", "N1:Q1"):
        ws.merge_cells(box)
    rows = [
        [1, u"市水务局", u"001", u"北京市", u"北京市", u"海淀区", u"是", u"主城区",
         100, 60, 20, 20, 20, 40, 10, 10, 20],
        [2, u"区水务站", u"002", u"北京市", u"北京市", u"朝阳区", u"否", u"副中心",
         80, 45, 15, 15, 15, 35, 10, 10, 15],
        [3, u"水库管理处", u"003", u"河北省", u"石家庄市", u"长安区", u"是", u"一般",
         50, 30, 10, 10, 10, 20, 5, 5, 10],
    ]
    for r in rows:
        ws.append(r)
    ref = wb.create_sheet(u"结构化参考")
    ref.append([u"序号", u"单位名称", u"坐落号", u"省/直辖市", u"市/直辖市区", u"区/县",
                u"是否主城区", u"区位类型", u"总计", u"技术公司-小计", u"技术公司-高层",
                u"技术公司-中层", u"技术公司-普通员工", u"财务公司-小计",
                u"财务公司-高层", u"财务公司-中层", u"财务公司-普通员工"])
    for r in rows:
        ref.append(r)
    wb.save(path)


FIXTURES = [
    (u"水库基础信息（dim_reservoir_info）.xlsx", make_basic),
    (u"大表压精度（ods_t_big）.xlsx", make_overflow),
    (u"结构定义订单（ods_t_order_meta）.xlsx", make_meta),
    (u"必填表头（ods_t_star_form）.xlsx", make_star_form),
    (u"空采集模板（ods_t_star_tpl）.xlsx", make_star_template),
    (u"空列词表（ods_t_name_kinds）.xlsx", make_name_kinds),
    (u"空列词表全量（ods_t_name_words）.xlsx", make_name_words),
    (u"表头带注释（ods_t_head_note）.xlsx", make_head_note),
    (u"两行合并表头（ods_t_merged_header）.xlsx", make_merged_header),
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
    # 推断层的输入不该是"pandas header=0 看到的第一行"：两行合并表头会被压成一行，
    # 原始 grid 里第二行还是数据。TypeDiffer 只撞推断规则，所以另存这份展开后的有效网格；
    # ReaderDiffer 仍用上面的原始 grid 撞读取层，两者分工不变。
    table_grid = None
    if tdef.get("source") == "normal" and tdef.get("df") is not None:
        eff = tdef["df"]
        table_grid = {
            "headers": [encode(c) for c in eff.columns],
            "rows": [[encode(v) for v in row]
                     for row in eff.itertuples(index=False, name=None)],
        }
    return {
        "file": os.path.basename(path),
        "sha256": sha256(path),
        "grid": grid,
        "table_grid": table_grid,
        "def": {
            "table_name": tdef["table_name"],
            "base_table_name": tdef["base_table_name"],
            "table_comment": tdef["table_comment"],
            "columns": cols,
            "primary_key": tdef["primary_key"],
            "pk_reason": tdef["pk_reason"],
            "source": tdef["source"],
            # 具名空列清单与"一行数据都没有"这两件事也得跨线对：
            # 它们决定日志里那句点名和"建出来是空表"的判定
            "empty_columns": tdef.get("empty_columns") or [],
            "no_data_rows": bool(tdef.get("no_data_rows")),
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
