# -*- coding: utf-8 -*-
"""把 Python(PySide6) 那条 GUI 线的界面与行为枚举成扁平 JSON，供与 Swing 线逐键对撞。

为什么不用"读代码列条目"那套：上一轮就是这么自证的，结果用户连说五次"差距还很多"。
清单是自己写的，边界也就停在自己想到的地方。这里改成**从活对象上机器枚举**：
每个按钮/输入框/表格/弹窗都按 key 落一份事实，两条线的 key 集与值由比较器逐条对，
少一个按钮、文案差一个字、启用条件不同，都会以"键缺失/值不等"的形式露出来。

用法（仓库根目录）：
    .venv/Scripts/python.exe java/tools/gui_dump_py.py <输出json> [夹具目录]
"""
import json
import os
import re
import shutil
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    tempfile.gettempdir(), "e2m-gui-dump", "py.json")
FIX = sys.argv[2] if len(sys.argv) > 2 else os.path.join(REPO, "java", "verify", "fixtures")

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, REPO)

from PySide6.QtCore import Qt  # noqa: E402
from PySide6.QtWidgets import (QApplication, QCheckBox, QComboBox, QGridLayout, QLabel,  # noqa: E402
                               QLineEdit, QListWidget, QPlainTextEdit, QProgressBar,
                               QPushButton, QSpinBox, QTableWidget)
import main as m  # noqa: E402

app = QApplication(sys.argv)
m.setup_app(app)   # 图标等应用级设置走生产同一段代码
m.MODAL_PROGRESS = False
POPUPS = []


class _StubBox(object):
    def __init__(self, parent=None):
        self.parent = parent
        self._kind = "information"
        self._title = ""
        self._text = ""

    def setWindowTitle(self, title):
        self._title = title

    def setText(self, text):
        self._text = text

    def setIcon(self, icon):
        name = getattr(icon, "name", str(icon))
        self._kind = {"Information": "information", "Warning": "warning",
                      "Critical": "critical"}.get(name, "information")

    def setStandardButtons(self, buttons):
        pass

    def setMinimumWidth(self, width):
        pass

    def setMinimumHeight(self, height):
        pass

    def findChildren(self, cls):
        return []

    def exec(self):
        POPUPS.append((self._kind, self._title, self._text))
        return 0

    @staticmethod
    def information(parent, title, text, *a, **k):
        POPUPS.append(("information", title, text))
        return 0

    @staticmethod
    def warning(parent, title, text, *a, **k):
        POPUPS.append(("warning", title, text))
        return 0

    @staticmethod
    def critical(parent, title, text, *a, **k):
        POPUPS.append(("critical", title, text))
        return 0

    @staticmethod
    def question(parent, title, text, *a, **k):
        POPUPS.append(("question", title, text))
        return 0


m.QMessageBox = _StubBox
m._POPUP_MESSAGE_BOX = _StubBox
os.chdir(REPO)

D = {}
TMP = tempfile.mkdtemp(prefix="e2m-gui-dump-")
_PATH_RE = re.compile(r"[A-Za-z]:[\\/][^|;\"'\]\[）)）]*")
_DAY_RE = re.compile(r"(meta_data_file_|simple_meta_data_file_)\d{8}")


def norm(text):
    s = "" if text is None else str(text)
    s = s.replace("\r", "")
    s = _PATH_RE.sub(lambda mo: "<P>" + os.path.basename(mo.group(0).rstrip("/\\")), s)
    s = _DAY_RE.sub(lambda mo: mo.group(1) + "<DAY>", s)
    return s.strip()


def put(key, value):
    D[key] = norm(value)


def tip_of(w):
    try:
        return w.toolTip()
    except Exception:
        return ""


def dump_widget(role, w):
    if w is None:
        put("w.%s.missing" % role, "1")
        return
    put("w.%s.kind" % role, type(w).__name__)
    if not isinstance(w, (QSpinBox, QComboBox)):
        put("w.%s.text" % role, getattr(w, "text")() if hasattr(w, "text") else "")
    put("w.%s.tip" % role, tip_of(w))
    put("w.%s.on" % role, int(bool(w.isEnabled())))
    put("w.%s.vis" % role, int(bool(w.isVisible())))
    if isinstance(w, QLineEdit):
        put("w.%s.ph" % role, w.placeholderText())
        put("w.%s.echo" % role, w.echoMode().name)
    if isinstance(w, QSpinBox):
        put("w.%s.value" % role, w.value())
        put("w.%s.min" % role, w.minimum())
        put("w.%s.max" % role, w.maximum())
    if isinstance(w, QCheckBox):
        put("w.%s.checked" % role, int(bool(w.isChecked())))
    if isinstance(w, QProgressBar):
        put("w.%s.value" % role, w.value())
        put("w.%s.min" % role, w.minimum())
        put("w.%s.max" % role, w.maximum())
        put("w.%s.text" % role, w.format().replace("%p", "0"))
    if isinstance(w, QComboBox):
        put("w.%s.items" % role, ",".join(w.itemText(i) for i in range(w.count())))


