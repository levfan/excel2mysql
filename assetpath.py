"""随包资源（图标、字体、许可文本）的取路径口。

单独成模块是因为读这些文件的不止主窗口：`ui_help` 里「关于」那一行要画随包的小图，
`main` 要装随包字体与窗口图标。判据只有一条——打包后资源在 PyInstaller 的解包目录里，
开发态在项目根目录里——写两份迟早会漂，而漂了的那种失败是"包里没有图，界面空一块"，
开发态怎么跑都看不出来。
"""
import os
import sys


def resource_path(rel: str) -> str:
    """打包后资源在 PyInstaller 解包目录，开发态在项目根目录。"""
    base = getattr(sys, "_MEIPASS", None)
    if base is None:
        base = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(base, rel)
