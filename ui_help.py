"""信息类对话框：使用说明（左目录 + 右正文）与「关于」（版本/提交/构建日期/作者/仓库）。

单独成模块是因为主窗口已经够挤，而这些对话框只服务于"看"，不参与任何导入逻辑。
"""
import os

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

# 「关于」底下不铺说明文字：提交号与构建日期的来源改挂在「提交」「构建日期」
# 两行的悬浮提示上，界面只留四行结果。Java 侧镜像在 AboutDialog.java 的同名常量
COMMIT_TIP = ("提交号取自构建戳：交付包里是打包那一刻烧进去的那一份，"
              "直接跑源码时现场问 git；问不到写「无 git 信息」，不编一个号出来。"
              "要核当前源码版本，在仓库目录里跑 git rev-parse --short HEAD")
BUILT_TIP = ("构建日期同样取自构建戳：交付包里写的是打包完成那一刻，"
             "直接跑源码时这一格显示「开发态（未打包）」")
# 「复制全部」那颗按钮的三种状态：两栈各写一份就会漂移，由对撞键逐字比
COPY_LABEL = "复制全部"
COPIED_LABEL = "已复制"
CLIP_FAIL_LABEL = "剪贴板打不开"

# 「关于」里那一行仓库链接。文案与状态字都是两栈各写一份的串，
# 由对撞键 dlg.about.repo.* 逐字比；地址本身只有一个来源（appinfo.REPO_URL）
REPO_LABEL = "GitHub"
REPO_OPENED_LABEL = "已在浏览器打开"
REPO_COPIED_LABEL = "打不开浏览器，地址已复制"
REPO_FAIL_LABEL = "打不开浏览器，也没能写进剪贴板"
REPO_TIP = ("仓库 " + REPO_URL + "：单击用系统默认浏览器打开。"
             "本机打不开浏览器时（内网、没装浏览器、被安全软件拦下），"
             "地址会写进剪贴板")
# 随包的两张小图：GitHub 轮廓与行尾的外链箭头。画不出汉字那类教训同样适用于图形——
# 所以图不靠系统主题，文件跟着包走（生成办法见 java/tools/export_repo_icons.py）
REPO_ICON_FILES = ("assets/github_mark.png", "assets/external_link.png")
REPO_ICON_PX = 18     # 左边那枚：与正文那一档字号差不多高
REPO_ARROW_PX = 15    # 行尾那枚：略小一档，免得抢正文的字

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


class RepoLinkRow(QFrame):
    """「关于」里那一行仓库链接：左边 GitHub 轮廓、中间「GitHub」、行尾外链箭头，整行可点。

    地址不铺在正文那一格（框窄，长地址会被挤到看不全），所以取走它的路径给三条：
    悬浮提示写完整地址、「复制全部」多带一行、本机打不开浏览器时直接进剪贴板。
    点了必须有可见回执——静默不响应等于没这一行。
    """

    def __init__(self, url=REPO_URL, opener=None, parent=None):
        super().__init__(parent)
        self.url = url
        self.opener = opener or open_in_browser
        self.last_status = ""
        lay = QHBoxLayout(self)
        lay.setContentsMargins(6, 3, 6, 3)
        lay.setSpacing(7)
        self.icon = QLabel()
        pm = _bundled_pixmap(REPO_ICON_FILES[0], REPO_ICON_PX)
        if pm is not None:
            self.icon.setPixmap(pm)
        self.text = QLabel(REPO_LABEL)
        self.arrow = QLabel()
        pm = _bundled_pixmap(REPO_ICON_FILES[1], REPO_ARROW_PX)
        if pm is not None:
            self.arrow.setPixmap(pm)
        lay.addWidget(self.icon)
        lay.addWidget(self.text)
        lay.addStretch(1)
        lay.addWidget(self.arrow)
        self.setCursor(Qt.PointingHandCursor)
        self.setFocusPolicy(Qt.TabFocus)   # Tab 走到这一行，回车与空格等价于单击

    def mousePressEvent(self, e):
        if e.button() == Qt.LeftButton:
            self.activate_link()
        super().mousePressEvent(e)

    def keyPressEvent(self, e):
        if e.key() in (Qt.Key_Return, Qt.Key_Enter, Qt.Key_Space):
            self.activate_link()
            return
        super().keyPressEvent(e)

    def enterEvent(self, e):
        # 悬停给一档浅底：整行都是热区，不给个底色没人知道能点
        self.setStyleSheet("background-color: #eef2f6; border-radius: 4px;")

    def leaveEvent(self, e):
        self.setStyleSheet("")

    def activate_link(self):
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
        QTimer.singleShot(2000, lambda: self.text.setText(REPO_LABEL))

    def row_text(self):
        """自检与对撞用：这一行此刻显示的字。"""
        return self.text.text()


