"""
Excel ⇄ MySQL（PySide6 GUI）
==========================
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
  - 导入完成的文件仍留在待导入列表里（不自动抽走），要清空或删行都有现成入口
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

from PySide6.QtCore import Qt, QThread, Signal, QUrl, QFileInfo, QTimer, QObject, QEvent, QSize, QRectF, QPointF
from PySide6.QtGui import (
    QDragEnterEvent, QDropEvent, QFont, QFontInfo, QIcon, QColor, QTextCursor,
    QTextCharFormat,
    QTextBlockFormat, QPainter, QPen, QPainterPath, QBrush, QPixmap,
)
from PySide6.QtWidgets import (
    QApplication, QWidget, QVBoxLayout, QHBoxLayout, QFormLayout,
    QLineEdit, QSpinBox, QPushButton, QComboBox, QFileDialog, QTableWidget,
    QTableWidgetItem, QLabel, QProgressBar, QPlainTextEdit, QTextEdit, QGroupBox,
    QMessageBox, QHeaderView, QCheckBox, QAbstractItemView, QScrollArea, QStyle,
    QDialog, QDialogButtonBox, QTabWidget, QSplitter, QProxyStyle, QToolButton,
    QTreeWidget, QTreeWidgetItem, QStyledItemDelegate,
    QFrame, QSizePolicy,
)

from core import conn_config
from core.log_text import (
    describe_db_error, format_log_entry, log_level, LOG_LEVEL_MARK, LOG_TIME_FORMAT,
    COVER_NOTHING_BODY, SQL_PAGE_DUMP_HINT,
)
from core.infer import (
    build_table_def, make_unique_names, apply_table_prefix, validate_primary_key,
    replace_first_segment, sanitize_table_name, _col_to_field, dedup_field,
    table_name_problem, case_switch, row_fit_notice, apply_auto_pk, _attach_row_fit,
    AUTO_PK_FIELD,
)
from core.sql_execute import executable_statements, run as run_sql_statements
from core.sql_script import (
    extract_create_tables, split_sql_statements, parse_insert_rows,
    parse_create_tables, rename_table_sql, apply_display_renames,
    set_table_comment, prepend_drop_tables,
    is_dump_script, dump_statements, dump_skip_reason, dump_database_switch,
    edit_column, format_sql, format_refusal, add_auto_pk_create,
    add_column, delete_column,
    SQL_FORMAT_KEYWORDS, SQL_FORMAT_TYPES,
)
from core.naming import clean_prefix, is_valid_prefix, rewrite_versions, swap_first_segment
from sql_samples import SAMPLES as SQL_SAMPLES, matches as sample_matches
from core.mysql_io import (
    build_ddl, connect, list_databases, create_table, insert_data,
)
from core.zip_import import (
    extract_excel_archive, count_excel_entries, ZipArchiveError, EXCEL_EXTS,
)
from core.merge_check import MergedCellsError
from core.wide_split import split_wide_table, notice_lines as split_notice_lines
from ui_help import HelpDialog, AboutDialog, fit_client
from appinfo import build_info, title_text
from assetpath import resource_path
from version import APP_VERSION

IMPORT_EXTS = EXCEL_EXTS + (".zip",)   # 拖拽区与文件选择框共同接受的扩展名


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
# 目标库那一行的就地说明。那一格灰着的时候，控件本身说不出"为什么灰、点哪颗才会亮"，
# 用户只能读成"这个库不给换"（现场报的就是这句），所以两态各写一句钉在旁边。
DB_HINT_UNLOADED = "库列表还没加载：点「连接并加载库」拿到清单，再在这里选库"
DB_HINT_STALE = "连接信息改过了：下面这份清单来自上一次连接，重新点「连接并加载库」才作数"
NO_FILE_HINT = "请先添加 Excel 文件或 ZIP 压缩包。"

# 语句头 → 这条语句动的是哪张表。按选中行收窄脚本时用它给语句归类：
# 认得出的才带走，认不出的（SET / BEGIN / LOCK TABLES 这类整份脚本的壳）留在原地。
# 顺序与正则里的分支一一对应：CREATE TABLE 要排在 CREATE INDEX 之前判定，
# 否则 `CREATE TABLE` 会先被 `CREATE ... INDEX` 那条吃掉一半。
SQL_HEAD_TABLE_RE = re.compile(
    r"^\s*(?:"
    r"CREATE\s+(?:TEMPORARY\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?"
    r"|INSERT\s+(?:IGNORE\s+)?INTO\s+"
    r"|REPLACE\s+INTO\s+"
    r"|ALTER\s+TABLE\s+"
    r"|CREATE\s+(?:UNIQUE\s+|FULLTEXT\s+|SPATIAL\s+)?INDEX\s+\S+\s+ON\s+"
    r"|DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?"
    r"|TRUNCATE\s+TABLE\s+"
    r"|DELETE\s+FROM\s+"
    r")([`\w$]+(?:\.[`\w$]+)?)", re.I)

# 「SQL 执行器」编辑区的开场文本：既是"连不连得上库"的一键自检语句，
# 也是「插入到编辑区」判断"这段还是默认值、可以直接覆盖掉"的比对基准
SQL_RUN_DEFAULT = "SELECT 1 AS 连通检查;"

# 编辑区那段 SQL 的格式化入口：按钮文案、悬停那一句、快捷键写法三处共用。
# 快捷键在两栈各写一遍绑定代码，能钉住的只有"文案里承诺的那一串"与"真按键有没有反应"：
# 用例按真按键走一遍，对撞比 tooltip 文案，所以这三者不会各说各话。
SQL_FORMAT_LABEL = "格式化 SQL"
SQL_FORMAT_KEY = "Ctrl+Alt+L"
SQL_FORMAT_TIP = ("排整齐左边这段 SQL：关键字与类型大写、从句各起一行、字段清单一个字段一行并缩进；"
                  "整库 dump（带 DELIMITER 那种）会拒绝并说明原因。\n"
                  "快捷键 " + SQL_FORMAT_KEY + "（焦点在这块编辑区里时按）")

# 两处日志区共用的字号：9 号字现场判过"太小"，14 号在默认窗口里只能露出三行；
# 10 号是两者之间的落点——控制台/IDE 的常见档位，比界面基准 9 号大一点。
LOG_FONT_SIZE = 10

# 第三页 SQL 编辑区与结果区的等宽字号：与第一页日志同一档（用户口径：两页正文一样大）。
# 直接同源到 LOG_FONT_SIZE，改日志档时这两处跟着走，不会一页大、一页小。
SQL_RUN_FONT_SIZE = LOG_FONT_SIZE
# 一批里最多留多少个结果集给表格回看：一条一句地跑上千句 select 时，
# 全留着就是几百兆的格子；丢的是最早那一个，用户回看的一般是刚跑出来的那条。
MAX_RESULT_SETS = 50
# 结果表表头 title 的前缀：Java 侧同一句（MainFrame.HEADER_COMMENT_PREFIX），
# 两栈鼠标停在同一列上看到的话不一样，用户会以为是两个不同的东西。
HEADER_COMMENT_PREFIX = "字段注释："
# 没有结果集时挂在整条表头上的引导（装出结果后换成逐列的字段注释）：
# 与 Java 侧 MainFrame.RESULT_HEADER_HINT 同一句，两栈停在同一处看到的话不能不一样。
RESULT_HEADER_HINT = ("「SQL 执行器」的结果表：列名来自查询本身，字段注释按列挂在各自表头的"
                      " title 上，鼠标停在列名上即见")
# 表格内容与表头的字号：与 Excel 数据网格同级（11 号），并保持比日志大一档。
# 18 号是上一轮"内容要比日志大"的过头做法，一屏装不下几行；两栈同一组数（11 / 10）。
TABLE_FONT_SIZE = 11
# 表格行高按字高加一点留白算出来，不写死像素：写死 30px 会让 18 号字顶满一行，
# 换成 11 号又显得空，字号与行距就对不上了。
TABLE_ROW_PADDING = 6
# 「序号」那一列的列宽（三张主表格同一个数，Java 侧 Tables.SERIAL_COL_PX 同值）。
# 原先写 44：Qt 的表头最小宽度（minimumSectionSize）在随包字体下量到 49，写 44 会被静默抬到 49，
# 而 Swing 没有这道下限、照 44 画——两栈就差了 5px。统一按字体量出来的那一档写死，两边一致。
SERIAL_COL_PX = 49

# 「SQL 示例」目录那一栏：它是这框里唯一要逐条读的东西，原先吃界面基准 9 号、
# 分组与条目除了缩进毫无差别，读起来累。字号并入表格那一档，行高同一条算法。
SAMPLE_TREE_FONT_SIZE = TABLE_FONT_SIZE
SAMPLE_TREE_ROW_PADDING = 6
SAMPLE_TREE_INDENT = 26
# 配色复用介绍条与主按钮那一组冷色（Java 侧 PageIntro / Tables 同值），不另造色值：
# 分组行像章节标题、条目像正文，层次靠这个出来，不靠装饰
SAMPLE_GROUP_FG = "#1f5fd0"
SAMPLE_GROUP_BG = "#f4f8ff"

# 搜索那一栏：一句 30 字的提示在 283px 宽的框里只显出前 20 个字，截在半句上，
# 看着像灰底杂字而不像提示；所以提示换短句，认得出"这是搜索"的活交给放大镜。
# 放大镜自绘：主题图标在 Windows 上取不到（QIcon.fromTheme 给空图标），
# emoji 又可能渲染成豆腐块，两端同一条画法才画得出同一个形状。
SAMPLE_SEARCH_HINT = "搜索示例：标题、说明、关键词"
SAMPLE_SEARCH_TIP = ("关键词过滤：短标题、说明、场景、解决的问题、"
                     "关键词与语句都算（清空回到全部）")
SAMPLE_SEARCH_ICON_SIZE = 14

# 介绍块的角色配色：标题一档、字段名一档、关键词成一块，值本身保持正文默认色。
# 介绍块与 SQL 正文的角色色。以前"一个新色都不造"，结果两处都读不清：关键词整条铺
# 深底白字，多个词连成一整块把字压糊；关键字刷的是比正文还淡的灰，看着等于没高亮。
# 现在明造三支：紫 #8250df 给关键字与关键词（同一概念同一支色），浅灰 #f0f2f4 只当
# 逐词小块的底，绿 #1a7f37 给「解决的问题」那一档提示。警示橙留给真会删数据的语句，
# 不跟这三支混用。两端同一组色值与同一条切分规则，对撞键 intro.rich 把两栈各自读回的
# 角色段比一遍。
SAMPLE_INTRO_TITLE_FG = "#2f6feb"
SAMPLE_INTRO_LABEL_FG = "#1f5fd0"
SAMPLE_INTRO_CHIP_FG = "#8250df"
SAMPLE_INTRO_CHIP_BG = "#f0f2f4"
SAMPLE_INTRO_TIP_FG = "#1a7f37"
SAMPLE_INTRO_BLOCK_SPACING = 4

# DDL 预览的行距：一页几十行，每行都留介绍块那一档就太松，取它的一半。
DDL_BLOCK_SPACING = 2

# 第二页 DDL 预览下面那一句：预览排过版，但脚本正文没动，两件事必须分开说。
# 两端逐字同形，由对撞键 sqlddl.note 比着。
SCRIPT_DDL_NOTE = "预览按格式化器排版；脚本正文一个字没改，改名、保存、执行都按原文走"

# 每张表格左上角那一句标识（组框标题）。用户口径：每个表格都要有标识，
# 第一页的「待导入文件」就是样子——标题里顺带写明这一张表怎么操作。
# 两栈同一句、由对撞器逐条比（读的是真控件上的标题，不是这里抄一份）。
FILE_TABLE_TITLE = "待导入文件（双击表名或表注释编辑，双击其他列改字段）"
SQL_TABLE_TITLE = "待处理的脚本表（双击表名或表注释编辑，双击其他列看元数据）"
RUN_TABLE_TITLE = "待执行脚本（按清单顺序执行）"
COLUMN_TABLE_TITLE = "字段清单"

# 「表名前缀」输入框的占位句：两页共用这一个常量（Java 侧同一句在 gui.PrefixHint），
# 两页做的是同一件事，措辞就该逐字相同。哪一页对新添加的文件自动生效，这条页签差异
# 写在两颗按钮的 tooltip 里，不塞进占位句。
PREFIX_PLACEHOLDER = ("可选，如 dim_：点「批量应用前缀」加上这段，"
                      "点「替换首个前缀」换掉第一个下划线前的内容")

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


TAB_SCROLL_BUTTON_WIDTH = 28


class _TabScrollStyle(QProxyStyle):
    """页签条左右翻页那两颗按钮：Qt 默认只给 16px 宽，点起来是指甲盖大小。

    QTabBar 摆这两颗钮时读样式的 PM_TabBarScrollButtonWidth，QSS 的 width 改不动它
    （实测只改到 sizeHint，真尺寸照旧 16），只能换样式。每个页签件各持一份实例：
    setStyle 之后样式归控件所有，共用一份会在析构时重复释放。
    """

    def pixelMetric(self, metric, option=None, widget=None):
        if metric == QStyle.PM_TabBarScrollButtonWidth:
            return TAB_SCROLL_BUTTON_WIDTH
        return QProxyStyle.pixelMetric(self, metric, option, widget)


def style_tab_scroll(tabs):
    """给一条页签条换上放大过的翻页按钮（Java 侧同一件事见 gui.TabScrollers）。"""
    style = _TabScrollStyle()
    tabs.tabBar().setStyle(style)
    tabs.e2m_tab_scroll_style = style      # 控件不持有 Python 引用，挂着防回收
    return tabs


def tab_scroller_width(tabs):
    """这条页签上翻页钮的宽度（Qt 逻辑像素）；没有翻页钮时回 -1。

    读样式口径而不是 QToolButton.sizeHint：QTabBar 摆这两颗钮时用的是样式的
    PM_TabBarScrollButtonWidth，而 sizeHint 不管样式怎么改都回 24，读它会误判。
    Java 侧同一个入口是 gui.TabScrollers.widthOf（那边给物理像素，对撞前折回设计档）。
    """
    bar = tabs.tabBar()
    if not any(isinstance(c, QToolButton) for c in bar.children()):
        return -1
    return bar.style().pixelMetric(QStyle.PM_TabBarScrollButtonWidth, None, bar)


def fill_widths(bases, avail, stretch):
    """按"这一列需要多宽"打底，把视口富余的宽度摊给文字列。

    底宽之和已经超过 avail 时原样返回——一列都不往小里压，这样"内容比视口宽"
    才轮得到横向滚动条把超长的尾巴带出来。富余部分按各列底宽等比分，整除的
    零头落在最后一个文字列上，总和正好等于 avail，表格右侧不留白条。
    """
    widths = list(bases)
    spare = avail - sum(widths)
    if spare <= 0 or not stretch:
        return widths
    total = sum(bases[c] for c in stretch)
    given = 0
    for c in stretch[:-1]:
        add = spare * bases[c] // total
        widths[c] += add
        given += add
    widths[stretch[-1]] += spare - given
    return widths


class FillWidthTable(QTableWidget):
    """字段清单表：列宽铺满视口，但绝不把任何一列压到它需要的宽度以下。

    每列先取"设计下限与内容需要"的大者当底，视口还有富余就摊给文字列；视口一变
    （含滚动条出现、弹窗拉宽）就重算。末列仍不铺满（stretchLastSection 关着）：
    铺满会把超长那一格压回视口宽度，切掉的那半截滚不到。这里铺的只是"视口比
    底宽宽"的那部分，内容真超出时横向条照旧——两件事分开管。
    """

    def __init__(self, rows, cols, floors, stretch, parent=None):
        super().__init__(rows, cols, parent)
        self._floors = tuple(floors)
        self._stretch = tuple(stretch)
        self._fitting = False
        self.horizontalHeader().setSectionResizeMode(QHeaderView.Interactive)
        self.horizontalHeader().setStretchLastSection(False)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarAsNeeded)
        self.viewport().installEventFilter(self)

    def base_widths(self):
        """每列的底宽：设计下限、单元格内容、表头文字三者取大。"""
        head = self.horizontalHeader()
        return [max(floor, self.sizeHintForColumn(c), head.sectionSizeHint(c))
                for c, floor in enumerate(self._floors)]

    def fit_columns(self):
        if self._fitting:
            return
        self._fitting = True
        try:
            widths = fill_widths(self.base_widths(), self.viewport().width(),
                                  self._stretch)
            for c, w in enumerate(widths):
                if self.columnWidth(c) != w:
                    self.setColumnWidth(c, w)
        finally:
            self._fitting = False

    def eventFilter(self, obj, event):
        # 只挂 viewport：拉弹窗、竖条出现这两件事都会让它收到 Resize，
        # 表格自己那一层的事件与它同源，挂两处只会把底宽算两遍
        if obj is self.viewport() and event.type() == QEvent.Resize:
            self.fit_columns()
        return super().eventFilter(obj, event)


EMPTY_HINT_COLOR = "#8a97a8"
EMPTY_ICON_COLOR = "#aebdd1"
ACTION_BAR_BG = "#f4f7fc"
ACTION_BAR_BORDER = "#dce5f2"


class EmptyHintTable(QTableWidget):
    """清单为空时在表格中央画一个"拖进来"的图标和一句引导语。

    用户口径：空表格要看得懂下一步做什么，并且和上方虚线区说同一句话。
    文字画在 viewport 上而不是塞一个覆盖控件：覆盖控件会吃掉滚轮与右键，
    表格一空就只剩这层装饰可点，反而更迷惑。

    Del 键只发信号、不做业务——删哪几行、留痕怎么写、有任务在跑拦不拦，
    都归主窗口一处说，避免快捷键和按钮两条入口各写一套规则。
    """

    del_pressed = Signal()

    def __init__(self, rows, cols, hint="", parent=None):
        super().__init__(rows, cols, parent)
        self.empty_hint = hint

    def keyPressEvent(self, e):
        if e.key() == Qt.Key_Delete:
            self.del_pressed.emit()
            return
        super().keyPressEvent(e)

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


class PasteFilesFilter(QObject):
    """接住主窗口里任何一处按下的 Ctrl+V：先看剪贴板，是文件就交给"添加文件"这条路。

    装在 QApplication 上而不是某个控件上，因为按键先到谁手里由焦点决定——
    鼠标停在拖拽区、SQL 编辑区还是主机名输入框，都不该改变"复制了文件按 Ctrl+V"的结果。
    剪贴板是纯文本时返回 False，按键照旧交回焦点控件自己粘。
    整条应用只装这一份（`install_paste_filter`）：每个窗口各装一份的话，
    一份过滤就是事件流上一次 Python 回调，窗口攒到十几个会把事件循环拖到
    计时器都轮不到——用例里量到过（同一段任务的事件 tick 从每轮几十次掉到 1 次）。
    """

    def __init__(self, app):
        super().__init__(app)

    @staticmethod
    def is_paste_key(event):
        mods = event.modifiers()
        return (event.key() == Qt.Key_V
                and bool(mods & Qt.ControlModifier)
                and not mods & (Qt.ShiftModifier | Qt.AltModifier | Qt.MetaModifier))

    def eventFilter(self, obj, event):
        if event.type() != QEvent.KeyPress or not self.is_paste_key(event):
            return False
        win = obj.window() if isinstance(obj, QWidget) else None
        if not isinstance(win, MainWindow):
            return False      # 弹窗与其它顶层窗口：那一份粘贴按它自己的规矩走
        if win.on_paste_files(obj):
            return True
        return super().eventFilter(obj, event)


_PASTE_FILTER = None


def install_paste_filter(app):
    """给这个进程装唯一的一份 Ctrl+V 过滤器；重复调用只装一次。"""
    global _PASTE_FILTER
    if _PASTE_FILTER is None:
        _PASTE_FILTER = PasteFilesFilter(app)
        app.installEventFilter(_PASTE_FILTER)
    return _PASTE_FILTER


class FormatKeyFilter(QObject):
    """SQL 编辑区上的 Ctrl+Alt+L：焦点在这块才生效。

    与 Ctrl+V 那条不同——粘贴要"鼠标停哪儿都算"所以挂应用级过滤；格式化只针对
    编辑区那一段 SQL，挂在控件自己的事件过滤上：焦点在连接区的输入框里按这串
    按键，不该把按键吃掉。
    窗口级 QShortcut 在这一套界面上实测抢不到按键（控件先消费），所以走事件过滤。
    """

    @staticmethod
    def is_format_key(event):
        mods = event.modifiers()
        return (event.key() == Qt.Key_L
                and bool(mods & Qt.ControlModifier) and bool(mods & Qt.AltModifier)
                and not mods & (Qt.ShiftModifier | Qt.MetaModifier))

    def __init__(self, callback):
        super().__init__()
        self._callback = callback

    def eventFilter(self, obj, event):
        if event.type() == QEvent.KeyPress and self.is_format_key(event):
            self._callback()
            return True
        return False


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

PAGE_INTRO_QSS = (
    "QLabel { color: #1f5fd0; background: #f4f8ff;"
    " border: 1px solid #8fb4ea; border-left: 3px solid #2f6feb;"
    " border-radius: 4px; padding: 4px 10px; font-weight: bold; }"
)


def selectable(lbl):
    """让一句纯文字能用鼠标拖选、Ctrl+C 抄走。

    只开 `TextSelectableByMouse`，不开 `TextSelectableByKeyboard`：后者会把这格文字
    放进焦点链，Tab 就会多停几站、方向键也不再翻表——那是"能复制"付出的交互税，
    按口径不该收。拖拽区那句提示也不开：它是点击热区，开了选区就吃掉那一下点击。
    """
    lbl.setTextInteractionFlags(Qt.TextSelectableByMouse)
    return lbl


def page_intro(text):
    """页签顶部那句介绍：三页同一块强调底板，一眼看出这一页把什么写进库。

    强调色与主按钮同一支（#2f6feb），两栈同值（Java 侧 gui.PageIntro）。
    """
    lbl = QLabel(text)
    lbl.setStyleSheet(PAGE_INTRO_QSS)
    return selectable(lbl)


def hline():
    """动作区与上方内容之间的细分隔线（两栈同一处：Java 侧用 JSeparator）。"""
    line = QFrame()
    line.setFixedHeight(1)
    line.setStyleSheet("background-color: #dbe3ee;")
    return line


def scope_label():
    """动作条上那句「这一下动几张表」：选中行会悄悄改范围，得摆在按钮旁边说清楚。

    两处讲究都是为了不动窗口的最小宽度：文字取短句（长解释放悬停提示），
    横向策略设成 Ignored——否则这行字的最小宽度就是整句的像素宽，窄屏上会把
    主窗口顶出屏幕（这一页的按钮早先就踩过，才套的横向滚动区）。
    """
    lbl = QLabel("范围：全量")
    lbl.setStyleSheet("color: #667085;")
    lbl.setSizePolicy(QSizePolicy.Ignored, QSizePolicy.Fixed)
    lbl.setMinimumWidth(0)
    return lbl


def equal_heights(*widgets):
    """同一排里的输入框与按钮统一取同一个高度。

    Qt 按各自样式算出来的高度差 1~2px：输入框 27、普通按钮 28、带 padding 的主按钮 26。
    单看每一个都正常，排成一行就是一眼能看见的参差（表名前缀那行、底部动作条都踩过），
    主按钮还会因为比同排矮一截把文字切掉半行。统一按最高的那个钉死。
    """
    widgets = [w for w in widgets if w is not None]
    if not widgets:
        return
    h = max(max(w.sizeHint().height(), w.minimumSizeHint().height())
            for w in widgets)
    for w in widgets:
        w.setFixedHeight(h)


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


def confirm_box(parent, title: str, text: str, ok_text: str = "确定",
                cancel_text: str = "取消", kind: str = "warning") -> bool:
    """是/否确认：与消息弹窗同一套排版，只把「确定」换成两颗各自写着动作的按钮。

    返回 True = 用户按了 ok_text 那颗。Esc、点关闭、按 cancel_text 都算"不"。
    删字段这类不可撤回的动作走这一道，不拿"点了就删"赌用户手没抖。
    """
    box = build_popup_box(parent, title, text, kind)
    box.setStandardButtons(QDialogButtonBox.NoButton)
    bar = box.buttons
    yes = bar.addButton(ok_text, QDialogButtonBox.AcceptRole)
    bar.addButton(cancel_text, QDialogButtonBox.RejectRole)
    yes.setDefault(True)
    bar.accepted.connect(box.accept)
    bar.rejected.connect(box.reject)
    return box.exec() == QDialog.Accepted



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


def auto_split_wide(td, out_dir=None, stem=None):
    """建模时行上限救不回来：按公共字段把这张表拆成几个 Excel。

    只有图形版 tab1 的解析流程调它（命令行两条线不改现场文件）。
    结构定义模式的表不拆：那类表的字段类型是人工写的承诺，拆出来的数据表会按
    "普通数据表"重新推断类型，等于把人工定义丢掉——只把拦下理由照旧报出来。
    返回 None 表示这张表没被拦下，本来就不用拆。
    """
    refuse = (td.get("row_fit") or {}).get("refuse")
    if not refuse:
        return None
    if td.get("source") == "structured":
        return {"ok": False, "files": [], "common": [], "rows": 0,
                "reason": "结构定义模式的表不自动拆（字段类型是人工写的）"}
    return split_wide_table(td, out_dir=out_dir, stem=stem)


def parse_import_files(files, existing_paths, zip_dir, dest_start, sink,
                       initials=False, upper=False, lower=False, auto_pk=False):
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
    zip_dirs = {}          # 解压出的路径 → 包内目录链，表名要从包根一路拼到文件名
    zip_home = {}          # 解压出的路径 → (压缩包所在目录, 包内叶子名)：拆出来的 Excel 落这儿
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
                inner = [p for p in x["origin"].replace("\\", "/").split("/")[:-1]
                         if p]
                if inner:
                    zip_dirs[x["path"]] = tuple(inner)
                zip_home[x["path"]] = (os.path.dirname(os.path.abspath(f)),
                                       os.path.splitext(os.path.basename(
                                           x["origin"].replace("\\", "/")))[0])
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
                                 upper=upper, lower=lower, auto_pk=auto_pk,
                                 dirs=zip_dirs.get(f, ()))
            # 行上限救不回来（row size too large / 列数超 1017）时顺手拆成几个 Excel：
            # 拆出来的文件落在原文件（压缩包）旁边，拖回来就是几张能分别建的表
            split_dir, split_stem = zip_home.get(f, (None, None))
            split = auto_split_wide(td, out_dir=split_dir, stem=split_stem)
            if split is not None:
                td["wide_split"] = split
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
            for msg in split_notice_lines(td, split or {}):
                sink.log(msg)
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
    return {"prefix": prefix, "n": len(tables), "cancelled": sink.cancelled}


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


def sql_insert_row_counts(record):
    """这份脚本里每张表的 INSERT / REPLACE 数据行数：{表名: 行数}。

    与「生成标准/简易 Excel」那一路同一个口径（core.sql_script.parse_insert_rows），
    但只留每张表第一行的值、其余只数行——清单上要的只是一个数，几十万行的 dump
    不值得为了这个数把整份值都转成 Python 对象。
    正文一改（改名、改表注释）就整份换成新字符串，这里按对象身份认缓存，
    换了正文自然重算一遍，不会把旧行数挂在改了名的表上。
    """
    text = record.get("text") or ""
    cached = record.get("insert_rows")
    if cached is not None and record.get("insert_rows_src") is text:
        return cached
    counts = {}
    for name, entry in parse_insert_rows(text, 1).items():
        counts[name] = entry.get("total", 0)
    record["insert_rows"] = counts
    record["insert_rows_src"] = text
    return counts


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
        record = {"path": p, "text": text, "modified": False,
                  "rename_map": {}, "display_map": {}, "tabs": tabs,
                  "parsed": parsed}
        # 清单上「数据行数」这一列要的数顺手在这条子线程里算好：刷新表格时不用
        # 为了一个数在主线程上再扫一遍整份脚本（10MB 脚本实测一次就是 1 秒上下）
        sql_insert_row_counts(record)
        loaded.append(record)
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


def load_databases_task(cfg, sink):
    """连库取列表这一趟长任务的正文（跑在子线程里）。

    失败不往任务框架外面抛：那条路会变成一句「任务异常」，而"连不上库"有自己
    既定的弹窗与日志口径（`连接失败` + `库列表未加载`），所以把原因随结果一起回给界面。
    返回 (库列表或 None, 失败原因或 None)，永远不为 None——不然收尾会被当成"没做成"跳过。
    """
    sink.step("连接 %s:%s" % (cfg["host"], cfg["port"]))
    try:
        return fetch_databases(cfg), None
    except Exception as e:
        return None, describe_db_error(e)


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
                "执行前先 DROP " + "、".join(dropped) if dropped else COVER_NOTHING_BODY))
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

    sets = []
    dropped = [0]

    def collect(data):
        # 一批里可能有几十上百条 select：只留最近这些，够人回看，也不会把内存吃穿。
        # 丢的是最早那一个——用户回看的一般是刚跑出来的那条。
        if len(sets) >= MAX_RESULT_SETS:
            sets.pop(0)
            dropped[0] += 1
        sets.append(data)

    try:
        return {"executed": run_sql_statements(conn, text, sink.log,
                                               progress=progress,
                                               cancelled=lambda: sink.cancelled,
                                               result_cb=collect),
                "sets": sets, "sets_dropped": dropped[0]}
    finally:
        try:
            conn.close()
        except Exception:
            pass


def _sql_naming(name, initials, upper, lower, is_table):
    """第二页的命名开关：只重写含中文的名字；纯英文只在勾了大小写档时改大小写。

    脚本里的英文标识符本来就是合法表名/字段名，两颗都不勾时一个字不动；
    勾「取拼音首字母」才有转写动作，勾「转大写」或「转小写」才有大小写动作。
    """
    if initials and re.search(r"[\u4e00-\u9fff]", name):
        name = (sanitize_table_name(name, initials=True)
                if is_table else _col_to_field(name, initials=True))
    return case_switch(name, upper, lower)


def _sql_data_frame(data, orig_fields, mapping):
    """一张表的 INSERT 结果 -> (数据帧, 要点名的说明)。

    帧的列头用生成物那一侧的字段名（改过大小写的也是），值按脚本里的原列名取；
    省略列清单的语句按建表列序对位。哪一列该取网格的第几格由写盘那一步
    （`core.meta_export.write_table`）自己按列头找，表定义里没有的列自然写空——
    Java 侧的 `MetaExport.columnMap` 是同一条规则，两端不必各排一次下标。
    """
    import pandas as pd

    notes = []
    cols = data["columns"]
    # 列名在 MySQL 里不分大小写：脚本写 `Name`、表定义是 `name` 也要对到同一格
    by_lower = dict((f.lower(), mapping.get(f, f)) for f in orig_fields)
    if cols is None:
        header = [mapping.get(f, f) for f in orig_fields]
        kept = [row for row in data["rows"] if len(row) == len(orig_fields)]
        dropped = len(data["rows"]) - len(kept)
    else:
        header = [by_lower.get(c.lower(), c) for c in cols]
        kept = list(data["rows"])
        dropped = 0
        for c in cols:
            if c.lower() not in by_lower:
                notes.append("列 %s 不在表定义里，这一列的值不写" % c)
    if dropped:
        notes.append("有 %d 行的值个数与建表列数对不上，这些行不写" % dropped)
    if data["total"] > len(data["rows"]):
        notes.append("脚本里共 %d 行，只写入前 %d 行（上限 SQL_EXPORT_ROW_CAP）"
                     % (data["total"], len(data["rows"])))
    if not kept:
        return None, notes
    return pd.DataFrame(kept, columns=header, dtype=object), notes


# xlsx 一个 Sheet 的物理上限：1048576 行（含表头那一行）。这里不设人为的条数上限，
# 只按这个物理上限留余量——一张表的「数据」Sheet 最多写它的 2/3，再多的在日志点名。
XLSX_MAX_ROWS = 1048576
SQL_EXPORT_ROW_CAP = XLSX_MAX_ROWS * 2 // 3  # 699050


def sql_meta_export_task(items, out_root, with_meta, upper, sink, lower=False,
                         auto_pk=False):
    """「SQL 脚本改名」页的生成标准/简易 Excel：列定义取 CREATE TABLE，数据取 INSERT。

    items 为 [(path, text), ...]，text 取的是页面上改过名之后的当前正文，
    所以"先批量改前缀、再生成交付件"这条路是通的——产物里的表名就是新表名。
    脚本里没有 INSERT 时「数据」Sheet 仍只有英文字段名那一行表头，那是给下游填的模板；
    有 INSERT 就把值按字面量转出来写进去，每张表最多写 SQL_EXPORT_ROW_CAP 行
    （= xlsx 单表上限 1048576 的 2/3，约 70 万行；不再按 10000 截断），
    超出的只在日志里点名（脚本正文本来就已整份在内存里，截断省的是转值与写盘）。
    第二页的大小写开关只作用在清单、元数据视图和生成物上；脚本正文一个字不动。
    """
    from core.meta_export import export_tables, SQL_DIR_TAG
    from core.sql_script import parse_insert_rows

    if upper:
        sink.log("[命名] 生成物表名与字段名：转大写")
    elif lower:
        sink.log("[命名] 生成物表名与字段名：转小写")
    tables, where, used_tables = [], {}, {}
    taken, orphan = set(), {}
    added_pk = kept_pk = 0
    if auto_pk:
        sink.log("[主键] 自动补全主键：开（没有主键的表在字段首位补 %s，"
                 "脚本里有 INSERT 的按行填唯一值）" % AUTO_PK_FIELD)
    for path, text in items:
        if sink.cancelled:
            break
        base = os.path.basename(path)
        sink.note("解析 %s 的表定义…" % base)
        data = parse_insert_rows(text, SQL_EXPORT_ROW_CAP)
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
            orig_fields = [c["field"] for c in t["columns"]]
            mapping = {f: f for f in orig_fields}
            if upper or lower:
                t = dict(t)
                t["table_name"] = dedup_field(
                    used_tables, _sql_naming(t["table_name"], False, upper, lower, True))
                seen_fields = {}
                renamed = []
                for c in t["columns"]:
                    new_field = dedup_field(
                        seen_fields, _sql_naming(c["field"], False, upper, lower, False))
                    mapping[c["field"]] = new_field
                    renamed.append(dict(c, field=new_field))
                t["columns"] = renamed
            rows = data.pop(name, None) if name not in taken else None
            taken.add(name)
            if rows is not None:
                frame, notes = _sql_data_frame(rows, orig_fields, mapping)
                for note in rows["notes"] + notes:
                    sink.log("! [WARN] 说明 | `%s`：%s" % (name, note))
                if frame is not None:
                    t["df"] = frame
            if auto_pk:
                # 数据先挂上再补列：apply_auto_pk 按 df 的行数逐行给唯一值，
                # 脚本里没有 INSERT 时 df 还不存在，这一张就只补字段定义
                moved, _why = apply_auto_pk(t, True, upper=upper, lower=lower)
                if moved:
                    added_pk += 1
                else:
                    kept_pk += 1
            tables.append(t)
        for extra, rows in data.items():
            orphan[extra] = (base, rows["total"], extra in taken)
    for extra, (base, total, dup) in orphan.items():
        if dup:
            sink.log("! [WARN] 跳过 | `%s`：%s 里的 %d 行数据不写，这张表只按第一次出现的那份"
                     "定义导出" % (extra, base, total))
        else:
            sink.log("! [WARN] 跳过 | `%s`：%s 里有 %d 行 INSERT 数据，但脚本里没有它的"
                     " CREATE TABLE，没有列定义可导出，整张表不写" % (extra, base, total))
    if auto_pk:
        sink.log("[主键] 本轮补 %d 张，%d 张已有主键或已有同名字段不动"
                 % (added_pk, kept_pk))
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
                refuse = (t.get("row_fit") or {}).get("refuse")
                if refuse:
                    # 建模阶段就判得出这条 DDL 会被库拒（1118/1117），不发出去：
                    # 建表是隐式提交，半途报错既回滚不了也说不清，现场只会看到"导入失败"
                    result["msg"] = refuse["reason"]
                    hint = ""
                    split = t.get("wide_split") or {}
                    if split.get("ok"):
                        # 拆出来的 Excel 已经在建模那一步落盘了，这里只提醒去拖回来，
                        # 别再让人对着拦下理由找"那我该怎么办"
                        hint = ("\n已自动拆成 %d 个 Excel（按公共字段 %d 列）：%s；"
                                "把这几份拖回待导入列表就能分别建表"
                                % (len(split["files"]), len(split["common"]),
                                   "、".join(os.path.basename(x["file"])
                                            for x in split["files"])))
                    rep.log_error("[建表前拦下] 表 `%s`\n%s%s"
                                  % (name, row_fit_notice(refuse), hint))
                    results.append(result)
                    continue
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
    # 第三行是粘贴入口的提示：Ctrl+V 在窗口层级监听，等同把文件拖进这块虚线区。
    PASTE_HINT = "快捷键：复制文件后按 Ctrl+V 直接添加"
    EXCEL_HINT = "将 Excel 文件或 ZIP 压缩包拖到此处，或点击选择文件\n" \
                 "（支持 .xlsx / .xls / .zip，可多选）\n" + PASTE_HINT
    SQL_HINT = "将 SQL 脚本拖到此处，或点击选择文件\n（支持 .sql，可多选）\n" + PASTE_HINT
    RUN_HINT = "将 SQL 脚本拖到此处，或点击选择文件\n" \
               "（支持 .sql，可多选；按列表顺序执行）\n" + PASTE_HINT

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
        self._conn_dirty = False   # 改过主机/端口/用户名/密码：手上的库清单来自上一次连接
        self._conn_load_cfg = None  # 正在跑的这次连接用的是哪一份配置（回执要报出来）
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
        # Ctrl+V 挂在应用级按键过滤上，不挂窗口级 QShortcut：焦点一旦落进输入框或
        # SQL 编辑区，那条按键会被控件自己吃掉，QShortcut 实测轮不到，
        # 三页虚线区那句"复制文件后按 Ctrl+V 直接添加"就成了空话。
        # 过滤在最前面，先看剪贴板里是不是文件，是就一律归"添加文件"，不看焦点。
        self.paste_filter = install_paste_filter(QApplication.instance())
        self._log_font_choice()
        self._load_default_conn()

    def _log_font_choice(self):
        """开局说一句界面字体是哪一支、画不画得出汉字。

        与 Java 侧 MainFrame.logFontChoice 同一句：这一档坏掉时界面一个字都不报错，
        现场（2026-11-05 Win7 那次整页中文成空方框）只能靠抄这一行定案。
        读的是真控件上的字体与真字形判据，不是再抄一遍常量。
        """
        font = self.file_table.font()
        family = QFontInfo(font).family() or APP_FONT_FAMILY or font.family()
        shows = self.file_table.fontMetrics().inFont(u"中")
        append_log(self.log, "[界面] 字体：%s（%s）"
                   % (family, "可显示中文" if shows else "画不出中文"))
        if not shows:
            append_log(self.log,
                       "! [WARN] 界面 | 界面字体没有中文字形，中文会显示成空方框："
                       "随包字体没进包或被裁掉，请核对包内 %s" % APP_FONT_FILE,
                       error=True)

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
        self.btn_reload_cfg = QPushButton("刷新配置文件")
        self.btn_reload_cfg.setToolTip(
            "换了程序同级的 conn.ini 或 E2M_* 环境变量后点这里：重读配置并按新连接信息重连")
        self.btn_reload_cfg.clicked.connect(self.on_reload_conn_config)
        self.btn_conn_edit = QPushButton("设置数据源信息")
        self.btn_conn_edit.setToolTip("展开填写主机 / 端口 / 用户名 / 密码，并测试连接与选择数据库")
        self.btn_conn_edit.clicked.connect(lambda: self.set_conn_expanded(True))
        bar_lay.addWidget(self.lbl_conn_state, 1)
        bar_lay.addWidget(self.btn_reload_cfg)
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
        # 那一格灰着/清单过期时的就地说明：只有顶部状态栏一句话的话，
        # 用户盯着这颗点不动的下拉只会读成"目标库不给换"（现场报的就是这句）
        self.lbl_db_hint = selectable(QLabel())
        self.lbl_db_hint.setStyleSheet("color: #666;")
        self.lbl_db_hint.setWordWrap(True)
        f.addRow(selectable(QLabel("主机：")), self.ed_host)
        f.addRow(selectable(QLabel("端口：")), self.ed_port)
        f.addRow(selectable(QLabel("用户名：")), self.ed_user)
        f.addRow(selectable(QLabel("密码：")), self.ed_pass)
        f.addRow(selectable(QLabel("目标库：")), h)
        f.addRow("", self.lbl_db_hint)
        # 四项里任一改动就把清单标成"来自上一次连接"：不拦人也不清空，只把话说在前面
        self.ed_host.textChanged.connect(self._mark_conn_dirty)
        self.ed_port.valueChanged.connect(self._mark_conn_dirty)
        self.ed_user.textChanged.connect(self._mark_conn_dirty)
        self.ed_pass.textChanged.connect(self._mark_conn_dirty)
        conn_lay.addWidget(self.conn_form)
        root.addWidget(conn)

        # 2. 功能页签：Excel 导入 / SQL 脚本改名 / SQL 执行器（位于数据库信息下方）
        self.main_tabs = style_tab_scroll(QTabWidget())
        excel_page = QWidget()
        excel_lay = QVBoxLayout(excel_page)

        # 2.0 一句话介绍：与「SQL 执行器」那页同一块强调底板
        #（页内不再各写一条"目标库：xxx —— …"，当前库只看顶部状态栏那一处）
        self.imp_intro = page_intro("中英表头建模，回写库")
        excel_lay.addWidget(self.imp_intro)

        # 2.1 文件拖拽区
        self.drop = DropArea()
        self.drop.files_dropped.connect(self.on_files_dropped)
        self.drop.clicked.connect(self.on_add_files)

        # 3. 文件列表
        file_box = QGroupBox(FILE_TABLE_TITLE)
        fl = QVBoxLayout(file_box)

        # 命名规则工具条贴表格上沿：它管的是"表名/字段名怎么起"，与表格内容同组，
        # 但不占下面前缀行——前缀行要留给前缀输入框与两颗按钮。
        # 只把首字母勾上：新加的文件按「首字母 + 小写」起名，转大写由用户按需勾。
        # 在连信号之前设好，免得启动时凭空打一行"切换"日志
        name_row = QHBoxLayout()
        name_row.addWidget(selectable(QLabel("命名规则：")))
        self.chk_initials = QCheckBox("表名和字段名取中文拼音首字母")
        self.chk_initials.setChecked(True)
        self.chk_initials.setToolTip(
            "由中文转写的表名与字段名用拼音首字母（订单金额 → ddje、\n"
            "水库基础信息 → skjcxx）；默认已勾选，取消则用全拼（ding_dan_jin_e）。\n"
            "文件名括号里显式写下的英文表名不受影响；对之后添加的文件生效。")
        self.chk_initials.toggled.connect(self.on_initials_toggled)
        self.chk_upper = QCheckBox("表名与字段名转大写")
        self.chk_upper.setToolTip(
            "由中文或文件名转写出来的表名与字段名全部大写\n"
            "（ding_dan_jin_e → DING_DAN_JIN_E、水库基础信息 → SHUI_KU_JI_CHU_XIN_XI）；\n"
            "默认不勾选，勾上才转大写。文件名括号里写下的英文表名同样转大写；\n"
            "结构定义 Sheet 里人工写下的字段名也一起转大写，两颗大小写开关都不勾\n"
            "则保持写下的原样；与「表名与字段名转小写」互斥，勾这颗会取消那一颗；\n"
            "对之后添加的文件生效（表名前缀按输入原样拼接，不跟着转）。")
        self.chk_upper.toggled.connect(self.on_upper_toggled)
        self.chk_lower = QCheckBox("表名与字段名转小写")
        self.chk_lower.setToolTip(
            "由中文转写出来的表名与字段名本来就落在小写（ding_dan_jin_e、skjcxx），\n"
            "这一档在转写那一路与不勾同结果；文件名括号里写下的英文表名本来也统一转小写。\n"
            "这颗真管到的是结构定义 Sheet 里人工手写的字段名：勾上才把它们转成小写\n"
            "（ITEM_CODE → item_code），两颗都不勾时一个字母都不动；\n"
            "与「表名与字段名转大写」互斥，勾这颗会取消那一颗；\n"
            "对之后添加的文件生效（表名前缀按输入原样拼接，不跟着转）。")
        self.chk_lower.toggled.connect(self.on_lower_toggled)
        name_row.addWidget(self.chk_initials)
        name_row.addWidget(self.chk_upper)
        name_row.addWidget(self.chk_lower)
        name_row.addStretch(1)
        fl.addLayout(name_row)

        self.file_table = EmptyHintTable(
            0, 7, "请把 Excel 文件或 ZIP 压缩包拖到上方虚线区")
        # 按整行选中：Del 删的是"这一行的文件"，按格选会让人以为删的是那一格
        self.file_table.setSelectionBehavior(QAbstractItemView.SelectRows)
        style_table_font(self.file_table)
        self.file_table.setHorizontalHeaderLabels(
            ["序号", "文件", "表名", "表注释", "字段数", "数据行数", "主键"])
        # 列宽可自由拖动调节；最后一列自动填满剩余空间
        header = self.file_table.horizontalHeader()
        header.setSectionResizeMode(QHeaderView.Interactive)
        header.setStretchLastSection(True)
        self.file_table.setColumnWidth(0, SERIAL_COL_PX)
        self.file_table.setColumnWidth(1, 320)
        self.file_table.setColumnWidth(2, 200)
        self.file_table.setColumnWidth(3, 280)
        self.file_table.setColumnWidth(4, 80)
        self.file_table.setColumnWidth(5, 90)
        self.file_table.setColumnWidth(6, 160)
        self.file_table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        # 与「SQL 脚本改名」那页同一交互：双击「表名」「表注释」进编辑器，双击其余列看结构。
        # 触发器关掉仍能 editItem，避免单击别的列也弹编辑框（第二页同一个做法）
        self.file_table.cellDoubleClicked.connect(self.on_edit_structure_row)
        self.file_table.itemChanged.connect(self.on_file_table_item_changed)
        self.file_table.del_pressed.connect(self.on_delete_selected_files)
        fl.addWidget(self.file_table)

        # 表名前缀（批量应用到全部表）
        prefix_row = QHBoxLayout()
        prefix_row.addWidget(selectable(QLabel("表名前缀：")))
        self.ed_prefix = QLineEdit()
        self.ed_prefix.setPlaceholderText(PREFIX_PLACEHOLDER)
        self.btn_apply_prefix = QPushButton("批量应用前缀")
        self.btn_apply_prefix.setToolTip(
            "在清单里每张表名前面加上这一段（重复点不会叠加）；填在这里之后，"
            "新添加的文件也自动带上。\n留空再点一次即移除前缀。")
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
        # 输入框与两颗按钮同一高度：Qt 各自算出来差 1~2px，并排看就是参差
        equal_heights(self.ed_prefix, self.btn_apply_prefix, self.btn_replace_prefix)
        fl.addLayout(prefix_row)

        btns = QHBoxLayout()
        self.btn_clear = QPushButton("清空待上传文件")
        self.btn_clear.clicked.connect(self.on_clear)
        self.btn_preview = QPushButton("生成表结构预览")
        self.btn_preview.clicked.connect(self.on_preview_structure)
        self.btn_ddl = QPushButton("预览 DDL")
        self.btn_ddl.clicked.connect(self.on_preview_ddl)
        # 统计按钮也放在这一排：它读的就是表格里「数据行数」那一列，与清单同组同位置
        self.btn_stat_rows = QPushButton("统计数据总行数")
        self.btn_stat_rows.setToolTip(
            "把清单里每张表的「数据行数」加起来，弹窗与日志报同一个总数；\n"
            "数的是已解析出来的数据行（不含表头），不改清单也不碰磁盘上的文件")
        self.btn_stat_rows.clicked.connect(self.on_stat_total_rows)
        # 四颗都是次级操作：等宽铺满整行，不跟底部的执行按钮抢注意力
        btns.addWidget(self.btn_clear, 1)
        btns.addWidget(self.btn_preview, 1)
        btns.addWidget(self.btn_ddl, 1)
        btns.addWidget(self.btn_stat_rows, 1)
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
        # 左列是执行选项，右侧才是动作按钮。页内进度条已经拿掉：导入本身弹的是
        # 那个非模态进度框（每张表 + 每行数据都计项），同一条进度不该在窗口里再摆一份，
        # 而解析 / 前缀 / 生成 / SQL 页那些任务期间这条恒为 0 的杠只会让人以为界面坏了。
        # 主按钮用强调色，停止与两颗生成按钮保持普通样式，一屏里只留一个视觉重点。
        excel_lay.addWidget(hline())
        self.excel_action_bar = ActionBar()
        op = QHBoxLayout(self.excel_action_bar)
        op.setContentsMargins(10, 6, 10, 6)
        self.chk_drop = QCheckBox("覆盖已存在表（先 DROP 再建）")
        op.addWidget(self.chk_drop)
        self.chk_auto_pk = QCheckBox("自动补全主键")
        self.chk_auto_pk.setToolTip(
            "勾选后，一个主键字段都没有的表会在字段顺序首位补一个 %s 主键字段\n"
            "（VARCHAR(32)、注释「业务主键」），有数据行的每行各给一个 32 位十六进制 UUID；\n"
            "已有主键的表不动，表里已经写着 %s 的也不动（补进去建表会重名报错）；\n"
            "一行数据都没有的空表只补字段定义，没有值可填。\n"
            "取消勾选就收回补进去的那一列，人工勾选的主键一个都不碰；\n"
            "对清单里已加载的文件即时生效，勾上之后再添加的文件同样按这一档处理。"
            % (AUTO_PK_FIELD, AUTO_PK_FIELD))
        self.chk_auto_pk.toggled.connect(self.on_auto_pk_toggled)
        op.addWidget(self.chk_auto_pk)
        op.addStretch(1)
        # 动作条上这支小字：这一排按钮这次到底动几张表。选中行会静悄悄地改范围，
        # 不写出来的话，改完一行名再点前缀的人会以为按钮坏了（只动了一行）。
        self.lbl_scope = scope_label()
        self.lbl_scope.setToolTip(
            "按钮区默认作用于整份清单；在表格里选中几行，就只作用于选中的这几行。\n"
            "没选中任何行时仍按全量走。")
        op.addWidget(self.lbl_scope)
        # 这里原来有一句 op.addSpacing(12) 用来隔开选项与动作按钮；现在中间多了这支小字，
        # 它自己就是那道间隔，再留 12px 只会把动作条的最小宽度顶宽（主窗口的最小宽度
        # 本来就贴着 800 宽的小屏，多 6px 就出界）
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
            "在程序同级建 meta_data_file_年月日 目录，"
            "每张表一个 xlsx（元数据 + 数据两个 Sheet）")
        self.btn_gen_std.setEnabled(False)
        self.btn_gen_std.clicked.connect(self.on_gen_standard)
        self.btn_gen_simple = QPushButton("生成简易excel文件")
        self.btn_gen_simple.setToolTip(
            "在程序同级建 simple_meta_data_file_年月日 目录，"
            "每张表一个 xlsx（只有数据 Sheet）")
        self.btn_gen_simple.setEnabled(False)
        self.btn_gen_simple.clicked.connect(self.on_gen_simple)
        op.addWidget(self.btn_gen_std)
        op.addWidget(self.btn_gen_simple)
        # 这一排五个控件统一高度：主按钮带自己的 padding，sizeHint 比邻近按钮矮 2px，
        # 之前那一颗蓝按钮的文字被切掉半行，看着像坏了
        equal_heights(self.chk_drop, self.chk_auto_pk, self.btn_stop, self.btn_import,
                      self.btn_gen_std, self.btn_gen_simple)
        excel_lay.addWidget(self.excel_action_bar)

        self.main_tabs.addTab(excel_page, "Excel 导入")
        self.main_tabs.addTab(self._build_sql_page(), "SQL 脚本改名")
        self.main_tabs.addTab(self._build_sql_run_page(), "SQL 执行器")
        root.addWidget(self.main_tabs, 1)
        # 切页签 / 切换目标库 / 改主机端口时，刷新状态栏与三页顶部那条指向
        #（host:port 只在状态栏的悬停提示里，改了主机端口要同步，否则提示的是旧地址）
        self.main_tabs.currentChanged.connect(self._on_tab_changed)
        # 选中行一变就改动作条上那句「作用范围」：范围是这一版的行为核心，
        # 不能只写在点下去之后的日志里
        self.file_table.itemSelectionChanged.connect(self._refresh_scope_hint)
        self.sql_table.itemSelectionChanged.connect(self._refresh_scope_hint)
        self._refresh_scope_hint()
        self.cb_db.currentIndexChanged.connect(self._on_db_changed)
        self.ed_host.textChanged.connect(self._refresh_db_labels)
        self.ed_port.valueChanged.connect(self._refresh_db_labels)
        # 启动即收起（用户口径：默认收起连接数据源信息）；要填连接点「设置数据源信息」展开，
        # 选定库之后也自动收起
        self.set_conn_expanded(False)
        self._refresh_db_labels()

    # -------------------- 当前目标库状态条（一条总状态，页内不再各写一条指向） --------------------
    def set_conn_expanded(self, expanded):
        """连接表单展开/收起：收起后整块环境信息只剩一条"当前是哪个库"的状态栏。"""
        self._conn_expanded = bool(expanded)
        self.conn_form.setVisible(expanded)
        self.btn_conn_edit.setVisible(not expanded)
        # 状态栏那句告警要跟着换说法：让人去点的那颗按钮必须真的在界面上
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
        self._refresh_db_hint(db)
        if db:
            self.lbl_conn_state.setText("● 目标库 %s" % db)
            self.lbl_conn_state.setToolTip("%s@%s:%s（用户 %s）"
                                           % (db, cfg["host"], cfg["port"], cfg["user"]))
            self.lbl_conn_state.setStyleSheet("color: #1a7f37; font-weight: bold;")
            return
        if self._conn_expanded:
            self.lbl_conn_state.setText("⚠ 未选择目标库——填好下面连接信息后点「连接并加载库」")
        else:
            self.lbl_conn_state.setText("⚠ 未选择目标库——点右侧「设置数据源信息」展开后填写")
        self.lbl_conn_state.setToolTip(NO_DB_HINT)
        self.lbl_conn_state.setStyleSheet("color: #c62828; font-weight: bold;")

    def _refresh_db_hint(self, db):
        """目标库那一行旁边的就地说明：清单没加载与清单已过期是两件事，分开说。

        加载中（格子还灰着）与改过连接信息（清单来自上一台服务器）都让用户以为
        "这颗下拉不给用"，而这两种情况下要点的那颗按钮是同一颗——写在格子边上才看得到。
        """
        if not self.cb_db.isEnabled():
            self.lbl_db_hint.setText(DB_HINT_UNLOADED)
        elif self._conn_dirty:
            self.lbl_db_hint.setText(DB_HINT_STALE)
        else:
            self.lbl_db_hint.setText("")

    def _mark_conn_dirty(self, *_a):
        """改过主机/端口/用户名/密码 = 手上这份库清单不再作数，先说出来，别替用户决定。

        不清空也不禁用：清空会在人逐字填表时反复打断，而"清单来自上一次连接"这句话
        该由界面说，不该靠猜。真正加载一次（成功或失败）之后再回到各自的状态。
        """
        if not self._conn_dirty:
            self._conn_dirty = True
        self._refresh_db_labels()

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
    # 连库取库列表占自己一位：它不动任何一页的列表，导入跑到一半也该能换库
    RES_CONN = "conn"
    RES_LABEL = {
        "excel": "Excel 导入页的待导入列表",
        "sql": "SQL 脚本改名页的脚本列表",
        "run": "SQL 执行器页的执行结果区（编辑区 SQL 与脚本清单共用）",
        "conn": "连接区（连库取库列表）",
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

    def _run_modal(self, title, work, args=(), on_done=None, log_view=None,
                   resources=None):
        """统一入口：把纯工作函数 work(sink, *args) 放到子线程跑，弹一个非模态进度框。

        非模态是为了不挡窗口：框挡在中间时另外两页根本点不动，用户只能等或者强关窗口。
        同一页的任务互斥、不同页可以并行，每个任务自己的框显示自己的进度。
        MODAL_PROGRESS=False（离屏用例）时不起线程、不弹框，原地跑完再回调，
        两条路径共用同一个 work 和同一个 TaskInfo，测试跑到的就是线上那段逻辑。

        resources 不给就按日志去向认发起页；连接区那种不属任何一页的活儿自己报一位，
        否则会被算成 Excel 页的任务，导入跑着时连不上库。
        """
        view = log_view if log_view is not None else self.log
        resources = tuple(resources) if resources else self._resources_for(view)
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
        # 第三页的「批量执行脚本文件」判据与这里同一口径：有脚本、且这一页没任务在跑
        run_on = bool(self.sql_run_files) and not self.task_busy((self.RES_RUN,))
        self.btn_run_scripts.setEnabled(run_on)
        # 行内那颗【执行】跟着同一判据灰：任务跑着的时候抽一行，回报的项数就对不上清单
        for i in range(self.sql_run_table.rowCount()):
            cell = self.sql_run_table.cellWidget(i, 4)
            row_btn = cell.findChild(QPushButton) if cell is not None else None
            if row_btn is not None:
                row_btn.setEnabled(run_on)

    # -------------------- 槽函数 --------------------
    def on_clear_log(self):
        self.log.clear()

    # -------------------- SQL 脚本改名页 --------------------
    def _build_sql_page(self):
        page = QWidget()
        lay = QVBoxLayout(page)

        # 一句话介绍：与另两页同一块强调底板
        self.sql_intro = page_intro("解析建表语句，回写库")
        lay.addWidget(self.sql_intro)

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
        name_bar.addWidget(selectable(QLabel("命名规则：")))
        self.chk_sql_upper = QCheckBox("表名与字段名转大写")
        self.chk_sql_upper.setToolTip(
            "勾选后，「当前表名」清单、双击打开的表元数据以及生成的 Excel 里的表名与字段名全部大写\n"
            "（t_order → T_ORDER、order_amount → ORDER_AMOUNT）；默认不勾选。\n"
            "脚本正文不改，手动改过的新表名会保留其输入内容；\n"
            "与「表名与字段名转小写」互斥，勾这颗会取消那一颗。")
        self.chk_sql_upper.toggled.connect(self.on_sql_upper_toggled)
        name_bar.addWidget(self.chk_sql_upper)
        self.chk_sql_lower = QCheckBox("表名与字段名转小写")
        self.chk_sql_lower.setToolTip(
            "勾选后，「当前表名」清单、双击打开的表元数据以及生成的 Excel 里的表名与字段名全部小写\n"
            "（T_ORDER → t_order、ORDER_AMOUNT → order_amount）；默认不勾选。\n"
            "脚本正文不改，手动改过的新表名会保留其输入内容；\n"
            "与「表名与字段名转大写」互斥，勾这颗会取消那一颗。")
        self.chk_sql_lower.toggled.connect(self.on_sql_lower_toggled)
        name_bar.addWidget(self.chk_sql_lower)
        name_bar.addStretch(1)

        self.sql_table = EmptyHintTable(0, 6, "请把 SQL 脚本拖到上方虚线区")
        style_table_font(self.sql_table)
        # 「CREATE 出现次数」这一列去掉：同一份脚本里一张表被写两遍，日志已经逐条点名，
        # 现场清单里那一列全是 1，占着宽度只让人以为坏了；表注释是"这张表是干什么的"，
        # 与「Excel 导入」那页同一列同名同位置，导入前先在这张表上就能核对。
        # 「数据行数」摆在最后一列：与第一页同名列同一个口径（脚本里 INSERT/REPLACE 的行数）
        self.sql_table.setHorizontalHeaderLabels(
            ["序号", "文件", "原表名", "当前表名", "表注释", "数据行数"])
        header = self.sql_table.horizontalHeader()
        header.setSectionResizeMode(QHeaderView.Interactive)
        header.setStretchLastSection(True)
        self.sql_table.setColumnWidth(0, SERIAL_COL_PX)
        self.sql_table.setColumnWidth(1, 300)
        self.sql_table.setColumnWidth(2, 260)
        self.sql_table.setColumnWidth(3, 200)
        self.sql_table.setColumnWidth(4, 280)
        self.sql_table.setColumnWidth(5, 90)
        self.sql_table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.sql_table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.sql_table.itemDoubleClicked.connect(self.on_sql_table_double_clicked)
        self.sql_table.itemChanged.connect(self.on_sql_table_item_changed)
        # 表格与下面的两行操作条作为一个整体参与高度缩放，先不挂到页面布局上

        prefix_row = QHBoxLayout()
        prefix_row.addWidget(selectable(QLabel("表名前缀：")))
        self.ed_sql_prefix = QLineEdit()
        self.ed_sql_prefix.setPlaceholderText(PREFIX_PLACEHOLDER)
        self.btn_sql_prefix = QPushButton("批量应用前缀")
        self.btn_sql_prefix.setToolTip(
            "只改「当前表名」列的显示（双击看的元数据表名同步），脚本正文不动；\n"
            "点保存/执行/生成时才把显示名写进脚本。前缀留空再点一次，显示恢复为脚本原名。\n"
            "新添加的脚本不会自动带上这一段，要点了这颗按钮才改。")
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
        # 与第一页同一档：输入框与两颗按钮同高
        equal_heights(self.ed_sql_prefix, self.btn_sql_prefix, self.btn_sql_replace)

        # 三颗操作按钮排在表格下面第一排（前缀行的下一行），与「Excel 导入」那页
        # 同一位置、同一形状：等宽铺满整行。清空是整份清出清单，不是只删选中行。
        # 不塞进保存/执行那一排——那排的最小宽度已经贴着窗口宽度，再加一颗就顶出屏幕
        # （U7 那条"整窗落在可用区内"实测被顶到 848 > 可用 800）。
        name_row = QHBoxLayout()
        self.btn_sql_clear = QPushButton("清空待上传文件")
        self.btn_sql_clear.clicked.connect(self.on_sql_clear_files)
        self.btn_sql_preview = QPushButton("生成表结构预览")
        self.btn_sql_preview.setToolTip(
            "打开当前选中那一行的字段清单（与双击「文件」「原表名」那一列是同一个框）；"
            "没选中行时取清单第一行。")
        self.btn_sql_preview.clicked.connect(self.on_sql_preview_structure)
        self.btn_sql_ddl = QPushButton("预览 DDL")
        self.btn_sql_ddl.setToolTip(
            "一张表一个页签，正文就是「保存修改后ddl脚本」「执行修改后ddl脚本到当前库」"
            "将要写出的那句 CREATE TABLE（含还没落地的改名与字段编辑）；\n"
            "勾选「覆盖已存在表」时，页签开头会补上执行前那句 DROP TABLE IF EXISTS。")
        self.btn_sql_ddl.clicked.connect(self.on_sql_preview_ddl)
        # 统计按钮与第一页同名同位置：读的就是表格最后一列「数据行数」
        self.btn_sql_stat_rows = QPushButton("统计数据总行数")
        self.btn_sql_stat_rows.setToolTip(
            "把清单里每张表的「数据行数」加起来，弹窗与日志报同一个总数；\n"
            "数的是脚本里 INSERT/REPLACE 的数据行（没有 INSERT 的表记 0），"
            "不改脚本也不碰磁盘上的文件")
        self.btn_sql_stat_rows.clicked.connect(self.on_sql_stat_total_rows)
        name_row.addWidget(self.btn_sql_clear, 1)
        name_row.addWidget(self.btn_sql_preview, 1)
        name_row.addWidget(self.btn_sql_ddl, 1)
        name_row.addWidget(self.btn_sql_stat_rows, 1)

        self.btn_sql_save = QPushButton("保存修改后ddl脚本")
        self.btn_sql_save.setEnabled(False)
        self.btn_sql_save.setStyleSheet(SECONDARY_BUTTON_QSS)
        self.btn_sql_save.clicked.connect(self.on_sql_save_modified)
        self.btn_sql_run = QPushButton("执行修改后ddl脚本到当前库")
        self.btn_sql_run.setEnabled(False)
        self.btn_sql_run.setStyleSheet(PRIMARY_BUTTON_QSS)
        self.btn_sql_run.clicked.connect(self.on_sql_execute)
        # 两颗生成按钮排在「执行修改后ddl脚本到当前库」右侧：与 Excel 导入页那两颗同一排同一口径，
        # 数据源换成了脚本正文，列定义看 CREATE TABLE、数据看 INSERT
        self.btn_sql_gen_std = QPushButton("生成标准excel文件")
        self.btn_sql_gen_std.setToolTip(
            "在程序同级建 sql_meta_data_file_年月日 目录，"
            "每张表一个 xlsx（元数据 + 数据两个 Sheet，每张最多 %d 行）"
            % SQL_EXPORT_ROW_CAP)
        self.btn_sql_gen_std.setEnabled(False)
        self.btn_sql_gen_std.clicked.connect(lambda: self.on_sql_gen_excel("标准", True))
        self.btn_sql_gen_simple = QPushButton("生成简易excel文件")
        self.btn_sql_gen_simple.setToolTip(
            "在程序同级建 sql_simple_meta_data_file_年月日 目录，"
            "每张表一个 xlsx（只有数据 Sheet，每张最多 %d 行）"
            % SQL_EXPORT_ROW_CAP)
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
        self.chk_sql_auto_pk = QCheckBox("自动补全主键")
        self.chk_sql_auto_pk.setToolTip(
            "勾选后，脚本里没写 PRIMARY KEY 的表会在字段顺序首位补一个 %s 主键字段\n"
            "（VARCHAR(32)、注释「业务主键」）：「表元数据」弹窗与生成的 Excel 都带上它，\n"
            "数据 Sheet 按脚本里 INSERT 的行数各给一个 32 位十六进制 UUID\n"
            "（脚本里没有 INSERT 就只补字段定义）；\n"
            "「预览 DDL」在副本里同步补出这一列，脚本正文一个字不动，\n"
            "「保存修改后ddl脚本」与「执行修改后ddl脚本到当前库」仍按脚本原文走。\n"
            "已经写了 PRIMARY KEY 的表不补；取消勾选恢复原样，默认不勾选。" % AUTO_PK_FIELD)
        self.chk_sql_auto_pk.toggled.connect(self.on_sql_auto_pk_toggled)
        op.addWidget(self.chk_sql_auto_pk)
        # 与第一页同一句：这一排按钮这次动几张表，说在按钮旁边
        self.lbl_sql_scope = scope_label()
        self.lbl_sql_scope.setToolTip(
            "按钮区默认作用于整份清单；在表格里选中几行，就只作用于选中的这几行。\n"
            "没选中任何行时仍按全量走。")
        op.addWidget(self.lbl_sql_scope)
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
        # 这一排同样统一高度：主按钮「执行修改后ddl脚本到当前库」带自己的 padding，
        # 比邻近按钮矮一截时文字会被切掉
        equal_heights(self.chk_sql_drop, self.chk_sql_auto_pk, self.btn_sql_save,
                      self.btn_sql_run, self.btn_sql_gen_std, self.btn_sql_gen_simple)
        # 执行层与上方表格区之间的细分隔线：底部动作条不再和表格内容糊成一片
        lay.addWidget(hline())
        lay.addWidget(self.sql_action_bar)
        return page

    def _sql_display_name(self, actual):
        return case_switch(actual, self.chk_sql_upper.isChecked(),
                           self.chk_sql_lower.isChecked())

    def _refresh_sql_table(self):
        self._refreshing_sql_table = True
        try:
            rows = []
            for s in self.sql_files:
                base = os.path.basename(s["path"])
                rev = {v: k for k, v in s["rename_map"].items()}
                display_map = s.get("display_map") or {}
                parsed = ensure_parsed(s)
                # 「数据行数」取脚本里这条表的 INSERT/REPLACE 行数；按当前正文缓存，
                # 改了正文（改名/改注释）就重算一遍。原表名兜一层：改名那一步
                # 只改 CREATE/INSERT 的引用，正文没动过的老记录按原名查得到
                insert_rows = sql_insert_row_counts(s)
                # 表名清单在读取/改写时就已算好，几十万行的脚本不再回扫一遍
                for t in s.get("tabs") or extract_create_tables(s["text"]):
                    rows.append({"path": s["path"], "base": base,
                                 "orig": rev.get(t["name"], t["name"]),
                                 "actual": t["name"],
                                 "display": self._sql_display_name(
                                     display_map.get(t["name"], t["name"])),
                                 "count": str(t["count"]),
                                 "insert_rows": insert_rows.get(
                                     t["name"], insert_rows.get(rev.get(t["name"], ""), 0)),
                                 "comment": (parsed.get(t["name"]) or {})
                                 .get("table_comment") or ""})
            self._sql_rows = rows
            self._sql_display_names = [r["display"] for r in rows]
            self.sql_table.setRowCount(len(rows))
            for i, row in enumerate(rows):
                items = [QTableWidgetItem(str(i + 1)), QTableWidgetItem(row["base"]),
                         QTableWidgetItem(row["orig"]), QTableWidgetItem(row["display"]),
                         QTableWidgetItem(row["comment"]),
                         QTableWidgetItem(str(row["insert_rows"]))]
                items[1].setData(Qt.UserRole, row["path"])
                items[1].setToolTip(row["path"])
                items[4].setToolTip(row["comment"])
                items[5].setToolTip(
                    "脚本里 `%s` 的 INSERT/REPLACE 数据行数：%s 行"
                    % (row["actual"], format(row["insert_rows"], ",")))
                for j, it in enumerate(items):
                    flags = Qt.ItemIsEnabled | Qt.ItemIsSelectable
                    if j in (3, 4):
                        flags |= Qt.ItemIsEditable
                    it.setFlags(flags)
                for j, it in enumerate(items):
                    self.sql_table.setItem(i, j, it)
        finally:
            self._refreshing_sql_table = False
        self._refresh_scope_hint()

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
        return SqlMetadataDialog(self._sql_metadata_view(table), row["display"],
                                 upper=self.chk_sql_upper.isChecked(),
                                 lower=self.chk_sql_lower.isChecked(), parent=self,
                                 on_edit=functools.partial(self._apply_sql_field_edit,
                                                           row_index),
                                 on_add=functools.partial(self._apply_sql_add_field,
                                                          row_index),
                                 on_delete=functools.partial(
                                     self._apply_sql_delete_fields, row_index))

    def _sql_columns_now(self, row_index):
        """按脚本正文重新解析这一张表，返回现在的列清单；解析不出来给 None。"""
        if not 0 <= row_index < len(self._sql_rows):
            return None
        row = self._sql_rows[row_index]
        record = next((s for s in self.sql_files if s["path"] == row["path"]), None)
        if record is None:
            return None
        table = ensure_parsed(record).get(row["actual"])
        return (table or {}).get("columns")

    def _sql_after_edit(self, row_index, record, new_text, note):
        """字段增删改了正文之后：落正文、重解这一份脚本、刷清单、写日志。"""
        record["text"] = new_text
        record["modified"] = True
        record["tabs"] = extract_create_tables(new_text)
        # 只重解这一份脚本：增删都在同一句 CREATE TABLE 里，几万行的 dump 也只扫一遍
        record["parsed"] = {t["table_name"]: t for t in parse_create_tables(new_text)}
        record.pop("insert_rows", None)          # 正文换了，行数缓存作废
        self._refresh_sql_table()
        row = self._sql_rows[row_index]
        append_log(self.sql_log, "[字段] 表 `%s` %s（已写进脚本；未落盘）"
                   % (row["actual"], note))
        return self._sql_columns_now(row_index)

    def _apply_sql_add_field(self, row_index):
        """「表元数据」弹窗里点「新增字段」：往这句 CREATE TABLE 末尾补一列。

        补完返回新的列清单，弹窗据此整表重铺（新列在最后一行，直接进编辑态）。
        """
        if not 0 <= row_index < len(self._sql_rows):
            return None
        row = self._sql_rows[row_index]
        record = next((s for s in self.sql_files if s["path"] == row["path"]), None)
        if record is None:
            return None
        new_text, changed, why = add_column(
            record["text"], row["actual"], "is_new_field", "VARCHAR(255)", "新字段")
        if not changed:
            self._warn("没能新增字段：%s" % why)
            return None
        return self._sql_after_edit(row_index, record, new_text, why)

    def _apply_sql_delete_fields(self, row_index, fields):
        """「表元数据」弹窗里删字段：一句表一次删完，中途失败就整句不动。"""
        if not 0 <= row_index < len(self._sql_rows):
            return None
        row = self._sql_rows[row_index]
        record = next((s for s in self.sql_files if s["path"] == row["path"]), None)
        if record is None:
            return None
        text, notes = record["text"], []
        for field in fields:
            text, changed, why = delete_column(text, row["actual"], field)
            if not changed:
                self._warn("字段「%s」没删：%s" % (field, why))
                return None
            notes.append(why)
        return self._sql_after_edit(row_index, record, text, "；".join(notes))

    def _sql_metadata_view(self, table):
        """弹窗看的那一份表定义：勾了「自动补全主键」就在副本上补出 business_id。

        取的是 parsed 缓存里那一份，必须先看一眼能否补、再在副本上补——parsed 是脚本
        当前正文的解析结果，被补一列就等于骗过了保存与执行两条出口的判据。
        """
        if not self.chk_sql_auto_pk.isChecked():
            return table
        view = dict(table)
        apply_auto_pk(view, True, upper=self.chk_sql_upper.isChecked(),
                      lower=self.chk_sql_lower.isChecked())
        return view

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
                   "[字段改写] 表 `%s` 的 %s（已写进脚本；未落盘）%s"
                   % (row["actual"], "，".join(parts), reason or ""))
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

    def _sql_case_state(self):
        """第二页当前生效的大小写档：两颗都不勾时清单显示的就是脚本里写的那个样子。"""
        if self.chk_sql_upper.isChecked():
            return "大写"
        if self.chk_sql_lower.isChecked():
            return "小写"
        return "保持原样"

    def _log_sql_case_switch(self):
        append_log(self.sql_log,
            "[提示] 表名与字段名大小写切换为：%s（当前表名清单、表元数据和生成的 Excel 一起变；"
            "脚本正文不改）" % self._sql_case_state())

    def on_sql_upper_toggled(self, checked):
        self._keep_one_case_switch(self.chk_sql_upper, self.chk_sql_lower)
        self._refresh_sql_table()
        self._log_sql_case_switch()

    def on_sql_lower_toggled(self, checked):
        self._keep_one_case_switch(self.chk_sql_lower, self.chk_sql_upper)
        self._refresh_sql_table()
        self._log_sql_case_switch()

    def on_sql_auto_pk_toggled(self, checked):
        """第二页这一颗不改脚本正文，只作用在三个读元数据的出口上。

        切换要留痕：「预览 DDL」里忽然多出一列，得能对上是自己勾的这颗；
        清单不刷新（这一页没有主键列），弹窗与预览都是点下去才现算。
        """
        append_log(self.sql_log,
                   "[提示] 自动补全主键切换为：%s（作用于表元数据弹窗、预览 DDL 副本与"
                   "两条生成出口，脚本正文不动）" % ("开" if checked else "关"))

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
            append_log(self.sql_log, "[提示] 拖入或粘贴的不是 SQL 脚本文件，已忽略。")
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

    def on_sql_preview_structure(self):
        """「生成表结构预览」：打开当前那一行的字段清单，与双击只读列同一个框。

        没选中行就取第一行——点按钮的人要的是"看一眼这张表"，不是被反问选哪一行。
        """
        if not self._sql_rows:
            self._warn("清单里没有脚本表，先在上方虚区添加 SQL 脚本。")
            return
        row = self.sql_table.currentRow()
        if row < 0 or row >= len(self._sql_rows):
            row = 0
        self.show_sql_table_metadata(row)

    # ---- 统计数据总行数 ----
    def on_sql_stat_total_rows(self):
        """把清单里每张表的「数据行数」加起来，弹窗与日志报同一个总数。

        求和取的是表格这一列（脚本里 INSERT/REPLACE 的行数），不是再读一遍脚本；
        多个脚本时按文件列一份分项，一眼看得出是哪个脚本占大头。
        """
        if not self.sql_files:
            self._warn("请先添加 SQL 脚本文件。")
            return
        if not self._sql_rows:
            self._warn("清单里没有脚本表（空脚本或只有注释），没有数据行数可统计。")
            return
        rows, scope = self._sql_scope()
        self._log_scope(scope, self.sql_log)
        total = sum(int(r.get("insert_rows") or 0) for r in rows)
        per_file = {}
        for r in rows:
            per_file[r["base"]] = per_file.get(r["base"], 0) + int(r.get("insert_rows") or 0)
        num = format(total, ",")
        append_log(self.sql_log,
                   "[统计] 脚本 %d 个、表 %d 张，数据总行数：%s 行%s"
                   % (len(per_file), len(rows), num, "（" + scope + "）" if scope else ""))
        lines = ["数据总行数：%s 行" % num, "",
                 "脚本 %d 个，共 %d 张表。" % (len(per_file), len(rows))]
        if scope:
            lines.insert(1, scope + "。")
        if len(per_file) > 1:
            lines.append("")
            lines.extend("  %s：%s 行" % (b, format(n, ","))
                         for b, n in per_file.items())
        popup_box(self, "数据总行数", "\n".join(lines))

    def _sql_ddl_pages(self, only=None):
        """一张表一页 DDL：正文取脚本里属于这张表的 CREATE TABLE 语句。

        还没落地的改名与字段编辑先算进正文（与保存/执行同一道 `_materialize` 的结果），
        但只算在这份副本上，页面的 `display_map` 一个字不改——预览不该把改动定死。

        整份脚本只切一遍：以前每张表都重扫并切分一次整份文件，500 张表的整库 dump
        点一次「预览 DDL」要 8.7 秒，界面看起来就是卡死（用户 2026-11-02 报）。

        only 给了（{脚本路径: {表名}}）就只出这几张表的页；表名取的是改名之后的
        那个名字，因为页是按改名后的正文切出来的。
        """
        pages = []
        for s in self.sql_files:
            text, plan = s["text"], dict(s.get("display_map") or {})
            if plan:
                text, renamed, _nohit = apply_display_renames(text, plan)
                plan.update({r["old"]: r["new"] for r in renamed})
            keep = None
            if only is not None:
                keep = {plan.get(n, n) for n in (only.get(s["path"]) or ())}
            bodies = {}
            for st in split_sql_statements(text):
                names = [t["name"] for t in extract_create_tables(st)]
                if not names:
                    continue
                one = st.strip().rstrip(";") + ";"
                for name in names:
                    bodies.setdefault(name, []).append(one)
            for t in extract_create_tables(text):
                if keep is not None and t["name"] not in keep:
                    continue
                body = "\n\n".join(bodies.get(t["name"], ()))
                if not body:
                    continue
                if self.chk_sql_auto_pk.isChecked():
                    # 只补在这份预览副本上：正文里有 PRIMARY KEY 就一句不动（kept），
                    # 补不出可插入形状时点名原因、原样显示，不拿"看着差不多"的句子顶替
                    body, status, why = add_auto_pk_create(body)
                    if status == "failed":
                        append_log(self.sql_log,
                                   "! [WARN] 跳过 | `%s`：%s，这张表的预览按原样显示"
                                   % (t["name"], why))
                pages.append((self._sql_display_name(t["name"]), body))
        return pages

    def on_sql_preview_ddl(self):
        """「预览 DDL」：按表分页看将要写出的建表语句，勾了覆盖开关就补上 DROP 那句。"""
        why = self._sql_prefix_blocker()
        if why:
            self._warn(why)
            return
        only, scope = self._sql_scope_names()
        self._log_scope(scope, self.sql_log)
        pages = self._sql_ddl_pages(only)
        if not pages:
            self._warn("没有解析出 CREATE TABLE 语句，没有可预览的 DDL。"
                       if not scope else
                       "选中的这几行没有解析出 CREATE TABLE 语句，没有可预览的 DDL。")
            return
        if self.chk_sql_drop.isChecked():
            pages = [(name, "DROP TABLE IF EXISTS `%s`;\n\n%s" % (name, body))
                     for name, body in pages]
        append_log(self.sql_log,
                   "[预览] DDL 共 %d 页%s" % (len(pages), "（" + scope + "）" if scope else ""))
        SqlScriptDdlDialog(pages, self).exec()

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
        only, scope = self._sql_scope_names()
        self._log_scope(scope, self.sql_log)
        if not prefix:
            self._clear_sql_display_prefix(only)
            return
        total, touched = 0, []
        for s in self.sql_files:
            names = self._sql_scope_in_file(only, s)
            if not names:
                continue
            fname = os.path.basename(s["path"])
            current = s.get("display_map") or {}
            plan, skipped = dict(current), []
            for old in names:
                shown = current.get(old, old)      # 接在页面上当前显示名之后，不从头计算
                if shown.startswith(prefix):
                    skipped.append(shown)
                    continue
                plan[old] = prefix + shown
                total += 1
                touched.append((fname, old, plan[old]))
            s["display_map"] = plan
            if touched:
                append_log(self.sql_log,
                           f"[前缀] {fname}："
                           + "、".join(f"{o} → {n}（显示）" for f, o, n in touched
                                       if f == fname))
            if skipped:
                append_log(self.sql_log,
                           f"[跳过] {fname}：已带前缀 {'、'.join(skipped)}")
        self._refresh_sql_table()
        append_log(self.sql_log,
                   f"[前缀完成] 共 {total} 个表名改为显示前缀「{prefix}」"
                   f"（脚本正文未改，点保存/执行/生成时才写入）")

    def _sql_scope_in_file(self, only, s):
        """这份脚本里这次要动的表名；全量时取脚本里的全部表名。"""
        if only is not None:
            return [n for n in (only.get(s["path"]) or ()) if n]
        return [t["name"] for t in (s.get("tabs") or extract_create_tables(s["text"]))]

    def _clear_sql_display_prefix(self, only=None):
        """空前缀再点一次：只撤掉显示层，表名回到脚本里的原名。

        only 给了就只撤这几张表的显示（按钮区按选中行生效），没给是整份清单。
        """
        n = 0
        for s in self.sql_files:
            plan = s.get("display_map") or {}
            names = self._sql_scope_in_file(only, s) if only is not None else None
            if names is None:
                n += len(plan)
                s["display_map"] = {}
                continue
            keep = {}
            for old, shown in plan.items():
                if old in names:
                    n += 1
                else:
                    keep[old] = shown
            s["display_map"] = keep
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
        only, scope = self._sql_scope_names()
        if not self.ed_sql_prefix.text().strip():
            self._log_scope(scope, self.sql_log)
            self._clear_sql_display_prefix(only)
            return
        head, why = self._clean_head(self.ed_sql_prefix.text())
        if head is None:
            self._warn(why)
            return
        self._log_scope(scope, self.sql_log)
        total = 0
        for s in self.sql_files:
            names = self._sql_scope_in_file(only, s)
            if not names:
                continue
            fname = os.path.basename(s["path"])
            current = s.get("display_map") or {}
            plan, skipped, changed = dict(current), [], []
            for old in names:
                shown = current.get(old, old)      # 与加前缀同一口径：基于当前显示名换首段
                new = swap_first_segment(shown, head)
                if new is None:
                    skipped.append(shown)
                    continue
                plan[old] = new
                changed.append((old, new))
                total += 1
            s["display_map"] = plan
            if changed:
                append_log(self.sql_log,
                           f"[换前缀] {fname}："
                           + "、".join(f"{o} → {n}（显示）" for o, n in changed))
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
        # 保存是按文件落盘的：一份脚本里只要有一张表动过，写出的就是整份正文。
        # 选中行只决定"改动了哪几张表"，落盘粒度照样是文件，这句得说明白，
        # 免得用户以为没选中的表在磁盘上没被碰过。
        if self._sql_scope_rows():
            append_log(self.sql_log,
                       "[范围] 本次改动只涉及选中的表；保存按脚本文件落盘，"
                       "写出的仍是整份正文（没动过的表内容一模一样）")
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
        idx = self._sql_scope_rows()      # 先记行号：materialize 会重铺清单
        self._materialize_sql_display()
        only, scope = self._sql_only_after(idx)
        self._log_scope(scope, self.sql_log)
        overwrite = self.chk_sql_drop.isChecked()
        note = "；已勾选「覆盖已存在表」，执行副本先 DROP 脚本要建的表" if overwrite else ""
        append_log(self.sql_log,
            f"===== 开始执行脚本（目标库 {db}，每个文件一个事务{note}）=====")
        items, dropped = [], []
        for s in self.sql_files:
            text = s["text"]
            if only is not None:
                names = only.get(s["path"]) or ()
                text = self._sql_text_for_tables(text, names)
                if not text:
                    append_log(self.sql_log,
                               "! [WARN] 跳过 | %s：选中的表在这份脚本里没有可执行的语句"
                               % os.path.basename(s["path"]))
                    continue
            if overwrite:
                text, tables = prepend_drop_tables(text)
                if tables:
                    dropped.append((os.path.basename(s["path"]), tables))
            items.append((s["path"], text))
        if not items:
            self._warn("选中的表在脚本里没有可执行的语句，没有执行任何脚本。")
            return
        if overwrite:
            if dropped:
                for fname, tables in dropped:
                    append_log(self.sql_log,
                        f"[覆盖] {fname}：执行前先 DROP " + "、".join(tables))
            else:
                append_log(self.sql_log, "[覆盖] " + COVER_NOTHING_BODY)
        # 整库 dump 落在这一页：跑法与第三个页签不一样，先说清楚再动手。
        # 不悄悄改用逐条提交——这一页的承诺就是"每个文件一个事务、失败整文件回滚"，
        # 偷偷换成另一套语义比让用户自己找页面更糟。
        for path, body in items:
            if is_dump_script(body):
                append_log(self.sql_log,
                           SQL_PAGE_DUMP_HINT % os.path.basename(path))
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
        # 行号先记下来：materialize 之后清单会重铺，选中的是"哪几张表"得按行号重新取
        idx = self._sql_scope_rows()
        self._materialize_sql_display()
        only, scope = self._sql_only_after(idx)
        self._log_scope(scope, self.sql_log)
        out_root = program_dir()
        append_log(self.sql_log,
                   f"===== 开始生成{label}excel文件（脚本 %d 个，输出根目录 {out_root}）====="
                   % len(self.sql_files))
        items = []
        for s in self.sql_files:
            text = s["text"]
            if only is not None:
                names = only.get(s["path"]) or ()
                text = self._sql_text_for_tables(text, names)
                if not text:
                    append_log(self.sql_log,
                               f"[跳过] {os.path.basename(s['path'])}："
                               f"选中的表在这份脚本里没有可导出的语句")
                    continue
            items.append((s["path"], text))
        if not items:
            self._warn("选中的表在脚本里没有可导出的语句，没有生成任何文件。")
            return
        # upper 留在 args 元组里、只把 lower 用关键字绑：_run_modal 会把 sink 追加在
        # 位置参数末尾，upper 一旦也被 partial 绑走，sink 就撞上它（同 3969 行那条教训）
        self._run_modal("正在生成%sexcel文件…" % label,
                        functools.partial(sql_meta_export_task,
                                          lower=self.chk_sql_lower.isChecked(),
                                          auto_pk=self.chk_sql_auto_pk.isChecked()),
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

        # 提示单独占一行：QLabel 不折行，它的最小宽度就是整句的宽度，
        # 和按钮排在同一行会把整个窗口的最小宽度顶出小屏
        self.sql_run_hint = page_intro("执行脚本与临时语句")
        lay.addWidget(self.sql_run_hint)

        # 文件这一路与第一页同一形状：拖拽区在上，清单表套在组框里
        self.sql_run_drop = DropArea(hint=DropArea.RUN_HINT,
                                     tooltip="点击选择 SQL 脚本；也可以直接拖到这里")
        self.sql_run_drop.files_dropped.connect(self.on_run_scripts_dropped)
        self.sql_run_drop.clicked.connect(self.on_run_scripts_add)

        self.sql_run_table = EmptyHintTable(
            0, 5, "请把 SQL 文件拖到上方虚线区，按清单顺序执行")
        style_table_font(self.sql_run_table)
        # 最后一列是行内【执行】：一次只跑这一行的那个脚本，与下面的「批量执行脚本文件」分开
        self.sql_run_table.setHorizontalHeaderLabels(
            ["序号", "文件", "大小", "状态", "操作"])
        header = self.sql_run_table.horizontalHeader()
        header.setSectionResizeMode(QHeaderView.Interactive)
        header.setStretchLastSection(True)
        self.sql_run_table.setColumnWidth(0, SERIAL_COL_PX)
        self.sql_run_table.setColumnWidth(1, 300)
        self.sql_run_table.setColumnWidth(2, 100)
        self.sql_run_table.setColumnWidth(3, 140)
        self.sql_run_table.setColumnWidth(4, 90)
        self.sql_run_table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.sql_run_table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        # 与第一页同一交互：选中行按 Del 出清单（只出这一页的队列，磁盘上的脚本不动）
        self.sql_run_table.del_pressed.connect(self.on_run_scripts_delete_selected)

        run_upper = QGroupBox(RUN_TABLE_TITLE)
        ru = QVBoxLayout(run_upper)
        ru.setContentsMargins(0, 0, 0, 0)
        ru.addWidget(self.sql_run_table, 1)
        # 这一排左边是「清空待执行脚本」（与第一页「清空待上传文件」同一语义：整份清出清单，
        # 磁盘上的脚本不动），右边是这一页的主按钮「批量执行脚本文件」：
        # 按清单顺序把队列里的脚本全跑一遍，与行内那颗【执行】（只跑一行）分在两头
        clear_row = QHBoxLayout()
        self.btn_run_clear = QPushButton("清空待执行脚本")
        self.btn_run_clear.clicked.connect(self.on_run_scripts_clear)
        clear_row.addWidget(self.btn_run_clear)
        clear_row.addStretch(1)
        self.btn_run_scripts = QPushButton("批量执行脚本文件")
        self.btn_run_scripts.setStyleSheet(PRIMARY_BUTTON_QSS)
        self.btn_run_scripts.setToolTip(
            "按清单顺序逐个执行全部待执行脚本：手写脚本每个文件一个事务，全部成功才提交；\n"
            "整库 dump 自动识别，改为不开事务、逐条提交、出错继续并汇总。\n"
            "只想跑其中一个就在它那行的【执行】上点。执行前请先选好目标库。")
        self.btn_run_scripts.setEnabled(False)
        self.btn_run_scripts.clicked.connect(self.on_run_scripts_execute)
        clear_row.addWidget(self.btn_run_scripts)
        ru.addLayout(clear_row)

        self.sql_run_input = QTextEdit(SQL_RUN_DEFAULT)
        self.sql_run_input.setFont(QFont("Consolas", SQL_RUN_FONT_SIZE))
        # 编辑区是给人改字的，粘进来的富文本（网页/Word 带的那些颜色字号）必须只留字：
        # 否则一段带样式的粘贴会把这块变成"半排好版"的文档，关键字色反而盖不住旧色
        self.sql_run_input.setAcceptRichText(False)
        # 初始化时整段压到最小，把高度让给上面的脚本清单；
        # 清单为空时那张表的图标 + 引导语才不会被挤掉
        self.sql_run_input.setMinimumHeight(log_min_height(self.sql_run_input))
        # 关键字高亮：文本一变就重挂叠加层，格式化、清空、插示例走的都是这一条
        self.sql_run_input.textChanged.connect(self._refresh_sql_highlight)
        apply_sql_highlight(self.sql_run_input)
        # 两颗清空各归各的 pane：只清结果区时不必丢掉刚敲了一半的语句，反过来也一样
        left = QWidget()
        ll = QVBoxLayout(left)
        ll.setContentsMargins(0, 0, 0, 0)
        lh = QHBoxLayout()
        lh.addWidget(selectable(QLabel("SQL 文本")))
        lh.addStretch(1)
        self.btn_sql_run_samples = QPushButton("示例 (?)")
        self.btn_sql_run_samples.setToolTip("打开示例清单：选中一条可复制到剪贴板或插到编辑区")
        self.btn_sql_run_samples.clicked.connect(self.on_sql_samples)
        lh.addWidget(self.btn_sql_run_samples)
        self.btn_sql_run_format = QPushButton(SQL_FORMAT_LABEL)
        self.btn_sql_run_format.setStyleSheet(SECONDARY_BUTTON_QSS)
        self.btn_sql_run_format.setToolTip(SQL_FORMAT_TIP)
        self.btn_sql_run_format.clicked.connect(self.on_format_sql)
        lh.addWidget(self.btn_sql_run_format)
        # 快捷键挂在编辑区自己身上：焦点在这块里按 Ctrl+Alt+L 才排这一段
        self.sql_run_format_key = FormatKeyFilter(self.on_format_sql)
        self.sql_run_input.installEventFilter(self.sql_run_format_key)
        self.btn_sql_run_clear_input = QPushButton("清空编辑区")
        self.btn_sql_run_clear_input.setToolTip("只清掉左边这段 SQL，右侧结果区不动")
        self.btn_sql_run_clear_input.clicked.connect(self.on_sql_run_clear_input)
        lh.addWidget(self.btn_sql_run_clear_input)
        # 编辑区这一路的动作按钮贴在它自己那一栏头上：只管编辑区里这段 SQL，
        # 与脚本清单那一路的「批量执行脚本文件」分在两处，点哪颗跑什么一眼分得清
        self.btn_sql_run_exec = QPushButton("执行编辑区 SQL")
        self.btn_sql_run_exec.setStyleSheet(SECONDARY_BUTTON_QSS)
        self.btn_sql_run_exec.setToolTip("执行左边编辑区里的 SQL：按分号切句逐条执行，"
                                         "全部成功才提交，中途出错整批回滚；与脚本文件清单互不影响")
        self.btn_sql_run_exec.clicked.connect(self.on_sql_run)
        lh.addWidget(self.btn_sql_run_exec)
        ll.addLayout(lh)
        ll.addWidget(self.sql_run_input, 1)

        self.sql_run_log = QPlainTextEdit()
        self.sql_run_log.setReadOnly(True)
        # 这里只放逐条回执（第几条、影响几行、错在哪）。结果集不再画进来：
        # 文本框里中英文混排时字宽不是整数倍关系必错行，而且结果一长就被自动滚到底，
        # 用户只看得见"共 N 行"那句尾巴，看不见数据本身——结果集交给下面的表格。
        self.sql_run_log.setFont(QFont("Consolas", SQL_RUN_FONT_SIZE))
        self.sql_run_log.setMinimumHeight(log_min_height(self.sql_run_log))
        self.sql_run_log.setMaximumBlockCount(2000)

        self.sql_run_result_table = QTableWidget()
        self.sql_run_result_table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        # 按格选：整行选的话一屏几百列，复制出来是一整行黏在一起的长串，没法贴进表格里
        self.sql_run_result_table.setSelectionBehavior(QAbstractItemView.SelectItems)
        self.sql_run_result_table.setAlternatingRowColors(True)
        self.sql_run_result_table.setWordWrap(False)
        self.sql_run_result_table.verticalHeader().setVisible(False)
        self.sql_run_result_table.horizontalHeader().setSectionResizeMode(QHeaderView.Interactive)
        self.sql_run_result_table.horizontalHeader().setStretchLastSection(False)
        style_table_font(self.sql_run_result_table)
        # 表头一开就带一句引导：注释是逐列挂上去的，还没结果时先说清该去哪儿看
        self.sql_run_result_table.horizontalHeader().setToolTip(RESULT_HEADER_HINT)
        # 没有结果集（一批全是写语句）时不摆空表：这一页初开的三段高度是钉住的契约
        self.sql_run_result_table.setVisible(False)
        self.sql_run_sets = []

        right = QWidget()
        rl = QVBoxLayout(right)
        rl.setContentsMargins(0, 0, 0, 0)
        rh = QHBoxLayout()
        rh.addWidget(selectable(QLabel("执行结果")))
        self.lbl_sql_run_caption = selectable(QLabel("还没有执行结果"))
        rh.addWidget(self.lbl_sql_run_caption)
        rh.addStretch(1)
        # 一批里多条 select 时用来切换看哪一批结果；只有一批就不占这块地方
        self.cb_sql_run_set = QComboBox()
        self.cb_sql_run_set.setVisible(False)
        self.cb_sql_run_set.currentIndexChanged.connect(self._show_sql_run_set)
        rh.addWidget(self.cb_sql_run_set)
        self.btn_sql_run_clear_log = QPushButton("清空结果区")
        self.btn_sql_run_clear_log.setToolTip("只清掉右边的执行结果，左边编辑区不动")
        self.btn_sql_run_clear_log.clicked.connect(self.on_sql_run_clear_result)
        rh.addWidget(self.btn_sql_run_clear_log)
        rl.addLayout(rh)
        # 表格与逐条回执之间也给一条可拖的分隔条：查的人有时要看数据、有时要看报错正文，
        # 谁占多数由他自己拖，不替他定死
        self.sql_run_result_split = QSplitter(Qt.Vertical)
        self.sql_run_result_split.setChildrenCollapsible(False)
        self.sql_run_result_split.setHandleWidth(6)
        self.sql_run_result_split.addWidget(self.sql_run_result_table)
        self.sql_run_result_split.addWidget(self.sql_run_log)
        self.sql_run_result_split.setStretchFactor(0, 3)
        self.sql_run_result_split.setStretchFactor(1, 2)
        self.sql_run_result_split.setSizes([260, 120])
        rl.addWidget(self.sql_run_result_split, 1)

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

        # 这一页不再有底部动作条：「批量执行脚本文件」回到脚本清单那一排、「执行编辑区
        # SQL」贴到编辑区栏头，覆盖开关按用户口径整行删掉——剩下的空间全部让给
        # SQL 文本与执行结果，两段一直铺到窗口底部。
        self.sql_run_samples = None                          # 示例对话框只在打开时才有
        return page

    def on_sql_run_clear_input(self):
        """只清编辑区：结果区是上一次的执行记录，动它得由用户自己点头。"""
        self.sql_run_input.clear()

    def on_sql_run_clear_result(self):
        """只清结果区：编辑区里可能正敲到一半，不该被一次"看腻了结果"带走。"""
        self.sql_run_log.clear()
        self.sql_run_sets = []
        self._reset_sql_run_result_table("还没有执行结果")

    def _reset_sql_run_result_table(self, caption):
        combo = self.cb_sql_run_set
        combo.blockSignals(True)
        combo.clear()
        combo.blockSignals(False)
        combo.setVisible(False)
        self.sql_run_result_table.clear()
        self.sql_run_result_table.setRowCount(0)
        self.sql_run_result_table.setColumnCount(0)
        self.sql_run_result_table.setVisible(False)
        # 表头整条给一句引导：字段注释是逐列挂在表头上的，可这里还没列可挂，
        # 停在空表头上至少让人知道结果列的注释要等表头有内容才有得看
        self.sql_run_result_table.horizontalHeader().setToolTip(RESULT_HEADER_HINT)
        self.lbl_sql_run_caption.setText(caption)

    def _show_sql_run_result(self, res):
        """一批跑完：结果集交给表格。回执文本由 sink 逐条写好了，这里只管数据。"""
        sets = res.get("sets") or []
        if not sets:
            self._reset_sql_run_result_table("这一批没有返回结果集的语句（都是写入或建表）")
            return
        self.sql_run_sets = sets
        combo = self.cb_sql_run_set
        combo.blockSignals(True)
        combo.clear()
        for s in sets:
            combo.addItem("第 %d 条 · %s" % (s["seq"], s["sql"][:60]))
        combo.blockSignals(False)
        # 只有一条时不摆下拉：多一个永远只有一个选项的控件，是占地方不是给功能
        combo.setVisible(len(sets) > 1)
        self.sql_run_result_table.setVisible(True)
        combo.setCurrentIndex(len(sets) - 1)
        self._show_sql_run_set()
        self._give_result_room()
        dropped = res.get("sets_dropped") or 0
        if dropped:
            append_log(self.sql_run_log,
                       "[提示] 这一批的结果集多于 %d 个，只保留最近 %d 个回看，"
                       "更早的 %d 个已丢弃" % (MAX_RESULT_SETS, MAX_RESULT_SETS, dropped))

    def _give_result_room(self):
        """跑出了结果集就把底部那一段撑开。

        初开时这一段压在自己的最小高度、把高度让给脚本清单——那是空页面的分配，
        不是有数据时的分配：点「执行」的人要看的是那几行数据，两行高的表格等于没有。
        已经够高就不动，用户自己拖过的比例也不覆盖（只在不足四成时抬到四成半）。
        """
        sizes = self.sql_run_split_v.sizes()
        total = sum(sizes)
        if total <= 0 or len(sizes) < 3:
            return
        if sizes[2] * 100 >= total * 40:
            return
        bottom = int(total * 0.45)
        rest = total - bottom
        self.sql_run_split_v.setSizes([int(rest * 0.31), rest - int(rest * 0.31), bottom])

    def _show_sql_run_set(self, _idx=None):
        """把选中的那一个结果集铺进表格。"""
        if not self.sql_run_sets:
            return
        idx = self.cb_sql_run_set.currentIndex()
        if idx < 0 or idx >= len(self.sql_run_sets):
            idx = len(self.sql_run_sets) - 1
        data = self.sql_run_sets[idx]
        table = self.sql_run_result_table
        cols = data["columns"]
        rows = data["rows"]
        table.setUpdatesEnabled(False)
        table.clear()
        table.setColumnCount(len(cols))
        table.setHorizontalHeaderLabels(cols)
        # 表头挂字段注释当 title：这一页查的表多是拼音首字母列名（DDJE 这种），
        # 光看列名读不出含义，注释只有服务端知道（core 已按列顺序取回，取不到就是空串）
        comments = data.get("comments") or []
        for c in range(len(cols)):
            head = table.horizontalHeaderItem(c)
            if head is not None:
                head.setToolTip(HEADER_COMMENT_PREFIX + comments[c]
                                if c < len(comments) and comments[c] else "")
        # 结果都摆在眼前了，空表头那句引导得撤掉：留着就是说谎
        table.horizontalHeader().setToolTip("")
        table.setRowCount(len(rows))
        for r, row in enumerate(rows):
            for c, value in enumerate(row):
                item = QTableWidgetItem(value)
                # 格子被列宽裁短时，悬停要看全（与清单里表注释那一格同一做法）
                item.setToolTip(value)
                table.setItem(r, c, item)
        # 列宽随内容：与两个字段弹窗同一算法，短的用下限托住，长的把表格撑宽出横向条。
        # 表头也要量进去——列名比数据长时，只按数据量会把表头截成半个词。
        fm = table.fontMetrics()
        for c in range(table.columnCount()):
            need = max(table.sizeHintForColumn(c),
                       fm.boundingRect(cols[c]).width() + 20)
            # 再留 12px：sizeHint 不含格子自己的边距，贴着边算会把最后一个字裁掉半笔
            table.setColumnWidth(c, max(70, need + 12))
        table.setUpdatesEnabled(True)
        total, printed = data["total"], data["printed"]
        caption = "第 %d 条 · 共 %d 行" % (data["seq"], total)
        if printed < total:
            caption += "（表格只装前 %d 行，其余没取回来）" % printed
        self.lbl_sql_run_caption.setText(caption)

    def _refresh_sql_highlight(self):
        """编辑区文本一变就重挂角色色（只动叠加层，正文与撤销栈都不碰）。"""
        apply_sql_highlight(self.sql_run_input)

    def on_format_sql(self):
        """把编辑区那段 SQL 排整齐：排了、没得排、不许排三种结局都留一句可见的话。

        不改语句内容——只动大小写与换行，判据是用例里那条"切分前后逐条同形"。
        排完把光标送回开头：长语句排完之后光标停在末尾，滚动位置还留在上一条的
        尾巴上，看着像"排到一半被截了"。
        """
        text = self.sql_run_input.toPlainText()
        why = format_refusal(text)
        if why:
            self._warn("没有格式化：" + why)
            return
        out = format_sql(text)
        if out == text:
            append_log(self.sql_run_log, "[提示] 这段 SQL 已经是排好的形状，一个字没动")
            return
        self.sql_run_input.setPlainText(out)
        cur = self.sql_run_input.textCursor()
        cur.movePosition(QTextCursor.Start)
        self.sql_run_input.setTextCursor(cur)
        append_log(self.sql_run_log,
                   "[格式化] 已排齐 %d 行、%d 条语句：只改大小写与换行，"
                   "语句内容不动；要跑再点「执行编辑区 SQL」"
                   % (out.count("\n") + 1, len(split_sql_statements(out))))

    def on_sql_run(self):
        text = self.sql_run_input.toPlainText()
        if not text.strip():
            self._warn("没有可执行的 SQL：编辑区是空的（只写注释也不算）")
            return
        cfg = self._conn_cfg()
        # 结果、失败原因、回滚说明都已经逐行写在右侧结果区里，这里不再弹窗打断：
        # 这一页是给人来回试语句的，每试一次弹一次模态框会点不完
        self._run_modal("正在执行 SQL…", sql_run_task, (text, cfg),
                        on_done=self._show_sql_run_result,
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
            append_log(self.sql_run_log, "[提示] 拖入或粘贴的不是 SQL 脚本文件，已忽略。")
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
            # 行内那颗【执行】只管这一行的脚本：清单每次重排都重新建，
            # 绑定的行号就与这一行同生同死，不会指到别的脚本上去
            self.sql_run_table.setCellWidget(i, 4, self._run_row_cell(i, s["path"]))
            # 描边与 padding 会抬高按钮的最小高：Qt 的 cellWidget 是按行高裁的，
            # 行高不跟着走就会把按钮上下削掉一圈，所以量按钮自己的 sizeHint
            cell_btn = self.sql_run_table.cellWidget(i, 4).findChild(QPushButton)
            if cell_btn is not None:
                cell_btn.ensurePolished()
                need = cell_btn.sizeHint().height() + 2
                if self.sql_run_table.rowHeight(i) < need:
                    self.sql_run_table.setRowHeight(i, need)
        self._sync_gen_buttons()

    def _run_row_cell(self, row_index, path):
        btn = QPushButton("执行")
        # 行内这颗套"次级强调"档（与「保存脚本」同一档）：不套样式就是一颗原生灰按钮，
        # 夹在行里看着像表格自己的边框，不像能点的东西（用户 2026-10-30 报"很不醒目"）。
        # 不用主按钮那档铺满蓝：清单可能十几行，一屏十几颗同等分量的蓝
        # 会把页底真正的主操作「批量执行脚本文件」压掉。
        btn.setStyleSheet(SECONDARY_BUTTON_QSS)
        btn.setToolTip("只执行这一行的脚本文件：\n%s" % path)
        btn.clicked.connect(lambda _=False, r=row_index: self.on_run_script_row(r))
        cell = QWidget()
        cl = QHBoxLayout(cell)
        cl.setContentsMargins(2, 0, 2, 0)
        cl.addStretch(1)
        cl.addWidget(btn)
        cl.addStretch(1)
        return cell

    def on_run_script_row(self, row_index):
        """行内【执行】：只跑这一行的脚本文件，跑法与「批量执行脚本文件」同一套。"""
        if not 0 <= row_index < len(self.sql_run_files):
            self._warn("这一行已经不在清单里了，请重新选中要执行的脚本。")
            return
        cfg = self._conn_cfg()
        db = cfg["database"]
        if not db:
            self._warn(NO_DB_HINT)
            return
        other = self._conflict((self.RES_RUN,))
        if other is not None:
            self._warn(self._busy_text(other))
            return
        self.sql_run_files[row_index]["status"] = "待执行"
        path = self.sql_run_files[row_index]["path"]
        self._refresh_run_table()
        append_log(self.sql_run_log,
                   f"===== 开始执行脚本文件（目标库 {db}，1 个文件；"
                   f"手写脚本每个文件一个事务，整库 dump 逐条提交）=====")
        self._run_modal("正在执行脚本文件到目标库…", sql_run_files_task,
                        ([path], False, cfg),
                        on_done=self._finish_run_scripts, log_view=self.sql_run_log)

    def on_run_scripts_delete_selected(self):
        """Del 键：把选中的行从待执行清单里去掉，多选就是一次批量删。

        与第一页同一口径：只出清单，磁盘上的脚本一个字不动；这一页有任务在跑时拦下来，
        半途抽走正在执行的那一行会让任务回报的项数对不上剩余清单。
        """
        other = self._conflict((self.RES_RUN,))
        if other is not None:
            self._warn(self._busy_text(other))
            return
        rows = sorted({i.row() for i in self.sql_run_table.selectionModel().selectedRows()},
                      reverse=True)
        rows = [r for r in rows if 0 <= r < len(self.sql_run_files)]
        if not rows:
            append_log(self.sql_run_log, "[删除] 没有选中脚本，先在清单里点一行再按 Del")
            return
        names = [os.path.basename(self.sql_run_files[r]["path"]) for r in rows]
        for r in rows:
            self.sql_run_files.pop(r)
        self._refresh_run_table()
        append_log(self.sql_run_log,
                   "[删除] 已移除 " + "、".join(names)
                   + f"，清单剩 {len(self.sql_run_files)} 个待执行脚本")

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
        append_log(self.sql_run_log,
                   f"===== 开始执行脚本文件（目标库 {db}，"
                   f"{len(self.sql_run_files)} 个文件；手写脚本每个文件一个事务，"
                   f"整库 dump 逐条提交）=====")
        paths = [s["path"] for s in self.sql_run_files]
        for s in self.sql_run_files:
            s["status"] = "待执行"
        self._refresh_run_table()
        # 这一页不再补 DROP：覆盖开关按用户口径整行删掉了，脚本正文怎么写就怎么执行
        self._run_modal("正在执行脚本文件到目标库…", sql_run_files_task,
                        (paths, False, cfg),
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
        self.sql_run_samples = SqlSamplesDialog(self.sql_run_input)
        self.sql_run_samples.show()

    def _conn_cfg(self) -> dict:
        return {
            "host": self.ed_host.text().strip(),
            "port": self.ed_port.value(),
            "user": self.ed_user.text().strip(),
            "password": self.ed_pass.text(),
            "database": self.cb_db.currentData() or self.cb_db.currentText().strip(),
        }

    def _read_conn_config(self):
        """读一份连接配置：自动发现 conn.ini，再让 E2M_* 盖过它。

        启动预填与「刷新配置文件」共用这一份读法，两处口径不会分叉。
        """
        cfg, src, path, err = conn_config.discover(program_dir())
        cfg, from_env = conn_config.apply_env(cfg)
        if from_env:
            src += "、环境变量 " + "/".join(from_env)
        return cfg, src, path, err, from_env

    def _load_default_conn(self):
        """启动时先读程序同级的 conn.ini 预填连接信息：现场配一次，不用每次手填。

        读法与命令行那条线是同一份实现（core/conn_config.py）：程序同级 → 当前目录，
        环境变量 E2M_* 再盖过文件。找不到文件不算错，照旧手填，但必须说一句——
        静默用内置默认值会让人以为自己的配置已经生效。
        """
        cfg, src, path, err, from_env = self._read_conn_config()
        # 只有配置点名了目标库才自动连：随包 conn.ini 是空模板（database 留空），
        # 照着它去连本机 3306 只会让新装用户一开界面就看到一条失败；没点库时
        # 即便连上也得让人手选，省不下那一步。
        self._conn_auto = (bool(from_env) or (path is not None and err is None)) \
            and bool(cfg.get("database"))
        self._apply_conn_cfg(cfg, src, path, err, from_env)

    def _apply_conn_cfg(self, cfg, src, path, err, from_env, manual=False):
        """把一份配置写进表单并留一句来源；启动预填与「刷新配置文件」共用。

        返回是否真写进去了：手动刷新时连一份可读的配置都没有，就把手上的连接信息
        留着不动——退回内置默认值会悄悄抹掉用户刚填的那台机器。
        """
        if manual and (err is not None or (path is None and not from_env)):
            if err is not None:
                append_log(self.log,
                           "[跳过] 刷新配置文件：找到 %s 但读不了，当前连接信息保持不变：%s"
                           % (path, err))
            else:
                append_log(self.log,
                           "[跳过] 刷新配置文件：程序同级与当前目录都没有 conn.ini，"
                           "当前连接信息保持不变")
            return False
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
                       "[%s] 连接信息%s %s | 来源：%s | 密码%s"
                       % ("刷新" if manual else "已加载",
                          "已更新" if manual else "预填",
                          shown, src,
                          "已读入（不回显）" if cfg["password"] else "未配置"))
            if not self._cfg_db:
                # 随包那份模板就是这一态：只说"(未填库)"，用户会以为已经连上、
                # 回头盯着灰掉的目标库纳闷
                append_log(self.log,
                           "[提示] 配置里没写目标库，%s；"
                           "点右侧「设置数据源信息」展开，再点「连接并加载库」就能选库"
                           % ("未自动重连" if manual else "启动不自动连接"))
        # 预填是程序自己往四个框里写字，不算"用户改过连接信息"
        self._conn_dirty = False
        return True

    def on_reload_conn_config(self):
        """工具已经开着时换了 conn.ini：重读一遍并按新配置重连，不用重开程序。"""
        cfg, src, path, err, from_env = self._read_conn_config()
        if not self._apply_conn_cfg(cfg, src, path, err, from_env, manual=True):
            return
        if self._cfg_db:
            # 与「连接并加载库」走同一条长任务：进度框、失败弹窗、失败清场都是同一口径
            self.on_load_databases()

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
                   "库列表未加载；点右侧「设置数据源信息」展开，再点「连接并加载库」重试\n%s"
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
        # 这份清单就是当前这四项连上去的那台服务器给的，"改过连接信息"这笔账到此结清
        self._conn_dirty = False
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
        """连库取列表：走长任务框架，点下去立刻有进度框。

        这一趟握手实测 2~5 秒（端口没人听 2 秒、地址黑洞吃满 5 秒连接超时），
        从前它直接站在界面线程里跑，那几秒整窗不响应——用户读作"软件死了"。
        """
        cfg = self._conn_cfg()
        self._db_load_seq += 1       # 还在路上的自动连接结果就此过期
        self._conn_load_cfg = cfg    # 回执那句要报"连的是哪台"，不能读回来时再问一次界面
        self._run_modal("正在连接并加载库…", load_databases_task, (cfg,),
                        on_done=self._on_db_load_done, log_view=self.log,
                        resources=(self.RES_CONN,))

    def _on_db_load_done(self, outcome):
        """任务回来：成功灌清单，失败照旧弹窗 + 日志 + 清场（口径与改造前一致）。"""
        dbs, err = outcome
        cfg = self._conn_load_cfg or self._conn_cfg()
        if err is None:
            self._apply_databases(dbs)
            return
        # 连不上的那台服务器给不回清单，上一台的那份也就不作数了：
        # 留着旧清单加旧库名，界面会一边显示"● 目标库 xxx"一边把库指向另一台机器
        self.cb_db.clear()
        self.cb_db.setEnabled(False)
        self._conn_dirty = False
        self._refresh_db_labels()
        # 弹窗与日志用同一套展开格式，否则弹窗里是 (2003, '...') 这种元组原文
        popup_box(self, "连接失败", err, "critical")
        append_log(self.log,
                   f"[连接失败] 无法连接到 "
                   f"{cfg['host']}:{cfg['port']}（用户 {cfg['user']}），库列表未加载\n"
                   + indent_detail(err), error=True)

    def on_initials_toggled(self, checked):
        """命名开关切换要留痕：之后看到 ddje 这样的字段名，得能对上是自己勾的。"""
        append_log(self.log, 
            "[提示] 命名规则切换为：%s（对之后添加的文件生效）"
            % ("拼音首字母" if checked else "全拼"))

    def _keep_one_case_switch(self, keep, other):
        """两颗大小写勾互斥：亮一颗就按掉另一颗，不留「又大写又小写」这种自相矛盾的组合。

        按掉的那颗不发自己的 toggled，否则一句切换日志会变成两条，
        而且第二条报的还是刚被按掉那一档。
        """
        if keep.isChecked() and other.isChecked():
            other.blockSignals(True)
            other.setChecked(False)
            other.blockSignals(False)

    def _case_state(self):
        """当前生效的大小写档，加上结构定义手写名跟着怎么办——日志与生成口径共用一份判读。"""
        if self.chk_upper.isChecked():
            return "大写", "跟着转大写"
        if self.chk_lower.isChecked():
            return "小写", "跟着转小写"
        return "小写", "保持原样"

    def on_upper_toggled(self, checked):
        """大小写开关同样留痕：列表里忽然一片大写，得能对上是自己勾的。"""
        self._keep_one_case_switch(self.chk_upper, self.chk_lower)
        case, hand = self._case_state()
        append_log(self.log,
            "[提示] 表名与字段名大小写切换为：%s（对之后添加的文件生效，"
            "结构定义 Sheet 里手写的字段名%s）" % (case, hand))

    def on_lower_toggled(self, checked):
        """小写档同一条留痕：勾上之后手写的大写字段名会整片变小写，得说得出是谁动的。"""
        self._keep_one_case_switch(self.chk_lower, self.chk_upper)
        case, hand = self._case_state()
        append_log(self.log,
            "[提示] 表名与字段名大小写切换为：%s（对之后添加的文件生效，"
            "结构定义 Sheet 里手写的字段名%s）" % (case, hand))

    def on_auto_pk_toggled(self, checked):
        """补列与撤列当场落到清单上。

        清单的「字段数」「主键」两列、结构预览、预览 DDL 与导入读的是同一份表定义，
        只在出口那侧补就会出现"清单说没主键、建表语句里有主键"的分裂；
        所以这一颗改的是 self.tables 本身，改完刷新清单。
        """
        state = "开" if checked else "关"
        if not self.tables:
            append_log(self.log,
                       "[提示] 自动补全主键切换为：%s（清单里没有文件，没有表可动）" % state)
            return
        upper = self.chk_upper.isChecked()
        lower = self.chk_lower.isChecked()
        done, skipped = [], []
        for t in self.tables:
            moved, why = apply_auto_pk(t, checked, upper=upper, lower=lower)
            if moved:
                _attach_row_fit(t)          # 多一列就多占 32 字节，过不了线要在这张表上拦下
                done.append((t, why))
            elif checked:
                skipped.append((t, why))
        self._refresh_file_table()
        append_log(self.log,
                   "[提示] 自动补全主键切换为：%s（动了 %d 张，跳过 %d 张，清单里共 %d 张表）"
                   % (state, len(done), len(skipped), len(self.tables)))
        for t, why in done:
            append_log(self.log, "+ [OK] 补主键 | `%s`：%s" % (t["table_name"], why))
        for t, why in skipped:
            append_log(self.log, "! [WARN] 跳过 | `%s`：%s" % (t["table_name"], why))

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
            append_log(self.log, "[提示] 拖入或粘贴的不是 Excel 或 ZIP 文件，已忽略。")

    def on_paste_files(self, target=None):
        """Ctrl+V 的分工：剪贴板里有文件就一律按「添加文件」处理，不看焦点在谁身上。

        三页虚线区都写着"复制文件后按 Ctrl+V 直接添加"，这句就得鼠标停哪儿都兑现；
        而窗口级 QShortcut 在焦点落进输入框、SQL 编辑区时根本抢不到按键（控件自己吃掉
        Ctrl+V，实测），所以这条挂在应用级按键过滤上。剪贴板是纯文本时才把按键交回
        焦点控件自己粘——粘主机名、粘 SQL 片段照旧。
        返回 True 表示这一按键已经被"添加文件"用掉，过滤器就不再往下传。
        `target` 是过滤器手里那个真正收到按键的控件，比"当前焦点控件"更准
        （焦点在跨窗口切换时会慢半拍，按错一边就是悄悄改行为）。
        """
        return self.route_paste(self.clipboard_file_paths(),
                                target if target is not None
                                else QApplication.focusWidget())

    def route_paste(self, paths, focus):
        """分派规则单独成一条：用例喂假剪贴板也能验到"有文件就抢、没文件就让"。"""
        if paths:
            self.paste_paths(paths)
            return True
        if isinstance(focus, (QLineEdit, QPlainTextEdit, QTextEdit)):
            return False        # 没有文件可加，这一按键本来就归文本控件自己粘
        # 焦点不在输入框里：让当前页给出那句"粘的不是文件"的回执，不静默
        self.paste_paths([])
        return True

    def clipboard_file_paths(self):
        """剪贴板里那些真的是文件的路径；目录与纯文本一律算"没有文件"。

        `mimeData()` 这会儿可能整个给回 None（离屏平台的剪贴板就是个空桩，
        真平台上也有拿不到数据的时候），这里不兜住就会把按键处理抛异常。
        """
        mime = QApplication.clipboard().mimeData()
        if mime is None:
            return []
        return [u.toLocalFile() for u in mime.urls()
                if QFileInfo(u.toLocalFile()).isFile()]

    def paste_paths(self, paths):
        """粘贴进来的那批路径走这一条：加到用户眼前这一页，不是永远加到第一页。

        三页的虚线区都写着"复制文件后按 Ctrl+V 直接添加"，那这句就得在当前页兑现；
        第二、三页直接把这批路径交给各自拖拽区的那个入口，回执与拖进来的逐字一致。
        对撞器与用例不碰系统剪贴板，也是走这一条验计数。
        """
        page = self.main_tabs.currentIndex()
        if page == 1:
            self.on_sql_files_dropped(paths)
            return
        if page == 2:
            self.on_run_scripts_dropped(paths)
            return
        accepted = [f for f in paths if f.lower().endswith(IMPORT_EXTS)]
        if not accepted:
            self.on_files_dropped(paths)
            return
        other = self._conflict((self.RES_EXCEL,))
        if other is not None:
            # 这一句要拦在"已通过粘贴添加"之前说：任务位被占时那条回执就成了假话
            self._warn(self._busy_text(other))
            return
        append_log(self.log, "[粘贴] 已通过粘贴添加 %d 个文件，其中 %d个 zip文件，"
                             "%d个 xlsx文件，%d个 xls文件"
                   % (len(accepted),
                      sum(1 for f in accepted if f.lower().endswith(".zip")),
                      sum(1 for f in accepted if f.lower().endswith(".xlsx")),
                      sum(1 for f in accepted if f.lower().endswith(".xls"))))
        self._add_files(accepted)

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
                              upper=self.chk_upper.isChecked(),
                              lower=self.chk_lower.isChecked(),
                              auto_pk=self.chk_auto_pk.isChecked()),
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
                # 其余列连标志一起收掉，让"只有表名与表注释可改"在数据和界面两处都成立
                it = QTableWidgetItem(text)
                it.setFlags(Qt.ItemIsEnabled | Qt.ItemIsSelectable)
                return it

            def editable(text):
                it = QTableWidgetItem(text)
                it.setFlags(Qt.ItemIsEnabled | Qt.ItemIsSelectable | Qt.ItemIsEditable)
                return it

            for i, t in enumerate(self.tables):
                self.file_table.setItem(i, 0, read_only(str(i + 1)))
                item = read_only(os.path.basename(t["filepath"]))
                # ZIP 展开出来的文件要能看出来自哪个包的哪个内部路径
                item.setToolTip(self._zip_origin.get(t["filepath"]) or t["filepath"])
                self.file_table.setItem(i, 1, item)
                self.file_table.setItem(i, 2, editable(t["table_name"]))
                comment_item = editable(t["table_comment"])
                # 注释整格可能比列宽长：悬停要看全（与第二页的表注释同一做法）
                comment_item.setToolTip(t["table_comment"])
                self.file_table.setItem(i, 3, comment_item)
                self.file_table.setItem(i, 4, read_only(str(len(t["columns"]))))
                self.file_table.setItem(i, 5, read_only(str(len(t["df"]))))
                pk = ", ".join(c["field"] for c in t["columns"] if c.get("primary_key")) or "无"
                self.file_table.setItem(i, 6, read_only(pk))
        finally:
            self._refreshing_file_table = False
        self._refresh_scope_hint()

    # ---- 按钮区的作用对象：选中几行就只改那几行 ----
    @staticmethod
    def _selected_rows(table, total):
        """表格里选中的行号（升序去重）；越界的一律丢掉，选中为空就返回空列表。"""
        model = table.selectionModel()
        if model is None:
            return []
        return sorted({i.row() for i in model.selectedRows() if 0 <= i.row() < total})

    def _scoped_tables(self):
        """按钮区这一下作用在哪些表：选了就只给选中的，没选就是整份清单。

        用户口径（2026-11-09）：按钮不该永远作用于全量——选中几行就该只改那几行；
        一个都没选时仍按整份清单走，两条路都不必先"全选"一次。
        返回 (表清单, 一句范围说明)；说明为空表示这次是全量。
        """
        rows = self._selected_rows(self.file_table, len(self.tables))
        if not rows:
            return list(self.tables), ""
        return ([self.tables[r] for r in rows],
                "本次只作用于选中的 %d 张表，没选中的一概不动" % len(rows))

    def _log_scope(self, scope, view=None):
        if scope:
            append_log(view or self.log, "[范围] " + scope)

    def _refresh_scope_hint(self):
        """动作条上那句「作用范围」跟着选中行走：两页各一支，没建好就跳过。

        选中行会静悄悄地改按钮范围：改完一行表名再点前缀，如果没这句提示，
        用户只会看到"只改了一行"然后以为按钮坏了（第一版就是这么被报上来的）。
        """
        pairs = ((getattr(self, "lbl_scope", None),
                  getattr(self, "file_table", None), len(self.tables)),
                 (getattr(self, "lbl_sql_scope", None),
                  getattr(self, "sql_table", None), len(self._sql_rows)))
        for lbl, table, total in pairs:
            if lbl is None or table is None:
                continue
            n = len(self._selected_rows(table, total))
            if n:
                lbl.setText("范围：选中 %d 张表" % n)
            else:
                lbl.setText("范围：全量（%d 张表）" % total)

    # ---- 第二页同一件事：按选中的表行决定按钮作用范围 ----
    def _sql_scope_rows(self):
        """选中的行号；一个都没选就是空列表（按全量走）。"""
        return self._selected_rows(self.sql_table, len(self._sql_rows))

    def _sql_scope(self):
        """「SQL 脚本改名」页按钮区的作用对象：选了就只给选中的，没选就是全量。"""
        idx = self._sql_scope_rows()
        if not idx:
            return list(self._sql_rows), ""
        return ([self._sql_rows[r] for r in idx],
                "本次只作用于选中的 %d 张表，没选中的一概不动" % len(idx))

    def _sql_scope_names(self):
        """作用范围摊成 {脚本路径: {表名}}；全量时给 (None, "")。

        取 `actual`——那是脚本正文里现在的表名，也是 display_map 的键、切句后
        认表名时对得上的那个；清单上看到的 `display` 已经过大小写开关，
        拿它去正文里找会找不到（勾了转大写时尤其明显）。
        """
        rows, scope = self._sql_scope()
        if not scope:
            return None, ""
        out = {}
        for r in rows:
            out.setdefault(r["path"], set()).add(r["actual"])
        return out, scope

    def _sql_only_after(self, idx):
        """落盘前的最后一步：行号 → {脚本路径: {表名}}。

        保存/生成/执行都会先 `_materialize_sql_display`，正文里的表名会当场
        换成显示名；所以范围必须在落地之后按同一个行号重新取一遍，
        否则拿到的是改名之前的老名字。全量时给 (None, "")。
        """
        idx = [i for i in idx if 0 <= i < len(self._sql_rows)]
        if not idx:
            return None, ""
        out = {}
        for i in idx:
            row = self._sql_rows[i]
            out.setdefault(row["path"], set()).add(row["actual"])
        return out, "本次只作用于选中的 %d 张表，没选中的一概不动" % len(idx)

    def _sql_text_for_tables(self, text, names):
        """从一份脚本里只留属于 names 这几张表的语句。

        生成 excel / 执行这类"整份文件"的动作要按选中行收窄时走这里：语句头认不出
        表名的（SET / BEGIN / LOCK TABLES 之类）一律不带走——它们是整份脚本的壳，
        收窄成几张表时带上只会把范围又放大回去。
        """
        keep = {n.lower() for n in names}
        out = []
        for st in split_sql_statements(text):
            m = SQL_HEAD_TABLE_RE.match(st.lstrip())
            if not m:
                continue
            target = m.group(1).replace("`", "").split(".")[-1].lower()
            if target in keep:
                out.append(st.strip().rstrip(";"))
        if not out:
            return ""
        return ";\n".join(out) + ";\n"

    def on_clear(self):
        self.tables.clear()
        self.file_table.setRowCount(0)
        self.btn_import.setEnabled(False)
        self._sync_gen_buttons()

    def on_delete_selected_files(self):
        """Del 键：把选中的行从待上传列表里去掉，多选就是一次批量删。

        只出清单，不动磁盘上的原文件；ZIP 展开出来的临时文件仍按原计划在退出时清。
        """
        other = self._conflict((self.RES_EXCEL,))
        if other is not None:
            # 半途抽走正在被解析的那一行，任务回报的项数就对不上剩余清单了
            self._warn(self._busy_text(other))
            return
        rows = sorted({i.row() for i in self.file_table.selectionModel().selectedRows()},
                      reverse=True)
        rows = [r for r in rows if 0 <= r < len(self.tables)]
        if not rows:
            append_log(self.log, "[删除] 没有选中文件，先在列表里点一行再按 Del")
            return
        for r in rows:
            self.tables.pop(r)
        self._refresh_file_table()
        self.btn_import.setEnabled(bool(self.tables))
        self._sync_gen_buttons()
        append_log(self.log, "[删除] 已移除 %d 个待上传文件，列表剩 %d 个"
                   % (len(rows), len(self.tables)))

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
        tables, scope = self._scoped_tables()
        self._log_scope(scope)
        self._run_modal("正在替换表名前缀…", table_replace_task, (tables, head),
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
        tables, scope = self._scoped_tables()
        self._log_scope(scope)
        self._run_modal("正在批量应用表名前缀…", table_prefix_task, (tables, prefix),
                        on_done=self._finish_apply_prefix)

    def _finish_apply_prefix(self, res):
        self._refresh_file_table()
        # 只报"这一下动了几张表"：按选中行生效后再说"全部表名"就把范围说错了
        n = res.get("n", 0)
        if res["prefix"]:
            append_log(self.log, f"[前缀] 已为 {n} 张表名批量添加前缀「{res['prefix']}」")
        else:
            append_log(self.log, f"[前缀] 已移除前缀，{n} 张表恢复基础表名")

    # ---- 表结构预览（弹窗表格） ----
    def on_preview_structure(self):
        if not self.tables:
            popup_box(self, "提示", NO_FILE_HINT)
            return
        tables, scope = self._scoped_tables()
        self._log_scope(scope)
        dlg = StructureDialog(tables, self)
        if dlg.exec():
            # 弹窗直接改的就是清单里那几个表定义（同一个 dict），不另做回写；
            # 新增/删除字段时 df 与 columns 由弹窗一起改，这里只刷清单与行上限体检
            for line in dlg.summary():
                append_log(self.log, "[结构] " + line)
            self._refresh_file_table()
            append_log(self.log, "[结构] 已按弹窗确认 %d 张表的字段定义" % len(tables))

    # ---- 统计数据总行数 ----
    def on_stat_total_rows(self):
        """把清单里每张表的「数据行数」加起来，弹窗与日志报同一个总数。

        数的是表格里那一列（解析时就按数据行填好的 len(df)），不是再从文件读一遍。
        """
        if not self.tables:
            popup_box(self, "提示", NO_FILE_HINT)
            return
        tables, scope = self._scoped_tables()
        self._log_scope(scope)
        total = sum(len(t["df"]) for t in tables)
        files = len({t["filepath"] for t in tables})
        num = format(total, ",")
        head = ("选中的 %d 张表" % len(tables)) if scope else \
               ("待导入文件 %d 个、表 %d 张" % (files, len(tables)))
        append_log(self.log,
                   "[统计] %s，数据总行数：%s 行" % (head, num))
        popup_box(self, "数据总行数",
                  "数据总行数：%s 行\n\n%s。" % (num, head))

    def on_file_table_item_changed(self, item) -> None:
        """清单里就地改的两格：表名要过校验，表注释直接写回这张表的定义。"""
        if self._refreshing_file_table or item is None:
            return
        if item.column() == 2:
            self._apply_file_table_name(item)
        elif item.column() == 3:
            self._apply_file_table_comment(item)

    def _apply_file_table_name(self, item) -> None:
        """手动改单张表的表名：校验不过就恢复原名，改成了就以新名为基础名。"""
        row = item.row()
        if not 0 <= row < len(self.tables):
            return
        t = self.tables[row]
        old = t["table_name"]
        # 反引号是 DDL 的引用符，不是名字的一部分；粘贴时最容易带上。
        # 名字里的 2.0 先按 _v2_ 的拼接形式改写：版本号不是非法字符，
        # 不该被下面"只能用字母、数字、下划线"那条规则拦下（拖文件进来时也不拦）
        new = rewrite_versions(str(item.text() or "").replace("`", "").strip())
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

    def _apply_file_table_comment(self, item) -> None:
        """表注释改了就写回这张表：建表那句 COMMENT 与两份生成件都读它。"""
        row = item.row()
        if not 0 <= row < len(self.tables):
            return
        t = self.tables[row]
        old = t.get("table_comment") or ""
        new = str(item.text() or "").strip()
        if new == old:
            # 只多打了空白时 Qt 照样发 itemChanged：去空白后其实没改，
            # 但这句回执必须有，刷回清单也不能留着多打的空白（静默 no-op 等于界面在骗人）
            append_log(self.log, "[表注释] %s 未发生变化，建表语句不改写" % t["table_name"])
            self._refresh_file_table()
            return
        t["table_comment"] = new
        self._refresh_file_table()
        append_log(self.log, "[表注释] %s → %s（建表语句按新注释生成）"
                   % (t["table_name"], new or "（空）"))

    def on_edit_structure_row(self, row: int, col: int = 0) -> None:
        """双击文件列表某一行：「表名」「表注释」就地改，其余列只弹那一张表的结构窗口。

        整列表一起弹会让人分不清改的是哪张表，Swing 那条线一直是按行弹的。
        """
        if not 0 <= row < len(self.tables):
            return
        if col in (2, 3):
            if self.task_busy((self.RES_EXCEL,)):
                return
            item = self.file_table.item(row, col)
            if item is not None:
                self.file_table.editItem(item)
            return
        if not self.tables:
            popup_box(self, "提示", NO_FILE_HINT)
            return
        t = self.tables[row]
        dlg = StructureDialog([t], self)
        if dlg.exec():
            for line in dlg.summary():
                append_log(self.log, "[结构] " + line)
            self._refresh_file_table()
            append_log(self.log, "[结构] %s 已按弹窗修改" % t["table_name"])

    # ---- DDL 预览 ----
    def on_preview_ddl(self):
        if not self.tables:
            popup_box(self, "提示", NO_FILE_HINT)
            return
        tables, scope = self._scoped_tables()
        self._log_scope(scope)
        dlg = DDLDialog(tables, self.chk_drop.isChecked(), self)
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
        tables, scope = self._scoped_tables()
        self._log_scope(scope)
        out_root = program_dir()
        title = ("正在生成标准excel文件…" if with_meta else "正在生成简易excel文件…")
        append_log(self.log, f"===== 开始生成{label}excel文件（输出根目录 {out_root}）=====")
        self._run_modal(title, meta_export_task, (list(tables), out_root, with_meta),
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
        tables, scope = self._scoped_tables()
        self._log_scope(scope)
        self.btn_import.setEnabled(False)
        self.btn_stop.setEnabled(True)
        self.worker = ImportWorker(self._conn_cfg(), list(tables), self.chk_drop.isChecked())
        self.worker.log.connect(self._on_worker_log)
        self.worker.log_error.connect(self._on_worker_error)
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
        # 导入完成的文件仍留在待导入列表里（用户 2026-11-09 点名：执行完不要清空待上传区域）：
        # 同一批文件常要再导一次到另一个库、或换个前缀再导，自动抽走等于逼人重新拖一遍。
        # 要清空有「清空待上传文件」，要删几行有 Del，退路都在。
        done_files = {r.get("file") for r in results if r["ok"]}
        kept = [t for t in self.tables if t.get("filepath") in done_files]
        if kept:
            append_log(self.log,
                       "[保留] 导入完成的文件仍留在待导入列表："
                       + "、".join(sorted({os.path.basename(t["filepath"]) for t in kept})))
        if fail:
            append_log(self.log,
                       "[保留] 导入失败的文件同样留在列表里，可调整后重试")
        self._refresh_file_table()
        self.btn_import.setEnabled(bool(self.tables))
        self.btn_stop.setEnabled(False)
        msg = f"成功 {ok} 张表，失败 {fail} 张表。"
        if kept:
            msg += f"\n导入完成的 {len(kept)} 张表仍留在待导入列表里。"
        if fail:
            msg += "\n失败的表也在列表里，可调整后重试。"
        popup_box(self, "导入完成", msg + "\n详见日志。")


# ---------------------------------------------------------------------------
# 表结构预览/编辑弹窗
# ---------------------------------------------------------------------------
# 两个字段弹窗的列宽口径：设计下限（46/230/280/160/96）与 Swing 侧同一组数，
# 富余宽度只摊给文字列（主键勾选框、字节数那两列保持下限附近不动）
STRUCTURE_COL_FLOORS = (46, 230, 280, 160, 96)
STRUCTURE_COL_STRETCH = (1, 2, 3)
METADATA_COL_FLOORS = (230, 280, 160, 70, 70)
METADATA_COL_STRETCH = (0, 1, 2)


class StructureDialog(QDialog):
    """表结构预览（可编辑）：一张表一页，字段名/注释/类型/主键可改，也能增删字段。

    保存时先把整份编辑收到副本、校验主键，通过后才统一写回——校验不过就留在编辑态。
    写回时 columns 与 df 必须同位置一起改：导入走 df.values 按列序号取数，错一格整份数据
    就串列（core.infer.apply_auto_pk 那条注释同一口径）。新增/删除字段就是会改到列数的操作，
    所以这里每次都按"行 → 原列序号"重建一份对齐的 df。

    保存过程任何一步抛异常都要弹出来说清楚：以前异常一抛，弹窗不关也不说话，
    用户只会看到"改了、点保存、没反应"，再打开还是老样子（用户 2026-11-09 点名）。
    """

    HEADERS = ["主键", "字段名(拼音/首字母)", "字段注释(中文表头)", "数据类型",
               "表内数据最大字节长度"]

    def __init__(self, tables, parent=None):
        super().__init__(parent)
        self.setWindowTitle("表结构预览（可编辑）")
        fit_client(self, 980, 640)
        self.tables = tables
        # 每个 tab 一个 (表定义, 表格, 每行对应的原列序号；新增的行为 None)
        self.editors = []
        self._notes = []          # 保存成功后的改动摘要，主窗口写进日志

        lay = QVBoxLayout(self)
        tip = QLabel("勾选一列或多列作为主键（多列即组合主键，主键约束至多一个）；"
                     "主键需全列非空且组合唯一，全部不勾选则不设主键。\n"
                     "「新增字段」「删除选中字段」改的是这张表的字段清单（表里选中行再按 Del 也是删）。")
        tip.setStyleSheet("color: #666;")
        tip.setWordWrap(True)
        lay.addWidget(tip)
        self.tabs = style_tab_scroll(QTabWidget())
        for t in tables:
            # 主键列 46、最大字节列 96 与 Swing 侧同一组设计下限；
            # 富余宽度只摊给三列文字，主键勾选框与字节数那两列不动
            tw = FillWidthTable(0, 5, STRUCTURE_COL_FLOORS, STRUCTURE_COL_STRETCH)
            style_table_font(tw)
            # 第 4 列是这一列数据在表内的实测最大字节数，不是类型声明的容量；
            # 挨着「数据类型」放时只写"最大字节长度"会被读成 VARCHAR(n) 那一档
            tw.setHorizontalHeaderLabels(self.HEADERS)
            tw.setSelectionBehavior(QAbstractItemView.SelectRows)
            tw.installEventFilter(self)
            origin = list(range(len(t["columns"])))
            self._load_rows(tw, t, origin)

            # 这张表不另加标题：tab 上写的就是表名，已经贴在表格正上方。
            # 增删字段这两颗放在表格上沿，与那一列"字段清单"同一组，不跟弹窗底部的保存挤一起。
            tools = QHBoxLayout()
            btn_add = QPushButton("新增字段")
            btn_add.setToolTip("在这张表的字段末尾补一列：先给一个 is_new_field 占位名，"
                               "再在表里把字段名、注释、数据类型改成要的\n"
                               "（新增的列没有数据值，导入时这一列按空值写）")
            btn_del = QPushButton("删除选中字段")
            btn_del.setToolTip("把选中的行从字段清单里删掉（可多选，Del 键同一件事）；\n"
                               "删掉的列以后不再建，主键勾在别的列上照旧生效")
            btn_add.clicked.connect(functools.partial(self._add_field, t, tw, origin))
            btn_del.clicked.connect(functools.partial(self._del_field, t, tw, origin))
            equal_heights(btn_add, btn_del)
            tools.addWidget(btn_add)
            tools.addWidget(btn_del)
            tools.addStretch(1)

            page = QWidget()
            page_lay = QVBoxLayout(page)
            page_lay.setContentsMargins(0, 0, 0, 0)
            page_lay.addLayout(tools)
            page_lay.addWidget(tw, 1)
            self.tabs.addTab(page, t["table_name"])
            self.editors.append((t, tw, origin))
        lay.addWidget(self.tabs)

        # 按钮写成中文的「保存」「取消」：以前是 QDialogButtonBox 的 OK / Cancel，
        # 中文界面里一颗写着 OK 的按钮，让人拿不准点了到底存不存
        btns = QDialogButtonBox()
        self.btn_save = btns.addButton("保存", QDialogButtonBox.AcceptRole)
        self.btn_cancel = btns.addButton("取消", QDialogButtonBox.RejectRole)
        self.btn_save.setDefault(True)
        btns.accepted.connect(self._on_accept)
        btns.rejected.connect(self.reject)
        lay.addWidget(btns)

    # ---- 表格装载与增删 ----
    def _fill_row(self, tw, row, col, is_new):
        cb = QCheckBox()
        cb.setChecked(bool(col.get("primary_key")))
        tw.setCellWidget(row, 0, cb)
        tw.setItem(row, 1, QTableWidgetItem(col["field"]))
        tw.setItem(row, 2, QTableWidgetItem(col["comment"]))
        tw.setItem(row, 3, QTableWidgetItem(col["type"]))
        size = "" if is_new or col.get("max_len") in (None, "") else str(col["max_len"])
        size_item = QTableWidgetItem(size)
        # 这一列是推断的中间量：改了不会改类型，只会出现"看着改对了、
        # 建表仍按原类型"的错觉，所以只给看不给改（与 Java 侧同口径）
        size_item.setFlags(size_item.flags() & ~Qt.ItemIsEditable)
        if is_new:
            size_item.setToolTip("新增字段还没有数据，这一格没有实测值")
        tw.setItem(row, 4, size_item)

    def _load_rows(self, tw, t, origin):
        cols = t["columns"]
        tw.setRowCount(len(cols))
        for i, c in enumerate(cols):
            self._fill_row(tw, i, c, is_new=origin[i] is None)
        tw.fit_columns()

    @staticmethod
    def _free_field(tw):
        """给新增行找一个当前表里没用过的占位字段名，省得和已有字段撞名。"""
        used = set()
        for r in range(tw.rowCount()):
            it = tw.item(r, 1)
            if it is not None:
                used.add(it.text().strip().lower())
        name, n = "is_new_field", 1
        while name.lower() in used:
            n += 1
            name = "is_new_field_%d" % n
        return name

    def _add_field(self, t, tw, origin):
        row = tw.rowCount()
        tw.insertRow(row)
        self._fill_row(tw, row, {"field": self._free_field(tw), "comment": "新字段",
                                 "type": "VARCHAR(255)", "max_len": None}, is_new=True)
        origin.append(None)
        tw.clearSelection()
        tw.selectRow(row)
        tw.scrollToItem(tw.item(row, 1))
        tw.setCurrentCell(row, 1)
        tw.editItem(tw.item(row, 1))
        tw.fit_columns()

    def _del_field(self, t, tw, origin):
        rows = sorted({i.row() for i in tw.selectionModel().selectedRows()},
                      reverse=True)
        rows = [r for r in rows if 0 <= r < tw.rowCount()]
        if not rows:
            popup_box(self, "删除字段",
                      "先在表里选中要删的行（可以按住 Ctrl 多选），再点这颗按钮。",
                      "warning")
            return
        if len(rows) >= tw.rowCount():
            popup_box(self, "删除字段",
                      "这张表只剩这些字段了，全删掉就没有列定义可建表。\n"
                      "要整张表不要，请在清单里把这一行删掉（Del）。", "warning")
            return
        for r in rows:
            tw.removeRow(r)
            origin.pop(r)
        tw.fit_columns()

    def eventFilter(self, obj, event):
        # Del 与「删除选中字段」同一个动作：两条入口一套规则，不各写一遍
        if event.type() == QEvent.KeyPress and event.key() == Qt.Key_Delete:
            for (t, tw, origin) in self.editors:
                if tw is obj:
                    self._del_field(t, tw, origin)
                    return True
        return super().eventFilter(obj, event)

    # ---- 保存 ----
    def _collect(self):
        """把每张表表格里的内容收成 (表定义, 新列清单, 每行对应的原列序号)。"""
        out = []
        for (t, tw, origin) in self.editors:
            cols = []
            for i in range(tw.rowCount()):
                src = origin[i]
                base = dict(t["columns"][src]) if src is not None else {
                    "field": "", "comment": "", "type": "VARCHAR(255)", "max_len": None}
                item = tw.item(i, 1)
                if item is not None and item.text().strip():
                    base["field"] = item.text().strip()
                item = tw.item(i, 2)
                if item is not None:
                    base["comment"] = item.text()
                item = tw.item(i, 3)
                if item is not None and item.text().strip():
                    base["type"] = item.text().strip()
                cb = tw.cellWidget(i, 0)
                base["primary_key"] = bool(cb and cb.isChecked())
                cols.append(base)
            out.append((t, cols, list(origin)))
        return out

    @staticmethod
    def _df_positions(t):
        """原列序号 → df 里的列序号；对不上就是 None（这一列在当前 df 里没有数据）。

        正常情况两者一一对应（同一个序号）。只有"自动补主键的空表"这类 df 比 columns
        少一列时才需要重对：按列名对——df 的列名就是解析时的中文表头，也等于字段的 comment。
        """
        df = t.get("df")
        old = t.get("columns") or []
        if df is None:
            return lambda i: None
        if df.shape[1] == len(old):
            return lambda i: i
        names = [str(x).strip().lower() for x in df.columns]
        table = []
        for c in old:
            key = str(c.get("comment") or "").strip().lower()
            table.append(names.index(key) if key and key in names else None)
        return lambda i: table[i] if 0 <= i < len(table) else None

    @staticmethod
    def _aligned_df(t, cols, picks):
        """按"新列顺序 → 原列序号"重建一份与 cols 对齐的 df。

        新增的行没有数据，用一列空值占位：导入时按空值写，主键校验也据此判"无数据"。
        df 本来就跟 columns 对不上的（自动补主键的空表），按 _df_positions 重对一遍，
        不再按下标硬取、越界崩。
        """
        import pandas as pd
        df = t.get("df")
        rows = 0 if df is None else len(df)
        pos = StructureDialog._df_positions(t)
        frames, names = [], []
        for i, src in enumerate(picks):
            at = pos(src) if src is not None else None
            if at is not None and df is not None and at < df.shape[1]:
                frames.append(df.iloc[:, at].reset_index(drop=True))
                names.append(df.columns[at])
            else:
                frames.append(pd.Series([None] * rows, dtype=object))
                names.append(cols[i]["field"])
        if not frames:
            return pd.DataFrame(index=range(rows))
        out = pd.concat(frames, axis=1)
        out.columns = names
        return out

    def _on_accept(self):
        try:
            staged = self._collect()
            problems = []
            for (t, cols, picks) in staged:
                for i, c in enumerate(cols):
                    if c.get("primary_key") and picks[i] is None:
                        problems.append("表「%s」：新增字段「%s」还没有数据，不能作主键"
                                        % (t["table_name"], c.get("field") or "（空）"))
                aligned = self._aligned_df(t, cols, picks)
                good, msg = validate_primary_key(aligned, cols)
                if not good:
                    problems.append("表「%s」：%s" % (t["table_name"], msg))
            if problems:
                popup_box(self, "主键设置无效",
                          "\n".join(problems) + "\n\n请调整主键勾选或字段类型后重试。",
                          "warning")
                return        # 校验不过不放行，保持弹窗打开让用户调整
        except Exception as e:                      # 保存失败必须有交代，不许静默
            popup_box(self, "保存失败",
                      "这次修改没能保存：\n%s\n\n弹窗保持打开，可以继续调整。"
                      % describe_db_error(e), "critical")
            return

        self._notes = []
        for (t, cols, picks) in staged:
            self._notes.extend(self._apply(t, cols, picks))
        self.accept()

    def _apply(self, t, cols, picks):
        """把一张表的编辑落到表定义上，返回改动摘要（主窗口写进日志）。"""
        old = t["columns"]
        notes = []
        gone = [old[i]["field"] for i in range(len(old)) if i not in picks]
        added = [c["field"] for c, p in zip(cols, picks) if p is None]
        if gone:
            notes.append("%s：删除字段 %s" % (t["table_name"], "、".join(gone)))
        if added:
            notes.append("%s：新增字段 %s" % (t["table_name"], "、".join(added)))
        for i, c in enumerate(cols):
            src = picks[i]
            if src is None:
                continue
            o = old[src]
            for key, label in (("field", "字段名"), ("comment", "注释"), ("type", "类型")):
                if str(c.get(key) or "") != str(o.get(key) or ""):
                    notes.append("%s：%s %s → %s" % (t["table_name"], label,
                                                     o.get(key) or "（空）",
                                                     c.get(key) or "（空）"))
            if bool(c.get("primary_key")) != bool(o.get("primary_key")):
                notes.append("%s：%s 的主键勾选 %s"
                             % (t["table_name"], c["field"],
                                "勾上" if c.get("primary_key") else "取消"))
        # 列数变了（含 df 本来就跟 columns 对不上那种：自动补主键的空表就是这样）：
        # 重铺一份对齐的 df，并把上一次"行字节上限体检"的结论作废
        df = t.get("df")
        changed_shape = (len(cols) != len(old) or picks != list(range(len(old)))
                         or (df is not None and df.shape[1] != len(cols)))
        t["columns"] = cols
        if changed_shape:
            t["df"] = self._aligned_df(t, cols, picks)
            t.pop("row_fit", None)
        return notes

    def summary(self):
        return list(self._notes)

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

    给了 on_add / on_delete 时表格上沿多两颗按钮：新增字段、删除选中字段。
    两个回调都回"这张表新的列清单"（成功）或 None（被拒，弹窗保持原样），
    弹窗据此整表重铺——新增/删除之后行数与行号都变了，不重铺就点不动下一格。
    """

    def __init__(self, table, display_name, upper, parent=None, on_edit=None,
                 lower=False, on_add=None, on_delete=None):
        super().__init__(parent)
        self.setWindowTitle("表元数据（%s）" % display_name)
        fit_client(self, 980, 640)
        self._table = table
        self._display_name = display_name
        self._on_edit = on_edit
        self._on_add = on_add
        self._on_delete = on_delete
        self._upper = bool(upper)
        self._lower = bool(lower)
        self._loading = True
        self._originals = []          # 每行的 (字段名, 注释, 类型) 原值，用来判断改了哪一格

        lay = QVBoxLayout(self)
        head = QLabel("表名：%s    表注释：%s"
                      % (display_name, table.get("table_comment") or "（无）"))
        head.setTextInteractionFlags(Qt.TextSelectableByMouse)
        head.setWordWrap(True)
        lay.addWidget(head)
        if on_edit is None:
            tip = "这里按脚本里的 CREATE TABLE 原样展示；" \
                  "勾选「表名与字段名转大写」或「转小写」只切换显示，不回写脚本正文。"
        else:
            tip = "字段名、字段注释、数据类型这三列可以直接改，改完就写进脚本正文（还没落盘）；" \
                  "主键与可空照脚本里的定义显示，不在这里改。" \
                  "「表名与字段名转大写」或「转小写」只切换显示，不回写脚本正文。"
        lay.addWidget(QLabel(tip))

        tools = QHBoxLayout()
        self.btn_add = QPushButton("新增字段")
        self.btn_add.setToolTip("在这张表的字段末尾补一列（占位名 is_new_field，"
                                "补完在表里改字段名/注释/类型）\n"
                                "只改脚本里的 CREATE TABLE，INSERT 里的数据一行都不动")
        self.btn_del = QPushButton("删除选中字段")
        self.btn_del.setToolTip("把选中的行从这句 CREATE TABLE 里删掉（可多选，Del 键同一件事）；\n"
                                "主键/索引名单里引用到它的那几项一并清掉")
        equal_heights(self.btn_add, self.btn_del)
        tools.addWidget(self.btn_add)
        tools.addWidget(self.btn_del)
        tools.addStretch(1)
        editable = on_edit is not None
        show_tools = editable and (on_add is not None or on_delete is not None)
        self.btn_add.setVisible(editable and on_add is not None)
        self.btn_del.setVisible(editable and on_delete is not None)
        if show_tools:
            lay.addLayout(tools)
        self.btn_add.clicked.connect(self._add_clicked)
        self.btn_del.clicked.connect(self._del_clicked)

        tw = FillWidthTable(0, 5, METADATA_COL_FLOORS, METADATA_COL_STRETCH)
        style_table_font(tw)
        tw.setHorizontalHeaderLabels(["字段名", "字段注释", "数据类型", "主键", "可空"])
        tw.setSelectionBehavior(QAbstractItemView.SelectRows)
        tw.installEventFilter(self)
        self.field_table = tw
        if on_edit is not None:
            tw.itemChanged.connect(self._on_item_changed)
        # 表格左上的标识（与第一页「待导入文件」同一做法）：标题里带列数，
        # 不必滚到表格末尾数这一张表解析出了多少列
        self.col_box = QGroupBox()
        cb_lay = QVBoxLayout(self.col_box)
        cb_lay.setContentsMargins(6, 2, 6, 6)
        cb_lay.addWidget(tw, 1)
        lay.addWidget(self.col_box, 1)
        self._load(table.get("columns") or [])

        btns = QDialogButtonBox(QDialogButtonBox.Close)
        btns.rejected.connect(self.reject)
        btns.clicked.connect(lambda _: self.reject())
        lay.addWidget(btns)

    def _load(self, cols):
        """按一份列清单整表重铺：新增/删除之后行号会变，不重铺就点不动下一格。"""
        self._loading = True
        try:
            tw = self.field_table
            tw.setRowCount(len(cols))
            self._originals = []
            for i, c in enumerate(cols):
                field = c["field"]
                typ = c.get("type") or ""
                comment = c.get("comment") or ""
                self._originals.append((field, comment, typ))
                values = [case_switch(field, self._upper, self._lower), comment, typ,
                          "是" if c.get("primary_key") else "",
                          "是" if c.get("nullable", True) else "否"]
                for j, value in enumerate(values):
                    item = QTableWidgetItem(value)
                    item.setTextAlignment(
                        Qt.AlignCenter if j >= 3 else Qt.AlignLeft | Qt.AlignVCenter)
                    if j >= 3:
                        item.setFlags(item.flags() & ~Qt.ItemIsEditable)
                    tw.setItem(i, j, item)
        finally:
            self._loading = False
        self.col_box.setTitle("%s（共 %d 列）" % (COLUMN_TABLE_TITLE, len(cols)))
        # 与结构弹窗同一档：底宽取"设计下限与内容需要"的大者，富余摊给字段名/注释/
        # 数据类型三列铺满弹窗；装不下时出横向条，不把超长那一格切掉
        # （Swing 侧 AUTO_RESIZE_OFF + Tables.fillOnResize 同一算法）
        self.field_table.fit_columns()

    def tableWidget(self):
        return self.field_table

    # ---- 新增 / 删除 ----
    def _add_clicked(self):
        if self._on_add is None:
            return
        cols = self._on_add()
        if cols is not None:
            self._load(cols)
            if self.field_table.rowCount():
                row = self.field_table.rowCount() - 1
                self.field_table.selectRow(row)
                self.field_table.scrollToBottom()
                self.field_table.setCurrentCell(row, 0)
                self.field_table.editItem(self.field_table.item(row, 0))

    def _del_clicked(self):
        if self._on_delete is None:
            return
        rows = sorted({i.row() for i in self.field_table.selectionModel().selectedRows()},
                      reverse=True)
        if not rows:
            popup_box(self, "删除字段",
                      "先在表里选中要删的字段（可按住 Ctrl 多选），再点这颗按钮。",
                      "warning")
            return
        fields = [self._originals[r][0] for r in rows if 0 <= r < len(self._originals)]
        if len(fields) >= self.field_table.rowCount():
            popup_box(self, "删除字段",
                      "这是这张表最后的字段了，全删掉就没有列定义可建表。", "warning")
            return
        if not confirm_box(self, "删除字段",
                           "从脚本里删掉这些字段？\n%s\n\n"
                           "主键/索引名单里引用到它们的项会一并清掉；脚本里的 INSERT 数据不动，"
                           "所以删完再执行到库可能报列数不匹配。\n"
                           "（脚本文件本身要等「保存修改后ddl脚本」才落盘）"
                           % "、".join(fields), "删除", "不删"):
            return
        cols = self._on_delete(fields)
        if cols is not None:
            self._load(cols)

    def eventFilter(self, obj, event):
        if (obj is self.field_table and event.type() == QEvent.KeyPress
                and event.key() == Qt.Key_Delete and self._on_delete is not None):
            self._del_clicked()
            return True
        return super().eventFilter(obj, event)

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
            for col, value in ((0, case_switch(field, self._upper, self._lower)),
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
        self.tabs = style_tab_scroll(QTabWidget())
        for t in tables:
            ddl = build_ddl(t["table_name"], t["table_comment"], t["columns"], drop_if_exists)
            refuse = (t.get("row_fit") or {}).get("refuse")
            if refuse:
                # 预览里先把话说在前面：这份 DDL 发进库会被拒，注释行就是拦下理由与拆法
                ddl = "-- " + row_fit_notice(refuse).replace("\n", "\n-- ") + "\n" + ddl
            te = QPlainTextEdit()
            te.setReadOnly(True)
            te.setFont(QFont("Menlo"))
            # 同一套角色上色：注释最亮、标识符次级蓝、类型加粗、关键字退到背景，
            # 勾了覆盖开关补上的那句 DROP 走警示色
            paint_styled(te, ddl, ddl_spans(ddl), ddl_formats(te), DDL_BLOCK_SPACING)
            self.tabs.addTab(te, t["table_name"])
        lay.addWidget(self.tabs)
        btns = QDialogButtonBox(QDialogButtonBox.Close)
        btns.rejected.connect(self.reject)
        btns.accepted.connect(self.accept)
        lay.addWidget(btns)


# ---------------------------------------------------------------------------
class SqlScriptDdlDialog(QDialog):
    """「SQL 脚本改名」页的 DDL 预览：一张表一个页签，正文取自脚本自己的 CREATE TABLE。

    与第一页那个弹窗同形状（同一档尺寸、同样只读、同样一颗关闭），差别只在正文来源：
    第一页是从 Excel 推断出来的表定义现拼 DDL，这一页本来就是脚本正文。
    正文先按格式化器排一遍再显示——从前这里"照原文贴"，脚本里一行到底的 CREATE TABLE
    在预览里就是一行长条，读的人只能横向拖。排的是显示，脚本本身一个字不动：
    改名、保存、执行都仍按原文走，下方那句话把这件事当面说清。
    """

    def __init__(self, pages, parent=None):
        super().__init__(parent)
        self.setWindowTitle("DDL 预览")
        fit_client(self, 900, 600)
        lay = QVBoxLayout(self)
        self.tabs = style_tab_scroll(QTabWidget())
        for name, ddl in pages:
            shown = format_sql(ddl)
            te = QPlainTextEdit()
            te.setReadOnly(True)
            te.setFont(QFont("Menlo"))
            # 排过的正文按角色上色（与第一页那个弹窗同一套切分与色板）
            paint_styled(te, shown, ddl_spans(shown), ddl_formats(te), DDL_BLOCK_SPACING)
            self.tabs.addTab(te, name)
        lay.addWidget(self.tabs)
        self.note = QLabel(SCRIPT_DDL_NOTE)
        lay.addWidget(self.note)
        btns = QDialogButtonBox(QDialogButtonBox.Close)
        btns.rejected.connect(self.reject)
        btns.accepted.connect(self.accept)
        lay.addWidget(btns)


# ---------------------------------------------------------------------------
class _TreeRowDelegate(QStyledItemDelegate):
    """目录的行高跟着字高走：Qt 的树默认行高是样式里写死的，字号抬到 11 号就顶行。

    与 `style_table_font` 里 `fontMetrics().height() + 留白` 同一条算法，
    Java 侧对应 `JTree.setRowHeight(字高 + 留白)`。
    """

    def sizeHint(self, option, index):
        base = super().sizeHint(option, index)
        return QSize(base.width(), base.height() + SAMPLE_TREE_ROW_PADDING)


def make_find_icon(size=SAMPLE_SEARCH_ICON_SIZE):
    """搜索框左侧那颗放大镜：圆环占左上六成，手柄从环边拉到右下角。

    两端各画一遍但同一条几何，Java 侧见 `MainFrame.findIcon`。
    """
    pm = QPixmap(size, size)
    pm.fill(Qt.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.Antialiasing, True)
    pen = QPen(QColor(SAMPLE_GROUP_FG))
    pen.setWidthF(max(1.3, size / 9.0))
    p.setPen(pen)
    ring = size * 0.58
    p.drawEllipse(QRectF(0.8, 0.8, ring, ring))
    p.drawLine(QPointF(ring + 0.8, ring + 0.8), QPointF(size - 1.2, size - 1.2))
    p.end()
    return QIcon(pm)


def rich_format(fg=None, bg=None, bold=False, size_pt=None):
    """一条角色对应的字符格式（介绍块与 DDL 预览共用这一套造法）。

    字号只收绝对值：Qt 的 QTextCharFormat 没有"相对大一号"这种接口，
    要按控件自己的字号推，由调用方算好传进来。
    """
    fmt = QTextCharFormat()
    if fg:
        fmt.setForeground(QColor(fg))
    if bg:
        fmt.setBackground(QColor(bg))
    if bold:
        fmt.setFontWeight(QFont.Bold)
    if size_pt:
        fmt.setFontPointSize(size_pt)
    return fmt


def rich_text(text):
    r"""换行归一成 \n：两栈的文档里段界只占一个字符。

    脚本正文是从磁盘读进来的，CRLF 那份会让文档比原文短一截——角色段是按原文算的
    偏移，插进文档就整体错一格（第一行还是对的，越往后越歪）。Java 侧同一条在
    gui.RichText.normalizeNewlines。
    """
    return text.replace("\r\n", "\n").replace("\r", "\n")


def paint_styled(view, text, spans, formats, block_spacing=0):
    """按角色给纯文本控件上色：文本内容逐字不变（换行除外，见 rich_text），只换字重与颜色。

    两端各有一份同构实现（Java 侧 gui.RichText）。对撞键比的是"从真文档里读回来的
    角色段"，不是这里抄的常量——改了切分规则，另一侧一定会发现。
    """
    text = rich_text(text)
    cur = view.textCursor()
    cur.beginEditBlock()
    cur.mergeCharFormat(QTextCharFormat())
    cur.select(QTextCursor.Document)
    cur.removeSelectedText()
    last = 0
    for start, end, role in spans:
        if start > last:
            # 段与段之间那些字符（就是行末那个换行）要显式用默认格式插：
            # 不传格式的话光标会沿用上一段的格式，换行符跟着被算进标题那一档
            cur.insertText(text[last:start], QTextCharFormat())
        cur.insertText(text[start:end], formats.get(role))
        last = max(last, end)
    if last < len(text):
        cur.insertText(text[last:], QTextCharFormat())
    if block_spacing:
        bfmt = QTextBlockFormat()
        bfmt.setBottomMargin(block_spacing)
        blk = view.document().firstBlock()
        while blk.isValid():
            bcur = QTextCursor(blk)
            bcur.mergeBlockFormat(bfmt)
            blk = blk.next()
    cur.endEditBlock()
    cur.movePosition(QTextCursor.Start)
    view.setTextCursor(cur)


def intro_spans(text):
    """介绍那五行的角色切分：第一行是标题，每行冒号前是字段名，关键词逐个成小块。

    关键词按空格切，一个词一块底，词与词之间那格空格不上底——从前整条铺一片深色，
    三五个词连成一整块，字糊在底里读不出来（用户原话："人字都看不清"）。
    「解决的问题」单开一档提示色：那一句讲的是"不查会撞上什么"，与说明、场景不是一回事。
    """
    text = rich_text(text)
    spans = []
    pos = 0
    for line in text.split("\n"):
        end = pos + len(line)
        if pos == 0:
            spans.append((0, end, "title"))
        else:
            cut = line.find("：")
            if cut < 0:
                spans.append((pos, end, "value"))
            else:
                spans.append((pos, pos + cut + 1, "label"))
                if line.startswith("关键词："):
                    off = pos + cut + 1
                    for word in line[cut + 1:].split(" "):
                        if word:
                            spans.append((off, off + len(word), "chip"))
                        off += len(word) + 1
                elif line.startswith("解决的问题："):
                    spans.append((pos + cut + 1, end, "tip"))
                else:
                    spans.append((pos + cut + 1, end, "value"))
        pos = end + 1
    return spans


# 角色 → 屏幕上看得见的特征。签名与 Java 侧 roleAt 认同一组数，改一处必须同批改另一侧。
RICH_KEYWORD_FG = SAMPLE_INTRO_CHIP_FG   # 关键字：与介绍块那几颗关键词同一支紫
RICH_DANGER_FG = LOG_WARN_COLOR      # DROP 那一行：预览里唯一会把数据真删掉的动作


def _char_role(fmt):
    """把一个字符格式归到某一档角色，判据是颜色与字重，不是"当初设了什么"。"""
    bg = fmt.background()
    if bg.style() != Qt.NoBrush and bg.color().name() == SAMPLE_INTRO_CHIP_BG:
        return "K"
    fg = fmt.foreground()
    name = fg.color().name() if fg.style() != Qt.NoBrush else ""
    if name == SAMPLE_INTRO_TITLE_FG:
        return "T"
    if name == SAMPLE_INTRO_LABEL_FG:
        return "L"
    if name == SAMPLE_INTRO_TIP_FG:
        return "S"
    if name == RICH_KEYWORD_FG:
        return "W"
    if name == RICH_DANGER_FG:
        return "D"
    if fmt.fontWeight() >= QFont.Bold:
        return "B"                   # 类型那一档：不加颜色，只加粗
    return "V"


def _runs_from_roles(text, segs):
    """把 (起, 止, 角色) 段表压成 "T12|L3|V23"；段与段之间那些字没上色，记成 V。

    换行不计：它是段界、本身没有字形，Qt 从段界读回来的是下一段那一档、
    Swing 读回来的是上一段那一档，两边各说各话，计进段长就永远对不齐。
    """
    def count(a, b):
        return sum(1 for i in range(max(a, 0), min(b, len(text)))
                   if text[i] != "\n")

    out = []
    prev, run, pos = None, 0, 0

    def push(role, n):
        nonlocal prev, run
        if n <= 0:
            return
        if role == prev:
            run += n
            return
        if prev is not None:
            out.append("%s%d" % (prev, run))
        prev, run = role, n

    for start, end, role in sorted(segs):
        if end <= pos:
            continue                      # 段重叠（不该发生）：已经用掉的那一段说了算
        push("V", count(pos, start))
        push(role, count(max(start, pos), end))
        pos = max(pos, end)
    push("V", count(pos, len(text)))
    if prev is not None:
        out.append("%s%d" % (prev, run))
    return "|".join(out)


def styled_signature(view):
    """读回文档里每一段的角色归属，压成 "T12|L3|V23|L4|K18" 这样一串。

    判角色靠颜色不靠记忆：这条串说的是"屏幕上现在哪一段是什么色"，两栈的切分规则
    一旦不一致，对撞就会红，而不是两边各自读同一份常量绿着。
    两处读法必须与 Java 侧（gui.RichText.roleSignature）逐字对齐，否则比的是读法不是切分：
    逐字取格式要先选中这一个字再读，光标停在 i 处时 charFormat() 给的是插入点格式
    （等于前一个字的格式），整串会右移一格。
    """
    doc = view.document()
    text = doc.toPlainText()
    cur = QTextCursor(doc)
    segs = []
    prev, start = None, 0
    for i in range(len(text)):
        cur.setPosition(i)
        cur.setPosition(i + 1, QTextCursor.KeepAnchor)
        role = _char_role(cur.charFormat())
        if role != prev:
            if prev is not None:
                segs.append((start, i, prev))
            prev, start = role, i
    if prev is not None:
        segs.append((start, len(text), prev))
    return _runs_from_roles(text, segs)


def apply_sql_highlight(view):
    """给编辑区挂上角色色：走 extraSelections 这一层，正文一个字不动、撤销栈也不加一步。

    为什么不写成文档格式（只读那几块走的就是文档格式）：编辑区是给人敲字的，
    整篇重挂格式会给撤销栈塞一层"看不见的编辑"，Ctrl+Z 第一下只把颜色撤没了，
    字还在——用户读作"撤销没反应"。叠加层是绘制时套上去的，屏幕上一个字不多一个不少，
    读回来靠 applied_signature。
    """
    formats = sql_formats(view)
    sel = []
    for start, end, role in sql_spans(view.toPlainText()):
        fmt = formats.get(role)
        if fmt is None:
            continue
        item = QTextEdit.ExtraSelection()
        item.format = fmt
        cur = QTextCursor(view.document())
        cur.setPosition(start)
        cur.setPosition(end, QTextCursor.KeepAnchor)
        item.cursor = cur
        sel.append(item)
    view.setExtraSelections(sel)


def applied_signature(view):
    """编辑区那一份签名：角色挂在叠加层里，就从真挂上去的那一份读回来。

    与 styled_signature 同一串形状（同样的角色字母、同样的段长压缩），
    两栈的编辑区才比得了——Java 侧那块是文档属性，读法是 gui.RichText.appliedSignature。
    """
    text = view.toPlainText()
    segs = []
    for item in view.extraSelections():
        cur = item.cursor
        role = _char_role(item.format)
        if role != "V":
            segs.append((cur.selectionStart(), cur.selectionEnd(), role))
    return _runs_from_roles(text, segs)


DDL_KEYWORDS = frozenset(("CREATE", "TABLE", "PRIMARY", "KEY", "NOT", "NULL", "DEFAULT",
                          "ENGINE", "CHARSET", "COLLATE", "IF", "EXISTS", "DROP",
                          "UNIQUE", "COMMENT", "AUTO_INCREMENT", "USING", "INNODB",
                          "UTF8MB4"))
# UNSIGNED / BINARY 记在类型那一档不是关键字：DDL 里它们紧跟在 BIGINT、VARCHAR 后面，
# 拆开看就是同一个字段的两半，一半加粗一档退灰会让人以为是两个东西。
DDL_TYPES = frozenset(("VARCHAR", "CHAR", "DECIMAL", "NUMERIC", "INT", "INTEGER", "BIGINT",
                       "TINYINT", "SMALLINT", "MEDIUMINT", "FLOAT", "DOUBLE", "DATE",
                       "DATETIME", "TIMESTAMP", "TIME", "YEAR", "TEXT", "TINYTEXT",
                       "MEDIUMTEXT", "LONGTEXT", "BLOB", "JSON", "BIT", "BOOL", "BOOLEAN",
                       "UNSIGNED", "BINARY"))
DDL_TOKEN_RE = re.compile(r"`[^`]*`|'(?:[^']|'')*'|[A-Za-z_][A-Za-z0-9_]*")


def ddl_spans(text):
    """把一句 DDL 切成 (起, 止, 角色)：按"一行一字段"的形状认角色，不跑完整 SQL 语法。

    `DROP TABLE` 那一行整行标警示色——整段预览里只有它会把数据真删掉，它该跳出来。
    引号与反引号那两条排在裸词前面，注释里出现的 NULL 之类不会被误当成关键字。
    """
    text = rich_text(text)
    spans = []
    pos = 0
    for line in text.split("\n"):
        if line.lstrip().upper().startswith("DROP TABLE"):
            spans.append((pos, pos + len(line), "danger"))
        else:
            for tok in DDL_TOKEN_RE.finditer(line):
                w = tok.group(0)
                if w.startswith("`"):
                    role = "ident"
                elif w.startswith("'"):
                    role = "comment"
                elif w.upper() in DDL_TYPES:
                    role = "type"
                elif w.upper() in DDL_KEYWORDS:
                    role = "keyword"
                else:
                    continue
                spans.append((pos + tok.start(), pos + tok.end(), role))
        pos += len(line) + 1
    return spans


def ddl_formats(view):
    """注释（中文说明）最亮，标识符次级蓝，类型与关键字都加粗上色。

    关键字从前是"退到背景"那一档灰，比正文还淡——用户读作"没高亮关键字"。
    现在给一支实色紫并加粗：它在整篇里确实跳得出来，又不与警示橙那种"会删数据"混。
    """
    return {
        "ident": rich_format(fg=SAMPLE_INTRO_LABEL_FG),
        "comment": rich_format(fg=SAMPLE_INTRO_TITLE_FG),
        "type": rich_format(bold=True),
        "keyword": rich_format(fg=RICH_KEYWORD_FG, bold=True),
        "danger": rich_format(fg=RICH_DANGER_FG, bold=True),
    }


# 认词不跑语法：注释（-- /# /块注释）、反引号标识符、单引号串先各自成段，
# 剩下的裸词按"会不会真删数据 → 类型 → 关键字"排优先级
SQL_TOKEN_RE = re.compile(r"/\*.*?\*/|--[^\n]*|#[^\n]*|`[^`]*`|'(?:[^']|'')*"
                         r"|[A-Za-z_][A-Za-z0-9_]*", re.S)
SQL_DANGER = frozenset(("DROP", "DELETE", "TRUNCATE"))


def sql_spans(text):
    """把一段 SQL 切成 (起, 止, 角色)：角色名与 DDL 预览同一套，颜色也就同一套。

    词表直接取格式化器那两张（`SQL_FORMAT_KEYWORDS` / `SQL_FORMAT_TYPES`），
    不再另立一份——同一句话里"哪些是关键字"在两处答案不同，比高亮难看多了。
    DROP/DELETE/TRUNCATE 走警示色：DDL 预览里那句 `DROP TABLE` 同理，
    这一段里只有它们会把数据真删掉。
    """
    text = rich_text(text)
    spans = []
    for tok in SQL_TOKEN_RE.finditer(text):
        w = tok.group(0)
        if w.startswith(("/*", "--", "#", "'")):
            role = "comment"
        elif w.startswith("`"):
            role = "ident"
        elif w.upper() in SQL_DANGER:
            role = "danger"
        elif w.upper() in SQL_FORMAT_TYPES:
            role = "type"
        elif w.upper() in SQL_FORMAT_KEYWORDS:
            role = "keyword"
        else:
            continue
        spans.append((tok.start(), tok.end(), role))
    return spans


def sql_formats(view):
    """SQL 正文的角色格式：直接取 DDL 预览那一份（用户点名参照它的文字格式）。

    两处共用同一支色与同一个角色名，签名在两处读法相同，也不会长出"两个预览两种蓝"。
    """
    return ddl_formats(view)


def intro_formats(view):
    """标题用主按钮那支蓝并大一号；字段名用目录分组那支蓝；关键词一颗一小块浅底。

    底色只铺在每一个关键词自己身上，词间那格空格留白：整行铺底会把多个词连成一整块，
    字压在里面读不出来。「解决的问题」走提示绿，值本身仍是正文默认色。
    大一号是按这块自己的当前字号推的，不写死绝对值——换 DPI 或改界面基准都跟着走。
    """
    base = view.font().pointSize()
    return {
        "title": rich_format(fg=SAMPLE_INTRO_TITLE_FG, bold=True,
                             size_pt=(base + 1) if base > 0 else None),
        "label": rich_format(fg=SAMPLE_INTRO_LABEL_FG, bold=True),
        "value": rich_format(),
        "chip": rich_format(fg=SAMPLE_INTRO_CHIP_FG, bg=SAMPLE_INTRO_CHIP_BG, bold=True),
        "tip": rich_format(fg=SAMPLE_INTRO_TIP_FG),
    }


class SqlSamplesDialog(QDialog):
    """「示例 (?)」打开的那个独立窗口：左边搜关键词 + 目录，右边上方介绍、下方语句。

    非模态：这一页的用法是照着清单改语句、执行、再看结果，
    模态框会把编辑区锁住，等于让人把 SQL 抄两遍。目录与正文都取自
    sql_samples.py（Java 那条线导出同一份表），两边不会各说各话。

    目录分两层（分组 → 短标题）而不是把上百条平铺在第一层：示例只会越加越多，
    平铺时找一条要滚半天；上面再给一行关键词过滤，常用路径就是"搜一下、点第一条"。
    右侧分上下两块是因为一条示例要回答两个问题——"我什么时候要用它、它替我挡掉什么"
    和"语句到底怎么写"，只看语句的人会把场景丢在手册里。

    挂 Qt.Window 且不认主窗口当 parent：Windows 上认了 owner 的窗口没有最小化/最大化那两颗
    按钮、也不单占任务栏一格，主窗口一最小化它还跟着藏——这张清单正是开着用的。
    """

    def __init__(self, editor):
        super().__init__()
        self._editor = editor
        self.setModal(False)
        self.setWindowFlags(Qt.Window | Qt.WindowTitleHint | Qt.WindowSystemMenuHint
                            | Qt.WindowMinimizeButtonHint | Qt.WindowMaximizeButtonHint
                            | Qt.WindowCloseButtonHint)
        self.setWindowTitle("SQL 示例（%d 条）" % len(SQL_SAMPLES))
        fit_client(self, 980, 620)
        lay = QVBoxLayout(self)

        self.search = QLineEdit()
        self.search.setPlaceholderText(SAMPLE_SEARCH_HINT)
        self.search.setToolTip(SAMPLE_SEARCH_TIP)
        self.search.setClearButtonEnabled(True)
        # 前导图标：不点进去也得看出这一条是能输入的搜索框（Java 侧同款自绘）
        self.search.addAction(make_find_icon(), QLineEdit.LeadingPosition)
        self.search.textChanged.connect(self._refilter)
        self.tree = QTreeWidget()
        self.tree.setHeaderHidden(True)
        self.tree.setUniformRowHeights(True)
        # 字号并入表格那一档、行高按字高算、缩进抬到与 11 号相配：
        # 这栏是逐条读的清单，9 号加 20px 缩进读起来眼睛要自己找层级
        self.tree.setFont(QFont("", SAMPLE_TREE_FONT_SIZE))
        self.tree.setIndentation(SAMPLE_TREE_INDENT)
        self.tree.setItemDelegate(_TreeRowDelegate(self.tree))
        self.lbl_hits = QLabel()
        self._groups = []                         # [(分组项, [叶子项])]，按来源顺序
        self._build_tree()

        left = QWidget()
        ll = QVBoxLayout(left)
        ll.setContentsMargins(0, 0, 0, 0)
        ll.addWidget(self.search)
        ll.addWidget(QLabel("示例目录（分组 → 条目）"))
        ll.addWidget(self.tree, 1)
        ll.addWidget(self.lbl_hits)

        self.intro = QPlainTextEdit()
        self.intro.setReadOnly(True)
        self.preview = QTextEdit()
        self.preview.setReadOnly(True)
        # 介绍条跟着界面基准，目录已经抬到表格那一档（见 SAMPLE_TREE_FONT_SIZE），
        # 只有语句正文按日志档 10 号：
        # 这是照着挑的阅读弹窗，不吃编辑区那档 16 号（16 号在并排清单旁显得突兀）
        self.preview.setFont(QFont("Consolas", LOG_FONT_SIZE))
        btns = QHBoxLayout()
        self.btn_copy = QPushButton("复制到剪贴板")
        self.btn_copy.clicked.connect(self.on_copy)
        self.btn_insert = QPushButton("插入到编辑区")
        self.btn_insert.clicked.connect(self.on_insert)
        self.btn_close = QPushButton("关闭")
        self.btn_close.clicked.connect(self.hide)
        btns.addStretch(1)
        btns.addWidget(self.btn_copy)
        btns.addWidget(self.btn_insert)
        btns.addWidget(self.btn_close)
        sql_box = QWidget()
        sl = QVBoxLayout(sql_box)
        sl.setContentsMargins(0, 0, 0, 0)
        sl.addWidget(QLabel("语句全文（可全选复制）"))
        sl.addWidget(self.preview, 1)
        sl.addLayout(btns)

        self.right_split = QSplitter(Qt.Vertical)
        self.right_split.setChildrenCollapsible(False)
        self.right_split.setHandleWidth(6)
        self.right_split.addWidget(self.intro)
        self.right_split.addWidget(sql_box)
        self.right_split.setStretchFactor(0, 2)
        self.right_split.setStretchFactor(1, 3)
        self.right_split.setSizes([170, 330])

        self.split = QSplitter(Qt.Horizontal)
        self.split.setChildrenCollapsible(False)
        self.split.setHandleWidth(6)
        self.split.addWidget(left)
        self.split.addWidget(self.right_split)
        self.split.setStretchFactor(0, 38)
        self.split.setStretchFactor(1, 62)
        self.split.setSizes([360, 580])
        lay.addWidget(self.split, 1)

        self.tree.currentItemChanged.connect(self._on_pick)
        self._refilter("")            # 一开框就把"共 N 条，分 M 组"说出来
        if self._groups:
            first_leaf = self._groups[0][1][0]
            self._groups[0][0].setExpanded(True)
            self.tree.setCurrentItem(first_leaf)   # 打开就有一条被选中，右边不是空白

    def _build_tree(self):
        """按来源顺序搭两层目录：分组是父节点，条目只写短标题（长的那句在右侧）。

        叶子上带示例序号——点开哪条就按哪条取，命中与否不在这里缓存，
        判断统一走 sql_samples.matches，两栈同一个词才命中同一批条目。
        """
        current = None
        for idx, sample in enumerate(SQL_SAMPLES):
            if current is None or current[0] != sample.group:
                current = (sample.group, QTreeWidgetItem([sample.group]))
                self.tree.addTopLevelItem(current[1])
                self._groups.append((current[1], []))
                # 分组行按章节标题那一档画：加粗 + 强调蓝 + 浅蓝底，
                # 条目行保持常规黑——两层原来只差缩进，扫一眼分不出谁是谁
                grp = current[1]
                grp.setFont(0, QFont("", SAMPLE_TREE_FONT_SIZE, QFont.Bold))
                grp.setForeground(0, QBrush(QColor(SAMPLE_GROUP_FG)))
                grp.setBackground(0, QBrush(QColor(SAMPLE_GROUP_BG)))
            leaf = QTreeWidgetItem([sample.title])
            leaf.setData(0, Qt.UserRole, idx)
            leaf.setToolTip(0, sample.desc)
            current[1].addChild(leaf)
            self._groups[-1][1].append(leaf)

    def _refilter(self, needle):
        """搜索框逐字过滤：命中的叶子留下，整组没命中就把这组也收起藏掉。"""
        key = (needle or "").strip()
        hits = 0
        first = None
        for group_item, leaves in self._groups:
            shown = 0
            for leaf in leaves:
                ok = sample_matches(SQL_SAMPLES[leaf.data(0, Qt.UserRole)], needle)
                leaf.setHidden(not ok)
                if ok:
                    shown += 1
                    if first is None:
                        first = leaf
            group_item.setHidden(shown == 0)
            group_item.setExpanded(bool(key) and shown > 0)
            hits += shown
        if not key:
            self.lbl_hits.setText("共 %d 条，分 %d 组" % (len(SQL_SAMPLES), len(self._groups)))
        elif hits:
            self.lbl_hits.setText("命中 %d 条（共 %d 条）" % (hits, len(SQL_SAMPLES)))
        else:
            self.lbl_hits.setText("没有命中：换个关键词，或清空搜索框回到全部 %d 条"
                                  % len(SQL_SAMPLES))
        if first is not None:
            self.tree.setCurrentItem(first)

    def _sample_index(self):
        item = self.tree.currentItem()
        if item is None:
            return -1
        value = item.data(0, Qt.UserRole)
        return value if isinstance(value, int) else -1

    def intro_text(self, idx):
        """右侧上方那段介绍：纯文本四行，不引 HTML 引擎（两栈同一份排版口径）。"""
        s = SQL_SAMPLES[idx]
        return ("【%s · %s】\n说明：%s\n场景：%s\n解决的问题：%s\n关键词：%s"
                % (s.group, s.title, s.desc, s.scenario, s.problem, s.keywords))

    def _on_pick(self, item=None, _prev=None):
        idx = self._sample_index()
        if idx < 0:
            return                      # 点中的是分组那一行：右侧保持上一条示例
        _intro = self.intro_text(idx)
        # 内容仍是那五行，只是按角色上色：标题一档、字段名一档、关键词成一块
        paint_styled(self.intro, _intro, intro_spans(_intro),
                     intro_formats(self.intro), SAMPLE_INTRO_BLOCK_SPACING)
        # 语句正文与 DDL 预览共用同一套角色色：切分取自 sql_spans，格式取自 ddl_formats
        _sql = SQL_SAMPLES[idx].sql
        paint_styled(self.preview, _sql, sql_spans(_sql),
                     sql_formats(self.preview), DDL_BLOCK_SPACING)
        # 换一条就把两侧滚回开头：上一条长文留下的滚动位置会让新语句
        # 看着像"缺了上半截"（与 Java 侧 openSqlSamples 的复位同一处理）
        for view in (self.intro, self.preview):
            for bar in (view.verticalScrollBar(), view.horizontalScrollBar()):
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
APP_FONT_FILE = os.path.join("assets", "fonts", "NotoSansSC-Regular.ttf")
# 装进 Qt 之后的实际族名；自检与对撞读的是它，不是再抄一遍字面量
APP_FONT_FAMILY = ""


def load_app_font(app: QApplication) -> str:
    """把随包的界面字体装进 Qt，并让应用默认字体就用它。

    界面字体不再跟系统挑：2026-11-05 用户在 Win7 专业版上碰到整页中文成空方框，坏在 Java 线
    "按英文字面名在系统字体清单里挑一档"——中文系统枚举出来的是「微软雅黑」，英文名列在另一份
    locale 的清单里，挑不中就落到没有中文字形的 Segoe UI，而 JDK 8 对实体字族不做字形回退。
    两条线现在共用仓库里那一份思源黑体（Noto Sans SC，SIL OFL），客户机器上装了什么字体
    都影响不到渲染。字号仍走平台默认那一档：各控件自己写的字号一个字都不动。
    """
    global APP_FONT_FAMILY
    from PySide6.QtGui import QFontDatabase

    APP_FONT_FAMILY = ""
    path = resource_path(APP_FONT_FILE)
    if not os.path.exists(path):
        print("! [WARN] GUI | 包里没有随包字体 %s，界面沿用系统默认字体" % path)
        return ""
    fid = QFontDatabase.addApplicationFont(path)
    families = QFontDatabase.applicationFontFamilies(fid) if fid >= 0 else []
    if not families:
        print("! [WARN] GUI | 随包字体装不进 Qt（%s），界面沿用系统默认字体" % path)
        return ""
    APP_FONT_FAMILY = families[0]
    font = app.font()
    font.setFamily(APP_FONT_FAMILY)
    app.setFont(font)
    return APP_FONT_FAMILY


def setup_app(app: QApplication) -> None:
    """应用级设置。

    单独成函数是因为两条 GUI 线的对撞器要跑同一段代码，
    放在 __main__ 里就只有人手双击才能验到。
    """
    app.setWindowIcon(QIcon(resource_path(os.path.join("assets", "app_icon.ico"))))
    load_app_font(app)


def window_icon_side(widget):
    """这个窗口标题栏那颗图标的最大边长；一个都没挂就是 0。

    读的是窗口自己那份 `windowIcon()`，不是再问一次 QApplication：对撞要比的就是
    "这个窗口实际显示什么"。Qt 会把应用级图标落进每个顶层窗口的这个属性，所以
    两侧只要都读真窗口就能对上；Java 侧的同名读法是 gui.WindowIcons.sideOf。
    """
    sizes = widget.windowIcon().availableSizes()
    return max([max(s.width(), s.height()) for s in sizes], default=0)


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
