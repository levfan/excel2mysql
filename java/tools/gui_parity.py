# -*- coding: utf-8 -*-
"""对撞两条 GUI 线枚举出来的界面事实：java/tools/gui_dump_py.py 与 verify 的 GuiDump。

为什么要这么一层：清单是我自己写的就会只对我想到的东西负责。把两边的
按钮/输入框/表格/弹窗/日志/产物全部落成同名 key，再逐键比，
"少一颗按钮、文案差一个字、启用条件不同、日志少一行"都会以键缺失或值不等露出来。

跑法（在 java/ 目录下）：
    python tools/gui_parity.py            # 自己调两条线各跑一次枚举再比
    python tools/gui_parity.py --py a.json --java b.json   # 只比已有产物
"""
import argparse
import io
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
JAVA_DIR = os.path.dirname(HERE)
REPO = os.path.dirname(JAVA_DIR)
JDK8 = os.environ.get("JDK8", r"C:\Program Files\Java\jdk1.8.0_202")
MVN = os.environ.get("MVN", "mvn.cmd" if os.name == "nt" else "mvn")
PY_VENV = os.environ.get("PY_VENV", os.path.join(REPO, ".venv", "Scripts", "python.exe"))
FIX = os.path.join(JAVA_DIR, "verify", "fixtures")
OUT_DIR = os.path.join(JAVA_DIR, "verify", "target", "parity")

TAG_RE = re.compile(r"<[^>]+>")
BREAK_RE = re.compile(r"\s*\n\s*")

# 控件类别：两边的类名必然不同（QLineEdit vs JTextField/HintTextField），
# 归一到同一词表后才谈得上比"是不是同一类控件"，具体类名留在 json 里可查。
KIND = {
    "QLineEdit": "text", "JTextField": "text", "HintTextField": "text",
    "JPasswordField": "text",
    "QComboBox": "combo", "JComboBox": "combo",
    "QCheckBox": "check", "JCheckBox": "check",
    "QSpinBox": "spin", "JSpinner": "spin",
    "QProgressBar": "progress", "JProgressBar": "progress",
    "QPushButton": "button", "JButton": "button",
}


def canon_key_value(key, value):
    if key.endswith(".kind"):
        return KIND.get(value, value)
    if key.endswith(".tip"):
        # tooltip 的换行是载体决定的（Qt 用 \n、Swing 用 <br>），比的是话术本身。
        # 中文断句处换行不该补出空格，所以先把换行连同两侧空白一起抹掉
        return " ".join(BREAK_RE.sub("", canon(value)).split())
    if key.endswith(".log"):
        # 日志里缩进的明细行是驱动原文（pymysql 的 2003 与 JDBC 的
        # Communications link failure），两条线换的是运行时，不是界面文案；
        # 固定文案那一行照比，明细只比"有没有"（在 guard.conn.* 里单独断）
        raw = (value or "").replace("\r", "")
        kept = [ln for ln in raw.split("\n") if not ln.startswith("    ")]
        return canon("\n".join(kept))
    return canon(value)


def same_cell(x, y):
    """一格是否等价：两边都能当数读的，就按数比。

    同一个 1e25，openpyxl 原样给浮点、POI 归一成整数，十进制字面量必然不同，
    但它们是同一个数。按文本比会把这条记成"不一致"，按数比才是要表达的契约。
    """
    if x == y:
        return True
    try:
        return float(x) == float(y)
    except (TypeError, ValueError):
        return False


def diff_grid(key, a, b):
    """导出产物那种"一行一行、一格一格"的文本：逐格按 same_cell 比，返回差异描述。"""
    la, lb = a.split("\n"), b.split("\n")
    out = []
    for i in range(max(len(la), len(lb))):
        xa = la[i] if i < len(la) else None
        xb = lb[i] if i < len(lb) else None
        if xa == xb:
            continue
        if xa is None or xb is None or xa.startswith("### ") or xb.startswith("### ") \
                or xa.startswith("[") or xb.startswith("["):
            out.append("行%d 仅一侧有：%r vs %r" % (i, xa, xb))
            continue
        ca, cb = xa.split(";"), xb.split(";")
        for j in range(max(len(ca), len(cb))):
            va = ca[j] if j < len(ca) else None
            vb = cb[j] if j < len(cb) else None
            if not same_cell(va, vb):
                out.append("行%d 列%d：%r vs %r" % (i, j, va, vb))
        if len(out) > 8:
            break
    return out

# 报告里有中文与 ⏎ 这类符号，输出被重定向时 Python 会退回系统 ANSI 码页直接抛错
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass


def canon(value):
    """把只剩载体差别的部分抹平：Swing 的 tooltip 用 HTML，Qt 用纯文本 + \\n。"""
    s = value or ""
    if "<html" in s.lower() or "<br" in s.lower():
        s = s.replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n")
        s = TAG_RE.sub("", s)
    s = s.replace("\r", "").replace(" ", " ").replace(" ", " ")
    s = "\n".join(line.strip() for line in s.split("\n"))
    return s.strip()


