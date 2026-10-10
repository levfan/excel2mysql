# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller 打包配置：Windows 7 命令行版（onedir + 控制台）。

用法（必须用 .venv38 里的 PyInstaller 5.x，GUI 那套 6.x 打的包跑不了 Win7）：
  .venv38\\Scripts\\pyinstaller --noconfirm excel2mysql_cli38.spec
产物：
  dist\\excel2mysql-cli\\excel2mysql-cli.exe  +  _internal\\

排除/收集口径与 GUI 包共用 _pack_common.py，改一处两边同时生效；
本包另外排掉 Qt 与 GUI 模块（CLI_EXCLUDES），build_exe38.py 还会按文件名复查产物。
"""
import os
import sys

sys.path.insert(0, os.path.abspath(SPECPATH))
from _pack_common import CLI_EXCLUDES, HIDDEN_IMPORTS

a = Analysis(
    ["cli.py"],
    pathex=["."],
    binaries=[],
    # 默认连接配置 + 面向使用方的说明，都随包放到 exe 同级：拷走整个目录就能直接用
    datas=[("assets/conn.ini", "."), ("assets/README.txt", ".")],
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
    name="excel2mysql-cli",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,               # CLI：双击要能看到日志，不是静默闪退
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
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name="excel2mysql-cli",
)
