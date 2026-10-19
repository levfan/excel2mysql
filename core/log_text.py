"""两条图形线与命令行共用的报错文案，与 Java 侧 core/LogText.java 对应。

放这里的判定标准只有一个：同一句话在多处出现、且必须逐字一致。
以前 describe_db_error 在 cli.py 与 main.py 各有一份、回滚提醒只在 cli.py 里有一份，
「SQL 执行器」这个页签要同时用这两样，就先把它们收到一处再引用，
不要再抄第三份——抄一份就会有一天只改两处。
"""

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
    if args and isinstance(args[0], int):
        detail = args[1] if len(args) > 1 else ""
        return "%s（MySQL 错误码 %s）：%s" % (type(e).__name__, args[0], detail)
    return "%s：%s" % (type(e).__name__, e)
