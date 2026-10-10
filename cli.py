"""
Excel -> MySQL 命令行版（Windows 7 线）
=====================================
给同一份 core/ 加的无界面入口：不依赖 Qt，可在 Python 3.8 + Win7 SP1 上运行。
GUI 版（main.py）要求 Windows 10 1809+，两者共用推断/建表/导入的全部核心逻辑。

用法：
    excel2mysql-cli.exe [选项] <文件或目录> [<文件或目录> ...]
    excel2mysql-cli.exe --dry-run 数据\\目录
    excel2mysql-cli.exe -c conn.ini 数据\\目录 --prefix ods_
    excel2mysql-cli.exe -c conn.ini --check              # 只测连接并列出库

输入 .xlsx / .xls / .zip（压缩包自动展开包内 Excel）；给目录则递归收集。
连接信息优先级：命令行参数 > 环境变量（E2M_HOST/E2M_PORT/E2M_USER/E2M_PASS/E2M_DB）> 配置文件。

退出码：
    0  全部成功
    1  用法或配置错误（参数冲突、配置文件缺失、前缀非法、未指定目标库）
    2  有输入文件解析失败（其余文件继续处理）
    3  无法连接 MySQL 或目标库不可用
    4  有表建表或导入失败（其余表继续处理）
    130 用户按 Ctrl+C 中止

开发态：python cli.py ...      打包：双击 一键打包38.bat
"""
import argparse
import configparser
import os
import shutil
import sys
import tempfile
import time

from core.infer import (
    build_table_def, make_unique_names, apply_table_prefix, replace_first_segment,
)
from core.merge_check import MergedCellsError
from core.mysql_io import build_ddl, connect, create_table, insert_data, list_databases
from core.naming import clean_prefix, is_valid_prefix
from core.zip_import import EXCEL_EXTS, ZipArchiveError, extract_excel_archive
from version import APP_VERSION

ZIP_EXT = ".zip"
EXIT_OK = 0
EXIT_USAGE = 1
EXIT_PARSE = 2
EXIT_CONNECT = 3
EXIT_IMPORT = 4

# 配置文件里允许的段名，按顺序取第一个存在的
CONFIG_SECTIONS = ("mysql", "connection")
CONFIG_KEYS = {"host": "host", "port": "port", "user": "user",
               "password": "password", "database": "database"}
ENV_KEYS = {"host": "E2M_HOST", "port": "E2M_PORT", "user": "E2M_USER",
            "password": "E2M_PASS", "database": "E2M_DB"}


# ---------------------------------------------------------------------------
# 日志：一行读完整件事，行首符号 + 大写分级标签，分段用 |
# 符号一律 ASCII：Win7  cmd 默认 cp936 码页，非 ASCII 符号会变成乱码或方块
# ---------------------------------------------------------------------------
class Log:
    def __init__(self, path=None, quiet=False):
        self._fh = None
        self.path = path
        self.quiet = quiet
        self.counts = {"info": 0, "ok": 0, "warn": 0, "error": 0}
        if path:
            self._fh = open(path, "a", encoding="utf-8")

    def _emit(self, level, mark, text):
        self.counts[level] += 1
        line = "%s [%s] %s" % (mark, level.upper(), text)
        # quiet 只压 info/ok 两档；warn/error 必须始终可见，否则 --quiet 会把失败藏起来
        if not (self.quiet and level in ("info", "ok")):
            print(line, flush=True)
        if self._fh:
            self._fh.write("%s %s\n" % (time.strftime("[%Y-%m-%d %H:%M:%S]"), line))
            self._fh.flush()

    def info(self, text):
        self._emit("info", "-", text)

    def ok(self, text):
        self._emit("ok", "+", text)

    def warn(self, text):
        self._emit("warn", "!", text)

    def error(self, text):
        self._emit("error", "x", text)

    def detail(self, text, level="info"):
        """把多行详情缩进跟在上一条之后，仍保持每条记录可整体 grep。

        level 跟着上一条记录走：错误详情在 --quiet 下也要打出来，否则只剩一句
        "建表失败"而看不到是 which 语句被拒。
        """
        body = "\n".join("      " + ln for ln in str(text).splitlines())
        if self._fh:
            self._fh.write(body + "\n")
            self._fh.flush()
        if not (self.quiet and level in ("info", "ok")):
            print(body, flush=True)

    def close(self):
        if self._fh:
            self._fh.close()
            self._fh = None


