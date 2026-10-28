# -*- coding: utf-8 -*-
"""重跑生成器，把生成出来的内容与仓库里那份逐字节比——不一样就红。

HelpDoc.java 与 SqlSamples.java 都不是手写的：正文来自 ../help_doc.py，
SQL 示例来自 ../sql_samples.py（示例库的唯一来源），由本目录下的导出脚本生成。
少了这道闸，改了 Python 那份却忘了重导，Java 界面照旧显示旧文案，
双端对撞器又只比目录标题（正文不参与跨线比对），这种漂移会一路跟到交付物里。

跑法（在 java/ 目录下）：
    python tools/check_generated.py        # 只判定，不改仓库
    python tools/check_generated.py --write # 确实要更新就带上 --write
"""
import argparse
import difflib
import io
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
JAVA_DIR = os.path.dirname(HERE)

# (生成脚本, 产物相对 java/ 的路径)
TARGETS = [
    ("export_help_java.py", os.path.join("app", "src", "main", "java",
                                         "com", "excel2mysql", "gui", "HelpDoc.java")),
    ("export_sql_samples.py", os.path.join("app", "src", "main", "java",
                                           "com", "excel2mysql", "gui", "SqlSamples.java")),
]


def read(path):
    with io.open(path, "rb") as fh:
        return fh.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true",
                    help="允许把重生成出来的内容留在工作区（默认只判定并还原原样）")
    args = ap.parse_args()
    drifted = []
    for script, rel in TARGETS:
        dst = os.path.join(JAVA_DIR, rel)
        if not os.path.exists(dst):
            print("x [ERROR] GENERATED | 产物不存在：%s" % rel)
            drifted.append((rel, "文件缺失"))
            continue
        before = read(dst)
        proc = subprocess.Popen([sys.executable, os.path.join(HERE, script)],
                                cwd=JAVA_DIR, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT)
        out = proc.communicate()[0].decode("utf-8", "replace")
        if proc.returncode != 0:
            print("x [ERROR] GENERATED | %s 跑不起来（退出码 %d）：%s"
                  % (script, proc.returncode, out.strip()[:200]))
            return 1
        after = read(dst)
        if after == before:
            print("- [INFO] GENERATED | %s 与源一致（%s）" % (rel, out.strip()))
            continue
        a = before.decode("utf-8", "replace").splitlines()
        b = after.decode("utf-8", "replace").splitlines()
        diff = [l for l in difflib.unified_diff(a, b, lineterm="") if
                l.startswith(("+", "-")) and not l.startswith(("+++", "---"))]
        print("x [ERROR] GENERATED | %s 与源不一致：改了 Python 那份没重导（差异 %d 行）"
              % (rel, len(diff)))
        for l in diff[:12]:
            print("      %s" % l[:160])
        if not args.write:
            with io.open(dst, "wb") as fh:
                fh.write(before)          # 判定模式下还原，工作区不该被检查器改动
        drifted.append((rel, len(diff)))
    if drifted:
        print("x [ERROR] GENERATED | %d 份生成物与源漂移： %s"
              % (len(drifted), "、".join(r for r, _ in drifted)))
        print("      确认要以 Python 那份为准就重跑对应导出脚本（或本脚本带 --write）")
        return 1
    print("+ [OK] GENERATED | 两份生成物（HelpDoc / SqlSamples）都与各自源逐字节一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
