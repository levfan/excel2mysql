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


_DROP_HEAD_RE = re.compile(r"(?is)^DROP\s+TABLE\b(.*)$")
_DROP_IF_EXISTS_RE = re.compile(r"(?is)^\s*IF\s+EXISTS\b")
_DROP_TARGET_ITEM_RE = re.compile(
    r"^`?[A-Za-z0-9_$]+`?(?:\s*\.\s*`?[A-Za-z0-9_$]+`?)?$")


def _drop_key(target: str) -> str:
    """`db`.`t` / db.t / `t` 归成同一个比对键：只去反引号与空白，大小写留着。

    反引号不影响 MySQL 认哪张表；大小写在 Linux 上是两张表，所以不敢不分。
    比对只用来判断"脚本自己删过没有"，键取不上就是多补一句 DROP（原有行为），
    不会因为归一过头而少删。
    """
    return re.sub(r"[`\s]", "", target)


def _dropped_targets(stmt: str) -> list:
    """这一句是整句 DROP TABLE 的话，返回它删掉的表名键；掺了认不出的写法就整句不算。"""
    masked = _mask_comments(stmt).strip()
    m = _DROP_HEAD_RE.match(masked)
    if not m:
        return []
    rest = _DROP_IF_EXISTS_RE.sub("", m.group(1), count=1).strip().rstrip(";").strip()
    keys = []
    for part in rest.split(","):
        part = part.strip()
        if not part:
            continue
        if not _DROP_TARGET_ITEM_RE.match(part):
            return []
        keys.append(_drop_key(part))
    return keys


def prepend_drop_tables(text: str) -> tuple:
    """给执行副本开头的每个建表目标补一句 DROP TABLE IF EXISTS。

    只在「覆盖已存在表」勾选后由执行动作调用，不写回脚本正文。
    返回 (新文本, 表名列表)；没有要先删的表时原样返回。
    只认整句以 CREATE TABLE 开头的语句：注释里的建表、字符串里的建表都不该被删，
    这是删除动作，判据必须比"清单里显示哪些表"更严。
    脚本自己已经写了 DROP TABLE 的表不再补第二句：补出来的那一批排在副本最前面，
    会跑在整库 dump 自带的 /*!40014 SET ... FOREIGN_KEY_CHECKS=0 */; 之前，
    被外键引用的父表在那之前删不掉，现场看到的就是 MySQL 3730。
    顺序按出现顺序倒过来，近似"先删子表再删父表"。
    """
    dropped = set()
    for stmt in split_sql_statements(text):
        dropped.update(_dropped_targets(stmt))
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
        key = _drop_key(target)
        if key in dropped or key in seen:
            continue
        seen.add(key)
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


_COL_COMMENT_RE = re.compile(r"(?i)\bCOMMENT\s+")
_COL_TYPE_RE = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_$]*(?:\s*\([^()]*\))?)")
_FIELD_BAD_RE = re.compile(r"[\s`;\"'\\]")


def _paren_tokens(masked_piece: str) -> list:
    """约束行里第一个括号名单中的列名（PRIMARY KEY (a,b) / KEY `i` (`c`)）。"""
    open_at = masked_piece.find("(")
    if open_at == -1:
        return []
    close = _match_paren(masked_piece, open_at)
    if close == -1:
        return []
    names = []
    for token in masked_piece[open_at + 1:close].split(","):
        name, _rest = _leading_name(token)
        if name:
            names.append(name)
    return names


def _column_comment_span(masked_piece: str, piece: str):
    """列定义里 COMMENT 字面量的 (起, 止)；只有关键字没有字面量、或在字符串里，返回 None。"""
    for m in _COL_COMMENT_RE.finditer(masked_piece):
        i = m.end()
        if i >= len(piece) or piece[i] not in ("'", '"'):
            continue
        quote = piece[i]
        j = i + 1
        while j < len(piece):
            if piece[j] == "\\" and quote != "`":
                j += 2
                continue
            if piece[j] == quote:
                if j + 1 < len(piece) and piece[j + 1] == quote:
                    j += 2
                    continue
                return (m.start(), j + 1)
            j += 1
    return None


