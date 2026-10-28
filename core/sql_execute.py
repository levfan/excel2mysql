"""「SQL 执行器」页签的函数体：把编辑区里那段文本切成语句逐条执行，产出文本结果。

与 Java 侧 core/SqlExecute.java 同一套文案与事务语义（全部成功才提交、中途出错整批回滚、
失败那句后面附 DDL 隐式提交的提醒）。两个页签/两条线如果回滚语义不一样，
现场按一个页签的经验去理解另一个就会出错。
"""
import re

from core.log_text import DDL_NO_ROLLBACK_NOTE, describe_db_error
from core.sql_script import has_executable_content, split_sql_statements
from core.text_width import clip, pad, width

PREVIEW_ROWS = 200
# 单个格子最多占这么多显示列。取 40 是因为治理体检一类查询里最宽的通常是
# 表名/列名/注释，40 列足够看清，又不至于让三十列的结果横向看不到边。
CELL_MAX = 40
TABLE_INDENT = "      "


def executable_statements(text):
    """切句再剔掉"整句只剩注释"的片段——执行器只认真正要执行的那几句。"""
    return [s for s in split_sql_statements(text) if has_executable_content(s)]


def _one_line(sql):
    s = re.sub(r" +", " ", sql.replace("\n", " ").replace("\r", " ")).strip()
    return s if len(s) <= 160 else s[:160] + "…（共 %d 字符）" % len(s)


def _cell(v):
    """一个取值 -> 格子里那段文字。

    NULL 要显式写出来（空着分不清是 NULL 还是空串），换行与制表符换成可见的转义，
    否则一条带换行的备注会把整张表顶散。
    """
    if v is None:
        return "NULL"
    s = v if isinstance(v, str) else str(v)
    s = s.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")
    return clip(s.replace("\t", "\\t"), CELL_MAX)


def render_table(columns, rows):
    """列名 + 若干行 -> 等宽表格的各行文本（不带缩进，由调用方补）。

    结果区是个纯文本框，能靠的只有对齐：按**显示宽度**（汉字占两列）把每列补到同宽，
    上下与表头下面各画一条分隔线。原来那种 `" | "` 直接拼一行，列宽一不一样就错行，
    混进中文之后连列名都对不上是哪一列。
    """
    if not columns:
        return []
    head = [_cell(c) for c in columns]
    body = [[_cell(v) for v in r] for r in rows]
    widths = []
    for i in range(len(head)):
        w = width(head[i])
        for r in body:
            if i < len(r):
                w = max(w, width(r[i]))
        widths.append(w if w > 0 else 1)
    rule = "+" + "+".join("-" * (w + 2) for w in widths) + "+"

    def row_line(vals):
        # 行比列少时补空格而不是 NULL：少给值不代表值是 NULL
        return "|" + "|".join(
            " " + pad(vals[i] if i < len(vals) else "", widths[i]) + " "
            for i in range(len(widths))) + "|"

    out = [rule, row_line(head), rule]
    for r in body:
        out.append(row_line(r))
    if body:
        out.append(rule)      # 零行的时候表头下面那条线就是收口，不重复画
    return out


def _as_text(v):
    """协议里回来的库名是 bytes，information_schema 里的是 str——两边拼一个键之前先归一。"""
    if v is None:
        return ""
    if isinstance(v, bytes):
        return v.decode("utf-8", "replace")
    return v if isinstance(v, str) else str(v)


def _column_origin(cur):
    """结果集每列的来源 (库名, 原表名, 原列名)；驱动没给这些就返回 None。

    MySQL 的列定义里根本不带注释，注释只能拿"哪张表的哪个列"去 information_schema 反查。
    PyMySQL 把协议原文挂在 cursor._result.fields 上，取 org_* 而不是 *name：
    `FROM 表 t` 这种带别名的写法里，table_name 是别名 t（拿去查必然查不到），
    org_table 才是真表名；`sj AS 时间` 同理要取 org_name。表达式列（1 AS lit、amt+0）
    两项都是空串，本来就对不上任何列，留空即可。
    """
    fields = getattr(getattr(cur, "_result", None), "fields", None)
    if not fields:
        return None
    conn = getattr(cur, "connection", None)
    fallback_db = _as_text(getattr(conn, "db", ""))
    out = []
    for f in fields:
        out.append((_as_text(getattr(f, "db", "")) or fallback_db,
                    _as_text(getattr(f, "org_table", "")),
                    _as_text(getattr(f, "org_name", ""))))
    return out


