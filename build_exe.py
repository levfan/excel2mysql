"""一键打包脚本：改完代码后跑它，产出可分发的 exe 并完成全部验收。

用法：
    双击 一键打包-图形版.bat          （推荐）
    python build_exe.py        （命令行）
    python build_exe.py --fast       只打产品包，跳过冻结态用例集与启动验收（图快时用）
    python build_exe.py --app-only   只打产品包，但保留开发态用例集检查
    python build_exe.py --keep-cache 保留 build/ 中间缓存以加速下次构建（默认成功后清掉）

要连 MySQL 真实读写用例（G 组）一起跑，先在环境里备好凭据再执行：
    set PT_HOST=127.0.0.1& set PT_PORT=3306& set PT_USER=root& set PT_PASS=密码& set PT_DB=测试库
未设置时 G 组会打印 SKIP 并在结尾记为"未覆盖"，不会冒充通过。

任一步失败即中止并以非 0 退出码结束，不会给你一个"看起来成功了"的包。
"""
import ctypes
import os
import shutil
import subprocess
import sys
import time
import time
from ctypes import wintypes

ROOT = os.path.dirname(os.path.abspath(__file__))
VENV_PY = os.path.join(ROOT, ".venv", "Scripts", "python.exe")
PYINSTALLER = os.path.join(ROOT, ".venv", "Scripts", "pyinstaller.exe")
APP_DIST = os.path.join(ROOT, "dist", "excel2mysql")
APP_EXE = os.path.join(APP_DIST, "excel2mysql.exe")
TEST_DIST = os.path.join(ROOT, "dist_packtest", "packtest")
TEST_EXE = os.path.join(TEST_DIST, "packtest.exe")
BUILD_DIRS = [os.path.join(ROOT, "build"), os.path.join(ROOT, "build_packtest")]

GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"
STEPS = []


def setup_console():
    """让输出在任何重定向方式下都是 UTF-8，并尝试打开控制台 ANSI 颜色。

    chcp 65001 只影响控制台；stdout 被重定向到文件或管道时 Python 会退回系统
    ANSI 码页（简体中文 Windows 是 cp936），▶ ✓ ✗ 会直接抛 UnicodeEncodeError。
    所以这里显式把 stdout/stderr 改成 UTF-8，不依赖调用方设的环境变量。
    """
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
        finish()
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
    """删除目录；被占用时给出可操作的提示而不是裸抛异常。"""
    if not os.path.isdir(path):
        return
    for attempt in range(6):
        try:
            shutil.rmtree(path)
            return
        except PermissionError:
            time.sleep(1.0)
    raise RuntimeError(
        f"无法删除 {path}：目录被其他程序占用。"
        "请关闭正在运行的 excel2mysql.exe / 打开该目录的资源管理器窗口 / "
        "编辑器的项目缓存后重试。")


# ---------------------------------------------------------------------------
def check_env():
    if not os.path.isfile(VENV_PY):
        raise RuntimeError(
            f"未找到虚拟环境 {VENV_PY}\n"
            "  请先创建并装依赖：python -m venv .venv && .venv\\Scripts\\python "
            "-m pip install -r requirements.txt")
    if not os.path.isfile(PYINSTALLER):
        raise RuntimeError(
            "未安装 PyInstaller。\n"
            f"  执行：{VENV_PY} -m pip install pyinstaller")
    missing = [p for p in ("excel2mysql.spec", "packtest.spec", "pack_test.py",
                           "_pack_common.py", "main.py", "core/zip_import.py", "core/merge_check.py",
                           "core/infer.py", "core/mysql_io.py", "core/sql_script.py",
                           "core/naming.py", "ui_help.py", "help_doc.py",
                           "version.py", "appinfo.py",
                           "assets/app_icon.ico")
               if not os.path.isfile(os.path.join(ROOT, p))]
    if missing:
        raise RuntimeError(f"缺少打包所需文件：{', '.join(missing)}")
    if os.environ.get("PT_USER") and not os.environ.get("PT_DB"):
        raise RuntimeError("设了 PT_USER 但没设 PT_DB，G 组用例无法运行；请补 PT_DB 或清掉 PT_USER")
    db = ("会跑（含 MySQL 真实读写" if os.environ.get("PT_USER") and os.environ.get("PT_DB")
          else "跳过 G 组（未设 PT_USER/PT_DB）")
    if os.environ.get("PT_USER") and os.environ.get("PT_DB") and os.environ.get("PT_XLS"):
        db += " + 真实 .xls"
    db += "）" if db.startswith("会跑") else ""
    return f"Python {sys.version.split()[0]} · 用例集 {db}"