def load(path):
    with io.open(path, encoding="utf-8") as fh:
        return json.load(fh)


def run(cmd, cwd):
    env = dict(os.environ)
    # 构建与跑产物都用随包同款 JDK 8：开发机上的高版本 JDK 只会把"现场跑不了"留到交付那天
    env["JAVA_HOME"] = JDK8
    env["PATH"] = os.path.join(JDK8, "bin") + os.pathsep + env.get("PATH", "")
    proc = subprocess.Popen(cmd, cwd=cwd, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, env=env)
    out = proc.communicate()[0].decode("utf-8", "replace")
    return proc.returncode, out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--py")
    ap.add_argument("--java")
    ap.add_argument("--keep", action="store_true", help="不重跑，直接用已有 json")
    args = ap.parse_args()

    if not args.keep:
        py_json = args.py or os.path.join(OUT_DIR, "py.json")
        java_json = args.java or os.path.join(OUT_DIR, "java.json")
        code, out = run([PY_VENV, os.path.join("tools", "gui_dump_py.py"),
                         py_json, FIX], cwd=JAVA_DIR)
        if code != 0:
            print(out[-4000:])
            print("x [ERROR] GUI-PARITY | Python 侧枚举失败，退出码 %d" % code)
            return 1
        code, out = run([MVN, "-B", "-q", "-o", "install", "-pl", "app,verify"],
                        cwd=JAVA_DIR)
        if code != 0:
            print(out[-4000:])
            print("x [ERROR] GUI-PARITY | Java 侧构建失败，退出码 %d" % code)
            return 1
        javac = os.path.join(JDK8, "bin", "java.exe")
        cp = ";".join([os.path.join("app", "target", "classes"),
                       os.path.join("verify", "target", "classes"),
                       os.path.join("verify", "target", "lib", "*")])
        code, out = run([javac, "-Dfile.encoding=UTF-8", "-cp", cp,
                         "com.excel2mysql.verify.GuiDump", java_json, FIX], cwd=JAVA_DIR)
        if code != 0:
            print(out[-4000:])
            print("x [ERROR] GUI-PARITY | Java 侧枚举失败，退出码 %d" % code)
            return 1
    else:
        py_json, java_json = args.py, args.java

    a, b = load(py_json), load(java_json)
    ka, kb = set(a), set(b)
    # env.* 记的是"这台机器上量到的现场值"（窗口实测尺寸、可用区多大）。
    # 两边的屏幕不一样，这类值天生不可比；跨线比的是配置值（*.want）与折行结果（wrap.*）。
    env_keys = sorted({k for k in (ka | kb) if k.startswith("env.")})
    ka -= set(env_keys)
    kb -= set(env_keys)
    only_py = sorted(ka - kb)
    only_java = sorted(kb - ka)
    diffs = []
    grids = []
    for k in sorted(ka & kb):
        if k.endswith(".content"):
            found = diff_grid(k, a[k], b[k])
            if found:
                grids.append((k, found))
            continue
        if canon_key_value(k, a[k]) != canon_key_value(k, b[k]):
            diffs.append(k)

    print("- [INFO] GUI-PARITY | Python 侧 %d 键 | Java 侧 %d 键 | 共有 %d 键"
          % (len(ka), len(kb), len(ka & kb)))
    for k in env_keys:
        print("     现场值（不比）%s | Python=%s Java=%s"
              % (k, one(a.get(k, "-")), one(b.get(k, "-"))))
    for k in only_py:
        print("     仅 Python 有 | %s = %s" % (k, one(a[k])))
    for k in only_java:
        print("     仅 Java 有   | %s = %s" % (k, one(b[k])))
    for k in diffs:
        print("     值不等 %s" % k)
        print("           Python: %s" % one(a[k]))
        print("           Java  : %s" % one(b[k]))
    for k, found in grids:
        print("     产物不等 %s（逐格比，数字按值比）" % k)
        for f in found[:8]:
            print("           %s" % f)
    total = len(only_py) + len(only_java) + len(diffs) + len(grids)
    if total:
        print("x [ERROR] GUI-PARITY | 两条线不一致 %d 处（仅Py %d / 仅Java %d / 值不等 %d / 产物 %d）"
              % (total, len(only_py), len(only_java), len(diffs), len(grids)))
        return 1
    print("+ [OK] GUI-PARITY | 两条 GUI 线枚举出的 %d 项界面事实逐键一致" % len(ka & kb))
    return 0


def one(text, limit=200):
    s = (text or "").replace("\n", " ⏎ ")
    return s if len(s) <= limit else s[:limit] + "…"


if __name__ == "__main__":
    sys.exit(main())
