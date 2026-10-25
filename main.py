"""
Excel -> MySQL 批量导入工具（PySide6 GUI）
========================================
功能：
  - 输入 MySQL 连接信息，选择目标库
  - 拖拽 / 点击虚线区选择多个 Excel 文件，或直接给 ZIP 压缩包（自动展开包内的多个 Excel）
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
运行：python main.py            打包：双击 一键打包-图形版.bat
"""
import os
import shutil
import sys
import tempfile
import time
import warnings

# 与 cli.py 同一条口径：openpyxl 读"没有命名样式"的 Excel（真实数据包里全是这种）会给每张表
# 打一行 UserWarning，pandas/cryptography 在 import 期也有自己的警告。这些必须在
# core.infer 被引进来之前压掉，否则日志里会淹出一堆不属于本工具格式的行。
warnings.filterwarnings("ignore", message=".*no default style.*", append=True)
warnings.filterwarnings("ignore", message=".*no longer supported by the Python core team.*",
                        append=True)

import functools
import re

from PySide6.QtCore import Qt, QThread, Signal, QUrl, QFileInfo, QTimer, QObject, QEvent
from PySide6.QtGui import (
    QDragEnterEvent, QDropEvent, QFont, QIcon, QColor, QTextCursor, QTextCharFormat,
    QPainter, QPen, QPainterPath,
)
from PySide6.QtWidgets import (
    QApplication, QWidget, QVBoxLayout, QHBoxLayout, QFormLayout,
    QLineEdit, QSpinBox, QPushButton, QComboBox, QFileDialog, QTableWidget,
    QTableWidgetItem, QLabel, QProgressBar, QPlainTextEdit, QGroupBox,
    QMessageBox, QHeaderView, QCheckBox, QAbstractItemView, QScrollArea, QStyle,
    QDialog, QDialogButtonBox, QTabWidget, QSplitter, QListWidget,
    QFrame, QSizePolicy,
)

from core import conn_config
from core.log_text import (
    describe_db_error, format_log_entry, log_level, LOG_LEVEL_MARK, LOG_TIME_FORMAT,
)
from core.infer import (
    build_table_def, make_unique_names, apply_table_prefix, validate_primary_key,
    replace_first_segment, sanitize_table_name, _col_to_field, dedup_field,
    table_name_problem,
)
from core.sql_execute import executable_statements, run as run_sql_statements
from core.sql_script import (
    extract_create_tables, split_sql_statements,
    parse_create_tables, rename_table_sql, apply_display_renames,
    set_table_comment, prepend_drop_tables,
    is_dump_script, dump_statements, dump_skip_reason, dump_database_switch,
    edit_column,
)
from core.naming import clean_prefix, is_valid_prefix, swap_first_segment
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
# 日志输出：每条带时间戳（年月日到秒）与分级标签，按级别上色；错误整条标红并展开数据库异常
# ---------------------------------------------------------------------------
LOG_ERROR_COLOR = "#c62828"
LOG_WARN_COLOR = "#b26a00"
LOG_OK_COLOR = "#1a7f37"
# info 不上色：一屏里绝大多数行都是它，全染上颜色等于全没染
LOG_LEVEL_COLORS = {"ok": LOG_OK_COLOR, "info": None, "warn": LOG_WARN_COLOR,
                    "error": LOG_ERROR_COLOR}
MAX_LOG_STATEMENT = 2000   # 出错语句全文最长展示，超出截断，避免超大 INSERT 刷爆日志

# 未选库只在顶部状态栏说一次；这句是不点名状态的完整解释，给悬停提示与拦下弹窗共用
NO_DB_HINT = "未选择目标库：请先在上方连接区填好连接信息，加载出库列表后选中要写入的库"
NO_FILE_HINT = "请先添加 Excel 文件或 ZIP 压缩包。"

# 「SQL 执行器」编辑区的开场文本：既是"连不连得上库"的一键自检语句，
# 也是「插入到编辑区」判断"这段还是默认值、可以直接覆盖掉"的比对基准
SQL_RUN_DEFAULT = "SELECT 1 AS 连通检查;"

# 两处日志区共用的字号：9 号字现场判过"太小"，14 号在默认窗口里只能露出三行；
# 10 号是两者之间的落点——控制台/IDE 的常见档位，比界面基准 9 号大一点。
LOG_FONT_SIZE = 10

# 第三页 SQL 编辑区与结果区的等宽字号：与第一页日志同一档（用户口径：两页正文一样大）。
# 直接同源到 LOG_FONT_SIZE，改日志档时这两处跟着走，不会一页大、一页小。
SQL_RUN_FONT_SIZE = LOG_FONT_SIZE
# 表格内容与表头的字号：与 Excel 数据网格同级（11 号），并保持比日志大一档。
# 18 号是上一轮"内容要比日志大"的过头做法，一屏装不下几行；两栈同一组数（11 / 10）。
TABLE_FONT_SIZE = 11
# 表格行高按字高加一点留白算出来，不写死像素：写死 30px 会让 18 号字顶满一行，
# 换成 11 号又显得空，字号与行距就对不上了。
TABLE_ROW_PADDING = 6

# 每张表格左上角那一句标识（组框标题）。用户口径：每个表格都要有标识，
# 第一页的「待导入文件」就是样子——标题里顺带写明这一张表怎么操作。
# 两栈同一句、由对撞器逐条比（读的是真控件上的标题，不是这里抄一份）。
FILE_TABLE_TITLE = "待导入文件（单击表名改名，双击其他列改字段）"
SQL_TABLE_TITLE = "待处理的脚本表（双击表名或表注释编辑，双击其他列看元数据）"
RUN_TABLE_TITLE = "待执行脚本（按清单顺序执行）"
COLUMN_TABLE_TITLE = "字段清单"

# 日志框的最小高度按"看得见的行数"约定，不按像素：同一档字号在 Qt 与 Swing 里
# 量出来的行高本就不同（界面基准也不同），写同一个像素数会变成"两边各自满足同一个数、
# 实际能看到的行数却不同"。像素数由各自的字体量出来，跨线比的是这个 4。
# 下限不能给得更大：主窗口自身最小高就有 717px，按 8 行给会把窗口顶到 847px，
# 1024x768 的现场机与 U7 那条"整窗落在可用区内"都撑不住。
LOG_MIN_LINES = 4


def log_min_height(view) -> int:
    """这个日志控件的最小高度：够放下 LOG_MIN_LINES 行它自己的字。"""
    return LOG_MIN_LINES * view.fontMetrics().height()


class LogMinPlacer(QObject):
    """默认把末段压在自己的最小高度，直到用户亲手拖过把手。

    第一、二页的末段是日志；第三页的末段是「SQL 文本 + 执行结果」，
    它同样要压到最小，把高度让给上面那张清单表（空清单的图标要看得全）。

    QSplitter 在尺寸总和不等于可用高度时会按 stretch 因子再分一次，
    构造时直接 setSizes([..., log_min]) 仍会多给日志一截；而窗口从构造到
    首次显示会经历好几次 Resize，只在第一次摆会留下最后一轮拉伸分给日志的
    那几十像素。所以在用户拖把手之前，每次 Resize 都按绝对高度重摆一次；
    一旦 splitterMoved 出现，日志高度就归用户，程序不再抢。
    """

    def __init__(self, splitter, tail_panel):
        super().__init__(splitter)
        self.splitter = splitter
        self.log_panel = tail_panel
        self.user_moved = False
        self.adjusting = False
        splitter.splitterMoved.connect(self._mark_user_moved)
        splitter.installEventFilter(self)

    def _mark_user_moved(self, pos, index):
        self.user_moved = True

    @staticmethod
    def _min_size(w):
        # QSplitter 不许折叠时，段的最小高度取 minimumHeight 与 minimumSizeHint 的较大者
        return max(w.minimumHeight(), w.minimumSizeHint().height())

    def eventFilter(self, obj, event):
        if self.user_moved or self.adjusting or event.type() != QEvent.Resize:
            return False
        height = self.splitter.height()
        log_min = self._min_size(self.log_panel)
        # 隐藏页签还没排过版时高度与最小尺寸都不可信，等下一次 Resize
        if log_min <= 0 or height < log_min + 200:
            return False
        handles = self.splitter.handleWidth() * (self.splitter.count() - 1)
        first = max(round(height * 110.0 / 670.0),
                    self._min_size(self.splitter.widget(0)))
        rest = height - first - handles
        table_min = self._min_size(self.splitter.widget(1))
        self.adjusting = True
        try:
            self.splitter.setSizes([first, max(rest - log_min, table_min), log_min])
        finally:
            self.adjusting = False
        return False

ZEBRA_ALT_BG = "#f4f8fc"


def style_table_font(view):
    """表格内容与表头一起设成同一档字号：只设单元格不设表头，表头会小一圈。

    两栈同一口径（Java 侧 gui.Tables.style / Tables.zebra），字号关系由用例钉住：
    日志不许比表格大；斑马纹的隔行底色也由两栈共用这一个色值。
    """
    font = QFont("", TABLE_FONT_SIZE)
    view.setFont(font)
    view.horizontalHeader().setFont(font)
    view.verticalHeader().setFont(font)
    # 行高跟着字高走：Qt 的默认行高是样式里写死的 30px，不随字体变，
    # 18 号字会顶满一行、11 号字又空出一截
    view.verticalHeader().setDefaultSectionSize(
        view.fontMetrics().height() + TABLE_ROW_PADDING)
    view.setAlternatingRowColors(True)
    view.setStyleSheet("QTableView { alternate-background-color: %s; }" % ZEBRA_ALT_BG)
    return view


EMPTY_HINT_COLOR = "#8a97a8"
EMPTY_ICON_COLOR = "#aebdd1"
ACTION_BAR_BG = "#f4f7fc"
ACTION_BAR_BORDER = "#dce5f2"


class EmptyHintTable(QTableWidget):
    """清单为空时在表格中央画一个"拖进来"的图标和一句引导语。

    用户口径：空表格要看得懂下一步做什么，并且和上方虚线区说同一句话。
    文字画在 viewport 上而不是塞一个覆盖控件：覆盖控件会吃掉滚轮与右键，
    表格一空就只剩这层装饰可点，反而更迷惑。
    """

    def __init__(self, rows, cols, hint="", parent=None):
        super().__init__(rows, cols, parent)
        self.empty_hint = hint

    def paintEvent(self, event):
        super().paintEvent(event)
        if self.rowCount() > 0 or not self.empty_hint:
            return
        vp = self.viewport()
        painter = QPainter(vp)
        try:
            painter.setRenderHint(QPainter.Antialiasing, True)
            font = QFont("", 11)
            painter.setFont(font)
            fm = painter.fontMetrics()
            icon_h, gap = 44, 10
            total = icon_h + gap + fm.height()
            top = max(8, (vp.height() - total) // 2)
            cx = vp.width() // 2
            self._paint_empty_icon(painter, cx, top, icon_h)
            painter.setPen(QColor(EMPTY_HINT_COLOR))
            text_w = fm.horizontalAdvance(self.empty_hint)
            painter.drawText((vp.width() - text_w) // 2,
                             top + icon_h + gap + fm.ascent(), self.empty_hint)
        finally:
            painter.end()

    @staticmethod
    def _paint_empty_icon(painter, cx, top, size):
        pen = QPen(QColor(EMPTY_ICON_COLOR))
        pen.setWidth(2)
        pen.setCapStyle(Qt.RoundCap)
        painter.setPen(pen)
        painter.setBrush(Qt.NoBrush)
        half = size // 2
        tray_top = top + int(size * 0.42)
        tray_bottom = top + size
        path = QPainterPath()
        path.moveTo(cx - half, tray_top)
        path.lineTo(cx - half, tray_bottom - 6)
        path.quadTo(cx - half, tray_bottom, cx - half + 6, tray_bottom)
        path.lineTo(cx + half - 6, tray_bottom)
        path.quadTo(cx + half, tray_bottom, cx + half, tray_bottom - 6)
        path.lineTo(cx + half, tray_top)
        painter.drawPath(path)
        arrow_tip = tray_top - 4
        painter.drawLine(cx, top + 2, cx, arrow_tip)
        painter.drawLine(cx - 7, arrow_tip - 7, cx, arrow_tip)
        painter.drawLine(cx + 7, arrow_tip - 7, cx, arrow_tip)


class ActionBar(QFrame):
    """底部动作条的浅色底板：覆盖开关与执行按钮放进同一块底里。

    用户口径：「覆盖已存在表」要像"执行前确认"的一部分，而不是和按钮各站一边。
    底色只负责归组，不加边框阴影，免得和蓝色的主按钮抢注意力。
    """

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setObjectName("actionBar")
        self.fill_color = ACTION_BAR_BG
        self.border_color = ACTION_BAR_BORDER
        self.setAttribute(Qt.WA_StyledBackground, True)
        self.setStyleSheet(
            "#actionBar { background: %s; border: 1px solid %s; border-radius: 6px; }"
            % (ACTION_BAR_BG, ACTION_BAR_BORDER))
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)


# 主按钮（执行/导入）的强调色，与 Java 侧 gui.Buttons.primary 同一组色值；
# 次级强调（保存脚本）只描边不铺色，避免一屏出现两颗同等分量的主按钮。
PRIMARY_BUTTON_QSS = (
    "QPushButton { background-color: #2f6feb; color: #ffffff;"
    " border: 1px solid #2459c9; border-radius: 4px;"
    " padding: 3px 12px; font-weight: bold; }"
    "QPushButton:hover { background-color: #3b7bf5; }"
    "QPushButton:pressed { background-color: #2459c9; }"
    "QPushButton:disabled { background-color: #aac4f0;"
    " border-color: #aac4f0; color: #f5f8ff; }"
)
SECONDARY_BUTTON_QSS = (
    "QPushButton { color: #1f5fd0; background: #f4f8ff;"
    " border: 1px solid #8fb4ea; border-radius: 4px; padding: 3px 10px; }"
    "QPushButton:hover { background: #e8f1ff; border-color: #2f6feb; }"
    "QPushButton:disabled { color: #9aa7b8; background: #f7f9fc;"
    " border-color: #c9d4e4; }"
)


def hline():
    """动作区与上方内容之间的细分隔线（两栈同一处：Java 侧用 JSeparator）。"""
    line = QFrame()
    line.setFixedHeight(1)
    line.setStyleSheet("background-color: #dbe3ee;")
    return line


ROLLBACK_NOTE = ("注意：CREATE/DROP/ALTER 等 DDL 在 MySQL 中隐式提交，不会随事务回滚；"
                 "回滚仅对其后的数据操作生效。")

# 结果弹窗的尺寸：与 Java 侧 MessageDialog 同一组数（设计像素）。
# 框按正文要多大就给多大，只有下限与上限，不再有一个"至少 820x420"的死地板：
# 一句话的提示也摊开这么大一块空白，用户第一反应是"这框是不是坏了"。
POPUP_WANT_WIDTH = 880       # 长句仍然给到 880，折行点才贴得上右边界
POPUP_MIN_WIDTH = 420
POPUP_MIN_HEIGHT = 170
POPUP_MAX_HEIGHT = 640       # 超过这一档才真的出滚动条
POPUP_SIDE_CHROME = 48       # 滚动区边框 + 正文左右边距 + 布局外边距
POPUP_TOP_CHROME = 120       # 标题行 + 按钮行 + 上下留白


class PopupMessageBox(QDialog):
    """结果/告警弹窗：正文原样显示，横向折行交给控件按内容区右边界做，纵向出滚动条。

    以前是 QMessageBox + 自己按 72 显示列预折行。那条列预算跟弹窗的实际像素宽
    没有任何约束关系——同一句话在 Java 那颗弹窗里只排到半幅宽（实测 691/1250），
    而且换行点写死在文本里，窗口拉大也不会重排。更要紧的是：为了把高度摁住，
    两边都得按"折出来的行"截断，于是截断位置也跟着各栈的字体走，
    一句"其余 N 行见日志"把两栈显示的正文改成了不同的话。
    改成"显示原文 + 控件折行 + 滚动条"之后，两栈显示的确实是同一份原文。
    """

    _STD_ICONS = {QMessageBox.Information: QStyle.SP_MessageBoxInformation,
                  QMessageBox.Warning: QStyle.SP_MessageBoxWarning,
                  QMessageBox.Critical: QStyle.SP_MessageBoxCritical}

    def __init__(self, parent=None):
        super().__init__(parent)
        lay = QVBoxLayout(self)
        head = QHBoxLayout()
        self.icon_label = QLabel()
        self.title_label = QLabel()
        bold = self.title_label.font()
        bold.setBold(True)
        self.title_label.setFont(bold)
        head.addWidget(self.icon_label)
        head.addWidget(self.title_label, 1)
        head.addStretch(1)
        lay.addLayout(head)

        self.body = QLabel()
        self.body.setWordWrap(True)
        self.body.setAlignment(Qt.AlignTop | Qt.AlignLeft)
        self.body.setTextInteractionFlags(Qt.TextSelectableByMouse)
        self.body.setMargin(8)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setWidget(self.body)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        lay.addWidget(scroll, 1)

        self.buttons = QDialogButtonBox(QDialogButtonBox.Ok)
        self.buttons.accepted.connect(self.accept)
        lay.addWidget(self.buttons)
        self.setMinimumSize(POPUP_MIN_WIDTH, POPUP_MIN_HEIGHT)

    # build_popup_box 与用例桩之间就靠这几个装配方法对话，两边实现同一套
    def setWindowTitle(self, title):
        super().setWindowTitle(title)
        self.title_label.setText(title)

    def setText(self, text):
        self.body.setText(text)
        fm = self.body.fontMetrics()
        lines = text.split("\n")
        widest = max([fm.horizontalAdvance(line) for line in lines] or [0])
        width = min(POPUP_WANT_WIDTH, max(POPUP_MIN_WIDTH, widest + POPUP_SIDE_CHROME))
        # 先定宽再问"折完有多高"：按原文行数估高，长句在窄处会多折几行，
        # 差出来的那点高度刚好把滚动条顶出来——这就是"默认就有滚动条"的来路
        need = self.body.heightForWidth(width - POPUP_SIDE_CHROME)
        need = max(need, len(lines) * fm.height())
        fit_client(self, width, min(POPUP_MAX_HEIGHT,
                                    max(POPUP_MIN_HEIGHT, need + POPUP_TOP_CHROME)))

    def setIcon(self, icon):
        std = self._STD_ICONS.get(icon)
        if std is not None:
            self.icon_label.setPixmap(self.style().standardIcon(std).pixmap(32, 32))

    def setStandardButtons(self, buttons):
        self.buttons.setStandardButtons(buttons)

    def text(self):
        return self.body.text()

    def body_label(self):
        """正文那一个标签：用例与取证要量的是它的宽度与折完的高度，不是标题。"""
        return self.body