def describe_db_error(e):
    """展开 pymysql 异常为「异常类型 + MySQL 错误码 + 完整报错文本」。

    与 main.py 里的同名函数保持同一口径：pymysql 的 args 形如 (1064, '...')，
    直接 str(e) 会把元组原样打出来，错误码和正文混在一起读不出。
    """
    args = getattr(e, "args", ())
    if args and isinstance(args[0], int):
        detail = args[1] if len(args) > 1 else ""
        return "%s（MySQL 错误码 %s）：%s" % (type(e).__name__, args[0], detail)
    return "%s：%s" % (type(e).__name__, e)


# ---------------------------------------------------------------------------
# 参数与连接配置
# ---------------------------------------------------------------------------
def build_parser():
    p = argparse.ArgumentParser(
        prog="excel2mysql-cli",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description="Excel/ZIP 批量导入 MySQL（命令行版，支持 Windows 7）",
        epilog=(
            "示例：\n"
            "  excel2mysql-cli.exe --dry-run C:\\数据            只推断并打印建表语句\n"
            "  excel2mysql-cli.exe -c conn.ini C:\\数据          建表并导入目录下全部 Excel\n"
            "  excel2mysql-cli.exe -c conn.ini a.zip --prefix ods_   展开压缩包并统一加前缀\n"
            "  excel2mysql-cli.exe -c conn.ini --check          只测连接并列出数据库\n"
        ))
    p.add_argument("inputs", nargs="*", metavar="文件或目录",
                   help="要导入的 .xlsx / .xls / .zip 文件，或包含它们的目录")
    p.add_argument("-c", "--config", metavar="FILE",
                   help="连接配置文件（INI，段名 [mysql]，键 host/port/user/password/database）")
    p.add_argument("--host", help="MySQL 主机，默认 127.0.0.1")
    p.add_argument("--port", type=int, help="MySQL 端口，默认 3306")
    p.add_argument("--user", help="MySQL 用户名")
    p.add_argument("--password", help="MySQL 密码；建议改用环境变量 E2M_PASS 避免留在命令历史里")
    p.add_argument("-d", "--database", help="目标数据库")
    p.add_argument("--dry-run", action="store_true",
                   help="只解析、推断并打印建表 DDL，不连接数据库")
    p.add_argument("--check", action="store_true",
                   help="只连接数据库并列出可用库名，不做导入")
    p.add_argument("--prefix", help="为全部表名批量添加前缀（如 ods_）；基于原名重算，重复用不叠加")
    p.add_argument("--swap-prefix", metavar="NEW",
                   help="替换表名第一个下划线之前的首段：ods_t_order → new_t_order；"
                        "名里没有下划线的表不改并在日志里列出")
    p.add_argument("--drop-if-exists", action="store_true",
                   help="建表前先 DROP TABLE IF EXISTS（会删掉目标库里的同名表及其数据）")
    p.add_argument("--batch", type=int, default=500, metavar="N",
                   help="每批插入行数，默认 500")
    p.add_argument("--no-recursive", action="store_true",
                   help="给目录时只取该目录一层的 Excel，不递归子目录")
    p.add_argument("--keep-temp", action="store_true",
                   help="保留压缩包解压出来的临时 Excel（默认处理完删除）")
    p.add_argument("--log", metavar="FILE", help="同时把日志追加写入该 UTF-8 文件")
    p.add_argument("-q", "--quiet", action="store_true",
                   help="控制台只打印警告、错误与汇总（日志文件仍记全量）")
    p.add_argument("--version", action="version", version="excel2mysql-cli " + APP_VERSION)
    return p


def read_config_file(path):
    """读 INI 连接配置，返回 dict；文件缺失/格式错直接抛给用户。"""
    if not os.path.isfile(path):
        raise ValueError("配置文件不存在：%s" % path)
    cp = configparser.ConfigParser()
    try:
        with open(path, encoding="utf-8") as fh:
            cp.read_file(fh)
    except UnicodeDecodeError:
        # Win7 上记事本另存为默认 ANSI，中文注释会让 utf-8 解析炸掉，退回 GBK 再试
        cp = configparser.ConfigParser()
        with open(path, encoding="gbk", errors="replace") as fh:
            cp.read_file(fh)
    except configparser.Error as e:
        raise ValueError("配置文件格式错误（%s）：%s" % (path, e))
    section = next((s for s in CONFIG_SECTIONS if cp.has_section(s)), None)
    if section is None:
        raise ValueError("配置文件缺少 [%s] 段：%s" % ("|".join(CONFIG_SECTIONS), path))
    return {CONFIG_KEYS[k]: cp.get(section, k) for k in CONFIG_KEYS
            if cp.has_option(section, k)}


