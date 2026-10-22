"""两条图形线与命令行共用的报错文案，与 Java 侧 core/LogText.java 对应。

放这里的判定标准只有一个：同一句话在多处出现、且必须逐字一致。
以前 describe_db_error 在 cli.py 与 main.py 各有一份、回滚提醒只在 cli.py 里有一份，
「SQL 执行器」这个页签要同时用这两样，就先把它们收到一处再引用，
不要再抄第三份——抄一份就会有一天只改两处。
"""
import re
import time

DDL_NO_ROLLBACK_NOTE = ("注意：CREATE/DROP/ALTER 等 DDL 在 MySQL 中隐式提交，不会随事务回滚；"
                        "回滚仅对其后的数据操作生效。")

# pymysql 在发起握手前就把口令按 latin-1 编码（connections.py 里
# `self.password = self.password.encode("latin1")`），含中文的口令根本发不出去，
# 抛的是与数据库毫无关系的 UnicodeEncodeError。现场遇到时如果只把这行原样打出来，
# 运维会去查数据库配置，其实是口令的事——JDBC 那条路没有这个限制。
PASSWORD_ENCODING_HINT = ("多半出在口令上：pymysql 发口令前按 latin-1 编码，"
                          "口令里有中文等非 latin-1 字符时压根发不出去。"
                          "改用 ASCII 口令，或换 Java 版（JDBC 支持 Unicode 口令）。")


def describe_db_error(e):
    """展开 pymysql 异常为「异常类型 + MySQL 错误码 + 完整报错文本」。

    pymysql 的 args 形如 (1064, '...')，直接 str(e) 会把元组原样打出来，
    错误码和正文混在一起读不出。
    """
    if isinstance(e, UnicodeEncodeError):
        return "%s：%s\n    %s" % (type(e).__name__, e, PASSWORD_ENCODING_HINT)
    args = getattr(e, "args", ())
    # OSError（读写脚本文件）的 args[0] 是 errno，不是 MySQL 错误码；
    # 当成数据库错误写出来会把人引到库上去查一个不存在的 2 号错误。
    if args and isinstance(args[0], int) and not isinstance(e, OSError):
        detail = args[1] if len(args) > 1 else ""
        return "%s（MySQL 错误码 %s）：%s" % (type(e).__name__, args[0], detail)
    return "%s：%s" % (type(e).__name__, e)


# ---------------------------------------------------------------------------
# 日志一行的形状：时间戳 + 符号 + 大写分级标签，正文里的分段用 |
# 与命令行 cli.py 的 Log 同一套标签（OK/INFO/WARN/ERROR），图形界面只是多了钟点。
# 分级不是装饰：三个页签的日志一屏几百行，靠颜色与标签才能一眼挑出"这条要看"，
# 复制出去之后也还能按 [WARN] / [ERROR] 单独筛。
# ---------------------------------------------------------------------------
LOG_TIME_FORMAT = "%H:%M:%S"
LOG_LEVELS = ("ok", "info", "warn", "error")
LOG_LEVEL_MARK = {"ok": "+", "info": "-", "warn": "!", "error": "x"}

# 中文标签 -> 级别。两条 GUI 线共用这一张表（Java 侧 core/LogText.java 同名表），
# 同一句日志在两边必须同一颜色；对撞器比的是含标签的整行，级别写错就会当场不等。
LOG_TAG_LEVEL = {
    "已解析": "ok", "已加载": "ok", "已保存": "ok", "提交": "ok", "生效": "ok",
    "建表成功": "ok", "导入完成": "ok", "全部完成": "ok",
    "前缀完成": "ok", "换前缀完成": "ok", "添加结果": "ok",
    "跳过": "warn", "重复跳过": "warn", "压缩包为空": "warn", "已取消": "warn",
    "已中止": "warn", "正在中止...": "warn", "元数据": "warn", "数据警告": "warn",
    "保留": "warn",
    "解析失败": "error", "读取失败": "error", "保存失败": "error",
    "建表失败": "error", "执行失败": "error", "导入失败": "error",
    "连接失败": "error", "压缩包失败": "error", "任务异常": "error",
    "合并单元格": "error",
}

# 命令行那套前缀（"+ [OK] "/"! [WARN] "...）会原样出现在被搬进图形界面的文案里，
# 先剥掉再按本行的级别重新拼，避免出现 "09:12:03 + [OK] + [OK] ..." 这种重复标签
_LOG_HEAD_RE = re.compile(r"^(?:[+\-!x]\s*)?\[(OK|INFO|WARN|ERROR)\]\s+", re.IGNORECASE)
_TAG_RE = re.compile(r"^\[([^\]\n]{1,16})\]")


def log_time(now=None):
    """一条日志的钟点。整场会话都在同一个窗口里，日期不进每一行。"""
    return time.strftime(LOG_TIME_FORMAT, time.localtime(now) if now else time.localtime())


def log_level(text, error=False):
    """定级优先看调用方给的错误标志，其次看已有的大写标签，最后看中文标签。"""
    if error:
        return "error"
    head = (text or "").strip()
    m = _LOG_HEAD_RE.match(head)
    if m:
        return m.group(1).lower()
    t = _TAG_RE.match(head)
    if t:
        tag = t.group(1)
        # 「添加结果」这句在有拒绝/失败时会带"另有 …，详见日志"，
        # 这时候它是提醒而不是报成功，不能标成绿色
        if tag == "添加结果":
            return "warn" if "另有" in head else "ok"
        return LOG_TAG_LEVEL.get(tag, "info")
    return "info"


def format_log_entry(text, error=False, now=None):
    """把一条日志拼成界面上的样子，返回 (整条文字, 级别)。

    只有首行带时间戳与分级标签；已经缩进的续行（结果表格、驱动原文）原样保留，
    给它们打时间戳会把等宽表格顶歪。没有缩进的续行补 4 格，让人看得出还属于上一条。
    """
    body = (text or "").replace("\r\n", "\n").replace("\r", "\n")
    lines = body.split("\n")
    # 定级看整条记录，不是只看首行：「添加结果」的"另有 N 项解析失败"写在续行上，
    # 只按首行定级就会把一批有拒绝的添加报成绿色成功
    level = log_level(body, error)
    head = lines[0]
    if not head.strip() or head[:1].isspace():
        return body, level
    stripped = _LOG_HEAD_RE.sub("", head.strip()).strip()
    lines[0] = "[%s] %s [%s] %s" % (log_time(now), LOG_LEVEL_MARK[level],
                                    level.upper(), stripped)
    for i in range(1, len(lines)):
        if lines[i].strip() and not lines[i][:1].isspace():
            lines[i] = "    " + lines[i]
    return "\n".join(lines), level