_POPUP_MESSAGE_BOX = PopupMessageBox
_POPUP_ICONS = {"information": QMessageBox.Information,
                "warning": QMessageBox.Warning,
                "critical": QMessageBox.Critical}
_POPUP_OK = QDialogButtonBox.Ok


def build_popup_box(parent, title: str, text: str, kind: str = "information",
                    box_class=None):
    """构造结果弹窗：正文一律原样交给弹窗自己排，复制与显示的是同一份文字。"""
    box = (box_class or _POPUP_MESSAGE_BOX)(parent)
    box.setWindowTitle(title)
    box.setText(text)
    box.setIcon(_POPUP_ICONS[kind])
    box.setStandardButtons(_POPUP_OK)
    return box


def popup_box(parent, title: str, text: str, kind: str = "information"):
    """消息弹窗都走这一道，确保日志、弹窗和复制出来的是同一份文案。"""
    return build_popup_box(parent, title, text, kind).exec()



def append_log(view: QPlainTextEdit, text: str, error: bool = False,
               level: str = None) -> None:
    """向日志控件追加一条记录：首行带 [年-月-日 时:分:秒] 与分级标签，整条按级别上色。

    三个页签的日志框都只从这一处写，所以时间戳、级别、颜色不会出现"这一页有、那一页没有"。
    级别由 core.log_text 定（错误标志 > 已有大写标签 > 中文标签），与 Java 侧同一张表。
    """
    formatted, lvl = format_log_entry(text, error)
    if level:
        lvl = level
    color = LOG_LEVEL_COLORS.get(lvl)
    lines = formatted.count("\n") + 1
    view.appendPlainText(formatted)
    if not color:
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
    fmt.setForeground(QColor(color))
    fmt.setFontWeight(QFont.Bold if lvl in ("warn", "error") else QFont.Normal)
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


def format_file_size(size) -> str:
    """脚本文件大小按人看的一档显示；两栈同一套进位与一位小数。"""
    if size is None:
        return ""
    size = float(size)
    if size < 1024:
        return "%d B" % int(size)
    for unit in ("KB", "MB"):
        size /= 1024.0
        if size < 1024:
            return "%.1f %s" % (size, unit)
    return "%.1f GB" % (size / 1024.0)


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


class TaskInfo(QObject):
    """一个在跑的任务：占哪些资源、叫什么、进度到哪、日志写哪、完了回调谁。

    以前这些散在主窗口的一组字段上（task_dialog / _task_log_view / _task_on_done / task_*），
    一次只容得下一个任务，于是执行器跑上千条语句时另外两页全被锁住，
    而被挡下的人只看到一句"请稍等"。现在一个任务一个对象，资源冲突才互斥。
    """

    def __init__(self, window, resources, title, log_view, on_done):
        super().__init__(window)
        self.window = window
        self.resources = tuple(resources)
        self.title = title
        self.log_view = log_view
        self.on_done = on_done
        self.dialog = None
        self.worker = None
        self.started = time.time()
        self.done = 0
        self.total = 0
        self.label = ""
        self.result = None
        self.finished = False

    # ---- 进度：工作线程的信号排队送到这里（本对象住在主线程） ----
    def set_progress(self, done, total, label=""):
        self.done = done
        self.total = max(total, 0)
        self.label = label or self.label
        if self.dialog is not None:
            self.dialog.set_progress(done, total, label)

    def log(self, text):
        append_log(self.log_view, text)

    def log_error(self, text):
        append_log(self.log_view, text, error=True)

    def percent(self):
        if self.total <= 0:
            return 0
        return min(100, int(self.done * 100 / self.total))

    def describe(self):
        """一句能照着排查的话：任务名 + 百分比 + 计数 + 已跑多久（+ 当前项）。"""
        secs = int(time.time() - self.started)
        text = "%s %d%%（%d/%d）· 已 %d 秒" % (self.title or "未命名任务",
                                              self.percent(), self.done, self.total, secs)
        return text + " · " + self.label if self.label else text

    def cancel(self):
        if self.worker is not None:
            self.worker.cancel()
        elif self.dialog is not None:
            self.dialog.btn_cancel.click()

    def completed(self, result):
        self.result = result
        if self.dialog is not None:
            self.dialog.finish()

    def failed(self, msg):
        self.result = None
        if self.dialog is not None:
            self.dialog.finish()
        append_log(self.log_view,
                   "[任务异常] %s\n    本次操作未完成，请查看日志后重试。" % msg, error=True)
        popup_box(self.window, "任务异常", "%s\n本次操作未完成，详见日志。" % msg, "critical")

    def thread_done(self):
        """线程真正退出后才收尾：completed 可能更早（run 的最后一句就是发信号）。"""
        self.window._finish_task(self)


class ProgressDialog(QDialog):
    """模态进度框：标题 + 当前项文字 + 进度条 + 取消按钮。

    耗活的都在子线程，主线程只在信号到达时刷新控件，所以框始终能重绘、能拖动、
    取消按钮始终点得动，不会像同步循环那样"假死"。
    """

    def __init__(self, title, parent=None):
        super().__init__(parent)
        self.setWindowTitle(title)
        self.finished_ok = False
        # 只保留标题：任务进行中不能从标题栏关窗（线程还在跑），退出只走「取消」
        self.setWindowFlags(Qt.Dialog | Qt.CustomizeWindowHint | Qt.WindowTitleHint)
        # 非模态：`show()` 已经不再套嵌套事件循环，但 modal 标志本身就会挡住别的窗口，
        # 留着它三条页签在任务跑完前一个都点不动（用例 Y1d 实测抓出来的就是这个）
        self.setModal(False)
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
        self.finished_ok = True
        self._anim.stop()
        self.bar.setValue(100)
        self.accept()

    def closeEvent(self, e):
        """点标题栏的 X：任务还在跑就等于按「取消」，不能让框消失后留一个看不见的后台任务。

        以前 X 直接 accept()，exec() 一返回，主窗口以为这轮结束了：任务位被放开、
        用户可以再点下一个按钮，而工作线程还在写同一份列表。
        """
        if not getattr(self, "finished_ok", False):
            e.ignore()
            self.btn_cancel.click()
            return
        super().closeEvent(e)


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
            pk_msg = (f" | 主键 `{pk}`（{td.get('pk_reason', '')}）" if pk
                      else f" | 未设主键（{td.get('pk_reason', '')}）")
            src_msg = " | 结构定义模式" if td.get("source") == "structured" else ""
            merged_header = td.get("merged_header")
            merge_msg = (f" | 合并表头按 {merged_header['depth']} 行展开"
                         if merged_header else "")
            # 这一行的正文用 | 分段：三页的日志一屏几百行，
            # 逗号串起来的长句要数着读，分段后一眼就能挑出字段数、行数、主键
            line = (f"[已解析] {fname} → 表名预置 {td['table_name']}"
                    f" | 字段 {len(td['columns'])} 列 | 数据 {len(td['df'])} 行"
                    f"{src_msg}{merge_msg}{pk_msg}")
            notes = []
            extra_cols = td.get("extra_data_columns") or []
            if extra_cols:
                notes.append("数据 Sheet 多余列已忽略：%s" % "、".join(extra_cols))
            empty_cols = td.get("empty_columns") or []
            if empty_cols:
                head = "、".join(empty_cols[:6])
                more = f" 等 {len(empty_cols)} 个" if len(empty_cols) > 6 else ""
                notes.append("整列为空的字段照旧保留：%s%s" % (head, more))
            if td.get("no_data_rows"):
                notes.append("没有数据行，建出来是空表")
            blank_h = td.get("blank_headers") or []
            if blank_h:
                head = "、".join(blank_h[:6])
                more = f" 等 {len(blank_h)} 个" if len(blank_h) > 6 else ""
                notes.append("表头整行没写字，按列宽兜底成：%s%s" % (head, more))
            if notes:
                # 这几条是"为什么这张表长成这样"的补充，缩进跟在[已解析]下面，
                # 不再挤进正文那一行
                line += "\n    说明 | " + " | ".join(notes)
            sink.log(line)
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


def ensure_parsed(record):
    """这张记录的"表名 → 字段定义"：读入时已算好就直接取，没算过才现解析一次并存回去。

    sql_load_task 走的是前者；手工塞进列表的记录（用例、改名撞车后被丢掉的）走后者。
    表格刷新与元数据弹窗都靠它，不再各自回扫整份脚本。
    """
    parsed = record.get("parsed")
    if parsed is None:
        parsed = {t["table_name"]: t for t in parse_create_tables(record["text"])}
        record["parsed"] = parsed
    return parsed


def sql_load_task(paths, duplicates, sink):
    """读入 SQL 脚本文本并提取表名：几十万行的脚本读盘 + 正则扫描都在这里。

    duplicates 是加入列表时就被拦下的重复路径，一并计入进度与添加结果；
    返回 loaded（可直接并入 self.sql_files 的记录）、failed 与 duplicates，
    弹窗里要逐个列出完整路径，用户才知道刚才选的是哪个目录里的脚本。
    """
    sink.set_total(len(paths) + len(duplicates))
    loaded, failed = [], []
    for p in duplicates:
        sink.log(f"[跳过] 已在列表：{os.path.basename(p)}")
        sink.step(os.path.basename(p))
    for p in paths:
        fname = os.path.basename(p)
        if sink.cancelled:
            break
        try:
            text = read_sql_text(p)
        except Exception as e:
            reason = describe_db_error(e)
            failed.append({"path": p, "reason": reason})
            sink.log_error(f"[读取失败] {fname} 未能读入，已跳过\n"
                           + indent_detail(reason))
            sink.step(fname)
            continue
        tabs = extract_create_tables(text)
        # 字段定义也在这条子线程里一次解析完，按"原表名"存着：
        # 双击一行要的是其中一张表，整份重解析在 10MB 脚本上是 1.8 秒（实测），
        # 放在建对话框那一刻就是"点一下卡一下"，放在这里只是进度条多走一格
        parsed = {t["table_name"]: t for t in parse_create_tables(text)}
        names = "、".join(t["name"] for t in tabs) or "未发现 CREATE TABLE 语句"
        sink.log(f"[已加载] {fname}：{len(tabs)} 个表（{names}）")
        loaded.append({"path": p, "text": text, "modified": False,
                       "rename_map": {}, "display_map": {}, "tabs": tabs,
                       "parsed": parsed})
        sink.step(fname)
    return {"loaded": loaded, "failed": failed, "duplicates": list(duplicates),
            "cancelled": sink.cancelled}


def rekey_parsed(parsed, renames):
    """脚本正文里改了表名，把 sql_load_task 存的解析缓存按新名重挂。

    改表名不动列定义，缓存里那一格换个键还是对的。两种情况另外处理：
    要改的名字压根不在缓存里 → 缓存本来就不全，返回 None 丢掉整份，下次用时现解析；
    新名字与缓存里另一张表撞车 → 留先出现的那一张（与「同名表只按第一处导出」同一条规则），
    缓存仍然可用。撞车不丢整份，是因为刷新清单也要读这张缓存，
    丢了就等于把几十万行的重解析搬回主线程，正好是这次要治的那一下卡顿。
    """
    if parsed is None or not set(renames) <= set(parsed):
        return None
    out = {}
    for name, table in parsed.items():
        new = renames.get(name, name)
        if new in out:
            continue
        if new != name:
            table = dict(table)
            table["table_name"] = new
        out[new] = table
    return out


def sql_save_task(items, sink):
    """把改过的脚本写成 *_prefix.sql，几十万行的写盘放在子线程里。

    items 为 [(path, text), ...]；返回 saved 为 [(原路径, 落盘路径), ...]，
    failed 为 [{path, reason}]。结果弹窗要按文件列出完整路径，失败也要写清是哪一份。
    """
    sink.set_total(len(items))
    saved, failed = [], []
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
            reason = describe_db_error(e)
            failed.append({"path": path, "reason": reason})
            sink.log_error(f"[保存失败] {fname}：{reason}")
            sink.step(fname)
            continue
        saved.append((path, out))
        sink.log(f"[已保存] {out}")
        sink.step(fname)
    return {"saved": saved, "failed": failed, "cancelled": sink.cancelled}


def fetch_databases(cfg, _sink=None):
    """连库取库列表；启动自动连接与手动「连接并加载库」共用这一条路。

    纯工作函数（_sink 只为满足 ProgressWorker 的最后一个入参约定），不碰控件。
    5 秒连接超时：自动连接在后台跑，但关闭窗口时也不能被一个不在线的地址拖住。
    """
    conn = connect(cfg, connect_timeout=5)
    try:
        return list_databases(conn)
    finally:
        conn.close()