def resolve_cfg(args, log):
    """按 命令行 > 环境变量 > 配置文件 > 默认 的顺序合成连接配置。"""
    cfg = {"host": "127.0.0.1", "port": 3306, "user": "root",
           "password": "", "database": ""}
    used = []
    if args.config:
        cfg.update(read_config_file(args.config))
        used.append("配置文件 %s" % args.config)
    for key, env in ENV_KEYS.items():
        val = os.environ.get(env)
        if val:
            cfg[key] = val
            used.append("环境变量 %s" % env)
    for key in ("host", "port", "user", "password", "database"):
        val = getattr(args, key)
        if val is not None and val != "":
            cfg[key] = val
            used.append("命令行 --%s" % key)
    cfg["port"] = int(cfg["port"])
    src = "、".join(used) if used else "全部用默认值"
    shown = "%s@%s:%s/%s" % (cfg["user"], cfg["host"], cfg["port"], cfg["database"] or "(未选库)")
    log.info("连接目标 | %s | 来源：%s | 密码%s"
             % (shown, src, "取自环境变量/配置文件，不回显" if not args.password else "来自命令行 --password"))
    return cfg


def target_label(cfg):
    return "%s@%s:%s/%s" % (cfg["user"], cfg["host"], cfg["port"], cfg["database"] or "(未选库)")


def validate(args, cfg, log):
    """返回错误码，None 表示可以继续。"""
    if args.dry_run and args.check:
        log.error("参数冲突 | --dry-run 与 --check 不能同时用")
        return EXIT_USAGE
    if not args.dry_run and not args.check and not args.inputs:
        log.error("没有输入 | 请给出至少一个 Excel / ZIP 文件或目录（--dry-run/--check 除外）")
        return EXIT_USAGE
    if args.prefix is not None and args.swap_prefix is not None:
        log.error("参数冲突 | --prefix（加前缀）与 --swap-prefix（换首段）只能选一个")
        return EXIT_USAGE
    # --prefix 直接按原样校验（末尾下划线是分隔符，不能像 --swap-prefix 那样被清掉）；
    # 留空是合法用法，表示"移除前缀"，由 apply_naming 给出提示
    if args.prefix is not None:
        raw = args.prefix.strip()
        if raw and not is_valid_prefix(raw):
            log.error("前缀非法 | --prefix「%s」只允许字母、数字、下划线" % args.prefix)
            return EXIT_USAGE
    if args.swap_prefix is not None:
        head = clean_prefix(args.swap_prefix)
        if not head or not is_valid_prefix(head):
            log.error("前缀非法 | --swap-prefix「%s」清完之后为空或含非法字符，"
                      "只允许字母、数字、下划线" % args.swap_prefix)
            return EXIT_USAGE
    if args.batch is not None and args.batch <= 0:
        log.error("参数非法 | --batch 必须大于 0")
        return EXIT_USAGE
    if not args.dry_run and not cfg["database"]:
        log.error("未指定目标库 | 请用 -d/--database、配置文件 database 或环境变量 E2M_DB 指定；"
                  "只想看建表语句就用 --dry-run")
        return EXIT_USAGE
    return None


# ---------------------------------------------------------------------------
# 输入收集与解析
# ---------------------------------------------------------------------------
def collect_inputs(paths, recursive, log):
    """把文件/目录参数展开成 [(路径, 日志里显示的名字)]，保持参数顺序。

    目录内按名字排序，同一目录两次跑出来的顺序一致，日志和结果才可复现。
    """
    entries, zips = [], []
    for p in paths:
        if os.path.isdir(p):
            found = []
            if recursive:
                for root, dirs, names in os.walk(p):
                    dirs.sort()
                    found.extend(os.path.join(root, n) for n in sorted(names))
            else:
                found.extend(os.path.join(p, n) for n in sorted(os.listdir(p))
                             if os.path.isfile(os.path.join(p, n)))
            for f in found:
                low = f.lower()
                if low.endswith(ZIP_EXT):
                    zips.append(f)
                elif low.endswith(EXCEL_EXTS):
                    entries.append((f, os.path.relpath(f, p)))
                else:
                    log.info("跳过 | %s（不是 .xlsx / .xls / .zip）" % f)
        elif os.path.isfile(p):
            low = p.lower()
            if low.endswith(ZIP_EXT):
                zips.append(p)
            elif low.endswith(EXCEL_EXTS):
                entries.append((p, os.path.basename(p)))
            else:
                log.error("不支持的类型 | %s（只支持 .xlsx / .xls / .zip）" % p)
        else:
            log.error("路径不存在 | %s" % p)
    return entries, zips