def edit_column(text: str, table_name: str, field: str, new_field: str = None,
                new_comment: str = None, new_type: str = None) -> tuple:
    """改脚本里某一列的字段名 / 字段注释 / 数据类型，返回 (新文本, 是否改动, 原因)。

    只动那一行的那一段：字段名换标识符、类型换名字后面的那个类型词（UNSIGNED、
    NOT NULL、DEFAULT、AUTO_INCREMENT 这些原样留着），注释换 COMMENT 的字面量
    （原来没有就补在行尾）。改完这一行以外的字节级内容一个都不变。

    被主键或索引列名单引用的字段拒绝改名：索引行里写的还是旧名字，
    放过去就会生成一句引用不存在列的 DDL，报错要等建表时才炸，比在这儿拒绝难查得多。
    同名表被写两遍只改第一处，与表注释、元数据导出「同名表只按第一处」是同一条规则。
    """
    name = str(table_name or "").strip().strip("`")
    old = str(field or "").strip().strip("`")
    if not name or not old:
        return text, False, "没给表名或字段名"
    edits = {"field": new_field, "comment": new_comment, "type": new_type}
    if all(v is None for v in edits.values()):
        return text, False, "没有要改的内容"
    want_field = edits["field"] is not None
    if want_field:
        nf = str(edits["field"]).strip().strip("`")
        if not nf:
            return text, False, "字段名不能为空"
        if _FIELD_BAD_RE.search(nf):
            return text, False, "字段名不能含空格、引号、反引号或分号：%s" % nf
        if len(nf) > 64:
            return text, False, "字段名超过 64 个字符：%s" % nf
    want_type = edits["type"] is not None
    if want_type:
        nt = str(edits["type"]).strip()
        if not nt:
            return text, False, "数据类型不能为空"
        if _FIELD_BAD_RE.search(nt.replace("(", "").replace(")", "").replace(",", "")):
            return text, False, "数据类型写法不对：%s" % nt
    masked = _mask_comments(text)
    for m in _CREATE_TABLE_RE.finditer(masked):
        if m.group(1) != name:
            continue
        open_at = masked.find("(", m.end())
        if open_at == -1:
            return text, False, "那句 CREATE TABLE 没有列定义（LIKE / AS SELECT 写法）"
        close = _match_paren(masked, open_at)
        if close == -1:
            return text, False, "那句 CREATE TABLE 的括号配不上，无法定位列定义"
        body, body_masked = text[open_at + 1:close], masked[open_at + 1:close]
        # 先按顶层逗号切出每一行，同时记下它在原文里的偏移：替换要落在原文上
        pieces, at = [], 0
        for raw in _split_top_level(body, body_masked):
            pieces.append((at, raw))
            at += len(raw) + 1                      # +1 是那个分隔逗号
        constraint_names, target = [], None
        for start, raw in pieces:
            if not raw.strip():
                continue
            masked_raw = _mask_comments(raw)
            head = masked_raw.lstrip()
            if _CONSTRAINT_HEAD_RE.match(head):
                constraint_names.extend(_paren_tokens(head))
                continue
            got, tail = _leading_name(raw)
            if got and got.lower() == old.lower():
                target = (start, raw, tail)
        if target is None:
            return text, False, "表「%s」里没有字段「%s」（可能被改写或删除了）" % (name, old)
        if want_field:
            nf = str(edits["field"]).strip().strip("`")
            if nf.lower() != old.lower() and old.lower() in [c.lower() for c in constraint_names]:
                return text, False, ("字段「%s」被主键或索引列名单引用，改名会留下引用旧列的定义"
                                     % old)
        start, raw, tail = target
        piece = raw
        ws = len(piece) - len(piece.lstrip())
        name_at = ws
        name_end = ws + len(piece.lstrip()) - len(tail)     # tail 是名字之后剩下的部分
        tail_at = name_end                                   # 类型词在这个偏移之后
        want_c = edits["comment"] is not None
        # 三处替换按从右到左落笔，先动的段落不会挪后动的偏移
        spans = []
        if want_c:
            literal = "'" + str(edits["comment"] or "").strip().replace("'", "''") + "'"
            span = _column_comment_span(_mask_comments(piece), piece)
            if span is None:
                cut = len(piece.rstrip())          # 插在内容末尾，不落进尾部换行里
                spans.append((cut, cut, " COMMENT " + literal))
            else:
                spans.append((span[0], span[1], "COMMENT " + literal))
        if want_type:
            mt = _COL_TYPE_RE.match(tail)
            if mt is None:
                return text, False, "读不出字段「%s」的类型，先别改" % old
            spans.append((tail_at + mt.start(1), tail_at + mt.end(1),
                          str(edits["type"]).strip()))
        if want_field:
            nf = str(edits["field"]).strip().strip("`")
            if nf.lower() != old.lower():
                quoted = piece[name_at:name_at + 1] == "`"
                new_name = "`" + nf.replace("`", "``") + "`" if quoted else nf
                spans.append((name_at, name_end, new_name))
        for a, b, sub in sorted(spans, key=lambda s: s[0], reverse=True):
            piece = piece[:a] + sub + piece[b:]
        if piece == raw:
            return text, False, "填回去的和原来一样，脚本未改"
        return text[:open_at + 1 + start] + piece + text[open_at + 1 + start + len(raw):], True, ""
    return text, False, "脚本里找不到表「%s」的 CREATE TABLE" % name



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


