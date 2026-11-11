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
import re
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

# 随包文档的小节与篇幅档位与两条 Python 线共用一个来源：这一轮 README 收成用户向八节，
# 只有 build_exe.py 挂了断言，出包侧没人核，直到打包才炸。三条线都读同一份常量才不会漏。
sys.path.insert(0, ROOT)
from _pack_common import (README_LEN_RANGE, README_SECTIONS, SHIPPED_TEXT_FILES,  # noqa: E402
                          lone_lf_count, write_shipped_text)


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
    """生成「启动.bat」：双击就是开窗口，而且**不留黑框**。

    现场反馈「双击有个 cmd 黑框一直挂着」：旧的这一行用的是 jre\\bin\\java.exe，
    那是控制台子系统程序，cmd 会一直挂在那里等 JVM 退出，于是窗口从头到尾都在。
    javaw.exe 是同一份 JVM 的窗口子系统版本，不挂控制台；批处理把它拉起来就自己退出了，
    窗口跟着关掉，只剩 Spline 的界面。默认入口给的是「启动.vbs」（连闪一下都没有），
    这个 bat 是 .vbs 被组策略挡掉时的退路——所以它自己也得是不留黑框的。

    几条都是踩过的坑：
    1. 不能用 % 格式化拼字符串——批处理里 %% 与 % 含义不同，只有某几行走过格式化的话，
       其余的 %% 会原样落进脚本，变成"成功也报 FAILED"。所以统一用占位符替换。
    2. 脚本正文一律 ASCII，不写中文注释。cmd 按当前代码页逐字节解析，
       UTF-8 中文里出现会被当成命令分隔符的字节时，一行 rem 能被拆成"命令找不到"，
       而这台机器与目标机的代码页并不一样。中文说明放随包的 README.md（项目根那一份）。
       正文纯 ASCII 之后 chcp 65001 那行也一起删了——它每秒都在给启动加延迟。
    3. 无参数也必须开图形界面：双击的人要的是窗口，不是用法清单。
       拖文件进来时把文件转给界面（GuiMain 收路径参数并加载）。
    4. 只在「文件缺失」这种一眼要人处理的场合才 pause：正常启动不 pause，
       否则关掉界面后又会留一个"按任意键继续"的黑框，正是这次要治的病。
    """
    jar = JAR_NAME_TMPL % version
    lines = [
        "@echo off",
        "rem Start the Swing GUI through javaw.exe: no console is attached, no black window.",
        "rem Kept as the fallback entry for machines where .vbs scripts are blocked.",
        "setlocal",
        'cd /d "%~dp0"',
        'set "JAVAW=%~dp0jre\\bin\\javaw.exe"',
        'set "JAR=%~dp0__JAR__"',
        'if not exist "%JAVAW%" goto no_jre',
        'if not exist "%JAR%" goto no_jar',
        "rem start returns at once: the console closes even while javaw keeps running.",
        'start "" "%JAVAW%" -Dfile.encoding=UTF-8 -jar "%JAR%" --gui %*',
        "exit /b 0",
        "",
        ":no_jre",
        "echo [ERROR] missing jre\\bin\\javaw.exe - unpack the whole folder.",
        "pause",
        "exit /b 2",
        "",
        ":no_jar",
        "echo [ERROR] missing __JAR__ in this folder.",
        "pause",
        "exit /b 3",
        "",
    ]
    return ("\r\n".join(lines)).replace("__JAR__", jar).encode("ascii")


def debug_launcher_text(version):
    """生成「启动-调试.bat」：故意留着黑框，给排障用。

    javaw 那一路没有控制台，JVM 起不来或者启动阶段就崩的时候，界面上什么都不会出现，
    现场只能看到"双击没反应"。这一份用 java.exe 起，标准输出/错误都留在窗口里，
    退出码也打出来，最后 pause 住不让人看不见——排障要的正是这个黑框。
    """
    jar = JAR_NAME_TMPL % version
    lines = [
        "@echo off",
        "rem Diagnostic entry: keeps the console so start-up errors stay readable.",
        "chcp 65001 >nul",
        "setlocal",
        'cd /d "%~dp0"',
        'set "JAVA=%~dp0jre\\bin\\java.exe"',
        'if not exist "%JAVA%" (',
        "    echo [ERROR] missing jre\\bin\\java.exe - unpack the whole folder.",
        "    pause",
        "    exit /b 2",
        ")",
        'if not exist "%~dp0__JAR__" (',
        "    echo [ERROR] missing __JAR__ in this folder.",
        "    pause",
        "    exit /b 3",
        ")",
        '"%JAVA%" -Dfile.encoding=UTF-8 -jar "%~dp0__JAR__" --gui %*',
        'set "RC=%ERRORLEVEL%"',
        "echo [EXIT] %RC%",
        "pause",
        "exit /b %RC%",
        "",
    ]
    return ("\r\n".join(lines)).replace("__JAR__", jar).encode("ascii")


