"""打包口径的唯一来源。

excel2mysql.spec（应用）与 packtest.spec（功能用例集）共用同一份排除/收集配置，
避免"用例集能跑通但应用缺库"这种口径漂移。
"""

# 代码只用到 QtCore/QtGui/QtWidgets；完整 PySide6 会带进 WebEngine/Pdf/Addons
# 等数百 MB 模块，必须显式排除，否则产物体积失控。
QT_EXCLUDES = [
    "PySide6.QtWebEngineCore",
    "PySide6.QtWebEngineWidgets",
    "PySide6.QtWebEngineQuick",
    "PySide6.QtWebChannel",
    "PySide6.QtWebSockets",
    "PySide6.QtPdf",
    "PySide6.QtPdfWidgets",
    "PySide6.QtQuick",
    "PySide6.QtQuick3D",
    "PySide6.QtQuickWidgets",
    "PySide6.QtQml",
    "PySide6.QtQmlModels",
    "PySide6.QtQmlQuickControls2",
    "PySide6.Qt3DCore",
    "PySide6.Qt3DRender",
    "PySide6.QtMultimedia",
    "PySide6.QtMultimediaWidgets",
    "PySide6.QtBluetooth",
    "PySide6.QtPositioning",
    "PySide6.QtLocation",
    "PySide6.QtNfc",
    "PySide6.QtSerialPort",
    "PySide6.QtSensors",
    "PySide6.QtCharts",
    "PySide6.QtDataVisualization",
    "PySide6.QtGraphs",
    "PySide6.QtRemoteObjects",
    "PySide6.QtTest",
    "PySide6.QtVirtualKeyboard",
    "PySide6.QtScxml",
]

OTHER_EXCLUDES = [
    "tkinter",
    "matplotlib",
    "scipy",
    "PyQt5",
    "PyQt6",
    "IPython",
    "pytest",
    "PIL",        # 应用不读图，是打包工具链顺带装的
]

# 运行时才按名字 import、静态分析容易漏的模块
HIDDEN_IMPORTS = [
    "et_xmlfile",       # openpyxl 依赖
    "xlrd",             # pandas 读 .xls 时按名字动态 import，静态分析抓不到
    "tzdata",           # pandas 在 Windows 上取时区数据
    "pypinyin",         # 词典 JSON 由包自带的 pyinstaller hook 收集
    "openpyxl",
    "pymysql",
]

# PySide6 hook 会连带收集 QML/Quick/Pdf/VirtualKeyboard 这套 QtWidgets 用不到的
# DLL 及依赖它们的插件（约 20MB），在 COLLECT 前按文件名过滤掉。
# opengl32sw.dll 保留：远程桌面/无显卡驱动的虚拟机上缺了它 Qt 起不来。
UNNEEDED_QT_BINARIES = (
    "qt6quick", "qt6qml", "qt6qmlmeta", "qt6qmlmodels", "qt6qmlworkerscript",
    "qt6pdf", "qt6virtualkeyboard",
    "qmodernwindowsstyle",
    "qpdf",                     # imageformats 插件，依赖已被排除的 Qt6Pdf.dll
    "qtvirtualkeyboardplugin",  # platforminputcontexts 插件，同上
)


def drop_unneeded_qt(binaries):
    """剔除多余的 Qt 二进制，返回过滤后的列表。"""
    kept, dropped = [], []
    for item in binaries:
        name = item[0].replace("\\", "/").rsplit("/", 1)[-1].lower()
        (dropped if name.startswith(UNNEEDED_QT_BINARIES) else kept).append(item)
    print("[pack_common] 剔除多余 Qt 二进制:",
          ", ".join(sorted({i[0].replace('\\', '/').rsplit('/', 1)[-1] for i in dropped}))
          or "无")
    return kept


ALL_EXCLUDES = QT_EXCLUDES + OTHER_EXCLUDES

# ---------------------------------------------------------------------------
# Win7 命令行包（excel2mysql_cli38.spec）的额外排除
# ---------------------------------------------------------------------------
# CLI 与 GUI 共用 core/，但 Win7 包里绝不允许出现 Qt 与 GUI 侧模块：
# 一旦有人从 cli.py 里 import main（或直接 import PySide6），PyInstaller 会把
# 整个 Qt 拖进旧包 —— 产物在 Win7 上照样起不来（Qt 6 只要 Win10 1809+），
# 体积还会从十几 MB 涨到上百 MB，而这两件事都不会让打包本身报错。
# 所以这里显式排除，build_exe38.py 再按文件名复查一遍落地的产物。
GUI_MODULES = ["PySide6", "PySide2", "main", "ui_help", "help_doc"]

CLI_EXCLUDES = ALL_EXCLUDES + GUI_MODULES

# 随包的用户向文档（README.md）该有的小节与篇幅区间。两处共用同一份：
# 打包闸门拿它核产物里那一份（build_exe.py 的随包文档步骤），
# 功能用例集拿它核仓库里这一份（pack_test38.py 的 W29c），改一处两边同时生效。
# 上限是防构建与测试内容又一点点长回用户向文档，不是防写得多。
README_SECTIONS = ("选哪个包", "上手", "连接信息", "会踩的坑", "退出码",
                   "Win7 上起不来", "再往深", "许可")
README_LEN_RANGE = (2000, 3200)
