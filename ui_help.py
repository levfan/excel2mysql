"""信息类对话框：使用说明（左目录 + 右正文）与「关于」（版本/提交/构建日期/作者/仓库）。

单独成模块是因为主窗口已经够挤，而这些对话框只服务于"看"，不参与任何导入逻辑。
"""
from PySide6.QtCore import Qt, QTimer, QUrl
from PySide6.QtGui import QFont, QDesktopServices, QPixmap
from PySide6.QtWidgets import (
    QDialog, QHBoxLayout, QVBoxLayout, QListWidget, QListWidgetItem,
    QTextBrowser, QPushButton, QLabel, QFrame, QGridLayout, QApplication,
)

from appinfo import APP_NAME, REPO_URL, build_info, commit_display, full_commit
from assetpath import resource_path
from help_doc import SECTIONS

# 「关于」里那一行作者。Java 侧镜像在 gui/AboutDialog.java 的 AUTHOR 常量，
# 两条线的关于框由对撞键 dlg.about.rows 逐行比，改一处不改另一处就会红
AUTHOR = "刘帆"

# 「关于」里既不铺说明文字，也不挂悬浮提示：提交号与构建日期的从哪来、怎么取走，
# 全部归《使用说明》（右上角「?」或 F1），界面只留四行结果。
# 「复制全部」那颗按钮的三种状态：两栈各写一份就会漂移，由对撞键逐字比
COPY_LABEL = "复制全部"
COPIED_LABEL = "已复制"
CLIP_FAIL_LABEL = "剪贴板打不开"

# 作者那一格本身就是仓库链接。回执那三档字两栈各写一份，由对撞键 dlg.about.link.*
# 逐字比；地址只有一个来源（appinfo.REPO_URL）
REPO_OPENED_LABEL = "已在浏览器打开"
REPO_COPIED_LABEL = "打不开浏览器，地址已复制"
REPO_FAIL_LABEL = "打不开浏览器，也没能写进剪贴板"
# 名字右侧那枚很小的外链箭头：图不靠系统主题，文件跟着包走，
# 生成办法见 java/tools/export_repo_icons.py（画不出汉字那类教训同样适用于图形）
LINK_ARROW_FILE = "assets/external_link.png"
LINK_ARROW_PX = 12

# Qt 的 Rich Text 只支持 CSS 的一个子集，这里只写 QTextBrowser 认得的属性
# （写多了不报错，但静默不生效，所以行高用段落间距撑，不用 line-height）
STYLE = """
body { font-family: "Microsoft YaHei", "Segoe UI", sans-serif; font-size: 10.5pt;
       color: #24292f; }
h2 { font-size: 14pt; color: #0b5cad; margin-top: 18px; margin-bottom: 8px;
     border-bottom: 2px solid #dfe3e8; }
h3 { font-size: 11.5pt; color: #1a7f37; margin-top: 16px; margin-bottom: 6px; }
p { margin-top: 6px; margin-bottom: 6px; }
ul, ol { margin-top: 4px; margin-bottom: 6px; }
li { margin-bottom: 3px; }
code { background-color: #f0f2f4; color: #b3261e; font-family: Consolas, monospace; }
pre { background-color: #f6f8fa; border: 1px solid #d8dee4; padding: 8px;
      font-family: Consolas, monospace; font-size: 9.5pt; }
table.t { border-collapse: collapse; margin-top: 8px; margin-bottom: 8px; }
table.t th { background-color: #eef2f6; border: 1px solid #c9d1d9; padding: 4px 8px;
             text-align: left; }
table.t td { border: 1px solid #c9d1d9; padding: 4px 8px; }
p.tip { background-color: #eef7ee; border-left: 4px solid #1a7f37; padding: 6px 10px; }
p.warn { background-color: #fdf3f2; border-left: 4px solid #c62828; padding: 6px 10px; }
"""


