"""「SQL 执行器」页签的函数体：把编辑区里那段文本切成语句逐条执行，产出文本结果。

与 Java 侧 core/SqlExecute.java 同一套文案与事务语义（全部成功才提交、中途出错整批回滚、
失败那句后面附 DDL 隐式提交的提醒）。两个页签/两条线如果回滚语义不一样，
现场按一个页签的经验去理解另一个就会出错。
"""
import re

from core.log_text import DDL_NO_ROLLBACK_NOTE, describe_db_error
from core.sql_script import has_executable_content, split_sql_statements

PREVIEW_ROWS = 200


def executable_statements(text):
    """切句再剔掉"整句只剩注释"的片段——执行器只认真正要执行的那几句。"""
    return [s for s in split_sql_statements(text) if has_executable_content(s)]


def _one_line(sql):
    s = re.sub(r" +", " ", sql.replace("\n", " ").replace("\r", " ")).strip()
    return s if len(s) <= 160 else s[:160] + "…（共 %d 字符）" % len(s)


def _print_result(cur, emit):
    cols = [d[0] for d in (cur.description or [])]
    emit("      " + " | ".join(cols))
    printed = total = 0
    while True:
        row = cur.fetchone()
        if row is None:
            break
        total += 1
        if printed >= PREVIEW_ROWS:
            continue
        emit("      " + " | ".join("NULL" if v is None else str(v) for v in row))
        printed += 1
    emit("      共 %d 行%s" % (total,
                               "（只打印前 %d 行，其余没显示）" % printed
                               if total > printed else ""))


def run(conn, text, emit):
    """逐条执行。返回已执行条数；出错时最后一条日志就是原因。"""
    stmts = executable_statements(text)
    if not stmts:
        emit("! [WARN] 没有可执行的语句：编辑区是空的（只写注释也不算）")
        return 0
    cur = conn.cursor()
    done = 0
    for i, sql in enumerate(stmts, 1):
        emit("- [INFO] 第 %d/%d 条：%s" % (i, len(stmts), _one_line(sql)))
        try:
            n = cur.execute(sql)
            done = i
            if cur.description:
                _print_result(cur, emit)
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
            return done
    try:
        conn.commit()
        emit("+ [OK] 全部完成 | %d 条语句已提交" % done)
    except Exception as e:
        emit("x [ERROR] 提交失败 | %s" % describe_db_error(e))
    return done