def sql_execute_task(items, cfg, sink):
    """逐个脚本执行到目标库，每个文件一个事务。

    items 为 [(path, text), ...]；语句条数先汇总再计项，几十万条时按块推进，
    保证进度条既有分辨率又不会发出海量信号。
    """
    sink.note(f"正在切分 {len(items)} 个脚本的语句…")
    plans = []
    for path, text in items:
        stmts = executable_statements(text)
        plans.append((path, stmts))
        if sink.cancelled:
            break
    sink.set_total(sum(len(p[1]) for p in plans))

    target = f"{cfg['database']}@{cfg['host']}:{cfg['port']}"
    ok_files = fail_files = skipped = 0
    files = []                                      # 逐文件结局，执行完成弹窗按路径列出来
    for path, stmts in plans:
        fname = os.path.basename(path)
        if sink.cancelled:
            break
        if not stmts:
            skipped += 1
            files.append({"path": path, "status": "skip",
                          "detail": "没有可执行的语句"})
            sink.log(f"[跳过] {fname}：没有可执行的语句")
            continue
        try:
            conn = connect(cfg)
        except Exception as e:
            sink.log_error(
                f"[连接失败] 无法连接到 {target}（用户 {cfg['user']}），剩余脚本均未执行\n"
                + indent_detail(describe_db_error(e)))
            return {"ok": ok_files, "fail": fail_files, "skipped": skipped,
                    "files": files, "target": target,
                    "cancelled": True, "conn_failed": True}
        chunk = max(1, len(stmts) // 100)           # 每个文件最多约 100 次刷新
        reported = 0
        failed = False
        executed = 0                                # 供异常分支引用，begin/cursor 失败时也已有值
        outcome, detail = "ok", ""
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
                    outcome = "fail"
                    detail = f"第 {pos}/{len(stmts)} 条语句报错，本文件事务已回滚"
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
                outcome = "cancel"
                detail = f"已执行 {executed}/{len(stmts)} 条，本文件事务已回滚"
                sink.log(f"[已取消] {fname}：已执行 {executed}/{len(stmts)} 条，"
                         f"本文件事务已回滚\n    " + ROLLBACK_NOTE)
            else:
                conn.commit()                       # 全部成功 → 提交事务
                detail = f"{executed} 条语句已提交"
                sink.log(f"[提交] {fname}：{executed} 条语句已提交")
        except Exception as e:
            failed = True
            outcome = "fail"
            detail = f"执行异常（已提交 {executed} 条），本文件事务已回滚"
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
        files.append({"path": path, "status": outcome, "detail": detail})
        # 取消的文件既不算成功也不算失败：汇总里的成功数不能把回滚掉的那份算进去
        if outcome == "ok":
            ok_files += 1
        elif outcome == "fail":
            fail_files += 1
    done_paths = {f["path"] for f in files}
    for path, _ in plans:                            # 取消或连接中断后没轮到的脚本
        if path not in done_paths:
            files.append({"path": path, "status": "cancel", "detail": "未执行"})
    return {"ok": ok_files, "fail": fail_files, "skipped": skipped,
            "files": files, "target": target,
            "cancelled": sink.cancelled, "conn_failed": False}


DUMP_ERROR_LIMIT = 20


def _run_dump_file(conn, fname, stmts, sink, chunk, target):
    """按整库 dump 的方式执行一个文件：不开事务、每条一提交、出错继续。

    返回 (outcome, detail)。dump 里的 DDL 与 LOCK TABLES 本来就会隐式提交，
    "整文件回滚"在这里是个兑现不了的承诺；改成逐条提交后，某条失败不会再带走
    前面已经成功的数据，收尾就按"已执行多少条、几条报错"如实汇总。
    """
    cur = conn.cursor()
    total = len(stmts)
    executed = errors = skipped_drop = over_limit = reported = 0
    cancelled = False
    db_note = ""
    try:
        for pos, st in enumerate(stmts, 1):
            if sink.cancelled:
                cancelled = True
                break
            nav = dump_skip_reason(st)
            if nav:
                skipped_drop += 1
                sink.log(f"[跳过] {fname} 第 {pos}/{total} 条：{nav}"
                         "（删库语句不执行）")
                continue
            try:
                cur.execute(st)
                if cur.description is not None:
                    cur.fetchall()
                conn.commit()           # 逐条提交：后面某条失败时，前面的成果不再被带走
                executed += 1
                db = dump_database_switch(st)
                if db:
                    # dump 自己切库，后续语句就落在这个库里；汇总要说的是它，不是界面选的那个
                    _, sep, rest = target.partition("@")
                    target = f"{db}{sep}{rest}"
                    db_note = f"，目标库 {db}"
                    sink.log(f"[切库] {fname}：目标库换成 {db}")
            except Exception as e:
                errors += 1
                if errors <= DUMP_ERROR_LIMIT:
                    sink.log_error((
                        f"[执行失败] {fname} 第 {pos}/{total} 条语句报错，目标库 {target}\n"
                        + indent_detail(describe_db_error(e)) + "\n"
                        + "    出错语句：\n" + format_statement(st)))
                else:
                    over_limit += 1
                try:
                    conn.rollback()     # 清掉这一条可能留下的半截状态；已提交的语句不受影响
                except Exception:
                    pass
                if not getattr(conn, "open", True):
                    sink.log_error(f"[连接中断] {fname} 第 {pos}/{total} 条后连接已断开，"
                                   "剩余语句未执行")
                    break
            if pos - reported >= chunk:
                reported = pos
                sink.advance(chunk, f"{fname} 第 {pos}/{total} 条")
    finally:
        try:
            cur.close()
        except Exception:
            pass
    sink.advance(total - reported, f"{fname} 收尾")   # 把余数补齐，进度落准
    if over_limit:
        sink.log_error(f"[执行失败] {fname} 另有 {over_limit} 条语句报错未逐条列出")
    if errors:
        sink.log_error(f"[dump 完成] {fname}：已执行 {executed}/{total} 条，"
                       f"{errors} 条语句报错；逐条提交，已成功的已落库")
        return "fail", (f"已执行 {executed}/{total} 条，{errors} 条语句报错"
                        "（逐条提交，已成功的已落库）")
    if cancelled:
        sink.log(f"[已取消] {fname}：已执行 {executed}/{total} 条；"
                 "逐条提交，已执行的已落库")
        return "cancel", f"已执行 {executed}/{total} 条（逐条提交，已执行的已落库）"
    note = f"，跳过 {skipped_drop} 条删库语句" if skipped_drop else ""
    sink.log(f"[提交] {fname}：{executed} 条语句已提交{db_note}{note}")
    return "ok", f"{executed} 条语句已提交{db_note}{note}"


def sql_run_files_task(paths, overwrite, cfg, sink):
    """「SQL 执行器」页的脚本文件队列：按清单顺序逐个执行。

    手写脚本每个文件一个事务；识别为整库 dump 的文件走另一条通道（不开事务、
    逐条提交、出错继续），因为 dump 里的 DDL / LOCK TABLES 会隐式提交，
    "整文件回滚"在那里兑现不了。差别只在读盘时机——轮到哪个文件才读哪个，
    导入一份大 dump 不必先把所有脚本正文都按在内存里。进度按语句推进；
    读到新文件时总数随之增加（ProgressSink 的总数只增不减）。
    """
    target = f"{cfg['database']}@{cfg['host']}:{cfg['port']}"
    ok_files = fail_files = skipped = 0
    files = []                                      # 逐文件结局，收尾按路径写回清单状态
    reported_total = 0
    for index, path in enumerate(paths, 1):
        fname = os.path.basename(path)
        if sink.cancelled:
            files.append({"path": path, "status": "cancel", "detail": "未执行"})
            continue
        sink.note(f"正在读取第 {index}/{len(paths)} 个脚本：{fname}")
        try:
            text = read_sql_text(path)
        except Exception as e:
            reason = describe_db_error(e)
            fail_files += 1
            files.append({"path": path, "status": "fail", "detail": "读取失败，未执行"})
            sink.log_error(f"[读取失败] {fname} 未能读入，已跳过\n"
                           + indent_detail(reason))
            continue
        if overwrite:
            text, dropped = prepend_drop_tables(text)
            sink.log(f"[覆盖] {fname}：" + (
                "执行前先 DROP " + "、".join(dropped) if dropped
                else "脚本里没有以 CREATE TABLE 开头的语句，没有表需要先删除"))
        dump = is_dump_script(text)
        stmts = dump_statements(text) if dump else executable_statements(text)
        if dump:
            sink.log(f"[dump] {fname}：识别为整库 dump，按 dump 方式执行"
                     "（不开启事务，逐条提交；出错继续，最后汇总）")
        if not stmts:
            skipped += 1
            files.append({"path": path, "status": "skip",
                          "detail": "没有可执行的语句"})
            sink.log(f"[跳过] {fname}：没有可执行的语句")
            continue
        try:
            conn = connect(cfg)
        except Exception as e:
            sink.log_error(
                f"[连接失败] 无法连接到 {target}（用户 {cfg['user']}），剩余脚本均未执行\n"
                + indent_detail(describe_db_error(e)))
            files.append({"path": path, "status": "cancel", "detail": "连接失败，未执行"})
            for rest in paths[index:]:
                files.append({"path": rest, "status": "cancel", "detail": "未执行"})
            return {"ok": ok_files, "fail": fail_files, "skipped": skipped,
                    "files": files, "target": target,
                    "cancelled": True, "conn_failed": True}
        reported_total += len(stmts)
        sink.set_total(reported_total)
        chunk = max(1, len(stmts) // 100)           # 每个文件最多约 100 次刷新
        if dump:
            outcome, detail = _run_dump_file(conn, fname, stmts, sink, chunk, target)
            try:
                conn.close()
            except Exception:
                pass
            files.append({"path": path, "status": outcome, "detail": detail})
            if outcome == "ok":
                ok_files += 1
            elif outcome == "fail":
                fail_files += 1
            continue
        reported = 0
        failed = False
        executed = 0
        outcome, detail = "ok", ""
        try:
            conn.begin()
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
                    conn.rollback()
                    failed = True
                    outcome = "fail"
                    detail = f"第 {pos}/{len(stmts)} 条语句报错，本文件事务已回滚"
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
                outcome = "cancel"
                detail = f"已执行 {executed}/{len(stmts)} 条，本文件事务已回滚"
                sink.log(f"[已取消] {fname}：已执行 {executed}/{len(stmts)} 条，"
                         f"本文件事务已回滚\n    " + ROLLBACK_NOTE)
            else:
                conn.commit()
                detail = f"{executed} 条语句已提交"
                sink.log(f"[提交] {fname}：{executed} 条语句已提交")
        except Exception as e:
            failed = True
            outcome = "fail"
            detail = f"执行异常（已提交 {executed} 条），本文件事务已回滚"
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
        sink.advance(len(stmts) - reported, f"{fname} 收尾")   # 把余数补齐，进度落准
        files.append({"path": path, "status": outcome, "detail": detail})
        # 取消的文件既不算成功也不算失败：汇总里的成功数不能把回滚掉的那份算进去
        if outcome == "ok":
            ok_files += 1
        elif outcome == "fail":
            fail_files += 1
    return {"ok": ok_files, "fail": fail_files, "skipped": skipped,
            "files": files, "target": target,
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
    # 逐条推进度并每句查一次取消：一屏上千条语句的批次，
    # 进度条停在 0%、取消点了没反应，用户就只能等或者强关窗口
    state = {"done": 0}

    def progress(done, total):
        sink.set_total(total)
        sink.advance(done - state["done"], "第 %d/%d 条" % (done, total))
        state["done"] = done

    try:
        return {"executed": run_sql_statements(conn, text, sink.log,
                                               progress=progress,
                                               cancelled=lambda: sink.cancelled)}
    finally:
        try:
            conn.close()
        except Exception:
            pass


def _sql_naming(name, initials, upper, is_table):
    """第二页的命名开关：只重写含中文的名字；纯英文只在勾了大写时改大小写。

    脚本里的英文标识符本来就是合法表名/字段名，默认不勾选时一个字不动；
    勾「取拼音首字母」才有转写动作，勾「转大写」才有大小写动作。
    """
    if initials and re.search(r"[\u4e00-\u9fff]", name):
        name = (sanitize_table_name(name, initials=True)
                if is_table else _col_to_field(name, initials=True))
    return name.upper() if upper else name


def sql_meta_export_task(items, out_root, with_meta, upper, sink):
    """「SQL 脚本改名」页的生成标准/简易 Excel：先从脚本正文解析表定义，再走同一套导出。

    items 为 [(path, text), ...]，text 取的是页面上改过名之后的当前正文，
    所以"先批量改前缀、再生成交付件"这条路是通的——产物里的表名就是新表名。
    脚本里只有结构没有数据，所以「数据」Sheet 只有英文字段名那一行表头，
    给下游当可填模板用，不假装里面有数据。
    第二页的大小写开关只作用在清单、元数据视图和生成物上；脚本正文一个字不动。
    """
    from core.meta_export import export_tables, SQL_DIR_TAG

    if upper:
        sink.log("[命名] 生成物表名与字段名：转大写")
    tables, where, used_tables = [], {}, {}
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
            if upper:
                t = dict(t)
                t["table_name"] = dedup_field(
                    used_tables, _sql_naming(t["table_name"], False, upper, True))
                seen_fields = {}
                t["columns"] = [
                    dict(c, field=dedup_field(
                        seen_fields,
                        _sql_naming(c["field"], False, upper, False)))
                    for c in t["columns"]
                ]
            tables.append(t)
    if not tables:
        sink.log("! [WARN] 没有可导出的表：这些脚本里解析不出带列定义的 CREATE TABLE")
        return {"dir": None, "written": [], "total": 0, "cancelled": sink.cancelled}
    if sink.cancelled:
        return {"dir": None, "written": [], "total": len(tables), "cancelled": True}
    # 这一页的产物落 sql_ 前缀的目录，不与 Excel 页那份抢同一个日期目录
    target, written = export_tables(tables, out_root, with_meta, sink,
                                    dir_tag=SQL_DIR_TAG)
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
    clicked = Signal()

    # 两个图形页共用这一种拖拽区，只有文案不同：Excel 页收 xlsx/xls/zip，
    # SQL 页只收 .sql。默认值保持第一页原来那句，第二页构造时传自己那两句。
    EXCEL_HINT = "将 Excel 文件或 ZIP 压缩包拖到此处，或点击选择文件\n" \
                 "（支持 .xlsx / .xls / .zip，可多选）"
    SQL_HINT = "将 SQL 脚本拖到此处，或点击选择文件\n（支持 .sql，可多选）"
    RUN_HINT = "将 SQL 脚本拖到此处，或点击选择文件\n（支持 .sql，可多选；按列表顺序执行）"

    # 淡蓝底 + 悬停加深：底色本身就要让人看出"这块是可以放东西的地方"，
    # 不能等鼠标移上去才发现。色值与 Java 侧 DropArea 同一组，由对撞键核同值。
    BG = "#eaf2fd"
    BG_HOVER = "#d8e8ff"
    LINE = "#a8bcd8"
    LINE_HOVER = "#2f6feb"
    TEXT = "#2c4a72"

    def __init__(self, parent=None, hint=None, tooltip=None):
        super().__init__(parent)
        self.hint_text = hint if hint is not None else self.EXCEL_HINT
        self._hovered = False
        self.fill_color = self.BG
        self.hover_color = self.BG_HOVER
        self.line_color = self.LINE
        self.hover_line_color = self.LINE_HOVER
        self.setAcceptDrops(True)
        self.setCursor(Qt.PointingHandCursor)
        self.setToolTip(tooltip if tooltip is not None
                        else "点击选择 Excel / ZIP 文件；也可以直接拖到这里")
        lay = QVBoxLayout(self)
        self.label = QLabel(self.hint_text)
        self.label.setAlignment(Qt.AlignCenter)
        self.label.setFont(QFont("", 12))
        self.label.setStyleSheet("QLabel { background: transparent; color: %s; }" % self.TEXT)
        lay.addWidget(self.label)
        self.setMinimumHeight(80)
        self.setAttribute(Qt.WA_OpaquePaintEvent, False)

    def paintEvent(self, event):
        # 底色与虚线自己画，不用 QSS：自定义 QWidget 的样式表背景要等一次 polish，
        # 鼠标快速掠过时会慢一帧才上色，看起来像"悬停没反应"
        painter = QPainter(self)
        try:
            painter.setRenderHint(QPainter.Antialiasing, True)
            pen = QPen(QColor(self.hover_line_color if self._hovered else self.line_color))
            pen.setWidth(2)
            pen.setDashPattern([6, 4])
            painter.setPen(pen)
            painter.setBrush(QColor(self.hover_color if self._hovered else self.fill_color))
            inset = 1
            painter.drawRoundedRect(inset, inset, self.width() - 2 * inset - 1,
                                    self.height() - 2 * inset - 1, 8, 8)
        finally:
            painter.end()

    def _set_hover(self, on):
        if self._hovered != on:
            self._hovered = on
            self.setProperty("hovered", on)
            self.repaint()

    @property
    def hovered(self):
        return self._hovered

    def mouseReleaseEvent(self, e):
        if e.button() == Qt.LeftButton:
            self.clicked.emit()
        super().mouseReleaseEvent(e)

    def enterEvent(self, e):
        self._set_hover(True)
        super().enterEvent(e)

    def leaveEvent(self, e):
        self._set_hover(False)
        super().leaveEvent(e)

    def dragEnterEvent(self, e: QDragEnterEvent):
        if e.mimeData().hasUrls():
            e.acceptProposedAction()
            self._set_hover(True)

    def dragLeaveEvent(self, e):
        self._set_hover(False)

    def dropEvent(self, e: QDropEvent):
        self._set_hover(False)
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
        self.sql_files = []         # SQL 脚本改名页：[{"path","text","modified","rename_map"}]
        # SQL 执行器页的待执行脚本：只登记路径/大小/状态，正文等到执行时再读
        self.sql_run_files = []
        self._sql_rows = []         # 表格每一行对应的实际表名，避免显示大小写与脚本内容混淆
        self._sql_display_names = []  # 上一轮刷新出来的显示值，用来识别"没编辑却提交同名"
        self._refreshing_sql_table = False
        self._refreshing_file_table = False   # 刷新文件表时压住 itemChanged，避免自己触发改名
        self._zip_tmp = None        # ZIP 展开用的会话级临时目录，惰性创建
        self._zip_origin = {}       # 解压出的路径 → "包名!包内路径"，供文件列表 tooltip
        # 在跑的任务清单：一个任务一份账，资源冲突才互斥（不再是一个全局开关锁三页）
        self._tasks = []
        self._import_task = None   # 导入这条长任务单独留一份引用，收尾收它自己的框
        self._cfg_db = ""          # conn.ini 里写的目标库，库列表加载出来后按它预选
        # 启动自动连接：只有真的读到 conn.ini 或 E2M_* 才算"用户配过"。
        # 什么都没配时不去连——否则每台机器一开窗都先对本机 3306 撞一次，日志里多一条红
        self._conn_auto = False
        self._auto_conn_started = False
        self._auto_conn_worker = None
        # 手动加载一次就 +1：自动连接的结果回来晚了（用户已经自己点过按钮）就作废，
        # 不能让一份过期结果盖掉用户刚选的库
        self._db_load_seq = 0
        self._auto_conn_seq = 0
        # 连接表单的展开态自己记，不拿 conn_form.isVisible() 反查：
        # 窗口还没 show 过的时候 Qt 的 isVisible() 一律给 false，
        # 首屏那句话就会让人去点一颗此刻恰好藏着的「设置数据源信息」
        self._conn_expanded = False

        self._build_ui()
        self._load_default_conn()

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

        # 1. 连接区：业务时只关心"现在是哪个库"，五行环境信息平时收起成一条状态栏，
        #    要改连接/测连接/换库时点「设置数据源信息」展开（用户口径：运行时常驻展示作用不大）
        conn = QWidget()
        conn_lay = QVBoxLayout(conn)
        conn_lay.setContentsMargins(0, 0, 0, 0)
        conn_lay.setSpacing(2)

        self.conn_bar = QWidget()
        bar_lay = QHBoxLayout(self.conn_bar)
        bar_lay.setContentsMargins(6, 0, 0, 0)
        self.lbl_conn_state = QLabel()
        self.lbl_conn_state.setTextInteractionFlags(Qt.TextSelectableByMouse)
        self.btn_conn_edit = QPushButton("设置数据源信息")
        self.btn_conn_edit.setToolTip("展开填写主机 / 端口 / 用户名 / 密码，并测试连接与选择数据库")
        self.btn_conn_edit.clicked.connect(lambda: self.set_conn_expanded(True))
        bar_lay.addWidget(self.lbl_conn_state, 1)
        bar_lay.addWidget(self.btn_conn_edit)
        conn_lay.addWidget(self.conn_bar)

        self.conn_form = QGroupBox("MySQL 连接信息")
        f = QFormLayout(self.conn_form)
        self.ed_host = QLineEdit("127.0.0.1")
        self.ed_port = QSpinBox(); self.ed_port.setRange(1, 65535); self.ed_port.setValue(3306)
        self.ed_user = QLineEdit("root")
        self.ed_pass = QLineEdit(); self.ed_pass.setEchoMode(QLineEdit.Password)
        self.cb_db = QComboBox(); self.cb_db.setEnabled(False)
        self.btn_load_db = QPushButton("连接并加载库")
        self.btn_load_db.clicked.connect(self.on_load_databases)
        self.btn_conn_collapse = QPushButton("收起")
        self.btn_conn_collapse.setToolTip("收起连接信息，只保留当前库")
        self.btn_conn_collapse.clicked.connect(lambda: self.set_conn_expanded(False))
        h = QHBoxLayout()
        h.addWidget(self.cb_db, 1)
        h.addWidget(self.btn_load_db)
        h.addWidget(self.btn_conn_collapse)
        f.addRow("主机：", self.ed_host)
        f.addRow("端口：", self.ed_port)
        f.addRow("用户名：", self.ed_user)
        f.addRow("密码：", self.ed_pass)
        f.addRow("目标库：", h)
        conn_lay.addWidget(self.conn_form)
        root.addWidget(conn)

        # 2. 功能页签：Excel 导入 / SQL 脚本改名 / SQL 执行器（位于数据库信息下方）
        self.main_tabs = QTabWidget()
        excel_page = QWidget()
        excel_lay = QVBoxLayout(excel_page)

        # 2.0 当前目标库状态条：与「SQL 脚本改名」页顶部同源，随目标库选择联动刷新
        self.lbl_imp_db = QLabel()
        self.lbl_imp_db.setTextInteractionFlags(Qt.TextSelectableByMouse)
        excel_lay.addWidget(self.lbl_imp_db)

        # 2.1 文件拖拽区
        self.drop = DropArea()
        self.drop.files_dropped.connect(self.on_files_dropped)
        self.drop.clicked.connect(self.on_add_files)

        # 3. 文件列表
        file_box = QGroupBox(FILE_TABLE_TITLE)
        fl = QVBoxLayout(file_box)

        # 命名规则工具条贴表格上沿：它管的是"表名/字段名怎么起"，与表格内容同组，
        # 但不占下面前缀行——前缀行要留给前缀输入框与两颗按钮。
        # 两颗都默认勾上：新加的文件直接按「首字母 + 大写」起名。
        # 在连信号之前设好，免得启动时凭空打一行"切换"日志
        name_row = QHBoxLayout()
        name_row.addWidget(QLabel("命名规则："))
        self.chk_initials = QCheckBox("表名与字段名取拼音首字母")
        self.chk_initials.setChecked(True)
        self.chk_initials.setToolTip(
            "由中文转写的表名与字段名用拼音首字母（订单金额 → ddje、\n"
            "水库基础信息 → skjcxx）；默认已勾选，取消则用全拼（ding_dan_jin_e）。\n"
            "文件名括号里显式写下的英文表名不受影响；对之后添加的文件生效。")
        self.chk_initials.toggled.connect(self.on_initials_toggled)
        self.chk_upper = QCheckBox("表名与字段名转大写")
        self.chk_upper.setChecked(True)
        self.chk_upper.setToolTip(
            "由中文或文件名转写出来的表名与字段名全部大写\n"
            "（ding_dan_jin_e → DING_DAN_JIN_E、水库基础信息 → SHUI_KU_JI_CHU_XIN_XI）；\n"
            "默认已勾选，取消则用小写。文件名括号里写下的英文表名同样转大写；\n"
            "结构定义 Sheet 里人工写的字段名与手动改过的表名前缀原样保留；\n"
            "对之后添加的文件生效（表名前缀按输入原样拼接，不跟着转）。")
        self.chk_upper.toggled.connect(self.on_upper_toggled)
        name_row.addWidget(self.chk_initials)
        name_row.addWidget(self.chk_upper)
        name_row.addStretch(1)
        fl.addLayout(name_row)

        self.file_table = EmptyHintTable(
            0, 7, "请把 Excel 文件或 ZIP 压缩包拖到上方虚线区")
        style_table_font(self.file_table)
        self.file_table.setHorizontalHeaderLabels(
            ["序号", "文件", "表名", "表注释", "字段数", "数据行数", "主键"])
        # 列宽可自由拖动调节；最后一列自动填满剩余空间
        header = self.file_table.horizontalHeader()
        header.setSectionResizeMode(QHeaderView.Interactive)
        header.setStretchLastSection(True)
        self.file_table.setColumnWidth(0, 44)
        self.file_table.setColumnWidth(1, 320)
        self.file_table.setColumnWidth(2, 200)
        self.file_table.setColumnWidth(3, 280)
        self.file_table.setColumnWidth(4, 80)
        self.file_table.setColumnWidth(5, 90)
        self.file_table.setColumnWidth(6, 160)
        self.file_table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        # 单击「表名」直接进编辑器改这一张表的名字；双击其余列看这一行的结构。
        # 触发器关掉仍能 editItem，和 SQL 页同一个做法，避免单击别的列也弹编辑框。
        self.file_table.cellClicked.connect(self.on_file_table_clicked)
        self.file_table.cellDoubleClicked.connect(self.on_edit_structure_row)
        self.file_table.itemChanged.connect(self.on_file_table_item_changed)
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
        fl.addLayout(prefix_row)

        btns = QHBoxLayout()
        self.btn_clear = QPushButton("清空待上传文件")
        self.btn_clear.clicked.connect(self.on_clear)
        self.btn_preview = QPushButton("生成表结构预览")
        self.btn_preview.clicked.connect(self.on_preview_structure)
        self.btn_ddl = QPushButton("预览 DDL")
        self.btn_ddl.clicked.connect(self.on_preview_ddl)
        # 三颗都是次级操作：等宽铺满整行，不跟底部的执行按钮抢注意力
        btns.addWidget(self.btn_clear, 1)
        btns.addWidget(self.btn_preview, 1)
        btns.addWidget(self.btn_ddl, 1)
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
        self.log.setFont(QFont("Consolas", LOG_FONT_SIZE))
        self.log.setMinimumHeight(log_min_height(self.log))
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
        self.excel_split.setStretchFactor(1, 3)
        self.excel_split.setStretchFactor(2, 2)
        # 表格是主工作面：文件表那段还要装组框标题与两排操作按钮，110:200:330 时
        # 默认窗口里表格视口实测只剩 37px（不到两行）。日志默认只占它自己的最小
        # 高度（4 行），其余都给表格；真布局出来一次后按绝对高度摆（见 LogMinPlacer），
        # 之后仍可自由拖动分隔条
        self.excel_split.setSizes([110, 360, 200])
        self.excel_log_placer = LogMinPlacer(self.excel_split, log_panel)
        excel_lay.addWidget(self.excel_split, 1)

        # 6. 执行与输出（贴在窗口底部的动作条）
        # 左列是执行选项与页内进度，右侧才是动作按钮：进度跟着执行走，放在同一层；
        # 主按钮用强调色，停止与两颗生成按钮保持普通样式，一屏里只留一个视觉重点。
        excel_lay.addWidget(hline())
        self.excel_action_bar = ActionBar()
        op = QHBoxLayout(self.excel_action_bar)
        op.setContentsMargins(10, 6, 10, 6)
        op_left = QVBoxLayout()
        op_left.setSpacing(2)
        self.chk_drop = QCheckBox("覆盖已存在表（先 DROP 再建）")
        op_left.addWidget(self.chk_drop)
        self.progress = QProgressBar()
        self.progress.setValue(0)
        self.progress.setMinimumWidth(160)
        op_left.addWidget(self.progress)
        op.addLayout(op_left, 1)
        op.addSpacing(12)
        self.btn_import = QPushButton("开始导入")
        self.btn_import.setEnabled(False)
        self.btn_import.setStyleSheet(PRIMARY_BUTTON_QSS)
        self.btn_import.clicked.connect(self.on_start_import)
        self.btn_stop = QPushButton("停止")
        self.btn_stop.setEnabled(False)
        self.btn_stop.clicked.connect(self.on_stop)
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
        excel_lay.addWidget(self.excel_action_bar)

        self.main_tabs.addTab(excel_page, "Excel 导入")
        self.main_tabs.addTab(self._build_sql_page(), "SQL 脚本改名")
        self.main_tabs.addTab(self._build_sql_run_page(), "SQL 执行器")
        root.addWidget(self.main_tabs, 1)
        # 切页签 / 切换目标库 / 改主机端口时，刷新状态栏与三页顶部那条指向
        #（host:port 只在状态栏的悬停提示里，改了主机端口要同步，否则提示的是旧地址）
        self.main_tabs.currentChanged.connect(self._on_tab_changed)
        self.cb_db.currentIndexChanged.connect(self._on_db_changed)
        self.ed_host.textChanged.connect(self._refresh_db_labels)
        self.ed_port.valueChanged.connect(self._refresh_db_labels)
        # 启动即收起（用户口径：默认收起连接数据源信息）；要填连接点「设置数据源信息」展开，
        # 选定库之后也自动收起
        self.set_conn_expanded(False)
        self._refresh_db_labels()

    # -------------------- 当前目标库状态条（一条总状态 + 三页各一条动作指向） --------------------
    def set_conn_expanded(self, expanded):
        """连接表单展开/收起：收起后整块环境信息只剩一条"当前是哪个库"的状态栏。"""
        self._conn_expanded = bool(expanded)
        self.conn_form.setVisible(expanded)
        self.btn_conn_edit.setVisible(not expanded)
        # 状态栏那句告警要跟着换说法：展开时让人往下填，收起时才让人点「设置数据源信息」
        self._refresh_db_labels()

    def _on_tab_changed(self, index):
        self._refresh_db_labels()

    def _on_db_changed(self, _index):
        self._refresh_db_labels()
        # 选定库之后环境信息就没用了，自动收起；要换库再点「设置数据源信息」
        if self.cb_db.currentData() or self.cb_db.currentText().strip():
            self.set_conn_expanded(False)

    def _refresh_db_labels(self):
        cfg = self._conn_cfg()
        db = self.cb_db.currentData() or self.cb_db.currentText().strip()
        if db:
            self.lbl_conn_state.setText("● 目标库 %s" % db)
            self.lbl_conn_state.setToolTip("%s@%s:%s（用户 %s）"
                                           % (db, cfg["host"], cfg["port"], cfg["user"]))
            state_style = "color: #1a7f37; font-weight: bold;"
            head = "目标库：%s —— " % db
            tails = ["Excel 数据将导入到这个库", "解析并修改ddl脚本",
                     "编辑区 SQL 与脚本文件都执行到这个库"]
        else:
            # 告警条的说法要看当前是展开还是收起：让人去点的那颗按钮必须真的在界面上
            if self._conn_expanded:
                self.lbl_conn_state.setText("⚠ 未选择目标库——填好下面连接信息后点「连接并加载库」")
            else:
                self.lbl_conn_state.setText("⚠ 未选择目标库——点右侧「设置数据源信息」展开后填写")
            self.lbl_conn_state.setToolTip(NO_DB_HINT)
            state_style = "color: #c62828; font-weight: bold;"
            head = None
            tails = [None, None, None]
        self.lbl_conn_state.setStyleSheet(state_style)
        for lbl, tail in zip((self.lbl_imp_db, self.lbl_sql_db, self.lbl_run_db), tails):
            if tail is None:
                # 用户口径：未选库的提示只留顶部状态栏一处，页内三条不再重复告警
                lbl.setText("")
                lbl.setStyleSheet("")
                lbl.setVisible(False)
                continue
            lbl.setText(head + tail)
            lbl.setStyleSheet(state_style)
            lbl.setVisible(True)

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

    # -------------------- 长任务：按资源占用，一个任务一个非模态框 --------------------
    RES_EXCEL = "excel"
    RES_SQL = "sql"
    RES_RUN = "run"
    RES_LABEL = {
        "excel": "Excel 导入页的待导入列表",
        "sql": "SQL 脚本改名页的脚本列表",
        "run": "SQL 执行器页的执行结果区（编辑区 SQL 与脚本清单共用）",
    }

    def _resources_for(self, log_view):
        """任务占哪些资源：按日志去向认出是哪一页发起的。

        拆资源而不是拆页面：一页的两个任务会踩同一份列表或同一段编辑区，所以互斥；
        两页的「生成标准/简易excel文件」现在各写自己的目录（Excel 页 meta_data_file_…、
        SQL 页 sql_meta_data_file_…），不再抢同一个目录，也就可以并行。
        """
        if log_view is self.sql_log:
            base = self.RES_SQL
        elif log_view is self.sql_run_log:
            base = self.RES_RUN
        else:
            base = self.RES_EXCEL
        return (base,)

    def _conflict(self, resources):
        for task in self._tasks:
            for res in task.resources:
                if res in resources:
                    return task
        return None

    def task_busy(self, resources=None):
        """有没有任务在跑；给了 resources 就只问这几个资源是否被占。"""
        if resources is None:
            return bool(self._tasks)
        return self._conflict(tuple(resources)) is not None

    @property
    def task_dialog(self):
        """最近那个任务的进度框（用例与旧代码按这个名字取框）。"""
        return self._tasks[-1].dialog if self._tasks else None

    def _busy_text(self, other):
        shared = "、".join(self.RES_LABEL.get(r, r) for r in other.resources)
        return ("已有任务在跑：" + other.describe()
                + "。这一步没执行——它和那个任务共用「%s」，等它跑完或点进度框「取消」。" % shared)

    def _place_task_dialog(self, dlg):
        """多个进度框错开摆：都居中在主人窗口上会叠成一颗，后面那颗的「取消」点不到。

        往右下各错一格，竖直那一步取"框自己的高再加 20"，所以几颗不会压住对方。
        偏移量按"此刻有几个框在屏"算，不按任务累计序号——任务号会一直涨，
        跑上几轮就把框推到屏幕外去了。
        """
        shown = sum(1 for t in self._tasks
                    if t.dialog is not None and t.dialog is not dlg and t.dialog.isVisible())
        avail = QApplication.primaryScreen().availableGeometry()
        x = self.x() + 40 + shown * 40
        # 竖直那一步要大于框自己的高，不然两颗照样上下叠住（Java 侧自检实测到过）
        y = self.y() + 90 + shown * (dlg.height() + 20)
        dlg.move(max(avail.left() + 8, min(x, avail.right() - dlg.width() - 8)),
                 max(avail.top() + 8, min(y, avail.bottom() - dlg.height() - 8)))

    def _run_modal(self, title, work, args=(), on_done=None, log_view=None):
        """统一入口：把纯工作函数 work(sink, *args) 放到子线程跑，弹一个非模态进度框。

        非模态是为了不挡窗口：框挡在中间时另外两页根本点不动，用户只能等或者强关窗口。
        同一页的任务互斥、不同页可以并行，每个任务自己的框显示自己的进度。
        MODAL_PROGRESS=False（离屏用例）时不起线程、不弹框，原地跑完再回调，
        两条路径共用同一个 work 和同一个 TaskInfo，测试跑到的就是线上那段逻辑。
        """
        view = log_view if log_view is not None else self.log
        resources = self._resources_for(view)
        other = self._conflict(resources)
        if other is not None:
            # 静默 return 是最坏的写法：用户点了没反应，也不知道在等谁
            self._warn(self._busy_text(other))
            return
        task = TaskInfo(self, resources, title, view, on_done)
        self._tasks.append(task)
        self._sync_gen_buttons()
        if not MODAL_PROGRESS:
            sink = ProgressSink(on_progress=task.set_progress, on_log=task.log,
                                on_log_error=task.log_error)
            try:
                task.result = work(*args, sink)
            except Exception as e:
                task.failed(describe_db_error(e))
            self._finish_task(task)
            return
        dlg = ProgressDialog(title, self)
        worker = ProgressWorker(work, args)
        task.dialog, task.worker = dlg, worker
        worker.progress.connect(task.set_progress)
        worker.log.connect(task.log)
        worker.log_error.connect(task.log_error)
        worker.failed.connect(task.failed)
        worker.completed.connect(task.completed)
        worker.finished.connect(task.thread_done)
        dlg.btn_cancel.clicked.connect(worker.cancel)
        worker.start()
        self._place_task_dialog(dlg)
        # show() 而不是 exec()：主线程不再停在嵌套循环里，别的页签照常能用
        dlg.show()
        dlg.raise_()
        dlg.activateWindow()

    def _finish_task(self, task):
        """一个任务收尾：把结果交给它自己的回调（返回 None 视为没做成，不回调）。"""
        if task in self._tasks:
            self._tasks.remove(task)
        cb, result = task.on_done, task.result
        task.on_done, task.result, task.worker = None, None, None
        if result is not None and cb:
            cb(result)
        self._sync_gen_buttons()

    def _sync_gen_buttons(self):
        """两个生成按钮只在"有表可导、且这一页没有任务在跑"时可点。

        判据取自同一处而不是各写各的：任务跑着的时候表列表正被工作线程读，
        这时候再生成一份就是两个任务共用同一份数据互相踩。别的页在跑不影响这一页。
        """
        on = bool(self.tables) and not self.task_busy((self.RES_EXCEL,))
        self.btn_gen_std.setEnabled(on)
        self.btn_gen_simple.setEnabled(on)
        # 保存/执行只看清单里有没有脚本：忙的时候点下去由任务位拦下并说清在等谁，
        # 比跟着一起灰更好判读；清单空了则没有任何可写/可执行的对象，必须灰
        has_sql = bool(self.sql_files)
        self.btn_sql_save.setEnabled(has_sql)
        self.btn_sql_run.setEnabled(has_sql)
        # 这一页的判据是"有没有脚本"，不是"有没有解析出表"：
        # 解析在点下去之后才做，解析不出东西时日志会给话，按钮不该一直灰着让人以为坏了
        sql_on = bool(self.sql_files) and not self.task_busy((self.RES_SQL,))
        self.btn_sql_gen_std.setEnabled(sql_on)
        self.btn_sql_gen_simple.setEnabled(sql_on)
        # 第三页的「执行脚本文件」判据与这里同一口径：有脚本、且这一页没任务在跑
        run_on = bool(self.sql_run_files) and not self.task_busy((self.RES_RUN,))
        self.btn_run_scripts.setEnabled(run_on)

    # -------------------- 槽函数 --------------------
    def on_clear_log(self):
        self.log.clear()

    # -------------------- SQL 脚本改名页 --------------------
    def _build_sql_page(self):
        page = QWidget()
        lay = QVBoxLayout(page)

        # 当前目标库状态条：跟随上方公共连接区的库选择联动刷新
        self.lbl_sql_db = QLabel()
        self.lbl_sql_db.setTextInteractionFlags(Qt.TextSelectableByMouse)
        lay.addWidget(self.lbl_sql_db)

        # 添加文件这一路与「Excel 导入」那页统一：拖进来或点这块区域选文件，
        # 不再单独放一颗「添加 SQL 文件」按钮（两页各一套入口只会让人找两遍）
        self.sql_drop = DropArea(hint=DropArea.SQL_HINT,
                                 tooltip="点击选择 SQL 脚本；也可以直接拖到这里")
        self.sql_drop.files_dropped.connect(self.on_sql_files_dropped)
        self.sql_drop.clicked.connect(self.on_sql_add_files)

        # 命名规则工具条贴表格上沿：与第一页同一位置；这一页只有大小写一颗。
        # 默认不勾选。勾选后表名清单、元数据弹窗和生成件都按大写显示，
        # 但脚本正文仍保持原样，避免用户只是想看一份大写清单却把源文件改掉。
        name_bar = QHBoxLayout()
        name_bar.addWidget(QLabel("命名规则："))
        self.chk_sql_upper = QCheckBox("表名与字段名转大写")
        self.chk_sql_upper.setToolTip(
            "勾选后，「当前表名」清单、双击打开的表元数据以及生成的 Excel 里的表名与字段名全部大写\n"
            "（t_order → T_ORDER、order_amount → ORDER_AMOUNT）；默认不勾选。\n"
            "脚本正文不改，手动改过的新表名会保留其输入内容。")
        self.chk_sql_upper.toggled.connect(self.on_sql_upper_toggled)
        name_bar.addWidget(self.chk_sql_upper)
        name_bar.addStretch(1)

        self.sql_table = EmptyHintTable(0, 5, "请把 SQL 脚本拖到上方虚线区")
        style_table_font(self.sql_table)
        # 「CREATE 出现次数」这一列去掉：同一份脚本里一张表被写两遍，日志已经逐条点名，
        # 现场清单里那一列全是 1，占着宽度只让人以为坏了；表注释是"这张表是干什么的"，
        # 与「Excel 导入」那页同一列同名同位置，导入前先在这张表上就能核对
        self.sql_table.setHorizontalHeaderLabels(
            ["序号", "文件", "原表名", "当前表名", "表注释"])
        header = self.sql_table.horizontalHeader()
        header.setSectionResizeMode(QHeaderView.Interactive)
        header.setStretchLastSection(True)
        self.sql_table.setColumnWidth(0, 44)
        self.sql_table.setColumnWidth(1, 300)
        self.sql_table.setColumnWidth(2, 260)
        self.sql_table.setColumnWidth(3, 200)
        self.sql_table.setColumnWidth(4, 280)
        self.sql_table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.sql_table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.sql_table.itemDoubleClicked.connect(self.on_sql_table_double_clicked)
        self.sql_table.itemChanged.connect(self.on_sql_table_item_changed)
        # 表格与下面的两行操作条作为一个整体参与高度缩放，先不挂到页面布局上

        prefix_row = QHBoxLayout()
        prefix_row.addWidget(QLabel("表名前缀："))
        self.ed_sql_prefix = QLineEdit()
        self.ed_sql_prefix.setPlaceholderText(
            "如 dim_（加前缀，已带该前缀的表自动跳过）；填 change 再点"
            "「替换首个前缀」则把 ods_t_order 换成 change_t_order。"
            "清空后再点一次可恢复原表名")
        self.btn_sql_prefix = QPushButton("批量应用前缀")
        self.btn_sql_prefix.setToolTip(
            "只改「当前表名」列的显示（双击看的元数据表名同步），脚本正文不动；\n"
            "点保存/执行/生成时才把显示名写进脚本。前缀留空再点一次，显示恢复为脚本原名。")
        self.btn_sql_prefix.clicked.connect(self.on_sql_apply_prefix)
        self.btn_sql_replace = QPushButton("替换首个前缀")
        self.btn_sql_replace.setToolTip(
            "把每个表名第一个下划线之前的部分换成上面填的内容，只改页面显示；\n"
            "点保存/执行/生成时才写进脚本并把 CREATE/DROP/INSERT/ALTER/TRUNCATE 的引用一起改掉；\n"
            "表名里没有下划线的不动。末尾下划线不用写，留空再点一次恢复原表名")
        self.btn_sql_replace.clicked.connect(self.on_sql_replace_prefix)
        prefix_row.addWidget(self.ed_sql_prefix, 1)
        prefix_row.addWidget(self.btn_sql_prefix)
        prefix_row.addWidget(self.btn_sql_replace)

        # 清空按钮排在表格下面第一排的最左（前缀行的下一行），
        # 与「Excel 导入」那页「清空待上传文件」同一位置同一语义：整份清出清单，不是只删选中行。
        # 不塞进保存/执行那一排——那排的最小宽度已经贴着窗口宽度，再加一颗就顶出屏幕
        # （U7 那条"整窗落在可用区内"实测被顶到 848 > 可用 800）。
        name_row = QHBoxLayout()
        self.btn_sql_clear = QPushButton("清空待上传文件")
        self.btn_sql_clear.clicked.connect(self.on_sql_clear_files)
        name_row.addWidget(self.btn_sql_clear)
        name_row.addStretch(1)

        self.btn_sql_save = QPushButton("保存修改后ddl脚本")
        self.btn_sql_save.setEnabled(False)
        self.btn_sql_save.setStyleSheet(SECONDARY_BUTTON_QSS)
        self.btn_sql_save.clicked.connect(self.on_sql_save_modified)
        self.btn_sql_run = QPushButton("执行修改后ddl脚本到当前库")
        self.btn_sql_run.setEnabled(False)
        self.btn_sql_run.setStyleSheet(PRIMARY_BUTTON_QSS)
        self.btn_sql_run.clicked.connect(self.on_sql_execute)
        # 两颗生成按钮排在「执行修改后ddl脚本到当前库」右侧：与 Excel 导入页那两颗同一排同一口径，
        # 数据源换成了脚本里的 CREATE TABLE，产物形状不变
        self.btn_sql_gen_std = QPushButton("生成标准excel文件")
        self.btn_sql_gen_std.setToolTip(
            "在程序同级建 sql_meta_data_file_年月日 目录，每张表一个 xlsx：\n"
            "Sheet1「元数据」是脚本里解析出的字段定义，Sheet2「数据」只有表头\n"
            "（脚本里不含数据行，这一张是给下游填数据的模板）\n"
            "目录名带 sql_ 前缀，与「Excel 导入」那一页的生成件互不覆盖")
        self.btn_sql_gen_std.setEnabled(False)
        self.btn_sql_gen_std.clicked.connect(lambda: self.on_sql_gen_excel("标准", True))
        self.btn_sql_gen_simple = QPushButton("生成简易excel文件")
        self.btn_sql_gen_simple.setToolTip(
            "在程序同级建 sql_simple_meta_data_file_年月日 目录，每张表一个 xlsx：\n"
            "只留「数据」一个 Sheet，里面只有脚本里那些字段的英文名表头\n"
            "目录名带 sql_ 前缀，与「Excel 导入」那一页的生成件互不覆盖")
        self.btn_sql_gen_simple.setEnabled(False)
        self.btn_sql_gen_simple.clicked.connect(lambda: self.on_sql_gen_excel("简易", False))

        # 日志：清除按钮在日志框上方、贴右，与第一页同一排布
        self.btn_sql_clear_log = QPushButton("清除日志")
        self.btn_sql_clear_log.clicked.connect(self.on_sql_clear_log)
        log_row = QHBoxLayout()
        log_row.addStretch(1)
        log_row.addWidget(self.btn_sql_clear_log)

        self.sql_log = QPlainTextEdit()
        self.sql_log.setReadOnly(True)
        self.sql_log.setMaximumBlockCount(2000)
        self.sql_log.setFont(QFont("Consolas", LOG_FONT_SIZE))
        self.sql_log.setMinimumHeight(log_min_height(self.sql_log))
        log_panel = QWidget()
        lp = QVBoxLayout(log_panel)
        lp.setContentsMargins(0, 0, 0, 0)
        lp.addLayout(log_row)
        lp.addWidget(self.sql_log, 1)

        # 三段可上下拖拽，与「Excel 导入」那页同一形状：拖拽区 / 表格与它的两行操作条 / 日志。
        # 把手 6px、任何一段都不许拖到 0 高；表格段给到最大，理由与第一页那条一样。
        # 表格区套一层组框，标题就是这张表的标识——与第一页「待导入文件」同一做法
        sql_upper = QGroupBox(SQL_TABLE_TITLE)
        up_lay = QVBoxLayout(sql_upper)
        up_lay.setContentsMargins(0, 0, 0, 0)
        up_lay.addLayout(name_bar)
        up_lay.addWidget(self.sql_table, 1)
        up_lay.addLayout(prefix_row)
        up_lay.addLayout(name_row)

        self.sql_split = QSplitter(Qt.Vertical)
        self.sql_split.setChildrenCollapsible(False)     # 不允许把任一边拖到 0 高
        self.sql_split.setHandleWidth(6)
        self.sql_split.addWidget(self.sql_drop)
        self.sql_split.addWidget(sql_upper)
        self.sql_split.addWidget(log_panel)
        self.sql_split.setStretchFactor(0, 0)
        self.sql_split.setStretchFactor(1, 3)
        self.sql_split.setStretchFactor(2, 2)
        # 日志默认只占它自己的最小高度（4 行），其余都给表格；真布局出来一次后
        # 按绝对高度摆（见 LogMinPlacer），之后仍可自由拖动分隔条
        self.sql_split.setSizes([110, 360, 200])
        self.sql_log_placer = LogMinPlacer(self.sql_split, log_panel)
        lay.addWidget(self.sql_split, 1)

        # 底部动作条：与第一页同一形状——覆盖开关在左，动作按钮在右（在分隔条之外，不随表格被挤）
        self.sql_action_bar = ActionBar()
        op = QHBoxLayout(self.sql_action_bar)
        op.setContentsMargins(10, 6, 10, 6)
        self.chk_sql_drop = QCheckBox("覆盖已存在表（先 DROP 再建）")
        self.chk_sql_drop.setToolTip(
            "勾选后，执行前先对脚本里每条 CREATE TABLE 的建表目标补一句\n"
            "DROP TABLE IF EXISTS；只认整句以 CREATE TABLE 开头的语句，\n"
            "注释或字符串里出现的建表不会触发删除。只影响执行，不改脚本正文，\n"
            "也不影响保存与生成两条出口；默认不勾选。")
        op.addWidget(self.chk_sql_drop)
        # 四颗按钮的总宽比第一页多两颗，直接摊在一行会把主窗口的最小宽度顶到屏幕外：
        # 套一层横向滚动区，正常宽度下照样贴右排开，窄屏才出滚动条，窗口仍能缩回屏幕内
        run_box = QWidget()
        run_row = QHBoxLayout(run_box)
        run_row.setContentsMargins(0, 0, 0, 0)
        run_row.setSpacing(6)
        run_row.addStretch(1)
        run_row.addWidget(self.btn_sql_save)
        run_row.addWidget(self.btn_sql_run)
        run_row.addWidget(self.btn_sql_gen_std)
        run_row.addWidget(self.btn_sql_gen_simple)
        run_scroll = QScrollArea()
        run_scroll.setFrameShape(QFrame.NoFrame)
        run_scroll.setWidgetResizable(True)
        run_scroll.setVerticalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        run_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAsNeeded)
        run_scroll.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        # 视口默认铺一层窗口底色，在这条动作带上会显成一条灰带：只留按钮本身
        run_scroll.setStyleSheet(
            "QScrollArea, QScrollArea > QWidget > QWidget { background: transparent; }")
        run_scroll.setWidget(run_box)
        op.addWidget(run_scroll, 1)
        # 执行层与上方表格区之间的细分隔线：底部动作条不再和表格内容糊成一片
        lay.addWidget(hline())
        lay.addWidget(self.sql_action_bar)
        return page

    def _sql_display_name(self, actual):
        return actual.upper() if self.chk_sql_upper.isChecked() else actual

    def _refresh_sql_table(self):
        self._refreshing_sql_table = True
        try:
            rows = []
            for s in self.sql_files:
                base = os.path.basename(s["path"])
                rev = {v: k for k, v in s["rename_map"].items()}
                display_map = s.get("display_map") or {}
                parsed = ensure_parsed(s)
                # 表名清单在读取/改写时就已算好，几十万行的脚本不再回扫一遍
                for t in s.get("tabs") or extract_create_tables(s["text"]):
                    rows.append({"path": s["path"], "base": base,
                                 "orig": rev.get(t["name"], t["name"]),
                                 "actual": t["name"],
                                 "display": self._sql_display_name(
                                     display_map.get(t["name"], t["name"])),
                                 "count": str(t["count"]),
                                 "comment": (parsed.get(t["name"]) or {})
                                 .get("table_comment") or ""})
            self._sql_rows = rows
            self._sql_display_names = [r["display"] for r in rows]
            self.sql_table.setRowCount(len(rows))
            for i, row in enumerate(rows):
                items = [QTableWidgetItem(str(i + 1)), QTableWidgetItem(row["base"]),
                         QTableWidgetItem(row["orig"]), QTableWidgetItem(row["display"]),
                         QTableWidgetItem(row["comment"])]
                items[1].setData(Qt.UserRole, row["path"])
                items[1].setToolTip(row["path"])
                items[4].setToolTip(row["comment"])
                for j, it in enumerate(items):
                    flags = Qt.ItemIsEnabled | Qt.ItemIsSelectable
                    if j in (3, 4):
                        flags |= Qt.ItemIsEditable
                    it.setFlags(flags)
                for j, it in enumerate(items):
                    self.sql_table.setItem(i, j, it)
        finally:
            self._refreshing_sql_table = False

    def on_sql_table_double_clicked(self, item):
        """当前表名与表注释两列交给编辑器；其余列只是查看这一行对应表的元数据。"""
        if item is None or self.task_busy((self.RES_SQL,)):
            return
        row = item.row()
        if item.column() in (3, 4):
            self.sql_table.editItem(item)
            return
        self.show_sql_table_metadata(row)

    def show_sql_table_metadata(self, row_index):
        dlg = self._build_sql_metadata_dialog(row_index)
        if dlg is not None:
            dlg.exec()

    def _build_sql_metadata_dialog(self, row_index):
        if row_index < 0 or row_index >= len(self._sql_rows):
            return None
        row = self._sql_rows[row_index]
        record = next((s for s in self.sql_files if s["path"] == row["path"]), None)
        if record is None:
            return None
        # 读入时已经整份解析过（sql_load_task），这里只按脚本里现在这个名字取那一张；
        # 补回去那一步让"第二次点同一份脚本"不用再等 1.8 秒
        table = ensure_parsed(record).get(row["actual"])
        if table is None:
            append_log(self.sql_log, 
                "[元数据] 未找到表 %s 的定义（可能被后续语句改写）" % row["actual"])
            return None
        return SqlMetadataDialog(table, row["display"],
                                 self.chk_sql_upper.isChecked(), self,
                                 on_edit=functools.partial(self._apply_sql_field_edit,
                                                           row_index))

    def _apply_sql_field_edit(self, row_index, field, **kw):
        """表元数据弹窗改了一格：把这一列的新写法写进脚本正文那句 CREATE TABLE。

        返回 True/False，弹窗据此决定要不要把那一格填回原值；拒绝的原因一律点名，
        不静默吞掉一次编辑（改不动总要让人看得见为什么改不动）。
        """
        if row_index >= len(self._sql_rows):
            return False
        row = self._sql_rows[row_index]
        record = next((s for s in self.sql_files if s["path"] == row["path"]), None)
        if record is None:
            return False
        table = ensure_parsed(record).get(row["actual"])
        col = next((c for c in ((table or {}).get("columns") or [])
                    if str(c.get("field", "")).lower() == str(field).lower()), None)
        if col is None:
            return False
        new_text, changed, reason = edit_column(record["text"], row["actual"], field, **kw)
        if not changed:
            if reason:
                self._warn("字段「%s」没改：\n%s" % (field, reason))
            return False
        record["text"] = new_text
        record["modified"] = True
        # 解析缓存就地跟上，省掉整份脚本重扫（一份 dump 有几十万行）
        before = (col.get("field"), col.get("comment") or "", col.get("type") or "")
        for key, attr in (("new_field", "field"), ("new_comment", "comment"),
                          ("new_type", "type")):
            if key in kw:
                col[attr] = str(kw[key]).strip()
        self._refresh_sql_table()
        parts = []
        if "new_field" in kw:
            parts.append("字段名 %s → %s" % (before[0], col["field"]))
        if "new_comment" in kw:
            parts.append("注释 %s → %s"
                         % (before[1] or "（空）", str(kw["new_comment"]).strip() or "（空）"))
        if "new_type" in kw:
            parts.append("类型 %s → %s" % (before[2], col["type"]))
        append_log(self.sql_log,
                   "[字段改写] 表 `%s` 的 %s（已写进脚本；未落盘）"
                   % (row["actual"], "，".join(parts)))
        return True

    def on_sql_table_item_changed(self, item):
        """手动编辑「当前表名」或「表注释」会到这里；两者都同步改写脚本正文。"""
        if self._refreshing_sql_table or item.column() not in (3, 4):
            return
        if item.column() == 3:
            self._apply_sql_row_name(item.row(), item.text())
        else:
            self._apply_sql_row_comment(item.row(), item.text())

    def _apply_sql_row_comment(self, row_index, typed):
        """表注释改了就写进那句 CREATE TABLE 的表选项，日志说清改的是哪一张表。"""
        if row_index >= len(self._sql_rows):
            return
        row = self._sql_rows[row_index]
        record = next((s for s in self.sql_files if s["path"] == row["path"]), None)
        if record is None:
            return
        new = str(typed or "").strip()
        if new == row["comment"]:
            append_log(self.sql_log,
                       "[表注释] %s 未发生变化，脚本未改写" % row["actual"])
            self._refresh_sql_table()
            return
        new_text, changed = set_table_comment(record["text"], row["actual"], new)
        if not changed:
            self._warn("表「%s」那句 CREATE TABLE 没有可改写的表选项"
                       "（LIKE / AS SELECT 写法不带表选项），脚本未改。" % row["actual"])
            self._refresh_sql_table()
            return
        record["text"] = new_text
        record["modified"] = True
        table = ensure_parsed(record).get(row["actual"])
        if table is not None:
            table["table_comment"] = new
        self._refresh_sql_table()
        append_log(self.sql_log,
                   "[表注释] %s → %s（已写进脚本；未落盘）" % (row["actual"], new or "（空）"))

    def _apply_sql_row_name(self, row_index, typed):
        """只有手动编辑「当前表名」会到这里；同步改脚本引用，日志说清改了多少处。"""
        if row_index >= len(self._sql_rows):
            return
        row = self._sql_rows[row_index]
        record = next((s for s in self.sql_files if s["path"] == row["path"]), None)
        if record is None:
            return
        new = typed.strip().strip("`")
        old = row["actual"]
        old_display = row["display"]
        if new == old_display:
            self._refresh_sql_table()
            return
        if not new:
            self._warn("表名不能为空，已恢复原表名「%s」。" % old)
            self._refresh_sql_table()
            return
        if new == old:
            # 手填脚本里的原名 = 只撤掉这一行的显示前缀，正文和修改标记都不动
            record.setdefault("display_map", {})
            if record["display_map"].pop(old, None) is not None:
                self._refresh_sql_table()
                append_log(self.sql_log, 
                    "[表名] %s 的显示前缀已移除，脚本未改写" % old)
            else:
                append_log(self.sql_log, "[表名] %s 未发生变化，脚本未改写" % old)
                self._refresh_sql_table()
            return
        new_text, changed = rename_table_sql(record["text"], old, new)
        if not changed:
            self._warn("表名「%s」改成「%s」没找到可改写的 SQL 引用，脚本未改。" % (old, new))
            self._refresh_sql_table()
            return
        record["text"] = new_text
        record["tabs"] = extract_create_tables(new_text)
        record["parsed"] = rekey_parsed(record.get("parsed"), {old: new})
        record["modified"] = True
        record["rename_map"][row["orig"]] = new
        record.setdefault("display_map", {})
        record["display_map"].pop(old, None)
        record["display_map"].pop(new, None)
        self._refresh_sql_table()
        append_log(self.sql_log, 
            "[表名] %s → %s（已同步改写 %d 处引用；未落盘）" % (old, new, changed))

    def on_sql_upper_toggled(self, checked):
        self._refresh_sql_table()
        append_log(self.sql_log, 
            "[提示] 表名与字段名大小写切换为：%s（当前表名清单、表元数据和生成的 Excel 一起变；"
            "脚本正文不改）" % ("大写" if checked else "保持原样"))

    def on_sql_add_files(self):
        files, _ = QFileDialog.getOpenFileNames(
            self, "选择 SQL 脚本", "", "SQL 脚本 (*.sql);;所有文件 (*)")
        if files:
            self.add_sql_paths(files)

    def on_sql_files_dropped(self, paths):
        """拖进来的路径只收 .sql，与第一页同一句口径；混着别的文件时点名哪些没进去。"""
        keep = [p for p in paths if p.lower().endswith(".sql")]
        wrong = [p for p in paths if not p.lower().endswith(".sql")]
        if not keep:
            append_log(self.sql_log, "[提示] 拖入的不是 SQL 脚本文件，已忽略。")
            return
        if wrong:
            append_log(self.sql_log,
                       "[跳过] 不是 SQL 脚本，未读入：%s"
                       % "、".join(os.path.basename(p) for p in wrong))
        self.add_sql_paths(keep)

    def add_sql_paths(self, files):
        """点击选择与拖拽共用这一个入口：先去重，再交给读盘子线程。"""
        existing = {s["path"] for s in self.sql_files}
        todo, dups = [], []
        for f in files:
            if f in existing:
                dups.append(f)
                continue
            todo.append(f)
        if not todo:
            for p in dups:
                append_log(self.sql_log, f"[跳过] 已在列表：{os.path.basename(p)}")
            self._refresh_sql_table()
            self._show_sql_add_result({"loaded": [], "failed": [], "duplicates": dups,
                                       "cancelled": False})
            return
        self._run_modal("正在读取 SQL 脚本…", sql_load_task, (todo, dups),
                        on_done=self._finish_sql_load, log_view=self.sql_log)

    def _finish_sql_load(self, res):
        self.sql_files.extend(res["loaded"])
        self._refresh_sql_table()
        self._show_sql_add_result(res)

    def _show_sql_add_result(self, res):
        """添加结束的弹窗：逐个列出完整路径，用户才分得清同名脚本来自哪个目录。"""
        loaded = res["loaded"]
        head = f"已加载 {len(loaded)} 个脚本，共 {sum(len(s['tabs']) for s in loaded)} 张表。"
        if res["cancelled"]:
            head += "（已取消，剩余文件未读入）"
        lines = [head]
        for s in loaded:
            lines.append(f"[已加载] {s['path']}：{len(s['tabs'])} 张表")
        for p in res["duplicates"]:
            lines.append(f"[跳过] {p}：已在列表")
        for f in res["failed"]:
            lines.append(f"[读取失败] {f['path']}：{' '.join(f['reason'].split())}")
        append_log(self.sql_log,
                   f"===== 添加完成：{len(loaded)} 个脚本，{len(res['failed'])} 个读取失败，"
                   f"{len(res['duplicates'])} 个重复 =====",
                   error=bool(res["failed"]))
        popup_box(self, "添加结果", "\n".join(lines),
                  "warning" if res["failed"] else "information")

    def on_sql_clear_files(self):
        """与第一页「清空待上传文件」同一语义：整份清出清单，磁盘上的脚本不动。"""
        n = len(self.sql_files)
        self.sql_files = []
        self._refresh_sql_table()
        self._sync_gen_buttons()
        if n:
            append_log(self.sql_log, f"[清空] 已清空 {n} 个脚本文件")
        else:
            append_log(self.sql_log, "[清空] 清单里没有脚本文件，无需清空")

    def _warn(self, text):
        """拦下一次点击：日志与弹窗双写同一句话。

        只弹窗不写日志的话，用户回头查日志找不到"刚才那下为什么没动"；
        Swing 那条线的 warn() 一直是两处都写，两条线要同一口径。
        """
        append_log(self.log, "已拦下：" + text)
        popup_box(self, "提示", text, "warning")

    def _sql_prefix_blocker(self):
        """两颗前缀按钮的共同前置检查：没有可处理的表就弹窗说清楚，不假装成功。"""
        if not self.sql_files:
            return "请先添加 SQL 脚本文件。"
        # 与 _refresh_sql_table 同口径：老记录可能没缓存 tabs，就地从正文补算
        if not any(s.get("tabs") or extract_create_tables(s["text"]) for s in self.sql_files):
            return ("列表里的脚本没有解析出 CREATE TABLE 表（空脚本或只有注释），"
                    "没有表名可处理。")
        return None

    def on_sql_apply_prefix(self):
        """只改页面显示：脚本正文一个字不动，四个动作按钮点下去时才写入。"""
        why = self._sql_prefix_blocker()
        if why:
            self._warn(why)
            return
        prefix = self.ed_sql_prefix.text().strip().replace("`", "").replace("\\", "")
        if not prefix:
            self._clear_sql_display_prefix()
            return
        total = 0
        for s in self.sql_files:
            fname = os.path.basename(s["path"])
            current = s.get("display_map") or {}
            plan, skipped = {}, []
            for t in s.get("tabs") or extract_create_tables(s["text"]):
                old = t["name"]
                shown = current.get(old, old)      # 接在页面上当前显示名之后，不从头计算
                if shown.startswith(prefix):
                    if shown != old:
                        plan[old] = shown          # 已带前缀：保留原显示，不叠也不丢
                    skipped.append(shown)
                    continue
                plan[old] = prefix + shown
            s["display_map"] = plan
            total += len(plan)
            if plan:
                append_log(self.sql_log, 
                    f"[前缀] {fname}："
                    + "、".join(f"{o} → {n}（显示）" for o, n in plan.items()))
            if skipped:
                append_log(self.sql_log, 
                    f"[跳过] {fname}：已带前缀 {'、'.join(skipped)}")
        self._refresh_sql_table()
        append_log(self.sql_log, 
            f"[前缀完成] 共 {total} 个表名改为显示前缀「{prefix}」"
            f"（脚本正文未改，点保存/执行/生成时才写入）")

    def _clear_sql_display_prefix(self):
        """空前缀再点一次：只撤掉显示层，表名回到脚本里的原名。"""
        n = 0
        for s in self.sql_files:
            n += len(s.get("display_map") or {})
            s["display_map"] = {}
        self._refresh_sql_table()
        if n:
            append_log(self.sql_log, 
                f"[前缀完成] 前缀已清空，{n} 个表名的显示恢复为脚本中的原名（脚本正文未改）")
        else:
            append_log(self.sql_log, 
                "[前缀完成] 前缀为空，表名显示本来就是脚本原名，无需恢复")

    def on_sql_replace_prefix(self):
        """首段前缀同样只改显示；空前缀视作撤掉显示层。"""
        why = self._sql_prefix_blocker()
        if why:
            self._warn(why)
            return
        if not self.ed_sql_prefix.text().strip():
            self._clear_sql_display_prefix()
            return
        head, why = self._clean_head(self.ed_sql_prefix.text())
        if head is None:
            self._warn(why)
            return
        total = 0
        for s in self.sql_files:
            fname = os.path.basename(s["path"])
            current = s.get("display_map") or {}
            plan, skipped = {}, []
            for t in s.get("tabs") or extract_create_tables(s["text"]):
                old = t["name"]
                shown = current.get(old, old)      # 与加前缀同一口径：基于当前显示名换首段
                new = swap_first_segment(shown, head)
                if new is None:
                    if shown != old:
                        plan[old] = shown          # 没换成（如首段本来就是这个）：保留原显示
                    skipped.append(shown)
                    continue
                plan[old] = new
            s["display_map"] = plan
            total += len(plan)
            if plan:
                append_log(self.sql_log, 
                    f"[换前缀] {fname}："
                    + "、".join(f"{o} → {n}（显示）" for o, n in plan.items()))
            if skipped:
                append_log(self.sql_log, 
                    f"[跳过] {fname}：未改写 {'、'.join(skipped)}（表名里没有下划线）")
        self._refresh_sql_table()
        append_log(self.sql_log, 
            f"[换前缀完成] 共 {total} 个表名改为显示名"
            f"（脚本正文未改，点保存/执行/生成时才写入）")

    def _materialize_sql_display(self):
        """把页面显示的表名写进脚本；保存/执行/生成三个动作共用这一道。"""
        pending = any(s.get("display_map") for s in self.sql_files)
        if not pending:
            return False
        for s in self.sql_files:
            plan = s.get("display_map") or {}
            if not plan:
                continue
            new_text, renamed, nohit = apply_display_renames(s["text"], plan)
            rev = {v: k for k, v in s["rename_map"].items()}
            for r in renamed:
                s["rename_map"][rev.get(r["old"], r["old"])] = r["new"]
            if renamed:
                s["text"] = new_text
                s["tabs"] = extract_create_tables(new_text)
                s["parsed"] = rekey_parsed(
                    s.get("parsed"), {r["old"]: r["new"] for r in renamed})
                s["modified"] = True
                append_log(self.sql_log, 
                    f"[生效] {os.path.basename(s['path'])}："
                    + "、".join(f"{r['old']} → {r['new']}（{r['n']} 处）" for r in renamed)
                    + " 已写入脚本")
            if nohit:
                append_log(self.sql_log, 
                    f"[跳过] {os.path.basename(s['path'])}：脚本里找不到 "
                    + "、".join(nohit) + " 的引用，未改写")
            s["display_map"] = {}
        self._refresh_sql_table()
        return True

    def on_sql_save_modified(self):
        self._materialize_sql_display()
        items = [(s["path"], s["text"]) for s in self.sql_files if s["modified"]]
        if not items:
            append_log(self.sql_log, "[保存] 没有修改过的脚本文件")
            popup_box(self, "保存完成", "没有修改过的脚本文件，无需保存。")
            return
        self._run_modal("正在保存修改后的脚本…", sql_save_task, (items,),
                        on_done=self._finish_sql_save, log_view=self.sql_log)

    def _finish_sql_save(self, res):
        done = {p for p, _ in res["saved"]}
        for s in self.sql_files:
            if s["path"] in done:
                s["modified"] = False
        n = len(done)
        note = "（已取消，剩余脚本未保存）" if res["cancelled"] else ""
        lines = [f"已保存 {n} 个脚本副本{note}。" if n else f"没有文件写出{note}。"]
        for _, out in res["saved"]:
            lines.append(f"[已保存] {out}")
        for f in res["failed"]:
            lines.append(f"[保存失败] {f['path']}：{' '.join(f['reason'].split())}")
        lines.append("详见日志。")
        if n:
            append_log(self.sql_log, f"===== 保存完成：{n} 个脚本副本{note} =====")
            popup_box(self, "保存完成", "\n".join(lines),
                      "warning" if res["failed"] else "information")
        else:
            append_log(self.sql_log, f"===== 保存完成：没有文件写出{note} =====", error=True)
            popup_box(self, "保存完成", "\n".join(lines), "warning")

    def on_sql_execute(self):
        cfg = self._conn_cfg()
        db = cfg["database"]
        if not db:
            self._warn(NO_DB_HINT)
            return
        if not self.sql_files:
            self._warn("请先添加 SQL 脚本文件。")
            return
        self._materialize_sql_display()
        overwrite = self.chk_sql_drop.isChecked()
        note = "；已勾选「覆盖已存在表」，执行副本先 DROP 脚本要建的表" if overwrite else ""
        append_log(self.sql_log,
            f"===== 开始执行脚本（目标库 {db}，每个文件一个事务{note}）=====")
        items, dropped = [], []
        for s in self.sql_files:
            text = s["text"]
            if overwrite:
                text, tables = prepend_drop_tables(text)
                if tables:
                    dropped.append((os.path.basename(s["path"]), tables))
            items.append((s["path"], text))
        if overwrite:
            if dropped:
                for fname, tables in dropped:
                    append_log(self.sql_log,
                        f"[覆盖] {fname}：执行前先 DROP " + "、".join(tables))
            else:
                append_log(self.sql_log,
                    "[覆盖] 脚本里没有以 CREATE TABLE 开头的语句，没有表需要先删除")
        self._run_modal("正在执行脚本到目标库…", sql_execute_task, (items, cfg),
                        on_done=self._finish_sql_execute, log_view=self.sql_log)

    def _finish_sql_execute(self, res):
        if res["conn_failed"]:
            return                      # 连不上库时已经报过详细原因，不再重复汇总
        note = "（已取消，剩余脚本未执行）" if res["cancelled"] else ""
        append_log(self.sql_log,
                   f"===== 执行完成：成功 {res['ok']} 个文件，失败 {res['fail']} 个文件"
                   f"{note} =====", error=bool(res["fail"]))
        labels = {"ok": "成功", "fail": "失败", "skip": "跳过", "cancel": "已取消"}
        lines = [f"成功 {res['ok']} 个文件，失败 {res['fail']} 个文件{note}。",
                 f"目标库 {res['target']}"]
        for f in res["files"]:
            lines.append(f"[{labels.get(f['status'], f['status'])}] "
                         f"{f['path']}：{f['detail']}")
        lines.append("详见日志。")
        popup_box(self, "执行完成", "\n".join(lines),
                  "warning" if res["fail"] else "information")

    def on_sql_gen_excel(self, label, with_meta):
        """从脚本正文解析表定义再生成交付件；写盘在子线程，输出落在程序同级。"""
        if not self.sql_files:
            self._warn("请先添加 SQL 脚本文件。")
            return
        self._materialize_sql_display()
        out_root = program_dir()
        append_log(self.sql_log,
                   f"===== 开始生成{label}excel文件（脚本 %d 个，输出根目录 {out_root}）====="
                   % len(self.sql_files))
        items = [(s["path"], s["text"]) for s in self.sql_files]
        self._run_modal("正在生成%sexcel文件…" % label, sql_meta_export_task,
                        (items, out_root, with_meta, self.chk_sql_upper.isChecked()),
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
        """第三个页签：既能按清单执行 .sql 脚本文件，也能临时问一句 SQL。

        文件这一路借用第一页的拖拽区与清单表、第二页的底部动作条：覆盖开关在左、
        主按钮在右；编辑区这一路仍在下面。两路共用右侧结果区，所以同一页同时只跑
        一个任务（见 _resources_for）。
        """
        page = QWidget()
        lay = QVBoxLayout(page)

        # 当前目标库状态条：与前两页同源、同一次刷新（公共连接区改了库或改了主机端口都得跟着变）
        self.lbl_run_db = QLabel()
        self.lbl_run_db.setTextInteractionFlags(Qt.TextSelectableByMouse)
        lay.addWidget(self.lbl_run_db)

        # 提示单独占一行：QLabel 不折行，它的最小宽度就是整句的宽度，
        # 和按钮排在同一行会把整个窗口的最小宽度顶出小屏
        self.sql_run_hint = QLabel(
            "两种用法：把 .sql 脚本拖到下面清单里按顺序执行；"
            "或直接在编辑区写 SQL 临时执行（多条用分号分隔）")
        lay.addWidget(self.sql_run_hint)

        # 文件这一路与第一页同一形状：拖拽区在上，清单表套在组框里
        self.sql_run_drop = DropArea(hint=DropArea.RUN_HINT,
                                     tooltip="点击选择 SQL 脚本；也可以直接拖到这里")
        self.sql_run_drop.files_dropped.connect(self.on_run_scripts_dropped)
        self.sql_run_drop.clicked.connect(self.on_run_scripts_add)

        self.sql_run_table = EmptyHintTable(
            0, 4, "请把 SQL 文件拖到上方虚线区，按清单顺序执行")
        style_table_font(self.sql_run_table)
        self.sql_run_table.setHorizontalHeaderLabels(["序号", "文件", "大小", "状态"])
        header = self.sql_run_table.horizontalHeader()
        header.setSectionResizeMode(QHeaderView.Interactive)
        header.setStretchLastSection(True)
        self.sql_run_table.setColumnWidth(0, 44)
        self.sql_run_table.setColumnWidth(1, 300)
        self.sql_run_table.setColumnWidth(2, 100)
        self.sql_run_table.setColumnWidth(3, 140)
        self.sql_run_table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.sql_run_table.setEditTriggers(QAbstractItemView.NoEditTriggers)

        run_upper = QGroupBox(RUN_TABLE_TITLE)
        ru = QVBoxLayout(run_upper)
        ru.setContentsMargins(0, 0, 0, 0)
        ru.addWidget(self.sql_run_table, 1)
        # 与第一页「清空待上传文件」同一位置同一语义：整份清出清单，磁盘上的脚本不动
        clear_row = QHBoxLayout()
        self.btn_run_clear = QPushButton("清空待执行脚本")
        self.btn_run_clear.clicked.connect(self.on_run_scripts_clear)
        clear_row.addWidget(self.btn_run_clear)
        clear_row.addStretch(1)
        ru.addLayout(clear_row)

        self.sql_run_input = QPlainTextEdit(SQL_RUN_DEFAULT)
        self.sql_run_input.setFont(QFont("Consolas", SQL_RUN_FONT_SIZE))
        # 编辑区与结果区同一档最小高度：初始化时整段压到最小，把高度让给上面的
        # 脚本清单；清单为空时那张表的图标 + 引导语才不会被挤掉
        self.sql_run_input.setMinimumHeight(log_min_height(self.sql_run_input))
        # 不折行：一句 SQL 折成三行之后，分号在哪、语句从哪儿开始都得数
        self.sql_run_input.setLineWrapMode(QPlainTextEdit.NoWrap)
        # 两颗清空各归各的 pane：只清结果区时不必丢掉刚敲了一半的语句，反过来也一样
        left = QWidget()
        ll = QVBoxLayout(left)
        ll.setContentsMargins(0, 0, 0, 0)
        lh = QHBoxLayout()
        lh.addWidget(QLabel("SQL 文本"))
        lh.addStretch(1)
        self.btn_sql_run_samples = QPushButton("示例 (?)")
        self.btn_sql_run_samples.setToolTip("打开示例清单：选中一条可复制到剪贴板或插到编辑区")
        self.btn_sql_run_samples.clicked.connect(self.on_sql_samples)
        lh.addWidget(self.btn_sql_run_samples)
        self.btn_sql_run_clear_input = QPushButton("清空编辑区")
        self.btn_sql_run_clear_input.setToolTip("只清掉左边这段 SQL，右侧结果区不动")
        self.btn_sql_run_clear_input.clicked.connect(self.on_sql_run_clear_input)
        lh.addWidget(self.btn_sql_run_clear_input)
        ll.addLayout(lh)
        ll.addWidget(self.sql_run_input, 1)

        self.sql_run_log = QPlainTextEdit()
        self.sql_run_log.setReadOnly(True)
        # 结果集是按显示宽度排出来的表格，字体不是等宽就会错行：与编辑区同一支字体
        self.sql_run_log.setFont(QFont("Consolas", SQL_RUN_FONT_SIZE))
        self.sql_run_log.setMinimumHeight(log_min_height(self.sql_run_log))
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

        # 三段落上下排：拖拽区 / 脚本清单 / 编辑区与结果。清单与编辑区同权，
        # 拖拽区按第一页的口径只占固定一份，不跟着窗口变高
        self.sql_run_split_v = QSplitter(Qt.Vertical)
        self.sql_run_split_v.setChildrenCollapsible(False)
        self.sql_run_split_v.setHandleWidth(6)
        self.sql_run_split_v.addWidget(self.sql_run_drop)
        self.sql_run_split_v.addWidget(run_upper)
        self.sql_run_split_v.addWidget(self.sql_run_split)
        self.sql_run_split_v.setStretchFactor(0, 0)
        self.sql_run_split_v.setStretchFactor(1, 1)
        self.sql_run_split_v.setStretchFactor(2, 2)
        # 编辑区与结果这一段默认只占它的最小高度（4 行），其余都给脚本清单；
        # 真布局出来后每次 Resize 都按绝对高度重摆（见 LogMinPlacer），用户拖过就交还
        self.sql_run_split_v.setSizes([110, 360, 200])
        self.sql_run_tail_placer = LogMinPlacer(self.sql_run_split_v, self.sql_run_split)
        lay.addWidget(self.sql_run_split_v, 1)

        # 底部动作条与第二页同一形状：覆盖开关在左、动作按钮在右，且在分隔条之外
        self.run_action_bar = ActionBar()
        op = QHBoxLayout(self.run_action_bar)
        op.setContentsMargins(10, 6, 10, 6)
        self.chk_run_drop = QCheckBox("覆盖已存在表（先 DROP 再建）")
        self.chk_run_drop.setToolTip(
            "勾选后，执行脚本文件前先对脚本里每条 CREATE TABLE 的建表目标补一句\n"
            "DROP TABLE IF EXISTS；只认整句以 CREATE TABLE 开头的语句，\n"
            "注释或字符串里出现的建表不会触发删除。只影响「执行脚本文件」，\n"
            "不改磁盘上的脚本正文；默认不勾选。")
        op.addWidget(self.chk_run_drop)
        op.addStretch(1)
        self.btn_sql_run_exec = QPushButton("执行编辑区 SQL")
        self.btn_sql_run_exec.setStyleSheet(SECONDARY_BUTTON_QSS)
        self.btn_sql_run_exec.setToolTip("执行左侧编辑区里的 SQL：按分号切句逐条执行，"
                                         "全部成功才提交，中途出错整批回滚；与脚本文件清单互不影响")
        self.btn_sql_run_exec.clicked.connect(self.on_sql_run)
        self.btn_run_scripts = QPushButton("执行脚本文件")
        self.btn_run_scripts.setStyleSheet(PRIMARY_BUTTON_QSS)
        self.btn_run_scripts.setToolTip(
            "按清单顺序逐个执行：手写脚本每个文件一个事务，全部成功才提交；\n"
            "整库 dump 自动识别，改为不开事务、逐条提交、出错继续并汇总。\n"
            "执行前请先选好目标库。")
        self.btn_run_scripts.setEnabled(False)
        self.btn_run_scripts.clicked.connect(self.on_run_scripts_execute)
        op.addWidget(self.btn_sql_run_exec)
        op.addWidget(self.btn_run_scripts)
        lay.addWidget(hline())
        lay.addWidget(self.run_action_bar)

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

    # -------------------- SQL 执行器页：脚本文件队列 --------------------
    def on_run_scripts_add(self):
        files, _ = QFileDialog.getOpenFileNames(
            self, "选择 SQL 脚本", "", "SQL 脚本 (*.sql);;所有文件 (*)")
        if files:
            self.add_run_script_paths(files)

    def on_run_scripts_dropped(self, paths):
        """拖进来的路径只收 .sql，与第二页同一句口径；混着别的文件时点名哪些没进去。"""
        keep = [p for p in paths if p.lower().endswith(".sql")]
        wrong = [p for p in paths if not p.lower().endswith(".sql")]
        if not keep:
            append_log(self.sql_run_log, "[提示] 拖入的不是 SQL 脚本文件，已忽略。")
            return
        if wrong:
            append_log(self.sql_run_log,
                       "[跳过] 不是 SQL 脚本，未读入：%s"
                       % "、".join(os.path.basename(p) for p in wrong))
        self.add_run_script_paths(keep)

    def add_run_script_paths(self, files):
        """只登记路径与大小，正文等轮到它执行时再读：导入一份大 dump 不必先占满内存。"""
        existing = {s["path"] for s in self.sql_run_files}
        added, dups, failed = [], [], []
        for f in files:
            if f in existing:
                dups.append(f)
                continue
            try:
                size = os.path.getsize(f)
            except OSError as e:
                failed.append({"path": f, "reason": describe_db_error(e)})
                continue
            self.sql_run_files.append({"path": f, "size": size, "status": "待执行"})
            existing.add(f)
            added.append(f)
        self._refresh_run_table()
        lines = []
        for p in added:
            lines.append(f"[已加入] {p}")
        for p in dups:
            lines.append(f"[跳过] {p}：已在列表")
        for f in failed:
            lines.append(f"[未加入] {f['path']}：{' '.join(f['reason'].split())}")
        append_log(self.sql_run_log,
                   f"===== 添加完成：{len(added)} 个脚本，{len(failed)} 个无法访问，"
                   f"{len(dups)} 个重复 =====", error=bool(failed))
        popup_box(self, "添加结果", "\n".join(lines),
                  "warning" if failed else "information")

    def _refresh_run_table(self):
        self.sql_run_table.setRowCount(len(self.sql_run_files))
        for i, s in enumerate(self.sql_run_files):
            items = [QTableWidgetItem(str(i + 1)),
                     QTableWidgetItem(os.path.basename(s["path"])),
                     QTableWidgetItem(format_file_size(s["size"])),
                     QTableWidgetItem(s["status"])]
            items[1].setToolTip(s["path"])
            color = {"成功": LOG_OK_COLOR, "失败": LOG_ERROR_COLOR,
                     "跳过": LOG_WARN_COLOR, "已取消": LOG_WARN_COLOR}.get(s["status"])
            if color:
                items[3].setForeground(QColor(color))
            for j, it in enumerate(items):
                it.setFlags(Qt.ItemIsEnabled | Qt.ItemIsSelectable)
                self.sql_run_table.setItem(i, j, it)
        self._sync_gen_buttons()

    def on_run_scripts_clear(self):
        """与第一页「清空待上传文件」同一语义：整份清出清单，磁盘上的脚本不动。"""
        n = len(self.sql_run_files)
        self.sql_run_files = []
        self._refresh_run_table()
        if n:
            append_log(self.sql_run_log, f"[清空] 已清空 {n} 个待执行脚本")
        else:
            append_log(self.sql_run_log, "[清空] 清单里没有脚本文件，无需清空")

    def on_run_scripts_execute(self):
        cfg = self._conn_cfg()
        db = cfg["database"]
        if not db:
            self._warn(NO_DB_HINT)
            return
        if not self.sql_run_files:
            self._warn("请先添加 SQL 脚本文件。")
            return
        overwrite = self.chk_run_drop.isChecked()
        note = "；已勾选「覆盖已存在表」，每个脚本先 DROP 它要建的表" if overwrite else ""
        append_log(self.sql_run_log,
                   f"===== 开始执行脚本文件（目标库 {db}，"
                   f"{len(self.sql_run_files)} 个文件；手写脚本每个文件一个事务，"
                   f"整库 dump 逐条提交{note}）=====")
        paths = [s["path"] for s in self.sql_run_files]
        for s in self.sql_run_files:
            s["status"] = "待执行"
        self._refresh_run_table()
        self._run_modal("正在执行脚本文件到目标库…", sql_run_files_task,
                        (paths, overwrite, cfg),
                        on_done=self._finish_run_scripts, log_view=self.sql_run_log)

    def _finish_run_scripts(self, res):
        labels = {"ok": "成功", "fail": "失败", "skip": "跳过", "cancel": "已取消"}
        by_path = {f["path"]: f for f in res["files"]}
        for s in self.sql_run_files:
            outcome = by_path.get(s["path"])
            if outcome is not None:
                s["status"] = labels.get(outcome["status"], outcome["status"])
        self._refresh_run_table()
        if res["conn_failed"]:
            return                      # 连不上库时已经报过详细原因，不再重复汇总
        note = "（已取消，剩余脚本未执行）" if res["cancelled"] else ""
        append_log(self.sql_run_log,
                   f"===== 执行完成：成功 {res['ok']} 个文件，失败 {res['fail']} 个文件"
                   f"{note} =====", error=bool(res["fail"]))
        lines = [f"成功 {res['ok']} 个文件，失败 {res['fail']} 个文件{note}。",
                 f"目标库 {res['target']}"]
        for f in res["files"]:
            lines.append(f"[{labels.get(f['status'], f['status'])}] "
                         f"{f['path']}：{f['detail']}")
        lines.append("详见日志。")
        popup_box(self, "执行完成", "\n".join(lines),
                  "warning" if res["fail"] else "information")

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

    def _load_default_conn(self):
        """启动时先读程序同级的 conn.ini 预填连接信息：现场配一次，不用每次手填。

        读法与命令行那条线是同一份实现（core/conn_config.py）：程序同级 → 当前目录，
        环境变量 E2M_* 再盖过文件。找不到文件不算错，照旧手填，但必须说一句——
        静默用内置默认值会让人以为自己的配置已经生效。
        """
        cfg, src, path, err = conn_config.discover(program_dir())
        cfg, from_env = conn_config.apply_env(cfg)
        # 只有配置点名了目标库才自动连：随包 conn.ini 是空模板（database 留空），
        # 照着它去连本机 3306 只会让新装用户一开界面就看到一条失败；没点库时
        # 即便连上也得让人手选，省不下那一步。
        self._conn_auto = (bool(from_env) or (path is not None and err is None)) \
            and bool(cfg.get("database"))
        if from_env:
            src += "、环境变量 " + "/".join(from_env)
        port = cfg["port"]
        try:
            port = int(port)
        except (TypeError, ValueError):
            append_log(self.log, "[跳过] 配置里的端口 %r 不是数字，这一项按内置默认值 3306"
                       % (cfg["port"],))
            port = 3306
        self.ed_host.setText(str(cfg["host"]))
        self.ed_port.setValue(port)
        self.ed_user.setText(str(cfg["user"]))
        if cfg["password"]:
            self.ed_pass.setText(str(cfg["password"]))
        self._cfg_db = str(cfg["database"] or "").strip()
        shown = "%s@%s:%s/%s" % (cfg["user"], cfg["host"], port,
                                 self._cfg_db or "(未填库)")
        if err is not None:
            # "有这份文件但读不了"和"压根没有"要分开说：前一种用户会去改文件，
            # 后一种要新建，混成一句"没找到"人就去找那个并不存在的文件
            append_log(self.log,
                       "[跳过] 找到 %s 但读不了，连接信息按内置默认值 %s：%s"
                       % (path, shown, err))
        elif path is None and not from_env:
            append_log(self.log,
                       "[连接信息] 没找到 conn.ini，按内置默认值 %s；"
                       "要预填就在程序同级放一份（写法见《使用说明》）" % shown)
        else:
            append_log(self.log,
                       "[已加载] 连接信息预填 %s | 来源：%s | 密码%s"
                       % (shown, src, "已读入（不回显）" if cfg["password"] else "未配置"))

    def showEvent(self, e):
        """首屏画完再自动连接：连接慢（对端不在线要等到超时）也不能卡住开窗。"""
        super().showEvent(e)
        if self._auto_conn_started:
            return
        self._auto_conn_started = True
        if self._conn_auto:
            QTimer.singleShot(0, self._auto_connect)

    def _auto_connect(self):
        """启动自动连接：conn.ini / E2M_* 给了明确配置时，窗口出来后自己连一次。

        失败只写日志、不弹模态框——启动就弹"连接失败"会让人以为软件打不开；
        顶部状态栏本来就会保持红色的"未选择目标库"，点「设置数据源信息」可重试。
        """
        if not self._conn_auto:
            return
        cfg = self._conn_cfg()
        self._db_load_seq += 1
        self._auto_conn_seq = self._db_load_seq
        append_log(self.log, "[连接中] 启动自动连接 %s:%s（用户 %s）…"
                   % (cfg["host"], cfg["port"], cfg["user"]))
        self._auto_conn_worker = ProgressWorker(fetch_databases, (cfg,))
        # 信号一律接主窗口的绑定方法：lambda 没有 QObject 接收者，会在工作线程里跑并碰控件
        self._auto_conn_worker.completed.connect(self._auto_connect_done)
        self._auto_conn_worker.failed.connect(self._auto_connect_failed)
        self._auto_conn_worker.finished.connect(self._auto_connect_finished)
        self._auto_conn_worker.start()

    def _auto_connect_done(self, dbs):
        if self._auto_conn_seq != self._db_load_seq:
            return                   # 手动加载已经接手，这份迟到的结果作废
        self._apply_databases(dbs)

    def _auto_connect_failed(self, detail):
        if self._auto_conn_seq != self._db_load_seq:
            return
        cfg = self._conn_cfg()
        append_log(self.log,
                   "[连接失败] 启动自动连接失败：无法连接到 %s:%s（用户 %s），"
                   "库列表未加载；可点「设置数据源信息」重试\n%s"
                   % (cfg["host"], cfg["port"], cfg["user"], indent_detail(detail)),
                   error=True)

    def _auto_connect_finished(self):
        worker, self._auto_conn_worker = self._auto_conn_worker, None
        if worker is not None:
            worker.deleteLater()

    def _apply_databases(self, dbs):
        """把库列表灌进下拉框并选中配置里的目标库；手动加载与自动连接共用。"""
        self.cb_db.blockSignals(True)
        self.cb_db.clear()
        for d in dbs:
            self.cb_db.addItem(d, d)
        self.cb_db.blockSignals(False)
        self.cb_db.setEnabled(True)
        if self._cfg_db and self.cb_db.findText(self._cfg_db) >= 0:
            # 配置里写了目标库就别让人再选一遍；库里没有这个名字就照旧让人手选
            self.cb_db.setCurrentText(self._cfg_db)
            append_log(self.log, "[已加载] 目标库按 conn.ini 选中：%s" % self._cfg_db)
        elif self._cfg_db:
            # 说了要选却没选上，必须解释：否则用户以为配置没生效，回头改配置文件
            append_log(self.log,
                       "[跳过] conn.ini 指定的目标库 %s 不在服务器上，请手动选择" % self._cfg_db)
        self._refresh_db_labels()
        append_log(self.log, f"[已加载] 可用数据库：{', '.join(dbs)}")

    def on_load_databases(self):
        cfg = self._conn_cfg()
        self._db_load_seq += 1       # 还在路上的自动连接结果就此过期
        try:
            self._apply_databases(fetch_databases(cfg))
        except Exception as e:
            # 弹窗与日志用同一套展开格式，否则弹窗里是 (2003, '...') 这种元组原文
            popup_box(self, "连接失败", describe_db_error(e), "critical")
            append_log(self.log,
                       f"[连接失败] 无法连接到 "
                       f"{cfg['host']}:{cfg['port']}（用户 {cfg['user']}），库列表未加载\n"
                       + indent_detail(describe_db_error(e)), error=True)

    def on_initials_toggled(self, checked):
        """命名开关切换要留痕：之后看到 ddje 这样的字段名，得能对上是自己勾的。"""
        append_log(self.log, 
            "[提示] 命名规则切换为：%s（对之后添加的文件生效）"
            % ("拼音首字母" if checked else "全拼"))

    def on_upper_toggled(self, checked):
        """大小写开关同样留痕：列表里忽然一片大写，得能对上是自己勾的。"""
        append_log(self.log, 
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
            append_log(self.log, "[提示] 拖入的不是 Excel 或 ZIP 文件，已忽略。")

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
        append_log(self.log, f"[添加结果] {text}")
        popup_box(self, "添加结果", text)

    def _refresh_file_table(self):
        # setItem 会发 itemChanged；不压住的话重建表格被当成"用户改了表名"
        self._refreshing_file_table = True
        try:
            self.file_table.setRowCount(len(self.tables))

            def read_only(text):
                # QTableWidgetItem 默认带可编辑标志，只靠视图触发器挡不住"标志上可改"；
                # 其余列连标志一起收掉，让"只有表名可改"在数据和界面两处都成立
                it = QTableWidgetItem(text)
                it.setFlags(Qt.ItemIsEnabled | Qt.ItemIsSelectable)
                return it

            for i, t in enumerate(self.tables):
                self.file_table.setItem(i, 0, read_only(str(i + 1)))
                item = read_only(os.path.basename(t["filepath"]))
                # ZIP 展开出来的文件要能看出来自哪个包的哪个内部路径
                item.setToolTip(self._zip_origin.get(t["filepath"]) or t["filepath"])
                self.file_table.setItem(i, 1, item)
                name_item = QTableWidgetItem(t["table_name"])
                name_item.setFlags(Qt.ItemIsEnabled | Qt.ItemIsSelectable | Qt.ItemIsEditable)
                self.file_table.setItem(i, 2, name_item)
                self.file_table.setItem(i, 3, read_only(t["table_comment"]))
                self.file_table.setItem(i, 4, read_only(str(len(t["columns"]))))
                self.file_table.setItem(i, 5, read_only(str(len(t["df"]))))
                pk = ", ".join(c["field"] for c in t["columns"] if c.get("primary_key")) or "无"
                self.file_table.setItem(i, 6, read_only(pk))
        finally:
            self._refreshing_file_table = False

    def on_clear(self):
        self.tables.clear()
        self.file_table.setRowCount(0)
        self.btn_import.setEnabled(False)
        self._sync_gen_buttons()
        self.progress.setValue(0)      # 清空待上传文件后不留上一次的进度残留

    def closeEvent(self, e):
        """退出时清掉 ZIP 展开出来的临时文件。"""
        worker = self._auto_conn_worker
        if worker is not None and worker.isRunning():
            # 不先等它收尾就析构 QThread，进程会在原生层直接 abort（关窗闪退）
            worker.wait(6000)
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
            append_log(self.log, f"[换前缀] {r['old']} → {r['new']}")
        if res["skipped"]:
            append_log(self.log, 
                f"[跳过] 表名里没有下划线，未改动：{'、'.join(res['skipped'])}")
        append_log(self.log, 
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
            append_log(self.log, f"[前缀] 已为全部表名批量添加前缀「{res['prefix']}」")
        else:
            append_log(self.log, "[前缀] 已移除前缀，恢复基础表名")

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

    def on_file_table_clicked(self, row: int, col: int) -> None:
        """单击「表名」直接进编辑器；其他列保持只读（双击才去看结构）。"""
        if col != 2 or self.task_busy((self.RES_EXCEL,)):
            return
        if not 0 <= row < self.file_table.rowCount():
            return
        item = self.file_table.item(row, 2)
        if item is not None:
            self.file_table.editItem(item)

    def on_file_table_item_changed(self, item) -> None:
        """手动改单张表的表名：校验不过就恢复原名，改成了就以新名为基础名。"""
        if self._refreshing_file_table or item is None or item.column() != 2:
            return
        row = item.row()
        if not 0 <= row < len(self.tables):
            return
        t = self.tables[row]
        old = t["table_name"]
        # 反引号是 DDL 的引用符，不是名字的一部分；粘贴时最容易带上
        new = str(item.text() or "").replace("`", "").strip()
        if new == old:
            if item.text() != old:
                self._refresh_file_table()   # 去掉多余空白/反引号后回显规范名
            return
        others = [x["table_name"] for x in self.tables if x is not t]
        why = table_name_problem(new, others)
        if why:
            self._warn(why + "已恢复原表名「%s」。" % old)
            self._refresh_file_table()
            return
        t["table_name"] = new
        t["base_table_name"] = new
        self._refresh_file_table()
        append_log(self.log, "[表名] %s → %s（手动修改，后续批量前缀基于新名计算）" % (old, new))

    def on_edit_structure_row(self, row: int, col: int = 0) -> None:
        """双击文件列表某一行：只弹那一张表的结构窗口。

        整列表一起弹会让人分不清改的是哪张表，Swing 那条线一直是按行弹的。
        「表名」列例外——单击/双击都只进改名编辑器，不叠一个结构窗。
        """
        if col == 2:
            return
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
        self.worker.log.connect(self._on_worker_log)
        self.worker.log_error.connect(self._on_worker_error)
        self.worker.table_progress.connect(self._on_table_progress)
        self.worker.table_done.connect(self._on_table_done)
        self.worker.finished_all.connect(self._on_finished_all)
        if not MODAL_PROGRESS:
            self.worker.start()
            return
        # 导入是真正的长任务：框按「每张表 + 每行数据」计项，随时能看到推进
        task = TaskInfo(self, (self.RES_EXCEL,), "正在导入到目标库…", self.log, None)
        self._tasks.append(task)
        self._import_task = task
        dlg = ProgressDialog(task.title, self)
        dlg.btn_cancel.setText("停止（当前表完成后中止）")
        task.dialog = dlg
        self.worker.progress.connect(task.set_progress)
        dlg.btn_cancel.clicked.connect(self.worker.abort)
        self.worker.start()
        self._place_task_dialog(dlg)
        dlg.show()
        dlg.raise_()
        dlg.activateWindow()
        self._sync_gen_buttons()

    def on_stop(self):
        if self.worker:
            self.worker.abort()
            append_log(self.log, "[正在中止...]")
        else:
            # 空点「停止」也要留痕：什么都不说会让人以为自己按错了，
            # 而 Swing 那条线同一句已经写在日志里，两边口径要一致
            append_log(self.log, "没有正在执行的任务")

    def _on_worker_log(self, msg):
        append_log(self.log, msg)

    def _on_worker_error(self, msg):
        append_log(self.log, msg, error=True)

    def _on_table_progress(self, cur, tot):
        if tot > 0:
            self.progress.setValue(int(cur / tot * 100))

    def _on_table_done(self, result):
        pass

    def _on_finished_all(self, results):
        # 先收掉这一次导入自己的框，再打汇总弹窗，避免两个框叠在一起
        task, self._import_task = self._import_task, None
        if task is not None:
            if task in self._tasks:
                self._tasks.remove(task)
            if task.dialog is not None:
                task.dialog.finish()
            task.dialog, task.worker = None, None
            self._sync_gen_buttons()
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
            append_log(self.log, 
                "[清理] 已从待导入列表移除成功文件："
                + "、".join(os.path.basename(t["filepath"]) for t in removed))
        if fail and kept:
            append_log(self.log, 
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
        self.comment_edits = []      # 与 editors 同序：每张表的表注释输入框
        for t in tables:
            tw = QTableWidget(len(t["columns"]), 5)
            style_table_font(tw)
            # 第 4 列是这一列数据在表内的实测最大字节数，不是类型声明的容量；
            # 挨着「数据类型」放时只写"最大字节长度"会被读成 VARCHAR(n) 那一档
            tw.setHorizontalHeaderLabels(
                ["主键", "字段名(拼音/首字母)", "字段注释(中文表头)", "数据类型",
                 "表内数据最大字节长度"])
            for i, c in enumerate(t["columns"]):
                cb = QCheckBox()
                cb.setChecked(bool(c.get("primary_key")))
                tw.setCellWidget(i, 0, cb)
                tw.setItem(i, 1, QTableWidgetItem(c["field"]))
                tw.setItem(i, 2, QTableWidgetItem(c["comment"]))
                tw.setItem(i, 3, QTableWidgetItem(c["type"]))
                size_item = QTableWidgetItem(str(c.get("max_len", "")))
                # 这一列是推断的中间量：改了不会改类型，只会出现"看着改对了、
                # 建表仍按原类型"的错觉，所以只给看不给改（与 Java 侧同口径）
                size_item.setFlags(size_item.flags() & ~Qt.ItemIsEditable)
                tw.setItem(i, 4, size_item)
            tw.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeToContents)
            tw.horizontalHeader().setSectionResizeMode(1, QHeaderView.Stretch)
            tw.horizontalHeader().setSectionResizeMode(2, QHeaderView.Stretch)
            # 末列按内容宽度走：表头 10 个字，默认 100px 会把它截成省略号
            tw.horizontalHeader().setSectionResizeMode(4, QHeaderView.ResizeToContents)
            # 这张表不另加标题：tab 上写的就是表名，已经贴在表格正上方
            page = QWidget()
            page_lay = QVBoxLayout(page)
            page_lay.setContentsMargins(0, 0, 0, 0)
            comment_row = QHBoxLayout()
            comment_row.addWidget(QLabel("表注释："))
            edit = QLineEdit(str(t.get("table_comment") or ""))
            edit.setClearButtonEnabled(True)
            comment_row.addWidget(edit, 1)
            page_lay.addLayout(comment_row)
            page_lay.addWidget(tw)
            self.comment_edits.append(edit)
            self.tabs.addTab(page, t["table_name"])
            self.editors.append((t, tw))
        lay.addWidget(self.tabs)

        btns = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        btns.accepted.connect(self._on_accept)
        btns.rejected.connect(self.reject)
        lay.addWidget(btns)

    def _on_accept(self):
        # 先把全部编辑收集到副本，主键校验通过后再写回，避免无效主键污染数据
        staged = []
        for (t, tw), edit in zip(self.editors, self.comment_edits):
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
            staged.append((t, cols, edit.text().strip()))

        problems = []
        for t, cols, _comment in staged:
            good, msg = validate_primary_key(t["df"], cols)
            if not good:
                problems.append(f"表「{t['table_name']}」：{msg}")
        if problems:
            popup_box(self, "主键设置无效",
                      "\n".join(problems) + "\n\n请调整主键勾选或字段类型后重试。",
                      "warning")
            return    # 校验不过不放行，保持弹窗打开让用户调整

        for t, cols, comment in staged:
            t["columns"] = cols
            t["table_comment"] = comment
        self.accept()

    def get_tables(self):
        return self.tables


# ---------------------------------------------------------------------------
# SQL 脚本页的表元数据弹窗（字段名/注释/类型可改，改完回写脚本正文）
# ---------------------------------------------------------------------------
class SqlMetadataDialog(QDialog):
    """一张表的字段清单。

    数据类型一列写完整形状（VARCHAR(64)、DECIMAL(10,2)），与第一页那一列同一个口径，
    不再拆成"类型 + 长度"两格。给了 on_edit 时字段名 / 字段注释 / 数据类型三列可改，
    每改一格就调一次回调（回调只管这一列真正变了的那几项）；没给回调时整表只读，
    自检与取证走这一路，不去动脚本。
    """

    def __init__(self, table, display_name, upper, parent=None, on_edit=None):
        super().__init__(parent)
        self.setWindowTitle("表元数据（%s）" % display_name)
        fit_client(self, 980, 640)
        lay = QVBoxLayout(self)
        head = QLabel("表名：%s    表注释：%s"
                      % (display_name, table.get("table_comment") or "（无）"))
        head.setTextInteractionFlags(Qt.TextSelectableByMouse)
        head.setWordWrap(True)
        lay.addWidget(head)
        if on_edit is None:
            tip = "这里按脚本里的 CREATE TABLE 原样展示；" \
                  "勾选「表名与字段名转大写」只切换显示，不回写脚本正文。"
        else:
            tip = "字段名、字段注释、数据类型这三列可以直接改，改完就写进脚本正文（还没落盘）；" \
                  "主键与可空照脚本里的定义显示，不在这里改。" \
                  "「表名与字段名转大写」只切换显示，不回写脚本正文。"
        lay.addWidget(QLabel(tip))
        cols = table.get("columns") or []
        tw = QTableWidget(len(cols), 5)
        style_table_font(tw)
        tw.setHorizontalHeaderLabels(["字段名", "字段注释", "数据类型", "主键", "可空"])
        self._on_edit = on_edit
        self._upper = bool(upper)
        self._loading = True
        self._originals = []          # 每行的 (字段名, 注释, 类型) 原值，用来判断改了哪一格
        if on_edit is not None:
            tw.itemChanged.connect(self._on_item_changed)
        for i, c in enumerate(cols):
            field = c["field"]
            typ = c.get("type") or ""
            comment = c.get("comment") or ""
            self._originals.append((field, comment, typ))
            values = [field.upper() if upper else field, comment, typ,
                      "是" if c.get("primary_key") else "",
                      "是" if c.get("nullable", True) else "否"]
            for j, value in enumerate(values):
                item = QTableWidgetItem(value)
                item.setTextAlignment(Qt.AlignCenter if j >= 3 else Qt.AlignLeft | Qt.AlignVCenter)
                if j >= 3:
                    item.setFlags(item.flags() & ~Qt.ItemIsEditable)
                tw.setItem(i, j, item)
        self._loading = False
        tw.setSelectionBehavior(QAbstractItemView.SelectRows)
        tw.horizontalHeader().setSectionResizeMode(QHeaderView.Interactive)
        tw.horizontalHeader().setStretchLastSection(True)
        tw.setColumnWidth(0, 230)
        tw.setColumnWidth(1, 280)
        tw.setColumnWidth(2, 160)
        tw.setColumnWidth(3, 70)
        tw.setColumnWidth(4, 70)
        # 表格左上的标识（与第一页「待导入文件」同一做法）：标题里带列数，
        # 不必滚到表格末尾数这一张表解析出了多少列
        col_box = QGroupBox("%s（共 %d 列）" % (COLUMN_TABLE_TITLE, len(cols)))
        cb_lay = QVBoxLayout(col_box)
        cb_lay.setContentsMargins(6, 2, 6, 6)
        cb_lay.addWidget(tw, 1)
        lay.addWidget(col_box, 1)
        self.field_table = tw
        btns = QDialogButtonBox(QDialogButtonBox.Close)
        btns.rejected.connect(self.reject)
        btns.clicked.connect(lambda _: self.reject())
        lay.addWidget(btns)

    def tableWidget(self):
        return self.field_table

    def _on_item_changed(self, item):
        """一格里改了什么都只交这一格对应的项；被拒绝时把这一格填回原值并说清原因。

        交出去的是**脚本里的字段名**（不是大写开关改过的显示名），
        否则勾着「转大写」时改不动任何一列。
        """
        if self._loading or self._on_edit is None or item.column() > 2:
            return
        row = item.row()
        if row >= len(self._originals):
            return
        field, comment, typ = self._originals[row]
        shown = lambda c: (self.field_table.item(row, c).text()
                           if self.field_table.item(row, c) is not None else "")
        kw = {}
        if shown(0).strip() and shown(0).strip() != field:
            kw["new_field"] = shown(0).strip()
        if shown(1) != comment:
            kw["new_comment"] = shown(1)
        if shown(2).strip() and shown(2).strip() != typ:
            kw["new_type"] = shown(2).strip()
        if not kw:
            return
        if self._on_edit(field, **kw):
            self._originals[row] = (kw.get("new_field", field),
                                    kw.get("new_comment", comment),
                                    kw.get("new_type", typ))
            return
        self._loading = True          # 填回原值别再触发一次 itemChanged
        try:
            for col, value in ((0, field.upper() if self._upper else field),
                               (1, comment), (2, typ)):
                cell = self.field_table.item(row, col)
                if cell is not None:
                    cell.setText(value)
        finally:
            self._loading = False


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
        # 左边清单是界面基准 9 号，右边全文按日志档 10 号：这是照着挑的阅读弹窗，
        # 不吃编辑区那档 16 号（16 号在并排清单旁边显得突兀）
        self.preview.setFont(QFont("Consolas", LOG_FONT_SIZE))
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
            # 换一条就把右侧滚回开头：上一条长文留下的滚动位置会让新语句
            # 看着像"缺了上半截"（与 Java 侧 openSqlSamples 的复位同一处理）
            for bar in (self.preview.verticalScrollBar(),
                        self.preview.horizontalScrollBar()):
                bar.setValue(bar.minimum())

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