# ---------------------------------------------------------------------------
# SQL 格式化：编辑区那颗 Ctrl+Alt+L 走这里，示例库的正文也按同一套规则排过。
#
# 只做三件事：关键字大写、深度 0 的从句各起一行、CREATE / ALTER TABLE 那份字段
# 清单一个字段一行。语义一个字都不改——判据是"格式化前后的语句切分逐条相同"，
# 这条与幂等（格式化两次等于一次）一起钉在跨栈语料里（SqlDiffer 的 format 分支）。
# 整库 dump 那种带 DELIMITER 的正文由调用方先按 is_dump_script 拦下并说明理由。
# Java 侧同构实现在 core/SqlScript.java 的 formatSql。

# 只有裸词才查这张表：字符串、反引号名、注释里的同名词一律不动
SQL_FORMAT_KEYWORDS = frozenset((
    "ADD", "AFTER", "ALL", "ALTER", "AND", "AS", "ASC", "AUTO_INCREMENT", "BEGIN",
    "BETWEEN", "BY", "CALL",
    "CASE", "CHANGE", "CHAR", "CHARACTER", "CHARSET", "COLLATE", "COLUMN", "COMMENT",
    "COMMIT",
    "CONCAT", "CONDITION", "CONSTRAINT", "CONTINUE", "CONVERT", "CREATE", "CROSS",
    "DATABASE", "DEALLOCATE", "DEFAULT", "DELETE", "DESC", "DESCRIBE", "DISTINCT",
    "DO", "DROP", "ELSE", "END", "ENGINE", "ESCAPE", "EXCEPT", "EXCLUDE", "EXECUTE",
    "EXISTS", "EXPLAIN", "FETCH", "FIRST", "FOR", "FORCE", "FOREIGN", "FROM",
    "FULL", "FUNCTION", "GRANT", "GROUP", "HAVING", "IF", "IGNORE", "IN",
    "INDEX", "INHERITS", "INNER", "INSERT", "INTERSECT", "INTERVAL", "INTO",
    "IS", "JOIN", "KEY", "LAST", "LEFT", "LIKE", "LIMIT", "MODE", "MODIFY",
    "NAMES", "NATURAL", "NOT", "NULL", "NULLS", "OF", "OFFSET", "ON", "OPTION", "OR",
    "ORDER", "OUTER", "PARTITION", "PRIMARY", "PROCEDURE", "RANGE", "READ",
    "REFERENCES", "REGEXP", "RENAME", "REPEAT", "REPLACE", "RETURN", "REVOKE",
    "RIGHT", "RLIKE", "ROLLBACK", "ROW", "ROWS", "SCHEMA", "SELECT", "SEPARATOR",
    "SET", "SHARE", "SHOW", "STRAIGHT_JOIN", "TABLE", "TEMPORARY", "THEN", "TO",
    "TRUNCATE", "UNION", "UNIQUE", "UNSIGNED", "UPDATE", "USING", "VALUES",
    "WHEN", "WHERE", "WITH", "WRITE", "ZEROFILL",
))

# 类型名一起大写：`varchar(64)` 排成 `VARCHAR(64)` 才与工具自己生成的 DDL 同形。
# 这张表里没有 utf8mb4 / InnoDB / general_ci 那类"值"——它们是要发给服务端的字符集与
# 引擎名，改大小写虽不改变含义，但读起来像被工具动过手脚（DDL 预览的高亮表同理）。
SQL_FORMAT_TYPES = frozenset((
    "VARCHAR", "CHAR", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "BLOB",
    "TINYBLOB", "MEDIUMBLOB", "LONGBLOB", "BINARY", "VARBINARY", "DECIMAL",
    "NUMERIC", "FLOAT", "DOUBLE", "REAL", "INT", "INTEGER", "TINYINT",
    "SMALLINT", "MEDIUMINT", "BIGINT", "BIT", "BOOL", "BOOLEAN", "DATE",
    "DATETIME", "TIMESTAMP", "TIME", "YEAR", "JSON",
))
# 实际要大写的词 = 关键字 + 类型
SQL_FORMAT_UPPER = SQL_FORMAT_KEYWORDS | SQL_FORMAT_TYPES
# 表选项那几位的等号贴紧写（`ENGINE=InnoDB`），与 SHOW CREATE TABLE 同形；
# 比较运算的等号照旧留空格（`WHERE a = 1`）
SQL_FORMAT_TIGHT_EQ = frozenset(("ENGINE", "CHARSET", "COLLATE", "COMMENT",
                                 "AUTO_INCREMENT", "KEY_BLOCK_SIZE"))

