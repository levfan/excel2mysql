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


# 执行前要删的那张表：与 extract_create_tables 同一段 CREATE 头，但把 schema 限定名
# 整段留下（db.tbl 就该删 db.tbl，不能删成当前库里的同名表）
_CREATE_TARGET_RE = re.compile(
    r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?"
    r"((?:`?[\w$]+`?\s*\.\s*)?`?[A-Za-z0-9_$]+`?)",
    re.IGNORECASE,
)


def prepend_drop_tables(text: str) -> tuple:
    """给执行副本开头的每个建表目标补一句 DROP TABLE IF EXISTS。

    只在「覆盖已存在表」勾选后由执行动作调用，不写回脚本正文。
    返回 (新文本, 表名列表)；没有 CREATE TABLE 时原样返回。
    只认整句以 CREATE TABLE 开头的语句：注释里的建表、字符串里的建表都不该被删，
    这是删除动作，判据必须比"清单里显示哪些表"更严。
    顺序按出现顺序倒过来，近似"先删子表再删父表"。
    """
    seen, names = set(), []
    for stmt in split_sql_statements(text):
        masked = _mask_comments(stmt).strip()
        m = re.match(r"(?is)^CREATE\s+TABLE\b", masked)
        if not m:
            continue
        m2 = _CREATE_TARGET_RE.search(masked)
        if not m2:
            continue
        target = re.sub(r"\s+", "", m2.group(1))
        if target not in seen:
            seen.add(target)
            names.append(target)
    if not names:
        return text, []
    block = "".join("DROP TABLE IF EXISTS %s;\n" % n for n in reversed(names))
    return block + "\n" + text, names


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


def rename_table_sql(text: str, old: str, new: str) -> tuple:
    """把脚本里的一个现有表名改成新名字，并同步其 SQL 引用。

    返回 (新文本, 改写处数)。名字没变、新名不合法或找不到引用时返回原句和 0，
    由界面明确写出为什么没落盘，不能静默吞掉一次操作。
    """
    old = str(old or "").strip().strip("`")
    new = str(new or "").strip().strip("`")
    if not old or not new or old == new:
        return text, 0
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_$]*", new):
        return text, 0
    new_text, renamed, nohit = _rewrite_names(text, {old: new})
    if renamed:
        return new_text, renamed[0]["n"]
    return text, 0


def apply_display_renames(text: str, display_map: dict) -> tuple:
    """把页面上显示的表名一次性写进脚本文本（点保存/执行/生成时才调用）。

    display_map 是 {脚本里的当前表名: 页面显示名}；空名与没变化的条目跳过。
    返回 (新文本, 改写列表, 未命中列表)，与 _rewrite_names 同一口径。
    """
    plan = {str(old): str(new) for old, new in (display_map or {}).items()
            if old and new and str(old) != str(new)}
    if not plan:
        return text, [], []
    return _rewrite_names(text, plan)


