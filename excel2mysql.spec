# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller 打包配置：onedir + 无控制台的 Windows GUI。

用法：pyinstaller --noconfirm excel2mysql.spec
排除/收集口径与功能用例集共用 _pack_common.py，改一处即两边同时生效。
"""
import os
import sys

sys.path.insert(0, os.path.abspath(SPECPATH))
from _pack_common import ALL_EXCLUDES, HIDDEN_IMPORTS, drop_unneeded_qt

a = Analysis(
    ["main.py"],
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
    name="excel2mysql",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,          # GUI：双击不弹黑窗
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon="assets/app_icon.ico",
    uac_admin=False,
)

coll = COLLECT(
    exe,
    drop_unneeded_qt(a.binaries),
    a.datas,
    strip=False,
    upx=False,
    name="excel2mysql",
)