# 这些词后面紧跟的左括号是函数/类型的括号，要贴紧写；其余（关键字与表名）留一格
SQL_FORMAT_FUNCS = frozenset((
    "COUNT", "SUM", "AVG", "MIN", "MAX", "GROUP_CONCAT", "IFNULL", "NULLIF",
    "COALESCE", "CONCAT", "CONCAT_WS", "DATE_FORMAT", "STR_TO_DATE", "NOW",
    "CURDATE", "CURTIME", "SYSDATE", "UNIX_TIMESTAMP", "FROM_UNIXTIME", "DATE",
    "TIMESTAMP", "YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND", "DATABASE",
    "SCHEMA", "VERSION", "USER", "CURRENT_USER", "SESSION_USER", "LENGTH",
    "CHAR_LENGTH", "BIT_LENGTH", "OCTET_LENGTH", "LEFT", "RIGHT", "SUBSTRING",
    "SUBSTRING_INDEX", "UPPER", "LOWER", "TRIM", "LTRIM", "RTRIM", "REPLACE",
    "REVERSE", "SPACE", "LPAD", "RPAD", "ABS", "CEIL", "CEILING", "FLOOR",
    "ROUND", "TRUNC", "MOD", "POW", "POWER", "SQRT", "RAND", "GREATEST",
    "LEAST", "CAST", "CONVERT", "MD5", "SHA1", "SHA2", "UUID", "JSON_EXTRACT",
    "JSON_UNQUOTE", "FOUND_ROWS", "ROW_COUNT", "LAST_INSERT_ID", "CONNECTION_ID",
    "SLEEP", "BENCHMARK", "FIELD", "INET_ATON", "INET6_ATON",
))
# 深度 0 上这些词一起句就另起一行
_SQL_BREAK_WORDS = frozenset((
    "SELECT", "FROM", "WHERE", "HAVING", "LIMIT", "OFFSET", "UNION", "INTERSECT",
    "EXCEPT", "INSERT", "UPDATE", "DELETE", "SET", "VALUES", "ON", "USING", "JOIN",
))
# GROUP / ORDER 只有紧跟 BY 才算从句，否则一列真叫 order 的写法会被腰斩
_SQL_PAIR_WORDS = frozenset(("GROUP", "ORDER"))
# LEFT / RIGHT 这些打头的连接写法，要等到后面真出现 JOIN 才换行
_SQL_JOIN_HEADS = frozenset(("LEFT", "RIGHT", "INNER", "FULL", "OUTER", "CROSS",
                             "NATURAL", "STRAIGHT_JOIN"))
# AND / OR 只在条件类从句里换行：SELECT 列表里 `a AND b` 是一个表达式，拆开反而读不断
_SQL_COND_CLAUSES = frozenset(("WHERE", "ON", "HAVING"))
# 两个符号字符合起来是一个运算符：`<=` `>=` `<>` `!=` `||` `->`。
# 分开发就会写出 `amount < = 0`，那是另一个意思都读不出来的样子。
_SQL_OP_HEAD = frozenset("<>=!|-")
_SQL_OP_TAIL = frozenset("=<>|")
_SQL_INDENT = "  "
# 超过这一列就在下一个逗号处折行（编辑区不折行，横向拖动比竖向滚动难读）
_SQL_WRAP_COL = 100


