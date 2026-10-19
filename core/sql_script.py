"""SQL 脚本文件处理：提取 CREATE TABLE 表名、批量加前缀/换首段前缀、语句切分。"""
import re

from .naming import clean_prefix, swap_first_segment

# CREATE TABLE [IF NOT EXISTS] `表名`（兼容 schema.`table` 限定名，取最后一段）
_CREATE_TABLE_RE = re.compile(
    r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?"
    r"(?:`?[\w$]+`?\s*\.\s*)?`?([A-Za-z0-9_$]+)`?",
    re.IGNORECASE,
)


def extract_create_tables(text: str) -> list:
    """
    按首次出现顺序统计脚本中所有 CREATE TABLE [IF NOT EXISTS] 语句的表名及次数。
    返回 [{"name": 表名, "count": 出现次数}, ...]；INSERT INTO 等其他语句不计入。
    """
    counts, order = {}, []
    for m in _CREATE_TABLE_RE.finditer(text):
        name = m.group(1)
        if name not in counts:
            counts[name] = 0
            order.append(name)
        counts[name] += 1
    return [{"name": n, "count": counts[n]} for n in order]


def _rewrite_names(text: str, plan: dict) -> tuple:
    """按 {旧表名: 新表名} 改写脚本中的表名引用。

    改写范围：
      - 带反引号的引用（`old`，含 `db`.`old`）全局替换，覆盖 DROP/INSERT/ALTER 等
      - 裸表名仅在语句头位置替换（CREATE TABLE / DROP TABLE / INSERT INTO /
        ALTER TABLE / TRUNCATE TABLE），避免误伤字符串内容；
        schema 限定的裸表名（db.tbl）不改写，请使用反引号写法
    返回 (新文本, 改写列表 [{"old","new","n"}], 未改写表名列表)；
    未改写列表含"没匹配到任何引用"的名字，供上层说明原因。
    """
    renamed, nohit = [], []
    new_text = text
    for old, new in plan.items():
        n = 0
        # 1) 带反引号的引用：`old` -> `new`
        new_text, k = re.subn("`" + re.escape(old) + "`", "`" + new + "`", new_text)
        n += k
        # 2) 语句头位置的裸表名
        new_text, k = re.subn(
            r"(CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?|DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?|"
            r"INSERT\s+INTO\s+|ALTER\s+TABLE\s+|TRUNCATE\s+TABLE\s+)"
            r"(?<![\w$`])" + re.escape(old) + r"(?![\w$`])",
            r"\g<1>" + new,
            new_text,
            flags=re.IGNORECASE,
        )
        n += k
        if n:
            renamed.append({"old": old, "new": new, "n": n})
        else:
            nohit.append(old)
    return new_text, renamed, nohit


def apply_table_prefix_sql(text: str, prefix: str) -> tuple:
    """
    给脚本中提取到的表名统一加前缀。返回 (新文本, 改写列表, 跳过列表)：
      改写列表 [{"old", "new", "n"}]，n 为该表名被改写的引用处数；
      跳过列表为已带该前缀的表名（重复应用不会叠加前缀）与没匹配到引用的表名。
    """
    prefix = str(prefix or "").strip().replace("`", "").replace("\\", "")
    if not prefix:
        return text, [], []
    plan, skipped = {}, []
    for t in extract_create_tables(text):
        old = t["name"]
        if old.startswith(prefix):
            skipped.append(old)
            continue
        plan[old] = prefix + old
    new_text, renamed, nohit = _rewrite_names(text, plan)
    return new_text, renamed, skipped + nohit


def replace_first_segment_sql(text: str, new_head: str) -> tuple:
    """把脚本中每个表名的首段前缀换成 new_head（ods_t_order -> change_t_order）。

    与 apply_table_prefix_sql 的区别：这里是"换掉已有的第一段"，不是"再加一段"；
    没有下划线或首段为空的表名不动，进跳过列表，由调用方在日志里逐条说明。
    """
    new_head = clean_prefix(new_head)
    if not new_head:
        return text, [], []
    plan, skipped = {}, []
    for t in extract_create_tables(text):
        old = t["name"]
        new = swap_first_segment(old, new_head)
        if new is None:
            skipped.append(old)
            continue
        plan[old] = new
    new_text, renamed, nohit = _rewrite_names(text, plan)
    return new_text, renamed, skipped + nohit


def split_sql_statements(text: str) -> list:
    """
    按分号切分 SQL 语句，正确跳过以下内容中的分号：
      - '...' / "..." 字符串（支持 \\' 与 '' 转义）
      - `...` 反引号标识符（`` 转义）
      - -- 与 # 行注释、/* */ 块注释
    空语句忽略；不支持 DELIMITER 存储过程脚本。
    """
    stmts, buf = [], []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch in ("'", '"', "`"):
            quote = ch
            buf.append(ch)
            i += 1
            while i < n:
                buf.append(text[i])
                if text[i] == "\\" and quote != "`" and i + 1 < n:
                    buf.append(text[i + 1])          # 反斜杠转义（反引号内不适用）
                    i += 2
                    continue
                if text[i] == quote:
                    if i + 1 < n and text[i + 1] == quote:
                        buf.append(text[i + 1])      # 双写转义 ''
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
            continue
        if (ch == "-" and nxt == "-") or ch == "#":  # 行注释到行尾
            j = text.find("\n", i)
            j = n if j == -1 else j
            buf.append(text[i:j])
            i = j
            continue
        if ch == "/" and nxt == "*":                 # 块注释
            j = text.find("*/", i + 2)
            j = n if j == -1 else j + 2
            buf.append(text[i:j])
            i = j
            continue
        if ch == ";":
            s = "".join(buf).strip()
            if s:
                stmts.append(s)
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    s = "".join(buf).strip()
    if s:
        stmts.append(s)
    return stmts


def has_executable_content(stmt: str) -> bool:
    """这一句里除了注释和空白还剩东西吗。

    `split_sql_statements` 会把 `-- 只有注释` 这种整段是注释的片段也切成一条（它只按分号切，
    不管内容），而「SQL 执行器」要对用户说"只写注释也不算"——判据就得在这儿补上，
    否则会出现"切出来一条、执行时又说没得执行"，或者把空注释当成正经一句报"已提交 1 条"。
    走的是与切句同一套词法（引号 / 反引号 / -- 与 # 行注释 / 块注释）：
    字符串里的 `--` 不算注释，所以 `SELECT '-- x'` 仍然是一句真要执行的语句。
    """
    i, n = 0, len(stmt)
    while i < n:
        ch = stmt[i]
        nxt = stmt[i + 1] if i + 1 < n else ""
        if ch.isspace():
            i += 1
            continue
        if ch in ("'", '"', "`"):
            quote = ch
            i += 1
            while i < n:
                if stmt[i] == "\\" and quote != "`":
                    i += 2
                    continue
                if stmt[i] == quote:
                    if i + 1 < n and stmt[i + 1] == quote:
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
            return True            # 引号里有任何内容都是一句真要执行的语句
        if (ch == "-" and nxt == "-") or ch == "#":
            j = stmt.find("\n", i)
            i = n if j == -1 else j
            continue
        if ch == "/" and nxt == "*":
            j = stmt.find("*/", i + 2)
            i = n if j == -1 else j + 2
            continue
        return True                # 既不是空白也不是注释 → 有可执行内容
    return False
