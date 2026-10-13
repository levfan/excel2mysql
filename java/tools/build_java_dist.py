# -*- coding: utf-8 -*-
"""组装 Java 线的 Windows 交付包：程序 jar + 随包 JRE + 启动脚本，并跑剔桩与冒烟两道闸门。

为什么要有这个脚本：Python 线一直有 一键打包.bat，Java 线只有 构建验证.bat——
产物是一个"得自己装 JDK 才能跑"的 jar，用户那条"最终构建产物要把 java 环境带上"的要求没交付。

三条硬规矩，都在代码里当着：
1. 随包 JRE 必须剔掉 api-ms-win-* 那批转发桩 DLL。留着它们，Windows 7 在没有
   UCRT 更新时会直接报"找不到 API-MS-WIN-CORE-XXX.dll"，整个包在目标机上起不来。
2. 冒烟测试用**包内那个 java.exe** 跑 --gui --selftest，不是用构建机的 JDK。
   否则测的是"我这台机器能跑"，不是"这个包能跑"。
3. 只剔确定不需要的东西（桩 DLL 与 .pdb），不顺手删 JavaFX、字体、安全策略这类
   "看着用不到"的文件——删错了在 Win7 上才爆，比包大几十兆难查得多。
"""
from __future__ import print_function

import os
import shutil
import subprocess
import sys
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
JAVA_DIR = os.path.dirname(HERE)                 # .../excel2mysql/java
ROOT = os.path.dirname(JAVA_DIR)                 # .../excel2mysql
JDK8_DEFAULT = r"C:\Program Files\Java\jdk1.8.0_202"
STUB_PREFIX = "api-ms-win-"
JAR_NAME_TMPL = "excel2mysql-%s.jar"


def fail(msg):
    print("x [ERROR] DIST | " + msg)
    sys.exit(1)


def info(msg):
    print("- [INFO] DIST | " + msg)


def ok(msg):
    print("+ [OK] DIST | " + msg)


def rmtree_writable(path):
    """删目录树：只读文件先清属性，瞬时占用（杀软/索引器）重试几次。

    随包 JRE 里的 bin\\server\\classes.jsa（CDS 归档）在 JDK 8 上是带只读属性的，
    直接 rmtree 会抛 PermissionError；删到一半崩了还会留下一个"看着像交付包"的残目录。
    """
    def on_error(func, p, exc_info):
        # shutil 传的第三个参数是 (type, value, traceback) 三元组，不是异常本身
        err = exc_info[1]
        try:
            if getattr(err, "winerror", None) == 5:
                os.chmod(p, 0o700)
            func(p)
        except OSError:
            raise err

    if not os.path.isdir(path):
        return
    last = None
    for attempt in range(6):
        try:
            shutil.rmtree(path, onerror=on_error)
            return
        except OSError as e:
            last = e
            time.sleep(0.5 * (attempt + 1))
    raise RuntimeError("删不掉旧交付目录 %s：%s" % (path, last))


def pom_version():
    pom = os.path.join(JAVA_DIR, "pom.xml")
    with open(pom, encoding="utf-8") as fh:
        text = fh.read()
    head = text.split("<modules>", 1)[0]
    tag = "<version>"
    i = head.index(tag) + len(tag)
    return head[i:head.index("</version>", i)].strip()


def dir_size(path):
    total = 0
    for base, _dirs, files in os.walk(path):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(base, f))
            except OSError:
                pass
    return total


def human(n):
    return "%.1f MB" % (n / 1048576.0)


def copy_jre(src_jre, dst_jre):
    """整份 JRE 拷过来，再按白名单剔。先全拷再删，比按扩展名挑着拷更不容易漏运行期文件。"""
    rmtree_writable(dst_jre)
    info("拷贝 JRE：%s" % src_jre)
    shutil.copytree(src_jre, dst_jre)
    removed = 0
    pdb = 0
    for base, _dirs, files in os.walk(dst_jre):
        for f in files:
            low = f.lower()
            p = os.path.join(base, f)
            if low.startswith(STUB_PREFIX):
                os.remove(p)
                removed += 1
            elif low.endswith(".pdb"):
                # 调试符号：只在崩溃转储分析时用，交付包里没人用，且单个就有几十兆
                os.remove(p)
                pdb += 1
    return removed, pdb


def count_stubs(path):
    n = 0
    for base, _dirs, files in os.walk(path):
        for f in files:
            if f.lower().startswith(STUB_PREFIX):
                n += 1
    return n


def launcher_text(version):
    """生成启动脚本。

    这里绝不能用 % 格式化拼字符串：批处理里 %% 与 % 的含义不同，
    一旦只有某几行走过格式化，其余的 %% 会原样落进脚本，变成"成功也报 FAILED"。
    所以统一用占位符替换，正文里只写单个 %。
    """
    jar = JAR_NAME_TMPL % version
    lines = [
        "@echo off",
        "chcp 65001 >nul",
        "setlocal",
        'cd /d "%~dp0"',
        'set "JAVA=%~dp0jre\\bin\\java.exe"',
        'if not exist "%JAVA%" (',
        "    echo [ERROR] 包里的 jre\\bin\\java.exe 不见了，请重新解压整个目录。",
        "    pause",
        "    exit /b 2",
        ")",
        "rem 用包内运行时，不碰系统 PATH 里的 java：目标机可能根本没装 JDK，也可能装的是别的版本",
        "rem 参数原样转发：把 .xlsx/.xls/.zip 拖到本脚本图标上就能直接加载",
        'if not exist "app\\__JAR__" (',
        "    echo [ERROR] 包里的 app\\__JAR__ 不见了。",
        "    pause",
        "    exit /b 3",
        ")",
        '"%JAVA%" -Dfile.encoding=UTF-8 -jar "app\\__JAR__" %*',
        'set "RC=%ERRORLEVEL%"',
        'if not "%RC%"=="0" (',
        "    echo [FAILED] exit code %RC%",
        "    pause",
        ")",
        "if \"%1\"==\"\" pause",
        "exit /b %RC%",
        "",
    ]
    return ("\r\n".join(lines)).replace("__JAR__", jar).encode("utf-8")