def _sql_tokens(text):
    """切成 (种类, 原文) 的 token：种类 ∈ word / num / str / cmt / punct / ws。

    引号与注释的走法与 `_split_sql` 逐条对齐（反斜杠转义在反引号里不算、双写引号算
    一个字符、行注释不吃行末那个换行）——两处词法一旦不一致，格式化就会把语句切歪，
    而"切分不变"正是这条功能的判据。
    """
    out = []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch in " \t\r\n":
            j = i
            while j < n and text[j] in " \t\r\n":
                j += 1
            out.append(("ws", text[i:j]))
            i = j
            continue
        if ch in ("'", '"', "`"):
            quote = ch
            j = i + 1
            while j < n:
                if text[j] == "\\" and quote != "`" and j + 1 < n:
                    j += 2
                    continue
                if text[j] == quote:
                    if j + 1 < n and text[j + 1] == quote:
                        j += 2
                        continue
                    j += 1
                    break
                j += 1
            out.append(("str", text[i:j]))
            i = j
            continue
        if (ch == "-" and nxt == "-") or ch == "#":
            j = text.find("\n", i)
            j = n if j == -1 else j
            out.append(("cmt", text[i:j]))
            i = j
            continue
        if ch == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j == -1 else j + 2
            out.append(("cmt", text[i:j]))
            i = j
            continue
        if ch == "@":
            # 用户变量与会话变量：@@version、@db、@@GLOBAL.x——整串一个 token，
            # 拆开会变成 `@ @ VERSION` 那种读不懂的样子（探针实测抓到的）
            j = i + 1
            if j < n and text[j] == "@":
                j += 1
            while j < n and (text[j].isalnum() or text[j] in "_$."):
                j += 1
            out.append(("var", text[i:j]))
            i = j
            continue
        if ch.isalpha() or ch == "_" or (ch.isalnum() and ch not in "0123456789"):
            j = i
            while j < n and (text[j].isalnum() or text[j] in "_$"):
                j += 1
            out.append(("word", text[i:j]))
            i = j
            continue
        if ch.isdigit():
            j = i
            while j < n and (text[j].isalnum() or text[j] in "_$."):
                j += 1
            out.append(("num", text[i:j]))
            i = j
            continue
        out.append(("punct", ch))
        i += 1
    return out


def _sql_next_word(tokens, at):
    """下一个非空白 token 的原文（大写），用来判 GROUP BY / ORDER BY / x JOIN 这种两词从句。"""
    for k in range(at + 1, len(tokens)):
        kind, text = tokens[k]
        if kind == "ws":
            continue
        return text.upper() if kind in ("word", "num") else ""
    return ""


def _sql_join_ahead(tokens, at):
    """从 at 往后是不是 `LEFT [OUTER] JOIN` 这一串：只有真接到 JOIN 才换行。"""
    for k in range(at + 1, len(tokens)):
        up = tokens[k][1].upper()
        if tokens[k][0] == "ws" or up in ("OUTER", "INNER"):
            continue
        return up == "JOIN"
    return False


def _sql_head_is_table_def(seen):
    """这个左括号之前，是不是只写过 `CREATE [TEMPORARY] TABLE [IF NOT EXISTS] 表名`
    或 `ALTER TABLE 表名`。

    只认这一种开头，是为了不把 `VARCHAR(64)`、`ADD INDEX idx (col)` 里的括号
    当成字段清单的开头展开——探针实测过：按"语句头有 CREATE/ALTER TABLE"判就会中招。
    """
    # 空白之外全算一个位置：表名可以写成 `db`.`tbl`，点号也是其中一格，
    # 把它滤掉就会把限定名误判成"表名之后还有别的东西"，清单不展开了
    # （这条是跨栈语料抓出来的：Java 侧按原意实现，Python 侧漏了）
    words = [t for k, t in seen if k != "ws"]
    if not words:
        return False
    i = 0
    if words[0].upper() == "CREATE":
        i = 1
        if i < len(words) and words[i].upper() in ("TEMPORARY", "UNLOGGED"):
            i += 1
        if i >= len(words) or words[i].upper() != "TABLE":
            return False
        i += 1
        if (i + 2 < len(words) and words[i].upper() == "IF"
                and words[i + 1].upper() == "NOT" and words[i + 2].upper() == "EXISTS"):
            i += 3
    elif words[0].upper() == "ALTER":
        i = 1
        if i >= len(words) or words[i].upper() != "TABLE":
            return False
        i += 1
    else:
        return False
    # 表名（可以带 `db`.`tbl` 这种点号限定，点号前后的段都不算空白 token）
    if i >= len(words):
        return False
    return i + 1 >= len(words) or words[i + 1] == "."