def page_of(win, w):
    """按钮属于哪一页：两页都有「批量应用前缀」「清除日志」，不分页就会互相覆盖。

    QTabWidget 的页面控件挂在内部 QStackedWidget 下，不是 main_tabs 的直接子控件，
    所以只能自下而上找"哪一页控件是我的祖先"。
    """
    tabs = win.main_tabs
    for i in range(tabs.count()):
        page = tabs.widget(i)
        cur = w
        while cur is not None:
            if cur is page:
                return norm(tabs.tabText(i))
            cur = cur.parent()
    return "顶部"


def dump_buttons(win):
    seen = []
    for b in win.findChildren(QPushButton):
        t = norm(b.text())
        if not t:
            continue
        page = page_of(win, b)
        key = "btn.%s.%s" % (page, t)
        seen.append("%s|%s" % (page, t))
        put(key + ".on", int(bool(b.isEnabled())))
        put(key + ".tip", tip_of(b))
    put("btn.list", "|".join(sorted(seen)))
    bar = find_layout(win.main_tabs.widget(0), win.btn_import)
    order = []
    if bar is not None:
        for i in range(bar.count()):
            wd = bar.itemAt(i).widget()
            if wd is not None and isinstance(wd, QPushButton) and norm(wd.text()):
                order.append(norm(wd.text()))
    put("bar.excel.order", "|".join(order))


def dump_table(role, tw):
    if not isinstance(tw, QTableWidget):
        return
    cols = []
    for c in range(tw.columnCount()):
        it = tw.horizontalHeaderItem(c)
        cols.append(norm(it.text()) if it else "")
    put("table.%s.cols" % role, "|".join(cols))
    put("table.%s.width" % role, tw.columnCount())
    rows = []
    for r in range(tw.rowCount()):
        cells = []
        for c in range(tw.columnCount()):
            it = tw.item(r, c)
            cells.append(norm(it.text()) if it else "")
        rows.append(";".join(cells))
    put("table.%s.rows" % role, "\n".join(rows))


def find_layout(root, target):
    """在 root 这棵子树里找真正装着 target 的那层布局。

    Qt 的嵌套 layout 不会把控件改父级，parentWidget().layout() 拿到的往往是整页的
    QVBoxLayout；而页与页之间是 widget 项而不是 layout 项，所以两样都要往下递归。
    """
    stack = [root.layout()] if root.layout() is not None else []
    while stack:
        lay = stack.pop()
        for i in range(lay.count()):
            item = lay.itemAt(i)
            if item.widget() is target:
                return lay
            if item.layout() is not None:
                stack.append(item.layout())
            if item.widget() is not None and item.widget().layout() is not None:
                stack.append(item.widget().layout())
    return None


def popup_body(text):
    """弹窗正文去掉折行引入的换行，只留内容（与 Java 侧 popupBody 同一件事）。"""
    return (text or "").replace("\n", "")


def popup_text():
    """最近一次弹窗的"图标|标题|正文"，没弹就是空串。

    正文去掉换行再比：换行位置由折行规则按字符宽度算，而两边弹窗里带的产物目录
    长度本来就不一样，折出来的行也不同。排版本身另有 wrap.single / wrap.path /
    wrap.tall 三个键钉住（同样的输入必须折出同样的串），这里只比"说的话"。
    """
    if not POPUPS:
        return ""
    _kind, title, text = POPUPS[-1]
    return "%s|%s|%s" % (_kind, title, text.replace("\n", ""))


def _db_tone(w):
    """目标库信息条现在是哪一档颜色：绿色=已选定、红色=没选库，别的写法算 none。"""
    s = w.styleSheet()
    if "#1a7f37" in s:
        return "ok"
    if "#c62828" in s:
        return "bad"
    return "none"