def check_not_running():
    """产品 exe 正在运行时文件被锁，构建会失败，提前拦住。"""
    out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq excel2mysql.exe", "/NH"],
                         capture_output=True, text=True).stdout
    if "excel2mysql.exe" in out.lower():
        raise RuntimeError("检测到 excel2mysql.exe 正在运行，请先关掉它再打包")


def run_dev_tests():
    env = dict(os.environ, QT_QPA_PLATFORM="offscreen", PYTHONIOENCODING="utf-8")
    run([VENV_PY, "pack_test.py"], env=env)
    return "全部用例通过"


def write_build_stamp():
    """把版本、提交号、构建时间烧进 build_stamp.py（该文件不入库）。

    exe 跑在别人机器上没有 .git，提交号只能在打包这台机器上取好写死；
    开发态没有这个文件也正常，appinfo 会现场问 git。git 不可用时写 unknown，
    不因此中断打包。
    """
    sys.path.insert(0, ROOT)
    from version import APP_VERSION

    def git(*args):
        try:
            r = subprocess.run(("git",) + args, cwd=ROOT, capture_output=True,
                               text=True, timeout=10, encoding="utf-8", errors="replace")
            return r.stdout.strip() if r.returncode == 0 else ""
        except Exception:
            return ""

    commit = git("rev-parse", "--short", "HEAD")
    branch = git("rev-parse", "--abbrev-ref", "HEAD")
    # 只看已跟踪文件的改动：未跟踪的遗留文件不该把构建标成 dirty
    dirty = bool(git("status", "--porcelain", "-uno"))
    built = time.strftime("%Y-%m-%d %H:%M:%S")
    q = chr(34)
    doc_open = q * 3
    body = [doc_open + "打包时由 build_exe.py 自动生成：不要手改，也不要入库（.gitignore 已排除）。",
            "", "开发态缺这个文件属正常，appinfo 会退回现场查 git。" + doc_open,
            "APP_VERSION = " + q + APP_VERSION + q,
            "COMMIT = " + q + (commit or "unknown") + q,
            "BRANCH = " + q + (branch or "unknown") + q,
            "DIRTY = " + str(bool(dirty)),
            "BUILT_AT = " + q + built + q]
    with open(os.path.join(ROOT, "build_stamp.py"), "w", encoding="utf-8", newline="") as fh:
        fh.write(chr(10).join(body) + chr(10))
    if not commit:
        say(f"{YELLOW}取不到 git 提交号（没装 git 或不是仓库），「关于」里会显示 unknown{RESET}")
    say(f"  build_stamp.py: v{APP_VERSION}  commit={commit or 'unknown'}  "
        f"branch={branch or 'unknown'}  dirty={bool(dirty)}  built={built}")

def build_app():
    rmtree_retry(APP_DIST)
    run([PYINSTALLER, "--noconfirm", "excel2mysql.spec"])
    if not os.path.isfile(APP_EXE):
        raise RuntimeError(f"构建结束但未产出 {APP_EXE}")
    n = sum(len(f) for _, _, f in os.walk(APP_DIST))
    size = sum(os.path.getsize(os.path.join(d, f))
               for d, _, fs in os.walk(APP_DIST) for f in fs) / 1048576
    return f"{size:.0f} MB / {n} 个文件"