def set_table_comment(text: str, table_name: str, comment: str) -> tuple:
    """改一张表的表注释：把那句 CREATE TABLE 的表选项 COMMENT='…' 换成新内容。

    返回 (新文本, 改写处数)。找不到这张表的 CREATE TABLE、或那句压根没有列定义
    （LIKE / AS SELECT 写法没有表选项可挂）时返回原文和 0，由界面明说为什么没落盘，
    不静默吞掉一次操作。同名表被写两遍只改**第一处**，与元数据导出「同名表只按第一处」
    是同一条规则。

    写法统一成 MySQL 的标准形：单引号包住、里面的单引号翻倍；原来用双引号的也换成这个
    口径。原本没有 COMMENT 的，在列定义右括号后面补一句 COMMENT='…'——表选项不分先后。
    清空注释写的是 COMMENT=''，读回来就是空串，不在脚本里留一个看不见的占位词。
    """
    name = str(table_name or "").strip().strip("`")
    if not name:
        return text, 0
    body = str(comment if comment is not None else "").strip()
    literal = "'" + body.replace("'", "''") + "'"
    masked = _mask_comments(text)
    for m in _CREATE_TABLE_RE.finditer(masked):
        if m.group(1) != name:
            continue
        nxt = _CREATE_TABLE_RE.search(masked, m.end())
        limit = min(m.end() + _first_top_level(masked[m.end():], ";"),
                    nxt.start() if nxt else len(masked))
        open_at = masked.find("(", m.end())
        if open_at == -1 or open_at >= limit:
            return text, 0            # LIKE / AS SELECT：没有表选项这段可改
        close = _match_paren(masked, open_at)
        if close == -1:
            return text, 0
        stop = close + 1 + _first_top_level(masked[close + 1:], ";")
        if nxt is not None:
            stop = min(stop, nxt.start())
        options = text[close + 1:stop]
        options_masked = masked[close + 1:stop]
        cm = _TABLE_COMMENT_RE.search(options_masked)
        if cm is None:
            new_text = text[:close + 1] + " COMMENT=" + literal + text[close + 1:]
            return new_text, 1
        at = close + 1 + cm.start()
        end = close + 1 + cm.end()
        # 只换 COMMENT 关键字后面那个字面量的值？不，整段重写：等号有没有、单双引号、
        # 翻倍引号这些写法差异一次抹平，改完的脚本再读回来一定是刚输入的内容
        new_text = text[:at] + "COMMENT=" + literal + text[end:]
        if new_text == text:
            return text, 0
        return new_text, 1
    return text, 0


def split_sql_statements(text: str) -> list:
    """
    按分号切分 SQL 语句，正确跳过以下内容中的分号：
      - '...' / "..." 字符串（支持 \\' 与 '' 转义）
      - `...` 反引号标识符（`` 转义）
      - -- 与 # 行注释、/* */ 块注释
    空语句忽略；不支持 DELIMITER 存储过程脚本——那是整库 dump 的写法，
    那条通道走 split_dump_statements。
    """
    return _split_sql(text, with_delimiter=False)


def split_dump_statements(text: str) -> list:
    """按整库 dump 的规则切句：在分号切分之上认 DELIMITER 指令。

    mysqldump 导出存储过程 / 函数 / 触发器时会写 `DELIMITER ;;`，函数体里的
    分号不能当语句结束；DELIMITER 本身是客户端指令，不发给服务端。
    """
    return _split_sql(text, with_delimiter=True)


# 行首的 DELIMITER 指令；只认整行（后面除空白外没有别的内容）
_DELIMITER_RE = re.compile(r"[ \t]*DELIMITER[ \t]+(\S+)[ \t]*(?:\r?\n|$)", re.IGNORECASE)


def _looks_like_delimiter(text: str, at: int) -> bool:
    """这一行是不是 DELIMITER 指令：先做 9 个字符的比较，避免每行都拼一次缓冲。"""
    n = len(text)
    i = at
    while i < n and text[i] in " \t":
        i += 1
    return text[i:i + 9].upper() == "DELIMITER"


def _split_sql(text: str, with_delimiter: bool) -> list:
    stmts, buf = [], []
    i, n = 0, len(text)
    delim = ";"
    while i < n:
        if (with_delimiter and (i == 0 or text[i - 1] == "\n")
                and _looks_like_delimiter(text, i)
                and not has_executable_content("".join(buf))):
            m = _DELIMITER_RE.match(text, i)
            if m is not None:
                delim = m.group(1)
                buf = []
                i = m.end()
                continue
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
        if text.startswith(delim, i):
            s = "".join(buf).strip()
            if s:
                stmts.append(s)
            buf = []
            i += len(delim)
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


# ---------------------------------------------------------------------------
# 整库 dump：识别与按 dump 方式执行需要的分类
# ---------------------------------------------------------------------------

# mysqldump / MariaDB dump / phpMyAdmin 导出都会带这几句里的一句。只有识别成 dump
# 的文件才走"逐条提交、出错继续"那条通道；手写脚本仍按每文件一个事务执行。
_DUMP_HEAD_MARKERS = (
    "-- mysql dump",
    "-- mariadb dump",
    "-- mysql shell dump",
    "-- phpmyadmin sql dump",
    "enable the sandbox mode",
)
_DUMP_TAIL_MARKER = "-- dump completed"
_DUMP_HEAD_CHARS = 8192
_DUMP_TAIL_CHARS = 4096