def btn_on(win, text):
    """按文案取「Excel 导入」页上那颗按钮的当前可点状态（两页重名的按钮按页取）。"""
    for b in win.findChildren(QPushButton):
        if norm(b.text()) == text and page_of(win, b) == u"Excel 导入":
            return int(bool(b.isEnabled()))
    return "缺"


def names(win):
    return "|".join(t["table_name"] for t in win.tables)


def main():
    win = m.MainWindow()
    win.show()
    app.processEvents()

    put("tabs", "|".join(win.main_tabs.tabText(i)
                        for i in range(win.main_tabs.count())))
    dump_widget("host", win.ed_host)
    dump_widget("port", win.ed_port)
    dump_widget("user", win.ed_user)
    dump_widget("pass", win.ed_pass)
    dump_widget("db", win.cb_db)
    dump_widget("prefix", win.ed_prefix)
    dump_widget("sql_prefix", win.ed_sql_prefix)
    dump_widget("drop_existing", win.chk_drop)
    dump_widget("initials", win.chk_initials)
    dump_widget("upper", win.chk_upper)
    dump_widget("sql_upper", win.chk_sql_upper)
    dump_widget("progress", win.progress)
    dump_buttons(win)
    dump_table("file", win.file_table)
    dump_table("sql", win.sql_table)
    put("hint.excel", win.lbl_imp_db.text())
    put("hint.sql", win.lbl_sql_db.text())
    put("drop.hint", win.drop.label.text())

    # ---- 第三个页签「SQL 执行器」：常驻可见量 + 示例对话框的一来一回 ----
    put("hint.sqlrun", norm(win.sql_run_hint.text()))
    put("hint.sqlrun.db", win.lbl_run_db.text())
    # 页面上的静态标签按字典序连起来比：两边的控件遍历顺序本来就不同（Qt 按创建序、
    # Swing 按加进容器的序），排序后比的是"这一页上有哪几句话"。
    put("sqlrun.labels", "|".join(sorted(
        norm(l.text()) for l in win.main_tabs.widget(2).findChildren(QLabel) if norm(l.text()))))
    put("sqlrun.default", win.sql_run_input.toPlainText())
    put("sqlrun.wrap", "wrap" if win.sql_run_input.lineWrapMode()
        == QPlainTextEdit.WidgetWidth else "nowrap")
    POPUPS.clear()
    win.sql_run_input.clear()
    win.on_sql_run()
    put("guard.sqlrun.empty", popup_text())
    win.sql_run_input.setPlainText(m.SQL_RUN_DEFAULT)
    win.on_sql_samples()
    app.processEvents()
    _sd = win.sql_run_samples
    put("samples.title", _sd.windowTitle())
    put("samples.count", _sd.list.count())
    put("samples.item.0", norm(_sd.list.item(0).text()))
    put("samples.preview.0", _sd.preview.toPlainText())
    _sd.list.setCurrentRow(7)
    put("samples.preview.7", _sd.preview.toPlainText())
    # 不比对「复制到剪贴板」的那句回执：剪贴板可能被别的进程占用，
    # 两条线各跑一次的成败不同，那是现场状态不是界面差异（两条线各自的自检里咬回执本身）
    _sd.show()
    win.sql_run_input.setPlainText(m.SQL_RUN_DEFAULT)
    _sd.list.setCurrentRow(3)
    _sd.btn_insert.click()
    put("samples.insert.default", win.sql_run_input.toPlainText())
    _sd.show()
    win.sql_run_input.setPlainText("SELECT 1")
    _sd.list.setCurrentRow(1)
    _sd.btn_insert.click()
    # 追加那条只比"补没补分号换行"：两条线的行分隔符不同（\r\n 与 \n），
    # 正文本身已由 samples.sql.1 那一键钉住。
    put("samples.insert.append",
        "yes" if win.sql_run_input.toPlainText().startswith("SELECT 1;\n") else "no")
    win.sql_run_input.setPlainText(m.SQL_RUN_DEFAULT)

    # ---- 空态：哪些按钮该是灰的 ----
    for t in ("开始导入", "生成标准excel文件", "生成简易excel文件", "预览 DDL",
              "生成表结构预览", "停止"):
        put("empty.on.%s" % t, btn_on(win, t))

    # ---- 空列表 / 没选库 / 拖错文件 / 空点停止：把说的那句话取出来对 ----
    POPUPS.clear()
    win.on_preview_ddl()
    put("guard.empty.ddl", popup_text())
    POPUPS.clear()
    win.on_preview_structure()
    put("guard.empty.structure", popup_text())
    POPUPS.clear()
    win.on_gen_standard()
    put("guard.empty.gen_std", popup_text())
    POPUPS.clear()
    win.on_gen_simple()
    put("guard.empty.gen_simple", popup_text())
    mark = win.log.toPlainText()
    win.on_files_dropped([os.path.join(TMP, "readme.txt")])
    put("guard.drop.txt", win.log.toPlainText()[len(mark):].strip())
    mark = win.log.toPlainText()
    win.on_stop()
    put("guard.stop", win.log.toPlainText()[len(mark):].strip())

    # ---- 连不上库时的那句话：只比固定文案那一行 ----
    # 驱动细节（pymysql 的 2003 与 JDBC 的 Communications link failure）必然不同，
    # 那是运行时差异不是界面差异；但"无法连接到 主机:端口（用户 X），库列表未加载"
    # 这一句和弹窗标题是两条线共用的契约。
    win.ed_host.setText("127.0.0.1")
    win.ed_port.setValue(1)
    POPUPS.clear()
    mark = win.log.toPlainText()
    win.on_load_databases()
    put("guard.conn.popup", "%s|%s" % (POPUPS[-1][0], POPUPS[-1][1]) if POPUPS else "")
    put("guard.conn.log", win.log.toPlainText()[len(mark):].strip().split("\n")[0])
    put("guard.conn.db_enabled", int(bool(win.cb_db.isEnabled())))

    # ---- 加载夹具 ----
    files = sorted(os.path.join(FIX, f) for f in os.listdir(FIX)
                   if f.lower().endswith((".xlsx", ".xls")))
    win._add_files(files)
    app.processEvents()
    put("loaded.names", names(win))
    put("file.serial", "%s=%s" % (
        win.file_table.horizontalHeaderItem(0).text(),
        ",".join(win.file_table.item(i, 0).text()
                 for i in range(win.file_table.rowCount()))))
    dump_table("file", win.file_table)
    for t in ("开始导入", "生成标准excel文件", "生成简易excel文件"):
        put("loaded.on.%s" % t, btn_on(win, t))
    put("loaded.log", win.log.toPlainText())
    # 日志框最小高度按"行数"约定（两栈 16pt 的行高不同，比像素数会各说各话）：
    # 缩成两行的日志框等于没有，这条比的就是两边都留够四行
    put("log.excel.minh", str(m.LOG_MIN_LINES))
    put("log.sql.minh", str(m.LOG_MIN_LINES))
    put("log.run.minh", str(m.LOG_MIN_LINES))
    put("loaded.hint.excel", win.lbl_imp_db.text())
    put("add.popup", popup_text())
    # 两侧都量"程序自己画的那块地方"：Qt 顶层窗口的 width/height 就是客户区。
    # 跨线比的是配置值 want（两边必须写同一个数）；实测值受现场屏幕与夹取影响，只记不比。
    put("window.want", str(win.property("e2mWant") or ""))
    put("env.window.size", "%dx%d" % (win.width(), win.height()))
    ic = app.windowIcon()   # 图标挂在 QApplication 上（main.py 末尾 setWindowIcon），不是挂在窗口上
    sizes = sorted(ic.availableSizes(), key=lambda s: max(s.width(), s.height()))
    put("window.icon", "none" if not sizes
        else "max=%d" % max(sizes[-1].width(), sizes[-1].height()))
    tip0 = win.file_table.item(0, 1)
    put("file.tooltip.0", tip0.toolTip() if tip0 else "")
    # 文件表只有「表名」列可编辑；单击改名、非法名恢复原名，两条线逐句话比
    put("file.rename.editable", "|".join(
        str(int(bool(win.file_table.item(0, c).flags() & Qt.ItemIsEditable)))
        for c in (2, 0, 3)))
    _rename_old = win.tables[0]["table_name"]
    win.file_table.item(0, 2).setText(_rename_old + "_probe")
    app.processEvents()
    _log_lines = [ln for ln in win.log.toPlainText().splitlines() if ln.strip()]
    put("file.rename.ok", "%s|%s|%s" % (
        win.tables[0]["table_name"], win.tables[0]["base_table_name"],
        _log_lines[-1] if _log_lines else ""))
    POPUPS.clear()
    win.file_table.item(0, 2).setText("")
    app.processEvents()
    put("file.rename.empty", "%s|%s" % (win.tables[0]["table_name"], popup_text()))
    POPUPS.clear()
    win.file_table.item(0, 2).setText("bad name")
    app.processEvents()
    put("file.rename.bad", "%s|%s" % (win.tables[0]["table_name"], popup_text()))
    POPUPS.clear()
    win.file_table.item(0, 2).setText(win.tables[1]["table_name"])
    app.processEvents()
    put("file.rename.dupe", "%s|%s" % (win.tables[0]["table_name"], popup_text()))
    win.file_table.item(0, 2).setText(_rename_old)
    app.processEvents()
    put("file.rename.restored", "%s|%s" % (
        win.tables[0]["table_name"], win.tables[0]["base_table_name"]))
    # 折行规则本身也要跨线比：同一段最坏文案，两边必须折出同一个字符串
    put("wrap.single", m.wrap_popup_text(
        "任务异常：读取 C:/数据/很长的一个文件名防空警报试鸣信息（dim_air_raid_siren）.xlsx 时失败，"
        "内部单元格 D17 的类型与推断结果冲突，需要人工确认这一列到底是文本还是数字，"
        "详见日志里的完整堆栈信息。"))
    put("wrap.path", m.wrap_popup_text(
        "已生成 24 个文件（共 158 张表）。\n目录：\n"
        "D:\\00.personal\\4.code\\excel2mysql\\java\\dist\\excel2mysql-java-1.0.1-win64"
        "\\app\\meta_data_file_20261014"))
    put("wrap.tall", m.wrap_popup_text("\n".join(
        "第 %d 行：这一行本身就不短，加上中文标点和路径信息会更长一些一些一些。" % i
        for i in range(1, 31))))
    # 双击不是"接了信号"就算数，要真的建出那一张表的结构窗口。
    # 真窗口是模态的，离屏 exec() 会挂住进程，所以只把 exec 换成"记一笔、返回取消"，
    # 构造过程走真代码。
    _real_sd = m.StructureDialog
    _opened = []

    class _ProbeSd(_real_sd):
        def exec(self):
            _opened.append(self)
            return 0

    m.StructureDialog = _ProbeSd
    try:
        win.file_table.setCurrentCell(0, 0)
        win.file_table.cellDoubleClicked.emit(0, 0)
    finally:
        m.StructureDialog = _real_sd
    if _opened:
        _sd = _opened[0]
        _titles = "|".join(_sd.tabs.tabText(i) for i in range(_sd.tabs.count()))
        _sd.deleteLater()
        put("dblclick.structure", "1|%d|%s" % (_sd.tabs.count(), _titles))
    else:
        put("dblclick.structure", "0|0|")
    POPUPS.clear()
    win.on_start_import()
    put("guard.nodb.import", popup_text())

    # ---- 结构弹窗改一格 → DDL 要跟着变（编辑真的落进表定义）----
    _t0 = win.tables[0]["columns"][0]
    _before_type, _before_ddl = _t0["type"], None
    _t0["type"] = "VARCHAR(123)"
    _dlg = m.DDLDialog(win.tables, False, win)
    put("structure.edit.ddl", "1" if "VARCHAR(123)" in _dlg.tabs.widget(0).toPlainText() else "0")
    _dlg.deleteLater()
    _t0["type"] = _before_type

    # ---- 压缩包：展开、汇总、来源 tooltip、临时目录清理 ----
    zdir = os.path.join(TMP, "zip")
    os.makedirs(zdir)
    import zipfile as _zf
    zpath = os.path.join(zdir, "pack1.zip")
    with _zf.ZipFile(zpath, "w") as zf:
        zf.writestr("readme.txt", "不是 Excel")
        with open(files[0], "rb") as fh:
            zf.writestr("inner.xlsx", fh.read())
    _n_before = len(win.tables)
    POPUPS.clear()
    mark = win.log.toPlainText()
    win._add_files([zpath])
    app.processEvents()
    put("zip.added", len(win.tables) - _n_before)
    put("zip.log", win.log.toPlainText()[len(mark):].strip())
    put("zip.popup", popup_text())
    _last = win.file_table.rowCount() - 1
    _it = win.file_table.item(_last, 1)
    put("zip.tooltip", _it.toolTip() if _it else "")

    # ---- 前缀两步 ----
    win.ed_prefix.setText("ods_")
    win.on_apply_prefix()
    app.processEvents()
    put("prefix.ods.names", names(win))
    win.ed_prefix.setText("change")
    win.on_replace_prefix()
    app.processEvents()
    put("prefix.change.names", names(win))
    win.ed_prefix.setText("")
    win.on_apply_prefix()
    app.processEvents()
    put("prefix.cleared.names", names(win))

    # ---- DDL：不勾选与勾选覆盖已存在表 ----
    dlg = m.DDLDialog(win.tables, False, win)
    put("dlg.ddl.want", str(dlg.property("e2mWant") or ""))
    put("env.dlg.ddl.size", "%dx%d" % (dlg.width(), dlg.height()))
    put("ddl.tabs", "|".join(dlg.tabs.tabText(i) for i in range(dlg.tabs.count())))
    put("ddl.first", dlg.tabs.widget(0).toPlainText())
    dlg.deleteLater()
    win.chk_drop.setChecked(True)
    dlg2 = m.DDLDialog(win.tables, True, win)
    put("ddl.drop.first", dlg2.tabs.widget(0).toPlainText())
    dlg2.deleteLater()
    win.chk_drop.setChecked(False)

    # ---- 结构弹窗 ----
    sd = m.StructureDialog(win.tables[:2], win)
    put("dlg.structure.title", sd.windowTitle())
    put("dlg.structure.want", str(sd.property("e2mWant") or ""))
    put("env.dlg.structure.size", "%dx%d" % (sd.width(), sd.height()))
    put("dlg.structure.tabs", "|".join(sd.tabs.tabText(i)
                                       for i in range(sd.tabs.count())))
    first = sd.tabs.widget(0)
    put("dlg.structure.cols", "|".join(norm(first.horizontalHeaderItem(c).text())
                                       for c in range(first.columnCount())))
    def srow(r):
        cells = []
        for c in range(first.columnCount()):
            if c == 0:
                cw = first.cellWidget(r, 0)
                cells.append("1" if (cw is not None and cw.isChecked()) else "0")
                continue
            it = first.item(r, c)
            cells.append(norm(it.text()) if it else "")
        return ";".join(cells)

    put("dlg.structure.rows", "\n".join(srow(r) for r in range(min(3, first.rowCount()))))
    sd.deleteLater()

    # ---- 说明与关于 ----
    hd = m.HelpDialog(win)
    put("dlg.help.title", hd.windowTitle())
    put("dlg.help.want", str(hd.property("e2mWant") or ""))
    put("env.dlg.help.size", "%dx%d" % (hd.width(), hd.height()))
    lst = hd.findChild(QListWidget)
    put("dlg.help.toc", "|".join(lst.item(i).text() for i in range(lst.count())))
    hd.deleteLater()
    ad = m.AboutDialog(win)
    put("dlg.about.title", ad.windowTitle())
    put("dlg.about.want", str(ad.property("e2mWant") or ""))
    put("env.dlg.about.size", "%dx%d" % (ad.width(), ad.height()))
    captions = []
    for lay in ad.findChildren(QGridLayout):
        for i in range(lay.rowCount()):
            it = lay.itemAtPosition(i, 0)
            wd = it.widget() if it is not None else None
            if wd is not None and norm(wd.text()):
                captions.append(norm(wd.text()))
    put("dlg.about.rows", "|".join(captions))
    ad.deleteLater()

    # ---- 进度框文案 ----
    pd = m.ProgressDialog(u"正在解析文件…", win)
    put("dlg.progress.title", pd.windowTitle())
    put("dlg.progress.want", str(pd.property("e2mWant") or ""))
    put("env.dlg.progress.size", "%dx%d" % (pd.width(), pd.height()))
    pd.set_progress(1, 3, u"读盘")
    put("dlg.progress.status", pd.lbl_step.text())
    put("dlg.progress.percent", pd.bar.text())
    put("dlg.progress.cancel", pd.btn_cancel.text())
    pd.deleteLater()

    # ---- 生成标准/简易 excel：产物本身也进对撞 ----
    out_root = os.path.join(TMP, "out")
    os.makedirs(out_root)
    real_pdir = m.program_dir
    m.program_dir = lambda: out_root
    win._gen_excel(u"标准", True)
    app.processEvents()
    put("export.std.log", win.log.toPlainText())
    put("export.std.popup.title", next((t for _k, t, _b in POPUPS if t == u"生成结果"), ""))
    put("export.std.popup.text", popup_body(
        next((b for _k, _t, b in POPUPS if _t == u"生成结果"), "")))
    std = os.path.join(out_root, [x for x in sorted(os.listdir(out_root))
                                  if x.startswith("meta_data_file_")][0])
    put("export.std.dir", os.path.basename(std))
    put("export.std.files", "|".join(sorted(os.listdir(std))))
    put("export.std.content", dump_xlsx(std))
    POPUPS[:] = []
    win._gen_excel(u"简易", False)
    app.processEvents()
    sim = os.path.join(out_root, [x for x in sorted(os.listdir(out_root))
                                  if x.startswith("simple_meta_data_file_")][0])
    put("export.simple.dir", os.path.basename(sim))
    put("export.simple.content", dump_xlsx(sim))
    m.program_dir = real_pdir

    # ---- SQL 页 ----
    sql_dir = os.path.join(TMP, "sql")
    os.makedirs(sql_dir)
    a = os.path.join(sql_dir, "a.sql")
    with open(a, "w", encoding="utf-8") as fh:
        fh.write("CREATE TABLE t_order (id INT);\n"
                 "INSERT INTO `t_order` VALUES (1);\n"
                 "CREATE TABLE plain (id INT);\n")
    b = os.path.join(sql_dir, "b.sql")
    with open(b, "w", encoding="utf-8") as fh:
        fh.write("CREATE TABLE dim_x (id INT);\n")
    sink = m.ProgressSink(on_log=lambda t: m.append_log(win.sql_log, t),
                          on_log_error=lambda t: m.append_log(win.sql_log, t, error=True))
    win._finish_sql_load(m.sql_load_task([a, b], [], sink))
    app.processEvents()
    dump_table("sql", win.sql_table)
    put("sql.log", win.sql_log.toPlainText())
    put("sql.add.popup", popup_text().replace(sql_dir, u"根目录"))
    win.ed_sql_prefix.setText("ods_")
    win.on_sql_apply_prefix()
    app.processEvents()
    dump_table("sql", win.sql_table)
    put("sql.prefixed.log", win.sql_log.toPlainText())
    put("sql.hint", win.lbl_sql_db.text())
    POPUPS.clear()
    win.ed_sql_prefix.setText("")
    win.on_sql_apply_prefix()
    put("guard.sql.empty_prefix", popup_text())

    # ---- SQL 页：移除选中、保存副本、没选库就执行 ----
    POPUPS.clear()
    mark = win.sql_log.toPlainText()
    win.sql_table.selectRow(0)
    win.on_sql_remove_selected()
    put("sql.remove.log", win.sql_log.toPlainText()[len(mark):].strip())
    mark = win.sql_log.toPlainText()
    win.on_sql_save_modified()
    app.processEvents()
    put("sql.save.log", win.sql_log.toPlainText()[len(mark):].strip())
    put("sql.save.popup", popup_text().replace(sql_dir, u"根目录"))
    POPUPS.clear()
    win.on_sql_execute()
    put("sql.execute.nodb", popup_text())

    # ---- SQL 页：两颗生成按钮（脚本正文 -> 标准/简易件） ----
    # 先把上面那两份脚本从页面清干净，生成只喂 probe.sql，
    # 否则产物清单里混着 t_order/dim_x，两边比的就是"清场是否一致"而不是解析
    while win.sql_files:
        win.sql_table.selectRow(0)
        win.on_sql_remove_selected()
        app.processEvents()
    gdir = os.path.join(TMP, "sqlgen")
    os.makedirs(gdir)
    probe = os.path.join(gdir, "probe.sql")
    with open(probe, "w", encoding="utf-8") as fh:
        fh.write(u"CREATE TABLE `t_gen` (\n"
                 u"  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',\n"
                 u"  `st` enum('a','b') DEFAULT 'a',\n"
                 u"  PRIMARY KEY (`id`)\n"
                 u") ENGINE=InnoDB COMMENT='生成检查表';\n"
                 u"CREATE TABLE t_dup9 (x INT);\n"
                 u"CREATE TABLE t_dup9 (y INT);\n"
                 u"CREATE TABLE t_like9 LIKE t_dup9;\n")
    gmark = win.sql_log.toPlainText()
    win._finish_sql_load(m.sql_load_task([probe], [], sink))
    app.processEvents()
    std_root = os.path.join(gdir, "std")
    smp_root = os.path.join(gdir, "smp")
    os.makedirs(std_root)
    os.makedirs(smp_root)
    POPUPS[:] = []
    m.program_dir = lambda: std_root
    win.on_sql_gen_excel(u"标准", True)
    app.processEvents()
    m.program_dir = lambda: smp_root
    win.on_sql_gen_excel(u"简易", False)
    app.processEvents()
    m.program_dir = real_pdir
    gen_log = win.sql_log.toPlainText()[len(gmark):].strip()
    gen_log = gen_log.replace(gdir, u"根目录")
    put("sqlgen.log", re.sub(r"(simple_)?meta_data_file_\d{8}",
                             r"\1meta_data_file_日期", gen_log))
    # 生成结果弹窗：标题与正文两边都要同一句（正文里的目录同样归一化）
    put("sqlgen.popup", re.sub(r"(simple_)?meta_data_file_\d{8}",
                               r"\1meta_data_file_日期",
                               popup_text().replace(gdir, u"根目录")))
    std = os.path.join(std_root, [x for x in sorted(os.listdir(std_root))
                                  if x.startswith("sql_meta_data_file_")][0])
    smp = os.path.join(smp_root, [x for x in sorted(os.listdir(smp_root))
                                  if x.startswith("sql_simple_meta_data_file_")][0])
    put("sqlgen.std.content", dump_xlsx(std))
    put("sqlgen.simple.content", dump_xlsx(smp))
    # 用本条线自己的读取器把标准件读回来，渲染成一行给对撞器比（读不回就是缺陷）
    try:
        gen_file = os.path.join(std, [x for x in sorted(os.listdir(std))
                                      if "t_gen" in x][0])
        td = m.build_table_def(gen_file)
        line = [td["source"], td["table_name"], td.get("table_comment") or ""]
        for c in td["columns"]:
            line.append("%s:%s:%s" % (c["field"], c["type"],
                                      "pk" if c.get("primary_key") else "-"))
        put("sqlgen.roundtrip", "|".join(line))
    except Exception as e:
        put("sqlgen.roundtrip", "读不回 " + type(e).__name__)

    win.on_clear()
    app.processEvents()
    dump_table("file", win.file_table)
    for t in ("开始导入", "生成标准excel文件", "生成简易excel文件"):
        put("cleared.on.%s" % t, btn_on(win, t))

    # ---- 三页顶部的目标库信息条必须同源：塞一个库进去，三句话和三种颜色要一起变 ----
    # 各页各写一份刷新的话，迟早会在某个页签看到"上一个库"；host:port 只在有库时才进文案，
    # 所以这里必须真选一个库，不能拿空态糊过去。放在最后一步做，免得把前面那些
    # "没选库当场拦下"的探针带偏（它们断的就是空态那句话）。
    win.cb_db.addItem(u"alpha", u"alpha")
    app.processEvents()
    put("hint.db.linked", u"|".join([win.lbl_imp_db.text(), win.lbl_sql_db.text(),
                                     win.lbl_run_db.text()]))
    put("hint.db.tone", u"|".join(_db_tone(w) for w in
                                  (win.lbl_imp_db, win.lbl_sql_db, win.lbl_run_db)))

    out_dir = os.path.dirname(os.path.abspath(OUT))
    if not os.path.isdir(out_dir):
        os.makedirs(out_dir)
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(D, fh, ensure_ascii=False, indent=1, sort_keys=True)
    print("+ [OK] GUI-DUMP-PY | %d 个键 | %s" % (len(D), OUT))
    shutil.rmtree(TMP, ignore_errors=True)
    return 0


def dump_xlsx(folder):
    """目录里每个 xlsx 的 Sheet 名与逐格值，压成一段可比对的文本。"""
    from openpyxl import load_workbook

    out = []
    for name in sorted(os.listdir(folder)):
        wb = load_workbook(os.path.join(folder, name), read_only=True, data_only=True)
        out.append("### " + name)
        for ws in wb.worksheets:
            out.append("[%s]" % ws.title)
            for row in ws.iter_rows(values_only=True):
                out.append(";".join("" if v is None else str(v) for v in row))
        wb.close()
    return "\n".join(out)


if __name__ == "__main__":
    sys.exit(main())
