"""使用说明对话框：左侧目录 + 右侧正文，正文内容全部来自 help_doc.SECTIONS。

单独成模块是因为主窗口已经够挤，且这份排版只服务于"看"，不参与任何导入逻辑。
"""
from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QDialog, QHBoxLayout, QVBoxLayout, QListWidget, QListWidgetItem,
    QTextBrowser, QPushButton, QLabel, QFrame,
)

from help_doc import SECTIONS

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
        self.resize(960, 700)

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
