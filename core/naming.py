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