_DUMP_NAV_RE = re.compile(r"(?is)^\s*(CREATE\s+DATABASE|DROP\s+DATABASE|USE)\b")


def is_dump_script(text: str) -> bool:
    """这份脚本是不是整库 dump：看抬头 / 结尾那几句导出工具才会写的话。

    判据故意用导出工具的固定抬头，而不是"含很多 SET"之类的猜测：
    走错通道的代价是事务语义与回滚承诺完全变样，宁可让没带抬头的手写脚本按普通脚本执行。
    """
    head = (text or "")[:_DUMP_HEAD_CHARS].lower()
    if any(marker in head for marker in _DUMP_HEAD_MARKERS):
        return True
    return _DUMP_TAIL_MARKER in (text or "")[-_DUMP_TAIL_CHARS:].lower()


def has_executable_content_dump(stmt: str) -> bool:
    """dump 模式的内容判据：`/*!40101 SET ... */` 是服务端真会执行的语句。

    普通块注释与 `/*M!...*/`（MariaDB 客户端沙箱标记）仍不算；`/*!999999\\- ...`
    这种只在客户端生效、发给服务端会变成空查询的写法也按没有内容处理。
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
            return True
        if (ch == "-" and nxt == "-") or ch == "#":
            j = stmt.find("\n", i)
            i = n if j == -1 else j
            continue
        if ch == "/" and nxt == "*":
            j = stmt.find("*/", i + 2)
            if stmt.startswith("/*!", i) and not stmt.startswith("/*M!", i):
                inner = stmt[i + 3:j if j != -1 else n]
                body = re.sub(r"^\d*", "", inner).strip()
                if body and not body.startswith("\\"):
                    return True
            i = n if j == -1 else j + 2
            continue
        return True
    return False


def dump_statements(text: str) -> list:
    """整库 dump 的可执行语句：认 DELIMITER，并保留 `/*!...*/` 版本注释语句。"""
    return [s for s in split_dump_statements(text) if has_executable_content_dump(s)]


def dump_skip_reason(stmt: str) -> str:
    """这句是 dump 里的库级导航语句吗，返回关键字（空串表示不是）。

    目标库由界面选择，dump 自带的 `CREATE DATABASE` / `DROP DATABASE` / `USE`
    不该改写现场选好的库；其中 DROP DATABASE 更不能执行，否则会把目标库整个删掉。
    语句开头常带着 `--` 注释块（真 dump 的 "Current Database" 那段就在 CREATE DATABASE 前面），
    所以先把开头的空白与注释剥掉再认；`/*!40000 DROP DATABASE ... */` 这类版本注释里的正文也要认。
    """
    s = str(stmt or "").strip()
    while s:
        if s.startswith("--") or s.startswith("#"):
            j = s.find("\n")
            s = "" if j == -1 else s[j + 1:].lstrip()
            continue
        if s.startswith("/*"):
            j = s.find("*/", 2)
            inner = s[2:j if j != -1 else len(s)]
            rest = "" if j == -1 else s[j + 2:].lstrip()
            if s.startswith("/*!") and not s.startswith("/*M!"):
                inner = re.sub(r"^\d*", "", inner[1:]).strip()
                m = _DUMP_NAV_RE.match(inner)
                return re.sub(r"\s+", " ", m.group(1).upper()) if m is not None else ""
            s = rest
            continue
        break
    m = _DUMP_NAV_RE.match(s)
    if m is None:
        return ""
    return re.sub(r"\s+", " ", m.group(1).upper())


# ---------------------------------------------------------------------------
# CREATE TABLE 正文解析：给「生成标准/简易 Excel 文件」那两颗按钮当数据源
# ---------------------------------------------------------------------------

# 表级约束行的开头。识别出来只为跳过它们，别把 KEY 的名字当成字段名
_CONSTRAINT_HEAD_RE = re.compile(
    r"^(PRIMARY\s+KEY|UNIQUE\s+(?:KEY|INDEX)?|FULLTEXT\s+(?:KEY|INDEX)?"
    r"|SPATIAL\s+(?:KEY|INDEX)?|FOREIGN\s+KEY|CONSTRAINT|CHECK|PERIOD|KEY|INDEX)\b",
    re.IGNORECASE)
# 表选项里的 COMMENT='xxx'（等号可有可无，引号单双都认，双写引号是转义）
_TABLE_COMMENT_RE = re.compile(r"""\bCOMMENT\b\s*=?\s*('(?:[^']|'')*'|"(?:[^"]|"")*")""",
                               re.IGNORECASE)
