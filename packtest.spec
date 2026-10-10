# -*- mode: python ; coding: utf-8 -*-
"""把 pack_test.py 冻结成控制台 exe，用于验证"打包后每个用例都能跑通"。

与 excel2mysql.spec 共用 _pack_common.py 的排除/收集口径，因此这里能跑通
就等于应用所需的库与资源在冻结环境里都齐备。

用法：
  pyinstaller --noconfirm --distpath dist_packtest --workpath build_packtest packtest.spec
  dist_packtest\\packtest\\packtest.exe
"""
import os
import sys

sys.path.insert(0, os.path.abspath(SPECPATH))
from _pack_common import ALL_EXCLUDES, HIDDEN_IMPORTS, drop_unneeded_qt

a = Analysis(
    ["pack_test.py"],
    pathex=["."],
    binaries=[],
    datas=[("assets/app_icon.ico", "assets")],
    hiddenimports=HIDDEN_IMPORTS,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=ALL_EXCLUDES,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="packtest",
    debug=False,
    strip=False,
    upx=False,
    console=True,           # 用例集需要看输出
    icon="assets/app_icon.ico",
)

coll = COLLECT(
    exe,
    drop_unneeded_qt(a.binaries),
    a.datas,
    strip=False,
    upx=False,
    name="packtest",
)
