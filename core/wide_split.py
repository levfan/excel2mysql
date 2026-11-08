# -*- coding: utf-8 -*-
"""宽表横向拆分：行上限救不回来时，按「公共字段 + 每段列」拆成多个 Excel。

为什么要有这个：129 节那套（`core.infer.fit_row_limits`）只会降长度、退 TEXT，
能救回一部分；救不回来的那三类——定长列把 InnoDB 半页堆满、需要走行外的列数
超过半页容量、列数超 1017——除了删列就只能拆表。以前 `refuse` 里只给一句
"建议拆成 N 段（每段多少列）"，人还得自己数着列去切，切完还容易把对照关系切丢。

拆的规矩是**公共字段每段都带**：从表头第一列起连续的那几列键列（编号/编码/名称/
日期这一类，外加全部主键列）在每一段里重复一份，于是每段都能自己认回同一行，
按公共字段 join 回原表就行。其余列按列序贪心装箱，每段（公共字段 + 本段列）
都必须自己过线——不是把原表平均切成 N 份就算完。

产物是 Excel 而不是 DDL：现场要的是"这几份重新拖进来就能建成几张表"。
表头写的是原表头中文（`comment`），所以重新导入时字段名还是原来那套拼音。
"""
import os

from .infer import row_plan

# 键列词：前四个是主键那一套（core.infer._PK_KEYWORDS 的超集），后面几个是宽表里
# 常见的前置维度列。命中即算公共字段的候选——判的是"这一列是不是用来认行的"，
# 不是"这一列是不是主键"。
_KEY_HINTS = ("编号", "序号", "代码", "编码", "标识", "卡号", "单号", "主键", "唯一",
              "名称", "日期", "时间", "年度", "年份", "月份", "单位", "类别")

# 日期时间型列天然是维度（哪一天的量），即使表头没写"日期"也算公共字段
_DATE_TYPES = ("DATE", "DATETIME", "TIMESTAMP", "TIME", "YEAR")

# 公共字段最多跟着重复几列：再宽就不叫"认行"了，纯属把度量列当键列
COMMON_MAX = 8

# 拆分产物的落点覆盖：None 表示写在原文件旁边（现场口径）。
# 自检与双端对撞把它指到临时目录——那些脚本喂进来的"用户文件"就是夹具目录，
# 按现场口径写会把夹具写花：下一轮、或另一条线，就会把 _partN.xlsx 当成夹具读进来。
OUT_DIR_OVERRIDE = None


def _base_type(typ):
    return str(typ or "").split("(")[0].strip().upper()


def is_key_column(col):
    """这一列像不像"用来认行的键列"：主键、词命中、或日期时间型。"""
    if col.get("primary_key"):
        return True
    text = "%s %s" % (col.get("comment") or "", col.get("field") or "")
    if any(k in text for k in _KEY_HINTS):
        return True
    return _base_type(col.get("type")) in _DATE_TYPES


def common_columns(columns):
    """认出表头最前面的公共字段，返回 (列序号列表, 一句说明)。

    第 0 列始终当锚点并计入公共字段（它命不命中键列词都一样——总得有个能对上行的列）；
    从第 1 列起继续取连续键列，遇到第一个不像键列的就停（"前面的公共字段"就是这个
    意思）。主键列一个都不许落在公共字段之外，落在后面的补进来。
    """
    if not columns:
        return [], "没有字段"
    first_is_key = is_key_column(columns[0])
    picked = [0]
    contig = 1
    for i in range(1, len(columns)):
        if contig >= COMMON_MAX:
            break
        if not is_key_column(columns[i]):
            break
        picked.append(i)
        contig += 1
    for i, col in enumerate(columns):
        if col.get("primary_key") and i not in picked:
            picked.append(i)
    picked.sort()
    if first_is_key:
        note = "从第 1 列起连续 %d 列是键列" % contig
    elif contig > 1:
        note = "第 1 列当锚点，后接 %d 列键列" % (contig - 1)
    else:
        note = "没认出键列，用第一列当锚点"
    if len(picked) > contig:
        note += "，另补 %d 列主键" % (len(picked) - contig)
    if contig >= COMMON_MAX:
        note += "（到 %d 列封顶）" % COMMON_MAX
    return picked, note


def _pack(columns, common, page_size):
    """按列序贪心装箱：每箱 = 公共字段 + 一段连续列，且必须自己过线。"""
    rests = [i for i in range(len(columns)) if i not in set(common)]
    segs, cur = [], []
    for i in rests:
        trial = cur + [i]
        if row_plan([columns[j] for j in common + trial], page_size)["ok"] or not cur:
            cur = trial
            continue
        segs.append(cur)
        cur = [i]
    if cur:
        segs.append(cur)
    return segs


