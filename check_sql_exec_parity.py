# -*- coding: utf-8 -*-
"""把两条线的「SQL 执行器」函数体打到同一个真库上跑，逐行比对它们说的话。

为什么要真跑而不是读代码：这一页的语义全在副作用里——回滚到底生效没有、
提交发生在哪一句之后、结果集报不报得出行数。两条线一份写成"全部成功才提交"、
另一份每句一提交，界面上的 tooltip 就有一边在撒谎。这里断的是三件事：

1. 落库取证：坏批回滚后探针表必须 0 行，好批提交后必须 1 行（Python 侧自己查回来）
2. 行序列逐行一致：两条线对同样的输入必须说同样的话
3. 唯一允许不同的是驱动报错那一行——异常类型名与消息前缀由驱动决定，
   只要求 MySQL 错误码相同、且那一行被结构化前缀（第 N 条被拒绝）钉住

口令只从环境变量读（PT_USER / PT_PASS / PT_DB），本文件不含凭据；
Java 侧子进程用同一套值填 E2M_*。探针表只动 e2msqlexecprobe，跑前跑后库里
表清单必须一张不多一张不少（Java 探针自己断，Python 侧在这里断）。
"""
import io
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
JAVA_DIR = os.path.join(HERE, "java")
JDK8 = os.environ.get("JDK8", r"C:\Program Files\Java\jdk1.8.0_202")
MVN = os.environ.get("MVN", "mvn.cmd" if os.name == "nt" else "mvn")
HOST = os.environ.get("PT_HOST", "127.0.0.1")
PORT = os.environ.get("PT_PORT", "3306")
PROBE = "e2msqlexecprobe"

NL = "\n"
B1 = "CREATE TABLE %s (id INT PRIMARY KEY, name VARCHAR(20));" % PROBE
B2 = ("INSERT INTO %s VALUES (1, '甲');" + NL
      + "INSERT INTO %s VALUES (2, '乙');" + NL
      + "SELECT * FROM no_such_table_e2m;") % (PROBE, PROBE)
B3 = ("INSERT INTO %s VALUES (3, '丙');" + NL
      + "SELECT id, name FROM %s ORDER BY id;") % (PROBE, PROBE)
B4 = "-- 只是一句注释"
BATCHES = [B1, B2, B3, B4]

ERR_RE = re.compile(r"^(x \[ERROR\] 第 (\d+) 条被拒绝 \| ).*$")
CODE_RE = re.compile(r"MySQL 错误码 (\d+)")


def normalize(line):
    """驱动报错那一行只留结构与前缀，其余部分由运行时决定，不参与跨线比对。"""
    m = ERR_RE.match(line)
    if not m:
        return line
    code = CODE_RE.search(line)
    return m.group(1) + "<驱动展开，MySQL 错误码 %s>" % (code.group(1) if code else "无")


def py_run(db):
    """在真库上跑 Python 那一份，返回 (行序列, 落库行数的两次观察)。"""
    import pymysql
    from core.sql_execute import run

    conn = pymysql.connect(host=HOST, port=int(PORT), user=os.environ["PT_USER"],
                           password=os.environ["PT_PASS"], database=db,
                           charset="utf8mb4", autocommit=False)
    lines = []
    seen = []
    cur = conn.cursor()
    tables_before = _tables(cur)
    if PROBE in tables_before:
        raise RuntimeError("库里已有同名探针表，先清掉再跑：%s" % PROBE)
    try:
        for i, text in enumerate(BATCHES):
            run(conn, text, lines.append)
            if i == 1:                      # 坏批之后：整批回滚，表里必须还是 0 行
                cur.execute("SELECT COUNT(*) FROM `%s`" % PROBE)
                seen.append(("坏批回滚后", cur.fetchone()[0]))
            if i == 2:                      # 好批之后：提交生效，表里应有 1 行
                cur.execute("SELECT COUNT(*) FROM `%s`" % PROBE)
                seen.append(("好批提交后", cur.fetchone()[0]))
    finally:
        cur.execute("DROP TABLE IF EXISTS `%s`" % PROBE)
        conn.commit()
        if _tables(cur) != tables_before:
            raise RuntimeError("跑完库里表清单变了：前后 %d -> %d"
                               % (len(tables_before), len(_tables(cur))))
        conn.close()
    return lines, seen


