"""逐条把 SQL 执行器的 126 条示例打到真库上跑一遍，证明它们是可执行语句而不是摆设。

每条之前重建一次临时环境。有几条天然依赖前一条的产物（加列之后才能改列名、
建了索引才能删索引、建了映射落地表才能按它统计），这类依赖在 PREREQ 里显式声明并先跑一遍，
而不是把环境注水成"什么都有"——注水会让"加列"那条永远撞 Duplicate column，
反倒看不出问题。切句用产品里那个 split_sql_statements，顺带把切分也验了。

夹具里只放"这套示例默认站在那张业务表上"的底座：t_order 与它那几个映射对照用的伴生表
（t_dict / t_synonym / t_exception_order / t_map_order_code_clean）。这四张表在示例里
没有一条会去建它们（建表那条只建映射落地表），不补就成了"语句本身没错、环境缺表"的假失败。
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
PREREQ = {10: [8], 13: [11], 30: [8], 47: [6], 79: [6], 82: [6],
          115: [8], 117: [7], 124: [6], 125: [6]}

# 序号 → 本条之前要先跑的语句。回写那三条要求业务表已经有回写列，真实流程里这一步
# 就是"先加一列"（加列那条示例教的是同一个动作）；把它放在这里而不是预置进夹具，
# 是因为预置会让生成宽表那条的 SELECT a.*, b.new_code 撞 Duplicate column。
ADD_WRITE_BACK = ["ALTER TABLE t_order ADD COLUMN new_code VARCHAR(64) COMMENT '回写的新编码'"]
SETUP = {i: ADD_WRITE_BACK for i in (120, 121, 123)}


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
    # 业务表按映射治理那一组要的形态建：除了订单本身的列，还带源端名称、旧编码、
    # 分类码、父级名与业务日期——这几列是编码映射示例的输入，缺一条就有一批假失败。
    # 唯独不带 new_code：回写那三条要有它，宽表那条又要 SELECT a.*, b.new_code，
    # 预置了后者就撞重复列。按真实流程的先后（先加回写列、再回写）放到 SETUP 里补。
    cur.execute("CREATE TABLE t_order (id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键', "
                "order_no VARCHAR(64) NOT NULL COMMENT '订单编号', amount DECIMAL(18,2), "
                "cust_no VARCHAR(32), created_at DATETIME, "
                "name VARCHAR(64) COMMENT '源端名称', old_code VARCHAR(64) COMMENT '源端旧编码', "
                "cat_code VARCHAR(32) COMMENT '分类码', parent_name VARCHAR(64) COMMENT '父级名称', "
                "biz_date DATE COMMENT '业务日期', "
                "PRIMARY KEY (id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    cur.execute("INSERT INTO t_order (order_no, amount, cust_no, created_at, "
                "name, old_code, cat_code, parent_name, biz_date) VALUES "
                "('A1', 1.5, 'C1', '2026-02-01 00:00:00', '甲产品', 'A01', 'C1', '甲类', '2026-02-01'),"
                "('A2', 2.5, 'C2', '2026-03-01 00:00:00', '乙产品', 'A02', 'C1', '甲类', '2026-03-01'),"
                "('A2', 2.5, 'C2', '2026-03-01 00:00:01', '乙 产品', 'A02', 'C2', '乙类', '2026-03-02')")
    cur.execute("CREATE TABLE t_dict (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                "dict_code VARCHAR(64) COMMENT '字典编码', dict_name VARCHAR(64) COMMENT '字典项名', "
                "name VARCHAR(64) COMMENT '名称', new_code VARCHAR(64) COMMENT '新编码', "
                "cat_code VARCHAR(32) COMMENT '分类码', dict_version VARCHAR(16) COMMENT '字典版本', "
                "eff_date DATE COMMENT '生效日', exp_date DATE COMMENT '失效日') "
                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    cur.execute("INSERT INTO t_dict (dict_code, dict_name, name, new_code, cat_code, "
                "dict_version, eff_date, exp_date) VALUES "
                "('D01', '甲产品', '甲产品', 'N01', 'C1', 'v1', '2020-01-01', NULL),"
                "('D02', '乙产品', '乙产品', 'N02', 'C1', 'v1', '2020-01-01', '2025-12-31'),"
                "('D02b', '乙产品', '乙产品', 'N02b', 'C2', 'v2', '2026-01-01', NULL),"
                "('D03', '丙产品', '丙产品', 'N03', 'C2', 'v1', '2020-01-01', NULL)")
    cur.execute("CREATE TABLE t_synonym (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                "alias VARCHAR(64) COMMENT '别名', std_name VARCHAR(64) COMMENT '标准名') "
                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    cur.execute("INSERT INTO t_synonym (alias, std_name) VALUES ('甲产品（旧）', '甲产品')")
    cur.execute("CREATE TABLE t_exception_order (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                "old_code VARCHAR(64), name VARCHAR(64), reason VARCHAR(255), "
                "check_time DATETIME) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    cur.execute("CREATE TABLE t_map_order_code_clean (old_code VARCHAR(64) NOT NULL PRIMARY KEY, "
                "name VARCHAR(64), new_code VARCHAR(64), conf_level VARCHAR(16)) "
                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
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
    if len(SAMPLES) != 126:
        print("条数不是 126：%d" % len(SAMPLES))
        return 1
    fails = []
    for i, sample in enumerate(SAMPLES, 1):
        reset()
        c = conn(DB)
        cur = c.cursor()
        err = None
        rows = 0
        for pre in PREREQ.get(i, []):
            perr, _p = run_one(cur, SAMPLES[pre - 1].sql)
            if perr:
                err = "前置第 %d 条就没跑通：%s" % (pre, perr)
                break
        if err is None:
            for st in SETUP.get(i, []):
                serr, _s = run_one(cur, st)
                if serr:
                    err = "夹具前置语句没跑通（%s）：%s" % (st.split("(")[0][:40], serr)
                    break
        if err is None:
            err, rows = run_one(cur, sample.sql)
        c.close()
        if err:
            fails.append((i, sample.group, sample.desc, err))
            print("  BAD %2d [%s] %s -> %s" % (i, sample.group, sample.desc, err))
        else:
            note = "，返回 %d 行" % rows if rows else ""
            pre = "（前置 %s）" % "/".join(str(x) for x in PREREQ[i]) if i in PREREQ else ""
            if i in SETUP:
                pre = pre + "（已补回写列）" if pre else "（已补回写列）"
            print("  ok  %2d [%s] %s%s%s" % (i, sample.group, sample.desc, pre, note))
    cleanup()
    print("合计 %d 条，失败 %d 条" % (len(SAMPLES), len(fails)))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