def column_comments(cur, ncols):
    """这一批结果集的字段注释，与列等长；查得到才给，查不到一律空串。

    只有图形界面这条通道会调它（命令行那条维持逐字原样，多一行日志都会破两栈文本对撞）。
    一次结果集最多一条 information_schema 查询：把所有列的表按库分组，一次 IN 查完。
    库名/表名按原样比（Linux 上 MySQL 的表名区分大小写，两边都出自同一个服务端），
    列名不分大小写比（MySQL 的列名本就不区分）。
    这一步失败不该影响用户看数据：整段兜异常退化成"没有注释"，不打日志也不报错
    ——执行器的文本行是两栈逐行对撞的，这里多打一行就红一片。
    驱动没给字段来源（假连接、非 PyMySQL）同样退化成空串，不报错。
    """
    blank = [""] * ncols
    try:
        conn = getattr(cur, "connection", None)
        origin = _column_origin(cur)
        if not origin or len(origin) != ncols:
            return blank
        want = {}
        for db, table, column in origin:
            if table and column:
                want.setdefault(db, set()).add(table)
        if not want:
            return blank
        found = {}
        meta = conn.cursor()
        try:
            for db, tables in want.items():
                names = sorted(tables)
                sql = ("SELECT TABLE_NAME, COLUMN_NAME, COLUMN_COMMENT"
                       " FROM information_schema.COLUMNS"
                       " WHERE TABLE_SCHEMA=%s AND TABLE_NAME IN ("
                       + ",".join(["%s"] * len(names)) + ")")
                meta.execute(sql, [db] + names)
                for row in meta.fetchall():
                    tb = _as_text(row[0])
                    col = _as_text(row[1]).lower()
                    found[(db.lower(), tb, col)] = _as_text(row[2])
        finally:
            meta.close()
        return [found.get((db.lower(), table, column.lower()), "")
                for db, table, column in origin]
    except Exception:
        return blank


def _print_result(cur, emit, result_cb=None, seq=0, sql=""):
    cols = [d[0] for d in (cur.description or [])]
    printed = total = 0
    rows = []
    while True:
        row = cur.fetchone()
        if row is None:
            break
        total += 1
        if printed < PREVIEW_ROWS:
            rows.append(list(row))
            printed += 1
    if result_cb is not None:
        # 图形界面把这一批交给表格控件去画。文本框里画表格有两处治不了：
        # 一是中英文混排时字宽不是整数倍关系，格子必错行；二是结果一长就被自动滚到底，
        # 用户只看得见"共 N 行"那句尾巴，看不见数据本身。行数由表格上方的说明行交代。
        # comments 是给表头当 title 用的字段注释：pinyin 列名（DDJE 这种）只有注释能读懂。
        result_cb({"seq": seq, "sql": sql,
                   "columns": [_cell(c) for c in cols],
                   "comments": column_comments(cur, len(cols)),
                   "rows": [[_cell(v) for v in r] for r in rows],
                   "total": total, "printed": printed})
        return
    for line in render_table(cols, rows):
        emit(TABLE_INDENT + line)
    emit("      共 %d 行%s" % (total,
                               "（只打印前 %d 行，其余没显示）" % printed
                               if total > printed else ""))


def run(conn, text, emit, progress=None, cancelled=None, result_cb=None):
    """逐条执行。返回已执行条数；出错时最后一条日志就是原因。

    progress(done, total) 与 cancelled() 是给图形界面用的：一屏 1242 条语句的批次
    如果只在结束时报一次，进度条全程停在 0%，用户既看不出在动、也停不下来。
    命令行那条线不传这两个参数，行为与原来逐字一致。
    result_cb(结果集) 同样只有图形界面传：给了就把结果成交给表格控件，
    文本里不再画 ASCII 表格；不给就是命令行原来那份逐字输出。
    """
    stmts = executable_statements(text)
    if not stmts:
        emit("! [WARN] 没有可执行的语句：编辑区是空的（只写注释也不算）")
        return 0
    total = len(stmts)
    if progress:
        progress(0, total)
    cur = conn.cursor()
    done = 0
    for i, sql in enumerate(stmts, 1):
        if cancelled is not None and cancelled():
            emit("! [WARN] 已取消 | 第 %d/%d 条未执行，本批未提交（已回滚）" % (i, total))
            try:
                conn.rollback()
                emit("- [INFO] 已回滚 | 前面 %d 条的效果看不到" % done)
            except Exception as e2:
                emit("! [WARN] 回滚也失败了 | %s" % e2)
            return done
        emit("- [INFO] 第 %d/%d 条：%s" % (i, total, _one_line(sql)))
        try:
            n = cur.execute(sql)
            done = i
            if cur.description:
                _print_result(cur, emit, result_cb, i, _one_line(sql))
            else:
                # 不在这里 commit：本批的契约是"全部成功才提交，中途出错整批回滚"，
                # 每句一提交会让前面已写进去的数据撤不回来，而界面上的 tooltip 说的是整批语义
                emit("+ [OK] 影响 %d 行" % (n or 0))
        except Exception as e:
            emit("x [ERROR] 第 %d 条被拒绝 | %s" % (i, describe_db_error(e)))
            emit("      " + DDL_NO_ROLLBACK_NOTE)
            try:
                conn.rollback()
                emit("- [INFO] 已回滚 | 本次未提交，库里看不到刚才这批语句的效果")
            except Exception as e2:
                emit("! [WARN] 回滚也失败了 | %s" % e2)
            if progress:
                progress(total, total)
            return done
        if progress:
            progress(done, total)
    try:
        conn.commit()
        emit("+ [OK] 全部完成 | %d 条语句已提交" % done)
    except Exception as e:
        emit("x [ERROR] 提交失败 | %s" % describe_db_error(e))
    if progress:
        progress(total, total)
    return done