def expand_zips(zips, tmp_dir, log):
    """展开压缩包内的 Excel，返回 (excel 路径, 包内来源名) 列表与坏包计数。

    口径与 GUI 的 parse_import_files 一致：解压目录按「包名_序号」命名避免同名互相覆盖，
    嵌套 zip 不递归，非 Excel 条目忽略并计数。
    """
    out, bad = [], 0
    if not zips:
        return out, bad
    log.info("压缩包 | 待展开 %d 个" % len(zips))
    for i, z in enumerate(zips, 1):
        zname = os.path.basename(z)
        stem = os.path.splitext(zname)[0] or "archive"
        dest = os.path.join(tmp_dir, "%s_%d" % (stem, i))
        try:
            r = extract_excel_archive(z, dest)
        except ZipArchiveError as e:
            bad += 1
            log.error("压缩包失败 | %s | %s" % (zname, e))
            continue
        except Exception as e:
            bad += 1
            log.error("压缩包失败 | %s | %s" % (zname, describe_db_error(e)))
            continue
        for x in r["files"]:
            out.append((x["path"], "%s!%s" % (zname, x["origin"].replace("\\", "/"))))
        msg = "压缩包展开 | %s | Excel %d 个" % (zname, len(r["files"]))
        if r["ignored"]:
            msg += " | 忽略非 Excel %d 个" % len(r["ignored"])
        if r["nested_zips"]:
            msg += " | 跳过嵌套 zip %d 个（不支持递归展开）" % r["nested_zips"]
        if r["files"]:
            log.ok(msg)
        else:
            log.error(msg + " | 包内没有 .xlsx / .xls")
            bad += 1
    return out, bad


def parse_one(path, origin, log):
    """解析一个 Excel 为表定义，返回 (td, 错误说明)；失败时 td 为 None。"""
    try:
        td = build_table_def(path)
    except MergedCellsError as e:
        total = sum(len(v) for v in e.merged.values())
        log.error("拒绝导入 | %s | 含 %d 处合并单元格，未进入待导入列表"
                  % (origin, total))
        log.detail("原因：%s" % e, level="error")
        return None, "合并单元格"
    except Exception as e:
        log.error("解析失败 | %s | %s" % (origin, describe_db_error(e)))
        log.detail(str(e), level="error")
        return None, "解析异常"
    pk = td.get("primary_key")
    msg = ("解析成功 | %s → 表 `%s` | 字段 %d 列 | 数据 %d 行 | %s"
           % (origin, td["table_name"], len(td["columns"]), len(td["df"]),
              ("主键 `%s`（%s）" % (pk, td.get("pk_reason", ""))) if pk
              else ("未设主键（%s）" % td.get("pk_reason", ""))))
    if td.get("source") == "structured":
        msg += " | 结构定义模式"
    extra = td.get("extra_data_columns") or []
    if extra:
        msg += " | 数据 Sheet 多余列已忽略：%s" % "、".join(extra)
    log.ok(msg)
    notes = [c["type_note"] for c in td["columns"] if c.get("type_note")]
    if notes:
        log.info("类型修正 | %s | %d 列按表头语义调整" % (origin, len(notes)))
        for n in notes:
            log.detail(n)
    return td, None