def fit_client(win, w: int, h: int) -> None:
    """把窗口摆成"客户区 w x h"，装不下就缩进当前屏幕的可用区并居中。

    Qt 的 resize 说的就是客户区；这里补两道保险：窗口别大过当前可用区（老机器
    1024x768 上按钮不会被任务栏吃掉），也别摆到屏幕外去。Swing 那条线用
    `Windows.clientSize` 做同一件事——两边的 setSize 含外框，得钉在内容面板上才对等。
    """
    screen = QApplication.primaryScreen()
    geo = screen.availableGeometry() if screen is not None else None
    win.setProperty("e2mWant", "%dx%d" % (w, h))   # 配置值留给取证用
    cw, ch = w, h
    if geo is not None:
        # 32/48 是留给边框与标题栏的余量：客户区再加上下左右那条框仍在可用区内才算放得下
        cw = min(cw, max(320, geo.width() - 32))
        ch = min(ch, max(200, geo.height() - 48))
    win.resize(cw, ch)
    if geo is not None:
        win.move(geo.x() + (geo.width() - cw) // 2,
                 geo.y() + (geo.height() - ch) // 2)


def build_html() -> str:
    """把 SECTIONS 拼成一份带锚点的长文档，供目录跳转，也能整篇往下滚。"""
    parts = [f'<a name="{anchor}"></a>{body}' for anchor, _title, body in SECTIONS]
    return ("<html><head><style>" + STYLE + "</style></head><body>"
            + "<hr>".join(parts) + "</body></html>")


class HelpDialog(QDialog):
    """使用说明窗口：点目录跳到对应章节，正文可整篇滚动阅读。"""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("使用说明")
        fit_client(self, 960, 700)

        self.toc = QListWidget()
        self.toc.setFixedWidth(170)
        self.toc.setFocusPolicy(Qt.NoFocus)
        self.toc.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        for anchor, title, _body in SECTIONS:
            item = QListWidgetItem(title)
            item.setData(Qt.UserRole, anchor)
            self.toc.addItem(item)

        self.browser = QTextBrowser()
        self.browser.setFrameShape(QFrame.NoFrame)
        self.browser.setHtml(build_html())

        self.btn_close = QPushButton("关闭")
        self.btn_close.clicked.connect(self.accept)
        hint = QLabel("随时按 F1 或标题栏下方的「?」打开本页")
        hint.setStyleSheet("color: #666;")

        left = QVBoxLayout()
        left.setContentsMargins(0, 0, 0, 0)
        left.addWidget(QLabel("<b>目录</b>"))
        left.addWidget(self.toc, 1)

        body = QHBoxLayout()
        body.addLayout(left)
        body.addWidget(self.browser, 1)

        row = QHBoxLayout()
        row.addWidget(hint)
        row.addStretch(1)
        row.addWidget(self.btn_close)

        lay = QVBoxLayout(self)
        lay.addLayout(body, 1)
        lay.addLayout(row)

        self.toc.currentRowChanged.connect(self._on_pick)
        self.toc.setCurrentRow(0)

    def _on_pick(self, row):
        item = self.toc.item(row)
        if item is not None:
            self.browser.scrollToAnchor(item.data(Qt.UserRole))

    def keyPressEvent(self, e):
        if e.key() == Qt.Key_Escape:        # Esc 关窗，和「关闭」等价
            self.reject()
            return
        super().keyPressEvent(e)


def open_in_browser(url: str) -> bool:
    """把地址交给系统默认浏览器；交不出去就返回 False，不抛异常。

    QDesktopServices.openUrl 自己会报成败：没有桌面会话、没有协议关联、
    被策略拦下都是 False，所以这里不用猜，也不用自己起进程。
    """
    try:
        return bool(QDesktopServices.openUrl(QUrl(url)))
    except Exception:
        return False


def _bundled_pixmap(rel: str, px: int):
    """读随包的小图并缩到 px；文件没进包时返回 None，让那一格留空而不是画个叉。"""
    pm = QPixmap(resource_path(rel))
    if pm.isNull():
        return None
    return pm.scaled(px, px, Qt.KeepAspectRatio, Qt.SmoothTransformation)


class LinkCell(QFrame):
    """「关于」里作者那一格：名字 + 一枚很小的外链箭头，整格可点开仓库。

    单独占一行太突兀，而这一格本来就是"这份东西是谁做的"——名字挂在哪个仓库上，
    点名字就过去了。地址取走的路径两条：「复制全部」多带一行、本机打不开浏览器时
    直接进剪贴板；不挂悬浮提示，那套说明归《使用说明》。
    点了必须有可见回执（名字那一格临时换成回执那一句，两秒后复原）——静默不响应等于没这个链接。
    """

    def __init__(self, text, url, opener=None, parent=None):
        super().__init__(parent)
        self.base_text = text
        self.url = url
        self.opener = opener or open_in_browser
        self.last_status = ""
        lay = QHBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(4)
        self.text = QLabel(text)
        self.arrow = QLabel()
        pm = _bundled_pixmap(LINK_ARROW_FILE, LINK_ARROW_PX)
        if pm is None:
            self.arrow_missing = True
        else:
            self.arrow.setPixmap(pm)
            self.arrow_missing = False
        lay.addWidget(self.text)
        lay.addWidget(self.arrow)
        lay.addStretch(1)      # 箭头紧跟名字，不顶到格子最右边
        self.setCursor(Qt.PointingHandCursor)
        self.setFocusPolicy(Qt.TabFocus)   # Tab 走到这一格，回车与空格等价于单击

    def mousePressEvent(self, e):
        if e.button() == Qt.LeftButton:
            self.activate()
        super().mousePressEvent(e)

    def keyPressEvent(self, e):
        if e.key() in (Qt.Key_Return, Qt.Key_Enter, Qt.Key_Space):
            self.activate()
            return
        super().keyPressEvent(e)

    def enterEvent(self, e):
        # 悬停只把名字换成链接蓝：整格铺底色在这一档字号下会把两个字糊成一团
        self.text.setStyleSheet("color: #0b5cad;")

    def leaveEvent(self, e):
        self.text.setStyleSheet("")

    def activate(self):
        """单击那一下：先交给浏览器，交不出去就把地址放进剪贴板，回执只说实话。"""
        if self.opener(self.url):
            self.last_status = "opened"
            self._flash(REPO_OPENED_LABEL)
        else:
            self.last_status = "copied" if self._copy_url() else "failed"
            self._flash(REPO_COPIED_LABEL if self.last_status == "copied"
                        else REPO_FAIL_LABEL)
        return self.last_status

    def _copy_url(self):
        """把地址写进剪贴板并回读确认：setText 在没有桌面会话时会静默落空。"""
        try:
            cb = QApplication.clipboard()
            cb.setText(self.url)
            return cb.text() == self.url
        except Exception:
            return False

    def _flash(self, label):
        self.text.setText(label)
        QTimer.singleShot(2000, lambda: self.text.setText(self.base_text))

    def cell_text(self):
        """自检与对撞用：这一格此刻显示的字。"""
        return self.text.text()


class AboutDialog(QDialog):
    """「关于」小框：版本 / 提交 / 构建日期 / 作者四行，值全部来自 appinfo。

    提交号与构建日期是打包时烧进产物的（exe 所在机器没有 .git），
    开发态则现场问 git；取不到就显示"开发态（未打包）"，不会编造。
    这四行都不挂悬浮提示：来源与取用方式写在《使用说明》里，框里只给结果。
    作者那一格是仓库链接，点名字开仓库。
    """

    def __init__(self, parent=None, repo_opener=None):
        super().__init__(parent)
        self.setWindowTitle("关于")
        self.setModal(True)
        fit_client(self, 380, 230)
        info = build_info()

        self.head_label = QLabel(APP_NAME)
        title = self.head_label
        f = QFont()
        f.setBold(True)
        f.setPointSize(f.pointSize() + 2)
        title.setFont(f)

        # 标题与四行之间垫一条细线：原来两档字号之间只靠空白分开，扫一眼分不出
        # 哪是产品名、哪是这台机器上这一份的构建信息
        rule = QFrame()
        rule.setFrameShape(QFrame.HLine)
        rule.setFrameShadow(QFrame.Sunken)

        grid = QGridLayout()
        grid.setHorizontalSpacing(14)
        grid.setVerticalSpacing(7)
        self.rows = {}
        for i, (key, label, value) in enumerate((
                ("version", "版本", f"v{info['version']}"),
                ("commit", "提交", commit_display(info)),
                ("built", "构建日期", info["built_at"]))):
            head = QLabel(label)
            head.setStyleSheet("color: #666;")
            body = QLabel(value)
            body.setTextInteractionFlags(Qt.TextSelectableByMouse)   # 提交号要能选中复制
            grid.addWidget(head, i, 0)
            grid.addWidget(body, i, 1)
            self.rows[key] = body
        # 作者那一格本身就是仓库链接：单独占一行太突兀，而"谁做的"与"在哪座仓库上"
        # 本来就是同一件事。行名照旧进 grid 第 0 列，对撞键 dlg.about.rows 仍数到四行
        author_head = QLabel("作者")
        author_head.setStyleSheet("color: #666;")
        self.author_link = LinkCell(AUTHOR, REPO_URL, repo_opener, self)
        grid.addWidget(author_head, 3, 0)
        grid.addWidget(self.author_link, 3, 1)
        grid.setColumnStretch(1, 1)

        self.btn_copy = QPushButton(COPY_LABEL)
        self.btn_copy.setToolTip("把上面五行一次放进剪贴板（提交号给 40 位完整值、仓库给完整地址）")
        self.btn_copy.clicked.connect(self.on_copy_all)
        self.btn_close = QPushButton("关闭")
        self.btn_close.clicked.connect(self.accept)
        row = QHBoxLayout()
        row.addStretch(1)
        row.addWidget(self.btn_copy)
        row.addWidget(self.btn_close)

        lay = QVBoxLayout(self)
        lay.addWidget(title)
        lay.addWidget(rule)
        lay.addLayout(grid)
        lay.addStretch(1)
        lay.addLayout(row)

    def copy_text(self):
        """「复制全部」放进剪贴板的那一份文本：一行一项，提交号给全号、仓库给完整地址。

        正面那一格仍是短号（一行放得下、也够人眼看），贴出去的要能一路粘进 git 里查，
        所以这一份给 40 位；老产物没烧全号时退回短号，不写一个空值出去。
        作者那一格只显示署名，完整仓库地址在这一份里给全，抄出去就能直接贴。
        """
        info = build_info()
        return "\n".join((
            APP_NAME,
            "版本：v%s" % info["version"],
            "提交：%s" % commit_display(dict(info, commit=full_commit(info))),
            "构建日期：%s" % info["built_at"],
            "作者：%s" % AUTHOR,
            "仓库：%s" % REPO_URL,
        ))

    def on_copy_all(self):
        text = self.copy_text()
        ok = False
        try:
            cb = QApplication.clipboard()
            cb.setText(text)
            # 写了不等于放进去了：没有桌面会话、或被别的程序占着剪贴板时，
            # setText 静默落空。回读一次，回执只说实话
            ok = cb.text() == text
        except Exception:
            ok = False
        self.btn_copy.setText(COPIED_LABEL if ok else CLIP_FAIL_LABEL)
        QTimer.singleShot(2000, lambda: self.btn_copy.setText(COPY_LABEL))

    def heading_text(self):
        """自检与对撞用：框面上那一行产品名。名字只有一个来源，两栈各抄一份就会漂。"""
        return self.head_label.text()

    def copy_labels(self):
        """自检与对撞用：「复制全部」这颗按钮的两种文字（点了要变、两秒后复原）。"""
        return self.btn_copy.text() + "|" + COPIED_LABEL + "|" + CLIP_FAIL_LABEL

    def link_text(self):
        """自检与对撞用：作者那一格此刻显示的字（平时是署名，点完那两秒是回执）。"""
        return self.author_link.cell_text()

    def link_url(self):
        """自检与对撞用：那一格点出去、也抄出去的那串地址。"""
        return self.author_link.url

    def link_states(self):
        """自检与对撞用：那一格开场那一句加三种回执，逐字比，两栈不许各写一份。"""
        return "|".join((AUTHOR, REPO_OPENED_LABEL, REPO_COPIED_LABEL, REPO_FAIL_LABEL))

    def link_arrow_missing(self):
        """自检用：那枚小箭头有没有真读进来（缺图不算失败，但要能被发现）。"""
        return LINK_ARROW_FILE if self.author_link.arrow_missing else ""

    def face_tips(self):
        """自检与对撞用：框面上挂着的悬浮提示拼在一起，这一格设计成空串。

        提交号与构建日期的来源说明原先就挂在这几格的提示里，现在那段话只写在
        《使用说明》——框里再冒出提示文字就是退回老样子，由这条与「正面不许铺说明」
        那条配成一对。两颗按钮的提示不算在内（那是操作说明，不是来源介绍）。
        """
        found = []
        for w in self.findChildren(QLabel) + self.findChildren(QFrame):
            tip = str(w.toolTip() or "").strip()
            if tip:
                found.append(tip)
        return "|".join(found)

    def keyPressEvent(self, e):
        if e.key() == Qt.Key_Escape:
            self.reject()
            return
        super().keyPressEvent(e)
