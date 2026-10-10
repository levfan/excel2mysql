"""Win7 线一键打包闸门：用 .venv38（Python 3.8.10 + PyInstaller 5.x）打命令行 exe。

与 GUI 那套（一键打包.bat → build_exe.py）完全独立：两台环境、两种产物、互不影响。
分两条线是因为 PyInstaller 6.x / Qt 6 / numpy 2.x 都不再支持 Windows 7，
只能把命令行版单独钉在 3.8 这一套上；core/ 两边共用。

用法：
    双击 一键打包38.bat              （推荐）
    .venv38\\Scripts\\python build_exe38.py
    .venv38\\Scripts\\python build_exe38.py --fast          只打产品包，跳过冻结态用例与冒烟验收
    .venv38\\Scripts\\python build_exe38.py --no-parity     跳过 3.12 那一轮跨栈比对
    .venv38\\Scripts\\python build_exe38.py --keep-cache    保留 build 中间缓存以加速下次构建

要连 MySQL 真实读写（G 组）一起跑，先备好凭据再执行：
    set PT_HOST=127.0.0.1& set PT_PORT=3306& set PT_USER=root& set PT_PASS=密码& set PT_DB=测试库
未设置时 G 组打印 SKIP 并在结尾记为"未覆盖"，不会冒充通过。

任一步失败即中止并以非 0 退出码结束，不会给你一个"看起来成功了"的包。

关于重复：本脚本自带的 step/say/run 等小骨架与 build_exe.py 各有一份，是有意为之——
两个闸门跑在不同 Python 上、验收项也不同（GUI 验窗口，CLI 验退出码与日志）。
把骨架抽成公共模块会让 GUI 那 388 项用例跟着冒风险，不值。
"""
import ctypes
import os
import shutil
import subprocess
import sys
import tempfile
import time
from ctypes import wintypes

ROOT = os.path.dirname(os.path.abspath(__file__))
VENV_PY = os.path.join(ROOT, ".venv38", "Scripts", "python.exe")
PYINSTALLER = os.path.join(ROOT, ".venv38", "Scripts", "pyinstaller.exe")
# GUI 线的环境：用它跑同一份 pack_test38.py，证明旧栈与新栈输出逐行一致
PARITY_PY = os.path.join(ROOT, ".venv", "Scripts", "python.exe")

APP_DIST = os.path.join(ROOT, "dist", "excel2mysql-cli")
APP_EXE = os.path.join(APP_DIST, "excel2mysql-cli.exe")
TEST_DIST = os.path.join(ROOT, "dist_packtest38", "packtest38")
TEST_EXE = os.path.join(TEST_DIST, "packtest38.exe")
BUILD_DIRS = [os.path.join(ROOT, "build"), os.path.join(ROOT, "build_packtest38")]
EXE_NAME = "excel2mysql-cli.exe"

GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"
STEPS = []


def setup_console():
    """让输出在任何重定向方式下都是 UTF-8，并尝试打开控制台 ANSI 颜色。"""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass
    if os.name != "nt":
        return
    try:
        k32 = ctypes.windll.kernel32
        h = k32.GetStdHandle(-11)
        mode = wintypes.DWORD()
        if k32.GetConsoleMode(h, ctypes.byref(mode)):
            k32.SetConsoleMode(h, mode.value | 0x0004)
    except Exception:
        pass


def say(msg):
    print(msg, flush=True)


def step(name, fn):
    """执行一个步骤；失败时打印醒目提示并终止整个流程。"""
    t0 = time.time()
    say(f"\n{DIM}{'=' * 66}{RESET}")
    say(f"▶ {name}")
    say(f"{DIM}{'=' * 66}{RESET}")
    try:
        detail = fn()
    except SystemExit:
        raise
    except Exception as e:
        STEPS.append((name, False, str(e)))
        say(f"{RED}✗ {name} 失败：{e}{RESET}")
        summary()
        sys.exit(1)
    dt = time.time() - t0
    STEPS.append((name, True, detail or ""))
    say(f"{GREEN}✓ {name}{RESET}  ({dt:.1f}s)" + (f"  {DIM}{detail}{RESET}" if detail else ""))


def run(cmd, **kw):
    """跑子进程，失败抛异常（保留其实时输出）。"""
    say(f"{DIM}$ {' '.join(cmd)}{RESET}")
    r = subprocess.run(cmd, cwd=ROOT, **kw)
    if r.returncode != 0:
        raise RuntimeError(f"命令退出码 {r.returncode}")
    return r