def apply_naming(tables, args, log):
    """表名定稿：加前缀 / 换首段，否则撞名自动 _2 _3。

    三个分支走的是 GUI 上三个按钮背后的同一批 core 函数；
    apply_table_prefix 与 replace_first_segment 内部都会再调一次
    make_unique_names，所以这里不重复调用。
    """
    if args.prefix is not None:
        prefix = args.prefix.strip()
        if not prefix:
            log.warn("前缀为空 | --prefix 留空等于移除前缀，表名按原名使用")
        elif not prefix.endswith("_"):
            log.warn("分隔符 | --prefix「%s」末尾没有下划线，会直接接在原名前面"
                     "（如 %sgoods）；GUI 上「批量应用前缀」同此口径，需要分隔就写上 _"
                     % (prefix, prefix))
        apply_table_prefix(tables, prefix)
        log.ok("表名前缀 | 已为 %d 张表统一加前缀「%s」" % (len(tables), prefix or "(无)"))
        return tables
    if args.swap_prefix is not None:
        head = clean_prefix(args.swap_prefix)
        renamed, skipped = replace_first_segment(tables, head)
        log.ok("首段前缀替换 | 改名 %d 张：%s"
               % (len(renamed),
                  "、".join("`%s`→`%s`" % (r["old"], r["new"]) for r in renamed) or "无"))
        if skipped:
            log.warn("首段前缀替换 | 跳过 %d 张（名里没有下划线，或以下划线开头）：%s"
                     % (len(skipped), "、".join("`%s`" % n for n in skipped)))
        return tables
    make_unique_names(tables)
    return tables


# ---------------------------------------------------------------------------
# 三种动作
# ---------------------------------------------------------------------------
def do_check(cfg, log):
    try:
        conn = connect(cfg)
    except Exception as e:
        log.error("连接失败 | %s | %s" % (target_label(cfg), describe_db_error(e)))
        return EXIT_CONNECT
    try:
        dbs = list_databases(conn)
    except Exception as e:
        log.error("取库列表失败 | %s | %s" % (target_label(cfg), describe_db_error(e)))
        return EXIT_CONNECT
    finally:
        try:
            conn.close()
        except Exception:
            pass
    log.ok("连接成功 | %s | 可导入的库 %d 个：%s"
           % (cfg["host"] + ":" + str(cfg["port"]), len(dbs), ", ".join(dbs) or "(无)"))
    if cfg["database"] and cfg["database"] not in dbs:
        log.error("目标库不存在 | %s 不在上面的列表里，请先建库或改用 -d 指定" % cfg["database"])
        return EXIT_CONNECT
    return EXIT_OK


def do_dry_run(tables, args, log):
    """不连库：打印每张表的建表语句，供现场核对与人工执行。"""
    for t in tables:
        ddl = build_ddl(t["table_name"], t["table_comment"], t["columns"],
                        args.drop_if_exists)
        log.info("建表语句预览 | `%s`（%d 行数据）" % (t["table_name"], len(t["df"])))
        log.detail(ddl)
    log.ok("dry-run 完成 | 共 %d 张表未写入数据库（本次没有连接 MySQL）" % len(tables))
    return EXIT_OK


def do_import(tables, args, cfg, log):
    try:
        conn = connect(cfg)
    except Exception as e:
        log.error("连接失败 | %s | %s" % (target_label(cfg), describe_db_error(e)))
        return EXIT_CONNECT

    ok_names, bad_names = [], []
    total_rows = 0
    try:
        for idx, t in enumerate(tables, 1):
            name = t["table_name"]
            total = len(t["df"])
            log.info("开始处理 | [%d/%d] 表 `%s`（%d 行）" % (idx, len(tables), name, total))
            conn.begin()
            ddl = build_ddl(name, t["table_comment"], t["columns"], args.drop_if_exists)
            try:
                create_table(conn, ddl)
                log.ok("建表成功 | `%s`" % name)
            except Exception as e:
                bad_names.append(name)
                log.error("建表失败 | `%s` | 目标库 %s | %s"
                          % (name, cfg["database"], describe_db_error(e)))
                log.detail("失败的建表语句：\n" + ddl, level="error")
                log.detail(DDL_NO_ROLLBACK_NOTE, level="error")
                continue

            def cb(cur, tot):
                if cur == tot:
                    log.info("导入进度 | `%s` %d/%d 行" % (name, cur, tot))

            try:
                rows = insert_data(conn, name, t["columns"], t["df"],
                                   batch=args.batch, progress_cb=cb,
                                   warn_cb=log.warn)
                ok_names.append(name)
                total_rows += rows
                log.ok("导入完成 | `%s` 共 %d 行" % (name, rows))
            except Exception as e:
                conn.rollback()
                bad_names.append(name)
                log.error("导入失败 | `%s` | %d 行数据未写入，本表事务已回滚 | %s"
                          % (name, total, describe_db_error(e)))
                log.detail(DDL_NO_ROLLBACK_NOTE, level="error")
    finally:
        try:
            conn.close()
        except Exception:
            pass
    log.ok("导入汇总 | 成功 %d 张表 / %d 行，失败 %d 张"
           % (len(ok_names), total_rows, len(bad_names)))
    if bad_names:
        log.detail("失败表：%s" % "、".join("`%s`" % n for n in bad_names), level="error")
        return EXIT_IMPORT
    return EXIT_OK