def plan_split(columns, page_size=16384):
    """给出拆分方案：拆哪几段、每段带哪些列。

    返回 {"ok": bool, "common": [...], "note": str, "parts": [{idx, own}], "reason": str}。
    idx 是这一段实际写出去的列序号（公共字段在前），own 是本段独有的列序号。
    """
    if not columns:
        return {"ok": False, "common": [], "note": "", "parts": [], "reason": "没有字段"}
    common, note = common_columns(columns)
    rest = [i for i in range(len(columns)) if i not in set(common)]
    if not rest:
        return {"ok": False, "common": common, "note": note, "parts": [],
                "reason": "整张表都是键列，没有可拆出去的度量列"}
    segs = _pack(columns, common, page_size)
    for s in segs:
        plan = row_plan([columns[j] for j in common + s], page_size)
        if not plan["ok"]:
            # 公共字段 + 单独一列都过不了线：这时候拆多段也没用，得先减公共字段或删列
            return {"ok": False, "common": common, "note": note, "parts": [],
                    "reason": ("公共字段 %d 列 + 单列仍超过行上限（行内 %d 字节 > %d）"
                               % (len(common), plan["local"], plan["local_limit"]))}
    if len(segs) < 2:
        return {"ok": False, "common": common, "note": note, "parts": [],
                "reason": "按行上限装得下整张表，不需要拆"}
    parts = [{"idx": common + s, "own": s} for s in segs]
    return {"ok": True, "common": common, "note": note, "parts": parts, "reason": ""}


def _header_text(col, used):
    """写回 Excel 的表头：原样用中文表头，缺了才退回字段名；重名的加序号。"""
    text = str(col.get("comment") or "").strip() or str(col.get("field") or "").strip()
    if not text:
        text = "col"
    base = text
    n = 2
    while text in used:
        text = "%s_%d" % (base, n)
        n += 1
    used.add(text)
    return text


def _unique_path(folder, filename):
    """不覆盖已有文件：同名就往后编号。"""
    path = os.path.join(folder, filename)
    if not os.path.exists(path):
        return path
    stem, ext = os.path.splitext(filename)
    n = 2
    while True:
        path = os.path.join(folder, "%s_%d%s" % (stem, n, ext))
        if not os.path.exists(path):
            return path
        n += 1


def _shadow_columns(td):
    """按「降级前」的类型算行字节：拆出来的 Excel 重新导入时，列会按数据重新推断成原来的
    类型，拿降级后的 TEXT 去装箱会装多——回读时又超线，又触发一次降级，一整段的列全变
    TEXT。装箱按原类型来，拆出来的每一份才会原样建得出来。
    """
    columns = td.get("columns") or []
    original = {}
    for conv in ((td.get("row_fit") or {}).get("conversions") or []):
        field = conv.get("field")
        if field and field not in original:
            original[field] = conv.get("from")
    if not original:
        return columns
    shadow = []
    for col in columns:
        frm = original.get(col.get("field"))
        if frm:
            copy = dict(col)
            copy["type"] = frm
            shadow.append(copy)
        else:
            shadow.append(col)
    return shadow


def split_wide_table(td, out_dir=None, stem=None, page_size=16384):
    """把一张过宽的普通表拆成若干 Excel，写到原文件旁边（或 out_dir）。

    返回 {"ok", "files": [{file, columns, own, rows, local}], "common", "note",
          "rows", "reason"}。失败只给 reason，不抛异常——拆分是"顺手救一把"，
    它失败不该把整份导入流程带崩，原来的拦下结论照旧。
    """
    columns = td.get("columns") or []
    df = td.get("df")
    shadow = _shadow_columns(td)
    plan = plan_split(shadow, page_size)
    if not plan["ok"]:
        return dict(plan, files=[], rows=0)
    if df is None:
        return dict(plan, ok=False, files=[], rows=0, reason="没有读到数据，拆不出 Excel")
    src = td.get("filepath") or ""
    folder = (out_dir or OUT_DIR_OVERRIDE
              or os.path.dirname(os.path.abspath(src)) or os.getcwd())
    if not stem:
        stem = os.path.splitext(os.path.basename(src))[0] or "table"
    files = []
    for n, part in enumerate(plan["parts"], 1):
        idx = part["idx"]
        used = set()
        headers = [_header_text(columns[j], used) for j in idx]
        data = df.iloc[:, idx].copy()
        data.columns = headers
        path = _unique_path(folder, "%s_part%d.xlsx" % (stem, n))
        data.to_excel(path, index=False)
        files.append({"file": path, "columns": len(idx), "own": len(part["own"]),
                      "rows": len(data),
                      "local": row_plan([shadow[j] for j in idx], page_size)["local"]})
    return {"ok": True, "common": plan["common"], "note": plan["note"],
            "parts": plan["parts"], "files": files, "rows": len(df), "reason": ""}


def notice_lines(td, result):
    """把拆分结果写成现场照抄的几行日志（图形版两页共用这段文案）。"""
    name = td.get("table_name") or ""
    if not result.get("ok"):
        if result.get("reason"):
            return ["[自动拆分] 表 `%s` 拆不动：%s" % (name, result["reason"])]
        return []
    columns = td.get("columns") or []
    common = result.get("common") or []
    heads = []
    for i in common:
        col = columns[i]
        heads.append(str(col.get("comment") or col.get("field") or "").strip())
    lines = ["[自动拆分] 表 `%s` 行内装不下，已按公共字段 `%s` 拆成 %d 个 Excel"
             "（每段都带这 %d 列，数据 %d 行一起带）："
             % (name, "、".join(heads), len(result["files"]), len(common),
                result.get("rows", 0))]
    for i, f in enumerate(result["files"], 1):
        lines.append("    第 %d 段：本段 %d 列 + 公共 %d 列 = %d 列，行内约 %d 字节 → %s"
                     % (i, f["own"], len(common), f["columns"], f["local"],
                        os.path.basename(f["file"])))
    lines.append("    把这几份拖回来就是 %d 张独立的表，按公共字段能 join 回原表；"
                 "文件放在原文件（压缩包里出来的放压缩包）旁边，同名不覆盖（后一份加 _2）"
                 % len(result["files"]))
    return lines
