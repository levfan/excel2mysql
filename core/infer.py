"""读取 Excel 并推断表结构：表名取括号内英文或由中文转拼音，表注释=文件名（或括号前中文），字段注释=中文表头。"""
import os
import re
from datetime import datetime, date
from decimal import Decimal, InvalidOperation
# PEP 604 的 `dict | None` 在 Python 3.8 导入时求值会 TypeError，Win7 线用 typing 写法
from typing import Optional

import pandas as pd
from pypinyin import lazy_pinyin

from .merge_check import MergedCellsError, ensure_no_merged_cells
from .naming import clean_prefix, swap_first_segment


# 文件名末尾「（表名）」模式：中英文括号均可，如 北京市水务局-水库基础信息（dim_reservoir_info）
_PAREN_TABLE_RE = re.compile(r"[（(]\s*([^（）()]*)\s*[）)]\s*$")

# 括号内容要形如合法 MySQL 英文标识符（以字母或下划线开头，只含字母/数字/下划线）；
# 另外还要求至少含一个字母 —— 纯数字括号是序号不是表名，见 parse_table_name_and_comment
_TABLE_EN_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")

# VARCHAR 长度算法：常用档位 + 全表字节预算
# utf8mb4 下 VARCHAR(n) 计入 MySQL 65535 字节行上限按 4n 字节算（n 为字符数），
# 全表 VARCHAR 总量不加控制会导致 CREATE TABLE 报 Row size too large
_VARCHAR_TIERS = (64, 96, 128, 192, 255, 384, 512, 768, 1024, 1536, 2048,
                  3072, 4096, 6144, 8192, 12288, 16383)
_VARCHAR_MAX_CHARS = 16383      # utf8mb4 VARCHAR 最大字符数（4 × 16383 ≈ 64KB）
_ROW_BYTE_BUDGET = 60000        # 全表 VARCHAR 行内字节预算（留出余量防行过大）
_COL_ROW_COST = 16              # 单个非 VARCHAR 列的行内开销估算（字节）
_FIELD_MAX_LEN = 30             # 字段名最长 30 字符；全拼超此则整列降级为首字母
_TABLE_MAX_LEN = 64             # 表名最长 64 字符（MySQL 标识符上限）；全拼超此同样降级为首字母

# ---------------- 金额语义识别 ----------------
# 纯值判定会把"订单金额"这种列在样本恰好为整数时判成 INT，金额被砍掉小数位；
# 所以在值判定之上再加一层表头语义修正：命中金额语义的数值列一律用 DECIMAL。
# 只收成词的关键词，刻意不含裸「费」「价」二字——避免"费县"(地名)、“评价”误判。
_MONEY_CN = (
    "金额", "总额", "总价", "单价", "均价", "价格", "价目", "报价", "成交价", "结算价",
    "基准价", "中标价", "投标价", "采购价", "批发价", "零售价", "售价", "进价",
    "运价", "电价", "水价", "热价", "气价", "油价", "票价", "话费", "房价", "地价",
    "费用", "费额", "成本", "余额", "余款", "工资", "薪金", "薪资", "薪酬", "补贴",
    "津贴", "奖金", "收入", "支出", "开销", "付款", "收款", "支付", "缴费", "付费",
    "预算", "决算", "清算", "结算", "税额", "税金", "税费", "利息", "保费",
    "股金", "市值", "估值", "净产值", "总产值", "销售额", "营业额", "零售额", "进口额",
    "出口额", "利润", "亏损", "毛利", "净利", "欠款", "贷款", "存款", "融资", "投资额",
    "资金", "额度", "补偿", "赔偿", "保证金", "押金", "定金", "预付款", "进度款",
    "工程款", "材料费", "人工费", "运输费", "服务费", "咨询费", "管理费", "仓储费",
)
_MONEY_EN = re.compile(
    r"(?:^|_)(?:amount|amt|money|price|cost|fee|fees|salary|wage|wages|income|"
    r"expense|expenses|balance|budget|revenue|profit|payment|payable|receivable|"
    r"charge|charges|fund|funds|tax|interest|capital)(?:$|_)",
    re.IGNORECASE,
)
# 比率类列（利率/税率/百分比/占比）语义上含"率"或 rate，钱味关键词会误命中，
# 一律否决：它们该按实测小数位定精度，而不是套金额的两位小数。
_RATE_CN = ("率", "比例", "百分比", "占比", "增速", "幅度")
_RATE_EN = re.compile(r"(?:rate|ratio|percent|percentage|pct|share)", re.IGNORECASE)
_MONEY_SCALE = 2                # 金额固定保留 2 位小数（精确到分）
_MONEY_PRECISION = 16           # 金额默认 DECIMAL(16,2)


def _is_money(comment: str, field: str) -> bool:
    """表头中文或字段名英文命中金额语义关键词；比率类列一律否决。"""
    c = str(comment or "")
    fl = str(field or "")
    if any(k in c for k in _RATE_CN) or _RATE_EN.search(c) or _RATE_EN.search(fl):
        return False
    if any(k in c for k in _MONEY_CN):
        return True
    return bool(_MONEY_EN.search(fl))