def _tables(cur):
    cur.execute("SHOW TABLES")
    return set(r[0].lower() for r in cur.fetchall())


def java_run(db):
    """构建并运行 Java 那份探针，返回它打印的 LINE| 行序列。"""
    env = dict(os.environ)
    env["JAVA_HOME"] = JDK8
    env["PATH"] = os.path.join(JDK8, "bin") + os.pathsep + env.get("PATH", "")
    env["E2M_HOST"] = HOST
    env["E2M_PORT"] = PORT
    env["E2M_USER"] = os.environ["PT_USER"]
    env["E2M_PASS"] = os.environ["PT_PASS"]
    env["E2M_DB"] = db
    proc = subprocess.Popen([MVN, "-B", "-q", "-o", "install", "-pl", "app,verify"],
                            cwd=JAVA_DIR, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, env=env)
    out = proc.communicate()[0].decode("utf-8", "replace")
    if proc.returncode != 0:
        print(out[-3000:])
        raise RuntimeError("Java 侧构建失败，退出码 %d" % proc.returncode)
    java = os.path.join(JDK8, "bin", "java.exe" if os.name == "nt" else "java")
    cp = ";".join([os.path.join("app", "target", "classes"),
                   os.path.join("verify", "target", "classes"),
                   os.path.join("verify", "target", "lib", "*")])
    proc = subprocess.Popen([java, "-Dfile.encoding=UTF-8", "-cp", cp,
                             "com.excel2mysql.verify.SqlExecCli"],
                            cwd=JAVA_DIR, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, env=env)
    out = proc.communicate()[0].decode("utf-8", "replace")
    # Java 侧走的是控制台输出，Windows 上是 \r\n；按行切完要把 \r 抹掉再比，
    # 否则每一行都差一个回车，看着像"两条线说的话不一样"
    lines = [l[5:].rstrip("\r") for l in out.split("\n") if l.startswith("LINE|")]
    if proc.returncode != 0:
        print(out[-3000:])
        raise RuntimeError("Java 探针退出码 %d（它自己的断言没通过）" % proc.returncode)
    if not lines:
        raise RuntimeError("Java 探针没发出任何 LINE| 行")
    return lines, out


def main():
    for var in ("PT_USER", "PT_PASS", "PT_DB"):
        if not os.environ.get(var):
            print("! [WARN] SQL-EXEC-PARITY | 未设 %s，跳过（这条要连真库）" % var)
            return 3
    db = os.environ["PT_DB"]
    py_lines, seen = py_run(db)
    print("- [INFO] SQL-EXEC-PARITY | Python 侧 %d 行，落库取证：%s"
          % (len(py_lines), "、".join("%s=%d 行" % (k, v) for k, v in seen)))
    if [v for _k, v in seen] != [0, 1]:
        print("x [ERROR] SQL-EXEC-PARITY | 落库取证不对（坏批回滚后应 0 行、好批提交后应 1 行）")
        return 1
    java_lines, jout = java_run(db)
    print("- [INFO] SQL-EXEC-PARITY | Java 侧 %d 行" % len(java_lines))
    a = [normalize(x) for x in py_lines]
    b = [normalize(x) for x in java_lines]
    bad = 0
    if len(a) != len(b):
        print("x [ERROR] SQL-EXEC-PARITY | 行数不同：Python %d / Java %d" % (len(a), len(b)))
        bad += 1
    for i in range(min(len(a), len(b))):
        if a[i] != b[i]:
            bad += 1
            print("x [ERROR] SQL-EXEC-PARITY | 第 %d 行不同" % (i + 1))
            print("      Python: %s" % a[i][:200])
            print("      Java  : %s" % b[i][:200])
            if bad > 8:
                break
    ok_line = [l for l in jout.split("\n") if l.startswith("+ [OK] SQL-EXEC")]
    if ok_line:
        print("      " + ok_line[0])
    if bad:
        print("x [ERROR] SQL-EXEC-PARITY | 两条线的执行器说法不一致 %d 处" % bad)
        return 1
    print("+ [OK] SQL-EXEC-PARITY | 同样的四段输入，两条执行器发出 %d 行逐行相同；"
          "坏批回滚后 0 行、好批提交后 1 行" % len(a))
    return 0


if __name__ == "__main__":
    sys.exit(main())