_TYPE_HEAD_RE = re.compile(r"^([A-Za-z][A-Za-z0-9_]*)\s*(\()?")
_PLAIN_KEYWORD_RE = re.compile(r"^[A-Z][A-Z0-9_]*$")


def _mask_comments(text: str) -> str:
    """返回与 text 等长、注释字符换成空格的副本。

    索引一一对应，所以结构（括号、逗号、关键字）在屏蔽副本上找，
    取值仍在原文上切——注释里写的括号与引号不会打乱配对。
    """
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch in ("'", '"', "`"):
            quote = ch
            i += 1
            while i < n:
                if text[i] == "\\" and quote != "`" and i + 1 < n:
                    i += 2
                    continue
                if text[i] == quote:
                    if i + 1 < n and text[i + 1] == quote:
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
            continue
        if (ch == "-" and nxt == "-") or ch == "#":
            j = text.find("\n", i)
            j = n if j == -1 else j
            for k in range(i, j):
                if not text[k].isspace():
                    out[k] = " "
            i = j
            continue
        if ch == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j == -1 else j + 2
            for k in range(i, j):
                if not text[k].isspace():
                    out[k] = " "
            i = j
            continue
        i += 1
    return "".join(out)


def _match_paren(masked: str, start: int) -> int:
    """masked[start] 是 '('，返回配对 ')' 的下标；配不上返回 -1。"""
    depth = 0
    for i in range(start, len(masked)):
        if masked[i] == "(":
            depth += 1
        elif masked[i] == ")":
            depth -= 1
            if depth == 0:
                return i
    return -1


def _split_top_level(body: str, masked: str) -> list:
    """按顶层逗号切开列定义与约束行；括号内（DECIMAL(10,2)、ENUM('a','b')）的逗号不算。"""
    out, depth, last = [], 0, 0
    for i, ch in enumerate(masked):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == "," and depth == 0:
            out.append(body[last:i])
            last = i + 1
    tail = body[last:]
    if tail.strip():
        out.append(tail)
    return out


def _first_top_level(masked: str, ch: str) -> int:
    """masked 里第一个不在引号/括号/注释中的 `ch` 的下标；找不到返回 len(masked)。

    屏蔽副本里引号是原样留着的（取值要靠它），所以这里还得自己认引号，
    不然 `COMMENT 'a;b'` 里的分号会把表选项截断。
    """
    depth = 0
    i, n = 0, len(masked)
    while i < n:
        c = masked[i]
        if c in ("'", '"', "`"):
            quote = c
            i += 1
            while i < n:
                if masked[i] == "\\" and quote != "`":
                    i += 2
                    continue
                if masked[i] == quote:
                    if i + 1 < n and masked[i + 1] == quote:
                        i += 2
                        continue
                    i += 1
                    break
                i += 1
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == ch and depth == 0:
            return i
        i += 1
    return n


def _leading_name(piece: str) -> str:
    """取一行定义开头的名字（反引号可有可无），返回 (名字, 剩下的部分)。"""
    s = piece.lstrip()
    off = len(piece) - len(s)
    if s.startswith("`"):
        end = s.find("`", 1)
        while end != -1 and end + 1 < len(s) and s[end + 1] == "`":   # `` 是转义
            end = s.find("`", end + 2)
        if end == -1:
            return "", s
        return s[1:end].replace("``", "`"), s[end + 1:]
    m = re.match(r"^([A-Za-z0-9_$]+)", s)
    if not m:
        return "", s
    return m.group(1), s[len(m.group(1)):]