def _format_one_statement(tokens):
    """一句（不含结尾分号）排成若干行。"""
    sig = [t for t in tokens if t[0] != "ws"]
    if not sig:
        return []
    lines = []
    cur = []
    depth = 0
    clause = ""
    listed = False          # 字段清单是否已经展开过（只展开第一处）
    type_paren = []         # 每层括号是不是"类型的参数表"（VARCHAR(64) / DECIMAL(20,4)）

    def indent_now():
        return _SQL_INDENT if (listed and depth >= 1) else ""

    def flush():
        if cur:
            lines.append(indent_now() + "".join(cur).strip())
            del cur[:]

    def gap(text, prev_kind, prev_up, tight_eq, tight_comma, prev_punct,
            table_name=False):
        """这个 token 前面要不要留空格。

        六条：标点贴前；点号两边贴紧；函数与类型的左括号贴紧（`VARCHAR(64)`、
        `count(1)`）；类型参数表里的逗号后面不补空格（`DECIMAL(20,4)`，与工具自己
        生成的 DDL 逐字同形）；表选项的等号两边贴紧（`ENGINE=InnoDB`，与 SHOW CREATE
        TABLE 同形），比较运算的等号照旧留一格；两个运算符字符合在一起是一个符号
        （`<=`、`<>`、`!=`、`||`、`->`），中间不能插空格。
        `table_name` 是"这个左括号前写的是表名"——表名与左括号之间留一格，
        与 `build_ddl` 生成的形状一致；不认这一条就会写出 `tmp_customer(`。
        """
        if not cur:
            return ""
        if tight_eq or tight_comma:
            return ""
        head = cur[-1].strip()[-1:] if cur[-1].strip() else ""
        if text.startswith(".") or head == "." or text[0] in ",);" or head == "(":
            return ""
        if (prev_punct in _SQL_OP_HEAD and len(text) == 1
                and text in _SQL_OP_TAIL):
            return ""
        if text == "=" and prev_kind == "word" and prev_up in SQL_FORMAT_TIGHT_EQ:
            return ""
        if text == "(" and prev_kind == "word" and not table_name:
            if prev_up in SQL_FORMAT_FUNCS or prev_up in SQL_FORMAT_TYPES:
                return ""
            if prev_up not in SQL_FORMAT_UPPER:
                return ""
        return " "

    for idx, (kind, text) in enumerate(sig):
        up = text.upper() if kind == "word" else ""
        prev_kind, prev_text = sig[idx - 1] if idx else ("", "")
        prev_up = prev_text.upper() if prev_kind == "word" else ""
        prev2_kind, prev2_text = sig[idx - 2] if idx >= 2 else ("", "")
        prev_punct = prev_text if prev_kind == "punct" else "\""
        # 上一个 token 是等号、再上一个正好是表选项那几位 → 这一位也贴紧
        # （等号是 punct，prev_up 只对 word 有值，这里必须拿原文比）
        tight_eq = prev_text == "=" and prev2_text.upper() in SQL_FORMAT_TIGHT_EQ
        tight_comma = prev_text == "," and bool(type_paren) and type_paren[-1]
        # 关键字与类型大写；字符串、反引号名、注释、变量一律原样
        emit = up if kind == "word" and up in SQL_FORMAT_UPPER else text
        if kind == "punct" and text == "(":
            head_is_table = (depth == 0 and not listed
                             and _sql_head_is_table_def(sig[:idx]))
            if head_is_table:
                listed = True
                cur.append(gap(text, prev_kind, prev_up, tight_eq, tight_comma,
                               prev_punct, True) + text)
                flush()
                depth += 1
                type_paren.append(False)
                continue
            depth += 1
            type_paren.append(prev_kind == "word" and prev_up in SQL_FORMAT_TYPES)
        elif kind == "punct" and text == ")":
            if depth == 1 and listed:
                flush()               # 最后一个字段先成行，缩进还在清单那一档
            depth = max(0, depth - 1)
            if type_paren:
                type_paren.pop()
        elif kind == "punct" and text == "," and depth == 1 and listed:
            cur.append(gap(emit, prev_kind, prev_up, tight_eq, tight_comma, prev_punct) + emit)
            flush()
            continue

        break_word = False
        if kind == "word" and cur:
            join_head = (up in _SQL_JOIN_HEADS and _sql_join_ahead(sig, idx)) or (
                up == "JOIN" and prev_up not in _SQL_JOIN_HEADS)
            cond_chain = up in ("AND", "OR") and clause in _SQL_COND_CLAUSES
            if depth == 0:
                break_word = (
                    up in _SQL_BREAK_WORDS and up != "JOIN"
                    and not (up == "SET" and prev_up == "CHARACTER")
                    or (up == "JOIN" and join_head)
                    or join_head
                    or (up in _SQL_PAIR_WORDS and _sql_next_word(sig, idx) == "BY")
                    or cond_chain)
            else:
                # 括号里的条件链也拆开：编辑器不折行，一条 WHERE 里三四个 OR 挤
                # 在一行就要横向拖两百多列才看完
                break_word = cond_chain
        if break_word:
            flush()
        elif (cur and prev_text == "," and depth <= 1
                and len("".join(cur)) > _SQL_WRAP_COL):
            # 超过这一列就在下一个逗号处折行：编辑器不折行，一条二十列的 SELECT
            # 清单挤成一行要横向拖两百多格。只在这一条已经够长时才折，
            # 短清单照旧留在一行，读起来不碎。
            flush()
        if kind == "word" and depth == 0 and up in _SQL_BREAK_WORDS:
            # 只在从句关键词上更新"现在在哪一段"：按每个词都覆盖的话，
            # `WHERE a = 1 AND ...` 里走到 AND 时 clause 已经变成表名了
            clause = up

        if cur:
            cur.append(gap(emit, prev_kind, prev_up, tight_eq, tight_comma, prev_punct) + emit)
        else:
            cur.append(emit)
        if kind == "cmt" and (text.startswith("--") or text.startswith("#")):
            flush()
    flush()
    return lines