def rmtree_retry(path):
    if not os.path.isdir(path):
        return
    for _ in range(6):
        try:
            shutil.rmtree(path)
            return
        except PermissionError:
            time.sleep(1.0)
    raise RuntimeError(
        f"无法删除 {path}：目录被其他程序占用。请关闭正在运行的 {EXE_NAME}、"
        "打开过的产物目录窗口或编辑器缓存后重试。")


def dir_stats(path):
    names = [os.path.join(d, f) for d, _, fs in os.walk(path) for f in fs]
    return len(names), sum(os.path.getsize(p) for p in names) / 1048576


# ---------------------------------------------------------------------------
def check_env():
    if not os.path.isfile(VENV_PY):
        raise RuntimeError(
            f"未找到 Win7 线虚拟环境 {VENV_PY}\n"
            "  建法（pyenv-win 已装好 3.8.10 的前提下）：\n"
            '    "C:\\Users\\<你>\\.pyenv\\pyenv-win\\versions\\3.8.10\\python.exe" '
            "-m venv .venv38\n"
            "    .venv38\\Scripts\\python -m pip install --upgrade pip\n"
            "    .venv38\\Scripts\\python -m pip install -r requirements38.txt")
    ver = subprocess.run([VENV_PY, "-c", "import sys;print(sys.version_info[:2])"],
                         capture_output=True, text=True).stdout.strip()
    if ver != "(3, 8)":
        raise RuntimeError(
            f".venv38 里的 Python 是 {ver}，Win7 线必须是 3.8（3.9 起官方不支持 Win7）。\n"
            "  重建这个环境：删掉 .venv38 后用 3.8.10 重新 python -m venv .venv38")
    if not os.path.isfile(PYINSTALLER):
        raise RuntimeError(
            ".venv38 里没装 PyInstaller。\n"
            f"  执行：{VENV_PY} -m pip install -r requirements38.txt")
    pi_ver = subprocess.run([VENV_PY, "-c",
                             "import importlib.metadata as m;print(m.version('pyinstaller'))"],
                            capture_output=True, text=True).stdout.strip()
    if not pi_ver.startswith("5."):
        raise RuntimeError(
            f"PyInstaller 版本是 {pi_ver}，Win7 线必须是 5.x"
            "（6.x 官方只支持 Windows 8+，bootloader 不为 Win7 构建）")
    missing = [p for p in ("excel2mysql_cli38.spec", "packtest38.spec", "pack_test38.py",
                           "cli.py", "_pack_common.py", "requirements38.txt",
                           "version.py", "assets/app_icon.ico", "assets/conn.ini",
                           "assets/README.txt",
                           os.path.join("core", "infer.py"), os.path.join("core", "mysql_io.py"),
                           os.path.join("core", "zip_import.py"), os.path.join("core", "infer.py"),
                           os.path.join("core", "merge_check.py"), os.path.join("core", "naming.py"),
                           os.path.join("core", "sql_script.py"))
               if not os.path.isfile(os.path.join(ROOT, p))]
    if missing:
        raise RuntimeError(f"缺少打包所需文件：{', '.join(missing)}")
    if os.environ.get("PT_USER") and not os.environ.get("PT_DB"):
        raise RuntimeError("设了 PT_USER 但没设 PT_DB，G 组用例无法运行；请补 PT_DB 或清掉 PT_USER")
    db = "含 MySQL 真实读写" if (os.environ.get("PT_USER") and os.environ.get("PT_DB")) else "跳过 G 组"
    xls = " + 真实 .xls" if os.environ.get("PT_XLS") else ""
    return f"Python {sys.version.split()[0]} · PyInstaller {pi_ver} · 用例 {db}{xls}"


def check_not_running():
    """产品 exe 正在运行时文件被锁，构建必挂，提前拦住。"""
    out = subprocess.run(["tasklist", "/FI", f"IMAGENAME eq {EXE_NAME}", "/NH"],
                         capture_output=True, text=True).stdout
    if EXE_NAME.lower() in out.lower():
        raise RuntimeError(f"检测到 {EXE_NAME} 正在运行，请先关掉它再打包")