def _normalize_type(raw: str) -> tuple:
    """DDL 里的类型串 -> (数据类型, 长度, 原样类型, 丢了什么)。

    元数据 Sheet 的读回端只认「一个关键字 + 纯数字长度」（_norm_meta_type /
    _norm_meta_length），所以这里必须按它能装下的形状写：
      - `int(11) unsigned zerofill` → INT + 11，丢掉的是 UNSIGNED、ZEROFILL
      - `varchar(64) character set utf8mb4` → VARCHAR + 64，丢掉的是字符集
      - `enum('a','b')` 这类括号里不是数字的，装进去会产出非法 DDL（只有 ENUM 没有取值），
        所以按 TEXT 导出
    丢掉的都写进"丢了什么"，由调用方拼成一句说明，不静默改语义。
    """
    s = (raw or "").strip()
    m = _TYPE_HEAD_RE.match(s)
    if not m:
        return "TEXT", None, s, "无法识别的类型"
    base = m.group(1).upper()
    length = None
    src = base
    rest = s
    if m.group(2):                                   # 类型后面紧跟括号
        masked = _mask_comments(s)
        open_at = masked.index("(", m.start(2))
        close = _match_paren(masked, open_at)
        if close == -1:
            return "TEXT", None, s, "类型括号没配对"
        args = s[open_at + 1:close].strip()
        rest = s[close + 1:].strip()
        src = "%s(%s)" % (base, args)
        if re.match(r"^\d+$", args):
            length = args
        elif re.match(r"^\d+\s*,\s*\d+$", args):
            length = "%s,%s" % (args.split(",")[0].strip(), args.split(",")[1].strip())
        else:
            return "TEXT", None, src, "取值列表 " + args
    dropped = []
    tail = rest.upper()
    for kw in ("UNSIGNED", "SIGNED", "ZEROFILL"):
        if re.search(r"\b%s\b" % kw, tail):
            dropped.append(kw)
    if (re.search(r"\bCHARACTER\s+SET\b", tail)
            or re.search(r"\bCOLLATE\b", tail)):
        dropped.append("字符集/排序规则")
    return base, length, src, "、".join(dropped)


def _column_comment(masked_tail: str, orig_tail: str) -> tuple:
    """取列注释，并从这一行剩下的部分里把它挖掉（免得 COMMENT 文本里的关键字被当修饰）。"""
    m = re.search(r"\bCOMMENT\b\s*=?", masked_tail, re.IGNORECASE)
    if not m:
        return "", orig_tail
    rest = orig_tail[m.end():].lstrip()
    quote = rest[:1]
    if quote not in ("'", '"'):
        return "", orig_tail
    masked_rest = _mask_comments(rest)
    end = masked_rest.find(quote, 1)
    while end != -1 and end + 1 < len(masked_rest) and masked_rest[end + 1] == quote:
        end = masked_rest.find(quote, end + 2)
    if end == -1:
        return "", orig_tail
    raw = rest[:end + 1]
    value = raw[1:-1].replace(quote * 2, quote)
    return value, orig_tail[:m.start()]


DUP_NOTE = "表名 %s 在本脚本里被定义了多次，只按第一处的列定义导出"

