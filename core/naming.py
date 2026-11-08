"""表名"前缀"相关的纯命名规则，供 Excel 表定义与 SQL 脚本文本两条线共用。

单独成模块是为了避免 infer.py 与 sql_script.py 互相 import：
「首个前缀怎么切」这个口径两边必须一致，否则同一个表名在两个页签里改出两种结果。
"""
import re

# 手填前缀允许的字符：合法 MySQL 标识符片段（大小写保留，Linux 下表名区分大小写）
_PREFIX_RE = re.compile(r"^[A-Za-z0-9_]+$")

# 名字里带小数的版本号：2.0 / V2.0 / v3.5 —— 直接转拼音会断成 2_0 两段，看着像两个
# 并列数字而不像版本。整段写成 v2（v3.5 写成 v3_5），拼进名字就是 xxx_v2 / xxx_v2_yyy。
# 主版本限一到两位，且前后不许贴着数字或点：2026.10.1 这类日期、1234.56 这种数值不受影响。
_VERSION_TOKEN_RE = re.compile(r"(?<![0-9.])[vV]?(\d{1,2})\.(\d{1,2})(?![0-9])")


def rewrite_versions(text) -> str:
    """把名字里的版本号改写成能直接进表名的一段：2.0 -> v2，V3.5 -> v3_5。

    表名与手工改名的校验都走这一条：用户看到的是 _v2_ 这种拼接形式，
    而不是被"只能用字母数字下划线"拦下来的 2.0。
    紧贴字母数字时两侧补下划线（orders2.0 -> orders_v2），
    这样「拼接形式」在任何名字里都成立，不会粘成 ordersv2。
    """
    src = str(text if text is not None else "")

    def _sub(m):
        major, minor = m.group(1), m.group(2)
        # 2.0 这种补零小版本没有区分意义，写成 v2；3.5 这种要留住 5，写成 v3_5
        token = f"v{major}" if minor == "0" else f"v{major}_{minor}"
        before = src[m.start() - 1] if m.start() > 0 else ""
        after = src[m.end():m.end() + 1]
        if before and before.isalnum():
            token = "_" + token
        if after and after.isalnum():
            token = token + "_"
        return token

    return _VERSION_TOKEN_RE.sub(_sub, src)


# 中文数字：文件名/目录名里的「二〇二五」「二零二五」「第十二批」这类写法，逐字转拼音只能得到
# er_er_wu / elew（〇 还不在拼音表里，整字被丢掉），表名读不出年份也不便排序。
# 先把连续的数字汉字读成阿拉伯数字，再走后面的拼音分词。
# 只处理**连续两个及以上**的数字汉字：统一编码、第一、万一 里的「一」是词的一部分而不是数词，
# 单个汉字一律照旧转拼音。
_CN_DIGITS = {
    "零": 0, "〇": 0, "○": 0,
    "一": 1, "壹": 1, "二": 2, "贰": 2, "貳": 2, "两": 2, "兩": 2,
    "三": 3, "叁": 3, "參": 3, "四": 4, "肆": 4, "五": 5, "伍": 5,
    "六": 6, "陆": 6, "陸": 6, "七": 7, "柒": 7, "八": 8, "捌": 8, "九": 9, "玖": 9,
}
_CN_UNITS = {
    "十": 10, "拾": 10, "百": 100, "佰": 100, "千": 1000, "仟": 1000,
    "万": 10000, "萬": 10000, "亿": 100000000, "億": 100000000,
}
_CN_NUM_RE = re.compile("[%s]{2,}" % re.escape("".join(list(_CN_DIGITS) + list(_CN_UNITS))))


def _cn_number(token: str):
    """一段中文数字 -> 数字串；读不成一个合理数字时返回 None（调用方保留原文）。

    没有单位词时逐位读，前导零照留：二〇二五 -> 2025，零一 -> 01；
    有单位词时按位置权重算：十二 -> 12，二十五 -> 25，一百零八 -> 108，两万 -> 20000。
    位置读法要先过两道合法性，否则「千万」「百万」「万一」这类词会被硬读成天文数字：
      1. 百/千/万/亿 前必须有明确数字（十 允许省略前面的「一」，十二 才是数词）；
      2. 结尾的裸数字只能跟在 十/百/千 或「零」后面（二十五、一百零八 成立，
         「一万二」这种口语省略不认）。
    """
    if not any(ch in _CN_UNITS for ch in token):
        return "".join(str(_CN_DIGITS[ch]) for ch in token)
    prev = ""
    for ch in token:
        if ch in _CN_UNITS and _CN_UNITS[ch] >= 100 and prev not in _CN_DIGITS:
            return None
        prev = ch
    tail = token[-1]
    if tail in _CN_DIGITS and tail != "零" and len(token) >= 2:
        before = token[-2]
        if before in _CN_UNITS and _CN_UNITS[before] >= 10000:
            return None
    total = section = number = 0
    for ch in token:
        if ch in _CN_DIGITS:
            number = _CN_DIGITS[ch]
            continue
        unit = _CN_UNITS[ch]
        if unit >= 10000:
            section += number
            total += (section or 1) * unit
            section = number = 0
        else:
            section += (number or 1) * unit      # 十二 / 一百 里的省略写法按 1 算
            number = 0
    return str(total + section + number)


def rewrite_cn_numbers(text) -> str:
    """把名字里的中文数字写成阿拉伯数字：二〇二五 -> 2025，第十二批 -> 第12批。

    表名与字段名共用这一条（两者的音节切分本来就同源），拼出来的名字既读得出年份，
    也与文件名里本来就写 2025 的那一份落在同一套形状上，不会一个 2025 一个 elew。
    """
    src = str(text if text is not None else "")
    return _CN_NUM_RE.sub(lambda m: _cn_number(m.group(0)) or m.group(0), src)


def clean_prefix(text) -> str:
    """清掉用户输入里的空白与反引号/反斜杠，并去掉末尾下划线。

    分隔用的下划线沿用表名自己那一个（ods_t_order 里 ods 后面那个），
    所以填 change 与 change_ 效果相同，不会出现 change__t_order。
    """
    cleaned = str(text or "").replace("`", "").replace("\\", "")
    return cleaned.strip().rstrip("_").strip()


def is_valid_prefix(text) -> bool:
    """校验清完之后的前缀是否只剩合法字符；空串视为不合法（调用方自己决定怎么提示）。"""
    return bool(_PREFIX_RE.match(text or ""))


def swap_first_segment(name, new_head: str):
    """把表名首段（第一个下划线之前）换成 new_head。

    返回新表名；以下情况返回 None 表示"不改"，调用方据此跳过并在日志里说明：
      - 名字里没有下划线（如 users）：整名就是唯一一段，换掉等于改名而不是换前缀
      - 首段为空（如 _tmp）：那不是前缀而是下划线开头的畸形名
    """
    head, sep, tail = str(name).partition("_")
    if not sep or not head:
        return None
    new = f"{new_head}{sep}{tail}"
    return None if new == name else new