def copy_project_doc():
    """把项目根那份 README.md 原样拷进包里。

    现场拿到包的顺序是"先翻文件、再决定要不要双击 exe"，所以包里必须有一份可读文档。
    以前是打包时由 help_doc.py 渲染一份 README.txt——仓库里看不见产物那份、产物里看不见
    仓库改的那份，两份会分叉；现在随包的就是仓库那一份，逐字节相同。
    标题那行挂了图标，所以连 `assets/app_icon.png` 一起放进包里的同名相对路径，
    否则文档里的图片在现场解析不出来。
    """
    src = os.path.join(ROOT, "README.md")
    with open(src, encoding="utf-8") as fh:
        text = fh.read()
    if 'src="assets/app_icon.png"' not in text:
        raise RuntimeError("README.md 的标题没挂图标，或图标路径不是 assets/app_icon.png")
    if len(text) < 9000:
        raise RuntimeError("README.md 只有 %d 字符，命令参考那几节不像齐备（现场只带这一份文档）" % len(text))
    out = os.path.join(APP_DIST, "README.md")
    with open(out, "wb") as fh:
        fh.write(text.replace("\r\n", "\n").replace("\n", "\r\n").encode("utf-8"))
    icon_src = os.path.join(ROOT, "assets", "app_icon.png")
    if not os.path.isfile(icon_src):
        raise RuntimeError("随包文档用的图标 assets/app_icon.png 不在仓库里")
    icon_dst = os.path.join(APP_DIST, "assets", "app_icon.png")
    if not os.path.isdir(os.path.dirname(icon_dst)):
        os.makedirs(os.path.dirname(icon_dst))
    shutil.copy2(icon_src, icon_dst)
    if os.path.getsize(out) != len(text.encode("utf-8")) + text.count("\n"):
        raise RuntimeError("包里的 README.md 字节数与仓库那份对不上（行尾转换算进去也不该差）")
    return "README.md %d 字符（与仓库逐字节一致）+ assets/app_icon.png" % len(text)


def build_and_run_frozen_tests():
    rmtree_retry(TEST_DIST)
    run([PYINSTALLER, "--noconfirm", "--distpath", "dist_packtest",
         "--workpath", "build_packtest", "packtest.spec"])
    if not os.path.isfile(TEST_EXE):
        raise RuntimeError("未产出 packtest.exe")
    # 冻结态用例集也必须钉 offscreen，与开发态那一轮同一档屏：U8b/U10/F35b 量的是
    # 屏幕可用区与滚动条，继承调用方的屏就会换一套数字（真窗出窗由 check_launch 单独验）
    env = dict(os.environ, QT_QPA_PLATFORM="offscreen", PYTHONIOENCODING="utf-8")
    run([TEST_EXE], env=env)
    return "冻结态全部用例通过"