def run_tests(py_exec, title):
    """跑 pack_test38.py 并回收通过/失败/跳过数，供汇总里比对两栈。"""
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    proc = subprocess.run([py_exec, "pack_test38.py"], cwd=ROOT, env=env,
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    sys.stdout.write(proc.stdout)
    sys.stdout.flush()
    if proc.returncode != 0:
        raise RuntimeError(f"{title}：用例集退出码 {proc.returncode}")
    tail = [ln for ln in proc.stdout.splitlines() if "用例集结束" in ln]
    return (tail[-1].strip() if tail else title + " 全部通过")


def run_dev_tests():
    return run_tests(VENV_PY, "旧栈 3.8 开发态用例集")


def run_parity_tests():
    """同一份用例在新栈上再跑一遍：只有两边都绿，才谈得上"旧栈行为没变"。"""
    if not os.path.isfile(PARITY_PY):
        return "跳过（没有 .venv，只验了旧栈）"
    return run_tests(PARITY_PY, "新栈 3.12 同口径用例集")


def build_app():
    rmtree_retry(APP_DIST)
    run([PYINSTALLER, "--noconfirm", "excel2mysql_cli38.spec"])
    if not os.path.isfile(APP_EXE):
        raise RuntimeError(f"构建结束但未产出 {APP_EXE}")
    n, size = dir_stats(APP_DIST)
    return f"{size:.0f} MB / {n} 个文件"


def inspect_package():
    """产物体检：旧包里不该有 Qt，得有 python38.dll，且 PE 声明的系统版本不能高于 Win7。

    这三条都是"打包本身不报错、但拿到 Win7 上跑不起来"的坑，只能在这里主动查。
    """
    files = [os.path.join(d, f) for d, _, fs in os.walk(APP_DIST) for f in fs]
    names = [os.path.basename(p).lower() for p in files]
    qt_hits = [n for n in names if n.startswith(("qt6", "qt5", "pyside", "shiboken"))]
    if qt_hits:
        raise RuntimeError(
            f"产物里混进了 Qt（{', '.join(sorted(set(qt_hits))[:6])}）——"
            "命令行包不该带 GUI 依赖，多半是 cli.py 里 import 了 main/PySide6")
    if not any(n == "python38.dll" for n in names):
        raise RuntimeError("产物里没有 python38.dll，说明不一定是 3.8 打出来的包")
    for want in ("conn.ini", "README.txt"):
        if not any(n == want.lower() for n in names):
            raise RuntimeError(
                "产物同级没有 %s（spec 的 datas 漏了 assets/ 里那份）——"
                "对方要么找不到默认配置，要么手上没有使用说明" % want)
    if any(n.endswith((".pyc", ".py")) and os.path.basename(n) == "main.py" for n in names):
        raise RuntimeError("产物里出现了 main.py")

    need = []
    # PyInstaller 5.x 的 onedir 是"平铺"布局：python38.dll、*.pyd、base_library.zip
    # 全都直接摊在 exe 旁边，没有 6.x 那个 _internal 目录。所以按实际布局找，别写死路径。
    runtime_dll = next((p for p in files
                        if os.path.basename(p).lower() == "python38.dll"), None)
    for p in (APP_EXE, runtime_dll):
        if not p or not os.path.isfile(p):
            continue
        try:
            import pefile
        except ImportError:
            need.append("（没装 pefile，跳过 PE 版本取证）")
            break
        pe = pefile.PE(p, fast_load=True)
        oh = pe.OPTIONAL_HEADER
        pe.close()
        need.append("%s: subsystem=%d, 要求 Windows %d.%d+"
                    % (os.path.basename(p), oh.Subsystem,
                       oh.MajorOperatingSystemVersion, oh.MinorOperatingSystemVersion))
        if (oh.MajorOperatingSystemVersion, oh.MinorOperatingSystemVersion) > (6, 1):
            raise RuntimeError(
                "%s 的 PE 头声明需要 Windows %d.%d 以上，Win7（6.1）会被系统直接拦掉"
                % (os.path.basename(p), oh.MajorOperatingSystemVersion,
                   oh.MinorOperatingSystemVersion))
    n, size = dir_stats(APP_DIST)
    return f"无 Qt 依赖 · {size:.0f} MB / {n} 个文件 · " + "；".join(need)


def build_and_run_frozen_tests():
    rmtree_retry(TEST_DIST)
    run([PYINSTALLER, "--noconfirm", "--distpath", "dist_packtest38",
         "--workpath", "build_packtest38", "packtest38.spec"])
    if not os.path.isfile(TEST_EXE):
        raise RuntimeError("未产出 packtest38.exe")
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    run([TEST_EXE], env=env)
    return "冻结态全部用例通过"


def make_smoke_fixture():
    """造一个只在闸门里用的小 xlsx，供产品 exe 真跑一遍解析与推断。"""
    import pandas as pd
    d = tempfile.mkdtemp(prefix="e2m_gate_")
    p = os.path.join(d, "闸门自检（gate_probe）.xlsx")
    df = pd.DataFrame({"订单编号": ["G001", "G002"], "金额": [1.5, 2.25],
                       "下单日期": ["2026-05-01", "2026-05-02"]})
    with pd.ExcelWriter(p, engine="openpyxl") as xw:
        df.to_excel(xw, index=False)
    return d, p


def smoke_product_exe():
    """真启动产品 exe 跑三件事：--version / --help / --dry-run，断退出码与落盘的日志。

    这是 GUI 版"启动验收：窗口出现且响应"在命令行版里的对应物——
    没有窗口可看，就验"进程能起、退出码对、日志里真有推断出来的 DDL"。
    --dry-run 不连库，所以这一轮在没数据库的机器上也能跑。
    """
    d, fixture = make_smoke_fixture()
    log = os.path.join(d, "smoke.log")
    try:
        r = subprocess.run([APP_EXE, "--version"], capture_output=True, text=True,
                           encoding="utf-8", errors="replace", cwd=APP_DIST)
        if r.returncode != 0 or "excel2mysql-cli" not in (r.stdout or ""):
            raise RuntimeError(f"--version 失败（退出码 {r.returncode}）：{r.stdout!r} {r.stderr!r}")
        ver_line = (r.stdout or "").strip().splitlines()[0]

        r = subprocess.run([APP_EXE, "--help"], capture_output=True, text=True,
                           encoding="utf-8", errors="replace", cwd=APP_DIST)
        if r.returncode != 0 or "--swap-prefix" not in (r.stdout or ""):
            raise RuntimeError(f"--help 失败（退出码 {r.returncode}）：{r.stderr!r}")

        r = subprocess.run([APP_EXE, "--dry-run", fixture, "--log", log],
                           capture_output=True, text=True, encoding="utf-8",
                           errors="replace", cwd=APP_DIST)
        if r.returncode != 0:
            raise RuntimeError(f"--dry-run 退出码 {r.returncode}\n{r.stdout}\n{r.stderr}")
        if not os.path.isfile(log):
            raise RuntimeError(f"--dry-run 没写出日志文件：{log}")
        with open(log, encoding="utf-8") as fh:
            text = fh.read()
        for must in ("CREATE TABLE `gate_probe`", "DECIMAL(16,2)", "DATE",
                     "+ [OK] 解析成功", "dry-run 完成"):
            if must not in text:
                raise RuntimeError(f"冻结态日志里缺少「{must}」——打包缺库或缺逻辑：\n{text[-600:]}")
        # 随包的默认连接配置必须真被读到（不带 -c 也能找到 exe 同级那份 conn.ini）
        if "（自动发现）" not in text:
            raise RuntimeError("冻结态没自动读到产物同级的 conn.ini，日志里的来源不是它：\n"
                               + text[:400])
        n, _ = dir_stats(APP_DIST)
        return f"{ver_line} · --help/--dry-run 退出码 0 · 日志含推断出的 DDL（{n} 个文件）"
    finally:
        shutil.rmtree(d, ignore_errors=True)


def check_no_python_needed():
    """产物必须是自包含的：不依赖本机 .venv38（拷给别人时那边没有 Python）。

    做法是换个"看不见 Python"的工作目录跑一次 exe，并且不带上 venv 的路径。
    """
    env = {k: v for k, v in os.environ.items() if k not in ("PYTHONHOME", "PYTHONPATH")}
    env.pop("VIRTUAL_ENV", None)
    d, fixture = make_smoke_fixture()
    log = os.path.join(d, "standalone.log")
    try:
        r = subprocess.run([APP_EXE, "--dry-run", fixture, "--log", log],
                           capture_output=True, text=True, encoding="utf-8",
                           errors="replace", cwd=d, env=env)
        if r.returncode != 0:
            raise RuntimeError(f"脱离项目目录后 exe 跑不起来（退出码 {r.returncode}）："
                               f"\n{r.stdout}\n{r.stderr}")
        # 这个临时目录里没有 conn.ini：仍然要报"自动发现"，说明找的是 exe 同级那份，
        # 不是靠当前工作目录——分发到别人机器上换个工作目录也不会突然读不到配置
        with open(log, encoding="utf-8") as fh:
            text = fh.read()
        if "（自动发现）" not in text:
            raise RuntimeError("在别的工作目录下没能自动发现 exe 同级的 conn.ini：\n" + text[:400])
        return "在临时目录里独立跑通，并自动读到 exe 同级的 conn.ini"
    finally:
        shutil.rmtree(d, ignore_errors=True)


def clean_build_cache():
    gone = 0
    for d in BUILD_DIRS:
        if os.path.isdir(d):
            shutil.rmtree(d, ignore_errors=True)
            gone += 1
    return f"清理 {gone} 个缓存目录" if gone else "无缓存可清"


def summary():
    say(f"\n{DIM}{'=' * 66}{RESET}")
    say("Win7 线打包结果")
    say(f"{DIM}{'=' * 66}{RESET}")
    for name, ok, detail in STEPS:
        mark = f"{GREEN}✓{RESET}" if ok else f"{RED}✗{RESET}"
        say(f" {mark} {name}" + (f"   {DIM}{detail}{RESET}" if detail else ""))
    failed = [n for n, ok, _ in STEPS if not ok]
    if failed:
        say(f"\n{RED}打包未完成：{len(failed)} 个步骤失败。{RESET}")
        return False
    n, size = dir_stats(APP_DIST)
    say(f"\n{GREEN}打包成功。{RESET}产物：{APP_EXE}  ({size:.0f} MB / {n} 个文件)")
    say(f"分发：把 {APP_DIST} 整个文件夹拷到 Win7 机器（或压成 zip），"
        f"解压后在 cmd 里运行 excel2mysql-cli.exe --help")
    say(f"  配置：产物同级带了一份 {os.path.join(APP_DIST, 'conn.ini')}（默认 127.0.0.1:3306/root，"
        f"口令与目标库留空），不带 -c 会自动读它；改这个文件就行")
    say(f"  说明：产物同级的 README.txt 是写给使用方的（前置条件、四条命令、退出码、排错），"
        f"发机器时一起拷过去即可")
    say(f"{YELLOW}注意：这条线用的是 PyInstaller 5.x，onedir 是平铺布局——"
        f"python38.dll / *.pyd / base_library.zip 全在 exe 同级目录，没有 GUI 线那个 "
        f"_internal 文件夹。整个目录一起拷，只拷 exe 跑不起来。{RESET}")
    say(f"{YELLOW}Win7 前置：需 SP1 且装好 UCRT（KB2999226）；缺它系统会先拦下进程，"
        f"程序没机会报错。{RESET}")
    if not os.environ.get("PT_USER"):
        say(f"{YELLOW}未覆盖：MySQL 真实读写用例（G 组）。设 PT_USER/PT_PASS/PT_DB 后重跑可补上。{RESET}")
    say(f"{YELLOW}未覆盖：本闸门在打包机上跑，无法替代真机 Win7 验收"
        f"（PE 头与无 Qt 取证已做）。{RESET}")
    return True


def main():
    args = set(sys.argv[1:])
    fast = "--fast" in args
    setup_console()
    say(f"{DIM}excel2mysql Win7 线一键打包 · {time.strftime('%Y-%m-%d %H:%M:%S')}{RESET}")

    step("检查打包环境（必须是 3.8 + PyInstaller 5.x）", check_env)
    step(f"确认 {EXE_NAME} 未在运行", check_not_running)
    step("旧栈 3.8 开发态用例集", run_dev_tests)
    if "--no-parity" not in args:
        step("新栈同口径用例集（跨栈逐条比对）", run_parity_tests)
    step("构建产品包（onedir + 控制台 + PyInstaller 5.x）", build_app)
    step("产物体检（无 Qt / python38.dll / PE 系统版本 <= 6.1）", inspect_package)
    if not fast:
        step("构建并运行冻结态用例集", build_and_run_frozen_tests)
        step("启动验收：真跑产品 exe（--version/--help/--dry-run）", smoke_product_exe)
        step("独立运行验收：脱离项目目录跑产品 exe", check_no_python_needed)
    else:
        say(f"{YELLOW}  --fast：已跳过冻结态用例集与启动验收{RESET}")

    if "--keep-cache" not in args:
        step("清理构建缓存", clean_build_cache)
    else:
        say(f"{DIM}  --keep-cache：保留 build/ 以加速下次构建{RESET}")

    if not summary():
        sys.exit(1)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        say(f"\n{YELLOW}已取消。{RESET}")
        sys.exit(130)