def vbs_launcher_text(version):
    """生成「启动.vbs」：全程不出现任何窗口，连一闪都没有。

    双击 .bat 时 cmd 总要先开一个控制台，哪怕脚本只跑 50 毫秒也会闪一下。
    .vbs 由 wscript.exe 执行，本来就没有控制台，于是这条路径是零窗口的。
    缺文件用 MsgBox 说人话（弹窗总比"双击没反应"强），正常启动什么都不弹。

    正文同样只用 ASCII：VBS 里的字符串字面量按脚本文件的编码读，写中文就得跟着
    操心 BOM 与代码页，不如把中文留给 README 与界面。
    """
    jar = JAR_NAME_TMPL % version
    lines = [
        "' Start the Swing GUI with no console window at all.",
        "' This is the recommended entry: unlike a .bat, wscript.exe has no console to flash.",
        "Option Explicit",
        "Dim fso, sh, here, javaw, jarPath, cmd, i, rc, q",
        'Set fso = CreateObject("Scripting.FileSystemObject")',
        'Set sh = CreateObject("WScript.Shell")',
        "here = fso.GetParentFolderName(WScript.ScriptFullName)",
        'javaw = here & "\\jre\\bin\\javaw.exe"',
        'jarPath = here & "\\__JAR__"',
        "If Not fso.FileExists(javaw) Then",
        '    MsgBox "missing jre\\bin\\javaw.exe - unpack the whole folder.", 16, "excel2mysql"',
        "    WScript.Quit 2",
        "End If",
        "If Not fso.FileExists(jarPath) Then",
        '    MsgBox "missing __JAR__ in this folder.", 16, "excel2mysql"',
        "    WScript.Quit 3",
        "End If",
        "sh.CurrentDirectory = here",
        # 引号一律走 Chr(34)：以前这里拿 """" 拼整条命令行，数错一个引号整份脚本
        # 就是「未结束的字符串常量」，双击毫无反应——而且只有真跑才看得出来。
        "q = Chr(34)",
        'cmd = q & javaw & q & " -Dfile.encoding=UTF-8 -jar " & q & jarPath & q & " --gui"',
        "For i = 0 To WScript.Arguments.Count - 1",
        '    cmd = cmd & " " & q & WScript.Arguments(i) & q',
        "Next",
        "On Error Resume Next",
        # 等着它退出（第三个参数 True）才能拿到返回码：以前不等，javaw 起不来
        # 就一点动静都没有，用户只能看到「双击没反应」。wscript 无控制台，等的时候不占界面。
        "rc = sh.Run(cmd, 1, True)",
        "If Err.Number <> 0 Then",
        '    MsgBox "could not start java: " & Err.Description, 16, "excel2mysql"',
        "    WScript.Quit 4",
        "End If",
        "If rc <> 0 Then",
        '    MsgBox "java exited with code " & rc & "." & vbCrLf _',
        '        & "Use the debug .bat in this folder (it keeps a console) to see why.", 48, "excel2mysql"',
        "    WScript.Quit rc",
        "End If",
        "",
    ]
    return ("\r\n".join(lines)).replace("__JAR__", jar).encode("ascii")


# 三个入口各自的写法要求：用哪个 java、许不许有 pause 之类，都按「这一份的角色」判，
# 不是一刀切——调试那一份故意留着黑框，拿"不许 pause"去卡它就是把排障手段卡死
LAUNCHERS = (
    # 文件名, 生成函数, 必须包含, 不许包含
    ("启动.bat", launcher_text,
     ("javaw.exe", "--gui"),
     ("jre\\bin\\java.exe",)),
    ("启动-调试.bat", debug_launcher_text,
     ("jre\\bin\\java.exe", "--gui", "pause"),
     ()),
    ("启动.vbs", vbs_launcher_text,
     # 引号只许走 Chr(34)；`--gui` 必须落在 jar 路径的闭合引号之后，
     # 否则 Java 收到的 -jar 参数就是 "xxx.jar --gui" 整串，一样起不来
     ("javaw.exe", "--gui", "MsgBox", "q = Chr(34)",
      '-jar " & q & jarPath & q & " --gui"', "exited with code"),
     ("jre\\bin\\java.exe",)),
)


