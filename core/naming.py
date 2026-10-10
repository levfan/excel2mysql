"""表名"前缀"相关的纯命名规则，供 Excel 表定义与 SQL 脚本文本两条线共用。

单独成模块是为了避免 infer.py 与 sql_script.py 互相 import：
「首个前缀怎么切」这个口径两边必须一致，否则同一个表名在两个页签里改出两种结果。
"""
import re

# 手填前缀允许的字符：合法 MySQL 标识符片段（大小写保留，Linux 下表名区分大小写）
_PREFIX_RE = re.compile(r"^[A-Za-z0-9_]+$")


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
