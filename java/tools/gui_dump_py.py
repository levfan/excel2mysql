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

from PySide6.QtWidgets import (QApplication, QCheckBox, QComboBox, QGridLayout, QLabel,  # noqa: E402
                               QLineEdit, QListWidget, QProgressBar, QPushButton, QSpinBox,
                               QTableWidget)
import main as m  # noqa: E402

app = QApplication(sys.argv)
m.MODAL_PROGRESS = False
POPUPS = []


class _StubBox(object):
    def __init__(self, parent):
        self.parent = parent

    def information(self, title, text, *a, **k):
        POPUPS.append(("information", title, text))
        return 0

    def warning(self, title, text, *a, **k):
        POPUPS.append(("warning", title, text))
        return 0

    def critical(self, title, text, *a, **k):
        POPUPS.append(("critical", title, text))
        return 0

    def question(self, *a, **k):
        POPUPS.append(("question", a[1] if len(a) > 1 else "", a[2] if len(a) > 2 else ""))
        return 0


m.QMessageBox = _StubBox
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


def popup_text():
    """最近一次弹窗的"标题|正文"，没弹就是空串。"""
    if not POPUPS:
        return ""
    _kind, title, text = POPUPS[-1]
    return "%s|%s|%s" % (_kind, title, text)


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
    win.resize(1100, 780)
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
    dump_widget("progress", win.progress)
    dump_buttons(win)
    dump_table("file", win.file_table)
    dump_table("sql", win.sql_table)
    put("hint.excel", win.lbl_imp_db.text())
    put("hint.sql", win.lbl_sql_db.text())
    put("drop.hint", win.drop.label.text())

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
    dump_table("file", win.file_table)
    for t in ("开始导入", "生成标准excel文件", "生成简易excel文件"):
        put("loaded.on.%s" % t, btn_on(win, t))
    put("loaded.log", win.log.toPlainText())
    put("loaded.hint.excel", win.lbl_imp_db.text())
    POPUPS.clear()
    win.on_start_import()
    put("guard.nodb.import", popup_text())

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
    lst = hd.findChild(QListWidget)
    put("dlg.help.toc", "|".join(lst.item(i).text() for i in range(lst.count())))
    hd.deleteLater()
    ad = m.AboutDialog(win)
    put("dlg.about.title", ad.windowTitle())
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
    put("export.std.popup.text", next((b for _k, _t, b in POPUPS if _t == u"生成结果"), ""))
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
    sink = m.ProgressSink(on_log=lambda t: win.sql_log.appendPlainText(t),
                          on_log_error=lambda t: win.sql_log.appendPlainText(t))
    win._finish_sql_load(m.sql_load_task([a, b], sink))
    app.processEvents()
    dump_table("sql", win.sql_table)
    put("sql.log", win.sql_log.toPlainText())
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

    # ---- 清空 ----
    win.on_clear()
    app.processEvents()
    dump_table("file", win.file_table)
    for t in ("开始导入", "生成标准excel文件", "生成简易excel文件"):
        put("cleared.on.%s" % t, btn_on(win, t))

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
