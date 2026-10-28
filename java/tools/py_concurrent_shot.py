"""真 windows 平台上的并发取证：两条任务同时在跑时，另外的页点不点得动。

为什么单独一个探针，不并进 `pack_test.py`：用例跑在 `QT_QPA_PLATFORM=offscreen` 上，
`grabWindow(0)` 拿到的是虚拟屏幕，像素证明不了"框没挡住窗口"。这一支不设离屏平台，
起的是用户看到的同一个窗口，抓的是真屏幕像素，还顺手把第三页的按钮真点一次。

用法（仓库根目录，用带 PySide6 的解释器）：
    .venv/Scripts/python.exe java/tools/py_concurrent_shot.py [输出目录]
产出：`screen.png`（整屏，两个进度框 + 主窗口同屏）与 `page3_samples.png`（第三页点出来的清单），
外加 `report.txt` 逐条 PASS/FAIL。退出码 0 只在全过的时候给。

会闪出真窗口，全程十几秒；夹具在本机实测够慢（解析八个七千行约 4 秒、
生成 150 个 xlsx 约 1.2 秒），两条任务的时间窗足够重叠。
"""
import io
import os
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    tempfile.gettempdir(), "e2m-shots", "py-concurrent")
REPORT = os.path.join(OUT, "report.txt")

sys.path.insert(0, REPO)
os.makedirs(OUT, exist_ok=True)

_notes = []
_fails = []


def log(msg):
    _notes.append(str(msg))
    with io.open(REPORT, "w", encoding="utf-8") as f:
        f.write("\n".join(_notes) + "\n")


def check(name, ok, detail=None):
    log("%s %s%s" % ("PASS" if ok else "FAIL", name,
                     "" if ok or detail is None else "  | %r" % (detail,)))
    if not ok:
        _fails.append(name)


def make_big_xlsx(path, rows, tag):
    from openpyxl import Workbook
    wb = Workbook(write_only=True)
    ws = wb.create_sheet("数据")
    ws.append(["编号", "名称", "金额", "数量", "日期", "备注"])
    for i in range(rows):
        ws.append(["N%08d" % i, "%s名称%d" % (tag, i), 3.14 + i % 100, i % 997,
                   "2026-03-%02d" % (i % 28 + 1), "备注%d" % (i % 50)])
    wb.save(path)
    return path


def make_sql(path, tables):
    with io.open(path, "w", encoding="utf-8") as f:
        for i in range(tables):
            f.write("CREATE TABLE t_shot_%05d (\n" % i)
            for c in range(8):
                f.write("  col_%02d VARCHAR(64) DEFAULT NULL COMMENT '字段%d',\n" % (c, c))
            f.write("  PRIMARY KEY (id)\n) ENGINE=InnoDB COMMENT='取证表%d';\n" % i)
    return path


def pump(seconds):
    """等这么久但照常泵事件循环：站在主线程睡就等于自己把界面掐死。"""
    from PySide6.QtWidgets import QApplication
    deadline = time.time() + seconds
    while time.time() < deadline:
        QApplication.processEvents()
        time.sleep(0.005)
    QApplication.processEvents()