def _num_shape(v):
    """取数值的 (整数位数, 小数位数)；非数值返回 None。

    用 Decimal 而非字符串切分：Excel 大数会以 1E+25 这类科学计数法存进单元格，
    按字面切分把整数位算成 5 位，会给出装不下的 DECIMAL 宽度，写入直接溢出。

    用于按实际数据校核 DECIMAL 精度：绝不允许把观测到的位数压小，
    否则经纬度这类 7 位小数的列会在写入时被静默截断。
    """
    if v is None or isinstance(v, bool):
        return None
    if isinstance(v, float):
        s = repr(v)
    elif isinstance(v, int):
        s = str(v)
    elif isinstance(v, str):
        s = v.strip().replace(",", "").replace("，", "")
    else:
        return None
    if s == "":
        return None
    try:
        d = Decimal(s)
    except (InvalidOperation, ValueError):
        return None
    if not d.is_finite():
        return None
    _, digits, exp = d.as_tuple()
    n = len(digits)
    if exp >= 0:
        return (max(n + exp, 1), 0)
    return (max(n + exp, 1), -exp)


def _decimal_type(int_digits: int, scale: int, floor_int: int,
                  money: bool) -> str:
    """按观测数据定 DECIMAL 精度，必要时放宽，绝不截断。

    金额列默认 DECIMAL(16,2)；非金额列整数位留 2 位增长余量、最少 4 位。
    MySQL DECIMAL 总精度上限 65，这里再压到 30 以避开异常数据造出的荒谬宽度。
    """
    if money:
        scale = max(scale, _MONEY_SCALE)
        int_digits = max(int_digits, _MONEY_PRECISION - _MONEY_SCALE)
    else:
        int_digits = max(int_digits + 2, floor_int)
    int_digits = max(int_digits, 1)
    scale = max(scale, 0)
    precision = min(int_digits + scale, 30)
    int_digits = precision - scale
    return f"DECIMAL({precision},{scale})"


def _varchar_tier(needed: int) -> int:
    """把需求字节数向上取整到常用长度档位（64 ~ 16383 字符）。"""
    for t in _VARCHAR_TIERS:
        if t >= needed:
            return t
    return _VARCHAR_MAX_CHARS


def parse_table_name_and_comment(filename: str) -> tuple:
    """
    从文件名解析 (表名, 表注释)。

    规则：文件名（去扩展名）以「（英文表名）」结尾时，例如
        北京市水务局-水库基础信息（dim_reservoir_info）.xlsx
    则 表名   = 括号内的英文  dim_reservoir_info
       表注释 = 括号前的中文  北京市水务局-水库基础信息
    中英文括号均可；括号内容必须是**可直接当表名用的英文标识符**（以字母或下划线开头、
    只含字母/数字/下划线、至少含一个字母）才采用，并统一转小写。
    以下情况一律回退到"由完整文件名汉字转拼音"：括号内是中文（人事处人员）、
    纯数字序号（7）（2026）、空或纯符号 —— 因此不会产生汉字表名，
    也不会产生只含数字的表名。
    回退时表注释保留完整文件名（含括号内容），表名取括号部分转拼音后保留，
    这样 人员信息表（人事处）与 人员信息表（财务部） 得到两个不同且可辨识的表名。
    文本无有效字符时用兜底表名 t_import_data。
    """
    stem = os.path.splitext(os.path.basename(filename))[0]
    m = _PAREN_TABLE_RE.search(stem)
    if m:
        raw = m.group(1).strip()
        base = stem[:m.start()].strip() or stem
        # 只有"能直接当表名用的英文标识符"才取括号内容：必须以字母或下划线开头
        # 且含字母。（7）（2026）这种纯序号不是表名，中文更不是
        if _TABLE_EN_RE.fullmatch(raw) and any(c.isascii() and c.isalpha() for c in raw):
            return raw.lower()[:64], base
        # 回退时表名与注释都用**完整文件名**（含括号内容）：
        # 只取括号前的话，人员信息表（人事处）与人员信息表（财务部）会塌成同名加 _2，
        # 谁也看不出是哪一份；括号里的汉字转拼音进表名，数字序号也保留做区分
        return sanitize_table_name(stem), stem
    return sanitize_table_name(stem), stem


