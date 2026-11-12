"""逐条把 SQL 执行器的 157 条示例打到真库上跑一遍，证明它们是可执行语句而不是摆设。

只有一条例外：TABLESAMPLE 那条本身就是"MySQL 不支持这个写法"的反面例子，
在 EXPECT_ERR 里声明成"应当报 1064"，自检要求它照样报错——这样文档里那句话
每次都被真库复核过，MySQL 哪天加上这个语法，这里立刻变红提醒回来重写示例。

每条之前重建一次临时环境。有几条天然依赖前一条的产物（加列之后才能改列名、
建了索引才能删索引、建了映射落地表才能按它统计），这类依赖在 PREREQ 里显式声明并先跑一遍，
而不是把环境注水成"什么都有"——注水会让"加列"那条永远撞 Duplicate column，
反倒看不出问题。切句用产品里那个 split_sql_statements，顺带把切分也验了。

前置与夹具都按 (分组, 短标题) 声明，不按序号：序号在示例中间插一条就整批错位，
错位之后前置照跑、本条照过，量到的却不是它真正依赖的那一步——这种红是看不出来的。

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

# (本条的分组, 短标题) → 需要先执行的示例
PREREQ = {
    ("建表与改表", "改列名"): [("建表与改表", "加列")],
    ("建表与改表", "删索引"): [("建表与改表", "加普通索引")],
    ("数据质量检查", "空值与空串混用"): [("建表与改表", "加列")],
    ("数据质量检查", "匹配率"): [("建表与改表", "治理落地表")],
    ("编码映射与关联", "前缀规则校验"): [("建表与改表", "治理落地表")],
    ("编码映射与关联", "映射去重落地"): [("建表与改表", "治理落地表")],
    ("数据修正", "空串改NULL"): [("建表与改表", "加列")],
    ("数据修正", "清全表"): [("建表与改表", "建快照表")],
    ("数据修正", "增量比对"): [("建表与改表", "治理落地表")],
    ("数据修正", "对账报告"): [("建表与改表", "治理落地表")],
    ("性能与容量治理", "只改真变的行"): [("编码映射与关联", "回填目标表")],
    ("性能与容量治理", "按主键分批改"): [("编码映射与关联", "回填目标表")],
    ("性能与容量治理", "临时表中转回填"): [("编码映射与关联", "回填目标表")],
}

# 回写那三条要求业务表已经有回写列，真实流程里这一步就是"先加一列"（加列那条示例
# 教的是同一个动作）；把它放在这里而不是预置进夹具，是因为预置会让生成宽表那条的
# SELECT a.*, b.new_code 撞 Duplicate column。
ADD_WRITE_BACK = ["ALTER TABLE t_order ADD COLUMN new_code VARCHAR(64) COMMENT '回写的新编码'"]
SETUP = {("数据修正", "只填空位回写"): ADD_WRITE_BACK,
         ("数据修正", "回写冲突检测"): ADD_WRITE_BACK,
         ("数据修正", "事务内回写"): ADD_WRITE_BACK}

# (分组, 短标题) → (预期错误码, 错误信息里必须出现的片段)。
# 这里只放"故意教人别抄"的跨库写法：跑通了反倒是坏消息（说明 MySQL 加了这个语法、
# 示例的措辞该改了），所以下面的主循环对它们的要求与别的条目正好相反。
EXPECT_ERR = {
    ("查询与执行计划", "TABLESAMPLE"): (1064, "syntax"),
}


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
    if len(SAMPLES) != 157:
        print("条数不是 157：%d" % len(SAMPLES))
        return 1
    at = {}
    for i, sample in enumerate(SAMPLES, 1):
        key = (sample.group, sample.title)
        if key in at:
            print("分组 + 短标题撞了：第 %d 条与第 %d 条都是 %s" % (at[key], i, key))
            return 1
        at[key] = i
    for src in PREREQ.values():
        for dep in src:
            if dep not in at:
                print("前置指向一条不存在的示例：%s" % (dep,))
                return 1
    for key in list(PREREQ) + list(SETUP) + list(EXPECT_ERR):
        if key not in at:
            print("前置声明的主键在示例库里找不到：%s" % (key,))
            return 1
    fails = []
    for i, sample in enumerate(SAMPLES, 1):
        key = (sample.group, sample.title)
        reset()
        c = conn(DB)
        cur = c.cursor()
        err = None
        rows = 0
        for dep in PREREQ.get(key, []):
            perr, _p = run_one(cur, SAMPLES[at[dep] - 1].sql)
            if perr:
                err = "前置「%s」就没跑通：%s" % (dep[1], perr)
                break
        if err is None:
            for st in SETUP.get(key, []):
                serr, _s = run_one(cur, st)
                if serr:
                    err = "夹具前置语句没跑通（%s）：%s" % (st.split("(")[0][:40], serr)
                    break
        if err is None:
            err, rows = run_one(cur, sample.sql)
        c.close()
        expect = EXPECT_ERR.get(key)
        if expect is not None:
            code, marker = expect
            if err is None:
                fails.append((i, sample.group, sample.desc,
                              "声明该报 %d 却跑通了，示例措辞要改" % code))
                print("  BAD %2d [%s] %s -> 声明该报 %d 却跑通"
                      % (i, sample.group, sample.desc, code))
            elif str(code) not in err or marker not in err:
                fails.append((i, sample.group, sample.desc, "报错与预期不符：%s" % err))
                print("  BAD %2d [%s] %s -> 报错与预期不符：%s"
                      % (i, sample.group, sample.desc, err))
            else:
                print("  ok  %2d [%s] %s（按预期报 %d，MySQL 没这个语法）"
                      % (i, sample.group, sample.desc, code))
            continue
        if err:
            fails.append((i, sample.group, sample.desc, err))
            print("  BAD %2d [%s] %s -> %s" % (i, sample.group, sample.desc, err))
        else:
            note = "，返回 %d 行" % rows if rows else ""
            pre = "（前置 %s）" % "/".join(x[1] for x in PREREQ[key]) if key in PREREQ else ""
            if key in SETUP:
                pre = pre + "（已补回写列）" if pre else "（已补回写列）"
            print("  ok  %2d [%s] %s%s%s" % (i, sample.group, sample.desc, pre, note))
    cleanup()
    print("合计 %d 条，失败 %d 条" % (len(SAMPLES), len(fails)))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
