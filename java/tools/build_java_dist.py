# -*- coding: utf-8 -*-
"""组装 Java 线的 Windows 交付包：程序 jar + 随包 JRE + 启动脚本，并跑剔桩与冒烟两道闸门。

为什么要有这个脚本：Python 线一直有 一键打包-图形版.bat，Java 线只有 构建验证.bat——
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

import io
import os
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
JAVA_DIR = os.path.dirname(HERE)                 # .../excel2mysql/java
ROOT = os.path.dirname(JAVA_DIR)                 # .../excel2mysql
JDK8_DEFAULT = r"C:\Program Files\Java\jdk1.8.0_202"
STUB_PREFIX = "api-ms-win-"
# 与那批转发桩同一来源的 CRT 运行时：PE 头声明的最低系统是 10.0，
# 留在包里就等于把 Win10 下限搬到 Win7 上赌它不被加载。
# msvcr100.dll 不在名单里——JVM 自己链的是 VC2010 运行时，Win7 上装得到，删了直接起不来。
CRT_BATCH = ("ucrtbase.dll", "vcruntime140.dll", "vcruntime140_1.dll",
             "concrt140.dll", "msvcp140.dll", "msvcp140_1.dll", "msvcp140_2.dll")
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
    raise RuntimeError(
        "删不掉旧交付目录 %s：%s\n"
        "      这个目录里有进程还活着——常见的是双击 启动.bat 开着的界面窗口，"
        "或上一次冒烟测试留下的 java.exe，它把 jre\\bin\\server\\jvm.dll 占住。\n"
        "      谁在占：powershell -c \"Get-CimInstance Win32_Process -Filter \"\"name='java.exe'\""
        " | Select ProcessId,CommandLine\"\n"
        "      命令行里带这个目录路径的那条结束掉再重跑本脚本（关界面窗口也算结束）；"
        "急着出包就先换个空目录跑，别删正在用的那份。" % (path, last))


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
    crt = 0
    pdb = 0
    for base, _dirs, files in os.walk(dst_jre):
        for f in files:
            low = f.lower()
            p = os.path.join(base, f)
            if low.startswith(STUB_PREFIX):
                os.remove(p)
                removed += 1
            elif low in CRT_BATCH:
                os.remove(p)
                crt += 1
            elif low.endswith(".pdb"):
                # 调试符号：只在崩溃转储分析时用，交付包里没人用，且单个就有几十兆
                os.remove(p)
                pdb += 1
    return removed + crt, pdb


def count_stubs(path):
    n = 0
    for base, _dirs, files in os.walk(path):
        for f in files:
            low = f.lower()
            if low.startswith(STUB_PREFIX) or low in CRT_BATCH:
                n += 1
    return n


def launcher_text(version):
    """生成启动脚本。

    两条都是踩过的坑：
    1. 不能用 % 格式化拼字符串——批处理里 %% 与 % 含义不同，只有某几行走过格式化的话，
       其余的 %% 会原样落进脚本，变成"成功也报 FAILED"。所以统一用占位符替换。
    2. 脚本正文一律 ASCII，不写中文注释。cmd 按当前代码页逐字节解析，
       UTF-8 中文里出现会被当成命令分隔符的字节时，一行 rem 能被拆成"命令找不到"，
       而这台机器与目标机的代码页并不一样。中文说明放随包的 README.md（项目根那一份）。
    3. 无参数也必须开图形界面：双击 启动.bat 的人要的是窗口，不是用法清单。
       拖文件进来时把文件转给界面（GuiMain 收路径参数并加载）。
    """
    jar = JAR_NAME_TMPL % version
    lines = [
        "@echo off",
        "chcp 65001 >nul",
        "setlocal",
        'cd /d "%~dp0"',
        'set "JAVA=%~dp0jre\\bin\\java.exe"',
        'if not exist "%JAVA%" (',
        "    echo [ERROR] missing jre\\bin\\java.exe - unpack the whole folder.",
        "    pause",
        "    exit /b 2",
        ")",
        'if not exist "__JAR__" (',
        "    echo [ERROR] missing __JAR__ in this folder.",
        "    pause",
        "    exit /b 3",
        ")",
        "rem Always start the GUI. Dropped files are forwarded to the window as paths.",
        '"%JAVA%" -Dfile.encoding=UTF-8 -jar "__JAR__" --gui %*',
        'set "RC=%ERRORLEVEL%"',
        'if not "%RC%"=="0" (',
        "    echo [FAILED] exit code %RC%",
        "    pause",
        ")",
        'if "%~1"=="" pause',
        "exit /b %RC%",
        "",
    ]
    return ("\r\n".join(lines)).replace("__JAR__", jar).encode("utf-8")


def copy_project_doc(dist, version):
    """把项目根那份 README.md 原样拷进包里，连标题用的图标一起。

    以前随包的是打包时渲染的 README.txt（正文在项目侧 java/assets/README.txt）与
    export_manual_txt 渲染的使用说明.txt：仓库里看不见产物那份、产物里看不见仓库改的那份，
    两份会分叉。现在项目里只有一份文档，包里就是它，逐字节相同；文档标题挂了图标，
    所以把 assets/app_icon.png 放到包内的同名相对路径下，图片才解析得出来。
    代价要说清：包里那份不再带"本包剔了几个桩、多大"这类现场数——那些是构建时才知道的数，
    由本脚本打印在构建日志里，不再混进唯一的文档正文。
    """
    src = os.path.join(ROOT, "README.md")
    if not os.path.isfile(src):
        fail("项目里唯一的那份文档不在：%s" % src)
    with io.open(src, encoding="utf-8") as fh:
        text = fh.read()
    if 'src="assets/app_icon.png"' not in text:
        fail("README.md 的标题没挂图标，或图标路径不是 assets/app_icon.png")
    dst = os.path.join(dist, "README.md")
    with open(dst, "wb") as fh:
        fh.write(text.replace("\r\n", "\n").replace("\n", "\r\n").encode("utf-8"))
    icon_src = os.path.join(ROOT, "assets", "app_icon.png")
    if not os.path.isfile(icon_src):
        fail("随包文档要用的图标 assets/app_icon.png 不在仓库里")
    icon_dst = os.path.join(dist, "assets", "app_icon.png")
    if not os.path.isdir(os.path.dirname(icon_dst)):
        os.makedirs(os.path.dirname(icon_dst))
    shutil.copy2(icon_src, icon_dst)
    if os.path.getsize(dst) != len(text.encode("utf-8")) + text.count("\n"):
        fail("包里的 README.md 与仓库那份字节数不对不上（行尾转换算进去也不该差）")
    ok("README.md %d 字符（与仓库同源）+ assets/app_icon.png" % len(text))
    return text


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


def cli_discovery(java_exe, jar, dist, fixtures):
    """验"程序同级 conn.ini 真被自动读到"：在一个毫不相干的临时目录里跑，且不带 -c。

    这条必须单独验：命令行缺配置时的报错是"没指定目标库"，
    跟"没读到随包那份 conn.ini"长得一模一样——只有跑在别处、看来源路径才能分清。
    用 --dry-run 是因为随包模板里 database 有意留空，真跑会被用法检查拦下。
    """
    tmp = tempfile.mkdtemp(prefix="e2m-dist-cli-")
    try:
        cmd = [java_exe, "-Dfile.encoding=UTF-8", "-jar", jar, "--dry-run", fixtures]
        info("配置发现测试：cwd=%s（不带 -c）" % tmp)
        env = dict(os.environ)
        env.pop("JAVA_HOME", None)
        for k in list(env):
            if k.startswith("E2M_"):
                env.pop(k)
        proc = subprocess.Popen(cmd, cwd=tmp, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, env=env)
        out = proc.communicate()[0].decode("utf-8", "replace")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    want = os.path.join(dist, "conn.ini").replace("/", "\\")
    hit = None
    for line in out.splitlines():
        if "配置来源" in line:
            hit = line.strip()
    if hit is None:
        print(out[-600:])
        fail("没打印配置来源，随包 conn.ini 的自动发现没验成")
    info(hit[:160])
    if want.lower() not in hit.lower():
        fail("自动读到的不是随包那份 conn.ini（期望 %s）" % want)
    ok("在别的工作目录里自动读到产物同级 conn.ini")


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
    # 依赖树里的 log4j-api / slf4j-api 没有实现时会各打几行"找不到日志实现"，
    # 其中一行开头就是 ERROR。它跟着 stdout 一起进现场日志，读的人只会以为导表失败了。
    noise = [l.strip() for l in text.splitlines()
             if "SLF4J:" in l or "could not find a logging provider" in l]
    if noise:
        fail("输出里混进第三方日志框架的噪声（会被现场当成报错）：%s" % noise[0][:120])
    ok("输出干净，没有第三方日志框架的噪声行")


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
    info("输出目录 %s" % dist)
    os.makedirs(dist)

    # jar 放产物根，不放 app\ 子目录：命令行找随包配置用的是"jar 同级"，
    # 只有 jar 在根上，conn.ini 才真是用户看到的"程序旁边那份"
    jar_dst = os.path.join(dist, os.path.basename(jar_src))
    shutil.copy2(jar_src, jar_dst)
    conn_src = os.path.join(JAVA_DIR, "assets", "conn.ini")
    if not os.path.isfile(conn_src):
        fail("缺模板 %s：命令行会报「没指定目标库」，跟没带配置长得一模一样" % conn_src)
    shutil.copy2(conn_src, os.path.join(dist, "conn.ini"))
    stubs_src = count_stubs(src_jre)
    removed, pdb = copy_jre(src_jre, os.path.join(dist, "jre"))
    info("剔除转发桩与 CRT 批 %d 个（源 JRE 里有 %d 个）、调试符号 %d 个"
         % (removed, stubs_src, pdb))

    with open(os.path.join(dist, "启动.bat"), "wb") as fh:
        bat = launcher_text(version)
        # 这两条各对应一个刚踩过的坑：漏了 --gui，双击只会打印 CLI 用法；
        # 正文混进中文，cmd 按代码页逐字节解析时能把一行 rem 拆成"命令找不到"。
        if b"--gui" not in bat:
            fail("启动.bat 没有 --gui：双击 启动.bat 打开的会是用法清单，不是窗口")
        non_ascii = sum(1 for b in bat if b > 127)
        if non_ascii:
            fail("启动.bat 里有 %d 个非 ASCII 字节：目标机代码页不同会被 cmd 解析错" % non_ascii)
        if b"\r\n" not in bat or bat.count(b"\n") != bat.count(b"\r\n"):
            fail("启动.bat 行尾不是全 CRLF")
        fh.write(bat)
    ok("启动.bat：走图形界面、纯 ASCII、CRLF 行尾")
    size = human(dir_size(dist))
    doc = copy_project_doc(dist, version)

    for want in ("conn.ini", "README.md", os.path.join("assets", "app_icon.png")):
        if not os.path.isfile(os.path.join(dist, want)):
            fail("产物里缺 %s" % want)
    ok("产物含 conn.ini、README.md 与 assets/app_icon.png")
    # 文档里的开关名必须真是这个 jar 认的：Python 命令行版那套 --run-sql / --swap-prefix
    # 抄到这里会让现场敲出一条"未知选项"。只核能照抄的那几行（含 java.exe -jar 或 启动.bat
    # 的示例）——"本包没有 --run-sql"这种提醒也得允许写出来，否则守卫会把解释当成错误
    copyable = [ln for ln in doc.split("\n")
                if "java.exe -jar" in ln or "启动.bat" in ln]
    for ghost in ("--run-sql", "--swap-prefix", "--drop-if-exists", "--keep-temp"):
        hit = [ln for ln in copyable if ghost in ln]
        if hit:
            fail("README.md 的可照抄命令里写了本包不认的开关 %s：%s" % (ghost, hit[0]))
    for must in ("--no-sql", "--no-excel", "--gui", "Windows 7", "KB2999226", "退出码"):
        if must not in doc:
            fail("README.md 缺了该有的内容：%s" % must)
    if ("%s" % version) not in doc:
        fail("README.md 里没有本版版本号 %s，那份文档的版本口径与构建脱了" % version)
    ok("README.md 的开关名、三条线内容与版本号都对得上（可照抄行 %d 条）" % len(copyable))

    left = count_stubs(os.path.join(dist, "jre"))
    if left:
        fail("剔桩没剔干净，包里还剩 %d 个 api-ms-win-* / CRT 批文件" % left)
    ok("剔桩闸门：包内 api-ms-win-* 与 ucrt/vcruntime/concrt/msvcp 为 0")

    major = max_class_major(jar_src)
    if major > 52:
        fail("交付 jar 里有主版本 %d 的 class，Win7 上的 Java 8 加载不了" % major)
    ok("class 主版本 %d <= 52（Java 8）" % major)

    java_exe = os.path.join(dist, "jre", "bin", "java.exe")
    fixtures = os.path.join(JAVA_DIR, "verify", "fixtures")
    smoke(java_exe, jar_dst, fixtures)
    cli_discovery(java_exe, jar_dst, dist, fixtures)

    print("+ [OK] DIST | 交付包 %s | %s" % (os.path.basename(dist), size))
    return 0


if __name__ == "__main__":
    sys.exit(main())
