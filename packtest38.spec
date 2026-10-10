# -*- mode: python ; coding: utf-8 -*-
"""把 pack_test38.py 冻结成控制台 exe：验证"Win7 包里的库齐不齐、用例跑不跑得通"。

与 excel2mysql_cli38.spec 共用 _pack_common.py 的 CLI_EXCLUDES / HIDDEN_IMPORTS，
因此这里能跑通就等于命令行包所需的依赖在冻结环境里都齐备（口径不漂移）。

用法（同样必须用 .venv38 的 PyInstaller 5.x）：
  .venv38\\Scripts\\pyinstaller --noconfirm --distpath dist_packtest38 ^
      --workpath build_packtest38 packtest38.spec
运行：
  dist_packtest38\\packtest38\\packtest38.exe
"""
import os
import sys

sys.path.insert(0, os.path.abspath(SPECPATH))
from PyInstaller.utils.hooks import copy_metadata

from _pack_common import CLI_EXCLUDES, HIDDEN_IMPORTS

# 把发行版元数据（*.dist-info）一起收进包内，冻结态的 importlib.metadata 才查得到版本。
# 不这么做，"旧栈装的到底是 requirements38 钉的那几个版本"这组用例在 exe 里只能 SKIP，
# 而这条恰恰是 Win7 线最需要现场自证的（GUI 包不需要，它不查元数据）。
_DIST_INFOS = ("numpy", "pandas", "PyMySQL", "openpyxl", "xlrd", "pypinyin",
               "tzdata", "pyinstaller")
_meta_datas = []
for _pkg in _DIST_INFOS:
    try:
        _meta_datas += copy_metadata(_pkg)
    except Exception as e:                       # 某个包没装就跳过，不让 spec 直接炸
        print("[packtest38.spec] 收不到 %s 的元数据：%s" % (_pkg, e))

a = Analysis(
    ["pack_test38.py"],
    pathex=["."],
    binaries=[],
    datas=_meta_datas,
    hiddenimports=HIDDEN_IMPORTS,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=CLI_EXCLUDES,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="packtest38",
    debug=False,
    strip=False,
    upx=False,
    console=True,               # 用例集需要看输出
    icon="assets/app_icon.ico",
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name="packtest38",
)
