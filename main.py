"""
Excel -> MySQL 批量导入工具（PySide6 GUI）
========================================
功能：
  - 输入 MySQL 连接信息，选择目标库
  - 拖拽 / 选择多个 Excel 文件，或直接给 ZIP 压缩包（自动展开包内的多个 Excel）
  - 添加完成后弹窗汇总：加了几项、xlsx / xls 各多少、有无失败或重复跳过
  - 含合并单元格的表直接拒绝导入（合并区只有左上角有值，导进去必然错位）
  - 自动推断表结构：文件名形如「注释（table_name）」时表名取括号内英文、
    表注释取括号前中文；否则表名由文件名汉字转拼音生成（符合 MySQL 规范）、
    表注释=文件名；字段注释=中文表头
  - 字段名由中文表头逐音节转拼音、蛇形命名，全拼超 30 字符整列降级为首字母
  - 表名支持批量添加前缀
  - 预览并编辑表名、字段名、类型、注释
  - 预览 DDL，一键建表并导入数据
  - 解析 / 导入 / 批量改前缀 / 读写 SQL 脚本全部放子线程，配模态进度框（可取消、不卡界面）
  - 逐表日志 + 进度条，单表失败不阻断其余
  - 导入成功的文件自动移出待导入列表，失败文件保留可重试

依赖：pip install -r requirements.txt   （PySide6 pymysql pandas openpyxl xlrd pypinyin）
运行：python main.py            打包：双击 一键打包.bat
"""
import os
import shutil
import sys
import tempfile
import unicodedata
import warnings

# 与 cli.py 同一条口径：openpyxl 读"没有命名样式"的 Excel（真实数据包里全是这种）会给每张表
# 打一行 UserWarning，pandas/cryptography 在 import 期也有自己的警告。这些必须在
# core.infer 被引进来之前压掉，否则日志里会淹出一堆不属于本工具格式的行。
warnings.filterwarnings("ignore", message=".*no default style.*", append=True)
warnings.filterwarnings("ignore", message=".*no longer supported by the Python core team.*",
                        append=True)

import functools

from PySide6.QtCore import Qt, QThread, Signal, QUrl, QFileInfo, QTimer
from PySide6.QtGui import QDragEnterEvent, QDropEvent, QFont, QIcon, QColor, QTextCursor, QTextCharFormat
from PySide6.QtWidgets import (
    QApplication, QWidget, QVBoxLayout, QHBoxLayout, QFormLayout,
    QLineEdit, QSpinBox, QPushButton, QComboBox, QFileDialog, QTableWidget,
    QTableWidgetItem, QLabel, QProgressBar, QPlainTextEdit, QGroupBox,
    QMessageBox, QHeaderView, QCheckBox, QAbstractItemView,
    QDialog, QDialogButtonBox, QTabWidget, QSplitter, QListWidget,
)

from core.log_text import describe_db_error
from core.infer import (
    build_table_def, make_unique_names, apply_table_prefix, validate_primary_key,
    replace_first_segment,
)
from core.sql_execute import run as run_sql_statements
from core.sql_script import (
    extract_create_tables, apply_table_prefix_sql, split_sql_statements,
    replace_first_segment_sql, parse_create_tables,
)
from core.naming import clean_prefix, is_valid_prefix
from sql_samples import SAMPLES as SQL_SAMPLES
from core.mysql_io import (
    build_ddl, connect, list_databases, create_table, insert_data,
)
from core.zip_import import (
    extract_excel_archive, count_excel_entries, ZipArchiveError, EXCEL_EXTS,
)
from core.merge_check import MergedCellsError
from ui_help import HelpDialog, AboutDialog, fit_client
from appinfo import build_info, title_text
from version import APP_VERSION

IMPORT_EXTS = EXCEL_EXTS + (".zip",)   # 拖拽区与文件选择框共同接受的扩展名


def resource_path(rel: str) -> str:
    """打包后资源在 PyInstaller 解包目录，开发态在项目根目录。"""
    base = getattr(sys, "_MEIPASS", None)
    if base is None:
        base = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(base, rel)


def program_dir() -> str:
    """可执行程序所在目录——生成物的日期目录要落在这里。

    不能用 resource_path 那个 `_MEIPASS`：那是 onefile 的一次性解包临时目录，
    程序退出就被删掉，用户在里面找不到自己刚生成的文件。
    onedir 与开发态取的是 exe（或 main.py）真正待着的那一层。
    """
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


# ---------------------------------------------------------------------------
# 日志输出：错误整条标红，并把数据库异常展开成可读的详细信息
# ---------------------------------------------------------------------------
LOG_ERROR_COLOR = "#c62828"
MAX_LOG_STATEMENT = 2000   # 出错语句全文最长展示，超出截断，避免超大 INSERT 刷爆日志

# 连接区在三个页签上方常驻，不属于任何一页，指引必须指向"上方"而不是某个页签
NO_DB_HINT = "未选择目标数据库：请先在上方填写连接信息并点「连接并加载库」，再选择目标库"
NO_FILE_HINT = "请先添加 Excel 文件或 ZIP 压缩包。"

# 「SQL 执行器」编辑区的开场文本：既是"连不连得上库"的一键自检语句，
# 也是「插入到编辑区」判断"这段还是默认值、可以直接覆盖掉"的比对基准
SQL_RUN_DEFAULT = "SELECT 1 AS 连通检查;"

ROLLBACK_NOTE = ("注意：CREATE/DROP/ALTER 等 DDL 在 MySQL 中隐式提交，不会随事务回滚；"
                 "回滚仅对其后的数据操作生效。")

# 弹窗正文的折行预算，两个数都按最小的目标机 1024x768 算：
# 56 显示列（汉字/全角算 2 列）约等于 28 个汉字的宽度，加上图标与边框仍远在屏内；
# 22 行连标题栏和按钮也还在可用高度里。再长就不往弹窗里硬塞了——放不下的部分
# 会把开头几行顶出屏幕，那才是真的看不见；完整内容本来就写在底部日志里。
POPUP_WRAP_COLS = 56
POPUP_MAX_LINES = 22
POPUP_OVERFLOW_NOTE = "……（其余 %d 行放不下，完整内容见底部日志）"


def _display_cols(ch: str) -> int:
    """一个字符占几个显示列：全角与宽字符算 2，其余算 1。"""
    return 2 if unicodedata.east_asian_width(ch) in ("F", "W") else 1


def wrap_popup_text(text: str) -> str:
    """把弹窗正文按显示列宽折行，并给放不下的行留一句去日志里看的提示。

    两边的控件库默认排版不一样：Swing 的消息框完全折不了一行（一条长路径能把它
    撑到 1286 像素宽），Qt 会折但会把 30 行正文撑到比屏幕还高。所以折行自己算，
    算完两边拿到的是同一个字符串，界面上才谈得上等价。
    """
    lines = []
    for seg in text.split("\n"):
        buf = ""
        wide = 0
        for ch in seg:
            w = _display_cols(ch)
            if buf and wide + w > POPUP_WRAP_COLS:
                lines.append(buf)
                buf = ""
                wide = 0
            buf += ch
            wide += w
        lines.append(buf)
    if len(lines) <= POPUP_MAX_LINES:
        return "\n".join(lines)
    kept = lines[:POPUP_MAX_LINES - 1]
    kept.append(POPUP_OVERFLOW_NOTE % (len(lines) - len(kept)))
    return "\n".join(kept)


def popup_box(parent, title: str, text: str, kind: str = "information"):
    """消息弹窗都走这一道：折行与限高只在这里写一遍，两条线同一口径。"""
    fn = {"information": QMessageBox.information,
          "warning": QMessageBox.warning,
          "critical": QMessageBox.critical}[kind]
    return fn(parent, title, wrap_popup_text(text))



def append_log(view: QPlainTextEdit, text: str, error: bool = False) -> None:
    """向日志控件追加一条记录；error=True 时整条（含换行展开的多个块）标红加粗。"""
    lines = text.count("\n") + 1
    view.appendPlainText(text)
    if not error:
        return
    doc = view.document()
    blk = doc.lastBlock()
    for _ in range(lines - 1):          # 回溯到本条记录的首个块
        prev = blk.previous()
        if not prev.isValid():
            break
        blk = prev
    cur = QTextCursor(doc)
    cur.setPosition(blk.position())
    last = doc.lastBlock()
    # 只覆盖本条记录的文字，不含块尾分隔符，避免格式渗到之后追加的日志上
    cur.setPosition(last.position() + last.length() - 1, QTextCursor.KeepAnchor)
    fmt = QTextCharFormat()
    fmt.setForeground(QColor(LOG_ERROR_COLOR))
    fmt.setFontWeight(QFont.Bold)
    cur.mergeCharFormat(fmt)




def format_statement(st: str) -> str:
    """出错语句按原样缩进展示，过长时截断并标注原始长度。"""
    body = st.strip()
    if len(body) > MAX_LOG_STATEMENT:
        body = body[:MAX_LOG_STATEMENT] + f"\n…（语句过长已截断，原文共 {len(st)} 字符）"
    return "\n".join("        " + ln for ln in body.splitlines())


def indent_detail(text: str, pad: str = "    ") -> str:
    """把多行详情统一缩进，避免与首行混在一起看不出层次。"""
    return "\n".join(pad + ln for ln in str(text).splitlines())


# ---------------------------------------------------------------------------
# 长任务执行框架：工作放子线程，主线程弹模态进度框实时刷新
# ---------------------------------------------------------------------------
# 离屏用例把这里置 False：任务原地跑完、不弹模态框（exec() 会挂住测试进程）。
# 两条路径用的是同一个工作函数和同一个回报接口，测试跑到的就是线上那段逻辑。
MODAL_PROGRESS = True


def _ignore(*_args, **_kwargs):
    """占位回调：ProgressSink 的默认通道。"""


class ProgressSink:
    """工作函数唯一的对外出口：报进度、报日志、查取消。

    子线程里三个出口接到 ProgressWorker 的信号上（跨线程自动排队回主线程，
    所以工作函数绝对不能自己碰控件）；同步执行时直接接到本线程的函数上。
    工作函数只认这个接口，因此可以脱离 Qt 单测。
    """

    def __init__(self, on_progress=_ignore, on_log=_ignore, on_log_error=_ignore,
                 is_cancelled=None):
        self.on_progress = on_progress
        self.on_log = on_log
        self.on_log_error = on_log_error
        self.is_cancelled = is_cancelled or (lambda: False)
        self.done = 0
        self.total = 0

    def set_total(self, total):
        """设置总项数；只增不减，进度条不会因为中途改总数而回退。"""
        self.total = max(self.total, int(total))
        self._tick("")

    def step(self, label=""):
        """完成一项：进度前进一格。"""
        self.advance(1, label)

    def advance(self, n=1, label=""):
        """一次推进 n 项。大批量小步骤（比如逐条 SQL）按块计数，
        否则几十万条语句会发出几十万次刷新信号，反而把界面拖卡。"""
        self.done += max(0, int(n))
        self._tick(label)

    def note(self, label=""):
        """只换当前项文字、不推进进度：用于总数还没算出来的准备阶段。"""
        self._tick(label)

    def _tick(self, label):
        # total 还没定（或工作函数多报了）时兜底，避免除零和百分比失真
        self.on_progress(self.done, max(self.total, self.done, 1), label)

    def log(self, text):
        self.on_log(text)

    def log_error(self, text):
        self.on_log_error(text)

    @property
    def cancelled(self):
        return bool(self.is_cancelled())