def check_launch():
    """真启动产品 exe：必须出现带正确标题的窗口，且进程能响应消息。"""
    user32 = ctypes.windll.user32
    user32.EnumWindows.argtypes = [ctypes.WINFUNCTYPE(
        wintypes.BOOL, wintypes.HWND, wintypes.LPARAM), wintypes.LPARAM]
    SMTO_ABORTIFHUNG = 0x0002
    WM_NULL = 0x0000

    def find(pid):
        hits = []

        @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
        def cb(hwnd, _):
            got = wintypes.DWORD()
            if user32.GetWindowThreadProcessId(hwnd, ctypes.byref(got)) and got.value == pid:
                if user32.IsWindowVisible(hwnd):
                    buf = ctypes.create_unicode_buffer(256)
                    user32.GetWindowTextW(hwnd, buf, 256)
                    if buf.value:
                        hits.append((hwnd, buf.value))
            return True
        user32.EnumWindows(cb, 0)
        return hits

    proc = subprocess.Popen([APP_EXE], cwd=os.path.dirname(APP_EXE))
    try:
        deadline = time.time() + 30
        hwnd = title = None
        while time.time() < deadline:
            time.sleep(0.4)
            if proc.poll() is not None:
                raise RuntimeError(f"exe 启动后自行退出，退出码 {proc.returncode}")
            h = find(proc.pid)
            if h:
                hwnd, title = h[0]
                break
        if not hwnd:
            raise RuntimeError("30 秒内没有出现窗口")
        # SendMessageTimeout 对未响应的窗口会超时返回 0，以此判断界面是否卡死
        res = wintypes.DWORD()
        alive = user32.SendMessageTimeoutW(
            hwnd, WM_NULL, 0, 0, SMTO_ABORTIFHUNG, 1500, ctypes.byref(res))
        if not alive:
            raise RuntimeError("窗口出现但不响应消息（可能卡在初始化）")
        return f"{title} · 窗口响应正常"
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=8)
            except subprocess.TimeoutExpired:
                proc.kill()
        time.sleep(0.5)
        if find(proc.pid):
            raise RuntimeError("关闭后仍有窗口残留")


def summary():
    say(f"\n{DIM}{'=' * 66}{RESET}")
    say("打包结果")
    say(f"{DIM}{'=' * 66}{RESET}")
    for name, ok, detail in STEPS:
        mark = f"{GREEN}✓{RESET}" if ok else f"{RED}✗{RESET}"
        say(f" {mark} {name}" + (f"   {DIM}{detail}{RESET}" if detail else ""))
    failed = [n for n, ok, _ in STEPS if not ok]
    if failed:
        say(f"\n{RED}打包未完成：{len(failed)} 个步骤失败。{RESET}")
        return False
    size = sum(os.path.getsize(os.path.join(d, f))
               for d, _, fs in os.walk(APP_DIST) for f in fs) / 1048576
    say(f"\n{GREEN}打包成功。{RESET}产物：{APP_EXE}  ({size:.0f} MB)")
    say(f"分发：把 {APP_DIST} 整个文件夹拷给对方（或压成 zip），"
        f"解压后双击 excel2mysql.exe，目标机不需要装 Python。")
    say(f"{YELLOW}注意：exe 与 _internal 必须放在一起，只拷 exe 跑不起来。{RESET}")
    if not os.environ.get("PT_USER"):
        say(f"{YELLOW}未覆盖：MySQL 真实读写用例（G 组）。设 PT_USER/PT_PASS/PT_DB 后重跑可补上。{RESET}")
    return True


def finish():
    ok = summary()
    if not ok:
        sys.exit(1)


def clean_build_cache():
    """删掉 PyInstaller 的中间缓存（.toc/.pyz/.pkg/warn 等），下次构建会自动重建。"""
    gone = 0
    for d in BUILD_DIRS:
        if os.path.isdir(d):
            shutil.rmtree(d, ignore_errors=True)
            gone += 1
    return f"清理 {gone} 个缓存目录" if gone else "无缓存可清"


def main():
    args = set(sys.argv[1:])
    fast = "--fast" in args
    app_only = "--app-only" in args
    setup_console()
    say(f"{DIM}excel2mysql 一键打包 · {time.strftime('%Y-%m-%d %H:%M:%S')}{RESET}")

    step("检查打包环境", check_env)
    step("确认产品 exe 未在运行", check_not_running)
    step("开发态功能用例集", run_dev_tests)
    step("生成 build_stamp.py（版本/提交/构建时间）", write_build_stamp)
    step("构建产品包（onedir + GUI）", build_app)
    step("随包文档（项目根的 README.md 原样拷入）", copy_project_doc)
    if not (fast or app_only):
        step("构建并运行冻结态用例集", build_and_run_frozen_tests)
        step("启动验收：真实窗口出现且可交互", check_launch)
    elif app_only:
        step("启动验收：真实窗口出现且可交互", check_launch)
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