def _cn_to_table_name(text: str, limit: int = _TABLE_MAX_LEN) -> str:
    """
    文本 -> 拼音表名组件：小写字母/数字/下划线，符合 MySQL 表名规范。
    各类分隔符（连字符、空格、斜杠、括号等）统一转下划线；连续英文/数字原样保留；
    汉字逐音节转拼音并用下划线分隔（如 水库基础信息 -> shui_ku_ji_chu_xin_xi）。
    超长处理与字段名同一套规则：全拼不超过 limit 用全拼，超过则整名降级为各音节
    首字母（如 北京市海淀区水务局下属大型水库枢纽工程基础信息汇总表 -> bjsshdqxsdkscsgjcxxhzbb），
    这样每个汉字都还在名字里，比"砍掉后半段"更能区分不同文件；
    数字/英文音节整段保留，数字开头自动补 t_ 前缀。无法产出有效字符时返回空串。
    """
    text = str(text).strip()
    if not text:
        return ""
    syllables = _syllables(text)
    if not syllables:
        return ""
    return _fit_name(syllables, limit, "t_")


def sanitize_table_name(text: str) -> str:
    """
    文本 -> 默认表名：汉字转拼音（音节间下划线分隔），符合 MySQL 命名规范
    （仅小写字母/数字/下划线，不以数字开头，最长 64 字符）。
    """
    name = _cn_to_table_name(text, limit=64)
    if not name:
        name = "t_import_data"
    return name


def _syllables(text):
    """把任意文本切成 (音节, 是否来自汉字) 序列：汉字逐字一个音节，英文/数字整段保留。

    字段名与表名共用这一套切分，保证同一个中文在两处的转写结果一致。
    """
    out = []
    norm = re.sub(r"[^\w]+", "_", str(text).strip(), flags=re.UNICODE)
    for seg in re.findall(r"[A-Za-z0-9_]+|[\u4e00-\u9fff]+", norm):
        if re.match(r"[\u4e00-\u9fff]", seg):
            out.extend((p, True) for p in lazy_pinyin(seg))     # 每个汉字一个音节
        else:
            out.extend((p, False) for p in seg.lower().split("_") if p)
    cleaned = []
    for syl, from_hanzi in out:
        syl = re.sub(r"[^a-z0-9]", "", syl)
        if syl:
            cleaned.append((syl, from_hanzi))
    return cleaned


def _fit_name(syllables, limit, digit_prefix):
    """全拼优先；全拼装不下就整名降级为各音节首字母（仿字段规则）。

    首字母之间不加下划线——单字母加分隔符不增加可读性，却让长度翻倍，
    把后面的音节挤出 limit 白白丢掉。
    数字/英文音节必须整段保留：只取首字母会把 "1.0mm" 和 "100.0mm" 都压成 "1"，
    而这类数字恰恰是名字的区分点。
    """
    def _fit(pieces):
        out = ""
        for piece in pieces:
            cand = piece if not out else out + "_" + piece
            if len(cand) > limit:
                break
            out = cand
        return out[:limit]

    has_cn = any(from_hanzi for _, from_hanzi in syllables)
    name = _fit([syl for syl, _ in syllables])
    if has_cn and len("_".join(syl for syl, _ in syllables)) > limit:
        name = "".join(syl[0] if from_hanzi else syl
                       for syl, from_hanzi in syllables)[:limit]
    if name[:1].isdigit():
        name = digit_prefix + name          # 数字开头补前缀，保证合法标识符
    return name[:limit]


def _col_to_field(cn: str, limit: int = _FIELD_MAX_LEN) -> str:
    """中文表头 -> 蛇形拼音字段名。

    规则（确定性，只有 30 字符这一个阈值）：
      1. 逐音节转拼音，音节间用下划线连接（蛇形），如 订单金额 -> ding_dan_jin_e；
      2. 全拼不超过 limit 就用全拼；
      3. 超过则整列强制降级为各音节首字母，如 北京市住房和城乡建设委员会 -> bjsszhjxwyh；
         首字母仍超长时截断兜底；
      4. 纯英文表头原样蛇形化，不做拼音转换；数字开头补 f_ 前缀。
    """
    if cn is None or str(cn).strip() == "":
        return "col"
    syllables = _syllables(cn)
    if not syllables:
        return "col"
    return _fit_name(syllables, limit, "f_")


def _looks_int(v) -> bool:
    if v is None:
        return True
    if isinstance(v, bool):
        return True
    if isinstance(v, (int, float)):
        return float(v).is_integer()
    s = str(v).strip()
    if s == "":
        return True
    s = s.replace(",", "").replace("，", "")
    try:
        int(s); return True
    except Exception:
        return False


def _looks_decimal(v) -> bool:
    if v is None:
        return True
    if isinstance(v, bool):
        return False
    if isinstance(v, (int, float)):
        return True
    s = str(v).strip()
    if s == "":
        return True
    s = s.replace(",", "").replace("，", "")
    try:
        float(s); return True
    except Exception:
        return False


def _looks_datetime(v) -> bool:
    if v is None or (isinstance(v, str) and v.strip() == ""):
        return True
    if isinstance(v, (datetime, date)):
        return True
    if not isinstance(v, str):
        return False        # 数字等其他类型直接排除，避免全量扫描时逐格式 strptime
    s = v.strip()
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y/%m/%d %H:%M:%S", "%Y-%m-%d %H:%M",
                "%Y-%m-%dT%H:%M:%S", "%Y%m%d%H%M%S"):
        try:
            datetime.strptime(s, fmt); return True
        except Exception:
            continue
    return False