DDL_NO_ROLLBACK_NOTE = ("注意：CREATE/DROP/ALTER 等 DDL 在 MySQL 中隐式提交，不会随事务回滚；"
                        "回滚仅对其后的数据操作生效。")


# ---------------------------------------------------------------------------
def run(argv=None):
    try:
        args = build_parser().parse_args(argv)
    except SystemExit as e:
        # argparse 对 --help/--version 抛 SystemExit(0)，对非法参数抛 SystemExit(2)。
        # 直接让它逃出去会用退出码 2 撞掉本项目的 EXIT_PARSE=2（解析失败），
        # 让调用方把"参数没写对"误读成"文件解析失败"，所以在这里翻译一次。
        code = EXIT_OK if (e.code is None or e.code == 0) else EXIT_USAGE
        print("- [INFO] %s" % ("已打印用法与退出码 0" if code == EXIT_OK
                                else "用法错误 | 参数不被识别或取值非法（详见 --help），退出码 %d"
                                     % code), flush=True)
        return code
    # 不强制 UTF-8：Win7 cmd 默认 cp936，照控制台码页输出中文才不乱码；
    # errors="replace" 保证万一有码页外字符也只是替换，不会把进程炸掉。
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")
        except (AttributeError, ValueError):
            pass

    log = Log(path=args.log, quiet=args.quiet)
    t0 = time.time()
    code = EXIT_OK
    tmp_dir = None
    try:
        log.info("excel2mysql-cli %s | Python %s | 动作 %s"
                 % (APP_VERSION, sys.version.split()[0],
                    "检查连接" if args.check else ("只推断" if args.dry_run else "建表导入")))
        try:
            cfg = resolve_cfg(args, log)
        except ValueError as e:
            log.error("配置错误 | %s" % e)
            return EXIT_USAGE
        bad = validate(args, cfg, log)
        if bad:
            return bad

        if args.check:
            return do_check(cfg, log)

        entries = []
        files, zips = collect_inputs(args.inputs, not args.no_recursive, log)
        entries.extend(files)
        if zips:
            tmp_dir = tempfile.mkdtemp(prefix="e2m_cli_")
            expanded, zips_bad = expand_zips(zips, tmp_dir, log)
            entries.extend(expanded)
        else:
            zips_bad = 0
        if not entries:
            log.error("没有可解析的 Excel | 输入里没有 .xlsx / .xls 文件")
            return EXIT_PARSE if zips_bad else EXIT_USAGE

        parse_failed = zips_bad
        tables = []
        for path, origin in entries:
            td, err = parse_one(path, origin, log)
            if td is None:
                parse_failed += 1
            else:
                tables.append(td)
        if not tables:
            log.error("全部输入解析失败 | 共 %d 项" % parse_failed)
            return EXIT_PARSE
        if parse_failed:
            log.warn("解析阶段有失败 | %d 项未进入待导入列表，其余 %d 张继续"
                     % (parse_failed, len(tables)))

        apply_naming(tables, args, log)
        if args.drop_if_exists:
            log.warn("危险开关 | --drop-if-exists 已开启：目标库里的同名表会先被删除")

        action_rc = do_dry_run(tables, args, log) if args.dry_run else do_import(tables, args, cfg, log)
        code = action_rc
        if parse_failed:
            code = max(code, EXIT_PARSE)
        return code
    except KeyboardInterrupt:
        log.error("已中止 | 用户按 Ctrl+C，剩余表未执行")
        return 130
    finally:
        log.info("耗时 | %.1f 秒 | 退出码 %s" % (time.time() - t0, code))
        if args.log:
            log.info("日志文件 | %s" % os.path.abspath(args.log))
        if tmp_dir:
            if args.keep_temp:
                log.info("临时目录 | 已保留解压出的 Excel：%s" % tmp_dir)
            else:
                shutil.rmtree(tmp_dir, ignore_errors=True)
        log.close()


def main():
    try:
        sys.exit(run())
    except BrokenPipeError:      # 管道被下游提前关掉（如 | more）不算失败
        sys.exit(EXIT_OK)


if __name__ == "__main__":
    main()