def wait_until(pred, seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        pump(0.02)
        if pred():
            return True
    return False


def ink_pixels(pixmap, step=2):
    """与占比最高的那个颜色（背景）不同的抽样像素数。

    不用"不同颜色数"当空帧判据：进度框这种"大片白 + 几行细字 + 一根条"的界面，
    按 23 像素步长抽样只会数出两三种颜色，看着像空帧其实拍得好好的（实测踩过）。
    """
    img = pixmap.toImage()
    hist = {}
    for y in range(0, img.height(), step):
        for x in range(0, img.width(), step):
            c = img.pixel(x, y)
            hist[c] = hist.get(c, 0) + 1
    if not hist:
        return 0
    bg = max(hist, key=lambda k: hist[k])
    return sum(v for k, v in hist.items() if k != bg)


def distinct_colors(pixmap, step=23):
    """按步长抽样数不同颜色数：整屏全白/全黑就是抓了个空帧。"""
    img = pixmap.toImage()
    seen = set()
    for y in range(0, img.height(), step):
        for x in range(0, img.width(), step):
            seen.add(img.pixel(x, y))
            if len(seen) > 400:
                return len(seen)
    return len(seen)


def _families():
    """Qt6 把 QFontDatabase 的无参构造标了废弃，静态取法在部分版本上没有——两种都试。"""
    from PySide6.QtGui import QFontDatabase
    try:
        return list(QFontDatabase.families())
    except Exception:  # noqa: BLE001
        return list(QFontDatabase().families())


def main():
    from PySide6 import QtGui
    from PySide6.QtWidgets import QApplication
    import main as m

    app = QApplication(sys.argv)
    log("qt platform=%s（不设 QT_QPA_PLATFORM=%r）"
        % (app.platformName(), os.environ.get("QT_QPA_PLATFORM")))
    fams = _families()
    cjk = [x for x in fams if "yahei" in x.lower() or "hei" in x.lower()
           or "song" in x.lower()]
    log("字体族 %d 个，CJK 命中 %s（豆腐块会让截图看不出排版）" % (len(fams), cjk[:4]))

    # 结果弹窗与消息框压掉：这一支验的是"框挡不挡窗口"，不是弹窗内容；
    # 弹窗 exec() 会真的把人拦在这里，探针就再也走不到抓屏那一步
    m.popup_box = lambda *a, **k: 0
    for _n in ("information", "warning", "critical", "question"):
        setattr(m.QMessageBox, _n, staticmethod(lambda *a, **k: 0))

    tmp = tempfile.mkdtemp(prefix="e2m-shot-")
    rows, tables = 7500, 150
    xlsx = [make_big_xlsx(os.path.join(tmp, "取证大表%d.xlsx" % k), rows, chr(65 + k))
            for k in range(8)]
    sql = make_sql(os.path.join(tmp, "取证生成.sql"), tables)
    gen_root = os.path.join(tmp, "生成输出")
    os.makedirs(gen_root, exist_ok=True)

    m.MODAL_PROGRESS = True
    m.program_dir = lambda: gen_root
    win = m.MainWindow()
    win.resize(1500, 950)
    win.show()
    pump(0.3)

    # 先把 SQL 读进第二页（生成要有表可写），这一步也走同一个任务框架
    win._run_modal("正在读取 SQL 脚本…", m.sql_load_task, ([sql], []),
                   on_done=win._finish_sql_load, log_view=win.sql_log)
    check("前置：脚本读完了（列表里 %d 项）" % tables,
          wait_until(lambda: not win._tasks and win.sql_files, 30),
          [t.title for t in win._tasks])

    # 两条真任务同时起：第一页解析、第二页写盘
    win._add_files(xlsx)
    win.on_sql_gen_excel("标准", True)
    both = wait_until(lambda: len(win._tasks) == 2, 8)
    dialogs = [t.dialog for t in win._tasks if t.dialog is not None]
    log("在跑的任务：%s" % [t.describe() for t in win._tasks])
    check("两条真任务同时在跑", both and len(win._tasks) == 2,
          [t.describe() for t in win._tasks])
    check("两个真进度框都在屏且都不是模态的",
          len(dialogs) == 2 and all(d.isVisible() and not d.isModal()
                                    for d in dialogs),
          [(d.isVisible(), d.isModal()) for d in dialogs])
    if len(dialogs) == 2:
        # 不替产品挪位置：挪了就等于自己造出"不重叠"，那张图就不能说明默认摆放是对的
        pump(0.2)
        ids = [int(d.winId()) for d in dialogs]
        pos = [(d.x(), d.y()) for d in dialogs]
        check("两个框是两个独立顶层窗口（各自有 winId 与位置）",
              len(set(ids)) == 2 and len(set(pos)) == 2, (ids, pos))

    # 关键一步：任务还在跑，切到第三页真点一颗按钮
    win.main_tabs.setCurrentIndex(2)
    pump(0.2)
    win.btn_sql_run_samples.click()
    pump(0.4)
    samp = win.sql_run_samples
    check("任务在跑时第三页「示例 (?)」点得出东西（非模态清单开出来了）",
          win.main_tabs.currentIndex() == 2 and samp is not None
          and samp.isVisible() and not samp.isModal()
          and sum(len(ls) for _g, ls in samp._groups) == len(m.SQL_SAMPLES),
          (win.main_tabs.currentIndex(), None if samp is None else
           (samp.isVisible(), samp.isModal(),
            sum(len(ls) for _g, ls in samp._groups))))

    screen = app.primaryScreen()
    # 先把本程序几个窗口抬到前台再抓整屏：不抬的话拍到的是桌面上别家应用，
    # 颜色数照样过阈值（Java 那边就这么假绿过一次），那张不能当证据
    for d in dialogs:
        d.raise_()
        d.activateWindow()
    win.raise_()
    pump(0.4)
    full = screen.grabWindow(0)
    full.save(os.path.join(OUT, "screen.png"))
    log("整屏 %dx%d、抽样 %d 色（抬过窗口之后拍的）-> %s"
        % (full.width(), full.height(), distinct_colors(full),
           os.path.join(OUT, "screen.png")))
    # 每个窗口再各自渲染一张：QWidget.grab() 走控件自己那条路，与谁压在上面无关。
    # 按各自的屏幕位置拼起来，这一张才是"两个进度框同屏"的证据
    parts = [(win, "主窗口")] + [(d, "进度框%d" % (i + 1)) for i, d in enumerate(dialogs)]
    if samp is not None and samp.isVisible():
        parts.append((samp, "第三页示例清单"))
    shots = []
    for wdg, label in parts:
        pix = wdg.grab()
        n_ink = ink_pixels(pix)
        path = os.path.join(OUT, "window_%s.png" % label)
        pix.save(path)
        log("%s：位置=(%d,%d) 尺寸=%dx%d 渲染 %dx%d、与背景不同的抽样点 %d 个 -> %s"
            % (label, wdg.x(), wdg.y(), wdg.width(), wdg.height(),
               pix.width(), pix.height(), n_ink, path))
        shots.append((wdg, label, pix))
        check("%s 渲染出来不是空帧（有字有条）" % label, not pix.isNull() and n_ink > 200,
              (pix.isNull(), n_ink))
    right = max(w.x() + w.width() for w, _l, _p in shots)
    bottom = max(w.y() + w.height() for w, _l, _p in shots)
    canvas = QtGui.QPixmap(max(right, 640), max(bottom, 480))
    canvas.fill(QtGui.QColor("#dcdcdc"))
    painter = QtGui.QPainter(canvas)
    for wdg, _label, pix in shots:
        painter.drawPixmap(wdg.x(), wdg.y(), pix)
    painter.end()
    comp = os.path.join(OUT, "composite.png")
    canvas.save(comp)
    log("拼图（各窗口按自己的屏幕位置贴在一起，%dx%d）-> %s"
        % (canvas.width(), canvas.height(), comp))
    check("两颗进度框的位置不重叠（重叠=后面那颗的「取消」点不到）",
          len(dialogs) == 2 and not dialogs[0].geometry().intersects(
              dialogs[1].geometry()),
          [d.geometry().getRect() for d in dialogs])

    if samp is not None:
        samp.close()
    # 收尾：两条任务要各自跑完并还位，不留残留
    done = wait_until(lambda: not win._tasks, 60)
    log("收尾后在跑任务数=%d，进度条=%d%%" % (len(win._tasks), win.progress.value()))
    check("两条任务都收得干净（资源位还回去、进度条归零）",
          done and win.progress.value() == 0,
          (done, len(win._tasks), win.progress.value()))
    written = sum(len([x for x in names if x.endswith(".xlsx")])
                  for _r, _d, names in os.walk(gen_root))
    check("第二页那条真写了盘（%d 个交付件）" % written, written > 0, written)
    log("解析进列表的表数=%d" % len(win.tables))
    check("第一页那条真解析出表", len(win.tables) == 8, len(win.tables))

    win.close()
    pump(0.2)
    log("FAIL 汇总：%s" % (_fails or "无"))
    return 1 if _fails else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001 - 探针要落到报告文件里，别只留个 traceback
        import traceback
        log("ERROR %r" % (exc,))
        log(traceback.format_exc())
        sys.exit(2)
