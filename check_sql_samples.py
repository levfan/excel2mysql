"""逐条把 SQL 执行器的 30 条示例打到真库上跑一遍，证明它们是可执行语句而不是摆设。

每条之前重建一次临时环境。有几条天然依赖前一条的产物（加列之后才能改列名、
建了索引才能删索引、建了快照表才能清它），这类依赖在 PREREQ 里显式声明并先跑一遍，
而不是把环境注水成"什么都有"——注水会让"加列"那条永远撞 Duplicate column，
反倒看不出问题。切句用产品里那个 split_sql_statements，顺带把切分也验了。
口令只从命令行环境变量读（PT_PASS），本文件不含凭据。
"""
import os
import sys

import pymysql

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from core.sql_script import split_sql_statements  # noqa: E402
from sql_samples import SAMPLES  # noqa: E402

HOST, PORT, USER, DB = "127.0.0.1", 3306, "root", "e2m_sqlscratch"
AUX = "ods_data"

# 序号（从 1 开始）→ 需要先执行的序号
PREREQ = {9: [7], 12: [10], 25: [7], 28: [7], 30: [6]}


def conn(db=None):
    return pymysql.connect(host=HOST, port=int(PORT), user=USER,
                           password=os.environ["PT_PASS"], database=db,
                           charset="utf8mb4", autocommit=True)


def reset():
    c = conn()
    cur = c.cursor()
    for name in (DB, AUX):
        cur.execute("DROP DATABASE IF EXISTS `%s`" % name)
    # 空库先建好：USE 那条要能单独跑通
    cur.execute("CREATE DATABASE `%s` DEFAULT CHARACTER SET utf8mb4" % AUX)
    cur.execute("CREATE DATABASE `%s` DEFAULT CHARACTER SET utf8mb4" % DB)
    cur.execute("USE `%s`" % DB)
    cur.execute("CREATE TABLE t_order (id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键', "
                "order_no VARCHAR(64) NOT NULL COMMENT '订单编号', amount DECIMAL(18,2), "
                "cust_no VARCHAR(32), created_at DATETIME, PRIMARY KEY (id)) "
                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    cur.execute("INSERT INTO t_order (order_no, amount, cust_no, created_at) VALUES "
                "('A1', 1.5, 'C1', '2026-02-01 00:00:00'),"
                "('A2', 2.5, 'C2', '2026-03-01 00:00:00'),"
                "('A2', 2.5, 'C2', '2026-03-01 00:00:01')")
    c.close()


def run_one(cur, sql):
    """执行一条示例里的全部语句，返回 (错误, 最后一个结果集行数)。"""
    rows = 0
    for st in split_sql_statements(sql):
        try:
            cur.execute(st)
            if cur.description:
                rows = len(cur.fetchall())
        except Exception as e:
            return ("%s：%s" % (type(e).__name__, str(e)[:130])), rows
    return None, rows


def cleanup():
    c = conn()
    cur = c.cursor()
    for name in (DB, AUX):
        cur.execute("DROP DATABASE IF EXISTS `%s`" % name)
    c.close()


def main():
    if len(SAMPLES) != 30:
        print("条数不是 30：%d" % len(SAMPLES))
        return 1
    fails = []
    for i, (group, desc, sql) in enumerate(SAMPLES, 1):
        reset()
        c = conn(DB)
        cur = c.cursor()
        err = None
        rows = 0
        for pre in PREREQ.get(i, []):
            perr, _p = run_one(cur, SAMPLES[pre - 1][2])
            if perr:
                err = "前置第 %d 条就没跑通：%s" % (pre, perr)
                break
        if err is None:
            err, rows = run_one(cur, sql)
        c.close()
        if err:
            fails.append((i, group, desc, err))
            print("  BAD %2d [%s] %s -> %s" % (i, group, desc, err))
        else:
            note = "，返回 %d 行" % rows if rows else ""
            pre = "（前置 %s）" % "/".join(str(x) for x in PREREQ[i]) if i in PREREQ else ""
            print("  ok  %2d [%s] %s%s%s" % (i, group, desc, pre, note))
    cleanup()
    print("合计 %d 条，失败 %d 条" % (len(SAMPLES), len(fails)))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