class ProgressDialog(QDialog):
    """模态进度框：标题 + 当前项文字 + 进度条 + 取消按钮。

    耗活的都在子线程，主线程只在信号到达时刷新控件，所以框始终能重绘、能拖动、
    取消按钮始终点得动，不会像同步循环那样"假死"。
    """

    def __init__(self, title, parent=None):
        super().__init__(parent)
        self.setWindowTitle(title)
        # 只保留标题：任务进行中不能从标题栏关窗（线程还在跑），退出只走「取消」
        self.setWindowFlags(Qt.Dialog | Qt.CustomizeWindowHint | Qt.WindowTitleHint)
        self.setModal(True)
        fit_client(self, 480, 140)
        self._target = 0

        head = QLabel(title)
        font = QFont()
        font.setBold(True)
        head.setFont(font)
        self.lbl_step = QLabel("准备中…")
        self.lbl_step.setWordWrap(True)
        self.lbl_step.setStyleSheet("color: #555;")
        self.bar = QProgressBar()
        self.bar.setRange(0, 100)
        self.bar.setValue(0)
        self.bar.setFormat("%p%")
        self.btn_cancel = QPushButton("取消")
        self.btn_cancel.clicked.connect(self._on_cancel)

        lay = QVBoxLayout(self)
        lay.addWidget(head)
        lay.addWidget(self.lbl_step)
        lay.addWidget(self.bar)
        row = QHBoxLayout()
        row.addStretch(1)
        row.addWidget(self.btn_cancel)
        lay.addLayout(row)

        # 项数少时 i/N 只有几格，直接赋值会一跳一跳；用定时器把目标值补间过去
        self._anim = QTimer(self)
        self._anim.setInterval(16)                 # ≈60fps
        self._anim.timeout.connect(self._animate)
        self._anim.start()

    def set_progress(self, done, total, label):
        self._target = 100 if total <= 0 else max(0, min(100, int(done * 100 / total)))
        text = f"进度 {done}/{total} 项"
        if label:
            text += f" · {label}"
        self.lbl_step.setText(text)

    def _animate(self):
        cur, target = self.bar.value(), self._target
        if cur == target:
            return
        gap = abs(target - cur)
        # 逼近式步进：差距大时跑得快，接近终点自动放缓，看起来就是"平滑推进"
        move = max(1, gap // 4)
        self.bar.setValue(target if gap <= move else (cur + move if cur < target else cur - move))

    def _on_cancel(self):
        self.btn_cancel.setEnabled(False)
        self.lbl_step.setText("正在取消…（当前项做完就停）")

    def finish(self):
        """收尾：进度条直接拉满并关闭对话框。"""
        self._anim.stop()
        self.bar.setValue(100)
        self.accept()


class ProgressWorker(QThread):
    """在子线程执行 work(*args, sink)——sink 约定是工作函数的最后一个入参。

    work 必须是纯工作函数：只通过 sink 报进度和日志，绝不读写界面。
    单条目的异常应由 work 自己兜住并计入结果；work 整体抛异常时走 failed。
    """

    progress = Signal(int, int, str)
    log = Signal(str)
    log_error = Signal(str)
    completed = Signal(object)
    failed = Signal(str)

    def __init__(self, work, args=(), parent=None):
        super().__init__(parent)
        self._work = work
        self._args = tuple(args)
        self._cancel = False

    def cancel(self):
        self._cancel = True

    def run(self):
        sink = ProgressSink(
            on_progress=self.progress.emit,
            on_log=self.log.emit,
            on_log_error=self.log_error.emit,
            is_cancelled=lambda: self._cancel,
        )
        try:
            result = self._work(*self._args, sink)
        except Exception as e:
            self.failed.emit(describe_db_error(e))
            return
        self.completed.emit(result)


# ---------------------------------------------------------------------------
# 纯工作函数：不碰控件，可在子线程跑，也可脱离 Qt 单测
# ---------------------------------------------------------------------------
# build_table_def 会逐阶段回报（合并检测 / 读取工作簿 / 识别格式 / 推断类型 / 选主键），
# 解析一个文件就按这么多项计；万一阶段数变了，末尾的补齐逻辑仍能把进度条落准。
_PARSE_UNITS = 5


def parse_import_files(files, existing_paths, zip_dir, dest_start, sink,
                       initials=False, upper=False):
    """ZIP 展开 + 逐文件解析表结构，返回待导入列表与汇总计数。

    进度总项数先按「每个压缩包 1 项 + 包内每个 Excel 解出 1 项 + 每个待解析 Excel
    _PARSE_UNITS 项（对应 build_table_def 的逐阶段回报）」算准（压缩包只读中央目录，
    代价很小），所以进度条一路单向前进，单个大文件解析途中也在走。
    取消时返回已解析到的部分结果；dest_start 是解压子目录编号的起点，跨批次递增，
    避免同名压缩包互相覆盖。
    """
    zips = [f for f in files if f.lower().endswith(".zip")]
    loose = [f for f in files if not f.lower().endswith(".zip")]
    entries = sum(count_excel_entries(z) for z in zips)
    sink.set_total(len(zips) + entries + _PARSE_UNITS * (len(loose) + entries))

    out, from_zip, zip_origin = [], set(), {}
    zips_ok = zips_bad = zips_empty = 0
    cancelled = False
    for f in files:
        if not f.lower().endswith(".zip"):
            out.append(f)                          # 散装 Excel 留到解析阶段计项
            continue
        zname = os.path.basename(f)
        stem = os.path.splitext(zname)[0] or "archive"
        dest = os.path.join(zip_dir,
                            f"{stem}_{dest_start + len(zip_origin) + 1}")
        try:
            r = extract_excel_archive(
                f, dest,
                progress_cb=lambda name, z=zname: sink.step(f"解出 {z}/{os.path.basename(name)}"))
        except ZipArchiveError as e:
            zips_bad += 1
            sink.log_error(f"[压缩包失败] {zname}：{e}")
        except Exception as e:
            zips_bad += 1
            sink.log_error(f"[压缩包失败] {zname} 展开异常\n"
                           + indent_detail(describe_db_error(e)))
        else:
            zips_ok += 1
            for x in r["files"]:
                zip_origin[x["path"]] = f"{zname}!{x['origin']}"
                from_zip.add(x["path"])
            out.extend(x["path"] for x in r["files"])
            msg = f"[压缩包] {zname}：展开 {len(r['files'])} 个 Excel"
            if r["ignored"]:
                msg += f"，忽略 {len(r['ignored'])} 个非 Excel 文件"
            if r["nested_zips"]:
                msg += f"，跳过 {r['nested_zips']} 个嵌套 zip（不支持递归展开）"
            sink.log(msg)
            if not r["files"]:
                zips_empty += 1
                sink.log_error(f"[压缩包为空] {zname}：包内没有找到 .xlsx / .xls 文件")
        sink.step(zname)
        if sink.cancelled:
            cancelled = True
            break

    tables, added_loose, added_zip = [], [], []
    failed = dups = merged = 0
    existing = set(existing_paths)
    for f in out:
        if sink.cancelled:
            cancelled = True
            break
        fname = os.path.basename(f)
        if f in existing:
            dups += 1
            sink.log(f"[重复跳过] {fname} 已在待导入列表")
            sink.advance(_PARSE_UNITS, fname)
            continue
        used = [0]

        def phase(text, n=fname, used=used):
            """把 build_table_def 的阶段回报转成进度：走一格报一次，收尾再补齐。"""
            if used[0] < _PARSE_UNITS:
                used[0] += 1
                sink.advance(1, f"{n}：{text}")

        try:
            td = build_table_def(f, progress_cb=phase, initials=initials,
                                 upper=upper)
            tables.append(td)
            (added_zip if f in from_zip else added_loose).append(f)
            pk = td.get("primary_key")
            pk_msg = (f"，主键 `{pk}`（{td.get('pk_reason', '')}）" if pk
                      else f"，未设主键（{td.get('pk_reason', '')}）")
            src_msg = "，结构定义模式" if td.get("source") == "structured" else ""
            merged_header = td.get("merged_header")
            merge_msg = (f"，合并表头按 {merged_header['depth']} 行展开"
                         if merged_header else "")
            extra_cols = td.get("extra_data_columns") or []
            extra_msg = (f"；数据 Sheet 多余列已忽略：{'、'.join(extra_cols)}"
                         if extra_cols else "")
            empty_cols = td.get("empty_columns") or []
            empty_msg = ""
            if empty_cols:
                head = "、".join(empty_cols[:6])
                more = f" 等 {len(empty_cols)} 个" if len(empty_cols) > 6 else ""
                empty_msg = f"；整列为空的字段照旧保留：{head}{more}"
            zero_msg = "；没有数据行，建出来是空表" if td.get("no_data_rows") else ""
            blank_h = td.get("blank_headers") or []
            blank_msg = ""
            if blank_h:
                head = "、".join(blank_h[:6])
                more = f" 等 {len(blank_h)} 个" if len(blank_h) > 6 else ""
                blank_msg = f"；表头整行没写字，按列宽兜底成：{head}{more}"
            sink.log(
                f"[已解析] {fname} → 表名预置 {td['table_name']}，"
                f"字段 {len(td['columns'])} 列，数据 {len(td['df'])} 行{src_msg}"
                f"{merge_msg}{pk_msg}{extra_msg}{empty_msg}{zero_msg}{blank_msg}")
            notes = [c["type_note"] for c in td["columns"] if c.get("type_note")]
            if notes:
                sink.log(
                    f"[类型修正] {fname} 共 {len(notes)} 列按表头语义调整："
                    + "\n    " + "\n    ".join(notes))
        except MergedCellsError as e:
            merged += 1
            total = sum(len(v) for v in e.merged.values())
            sink.log_error(
                f"[合并单元格] {fname} 含 {total} 处合并区，"
                f"已拒绝导入，未进入待导入列表\n"
                + indent_detail(str(e))
                + "\n    原因：合并区只有左上角有值，其余格读进来是空值，"
                  "字段名与数据会错位。请先在 Excel 中取消合并后重新添加。")
        except Exception as e:
            failed += 1
            sink.log_error(f"[解析失败] {f} 未能解析成表结构，已跳过\n"
                           + indent_detail(describe_db_error(e)))
        sink.advance(max(0, _PARSE_UNITS - used[0]), fname)

    return {"tables": tables, "added_loose": added_loose, "added_zip": added_zip,
            "zip_origin": zip_origin, "zips_ok": zips_ok, "zips_bad": zips_bad,
            "zips_empty": zips_empty, "failed": failed, "dups": dups, "merged": merged,
            "cancelled": cancelled or sink.cancelled}


def table_prefix_task(tables, prefix, sink):
    """批量重算表名（Excel 导入页）。

    改名本身是内存操作、耗时可忽略，这里按表计项只是为了让按钮点后有一段
    看得见的进度而不是"点了没反应"。tables 会被就地改写。
    """
    sink.set_total(len(tables) + 1)
    apply_table_prefix(tables, prefix, on_item=lambda t: sink.step(t["table_name"]))
    sink.step("重名去重")
    return {"prefix": prefix, "cancelled": sink.cancelled}


def table_replace_task(tables, new_head, sink):
    """替换表名首段前缀（Excel 导入页）。改名与去重都在子线程，逐表报进度。"""
    sink.set_total(len(tables) + 1)
    renamed, skipped = replace_first_segment(
        tables, new_head, on_item=lambda t: sink.step(t["table_name"]))
    sink.step("重名去重")
    return {"renamed": renamed, "skipped": skipped, "cancelled": sink.cancelled}


def sql_replace_prefix_task(items, new_head, sink):
    """逐个脚本替换表名首段前缀；入参是快照，改完由主线程写回。"""
    sink.set_total(len(items))
    updates, total = {}, 0
    for path, text, rename_map in items:
        fname = os.path.basename(path)
        if sink.cancelled:
            break
        new_text, renamed, skipped = replace_first_segment_sql(text, new_head)
        if renamed:
            rev = {v: k for k, v in rename_map.items()}
            updates[path] = {"text": new_text, "tabs": extract_create_tables(new_text),
                             "rename_map": {rev.get(r["old"], r["old"]): r["new"]
                                            for r in renamed}}
            sink.log(f"[换前缀] {fname}："
                     + "、".join(f"{r['old']} → {r['new']}（{r['n']} 处）" for r in renamed))
            total += len(renamed)
        if skipped:
            sink.log(f"[跳过] {fname}：未改写 {'、'.join(skipped)}"
                     f"（表名里没有下划线，或脚本中找不到引用）")
        sink.step(fname)
    sink.log(f"[换前缀完成] 共改写 {total} 个表名"
             f"（修改在内存中，可点「保存修改后脚本」落盘）")
    return {"updates": updates, "cancelled": sink.cancelled}


def read_sql_text(path):
    """按 utf-8-sig → utf-8 → gbk 依次尝试解码，全失败则替换非法字节而不是报错。"""
    with open(path, "rb") as fh:
        raw = fh.read()
    for enc in ("utf-8-sig", "utf-8", "gbk"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def sql_load_task(paths, sink):
    """读入 SQL 脚本文本并提取表名：几十万行的脚本读盘 + 正则扫描都在这里。

    返回 loaded（可直接并入 self.sql_files 的记录），不碰界面数据。
    """
    sink.set_total(len(paths))
    loaded = []
    for p in paths:
        fname = os.path.basename(p)
        if sink.cancelled:
            break
        try:
            text = read_sql_text(p)
        except Exception as e:
            failed += 1
            sink.log_error(f"[读取失败] {fname} 未能读入，已跳过\n"
                           + indent_detail(describe_db_error(e)))
            sink.step(fname)
            continue
        tabs = extract_create_tables(text)
        names = "、".join(t["name"] for t in tabs) or "（未发现 CREATE TABLE 语句）"
        sink.log(f"[已加载] {fname}：{len(tabs)} 个表（{names}）")
        loaded.append({"path": p, "text": text, "modified": False,
                       "rename_map": {}, "tabs": tabs})
        sink.step(fname)
    return {"loaded": loaded, "cancelled": sink.cancelled}


def sql_prefix_task(items, prefix, sink):
    """逐个脚本改写 CREATE TABLE 表名。

    入参是 [(path, text, rename_map), ...] 快照，返回按 path 索引的改写结果，
    由主线程统一写回 —— 子线程不碰 self.sql_files，界面和数据各改各的不会打架。
    """
    sink.set_total(len(items))
    updates, total = {}, 0
    for path, text, rename_map in items:
        fname = os.path.basename(path)
        if sink.cancelled:
            break
        new_text, renamed, skipped = apply_table_prefix_sql(text, prefix)
        if renamed:
            rev = {v: k for k, v in rename_map.items()}
            # 顺手在工作线程重算表名清单：几十万行的脚本不必再回主线程扫一遍
            updates[path] = {"text": new_text, "tabs": extract_create_tables(new_text),
                             "rename_map": {rev.get(r["old"], r["old"]): r["new"]
                                            for r in renamed}}
            sink.log(f"[前缀] {fname}："
                     + "、".join(f"{r['old']} → {r['new']}（{r['n']} 处）" for r in renamed))
            total += len(renamed)
        if skipped:
            sink.log(f"[跳过] {fname}：已带前缀 {'、'.join(skipped)}")
        sink.step(fname)
    sink.log(f"[前缀完成] 共改写 {total} 个表名"
             f"（修改在内存中，可点「保存修改后脚本」落盘）")
    return {"updates": updates, "cancelled": sink.cancelled}


def sql_save_task(items, sink):
    """把改过的脚本写成 *_prefix.sql，几十万行的写盘放在子线程里。

    items 为 [(path, text), ...]；返回 saved 为 [(原路径, 落盘路径), ...]。
    """
    sink.set_total(len(items))
    saved = []
    for path, text in items:
        fname = os.path.basename(path)
        if sink.cancelled:
            break
        stem, ext = os.path.splitext(path)
        out = f"{stem}_prefix{ext or '.sql'}"
        try:
            with open(out, "w", encoding="utf-8") as fh:
                fh.write(text)
        except Exception as e:
            sink.log_error(f"[保存失败] {fname}：{describe_db_error(e)}")
            sink.step(fname)
            continue
        saved.append((path, out))
        sink.log(f"[已保存] {out}")
        sink.step(fname)
    return {"saved": saved, "cancelled": sink.cancelled}


def sql_execute_task(items, cfg, sink):
    """逐个脚本执行到目标库，每个文件一个事务。

    items 为 [(path, text), ...]；语句条数先汇总再计项，几十万条时按块推进，
    保证进度条既有分辨率又不会发出海量信号。
    """
    sink.note(f"正在切分 {len(items)} 个脚本的语句…")
    plans = []
    for path, text in items:
        stmts = split_sql_statements(text)
        plans.append((path, stmts))
        if sink.cancelled:
            break
    sink.set_total(sum(len(p[1]) for p in plans))

    target = f"{cfg['database']}@{cfg['host']}:{cfg['port']}"
    ok_files = fail_files = skipped = 0
    for path, stmts in plans:
        fname = os.path.basename(path)
        if sink.cancelled:
            break
        if not stmts:
            skipped += 1
            sink.log(f"[跳过] {fname}：没有可执行的语句")
            continue
        try:
            conn = connect(cfg)
        except Exception as e:
            sink.log_error(
                f"[连接失败] 无法连接到 {target}（用户 {cfg['user']}），剩余脚本均未执行\n"
                + indent_detail(describe_db_error(e)))
            return {"ok": ok_files, "fail": fail_files, "skipped": skipped,
                    "cancelled": True, "conn_failed": True}
        chunk = max(1, len(stmts) // 100)           # 每个文件最多约 100 次刷新
        reported = 0
        failed = False
        executed = 0                                # 供异常分支引用，begin/cursor 失败时也已有值
        try:
            conn.begin()                            # 先开启事务
            cur = conn.cursor()
            for pos, st in enumerate(stmts, 1):
                if sink.cancelled:
                    break
                try:
                    cur.execute(st)
                    if cur.description is not None:
                        cur.fetchall()
                    executed += 1
                except Exception as e:
                    conn.rollback()                 # 执行失败 → 回滚事务
                    failed = True
                    sink.log_error((
                        f"[执行失败] {fname} 第 {pos}/{len(stmts)} 条语句报错，"
                        f"目标库 {target}，本文件事务已回滚\n"
                        + indent_detail(describe_db_error(e)) + "\n"
                        + "    出错语句：\n" + format_statement(st) + "\n"
                        + "    前面 " + str(executed) + " 条语句已随事务回滚。"
                        + ROLLBACK_NOTE))
                    break
                if pos - reported >= chunk:
                    reported = pos
                    sink.advance(chunk, f"{fname} 第 {pos}/{len(stmts)} 条")
            try:
                cur.close()
            except Exception:
                pass
            if failed:
                pass
            elif sink.cancelled and executed < len(stmts):
                # 中途取消：本文件不提交，回滚后重跑才是干净的（DDL 隐式提交的部分除外）
                conn.rollback()
                sink.log(f"[已取消] {fname}：已执行 {executed}/{len(stmts)} 条，"
                         f"本文件事务已回滚\n    " + ROLLBACK_NOTE)
            else:
                conn.commit()                       # 全部成功 → 提交事务
                sink.log(f"[提交] {fname}：{executed} 条语句已提交")
        except Exception as e:
            failed = True
            try:
                conn.rollback()
            except Exception:
                pass
            sink.log_error((
                f"[执行失败] {fname} 执行异常（已提交 {executed} 条），目标库 {target}，"
                f"本文件事务已回滚\n"
                + indent_detail(describe_db_error(e)) + "\n"
                + "    " + ROLLBACK_NOTE))
        finally:
            try:
                conn.close()
            except Exception:
                pass
        sink.advance(len(stmts) - reported, f"{fname} 收尾")   # 把余数补齐，进度条落准
        if failed:
            fail_files += 1
        else:
            ok_files += 1
    return {"ok": ok_files, "fail": fail_files, "skipped": skipped,
            "cancelled": sink.cancelled, "conn_failed": False}


def sql_run_task(text, cfg, sink):
    """「SQL 执行器」页签：把编辑区里那段文本逐条打到目标库上。

    连接配置在主线程取好再传进来（工作线程绝对不能碰控件）；连不上库时
    与 Swing 那条线说同一句话，返回 None 表示这次没做成、不要回调汇总。
    """
    try:
        conn = connect(cfg)
    except Exception as e:
        sink.log_error("x [ERROR] 连不上库 | " + describe_db_error(e))
        return None
    try:
        return {"executed": run_sql_statements(conn, text, sink.log)}
    finally:
        try:
            conn.close()
        except Exception:
            pass


def sql_meta_export_task(items, out_root, with_meta, sink):
    """「SQL 脚本改名入库」页的生成标准/简易 Excel：先从脚本正文解析表定义，再走同一套导出。

    items 为 [(path, text), ...]，text 取的是页面上改过名之后的当前正文，
    所以"先批量改前缀、再生成交付件"这条路是通的——产物里的表名就是新表名。
    脚本里只有结构没有数据，所以「数据」Sheet 只有英文字段名那一行表头，
    给下游当可填模板用，不假装里面有数据。
    """
    from core.meta_export import export_tables

    tables, where = [], {}
    for path, text in items:
        if sink.cancelled:
            break
        base = os.path.basename(path)
        sink.note("解析 %s 的表定义…" % base)
        for t in parse_create_tables(text):
            name = t["table_name"]
            if name in where:
                sink.log("! [WARN] 跳过 | `%s`：%s 里也定义了这张表，只按 %s 那一处导出"
                         % (name, base, where[name]))
                continue
            where[name] = base
            for note in t["notes"]:
                sink.log("! [WARN] 说明 | `%s`：%s" % (name, note))
            if not t["columns"]:
                continue        # 为什么不可导出，上面那条说明已经讲了
            tables.append(t)
    if not tables:
        sink.log("! [WARN] 没有可导出的表：这些脚本里解析不出带列定义的 CREATE TABLE")
        return {"dir": None, "written": [], "total": 0, "cancelled": sink.cancelled}
    if sink.cancelled:
        return {"dir": None, "written": [], "total": len(tables), "cancelled": True}
    target, written = export_tables(tables, out_root, with_meta, sink)
    return {"dir": target, "written": written, "total": len(tables),
            "cancelled": sink.cancelled}


# ---------------------------------------------------------------------------
# 后台工作线程：避免导入时界面卡死
# ---------------------------------------------------------------------------
class ImportWorker(QThread):
    log = Signal(str)
    log_error = Signal(str)             # 失败日志，接收端整条标红
    progress = Signal(int, int, str)    # done, total, 当前项：驱动模态进度框
    table_progress = Signal(int, int)   # current, total
    table_done = Signal(dict)           # 单表结果
    finished_all = Signal(list)         # 所有表结果

    def __init__(self, cfg, tables, drop_if_exists):
        super().__init__()
        self.cfg = cfg
        self.tables = tables
        self.drop_if_exists = drop_if_exists
        self._abort = False

    def abort(self):
        self._abort = True

    def run(self):
        results = []
        # 计项口径：每张表 1 项 + 每个数据行 1 项，几十万行的表也能看到连续推进
        rep = ProgressSink(
            on_progress=self.progress.emit, on_log=self.log.emit,
            on_log_error=self.log_error.emit, is_cancelled=lambda: self._abort)
        rep.set_total(len(self.tables) + sum(len(t["df"]) for t in self.tables))
        target = (f"{self.cfg.get('database')}@{self.cfg['host']}:{self.cfg.get('port')}"
                  if self.cfg.get("database")
                  else f"{self.cfg['host']}:{self.cfg.get('port')}")
        try:
            conn = connect(self.cfg)
        except Exception as e:
            rep.log_error(
                f"[连接失败] 无法连接到 {target}（用户 {self.cfg.get('user')}）\n"
                + indent_detail(describe_db_error(e)))
            self.finished_all.emit(results)
            return

        try:
            for idx, t in enumerate(self.tables, 1):
                if self._abort:
                    rep.log("[已中止] 剩余的表未执行")
                    break
                name = t["table_name"]
                total = len(t["df"])
                loaded = [0]                       # 已计入进度的行数（回调里要能改）
                rep.log(f"===== [{idx}/{len(self.tables)}] 处理表 `{name}`（{total} 行）=====")
                result = {"name": name, "file": t.get("filepath", ""), "ok": False, "rows": 0, "msg": ""}
                # 每张表一个事务：先开启，成功提交，失败回滚
                #（autocommit=False 下首个语句即开启事务；建表 DDL 受 MySQL
                #  隐式提交限制无法回滚，数据插入可整体回滚）
                conn.begin()
                try:
                    ddl = build_ddl(name, t["table_comment"], t["columns"], self.drop_if_exists)
                    rep.log(f"-- 建表 DDL --\n{ddl}")
                    create_table(conn, ddl)
                    rep.log(f"[建表成功] `{name}`")
                except Exception as e:
                    result["msg"] = f"建表失败: {e}"
                    rep.log_error(
                        f"[建表失败] 表 `{name}`（目标库 {target}）建表语句被 MySQL 拒绝\n"
                        + indent_detail(describe_db_error(e)) + "\n"
                        + "    失败的建表语句：\n" + format_statement(ddl) + "\n"
                        + "    " + ROLLBACK_NOTE)
                    results.append(result)
                    self.table_done.emit(result)
                    rep.advance(1 + total, f"跳过 `{name}`")
                    continue

                def cb(cur, tot):
                    delta = cur - loaded[0]
                    loaded[0] = cur
                    if delta:
                        rep.advance(delta, f"导入 `{name}`：{cur}/{tot} 行")
                    self.table_progress.emit(cur, tot)

                try:
                    rows = insert_data(conn, name, t["columns"], t["df"],
                                       batch=500, progress_cb=cb,
                                       warn_cb=lambda m: rep.log(m))
                    result["ok"] = True
                    result["rows"] = rows
                    result["msg"] = f"导入 {rows} 行"
                    rep.log(f"[导入完成] `{name}` 共 {rows} 行")
                except Exception as e:
                    result["msg"] = f"导入失败: {e}"
                    conn.rollback()
                    rep.log_error(
                        f"[导入失败] 表 `{name}`（目标库 {target}）共 {total} 行数据未能写入，"
                        f"本表事务已回滚\n"
                        + indent_detail(describe_db_error(e)) + "\n"
                        + "    " + ROLLBACK_NOTE)
                results.append(result)
                self.table_done.emit(result)
                # 补齐本表剩余计数（建表 1 项 + 未上报的行），保证进度条总数落准
                rep.advance(1 + max(0, total - loaded[0]), f"完成 `{name}`")
        finally:
            try:
                conn.close()
            except Exception:
                pass
            self.finished_all.emit(results)


# ---------------------------------------------------------------------------
# 拖拽接收区
# ---------------------------------------------------------------------------
def meta_export_task(tables, out_root, with_meta, sink):
    """生成「标准/简易 Excel 文件」：写盘与逐行取值都在这里，主线程只弹进度框。

    一张表一个文件，按文件计项；写失败只记错误不中断其余，
    与导入那条路同一口径（半途炸掉却把已经写好的目录报成失败，用户就白等一趟）。
    """
    from core.meta_export import export_tables

    target, written = export_tables(tables, out_root, with_meta, sink)
    return {"dir": target, "written": written, "total": len(tables),
            "cancelled": sink.cancelled}


class DropArea(QWidget):
    files_dropped = Signal(list)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setAcceptDrops(True)
        lay = QVBoxLayout(self)
        self.label = QLabel("将 Excel 文件或 ZIP 压缩包拖到此处\n（支持 .xlsx / .xls / .zip，可多选）")
        self.label.setAlignment(Qt.AlignCenter)
        self.label.setFont(QFont("", 12))
        lay.addWidget(self.label)
        self.setMinimumHeight(80)
        self.setStyleSheet(
            "DropArea { border: 2px dashed #888; border-radius: 8px; background: #f7f9fc; }"
            "DropArea:hover { border-color: #2f6feb; background: #eef4ff; }"
        )

    def dragEnterEvent(self, e: QDragEnterEvent):
        if e.mimeData().hasUrls():
            e.acceptProposedAction()

    def dropEvent(self, e: QDropEvent):
        urls = [u.toLocalFile() for u in e.mimeData().urls()
                if QFileInfo(u.toLocalFile()).isFile()]
        if urls:
            self.files_dropped.emit(urls)


# ---------------------------------------------------------------------------
# 主窗口
# ---------------------------------------------------------------------------
class MainWindow(QWidget):
    def __init__(self):
        super().__init__()
        self.setWindowTitle(title_text())     # 标题带版本号，不点开对话框也能看见
        fit_client(self, 1100, 780)
        self.tables = []            # 解析后的表定义列表
        self.worker = None
        self.sql_files = []         # SQL 脚本改名入库页：[{"path","text","modified","rename_map"}]
        self._zip_tmp = None        # ZIP 展开用的会话级临时目录，惰性创建
        self._zip_origin = {}       # 解压出的路径 → "包名!包内路径"，供文件列表 tooltip
        self.task_dialog = None     # 当前模态进度框，None 表示没有任务在跑
        self.task_worker = None     # 线程结束前必须一直持有引用，否则 QThread 被提前回收
        self._task_result = None
        self._task_on_done = None
        self._task_log_view = None
        self._build_ui()

    # -------------------- UI --------------------
    def _build_ui(self):
        root = QVBoxLayout(self)

        # 0. 标题栏正下方一条极窄的工具行：Qt 没法往 Windows 原生标题栏里塞按钮，
        #    所以「?」放在最贴近标题栏的右上角，位置与观感都最接近用户要的效果
        head = QHBoxLayout()
        head.setContentsMargins(0, 0, 0, 0)
        head.setSpacing(0)
        head.addStretch(1)
        self.btn_help = QPushButton("?")
        font_help = QFont()
        font_help.setBold(True)
        self.btn_help.setFont(font_help)
        self.btn_help.setFixedSize(26, 22)
        self.btn_help.setCursor(Qt.PointingHandCursor)
        self.btn_help.setToolTip("使用说明（按 F1 也可打开）")
        self.btn_help.clicked.connect(self.on_help)
        self.btn_about = QPushButton("i")
        self.btn_about.setFont(font_help)
        self.btn_about.setFixedSize(26, 22)
        self.btn_about.setCursor(Qt.PointingHandCursor)
        self.btn_about.setToolTip("关于：版本、提交号与构建日期")
        self.btn_about.clicked.connect(self.on_about)
        head.addWidget(self.btn_about)
        head.addWidget(self.btn_help)
        root.addLayout(head)

        # 1. 连接配置（公共区域，常驻在功能页签上方）
        conn_box = QGroupBox("MySQL 连接信息")
        f = QFormLayout(conn_box)
        self.ed_host = QLineEdit("127.0.0.1")
        self.ed_port = QSpinBox(); self.ed_port.setRange(1, 65535); self.ed_port.setValue(3306)
        self.ed_user = QLineEdit("root")
        self.ed_pass = QLineEdit(); self.ed_pass.setEchoMode(QLineEdit.Password)
        self.cb_db = QComboBox(); self.cb_db.setEnabled(False)
        self.btn_load_db = QPushButton("连接并加载库")
        self.btn_load_db.clicked.connect(self.on_load_databases)
        h = QHBoxLayout()
        h.addWidget(self.cb_db, 1)
        h.addWidget(self.btn_load_db)
        f.addRow("主机：", self.ed_host)
        f.addRow("端口：", self.ed_port)
        f.addRow("用户名：", self.ed_user)
        f.addRow("密码：", self.ed_pass)
        f.addRow("目标库：", h)
        root.addWidget(conn_box)

        # 2. 功能页签：Excel 导入 / SQL 脚本改名入库 / SQL 执行器（位于数据库信息下方）
        self.main_tabs = QTabWidget()
        excel_page = QWidget()
        excel_lay = QVBoxLayout(excel_page)

        # 2.0 当前目标库状态条：与「SQL 脚本改名入库」页顶部同源，随目标库选择联动刷新
        self.lbl_imp_db = QLabel()
        self.lbl_imp_db.setTextInteractionFlags(Qt.TextSelectableByMouse)
        excel_lay.addWidget(self.lbl_imp_db)

        # 2.1 文件拖拽区
        self.drop = DropArea()
        self.drop.files_dropped.connect(self.on_files_dropped)

        # 3. 文件列表
        file_box = QGroupBox("待导入文件（双击表名/字段可编辑）")
        fl = QVBoxLayout(file_box)
        self.file_table = QTableWidget(0, 6)
        self.file_table.setHorizontalHeaderLabels(
            ["文件", "表名", "表注释", "字段数", "数据行数", "主键"])
        # 列宽可自由拖动调节；最后一列自动填满剩余空间
        header = self.file_table.horizontalHeader()
        header.setSectionResizeMode(QHeaderView.Interactive)
        header.setStretchLastSection(True)
        self.file_table.setColumnWidth(0, 320)
        self.file_table.setColumnWidth(1, 200)
        self.file_table.setColumnWidth(2, 280)
        self.file_table.setColumnWidth(3, 80)
        self.file_table.setColumnWidth(4, 90)
        self.file_table.setColumnWidth(5, 160)
        self.file_table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        # 组框标题写着"双击表名/字段可编辑"，就得真的接上：
        # 双击哪一行就只弹那一张表（Swing 那条线一直是这个口径）
        self.file_table.cellDoubleClicked.connect(self.on_edit_structure_row)
        fl.addWidget(self.file_table)

        # 表名前缀（批量应用到全部表）
        prefix_row = QHBoxLayout()
        prefix_row.addWidget(QLabel("表名前缀："))
        self.ed_prefix = QLineEdit()
        self.ed_prefix.setPlaceholderText(
            "可选，如 dim_（新添加的文件自动带上，留空点按钮可移除）；"
            "填 change 再点「替换首个前缀」则换掉已有首段")
        self.btn_apply_prefix = QPushButton("批量应用前缀")
        self.btn_apply_prefix.clicked.connect(self.on_apply_prefix)
        self.btn_replace_prefix = QPushButton("替换首个前缀")
        self.btn_replace_prefix.setToolTip(
            "把表名第一个下划线之前的部分换成上面填的内容：\n"
            "ods_t_order → change_t_order、adb_table2 → change_table2；\n"
            "表名里没有下划线的（如 users）不动，日志会列出。末尾下划线不用写")
        self.btn_replace_prefix.clicked.connect(self.on_replace_prefix)
        prefix_row.addWidget(self.ed_prefix, 1)
        prefix_row.addWidget(self.btn_apply_prefix)
        prefix_row.addWidget(self.btn_replace_prefix)
        # 命名开关放在表名治理行：它和前缀一样是"名字怎么起"的治理项。
        # 不能进底部操作条——那条的最小宽度已经贴着 768，再塞一颗复选框
        # 会被 U7 的"整窗落在可用区内"当场抓住（实测顶到 922）。
        # 两颗复选框竖着摞，横向只占一颗的宽度，免得把这一行也顶宽
        name_switches = QVBoxLayout()
        # 两颗都默认勾上：新加的文件直接按「首字母 + 大写」起名。
        # 在连信号之前设好，免得启动时凭空打一行"切换"日志
        self.chk_initials = QCheckBox("表名与字段名取拼音首字母")
        self.chk_initials.setChecked(True)
        self.chk_initials.setToolTip(
            "由中文转写的表名与字段名用拼音首字母（订单金额 → ddje、\n"
            "水库基础信息 → skjcxx）；默认已勾选，取消则用全拼（ding_dan_jin_e）。\n"
            "文件名括号里显式写下的英文表名不受影响；对之后添加的文件生效。")
        self.chk_initials.toggled.connect(self.on_initials_toggled)
        name_switches.addWidget(self.chk_initials)
        self.chk_upper = QCheckBox("表名与字段名转大写")
        self.chk_upper.setChecked(True)
        self.chk_upper.setToolTip(
            "由中文或文件名转写出来的表名与字段名全部大写\n"
            "（ding_dan_jin_e → DING_DAN_JIN_E、水库基础信息 → SHUI_KU_JI_CHU_XIN_XI）；\n"
            "默认已勾选，取消则用小写。文件名括号里写下的英文表名同样转大写；\n"
            "结构定义 Sheet 里人工写的字段名与手动改过的表名前缀原样保留；\n"
            "对之后添加的文件生效（表名前缀按输入原样拼接，不跟着转）。")
        self.chk_upper.toggled.connect(self.on_upper_toggled)
        name_switches.addWidget(self.chk_upper)
        prefix_row.addLayout(name_switches)
        fl.addLayout(prefix_row)

        btns = QHBoxLayout()
        self.btn_add = QPushButton("添加文件")
        self.btn_add.clicked.connect(self.on_add_files)
        self.btn_clear = QPushButton("清空列表")
        self.btn_clear.clicked.connect(self.on_clear)
        self.btn_preview = QPushButton("生成表结构预览")
        self.btn_preview.clicked.connect(self.on_preview_structure)
        self.btn_ddl = QPushButton("预览 DDL")
        self.btn_ddl.clicked.connect(self.on_preview_ddl)
        btns.addWidget(self.btn_add)
        btns.addWidget(self.btn_clear)
        btns.addWidget(self.btn_preview)
        btns.addWidget(self.btn_ddl)
        fl.addLayout(btns)

        # 4. 日志
        log_row = QHBoxLayout()
        log_row.addStretch(1)
        self.btn_clear_log = QPushButton("清除日志")
        self.btn_clear_log.clicked.connect(self.on_clear_log)
        log_row.addWidget(self.btn_clear_log)
        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        self.log.setMaximumBlockCount(2000)
        log_panel = QWidget()
        lp = QVBoxLayout(log_panel)
        lp.setContentsMargins(0, 0, 0, 0)
        lp.addLayout(log_row)
        lp.addWidget(self.log, 1)

        # 5. 三段可上下拖拽调整高度；提示条与操作条是固定件，留在分隔条外头
        self.excel_split = QSplitter(Qt.Vertical)
        self.excel_split.setChildrenCollapsible(False)     # 不允许把某段拖到 0 高
        self.excel_split.setHandleWidth(6)
        self.excel_split.addWidget(self.drop)
        self.excel_split.addWidget(file_box)
        self.excel_split.addWidget(log_panel)
        self.excel_split.setStretchFactor(0, 0)
        self.excel_split.setStretchFactor(1, 2)
        self.excel_split.setStretchFactor(2, 3)
        self.excel_split.setSizes([130, 250, 290])
        excel_lay.addWidget(self.excel_split, 1)

        # 6. 操作（贴在窗口底部的动作条）
        op = QHBoxLayout()
        self.chk_drop = QCheckBox("覆盖已存在表（先 DROP 再建）")
        self.btn_import = QPushButton("开始导入")
        self.btn_import.setEnabled(False)
        self.btn_import.clicked.connect(self.on_start_import)
        self.btn_stop = QPushButton("停止")
        self.btn_stop.setEnabled(False)
        self.btn_stop.clicked.connect(self.on_stop)
        op.addWidget(self.chk_drop)
        op.addStretch(1)
        op.addWidget(self.btn_stop)
        op.addWidget(self.btn_import)
        # 两个生成按钮排在「开始导入」右侧：它们和导入用的是同一份已解析表定义，
        # 放在同一排才看得出"这是同一件事的另一种出口"，而不是另一功能
        self.btn_gen_std = QPushButton("生成标准excel文件")
        self.btn_gen_std.setToolTip(
            "在程序同级建 meta_data_file_年月日 目录，每张表一个 xlsx：\n"
            "Sheet1「元数据」是建表用的字段定义，Sheet2「数据」是数据行")
        self.btn_gen_std.setEnabled(False)
        self.btn_gen_std.clicked.connect(self.on_gen_standard)
        self.btn_gen_simple = QPushButton("生成简易excel文件")
        self.btn_gen_simple.setToolTip(
            "在程序同级建 simple_meta_data_file_年月日 目录，每张表一个 xlsx：\n"
            "只留「数据」一个 Sheet")
        self.btn_gen_simple.setEnabled(False)
        self.btn_gen_simple.clicked.connect(self.on_gen_simple)
        op.addWidget(self.btn_gen_std)
        op.addWidget(self.btn_gen_simple)
        excel_lay.addLayout(op)

        self.progress = QProgressBar()
        self.progress.setValue(0)
        excel_lay.addWidget(self.progress)

        self.main_tabs.addTab(excel_page, "Excel 导入")
        self.main_tabs.addTab(self._build_sql_page(), "SQL 脚本改名入库")
        self.main_tabs.addTab(self._build_sql_run_page(), "SQL 执行器")
        root.addWidget(self.main_tabs, 1)
        # 切页签 / 切换目标库 / 改主机端口时，刷新三页顶部各自的当前数据库信息条
        #（信息条文案含 host:port，改了主机端口必须同步，否则显示的是旧地址）
        self.main_tabs.currentChanged.connect(self._on_tab_changed)
        self.cb_db.currentIndexChanged.connect(self._on_db_changed)
        self.ed_host.textChanged.connect(self._refresh_db_labels)
        self.ed_port.valueChanged.connect(self._refresh_db_labels)
        self._refresh_db_labels()

    # -------------------- 当前目标库状态条（三个页签各一条，同源刷新） --------------------
    def _on_tab_changed(self, index):
        self._refresh_db_labels()

    def _on_db_changed(self, _index):
        self._refresh_db_labels()

    def _refresh_db_labels(self):
        cfg = self._conn_cfg()
        db = self.cb_db.currentData() or self.cb_db.currentText().strip()
        if db:
            head = f"当前目标库：{db}（{cfg['host']}:{cfg['port']}）——"
            self.lbl_imp_db.setText(head + "Excel 数据将导入到这个库")
            self.lbl_sql_db.setText(head + "SQL 脚本将执行到这个库")
            self.lbl_run_db.setText(head + "编辑区里的 SQL 将执行到这个库")
            style = "color: #1a7f37; font-weight: bold;"
        else:
            self.lbl_imp_db.setText("⚠ " + NO_DB_HINT)
            self.lbl_sql_db.setText("⚠ " + NO_DB_HINT)
            self.lbl_run_db.setText("⚠ " + NO_DB_HINT)
            style = "color: #c62828; font-weight: bold;"
        self.lbl_imp_db.setStyleSheet(style)
        self.lbl_sql_db.setStyleSheet(style)
        self.lbl_run_db.setStyleSheet(style)

    # -------------------- 使用说明 / 关于 --------------------
    def on_help(self):
        HelpDialog(self).exec()

    def on_about(self):
        AboutDialog(self).exec()

    def keyPressEvent(self, e):
        if e.key() == Qt.Key_F1:
            self.on_help()
            return
        super().keyPressEvent(e)

    # -------------------- 长任务：子线程干活 + 模态进度框 --------------------
    def _run_modal(self, title, work, args=(), on_done=None, log_view=None):
        """统一入口：把纯工作函数 work(sink, *args) 放到子线程跑，同时弹模态进度框。

        进度和日志靠信号排队回主线程，所以框始终能刷新、能取消；结果由 on_done
        在 exec() 返回后处理，避免在模态框里再套一层弹窗。
        MODAL_PROGRESS=False（离屏用例）时不起线程、不弹框，原地跑完再回调，
        两条路径共用同一个 work 和同一个 sink，测试即线上逻辑。
        """
        if self.task_dialog is not None:
            return                                   # 已有任务在跑（模态框本已挡住交互）
        self._task_log_view = log_view if log_view is not None else self.log
        self._task_on_done = on_done
        self._task_result = None
        self._sync_gen_buttons()
        if not MODAL_PROGRESS:
            sink = ProgressSink(on_log=self._on_task_log, on_log_error=self._on_task_log_error)
            try:
                self._task_result = work(*args, sink)
            except Exception as e:
                self._on_task_failed(describe_db_error(e))
            self._finish_task()
            return
        dlg = ProgressDialog(title, self)
        worker = ProgressWorker(work, args)
        self.task_dialog, self.task_worker = dlg, worker
        worker.progress.connect(dlg.set_progress)
        worker.log.connect(self._on_task_log)
        worker.log_error.connect(self._on_task_log_error)
        worker.failed.connect(self._on_task_failed)
        worker.completed.connect(self._on_task_completed)
        worker.finished.connect(self._on_task_thread_done)
        dlg.btn_cancel.clicked.connect(worker.cancel)
        worker.start()
        dlg.exec()
        self._finish_task()

    def _on_task_log(self, text):
        view = self._task_log_view or self.log
        view.appendPlainText(text)

    def _on_task_log_error(self, text):
        append_log(self._task_log_view or self.log, text, error=True)

    def _on_task_completed(self, result):
        self._task_result = result
        if self.task_dialog is not None:
            self.task_dialog.finish()

    def _on_task_thread_done(self):
        # 线程真正退出后才放引用；completed 可能更早（run 的最后一句就是发信号）
        self.task_worker = None

    def _on_task_failed(self, msg):
        if self.task_dialog is not None:
            self.task_dialog.finish()
        self._task_result = None
        append_log(self._task_log_view or self.log,
                   f"[任务异常] {msg}\n    本次操作未完成，请查看日志后重试。", error=True)
        popup_box(self, "任务异常", f"{msg}\n本次操作未完成，详见日志。", "critical")

    def _sync_gen_buttons(self):
        """两个生成按钮只在"有表可导、且没有别的任务在跑"时可点。

        判据取自同一处而不是各写各的：任务跑着的时候表列表正被工作线程读，
        这时候再生成一份就是两个任务共用同一份数据互相踩。
        """
        on = bool(self.tables) and self.task_dialog is None
        self.btn_gen_std.setEnabled(on)
        self.btn_gen_simple.setEnabled(on)
        # 这一页的判据是"有没有脚本"，不是"有没有解析出表"：
        # 解析在点下去之后才做，解析不出东西时日志会给话，按钮不该一直灰着让人以为坏了
        sql_on = bool(self.sql_files) and self.task_dialog is None
        self.btn_sql_gen_std.setEnabled(sql_on)
        self.btn_sql_gen_simple.setEnabled(sql_on)

    def _finish_task(self):
        """exec() 退出后收尾：把结果交给回调（工作函数返回 None 视为没做成，不回调）。"""
        self.task_dialog = None
        cb, self._task_on_done = self._task_on_done, None
        result = self._task_result
        self._task_result = None
        if result is not None and cb:
            cb(result)
        self._sync_gen_buttons()

    # -------------------- 槽函数 --------------------
    def on_clear_log(self):
        self.log.clear()

    # -------------------- SQL 脚本改名入库页 --------------------
    def _build_sql_page(self):
        page = QWidget()
        lay = QVBoxLayout(page)

        # 当前目标库状态条：跟随上方公共连接区的库选择联动刷新
        self.lbl_sql_db = QLabel()
        self.lbl_sql_db.setTextInteractionFlags(Qt.TextSelectableByMouse)
        lay.addWidget(self.lbl_sql_db)

        btn_row = QHBoxLayout()
        self.btn_sql_add = QPushButton("添加 SQL 文件")
        self.btn_sql_add.clicked.connect(self.on_sql_add_files)
        self.btn_sql_remove = QPushButton("移除选中文件")
        self.btn_sql_remove.clicked.connect(self.on_sql_remove_selected)
        btn_row.addWidget(self.btn_sql_add)
        btn_row.addWidget(self.btn_sql_remove)
        btn_row.addStretch(1)
        lay.addLayout(btn_row)

        self.sql_table = QTableWidget(0, 4)
        self.sql_table.setHorizontalHeaderLabels(
            ["文件", "原表名", "CREATE 出现次数", "当前表名"])
        header = self.sql_table.horizontalHeader()
        header.setSectionResizeMode(QHeaderView.Interactive)
        header.setStretchLastSection(True)
        self.sql_table.setColumnWidth(0, 300)
        self.sql_table.setColumnWidth(1, 260)
        self.sql_table.setColumnWidth(2, 130)
        self.sql_table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.sql_table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        # 表格与下面的两行操作条作为一个整体参与高度缩放，先不挂到页面布局上

        prefix_row = QHBoxLayout()
        prefix_row.addWidget(QLabel("表名前缀："))
        self.ed_sql_prefix = QLineEdit()
        self.ed_sql_prefix.setPlaceholderText(
            "如 dim_（加前缀，已带该前缀的表自动跳过）；填 change 再点"
            "「替换首个前缀」则把 ods_t_order 换成 change_t_order")
        self.btn_sql_prefix = QPushButton("批量应用前缀")
        self.btn_sql_prefix.clicked.connect(self.on_sql_apply_prefix)
        self.btn_sql_replace = QPushButton("替换首个前缀")
        self.btn_sql_replace.setToolTip(
            "把脚本里每个表名第一个下划线之前的部分换成上面填的内容，\n"
            "并同步改写 CREATE/DROP/INSERT/ALTER/TRUNCATE 中的引用；\n"
            "表名里没有下划线的不动。末尾下划线不用写")
        self.btn_sql_replace.clicked.connect(self.on_sql_replace_prefix)
        prefix_row.addWidget(self.ed_sql_prefix, 1)
        prefix_row.addWidget(self.btn_sql_prefix)
        prefix_row.addWidget(self.btn_sql_replace)

        act_row = QHBoxLayout()
        self.btn_sql_save = QPushButton("保存修改后脚本")
        self.btn_sql_save.clicked.connect(self.on_sql_save_modified)
        self.btn_sql_run = QPushButton("执行脚本到当前库")
        self.btn_sql_run.clicked.connect(self.on_sql_execute)
        # 两颗生成按钮排在「执行脚本到当前库」右侧：与 Excel 导入页那两颗同一排同一口径，
        # 数据源换成了脚本里的 CREATE TABLE，产物形状不变
        self.btn_sql_gen_std = QPushButton("生成标准excel文件")
        self.btn_sql_gen_std.setToolTip(
            "在程序同级建 meta_data_file_年月日 目录，每张表一个 xlsx：\n"
            "Sheet1「元数据」是脚本里解析出的字段定义，Sheet2「数据」只有表头\n"
            "（脚本里不含数据行，这一张是给下游填数据的模板）")
        self.btn_sql_gen_std.setEnabled(False)
        self.btn_sql_gen_std.clicked.connect(lambda: self.on_sql_gen_excel("标准", True))
        self.btn_sql_gen_simple = QPushButton("生成简易excel文件")
        self.btn_sql_gen_simple.setToolTip(
            "在程序同级建 simple_meta_data_file_年月日 目录，每张表一个 xlsx：\n"
            "只留「数据」一个 Sheet，里面只有脚本里那些字段的英文名表头")
        self.btn_sql_gen_simple.setEnabled(False)
        self.btn_sql_gen_simple.clicked.connect(lambda: self.on_sql_gen_excel("简易", False))
        act_row.addWidget(self.btn_sql_save)
        act_row.addWidget(self.btn_sql_run)
        act_row.addWidget(self.btn_sql_gen_std)
        act_row.addWidget(self.btn_sql_gen_simple)
        act_row.addStretch(1)
        self.btn_sql_clear_log = QPushButton("清除日志")
        self.btn_sql_clear_log.clicked.connect(self.on_sql_clear_log)
        act_row.addWidget(self.btn_sql_clear_log)

        self.sql_log = QPlainTextEdit()
        self.sql_log.setReadOnly(True)
        self.sql_log.setMaximumBlockCount(2000)

        # 上半区 = 待处理文件表格 + 它自己的前缀行与操作行；下半区 = 日志。
        # 两者之间一条可拖拽分隔条：一边变高另一边就变窄，做法与 Excel 导入页一致。
        sql_upper = QWidget()
        up_lay = QVBoxLayout(sql_upper)
        up_lay.setContentsMargins(0, 0, 0, 0)
        up_lay.addWidget(self.sql_table, 1)
        up_lay.addLayout(prefix_row)
        up_lay.addLayout(act_row)

        self.sql_split = QSplitter(Qt.Vertical)
        self.sql_split.setChildrenCollapsible(False)     # 不允许把任一边拖到 0 高
        self.sql_split.setHandleWidth(6)
        self.sql_split.addWidget(sql_upper)
        self.sql_split.addWidget(self.sql_log)
        self.sql_split.setStretchFactor(0, 2)
        self.sql_split.setStretchFactor(1, 3)
        self.sql_split.setSizes([260, 290])
        lay.addWidget(self.sql_split, 1)
        return page

    def _refresh_sql_table(self):
        rows = []
        for s in self.sql_files:
            base = os.path.basename(s["path"])
            rev = {v: k for k, v in s["rename_map"].items()}
            # 表名清单在读取/改写时就已算好，几十万行的脚本不再回扫一遍
            for t in s.get("tabs") or extract_create_tables(s["text"]):
                rows.append((base, rev.get(t["name"], t["name"]),
                             str(t["count"]), t["name"], s["path"]))
        self.sql_table.setRowCount(len(rows))
        for i, (base, orig, cnt, cur, path) in enumerate(rows):
            items = [QTableWidgetItem(base), QTableWidgetItem(orig),
                     QTableWidgetItem(cnt), QTableWidgetItem(cur)]
            items[0].setData(Qt.UserRole, path)
            items[0].setToolTip(path)
            for j, it in enumerate(items):
                self.sql_table.setItem(i, j, it)

    def on_sql_add_files(self):
        files, _ = QFileDialog.getOpenFileNames(
            self, "选择 SQL 脚本", "", "SQL 脚本 (*.sql);;所有文件 (*)")
        if not files:
            return
        existing = {s["path"] for s in self.sql_files}
        todo = []
        for f in files:
            if f in existing:
                self.sql_log.appendPlainText(f"[跳过] 已在列表：{os.path.basename(f)}")
                continue
            todo.append(f)
        if not todo:
            self._refresh_sql_table()
            return
        self._run_modal("正在读取 SQL 脚本…", sql_load_task, (todo,),
                        on_done=self._finish_sql_load, log_view=self.sql_log)

    def _finish_sql_load(self, res):
        self.sql_files.extend(res["loaded"])
        self._refresh_sql_table()

    def on_sql_remove_selected(self):
        rows = sorted({i.row() for i in self.sql_table.selectedIndexes()}, reverse=True)
        paths = set()
        for r in rows:
            it = self.sql_table.item(r, 0)
            if it:
                paths.add(it.data(Qt.UserRole))
        if not paths:
            return
        self.sql_files = [s for s in self.sql_files if s["path"] not in paths]
        self._refresh_sql_table()
        self.sql_log.appendPlainText(f"[移除] {len(paths)} 个脚本文件")

    def _warn(self, text):
        """拦下一次点击：日志与弹窗双写同一句话。

        只弹窗不写日志的话，用户回头查日志找不到"刚才那下为什么没动"；
        Swing 那条线的 warn() 一直是两处都写，两条线要同一口径。
        """
        append_log(self.log, "已拦下：" + text)
        popup_box(self, "提示", text, "warning")

    def on_sql_apply_prefix(self):
        prefix = self.ed_sql_prefix.text().strip()
        if not prefix:
            self._warn("请先输入表名前缀（如 dim_）。")
            return
        items = [(s["path"], s["text"], dict(s["rename_map"])) for s in self.sql_files]
        self._run_modal("正在改写脚本中的表名…", sql_prefix_task, (items, prefix),
                        on_done=self._finish_sql_prefix, log_view=self.sql_log)

    def _finish_sql_prefix(self, res):
        for s in self.sql_files:
            upd = res["updates"].get(s["path"])
            if not upd:
                continue
            s["text"] = upd["text"]
            s["tabs"] = upd["tabs"]
            s["modified"] = True
            s["rename_map"].update(upd["rename_map"])
        self._refresh_sql_table()

    def on_sql_replace_prefix(self):
        head, why = self._clean_head(self.ed_sql_prefix.text())
        if head is None:
            self._warn(why)
            return
        items = [(r["path"], r["text"], dict(r["rename_map"])) for r in self.sql_files]
        self._run_modal("正在替换脚本中的表名前缀…", sql_replace_prefix_task, (items, head),
                        on_done=self._finish_sql_prefix, log_view=self.sql_log)

    def on_sql_save_modified(self):
        items = [(s["path"], s["text"]) for s in self.sql_files if s["modified"]]
        if not items:
            self.sql_log.appendPlainText("[保存] 没有修改过的脚本文件")
            return
        self._run_modal("正在保存修改后的脚本…", sql_save_task, (items,),
                        on_done=self._finish_sql_save, log_view=self.sql_log)

    def _finish_sql_save(self, res):
        done = {p for p, _ in res["saved"]}
        for s in self.sql_files:
            if s["path"] in done:
                s["modified"] = False

    def on_sql_execute(self):
        cfg = self._conn_cfg()
        db = cfg["database"]
        if not db:
            self._warn(NO_DB_HINT)
            return
        if not self.sql_files:
            self._warn("请先添加 SQL 脚本文件。")
            return
        self.sql_log.appendPlainText(
            f"===== 开始执行脚本（目标库 {db}，每个文件一个事务）=====")
        items = [(s["path"], s["text"]) for s in self.sql_files]
        self._run_modal("正在执行脚本到目标库…", sql_execute_task, (items, cfg),
                        on_done=self._finish_sql_execute, log_view=self.sql_log)

    def _finish_sql_execute(self, res):
        if res["conn_failed"]:
            return                      # 连不上库时已经报过详细原因，不再重复汇总
        note = "（已取消，剩余脚本未执行）" if res["cancelled"] else ""
        append_log(self.sql_log,
                   f"===== 执行完成：成功 {res['ok']} 个文件，失败 {res['fail']} 个文件"
                   f"{note} =====", error=bool(res["fail"]))
        popup_box(self, "执行完成",
                  f"成功 {res['ok']} 个文件，失败 {res['fail']} 个文件{note}。\n详见日志。")

    def on_sql_gen_excel(self, label, with_meta):
        """从脚本正文解析表定义再生成交付件；写盘在子线程，输出落在程序同级。"""
        if not self.sql_files:
            self._warn("请先添加 SQL 脚本文件。")
            return
        out_root = program_dir()
        append_log(self.sql_log,
                   f"===== 开始生成{label}excel文件（脚本 %d 个，输出根目录 {out_root}）====="
                   % len(self.sql_files))
        items = [(s["path"], s["text"]) for s in self.sql_files]
        self._run_modal("正在生成%sexcel文件…" % label, sql_meta_export_task,
                        (items, out_root, with_meta),
                        on_done=self._finish_sql_gen_excel, log_view=self.sql_log)

    def _finish_sql_gen_excel(self, res):
        if not res:
            return
        n = len(res["written"])
        note = "（已取消，剩余文件未生成）" if res["cancelled"] else ""
        target = res["dir"] or "（没建目录）"
        text = (f"已生成 {n} 个文件（共 {res['total']} 张表）。\n目录：\n{target}{note}"
                if n else f"没有生成任何文件，详见日志。{note}")
        append_log(self.sql_log,
                   f"===== 生成完成：{n}/{res['total']} 个文件，目录 {target} ====={note}",
                   error=(n == 0))
        popup_box(self, "生成结果", text)

    def on_sql_clear_log(self):
        self.sql_log.clear()

    # -------------------- SQL 执行器页 --------------------
    def _build_sql_run_page(self):
        """第三个页签。与「SQL 脚本改名入库」页的分工：那一页管"手上有一批 .sql 文件要入库"
        （挑文件、改表名、落盘副本），这一页管"临时问一句、顺手改点结构"，
        所以它不挑文件也不落盘，只吃编辑区里那段文本。
        """
        page = QWidget()
        lay = QVBoxLayout(page)

        # 当前目标库状态条：与前两页同源、同一次刷新（公共连接区改了库或改了主机端口都得跟着变）
        self.lbl_run_db = QLabel()
        self.lbl_run_db.setTextInteractionFlags(Qt.TextSelectableByMouse)
        lay.addWidget(self.lbl_run_db)

        # 提示单独占一行，三颗按钮另起一行：QLabel 不折行，它的最小宽度就是整句的宽度，
        # 和按钮排在同一行会把整个窗口的最小宽度顶到 830 以上——小屏（1024x768 的现场机、
        # 还有 800 宽的离屏用例）上窗口放不下，U7 那条"客户区落在可用区内"就是抓这个的。
        self.sql_run_hint = QLabel("直接输入 SQL 执行：建库、建表、改结构、查元数据都行；"
                                   "一次可以写多条，用分号分隔")
        lay.addWidget(self.sql_run_hint)
        bar = QHBoxLayout()
        self.btn_sql_run_samples = QPushButton("示例 (?)")
        self.btn_sql_run_samples.setToolTip("打开示例清单：选中一条可复制到剪贴板或插到编辑区")
        self.btn_sql_run_samples.clicked.connect(self.on_sql_samples)
        self.btn_sql_run_exec = QPushButton("执行")
        self.btn_sql_run_exec.setToolTip("按分号切句逐条执行；全部成功才提交，中途出错整批回滚")
        self.btn_sql_run_exec.clicked.connect(self.on_sql_run)
        bar.addWidget(self.btn_sql_run_samples)
        bar.addWidget(self.btn_sql_run_exec)
        lay.addLayout(bar)

        self.sql_run_input = QPlainTextEdit(SQL_RUN_DEFAULT)
        self.sql_run_input.setFont(QFont("Consolas", 12))
        # 不折行：一句 SQL 折成三行之后，分号在哪、语句从哪儿开始都得数
        self.sql_run_input.setLineWrapMode(QPlainTextEdit.NoWrap)
        # 两颗清空各归各的pane：只清结果区时不必丢掉刚敲了一半的语句，反过来也一样
        left = QWidget()
        ll = QVBoxLayout(left)
        ll.setContentsMargins(0, 0, 0, 0)
        lh = QHBoxLayout()
        lh.addWidget(QLabel("SQL 文本"))
        lh.addStretch(1)
        self.btn_sql_run_clear_input = QPushButton("清空编辑区")
        self.btn_sql_run_clear_input.setToolTip("只清掉左边这段 SQL，右侧结果区不动")
        self.btn_sql_run_clear_input.clicked.connect(self.on_sql_run_clear_input)
        lh.addWidget(self.btn_sql_run_clear_input)
        ll.addLayout(lh)
        ll.addWidget(self.sql_run_input, 1)

        self.sql_run_log = QPlainTextEdit()
        self.sql_run_log.setReadOnly(True)
        # 结果集是按显示宽度排出来的表格，字体不是等宽就会错行：与编辑区同一支字体
        self.sql_run_log.setFont(QFont("Consolas", 12))
        self.sql_run_log.setMaximumBlockCount(2000)
        right = QWidget()
        rl = QVBoxLayout(right)
        rl.setContentsMargins(0, 0, 0, 0)
        rh = QHBoxLayout()
        rh.addWidget(QLabel("执行结果"))
        rh.addStretch(1)
        self.btn_sql_run_clear_log = QPushButton("清空结果区")
        self.btn_sql_run_clear_log.setToolTip("只清掉右边的执行结果，左边编辑区不动")
        self.btn_sql_run_clear_log.clicked.connect(self.on_sql_run_clear_result)
        rh.addWidget(self.btn_sql_run_clear_log)
        rl.addLayout(rh)
        rl.addWidget(self.sql_run_log, 1)

        self.sql_run_split = QSplitter(Qt.Horizontal)
        self.sql_run_split.setChildrenCollapsible(False)     # 不允许把任一边拖到 0 宽
        self.sql_run_split.setHandleWidth(6)
        self.sql_run_split.addWidget(left)
        self.sql_run_split.addWidget(right)
        self.sql_run_split.setStretchFactor(0, 45)
        self.sql_run_split.setStretchFactor(1, 55)
        self.sql_run_split.setSizes([450, 550])
        lay.addWidget(self.sql_run_split, 1)

        self.sql_run_samples = None                          # 示例对话框只在打开时才有
        return page

    def on_sql_run_clear_input(self):
        """只清编辑区：结果区是上一次的执行记录，动它得由用户自己点头。"""
        self.sql_run_input.clear()

    def on_sql_run_clear_result(self):
        """只清结果区：编辑区里可能正敲到一半，不该被一次"看腻了结果"带走。"""
        self.sql_run_log.clear()

    def on_sql_run(self):
        text = self.sql_run_input.toPlainText()
        if not text.strip():
            self._warn("没有可执行的 SQL：编辑区是空的（只写注释也不算）")
            return
        cfg = self._conn_cfg()
        # 结果、失败原因、回滚说明都已经逐行写在右侧结果区里，这里不再弹窗打断：
        # 这一页是给人来回试语句的，每试一次弹一次模态框会点不完
        self._run_modal("正在执行 SQL…", sql_run_task, (text, cfg),
                        log_view=self.sql_run_log)

    def on_sql_samples(self):
        if self.sql_run_samples is not None and self.sql_run_samples.isVisible():
            self.sql_run_samples.raise_()
            self.sql_run_samples.activateWindow()
            return
        self.sql_run_samples = SqlSamplesDialog(self.sql_run_input, self)
        self.sql_run_samples.show()

    def _conn_cfg(self) -> dict:
        return {
            "host": self.ed_host.text().strip(),
            "port": self.ed_port.value(),
            "user": self.ed_user.text().strip(),
            "password": self.ed_pass.text(),
            "database": self.cb_db.currentData() or self.cb_db.currentText().strip(),
        }

    def on_load_databases(self):
        try:
            cfg = self._conn_cfg()
            conn = connect(cfg)
            dbs = list_databases(conn)
            conn.close()
            self.cb_db.blockSignals(True)
            self.cb_db.clear()
            for d in dbs:
                self.cb_db.addItem(d, d)
            self.cb_db.blockSignals(False)
            self.cb_db.setEnabled(True)
            self._refresh_db_labels()
            self.log.appendPlainText(f"[已加载] 可用数据库：{', '.join(dbs)}")
        except Exception as e:
            # 弹窗与日志用同一套展开格式，否则弹窗里是 (2003, '...') 这种元组原文
            popup_box(self, "连接失败", describe_db_error(e), "critical")
            cfg = self._conn_cfg()
            append_log(self.log,
                       f"[连接失败] 无法连接到 "
                       f"{cfg['host']}:{cfg['port']}（用户 {cfg['user']}），库列表未加载\n"
                       + indent_detail(describe_db_error(e)), error=True)

    def on_initials_toggled(self, checked):
        """命名开关切换要留痕：之后看到 ddje 这样的字段名，得能对上是自己勾的。"""
        self.log.appendPlainText(
            "[提示] 命名规则切换为：%s（对之后添加的文件生效）"
            % ("拼音首字母" if checked else "全拼"))

    def on_upper_toggled(self, checked):
        """大小写开关同样留痕：列表里忽然一片大写，得能对上是自己勾的。"""
        self.log.appendPlainText(
            "[提示] 表名与字段名大小写切换为：%s（对之后添加的文件生效，"
            "结构定义 Sheet 里人工写的字段名不受影响）" % ("大写" if checked else "小写"))

    def on_add_files(self):
        files, _ = QFileDialog.getOpenFileNames(
            self, "选择 Excel 文件或 ZIP 压缩包", "",
            "Excel / ZIP (*.xlsx *.xls *.zip);;Excel 文件 (*.xlsx *.xls)"
            ";;ZIP 压缩包 (*.zip);;所有文件 (*)")
        if files:
            self._add_files(files)

    def on_files_dropped(self, files):
        accepted = [f for f in files if f.lower().endswith(IMPORT_EXTS)]
        if accepted:
            self._add_files(accepted)
        else:
            self.log.appendPlainText("[提示] 拖入的不是 Excel 或 ZIP 文件，已忽略。")

    def _add_files(self, files):
        """添加文件：展开压缩包 + 解析表结构全在子线程，主线程只弹模态进度框。"""
        has_zip = any(f.lower().endswith(".zip") for f in files)
        # 开关状态在主线程取好再进子线程：子线程不碰控件。
        # 用 partial 绑关键字，不塞进 args 元组——_run_modal 会把 sink 追加在末尾，
        # 塞进去就会把 sink 挤到 initials 形参上（H11 那次就是这么红的）
        self._run_modal(
            "正在解析文件…",
            functools.partial(parse_import_files,
                              initials=self.chk_initials.isChecked(),
                              upper=self.chk_upper.isChecked()),
            (files, {t.get("filepath") for t in self.tables},
             self._zip_dir() if has_zip else None, len(self._zip_origin)),
            on_done=self._finish_add_files)

    def _zip_dir(self):
        """会话级临时目录，窗口关闭时清理；导入失败的文件在会话内可重试。"""
        if self._zip_tmp is None:
            self._zip_tmp = tempfile.mkdtemp(prefix="e2m_gui_")
        return self._zip_tmp

    @staticmethod
    def _split_by_ext(paths):
        xlsx = sum(1 for p in paths if p.lower().endswith(".xlsx"))
        xls = sum(1 for p in paths if p.lower().endswith(".xls"))
        return xlsx, xls

    def _add_summary_text(self, added_loose, added_zip, zips_ok, failed, dups,
                          zips_bad, zips_empty, merged=0):
        """按「添加 N 项…，包含 x 项 xlsx、y 项 xls」拼弹窗正文，只输出实际用到的行。"""
        lines = []
        if added_loose:
            x, y = self._split_by_ext(added_loose)
            lines.append(f"添加 {len(added_loose)} 项文件，包含 {x} 项 xlsx 文件、{y} 项 xls 文件")
        if added_zip:
            x, y = self._split_by_ext(added_zip)
            lines.append(f"添加 {zips_ok} 项 zip 文件，包含 {x} 项 xlsx 文件、{y} 项 xls 文件")
        bad = []
        if merged:
            bad.append(f"{merged} 项含合并单元格已拒绝")
        if failed:
            bad.append(f"{failed} 项解析失败")
        if dups:
            bad.append(f"{dups} 项重复已跳过")
        if zips_bad:
            bad.append(f"{zips_bad} 个压缩包无法展开")
        if zips_empty:
            bad.append(f"{zips_empty} 个压缩包内没有 Excel")
        if bad:
            lines.append("另有 " + "、".join(bad) + "，详见日志")
        return "\n".join(lines)

    def _finish_add_files(self, res):
        """解析结果落到界面：子线程只算数据，列表、前缀、弹窗都在这里改。"""
        self._zip_origin.update(res["zip_origin"])
        self.tables.extend(res["tables"])
        if res["tables"]:
            prefix = self.ed_prefix.text().strip()
            if prefix:
                apply_table_prefix(self.tables, prefix)
            else:
                make_unique_names(self.tables)
            self._refresh_file_table()
            self.btn_import.setEnabled(True)
        text = self._add_summary_text(res["added_loose"], res["added_zip"], res["zips_ok"],
                                      res["failed"], res["dups"], res["zips_bad"],
                                      res["zips_empty"], res["merged"])
        if not text:
            return
        if res["cancelled"]:
            text = "已取消，只完成了部分解析\n" + text
        # 同一句话既进日志也进弹窗：日志可回溯，弹窗防走神漏看
        self.log.appendPlainText(f"[添加结果] {text}")
        popup_box(self, "添加结果", text)

    def _refresh_file_table(self):
        self.file_table.setRowCount(len(self.tables))
        for i, t in enumerate(self.tables):
            item = QTableWidgetItem(os.path.basename(t["filepath"]))
            # ZIP 展开出来的文件要能看出来自哪个包的哪个内部路径
            item.setToolTip(self._zip_origin.get(t["filepath"]) or t["filepath"])
            self.file_table.setItem(i, 0, item)
            self.file_table.setItem(i, 1, QTableWidgetItem(t["table_name"]))
            self.file_table.setItem(i, 2, QTableWidgetItem(t["table_comment"]))
            self.file_table.setItem(i, 3, QTableWidgetItem(str(len(t["columns"]))))
            self.file_table.setItem(i, 4, QTableWidgetItem(str(len(t["df"]))))
            pk = ", ".join(c["field"] for c in t["columns"] if c.get("primary_key")) or "无"
            self.file_table.setItem(i, 5, QTableWidgetItem(pk))

    def on_clear(self):
        self.tables.clear()
        self.file_table.setRowCount(0)
        self.btn_import.setEnabled(False)
        self._sync_gen_buttons()
        self.progress.setValue(0)      # 清空列表后不留上一次的进度残留

    def closeEvent(self, e):
        """退出时清掉 ZIP 展开出来的临时文件。"""
        if self._zip_tmp:
            shutil.rmtree(self._zip_tmp, ignore_errors=True)
        super().closeEvent(e)

    # ---- 批量表名前缀 ----
    @staticmethod
    def _clean_head(text):
        """把输入清成合法首段；不合法时返回 (None, 该提示什么)。"""
        head = clean_prefix(text)
        if not head:
            return None, "请先输入新的前缀（如 change）。"
        if not is_valid_prefix(head):
            return None, (f"前缀「{head}」含非法字符：只能用字母、数字、下划线；"
                          f"末尾的下划线不用写，原表名自带。")
        return head, ""

    def on_replace_prefix(self):
        if not self.tables:
            popup_box(self, "提示", NO_FILE_HINT)
            return
        head, why = self._clean_head(self.ed_prefix.text())
        if head is None:
            self._warn(why)
            return
        self._run_modal("正在替换表名前缀…", table_replace_task, (self.tables, head),
                        on_done=self._finish_replace_prefix)

    def _finish_replace_prefix(self, res):
        for r in res["renamed"]:
            self.log.appendPlainText(f"[换前缀] {r['old']} → {r['new']}")
        if res["skipped"]:
            self.log.appendPlainText(
                f"[跳过] 表名里没有下划线，未改动：{'、'.join(res['skipped'])}")
        self.log.appendPlainText(
            f"[换前缀完成] 共改 {len(res['renamed'])} 张表"
            + (f"，跳过 {len(res['skipped'])} 张" if res["skipped"] else ""))
        self._refresh_file_table()

    def on_apply_prefix(self):
        if not self.tables:
            popup_box(self, "提示", NO_FILE_HINT)
            return
        prefix = self.ed_prefix.text().strip()
        self._run_modal("正在批量应用表名前缀…", table_prefix_task, (self.tables, prefix),
                        on_done=self._finish_apply_prefix)

    def _finish_apply_prefix(self, res):
        self._refresh_file_table()
        if res["prefix"]:
            self.log.appendPlainText(f"[前缀] 已为全部表名批量添加前缀「{res['prefix']}」")
        else:
            self.log.appendPlainText("[前缀] 已移除前缀，恢复基础表名")

    # ---- 表结构预览（弹窗表格） ----
    def on_preview_structure(self):
        if not self.tables:
            popup_box(self, "提示", NO_FILE_HINT)
            return
        dlg = StructureDialog(self.tables, self)
        if dlg.exec():
            # 用户编辑后回写
            self.tables = dlg.get_tables()
            self._refresh_file_table()
            append_log(self.log, "[结构] 已按弹窗确认 %d 张表的字段定义" % len(self.tables))

    def on_edit_structure_row(self, row: int, _col: int = 0) -> None:
        """双击文件列表某一行：只弹那一张表的结构窗口。

        整列表一起弹会让人分不清改的是哪张表，Swing 那条线一直是按行弹的。
        """
        if not self.tables:
            popup_box(self, "提示", NO_FILE_HINT)
            return
        if not 0 <= row < len(self.tables):
            return
        t = self.tables[row]
        dlg = StructureDialog([t], self)
        if dlg.exec():
            self.tables[row] = dlg.get_tables()[0]
            self._refresh_file_table()
            append_log(self.log, "[结构] %s 已按弹窗修改" % t["table_name"])

    # ---- DDL 预览 ----
    def on_preview_ddl(self):
        if not self.tables:
            popup_box(self, "提示", NO_FILE_HINT)
            return
        dlg = DDLDialog(self.tables, self.chk_drop.isChecked(), self)
        dlg.exec()

    # ---- 生成标准 / 简易 excel 文件 ----
    def on_gen_standard(self):
        self._gen_excel("标准", True)

    def on_gen_simple(self):
        self._gen_excel("简易", False)

    def _gen_excel(self, label, with_meta):
        """写盘在子线程、进度框照弹，与导入同一个任务框架；输出落在程序同级。"""
        if not self.tables:
            self._warn(NO_FILE_HINT)
            return
        out_root = program_dir()
        title = ("正在生成标准excel文件…" if with_meta else "正在生成简易excel文件…")
        append_log(self.log, f"===== 开始生成{label}excel文件（输出根目录 {out_root}）=====")
        self._run_modal(title, meta_export_task, (list(self.tables), out_root, with_meta),
                        on_done=self._finish_meta_export, log_view=self.log)

    def _finish_meta_export(self, res):
        if not res:
            return
        n = len(res["written"])
        note = "（已取消，剩余文件未生成）" if res["cancelled"] else ""
        # 目录名要原样给出来：文件是落在程序同级、按日期新建的目录里，
        # 只说"生成完成"用户不知道该去哪儿找
        text = (f"已生成 {n} 个文件（共 {res['total']} 张表）。\n目录：\n{res['dir']}{note}"
                if n else f"没有生成任何文件，详见日志。{note}")
        append_log(self.log,
                   f"===== 生成完成：{n}/{res['total']} 个文件，目录 {res['dir']} ====={note}",
                   error=(n == 0))
        popup_box(self, "生成结果", text)

    # ---- 开始导入 ----
    def on_start_import(self):
        if not self.tables:
            return
        db = self._conn_cfg()["database"]
        if not db:
            self._warn(NO_DB_HINT)
            return
        self.btn_import.setEnabled(False)
        self.btn_stop.setEnabled(True)
        self.progress.setValue(0)
        self.worker = ImportWorker(self._conn_cfg(), self.tables, self.chk_drop.isChecked())
        self.worker.log.connect(self.log.appendPlainText)
        self.worker.log_error.connect(self._on_worker_error)
        self.worker.table_progress.connect(self._on_table_progress)
        self.worker.table_done.connect(self._on_table_done)
        self.worker.finished_all.connect(self._on_finished_all)
        if not MODAL_PROGRESS:
            self.worker.start()
            return
        # 导入是真正的长任务：模态框按「每张表 + 每行数据」计项，随时能看到推进
        dlg = ProgressDialog("正在导入到目标库…", self)
        dlg.btn_cancel.setText("停止（当前表完成后中止）")
        self.task_dialog = dlg
        self.worker.progress.connect(dlg.set_progress)
        dlg.btn_cancel.clicked.connect(self.worker.abort)
        self.worker.start()
        dlg.exec()

    def on_stop(self):
        if self.worker:
            self.worker.abort()
            self.log.appendPlainText("[正在中止...]")
        else:
            # 空点「停止」也要留痕：什么都不说会让人以为自己按错了，
            # 而 Swing 那条线同一句已经写在日志里，两边口径要一致
            self.log.appendPlainText("没有正在执行的任务")

    def _on_worker_error(self, msg):
        append_log(self.log, msg, error=True)

    def _on_table_progress(self, cur, tot):
        if tot > 0:
            self.progress.setValue(int(cur / tot * 100))

    def _on_table_done(self, result):
        pass

    def _on_finished_all(self, results):
        # 先收掉模态框，再打汇总弹窗，避免两个模态框叠在一起
        dlg, self.task_dialog = self.task_dialog, None
        if dlg is not None:
            dlg.finish()
        ok = sum(1 for r in results if r["ok"])
        fail = len(results) - ok
        append_log(self.log,
                   f"===== 全部完成：成功 {ok} 张表，失败 {fail} 张表 =====",
                   error=bool(fail))
        # 导入成功的文件从待导入列表移除，失败的保留便于调整后重试
        done_files = {r.get("file") for r in results if r["ok"]}
        removed = [t for t in self.tables if t.get("filepath") in done_files]
        kept = [t for t in self.tables if t.get("filepath") not in done_files]
        if removed:
            self.log.appendPlainText(
                "[清理] 已从待导入列表移除成功文件："
                + "、".join(os.path.basename(t["filepath"]) for t in removed))
        if fail and kept:
            self.log.appendPlainText(
                "[保留] 导入失败的文件仍在列表中，可调整后重试："
                + "、".join(os.path.basename(t["filepath"]) for t in kept))
        self.tables = kept
        self._refresh_file_table()
        self.btn_import.setEnabled(bool(self.tables))
        self.btn_stop.setEnabled(False)
        msg = f"成功 {ok} 张表，失败 {fail} 张表。"
        if removed:
            msg += f"\n已从待导入列表移除 {len(removed)} 个成功文件。"
        if fail:
            msg += "\n失败文件保留在列表中，可调整后重试。"
        popup_box(self, "导入完成", msg + "\n详见日志。")
        # 结果已经由弹窗和日志交代清楚，进度条回到初始的 0%：
        # 停在 100% 会让人分不清"这是上一批的尾巴，还是这一批的当前进度"
        self.progress.setValue(0)


# ---------------------------------------------------------------------------
# 表结构预览/编辑弹窗
# ---------------------------------------------------------------------------
class StructureDialog(QDialog):
    def __init__(self, tables, parent=None):
        super().__init__(parent)
        self.setWindowTitle("表结构预览（可编辑）")
        fit_client(self, 980, 640)
        self.tables = tables
        self.editors = []  # 每个 tab 一个 (table_dict, tablewidget)

        lay = QVBoxLayout(self)
        tip = QLabel("勾选一列或多列作为主键（多列即组合主键，主键约束至多一个）；"
                     "主键需全列非空且组合唯一，全部不勾选则不设主键")
        tip.setStyleSheet("color: #666;")
        lay.addWidget(tip)
        self.tabs = QTabWidget()
        for t in tables:
            tw = QTableWidget(len(t["columns"]), 5)
            tw.setHorizontalHeaderLabels(
                ["主键", "字段名(拼音/首字母)", "字段注释(中文表头)", "类型", "最大字节长度"])
            for i, c in enumerate(t["columns"]):
                cb = QCheckBox()
                cb.setChecked(bool(c.get("primary_key")))
                tw.setCellWidget(i, 0, cb)
                tw.setItem(i, 1, QTableWidgetItem(c["field"]))
                tw.setItem(i, 2, QTableWidgetItem(c["comment"]))
                tw.setItem(i, 3, QTableWidgetItem(c["type"]))
                tw.setItem(i, 4, QTableWidgetItem(str(c.get("max_len", ""))))
            tw.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeToContents)
            tw.horizontalHeader().setSectionResizeMode(1, QHeaderView.Stretch)
            tw.horizontalHeader().setSectionResizeMode(2, QHeaderView.Stretch)
            self.tabs.addTab(tw, t["table_name"])
            self.editors.append((t, tw))
        lay.addWidget(self.tabs)

        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btns.accepted.connect(self._on_accept)
        btns.rejected.connect(self.reject)
        lay.addWidget(btns)

    def _on_accept(self):
        # 先把全部编辑收集到副本，主键校验通过后再写回，避免无效主键污染数据
        staged = []
        for t, tw in self.editors:
            cols = []
            for i, c in enumerate(t["columns"]):
                nc = dict(c)
                item = tw.item(i, 1)
                if item and item.text().strip():
                    nc["field"] = item.text().strip()
                item = tw.item(i, 2)
                if item is not None:
                    nc["comment"] = item.text()
                item = tw.item(i, 3)
                if item and item.text().strip():
                    nc["type"] = item.text().strip()
                cb = tw.cellWidget(i, 0)
                nc["primary_key"] = bool(cb and cb.isChecked())
                cols.append(nc)
            staged.append((t, cols))

        problems = []
        for t, cols in staged:
            good, msg = validate_primary_key(t["df"], cols)
            if not good:
                problems.append(f"表「{t['table_name']}」：{msg}")
        if problems:
            popup_box(self, "主键设置无效",
                      "\n".join(problems) + "\n\n请调整主键勾选或字段类型后重试。",
                      "warning")
            return    # 校验不过不放行，保持弹窗打开让用户调整

        for t, cols in staged:
            t["columns"] = cols
        self.accept()

    def get_tables(self):
        return self.tables


# ---------------------------------------------------------------------------
# DDL 预览弹窗
# ---------------------------------------------------------------------------
class DDLDialog(QDialog):
    def __init__(self, tables, drop_if_exists, parent=None):
        super().__init__(parent)
        self.setWindowTitle("DDL 预览")
        fit_client(self, 900, 600)
        lay = QVBoxLayout(self)
        self.tabs = QTabWidget()
        for t in tables:
            ddl = build_ddl(t["table_name"], t["table_comment"], t["columns"], drop_if_exists)
            te = QPlainTextEdit(ddl)
            te.setReadOnly(True)
            te.setFont(QFont("Menlo"))
            self.tabs.addTab(te, t["table_name"])
        lay.addWidget(self.tabs)
        btns = QDialogButtonBox(QDialogButtonBox.Close)
        btns.rejected.connect(self.reject)
        btns.accepted.connect(self.accept)
        lay.addWidget(btns)


# ---------------------------------------------------------------------------
class SqlSamplesDialog(QDialog):
    """「示例 (?)」打开的那个对话框：左边挑，右边看全文，可复制也可插进编辑区。

    非模态：这一页的用法是照着清单改语句、执行、再看结果，
    模态框会把编辑区锁住，等于让人把 SQL 抄两遍。清单与语句正文都取自
    sql_samples.py（Java 那条线导出同一份表），两边不会各说各话。
    """

    def __init__(self, editor, parent=None):
        super().__init__(parent)
        self._editor = editor
        self.setModal(False)
        self.setWindowTitle("SQL 示例（%d 条）" % len(SQL_SAMPLES))
        fit_client(self, 900, 560)
        lay = QVBoxLayout(self)

        self.list = QListWidget()
        for group, desc, _sql in SQL_SAMPLES:
            self.list.addItem("【%s】%s" % (group, desc))
        self.preview = QPlainTextEdit(SQL_SAMPLES[0][2])
        self.preview.setReadOnly(True)
        self.preview.setFont(QFont("Consolas", 12))
        self.list.currentRowChanged.connect(self._on_pick)

        left = QWidget()
        ll = QVBoxLayout(left)
        ll.setContentsMargins(0, 0, 0, 0)
        ll.addWidget(QLabel("示例清单（按分组，选中看右半边的全文）"))
        ll.addWidget(self.list, 1)

        right = QWidget()
        rl = QVBoxLayout(right)
        rl.setContentsMargins(0, 0, 0, 0)
        rl.addWidget(QLabel("语句全文（可全选复制）"))
        rl.addWidget(self.preview, 1)
        btns = QHBoxLayout()
        self.btn_copy = QPushButton("复制到剪贴板")
        self.btn_copy.clicked.connect(self.on_copy)
        self.btn_insert = QPushButton("插入到编辑区")
        self.btn_insert.clicked.connect(self.on_insert)
        self.btn_close = QPushButton("关闭")
        self.btn_close.clicked.connect(self.hide)
        btns.addWidget(self.btn_copy)
        btns.addWidget(self.btn_insert)
        btns.addWidget(self.btn_close)
        rl.addLayout(btns)

        self.split = QSplitter(Qt.Horizontal)
        self.split.setChildrenCollapsible(False)
        self.split.setHandleWidth(6)
        self.split.addWidget(left)
        self.split.addWidget(right)
        self.split.setStretchFactor(0, 42)
        self.split.setStretchFactor(1, 58)
        self.split.setSizes([380, 520])
        lay.addWidget(self.split, 1)

        self.list.setCurrentRow(0)          # 打开就有一条被选中，右边不是空白

    def _on_pick(self, row):
        if 0 <= row < len(SQL_SAMPLES):
            self.preview.setPlainText(SQL_SAMPLES[row][2])

    def on_copy(self):
        QApplication.clipboard().setText(self.preview.toPlainText())
        # 标题就是回执：复制这种操作没有别的反馈，不写一句"已复制"没人知道点没点上
        self.setWindowTitle("SQL 示例（%d 条）· 已复制到剪贴板" % len(SQL_SAMPLES))

    def on_insert(self):
        cur = self._editor.toPlainText()
        add = self.preview.toPlainText()
        if not cur.strip() or cur.strip() == SQL_RUN_DEFAULT:
            self._editor.setPlainText(add)      # 还停留在连通检查那句，直接换成正题
        else:
            sep = "" if cur.endswith(";") else ";"
            self._editor.setPlainText(cur + sep + "\n" + add)
        self.hide()


# ---------------------------------------------------------------------------
# 打包/开发态共用的应用级设置
# ---------------------------------------------------------------------------
def setup_app(app: QApplication) -> None:
    """应用级设置。

    单独成函数是因为两条 GUI 线的对撞器要跑同一段代码，
    放在 __main__ 里就只有人手双击才能验到。
    """
    app.setWindowIcon(QIcon(resource_path(os.path.join("assets", "app_icon.ico"))))


if __name__ == "__main__":
    app = QApplication(sys.argv)
    setup_app(app)
    win = MainWindow()
    win.show()
    rc = app.exec()
    # 关窗之后先把窗口交还给还活着的 QApplication：模块清理阶段 app 与 win 谁先析构不由人，
    # Qt 对象排在 app 之后销毁会踩到已释放的窗口资源，用户看到的是"关掉就闪退"
    del win
    sys.exit(rc)