def _unclosed_line(blob, name):
    """逐行走一遍 VBS 字符串，返回第一处「行尾还停在字符串里」的行号与原文。

    这不是锦上添花的 lint：`启动.vbs` 曾经因为拼命令行时多写一个引号（15 个，奇数），
    整份脚本就是「未结束的字符串常量」，双击连窗都不弹。而本机的命令校验把脚本宿主
    按 LOLBin 拦着，实跑一遍做不到，于是错到交付都没人发现。引号必须靠读就能判死。
    """
    if not name.lower().endswith(".vbs"):
        return None
    for no, raw in enumerate(blob.decode("ascii").split("\r\n"), 1):
        line = raw.strip()
        if not line or line.startswith("'"):
            continue
        inside = False
        for ch in raw:
            if ch == '"':
                inside = not inside     # 相邻两个引号互为转义，一进一出仍然回到原状态
        if inside:
            return no, raw
    return None


def write_launchers(dist, version):
    """把三个入口写进交付目录，当场把各自那条硬要求核一遍。"""
    for name, make, must, forbid in LAUNCHERS:
        blob = make(version)
        bad = [s for s in must if s.encode("ascii") not in blob]
        if bad:
            fail("%s 少了该有的内容：%s" % (name, "、".join(bad)))
        hit = [s for s in forbid if s.encode("ascii") in blob]
        if hit:
            fail("%s 里不该出现 %s：那一份的角色不对" % (name, "、".join(hit)))
        non_ascii = sum(1 for b in blob if b > 127)
        if non_ascii:
            fail("%s 里有 %d 个非 ASCII 字节：目标机代码页不同会被解析错" % (name, non_ascii))
        if b"\r\n" not in blob or blob.count(b"\n") != blob.count(b"\r\n"):
            fail("%s 行尾不是全 CRLF" % name)
        broken = _unclosed_line(blob, name)
        if broken:
            fail("%s 第 %d 行引号没配平（字符串没闭合，脚本根本解析不了）：%s"
                 % (name, broken[0], broken[1].strip()))
        with open(os.path.join(dist, name), "wb") as fh:
            fh.write(blob)
    ok("启动.bat（javaw，无黑框）、启动-调试.bat（留控制台）、启动.vbs（零窗口）：纯 ASCII、CRLF、引号配平")


def copy_project_doc(dist):
    """把项目根那份 README.md 放进包里（行尾换成 CRLF），连标题用的图标一起。

    以前随包的是打包时渲染的 README.txt（正文在项目侧 java/assets/README.txt）与
    export_manual_txt 渲染的使用说明.txt：仓库里看不见产物那份、产物里看不见仓库改的那份，
    两份会分叉。现在项目里只有一份文档，包里就是它，只把行尾换成记事本认得的那种；
    文档标题挂了图标，所以把 assets/app_icon.svg 放到包内的同名相对路径下，图片才解析得出来。
    代价要说清：包里那份不再带"本包剔了几个桩、多大"这类现场数——那些是构建时才知道的数，
    由本脚本打印在构建日志里，不再混进唯一的文档正文。
    """
    src = os.path.join(ROOT, "README.md")
    if not os.path.isfile(src):
        fail("项目里唯一的那份文档不在：%s" % src)
    with io.open(src, encoding="utf-8") as fh:
        text = fh.read()
    if 'src="assets/app_icon.svg"' not in text:
        fail("README.md 的标题没挂图标，或图标路径不是 assets/app_icon.svg")
    if not README_LEN_RANGE[0] < len(text) < README_LEN_RANGE[1]:
        fail("README.md %d 字符，不在 %d–%d 这一档：短了是被砍成空壳，长了是构建细节又长回用户向文档"
             % ((len(text),) + README_LEN_RANGE))
    _miss = [s for s in README_SECTIONS if s not in text]
    if _miss:
        fail("README.md 缺了现场要用的小节：%s" % "、".join(_miss))
    dst = os.path.join(dist, "README.md")
    write_shipped_text(dst, text.encode("utf-8"))
    icon_src = os.path.join(ROOT, "assets", "app_icon.svg")
    if not os.path.isfile(icon_src):
        fail("随包文档要用的图标 assets/app_icon.svg 不在仓库里")
    icon_dst = os.path.join(dist, "assets", "app_icon.svg")
    if not os.path.isdir(os.path.dirname(icon_dst)):
        os.makedirs(os.path.dirname(icon_dst))
    shutil.copy2(icon_src, icon_dst)
    if lone_lf_count(dst):
        fail("包里的 README.md 还有纯 LF 行尾，Win7 记事本打开是一整行")
    with io.open(dst, "rb") as fh:
        shipped = fh.read()
    if shipped.replace(b"\r\n", b"\n") != text.encode("utf-8"):
        fail("包里的 README.md 内容与仓库那份对不上（只许行尾不同）")
    ok("README.md %d 字符（内容与仓库同一份，行尾转成 CRLF）+ assets/app_icon.svg" % len(text))
    return text


