"""把 Python 那条线的界面渲染成 PNG，供与 Java Swing 侧（java/verify 的 UiShot）逐屏比对。

为什么要渲染而不是截屏：锁屏或无显示设备上截屏只能拿到锁屏图；
Qt 的 QWidget.grab() 走离屏平台，画的就是线上那套控件。

为什么要桩掉消息框：结果弹窗的 exec() 在没有显示设备时会永久阻塞，
而这一轮要拍的是界面布局，不是弹窗内容。

用法（仓库根目录，用带 PySide6 的那个解释器）：
    .venv/Scripts/python.exe java/tools/py_ui_shot.py [输出目录] [夹具目录]
屏序与 UiShot 一一对应：01_empty 02_excel_loaded 03_sql_empty 04_sql_loaded
05_sql_prefixed 06_structure 07_ddl 08_help 09_about 10_progress
11_script_ddl 12_samples
"""
import os
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(tempfile.gettempdir(), "e2m-shots", "py")
FIX = sys.argv[2] if len(sys.argv) > 2 else os.path.join(REPO, "java", "verify", "fixtures")

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, REPO)

from PySide6.QtWidgets import QApplication  # noqa: E402
import main as m  # noqa: E402

app = QApplication(sys.argv)
# 界面字体要用产品那一份（随包思源黑体）：这一步不做，拍出来的中文全是空方框，
# 那张图就当不了"逐屏看过"的证据——Java 侧的 UiShot 走 UiScale.apply() 拿的是同一支字
m.load_app_font(app)
m.MODAL_PROGRESS = False
for _name in ("information", "warning", "critical", "question"):
    setattr(m.QMessageBox, _name, staticmethod(lambda *a, **k: 0))
# 添加文件后的「添加结果」走的是自绘 PopupBox，不归 QMessageBox 那组桩管；
# 不压掉它，exec() 会在离屏环境里永久等一个没人点的确定键
m.popup_box = lambda *a, **k: 0
os.chdir(REPO)


def snap(widget, name):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name + ".png")
    pm = widget.grab()
    pm.save(path)
    print("saved", path, pm.width(), "x", pm.height())


files = sorted(os.path.join(FIX, f) for f in os.listdir(FIX)
               if f.lower().endswith((".xlsx", ".xls", ".zip")))
win = m.MainWindow()
win.resize(1100, 780)
win.show()
app.processEvents()
snap(win, "01_empty")

win._add_files(files)
app.processEvents()
snap(win, "02_excel_loaded")

win.main_tabs.setCurrentIndex(1)
app.processEvents()
snap(win, "03_sql_empty")

sql_file = os.path.join(OUT, "shot_a.sql")
with open(sql_file, "w", encoding="utf-8") as fh:
    fh.write("CREATE TABLE t_order (id INT);\nINSERT INTO `t_order` VALUES (1);\n")
win.ed_sql_prefix.setText("ods_")
sink = m.ProgressSink(on_log=lambda t: m.append_log(win.sql_log, t),
                      on_log_error=lambda t: m.append_log(win.sql_log, t, error=True))
res = m.sql_load_task([sql_file], [], sink)
win.sql_files.extend(res["loaded"])
win._refresh_sql_table()
app.processEvents()
snap(win, "04_sql_loaded")

win.on_sql_apply_prefix()
app.processEvents()
snap(win, "05_sql_prefixed")

win.main_tabs.setCurrentIndex(0)
app.processEvents()

dlg = m.StructureDialog(win.tables, win)
dlg.show()
app.processEvents()
snap(dlg, "06_structure")
dlg.close()

ddl = m.DDLDialog(win.tables[:2], False, win)
ddl.show()
app.processEvents()
snap(ddl, "07_ddl")
ddl.close()

from ui_help import HelpDialog, AboutDialog  # noqa: E402

help_dlg = HelpDialog(win)
help_dlg.show()
app.processEvents()
snap(help_dlg, "08_help")
help_dlg.close()

about = AboutDialog(win)
about.show()
app.processEvents()
snap(about, "09_about")
about.close()

pd = m.ProgressDialog("正在解析文件…", win)
pd.set_progress(5, 12, "订单表.xlsx")
pd.show()
app.processEvents()
snap(pd, "10_progress")
pd.close()

# 脚本页那扇 DDL 预览与【SQL 示例】窗口：这两屏的可读性就是本轮改的东西，
# 只靠断言判不出"看着清楚没有"，必须渲染出来逐张看。夹具与 UiShot.java 逐字同一条。
_flat = ("CREATE TABLE `ods_t_order` (`id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '编号',"
         " `amount` DECIMAL(20,4) DEFAULT NULL COMMENT '金额',"
         " PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;")
sddl = m.SqlScriptDdlDialog([("ods_t_order", _flat)], win)
sddl.show()
app.processEvents()
snap(sddl, "11_script_ddl")
sddl.close()

samples = m.SqlSamplesDialog(win.sql_run_input)
samples.show()
app.processEvents()
snap(samples, "12_samples")
samples.close()

os.remove(sql_file)
print("输出目录", OUT, "夹具", [os.path.basename(f) for f in files])