def _looks_date(v) -> bool:
    if v is None or (isinstance(v, str) and v.strip() == ""):
        return True
    if isinstance(v, date) and not isinstance(v, datetime):
        return True
    if not isinstance(v, str):
        return False        # 数字等其他类型直接排除，避免全量扫描时逐格式 strptime
    s = v.strip()
    for fmt in ("%Y-%m-%d", "%Y/%m/%d", "%Y.%m.%d", "%Y%m%d"):
        try:
            datetime.strptime(s, fmt); return True
        except Exception:
            continue
    return False


def _has_leading_zero(v: str) -> bool:
    """纯整数字符串且带前导零（如 007、00123）——转 INT 会丢失前导零，应保持文本。"""
    t = str(v).strip().replace(",", "").replace("，", "")
    digits = t[1:] if t.startswith("-") else t
    return len(digits) > 1 and digits[0] == "0" and digits.isdigit()


def infer_columns(df: pd.DataFrame) -> list:
    """
    全量扫描每列，返回字段定义列表：
    [{"field": 拼音字段名, "comment": 中文表头, "type": "VARCHAR(64)", "max_len": 12}, ...]

    与旧版按 ~200 行抽样的差异：
    - 数据已全量在内存，直接全量扫描；max_len 与类型判断不再抽样，
      避免最长行 / 异常行漏判（max 恰恰是最不能抽样的统计量）
    - 纯空白字符串视为空值
    - 带前导零的整数字符串（007、00123）保持文本类型，避免转 INT 丢前导零
    - 决策顺序调整为 日期时间 > 整数 > 日期 > 小数 > 文本：
      20230101 这类 8 位纯数字编码列优先按数值处理，不再误判为 DATE；
      真实日期列在 Excel 中是日期对象或带分隔符字符串，不会同时满足 all_int

    文本列字节长度算法：
    - 需求字节 = 全量最大 UTF-8 字节数 max_len + 20% 余量（至少 16 字节）
    - 向上取整到常用档位（64 ~ 16383）→ VARCHAR(n)；需求 > 16383 字节 → TEXT；
      需求 > 65535 字节 → MEDIUMTEXT
    - utf8mb4 下 VARCHAR(n) 计入行大小 4n 字节，全表共用 60000 字节预算：
      预算不足的大文本列自动退为 TEXT（行外存储不占行预算），保证建表不报
      Row size too large
    """
    headers = [str(c) for c in df.columns]
    cols = []
    seen = {}
    for i, h in enumerate(headers):
        field = _col_to_field(h)
        if field in seen:
            seen[field] += 1
            field = f"{field}_{seen[field]}"
        else:
            seen[field] = 1
        coldata = df.iloc[:, i]
        max_len = 0
        has_value = False
        all_int = all_dec = all_dt = all_da = True
        for v in coldata:
            if v is None or (isinstance(v, float) and pd.isna(v)):
                continue
            if isinstance(v, str) and v.strip() == "":
                continue                        # 纯空白视为空值
            has_value = True
            sv = str(v)
            max_len = max(max_len, len(sv.encode("utf-8")))
            if all_int:
                if not _looks_int(v):
                    all_int = False
                elif isinstance(v, str) and _has_leading_zero(v):
                    all_int = False             # 前导零编码列保持文本
            if all_dec:
                if not _looks_decimal(v):
                    all_dec = False
                elif isinstance(v, str) and _has_leading_zero(v):
                    all_dec = False             # 避免落入 DECIMAL 分支同样丢前导零
            if all_dt and not _looks_datetime(v):
                all_dt = False
            if all_da and not _looks_date(v):
                all_da = False
        pending_n = None
        col_note = None
        money = _is_money(h, field)
        obs_int = obs_scale = 0
        for v in coldata:
            shape = _num_shape(v)
            if shape:
                obs_int = max(obs_int, shape[0])
                obs_scale = max(obs_scale, shape[1])
        if has_value and all_dt:
            typ = "DATETIME"
        elif has_value and money and (all_int or all_dec) and not all_da:
            # 金额列一律 DECIMAL：样本恰好是整数（Excel 常把 100 存成 100.0）
            # 也不能判成 INT，否则后续带小数的数据会被砍掉或写入报错
            typ = _decimal_type(obs_int, obs_scale, 4, money=True)
            if all_int:
                col_note = (f"表头「{h}」含金额语义，样本值虽为整数，"
                            f"仍按 {typ} 而非整数类型")
        elif has_value and all_int:
            # 检查是否溢出 INT 范围（带 break，避免全列无谓遍历）
            overflow = False
            for v in coldata:
                if v is None or (isinstance(v, float) and pd.isna(v)):
                    continue
                try:
                    iv = int(str(v).replace(",", "").replace("，", ""))
                    if abs(iv) > 2147483647:
                        overflow = True
                        break
                except Exception:
                    pass
            typ = "BIGINT" if overflow else "INT"
        elif has_value and all_da:
            typ = "DATE"
        elif has_value and all_dec:
            typ = _decimal_type(obs_int, obs_scale, 4, money=money)
        else:
            # 文本列：需求字节 = 全量最大字节 + 20% 余量；
            # 具体长度在第二遍按全表 VARCHAR 预算分配
            headroom = max(16, max_len // 5)
            needed = max_len + headroom
            if needed <= _VARCHAR_MAX_CHARS:
                typ = None
                pending_n = _varchar_tier(needed)
            else:
                typ = "TEXT" if needed <= 65535 else "MEDIUMTEXT"
        col = {"field": field, "comment": h, "type": typ, "max_len": max_len}
        if pending_n is not None:
            col["_varchar_n"] = pending_n
        if col_note:
            col["type_note"] = col_note
        cols.append(col)

    # 第二遍：VARCHAR 行内字节预算分配（utf8mb4 每 VARCHAR(n) 计 4n 字节）
    remaining = _ROW_BYTE_BUDGET
    for c in cols:
        n = c.pop("_varchar_n", None)
        if n is None:
            remaining -= _COL_ROW_COST
            continue
        cost = 4 * n
        if cost <= remaining:
            remaining -= cost
            c["type"] = f"VARCHAR({n})"
        else:
            c["type"] = "TEXT"    # 预算不足退为行外存储，保证建表成功
    return cols


# ---------------- 主键自动选择 ----------------
_PK_KEYWORDS = ("编号", "序号", "代码", "编码", "标识", "卡号", "单号", "主键", "唯一")
_PK_ID_FIELD_RE = re.compile(r"(?:^|_)(id|uid|uuid|guid)(?:_|$)", re.IGNORECASE)
_PK_ID_COMMENT_RE = re.compile(r"\bid\b|uuid|guid|唯一标识|主键", re.IGNORECASE)


def _pk_type_ok(typ: str) -> bool:
    """主键可用类型：INT/BIGINT，或长度 ≤ 255 的 VARCHAR/CHAR。"""
    t = str(typ).upper()
    if t.startswith(("INT", "BIGINT")):
        return True
    m = re.search(r"\((\d+)\)", t)
    return t.startswith(("VARCHAR", "CHAR")) and bool(m) and int(m.group(1)) <= 255


def _pk_value_key(v) -> str:
    """主键查重用的规范化键：数值 1 与 1.0 视为相同（MySQL 数值唯一性一致）。"""
    if isinstance(v, bool):
        return str(int(v))
    if isinstance(v, (int, float)):
        return repr(float(v))
    return str(v).strip()


def _pk_unique_nonull(df, i: int) -> bool:
    """主键硬性条件：全列非空（空白串也算空）且唯一。"""
    seen = set()
    for v in df.iloc[:, i]:
        if v is None or (isinstance(v, float) and pd.isna(v)):
            return False
        if isinstance(v, str) and v.strip() == "":
            return False
        key = _pk_value_key(v)
        if key in seen:
            return False
        seen.add(key)
    return True


def validate_primary_key(df: pd.DataFrame, columns: list) -> tuple:
    """
    校验标记为 primary_key 的字段能否组成主键（支持一个或多个字段组合）。
    返回 (是否可用, 问题说明)；未标记任何字段则直接通过（不设主键）。

    规则：
      - 字段类型须为 INT/BIGINT 或长度 ≤255 的 VARCHAR/CHAR（TEXT 等不能作主键）
      - 组合内每个字段全列非空（空白串也算空）
      - 组合值整体唯一
    """
    pk_idx = [i for i, c in enumerate(columns) if c.get("primary_key")]
    if not pk_idx:
        return True, ""
    for i in pk_idx:
        c = columns[i]
        if not _pk_type_ok(str(c.get("type", ""))):
            return False, (f"字段 {c.get('field', i)} 类型 {c.get('type')} "
                           f"不能作为主键（可用 INT/BIGINT 或长度 ≤255 的 VARCHAR/CHAR）")
    if len(df) == 0:
        return False, "没有数据行，无法校验主键"
    sub = df.iloc[:, pk_idx]
    seen = set()
    for r_off, row in enumerate(sub.itertuples(index=False, name=None)):
        parts = []
        for v in row:
            if v is None or (isinstance(v, float) and pd.isna(v)):
                return False, f"第 {r_off + 1} 行存在空值，主键字段不允许为空"
            if isinstance(v, str) and v.strip() == "":
                return False, f"第 {r_off + 1} 行存在空白值，主键字段不允许为空"
            parts.append(_pk_value_key(v))
        key = "\x1f".join(parts)
        if key in seen:
            return False, f"第 {r_off + 1} 行主键组合值重复，主键字段组合必须唯一"
        seen.add(key)
    return True, ""


def select_primary_key(df: pd.DataFrame, columns: list) -> tuple:
    """
    分析列命名与数据，选出至多一个适合做主键的字段。返回 (字段名或 None, 说明)。

    硬性条件（不满足直接排除）：
      - 类型为 INT/BIGINT，或长度 ≤ 255 的 VARCHAR/CHAR
      - 全列非空且唯一
      - 表至少有一行数据
    评分（语义分 ≥ 3 才入选，避免把碰巧唯一的金额列当主键）：
      +4  字段名形如 id/uuid/_id，或注释含 ID/主键/唯一标识
      +3  注释含 编号/序号/代码/编码/标识/卡号/单号/唯一 等关键词
      +2  整数类型（INT/BIGINT）
      +1  短字符类型（VARCHAR ≤ 64）
      同分取字节长度更短者，再取靠前的列。
    """
    if len(df) == 0:
        return None, "没有数据行，不设主键"
    best = None
    for i, c in enumerate(columns):
        typ = str(c["type"]).upper()
        if not _pk_type_ok(typ) or not _pk_unique_nonull(df, i):
            continue
        field = str(c["field"]).lower()
        comment = str(c.get("comment", ""))
        score = 0
        reasons = []
        if _PK_ID_COMMENT_RE.search(comment) or _PK_ID_FIELD_RE.search(field):
            score += 4
            reasons.append("ID/标识类命名")
        if any(k in comment for k in _PK_KEYWORDS):
            score += 3
            reasons.append("注释含编号/代码类关键词")
        if typ.startswith(("INT", "BIGINT")):
            score += 2
            reasons.append("整数类型")
        else:
            m = re.search(r"\((\d+)\)", typ)
            if m and int(m.group(1)) <= 64:
                score += 1
                reasons.append("短字符类型")
        if score < 3:
            continue
        max_len = c.get("max_len") or 0
        key = (score, -max_len, -i)
        if best is None or key > best[0]:
            best = (key, c["field"], "、".join(reasons))
    if best is None:
        return None, "没有命名与数据都合适的字段（需非空、唯一且语义明确）"
    return best[1], f"{best[2]}，全列非空且唯一"


# ---------------- 第 2 类：结构定义 Excel（元数据 Sheet + 数据 Sheet） ----------------
# 元数据表头模糊匹配：表头文本包含关键词即归到对应角色（按顺序取第一个命中角色）
_META_HEADER_ROLES = (
    ("field", ("字段名", "列名")),
    ("comment", ("字段注释", "注释", "说明")),
    ("type", ("数据类型", "类型")),
    ("length", ("长度",)),
    ("pk", ("主键",)),
    ("nullable", ("可空", "为空")),
)


def _match_meta_roles(df) -> Optional[dict]:
    """
    判断一个 Sheet 是否为「字段定义」元数据表。
    命中 field + type 且至少还有一个其他定义列时认为是元数据，返回角色→表头映射。
    """
    if df is None or df.empty or len(df.columns) < 3:
        return None
    roles = {}
    for h in df.columns:
        text = str(h).strip()
        if not text:
            continue
        for role, kws in _META_HEADER_ROLES:
            if role not in roles and any(k in text for k in kws):
                roles[role] = h
                break
    if "field" in roles and "type" in roles and len(roles) >= 3:
        return roles
    return None


def _norm_meta_length(v):
    """长度单元格 -> 规范字符串（如 '20'、'10,2'）；空或非法返回 None。"""
    if v is None or (isinstance(v, float) and pd.isna(v)):
        return None
    s = str(v).strip().replace("，", ",")
    if s == "":
        return None
    if "," in s:
        try:
            return ",".join(str(int(float(p))) for p in s.split(","))
        except Exception:
            return None
    try:
        return str(int(float(s)))
    except Exception:
        return None


def _norm_meta_type(raw_type, length_val):
    """类型单元格 + 长度单元格 -> 规范 SQL 类型（VARCHAR(20)、DECIMAL(10,2)、DATETIME…）。"""
    if raw_type is None or (isinstance(raw_type, float) and pd.isna(raw_type)):
        return None
    t = str(raw_type).strip().upper().replace("（", "(").replace("）", ")")
    m = re.match(r"^([A-Z][A-Z0-9_]*)\s*\((.*)\)$", t)
    if m:
        base, inline = m.group(1), _norm_meta_length(m.group(2))
        return f"{base}({inline})" if inline else base
    if not re.match(r"^[A-Z][A-Z0-9_]*$", t):
        return None
    ln = _norm_meta_length(length_val)
    return f"{t}({ln})" if ln else t


def _meta_flag(v) -> bool:
    """「是否主键」类单元格 -> True/False（是/y/true/1）。"""
    if v is None or (isinstance(v, float) and pd.isna(v)):
        return False
    if isinstance(v, bool):
        return v
    if isinstance(v, (int, float)):
        return v == 1
    return str(v).strip().lower() in ("是", "y", "yes", "true", "1")


def _meta_nullable(v) -> bool:
    """「是否可为空」类单元格：否/n/no/false/0 -> 不可空；是/空 -> 可空。"""
    if v is None or (isinstance(v, float) and pd.isna(v)):
        return True
    if isinstance(v, bool):
        return not v
    if isinstance(v, (int, float)):
        return v != 1
    return str(v).strip().lower() not in ("否", "n", "no", "false", "0")


def _build_structured(filepath: str, sheets: dict) -> dict:
    """
    第 2 类 Excel：结构定义模式。字段名/类型/长度/主键/可空全部以元数据 Sheet 定义为准，
    不再从数据推断；数据 Sheet 按字段名对齐（大小写不敏感），
    多余列忽略并记录，缺失列补 NULL；只有定义没有数据时建空表。
    """
    table_name, table_comment = parse_table_name_and_comment(filepath)
    meta_name = next(n for n, sdf in sheets.items() if _match_meta_roles(sdf) is not None)
    roles = _match_meta_roles(sheets[meta_name])
    meta = sheets[meta_name].dropna(axis=0, how="all")

    def cell(row, role):
        h = roles.get(role)
        return None if h is None else row[h]

    columns, seen = [], set()
    for _, row in meta.iterrows():
        field = str(cell(row, "field") or "").strip()
        field = re.sub(r"\s+", "_", field)
        typ = _norm_meta_type(cell(row, "type"), cell(row, "length"))
        if not field or typ is None:
            continue                      # 缺字段名或类型定义的行跳过
        low = field.lower()
        if low in seen:                   # 元数据内字段名重复时自动加序号
            k = 2
            while f"{field}_{k}".lower() in seen:
                k += 1
            field = f"{field}_{k}"
            low = field.lower()
        seen.add(low)
        comment = cell(row, "comment")
        comment = "" if comment is None or (isinstance(comment, float) and pd.isna(comment)) \
            else str(comment).strip()
        columns.append({
            "field": field,
            "comment": comment,
            "type": typ,
            "max_len": _norm_meta_length(cell(row, "length")),
            "nullable": _meta_nullable(cell(row, "nullable")),
            "primary_key": _meta_flag(cell(row, "pk")),
        })
    if not columns:
        raise ValueError("结构定义 Sheet 中没有可识别的字段定义行（每行需要字段名和数据类型）")

    # 数据 Sheet：第一个非元数据 Sheet；按字段名对齐到元数据列顺序
    data_name = None
    for n, sdf in sheets.items():
        if n != meta_name and _match_meta_roles(sdf) is None and len(sdf.columns) > 0:
            data_name = n
            break
    extra_cols = []
    if data_name is not None:
        raw = sheets[data_name].dropna(axis=1, how="all").dropna(axis=0, how="all")
        lower_map = {str(h).strip().lower(): h for h in raw.columns}
        data_cols = {}
        for c in columns:
            h = lower_map.get(c["field"].lower())
            data_cols[c["field"]] = raw[h] if h is not None else \
                pd.Series([None] * len(raw), index=raw.index)
        known = {c["field"].lower() for c in columns}
        extra_cols = [str(h) for h in raw.columns if str(h).strip().lower() not in known]
        df = pd.DataFrame(data_cols)
    else:
        df = pd.DataFrame({c["field"]: pd.Series(dtype=object) for c in columns})

    pk_fields = [c["field"] for c in columns if c["primary_key"]]
    if pk_fields:
        good, msg = validate_primary_key(df, columns)
        pk_reason = "结构定义（元数据 Sheet）指定"
        if not good:
            pk_reason += f"；注意：{msg}"
    else:
        pk_reason = "结构定义中未标记主键"
    return {
        "filepath": filepath,
        "table_name": table_name,
        # 未加前缀的基础表名，供「批量应用前缀」重复计算使用
        "base_table_name": table_name,
        "table_comment": table_comment,
        "columns": columns,
        "df": df,
        "primary_key": pk_fields[0] if pk_fields else None,
        "pk_reason": pk_reason,
        "source": "structured",
        "meta_sheet": str(meta_name),
        "data_sheet": None if data_name is None else str(data_name),
        "extra_data_columns": extra_cols,
    }


def build_table_def(filepath: str, progress_cb=None) -> dict:
    """
    读取单个 Excel，自动识别两类格式并返回表定义：
      第 1 类（普通数据表）：单个数据 Sheet，表名/注释来自文件名，字段从数据推断；
      第 2 类（结构定义表）：某个 Sheet 是字段定义（字段名/数据类型/长度/主键/可空），
        字段与类型以定义为准，另一个 Sheet 作为数据导入。
    返回 dict：filepath / table_name / base_table_name / table_comment / columns / df /
    primary_key / pk_reason / source("normal"|"structured") 等。
    """
    def _note(text):
        # 大文件读盘与推断要好几秒，逐阶段报一句，进度框上能看到"还在动"
        if progress_cb:
            progress_cb(text)

    ext = os.path.splitext(filepath)[1].lower()
    if ext not in (".xls", ".xlsx"):
        raise ValueError(f"不支持的文件类型：{ext}")
    # 合并单元格要在读数据之前判掉：这类文件解析出来必然错位，
    # 提前拒绝既避免导错数据，也省掉几十万行的读取开销
    _note("检查合并单元格")
    ensure_no_merged_cells(filepath)
    _note("读取工作簿")
    sheets = pd.read_excel(filepath, sheet_name=None, dtype=object)
    _note("识别表格格式")
    meta_name = None
    for n, sdf in sheets.items():
        if _match_meta_roles(sdf) is not None:
            meta_name = n
            break
    if meta_name is not None:
        _note("按字段定义组装")
        return _build_structured(filepath, sheets)
    df = next(iter(sheets.values()))
    # 去掉全空列与全空行（Excel 尾部格式化产生的空行避免整行 NULL 导入）
    df = df.dropna(axis=1, how="all")
    df = df.dropna(axis=0, how="all")
    table_name, table_comment = parse_table_name_and_comment(filepath)
    _note("推断字段类型")
    columns = infer_columns(df)
    # 一列都没有就别往下走了：这种表建出来是 `CREATE TABLE x ( )`，
    # MySQL 会回一条语法错误，现场看到"导入失败"却猜不到是自己拖了张空表
    if not columns:
        raise ValueError("没有解析到任何字段：表里没有可用的表头行（空表，或整列都是空值）")
    # 主键自动选择：至多一个，详见 select_primary_key
    _note("选择主键")
    pk_field, pk_reason = select_primary_key(df, columns)
    if pk_field:
        for c in columns:
            c["primary_key"] = (c["field"] == pk_field)
    return {
        "filepath": filepath,
        "table_name": table_name,
        # 未加前缀的基础表名，供「批量应用前缀」重复计算使用
        "base_table_name": table_name,
        "table_comment": table_comment,
        "columns": columns,
        "df": df,
        "primary_key": pk_field,
        "pk_reason": pk_reason,
        "source": "normal",
    }


def make_unique_names(tables: list) -> list:
    """
    对重复表名加 _2 _3 后缀，保证唯一。
    已考虑历史后缀：列表里已存在 users、users_2 时，新增的重复 users 会命名为
    users_3 而不是再次生成 users_2；重复调用（增量添加文件）是幂等的。
    """
    used = set()
    counter = {}
    for t in tables:
        name = t["table_name"]
        if name not in used:
            used.add(name)
            counter[name] = 1
            continue
        counter[name] += 1
        new_name = f"{name}_{counter[name]}"
        while new_name in used:
            counter[name] += 1
            new_name = f"{name}_{counter[name]}"
        t["table_name"] = new_name
        used.add(new_name)
    return tables


def replace_first_segment(tables: list, new_head: str, on_item=None) -> tuple:
    """把每张表名的首段前缀换成 new_head，返回 (改名列表, 跳过表名列表)。

    切分口径见 naming.swap_first_segment：第一个下划线之前算首段，
    所以 ods_t_order -> change_t_order；若之前用「批量应用前缀」加过前缀
    （dim_ods_t_order），被换掉的正是 dim_ 这一段——按界面上看到的名字改，最直观。
    换完把 base_table_name 一起更新，之后再加前缀基于新名算，不会留旧前缀。
    表名里没有下划线（users）或首段为空（_tmp）时不动，进跳过列表由调用方说明。
    """
    new_head = clean_prefix(new_head)
    if not new_head:
        return [], [t["table_name"] for t in tables]
    staged, skipped = [], []
    for t in tables:
        old = t["table_name"]
        new = swap_first_segment(old, new_head)
        if new is None:
            skipped.append(old)
        else:
            t["table_name"] = new
            t["base_table_name"] = new
            staged.append((t, old))
        # 改与不改都算"处理过这张表"，否则跳过的表会让进度条停在半途
        if on_item:
            on_item(t)
    make_unique_names(tables)       # 换首段可能撞名（ods_a 与 x_a 都变 change_a）
    renamed = [{"old": old, "new": t["table_name"]} for t, old in staged]
    return renamed, skipped


def apply_table_prefix(tables: list, prefix: str, on_item=None) -> list:
    """
    为全部表批量添加表名前缀：table_name = prefix + base_table_name。
    基于 base_table_name 重算，换前缀重复应用不会叠加；prefix 为空则移除前缀。
    on_item(table) 在每张表改名后回调，供上层报进度（大批量表时可见反馈）。
    """
    prefix = (prefix or "").strip().replace("`", "")
    for t in tables:
        base = t.get("base_table_name") or t["table_name"]
        t["base_table_name"] = base
        t["table_name"] = prefix + base if prefix else base
        if on_item:
            on_item(t)
    make_unique_names(tables)
    return tables