class AboutDialog(QDialog):
    """「关于」小框：版本 / 提交 / 构建日期 / 作者四行加一行仓库链接，值全部来自 appinfo。

    提交号与构建日期是打包时烧进产物的（exe 所在机器没有 .git），
    开发态则现场问 git；取不到就显示"开发态（未打包）"，不会编造。
    """

    def __init__(self, parent=None, repo_opener=None):
        super().__init__(parent)
        self.setWindowTitle("关于")
        self.setModal(True)
        # 262 = 原来那 230 再加一行仓库链接（行高 26 + 行距 6）
        fit_client(self, 380, 262)
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
        tips = {"commit": COMMIT_TIP + "；选中后可直接贴给别人",
                "built": BUILT_TIP}
        for i, (key, label, value) in enumerate((
                ("version", "版本", f"v{info['version']}"),
                ("commit", "提交", commit_display(info)),
                ("built", "构建日期", info["built_at"]),
                ("author", "作者", AUTHOR))):
            head = QLabel(label)
            head.setStyleSheet("color: #666;")
            body = QLabel(value)
            body.setTextInteractionFlags(Qt.TextSelectableByMouse)   # 提交号要能选中复制
            body.setToolTip(tips.get(key, ""))
            grid.addWidget(head, i, 0)
            grid.addWidget(body, i, 1)
            self.rows[key] = body
        grid.setColumnStretch(1, 1)

        # 仓库那一行不在 grid 里：grid 的第 0 列是行名，对撞键 dlg.about.rows 按那一列比，
        # 把这行塞进去会让四行变成五行，而它本来就不是"一行信息"，是一颗链接
        self.repo_row = RepoLinkRow(REPO_URL, repo_opener, self)
        self.repo_row.setToolTip(REPO_TIP)

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
        lay.addWidget(self.repo_row)
        lay.addStretch(1)
        lay.addLayout(row)

    def copy_text(self):
        """「复制全部」放进剪贴板的那一份文本：一行一项，提交号给全号、仓库给完整地址。

        正面那一格仍是短号（一行放得下、也够人眼看），贴出去的要能一路粘进 git 里查，
        所以这一份给 40 位；老产物没烧全号时退回短号，不写一个空值出去。
        仓库那一行界面上只写「GitHub」，完整地址在这一份里给全，抄出去就能直接贴。
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

    def repo_text(self):
        """自检与对撞用：仓库那一行此刻的字。"""
        return self.repo_row.row_text()

    def repo_url(self):
        """自检与对撞用：那一行点出去、也抄出去的那串地址。"""
        return self.repo_row.url

    def repo_states(self):
        """自检与对撞用：那一行的三种回执，逐字比，两栈不许各写一份。"""
        return "|".join((REPO_LABEL, REPO_OPENED_LABEL, REPO_COPIED_LABEL, REPO_FAIL_LABEL))

    def repo_tip(self):
        """自检与对撞用：那一行的悬浮提示（完整地址就在这一串里）。"""
        return self.repo_row.toolTip()

    def repo_icons_missing(self):
        """自检用：两张随包小图有没有真读进来（缺图不算失败，但要能被发现）。

        只报文件名：两栈一个按 assets/ 相对路径、一个按 classpath 路径，
        带上前缀对撞永远红，缺不缺图这件事本身才是判据。
        """
        return ",".join(os.path.basename(rel) for rel, label in
                        ((REPO_ICON_FILES[0], self.repo_row.icon),
                         (REPO_ICON_FILES[1], self.repo_row.arrow))
                        if label.pixmap().isNull())

    def keyPressEvent(self, e):
        if e.key() == Qt.Key_Escape:
            self.reject()
            return
        super().keyPressEvent(e)
