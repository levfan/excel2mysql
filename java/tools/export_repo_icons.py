# -*- coding: utf-8 -*-
"""生成「关于」框里作者那一格用的小图：外链箭头。

两条线共用同一份文件，住在 assets/ 下：Python 走 resource_path，
Java 由 pom 把 assets 里这张抄进 jar。所以这里只认 SVG 源、只写 PNG，
不参与任何运行时逻辑。

为什么烧成 PNG 而不是运行时画：
- Java 8 的 ImageIO 不认 SVG，现场随包的 JRE 也没有 SVG 解码器；
- 箭头如果用文字画（U+2197），就又回到"这台机器上有没有这个字形"那件事上，
  而这一轮刚刚把界面字体改成随包那一份，就是为了不再赌系统。

箭头是自己按 24 格网格画的通用外链符号（左下一只缺了右上角的方框，右上一支穿出去的箭头），
不带任何商标。源 SVG 抄在本文件里，重跑一次得到同一张图，不需要联网。
"""
import os

from PySide6.QtGui import QImage

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.normpath(os.path.join(HERE, "..", "..", "assets"))
SIDE = 96          # 出图边长：界面上只用到十几像素，源头出大一点缩放才不糊
INK = "#24292f"    # 与正文墨色同一档，浅色底上读得出

# 24 格网格、2 格粗的描边，与界面里那档小图标观感一致
EXTERNAL_LINK_SVG = (
    '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 24 24" '
    'fill="none" stroke="%s" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
    '<path d="M13 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7"/>'
    '<path d="M15 4h5v5"/>'
    '<path d="M10 14 20 4"/></svg>' % (SIDE, SIDE, INK)
)

OUTS = (("external_link.png", EXTERNAL_LINK_SVG),)


def render(svg, path):
    """把一段 SVG 画成 SIDE 方图存成 PNG；画不出或画成全透明就报错，不写废图。"""
    img = QImage(SIDE, SIDE, QImage.Format_ARGB32)
    img.fill(0)
    if not img.loadFromData(svg.encode("utf-8"), "svg"):
        raise SystemExit("QImage 画不出 SVG，写不出 %s（这台机器的 Qt 没有 svg 插件？）" % path)
    img.save(path, "PNG")
    back = QImage(path)
    if back.isNull() or back.width() != SIDE:
        raise SystemExit("%s 回读失败或尺寸不对：%s" % (path, back.size()))
    inked = sum(1 for y in range(back.height()) for x in range(back.width())
                if back.pixelColor(x, y).alpha() > 0)
    if inked == 0:
        raise SystemExit("%s 画出来是全透明，判失败" % path)
    return back.width(), back.height(), inked


def main():
    for name, svg in OUTS:
        out = os.path.join(ASSETS, name)
        w, h, inked = render(svg, out)
        print("写出 %s %dx%d，着色像素 %d，%d 字节"
              % (name, w, h, inked, os.path.getsize(out)))


if __name__ == "__main__":
    main()