DRIVERS_NOTE = u"""达梦（DM8）JDBC 驱动放置处

第五个页签「达梦 → MySQL」要连达梦库读表结构。它用的驱动不在本包里
（第三方许可与包体的原因），第一次用请自己补一步：

1. 在装了达梦的机器上找驱动 jar：
   达梦安装目录\\drivers\\java\\DmJdbcDriver18.jar
   本包自带 Java 8 运行环境，请选支持 JDK 1.8 的那一版驱动。
2. 把这个 jar 复制进本文件所在的 drivers 文件夹，文件名不用改。
3. 重开程序，切到「达梦 → MySQL」页，填主机、端口（达梦默认 5236）、
   用户名、口令与模式名（通常就是登录用户名，例如 SYSDBA），
   点「连达梦并列表」。

不放会怎样：只有这一页连不上达梦，点下去会在本页日志里写清缺什么、放到哪；
其余四个页签与命令行版都不受影响。

放别处读不到：程序只扫它自己同级的那个 drivers 文件夹，
不扫当前工作目录，也不读系统 CLASSPATH。多个 jar 会逐个试，
挑第一个认 jdbc:dm:// 这条串的；换了 jar 要重开程序才生效。
"""




DM_DRIVER_STAGE = os.path.join(JAVA_DIR, "assets", "drivers")

DRIVERS_NOTE_BUNDLED = u"""达梦（DM8）JDBC 驱动

本包已随带驱动：DmJdbcDriver8 系列见本目录下的 %s，
第五个页签「达梦 → MySQL」开箱就能连达梦，不用再做放置这一步。

现场达梦版本与本包这份差得远、或连上报驱动版本不匹配时，换掉本目录里的 jar 即可：
把达梦安装目录 drivers\\java 下对应版本的 DmJdbcDriver*.jar 放进来（文件名不用改），
删掉原来那份，重开程序。程序只扫本目录，多个 jar 会逐个试，
挑第一个认 jdbc:dm:// 这条串的；换了 jar 要重开程序才生效。

连不上时看本页日志：它会把「没找到驱动」「驱动不认这条连接串」「网络通信异常」
三种情况分开说，分别对应放错目录、地址端口写错、达梦服务没起或防火墙挡了。

不放驱动会怎样：只有第五页连不上达梦，其余四个页签与命令行版都不受影响。
"""


def write_drivers_dir(dist):
    """预建 drivers 目录并放一份放置说明：达梦驱动不随包分发，但放置口得在包里看得见。

    没有这一层时，用户要自己凭日志里那句话去新建目录、猜目录名；打包时预建好，
    打开包就能看见"驱动放这儿"，文件名也不要求他改。
    """
    dst_dir = os.path.join(dist, "drivers")
    if not os.path.isdir(dst_dir):
        os.makedirs(dst_dir)
    # 现场拿到的驱动 jar 放在 java/assets/drivers/（不入库，公开仓库不带第三方二进制）：
    # 有就跟着包走，用户不用自己找 jar；没有就只留目录与说明，由用户按说明放。
    bundled = []
    if os.path.isdir(DM_DRIVER_STAGE):
        for name in sorted(os.listdir(DM_DRIVER_STAGE)):
            if name.lower().endswith(".jar"):
                shutil.copy2(os.path.join(DM_DRIVER_STAGE, name),
                             os.path.join(dst_dir, name))
                bundled.append(name)
    note = (DRIVERS_NOTE_BUNDLED % "、".join(bundled)) if bundled else DRIVERS_NOTE
    dst = os.path.join(dst_dir, "README.txt")
    write_shipped_text(dst, note.encode("utf-8"))
    if lone_lf_count(dst):
        fail("drivers/README.txt 有纯 LF 行尾，记事本打开是一整行")
    for name in bundled:
        got = os.path.join(dst_dir, name)
        if not os.path.isfile(got) or os.path.getsize(got) == 0:
            fail("驱动 jar 没跟着进包：%s" % name)
    ok("预建 drivers 目录 + 放置说明（README.txt，CRLF）%s"
       % ("，随包带驱动 " + "、".join(bundled) if bundled else "（本次未随包带驱动）"))


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