def readme_text(version, stubs, size):
    jar = JAR_NAME_TMPL % version
    body = [
        "Excel → MySQL 批量导入工具（Java 线）v%s" % version,
        "",
        "怎么用",
        "  双击 启动.bat。想一次带上文件，把 .xlsx/.xls/.zip 拖到 启动.bat 图标上即可。",
        "  命令行：启动.bat --gui --selftest   （自检，不开真库也能跑）",
        "",
        "目录",
        "  启动.bat            启动脚本，用包内那个 java.exe，不依赖系统装没装 Java",
        "  app\\%s   程序本体（已把所有依赖打成一个 jar）" % jar,
        "  jre\\               随包的 Java 8 运行时",
        "  README.txt          本文件",
        "",
        "目标系统",
        "  64 位 Windows 7 及以上。随包 JRE 已剔除 %d 个 api-ms-win-* 转发桩 DLL，" % stubs,
        "  留着它们在没有 UCRT 更新的 Win7 上会直接起不来。",
        "",
        "体积",
        "  整个目录约 %s，其中绝大部分是 Java 运行时。" % size,
        "",
    ]
    return ("\r\n".join(body)).encode("utf-8")


def max_class_major(jar_path):
    """扫 jar 里每个 class 的主版本号。

    魔数是 CA FE BA BE 四个字节，不是 ASCII 的 "cafe"——写错的话这里永远返回 0，
    闸门就成了恒绿的假检查。
    """
    magic = b"\xca\xfe\xba\xbe"
    top = 0
    seen = 0
    with zipfile.ZipFile(jar_path) as zf:
        for name in zf.namelist():
            if not name.endswith(".class"):
                continue
            with zf.open(name) as fh:
                head = fh.read(8)
            if len(head) >= 8 and head[:4] == magic:
                seen += 1
                m = (head[6] << 8) | head[7]
                if m > top:
                    top = m
    if seen == 0:
        fail("jar 里一个 class 都没扫到，版本闸门等于没检查：%s" % jar_path)
    info("扫到 class 文件 %d 个" % seen)
    return top


def smoke(java_exe, jar, fixtures):
    """冒烟：用包内的运行时跑界面自检。跑不过就说明这个包在目标机上也是起不来的。"""
    cmd = [java_exe, "-Dfile.encoding=UTF-8", "-jar", jar,
           "--gui", "--selftest", fixtures]
    info("冒烟测试：%s" % " ".join(os.path.basename(c) for c in cmd[:2] + cmd[3:]))
    env = dict(os.environ)
    env.pop("JAVA_HOME", None)
    proc = subprocess.Popen(cmd, cwd=JAVA_DIR, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, env=env)
    out = proc.communicate()[0]
    code = proc.returncode
    text = out.decode("utf-8", "replace")
    for line in text.splitlines():
        if "GUI-SELFTEST" in line or "ERROR" in line:
            print("     " + line.strip()[:160])
    if code != 0:
        fail("包内运行时跑自检失败，退出码 %d" % code)
    ok("包内运行时自检通过（退出码 0）")


def main():
    version = pom_version()
    jar_src = os.path.join(JAVA_DIR, "app", "target", JAR_NAME_TMPL % version)
    if not os.path.isfile(jar_src):
        fail("找不到 %s；先跑 构建验证.bat 产出程序包" % jar_src)
    jdk = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("JDK8", JDK8_DEFAULT)
    src_jre = os.path.join(jdk, "jre")
    if not os.path.isfile(os.path.join(src_jre, "bin", "java.exe")):
        fail("JDK 8 里没有可用的 jre\\bin\\java.exe：%s" % src_jre)

    dist = os.path.join(JAVA_DIR, "dist", "excel2mysql-java-%s-win64" % version)
    rmtree_writable(dist)
    os.makedirs(os.path.join(dist, "app"))
    info("输出目录 %s" % dist)

    shutil.copy2(jar_src, os.path.join(dist, "app", os.path.basename(jar_src)))
    stubs_src = count_stubs(src_jre)
    removed, pdb = copy_jre(src_jre, os.path.join(dist, "jre"))
    info("剔除转发桩 DLL %d 个（源 JRE 里有 %d 个）、调试符号 %d 个" % (removed, stubs_src, pdb))

    with open(os.path.join(dist, "启动.bat"), "wb") as fh:
        fh.write(launcher_text(version))
    size = human(dir_size(dist))
    with open(os.path.join(dist, "README.txt"), "wb") as fh:
        fh.write(readme_text(version, removed, size))

    left = count_stubs(os.path.join(dist, "jre"))
    if left:
        fail("剔桩没剔干净，包里还剩 %d 个 api-ms-win-*" % left)
    ok("剔桩闸门：包内 api-ms-win-* 为 0")

    major = max_class_major(jar_src)
    if major > 52:
        fail("交付 jar 里有主版本 %d 的 class，Win7 上的 Java 8 加载不了" % major)
    ok("class 主版本 %d <= 52（Java 8）" % major)

    smoke(os.path.join(dist, "jre", "bin", "java.exe"),
          os.path.join(dist, "app", os.path.basename(jar_src)),
          os.path.join(JAVA_DIR, "verify", "fixtures"))

    print("+ [OK] DIST | 交付包 %s | %s" % (os.path.basename(dist), size))
    return 0


if __name__ == "__main__":
    sys.exit(main())