def parse_create_tables(text: str) -> list:
    """把脚本里的 CREATE TABLE 解析成表定义，供「生成标准/简易 Excel 文件」直接用。

    返回按首次出现顺序排列的表，形状与工具自己解析 Excel 时那一份对齐，
    所以 `core.meta_export` 不用改一行就能吃：
        {"table_name","table_comment","columns":[{"field","comment","type",
          "primary_key","nullable"}],"notes":[说明这句为什么被近似]}
    表名取的是**当前文本里的名字**，所以页面上改过前缀再点导出，产物就是新表名。

    只解析到能填进元数据 Sheet 的程度：列注释、主键、可空、类型与长度。
    DEFAULT / AUTO_INCREMENT / ON UPDATE / 生成列规则这些不进交付件，
    近似掉的（UNSIGNED、ENUM 等）会在该表的 notes 里逐列点名，不静默改语义。
    """
    masked_all = _mask_comments(text)
    tables, seen = [], {}
    for m in _CREATE_TABLE_RE.finditer(masked_all):
        name = m.group(1)
        # 列定义的 '(' 必须落在本条语句里：往后找会把下一条 CREATE TABLE 的括号当成自己的，
        # LIKE / AS SELECT 这种没有列定义的写法就会带着别人的列被导出（界限=顶层分号或下一条 CREATE）
        nxt = _CREATE_TABLE_RE.search(masked_all, m.end())
        limit = min(m.end() + _first_top_level(masked_all[m.end():], ";"),
                    nxt.start() if nxt else len(masked_all))
        open_at = masked_all.find("(", m.end())
        if open_at == -1 or open_at >= limit:
            entry = {"table_name": name, "table_comment": "", "columns": [],
                     "notes": ["这条 CREATE TABLE 后面没有列定义"
                               "（LIKE 或 AS SELECT 写法），无法生成元数据"]}
            if name in seen:
                continue
            seen[name] = entry
            tables.append(entry)
            continue
        # 表名与 '(' 之间若夹着别的关键字（如 CREATE TABLE x (a INT) PARTITION BY ...），
        # 找到的第一个左括号仍是列定义体；正文按配对括号取
        close = _match_paren(masked_all, open_at)
        if close == -1:
            continue
        body = text[open_at + 1:close]
        body_masked = masked_all[open_at + 1:close]
        stop = close + 1 + _first_top_level(masked_all[close + 1:], ";")
        m2 = _CREATE_TABLE_RE.search(masked_all, close + 1)
        if m2 is not None:
            stop = min(stop, m2.start())   # 表选项到本条语句末尾为止
        options = text[close + 1:stop]
        options_masked = masked_all[close + 1:stop]
        table_comment = ""
        cm = _TABLE_COMMENT_RE.search(options_masked)
        if cm:
            raw = options[cm.start(1):cm.end(1)]
            table_comment = raw[1:-1].replace(raw[:1] * 2, raw[:1])

        columns, notes = [], []
        pk_from_constraint = []
        for piece in _split_top_level(body, body_masked):
            if not piece.strip():
                continue
            head = piece.lstrip()
            masked_piece = _mask_comments(piece)
            head_masked = masked_piece.lstrip()
            if _CONSTRAINT_HEAD_RE.match(head_masked):
                pm = re.match(r"^PRIMARY\s+KEY\b", head_masked, re.IGNORECASE)
                if pm:
                    o = head_masked.index("(")
                    c = _match_paren(head_masked, o)
                    if c != -1:
                        for token in head_masked[o + 1:c].split(","):
                            col_name, _rest = _leading_name(token)
                            if col_name:
                                pk_from_constraint.append(col_name)
                continue
            field, tail = _leading_name(piece)
            if not field:
                continue
            masked_tail = masked_piece[len(masked_piece) - len(tail):]
            comment, tail = _column_comment(masked_tail, tail)
            masked_tail = _mask_comments(tail)
            base, length, src, lost = _normalize_type(tail)
            typ = base if length is None else "%s(%s)" % (base, length)
            inline_pk = bool(re.search(r"\bPRIMARY\s+KEY\b", masked_tail, re.IGNORECASE))
            not_null = bool(re.search(r"\bNOT\s+NULL\b", masked_tail, re.IGNORECASE))
            columns.append({"field": field, "comment": comment, "type": typ,
                            "primary_key": inline_pk, "nullable": not not_null,
                            "_lost": lost})
            if lost:
                notes.append("列 %s 的 %s 装不进元数据 Sheet，按 %s 导出（丢了 %s）"
                             % (field, src, typ, lost))
        if pk_from_constraint:
            wanted = set(x.lower() for x in pk_from_constraint)
            for c in columns:
                if c["field"].lower() in wanted:
                    c["primary_key"] = True
        for c in columns:
            if c["primary_key"]:
                c["nullable"] = False     # 主键建表时一定是 NOT NULL
        entry = {"table_name": name, "table_comment": table_comment,
                 "columns": [{k: v for k, v in c.items() if k != "_lost"} for c in columns],
                 "notes": notes}
        if name in seen:
            # 第二处的定义不导出，但也不静默：说明挂在第一处那条上，
            # 日志与金标准都读得到，用户不至于拿着"少了一列"的交付件去查数据库
            seen[name]["notes"].append(DUP_NOTE % name)
            continue
        seen[name] = entry
        tables.append(entry)
    return tables