def check_build_stamp(jar_path):
    """交付 jar 里必须烧着真提交号与构建日期。

    「关于」里那一行提交号的悬浮提示写着"交付包里是打包那一刻烧进去的那一份"，而提交号只有走
    构建验证.bat 才会以 -Dgit.commit 传给 maven：绕过闸门直跑 mvn 出来的 jar 那一格是空的，
    用户打开关于框看到的就是一句兑现不了的话。空提交号一律拒绝出包。
    """
    import zipfile
    try:
        with zipfile.ZipFile(jar_path) as zf:
            raw = zf.read("appinfo.properties").decode("utf-8", "replace")
    except Exception as e:
        fail("读不到 jar 里的 appinfo.properties：%s" % e)
        return
    props = {}
    for line in raw.splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    missing = [k for k in ("commit", "commitFull", "builtAt") if not props.get(k, "")]
    if missing:
        fail("交付 jar 里 %s 是空的：提交号（短号与 40 位全号）与构建日期只有走 构建验证.bat"
             " 才会烧进 jar，直跑 mvn 出的包不能拿去发布" % "、".join(missing))
    ok("随包提交号 %s（分支 %s，构建于 %s）已烧进 jar"
       % (props["commit"], props.get("branch") or "未记", props["builtAt"]))


def main():
    version = pom_version()
    jar_src = os.path.join(JAVA_DIR, "app", "target", JAR_NAME_TMPL % version)
    if not os.path.isfile(jar_src):
        fail("找不到 %s；先跑 构建验证.bat 产出程序包" % jar_src)
    check_build_stamp(jar_src)
    jdk = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("JDK8", JDK8_DEFAULT)
    src_jre = os.path.join(jdk, "jre")
    if not os.path.isfile(os.path.join(src_jre, "bin", "java.exe")):
        fail("JDK 8 里没有可用的 jre\\bin\\java.exe：%s" % src_jre)

    # 三条线的产物集中在仓库根 dist\ 下：图形版与命令行版一直在这儿，
    # Java 版原先落在 java\dist，取包要跑两个地方
    dist = os.path.join(ROOT, "dist", "excel2mysql-java-%s-win64" % version)
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
    with open(conn_src, "rb") as fh:
        write_shipped_text(os.path.join(dist, "conn.ini"), fh.read())
    stubs_src = count_stubs(src_jre)
    removed, pdb = copy_jre(src_jre, os.path.join(dist, "jre"))
    info("剔除转发桩与 CRT 批 %d 个（源 JRE 里有 %d 个）、调试符号 %d 个"
         % (removed, stubs_src, pdb))

    # 三个入口各判各的：默认那两个必须不带控制台（javaw / wscript），
    # 调试那一份必须留着控制台，漏了 --gui 就只会打印用法清单而不是开窗口
    write_launchers(dist, version)
    size = human(dir_size(dist))
    doc = copy_project_doc(dist)
    write_drivers_dir(dist)

    for want in ("conn.ini", "README.md", os.path.join("assets", "app_icon.svg"),
                 os.path.join("drivers", "README.txt")):
        if not os.path.isfile(os.path.join(dist, want)):
            fail("产物里缺 %s" % want)
    ok("产物含 conn.ini、README.md、assets/app_icon.svg 与预建的 drivers 放置口")
    # 这两份是现场用记事本改的：留一个纯 LF，Win7 记事本就把它当一整行显示，
    # 改口令的人要在看不见换行的文本里摸黑插字
    for want in SHIPPED_TEXT_FILES:
        p = os.path.join(dist, want)
        if os.path.isfile(p) and lone_lf_count(p):
            fail("产物里的 %s 还有 %d 个纯 LF 行尾，Win7 记事本打开是一整行"
                 % (want, lone_lf_count(p)))
    ok("随包文本行尾全是 CRLF（%s）" % "、".join(SHIPPED_TEXT_FILES))
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
    # 用户向文档不写版本号（版本口径归 --version 与随包 appinfo）。打包侧反向核一次：
    # 万一有人把带版本号的老 README 拷回来，本脚本已经不再核它对不对得上本版，
    # 那时它会在包里静静地写着上一版的产物名。127.0.0.1 这类地址不算版本号。
    _ver_shaped = sorted(set(re.findall(
        r"[vV]\d+\.\d+\.\d+(?:-[A-Za-z]+\.\d+)?"
        r"|excel2mysql[\w.\-]*[\s\-]\d+\.\d+\.\d+"
        r"|excel2mysql[\w.\-]*\.jar"
        r"|-win64", doc)))
    if _ver_shaped:
        fail("README.md 里出现了版本号形态的串 %s：用户向文档只讲怎么用" % _ver_shaped)
    ok("README.md 的开关名、三条线内容、不写版本号都核过（可照抄行 %d 条）"
       % len(copyable))

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