def format_refusal(text: str) -> str:
    """这段文本该不该拒绝格式化，返回拒绝理由（空串表示可以排）。

    整库 dump 的写法不能排：`DELIMITER ;;` 里的分号是客户端指令的一部分，按普通
    语句切就会把导出文件的收尾改坏；抬头/结尾那几句导出工具才会写的话同理。
    调用方拿到非空理由要原话回给用户，不能静默什么都不做。
    Java 侧同构在 core/SqlScript.java 的 formatRefusal。
    """
    if not (text or "").strip():
        return "这段是空的，没有可排的内容"
    if _DELIMITER_RE.search(text):
        return "这是整库 dump 的写法（带 DELIMITER 指令），格式化会改坏收尾"
    if is_dump_script(text):
        return "这是整库 dump 文件，格式化会改坏导出工具写的收尾"
    return ""


def format_sql(text: str) -> str:
    """把一段 SQL 排整齐：关键字大写、从句各起一行、字段清单一行一个。

    三条硬性质，全部钉在跨栈语料与用例集里：
    ① 幂等——格式化两次与一次结果相同；
    ② 不改语义——`split_sql_statements` 切出来的语句条数相同、逐条只差空白与
       关键字大小写（用例按"切分后去空白再比"来判）；
    ③ 两栈逐字节相同——Java 侧 `SqlScript.formatSql` 走同一条规则。
    """
    tokens = _sql_tokens(text)
    blocks = []
    buf = []
    for kind, piece in tokens:
        if kind == "punct" and piece == ";":
            block = _format_one_statement(buf)
            if block:
                block[-1] = block[-1] + ";"
                blocks.append(block)
            buf = []
            continue
        buf.append((kind, piece))
    tail = _format_one_statement(buf)
    if tail:
        blocks.append(tail)
    if not blocks:
        return text.strip()
    return "\n".join("\n".join(block) for block in blocks)


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
    """这一句里除了注释和空白还剩东西吗（两条执行通道共用这一份判据）。

    `split_sql_statements` 会把 `-- 只有注释` 这种整段是注释的片段也切成一条（它只按分号切，
    不管内容），而「SQL 执行器」要对用户说"只写注释也不算"——判据就得在这儿补上，
    否则会出现"切出来一条、执行时又说没得执行"，或者把空注释当成正经一句报"已提交 1 条"。
    走的是与切句同一套词法（引号 / 反引号 / -- 与 # 行注释 / 块注释）：
    字符串里的 `--` 不算注释，所以 `SELECT '-- x'` 仍然是一句真要执行的语句。

    版本注释 `/*!NNNNN ...*/` 要算：正文是服务端真会执行的语句，不是给人看的注释。
    导出工具把 SET NAMES、SET TIME_ZONE、SET FOREIGN_KEY_CHECKS=0 全写在这类语句里，
    判成注释就等于把这些设置悄悄丢掉——现场那份 MariaDB 整库 dump 走普通通道时
    1382 条只剩 679 条，外键检查因此一直开着，删被引用的父表就是 MySQL 3730。
    `/*M!...*/`（MariaDB 客户端沙箱标记）与 `/*!999999\\- ...`（发给服务端是空查询）
    只在客户端生效，仍按没有内容处理。
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
            if stmt.startswith("/*!", i) and not stmt.startswith("/*M!", i):
                inner = stmt[i + 3:j if j != -1 else n]
                body = re.sub(r"^\d*", "", inner).strip()
                if body and not body.startswith("\\"):
                    return True
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

_DUMP_NAV_RE = re.compile(r"(?is)^\s*DROP\s+DATABASE\b")
_DUMP_SWITCH_RE = re.compile(r"(?is)^\s*USE\s+([^\s;]+)")

# 剥注释用：版本注释 `/*!40101 SET ... */` 的正文服务端真会执行，摊平留下；
# 普通块注释与 MariaDB 客户端沙箱标记 `/*M!...*/` 整块去掉
_DUMP_VERSION_COMMENT_RE = re.compile(r"/\*!\d*\s*(.*?)\s*\*/", re.S)
_DUMP_BLOCK_COMMENT_RE = re.compile(r"/\*[^*]*(?:\*(?!/)[^*]*)*\*/", re.S)
_DUMP_LINE_COMMENT_RE = re.compile(r"(?m)^[ \t]*(?:--|#).*$")


def _dump_statement_head(stmt: str) -> str:
    """语句开头那截把注释摊平后的样子，只用来认第一个关键字。

    只截 300 字符：认的是语句开头，正文里出现的 `--` 或 `/*` 不该参与判定。
    """
    s = str(stmt or "")[:300]
    s = _DUMP_LINE_COMMENT_RE.sub("", s)
    s = _DUMP_VERSION_COMMENT_RE.sub(lambda m: m.group(1), s)
    s = _DUMP_BLOCK_COMMENT_RE.sub(" ", s)
    return re.sub(r"\s+", " ", s).strip()


def is_dump_script(text: str) -> bool:
    """这份脚本是不是整库 dump：看抬头 / 结尾那几句导出工具才会写的话。

    判据故意用导出工具的固定抬头，而不是"含很多 SET"之类的猜测：
    走错通道的代价是事务语义与回滚承诺完全变样，宁可让没带抬头的手写脚本按普通脚本执行。
    """
    head = (text or "")[:_DUMP_HEAD_CHARS].lower()
    if any(marker in head for marker in _DUMP_HEAD_MARKERS):
        return True
    return _DUMP_TAIL_MARKER in (text or "")[-_DUMP_TAIL_CHARS:].lower()


def dump_statements(text: str) -> list:
    """整库 dump 的可执行语句：认 DELIMITER，并保留 `/*!...*/` 版本注释语句。

    内容判据与普通通道共用 `has_executable_content`：两条通道只在"怎么切句"和
    "库级语句跳不跳"上不同，什么算一句必须一样——分开写过一次就分叉过一次，
    普通通道把版本注释当注释丢掉那回（MySQL 3730）就是这么来的。
    """
    return [s for s in split_dump_statements(text) if has_executable_content(s)]


def dump_skip_reason(stmt: str) -> str:
    """这句是 dump 里不该执行的语句吗，返回关键字（空串表示照常执行）。

    只有删库语句被拦下：`CREATE DATABASE` 与 `USE` 是 dump 在说"这些表建在哪个库里"，
    跳过它们等于把用户要导的那个库抹掉，表会散进界面随手选中的库。而 `DROP DATABASE`
    执行一次就把目标库整个删掉，风险不对等，仍然只跳过、不执行。
    语句开头常带着 `--` 注释块（真 dump 的 "Current Database" 那段就在建库语句前面），
    `/*!40000 DROP DATABASE ... */` 也要认，所以先把注释摊平再认第一个关键字。
    """
    return "DROP DATABASE" if _DUMP_NAV_RE.match(_dump_statement_head(stmt)) else ""


def dump_database_switch(stmt: str) -> str:
    """这句是 dump 里的切库语句（`USE ...`）吗，返回它切过去的库名（不是切库返回空串）。

    建库不改会话库，只有 `USE` 改；日志与收尾汇总要说的是"后续语句落在哪个库"，
    所以这里只认 `USE`。名字两边的反引号与引号都剥掉，库名里带 `-` 或 `.` 也照原样取。
    """
    m = _DUMP_SWITCH_RE.match(_dump_statement_head(stmt))
    if m is None:
        return ""
    return m.group(1).strip("`\"'")


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
