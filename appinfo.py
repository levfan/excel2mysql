"""版本与构建信息的统一读取口，供窗口标题与「关于」框使用。

版本号只有一个来源（`version.py`）。提交号与构建日期**必须在打包那一刻烧进产物**：
exe 跑在别人机器上时没有 `.git`，运行时再去查是查不到的，所以由 `build_exe.py`
生成 `build_stamp.py`（不入库）。开发态没有这个文件也完全正常——现场问 git，
问不到就老实显示"开发态（未打包）"，绝不编一个假提交号出来。
"""
import os
import subprocess

from version import APP_VERSION

UNKNOWN = "未知"

# 产品的显示名，只有一个来源：窗口标题、「关于」那一格、复制出去的第一行都读它。
# 仓库名、包名、exe 与 jar 的产物名是另一层（excel2mysql），刻意不跟着改——
# 现场指向旧名的脚本与部署文档不能因为换个标题就失效。
# Java 侧镜像在 core/AppInfo.java 的 APP_NAME，由对撞键 dlg.about.heading 逐字比。
APP_NAME = "表库工作台 Excel·SQL·MySQL"


def _run_git(args, cwd):
    """跑一条 git 命令拿输出；没有 git、不是仓库、超时都返回空串，不抛异常。"""
    try:
        r = subprocess.run(["git", *args], cwd=cwd, capture_output=True,
                           text=True, timeout=5)
        return r.stdout.strip() if r.returncode == 0 else ""
    except Exception:
        return ""


def build_info(project_dir=None):
    """返回 {"version","commit","commit_full","branch","dirty","built_at","source"}，任何情况下都不抛。

    source 为 "bundle" 表示读的是打包时生成的 build_stamp.py；
    "git" 表示开发态现场取的；"unknown" 表示两者都没有（提交号显示"未知"）。
    commit 是界面上那一格的短号，commit_full 是 40 位全号（「关于」里复制用）；
    老产物没烧这一格时退回短号，不拿空串冒充。
    """
    info = {"version": APP_VERSION, "commit": UNKNOWN, "commit_full": "",
            "branch": "", "dirty": False, "built_at": UNKNOWN, "source": "unknown"}
    try:
        import build_stamp as stamp
    except Exception:
        stamp = None
    if stamp is not None:
        info.update({
            "commit": getattr(stamp, "COMMIT", "") or UNKNOWN,
            "commit_full": getattr(stamp, "COMMIT_FULL", "") or "",
            "branch": getattr(stamp, "BRANCH", "") or "",
            "dirty": bool(getattr(stamp, "DIRTY", False)),
            "built_at": getattr(stamp, "BUILT_AT", "") or UNKNOWN,
            "source": "bundle",
        })
        return info
    here = project_dir or os.path.dirname(os.path.abspath(__file__))
    commit = _run_git(["rev-parse", "--short", "HEAD"], here)
    info["built_at"] = "开发态（未打包）"
    if commit:
        info.update({"commit": commit,
                     "commit_full": _run_git(["rev-parse", "HEAD"], here),
                     "branch": _run_git(["rev-parse", "--abbrev-ref", "HEAD"], here),
                     "dirty": bool(_run_git(["status", "--porcelain", "-uno"], here)),
                     "source": "git"})
    else:
        info["commit"] = "无 git 信息"
    return info


def commit_display(info=None):
    """提交号一行的展示文本：带上分支与"有未提交改动"提示，仍然只占一行。"""
    info = info or build_info()
    extra = []
    if info.get("branch"):
        extra.append(info["branch"])
    if info.get("dirty"):
        extra.append("工作区有未提交改动")
    return f"{info['commit']}（{'，'.join(extra)}）" if extra else info["commit"]


def full_commit(info=None):
    """可核对的完整提交号：拿不到全号时退回短号，两者都没有时原样报"取不到"。"""
    info = info or build_info()
    return info.get("commit_full") or info.get("commit") or UNKNOWN


def title_text():
    """窗口标题：带版本号，不用点开任何对话框就能看见跑的是哪一版。"""
    return f"{APP_NAME} v{build_info()['version']}"
