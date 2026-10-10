"""合并单元格探测。

含合并单元格的表无法可靠地映射成关系表：合并区只有左上角有值，其余格在
pandas 读进来是 NaN，字段名和数据列都会错位。所以这类文件直接拒绝导入，
而不是默默导出一份错数据。

.xlsx 用流式扫描 sheet XML 实现：openpyxl 要全量加载整个工作簿（20 万行实测
8.7 秒），而这里只需要知道"有没有合并区、在哪几处"，流式读到 <mergeCell
就早停，实测 0.1 秒。
"""
import os
import re
import zipfile

XLSX_EXTS = (".xlsx", ".xlsm")
XLS_EXTS = (".xls",)

_REL_TAG_RE = re.compile(r"<Relationship\b[^>]*>")
_REL_ID_RE = re.compile(r'Id="([^"]+)"')
_REL_TARGET_RE = re.compile(r'Target="([^"]+)"')
# 属性顺序不固定（openpyxl 把 Id 写在 Target 之后，Excel 反过来），只能逐个属性取
_SHEET_TAG_RE = re.compile(r"<sheet\b[^>]*>")
_SHEET_NAME_RE = re.compile(r'name="([^"]+)"')
_SHEET_RID_RE = re.compile(r'r:id="([^"]+)"')

CHUNK = 1 << 20          # 1 MB

CARRY = 256              # 跨块余量：够容纳一条完整的 <mergeCell ref="..."/>
MAX_REPORT = 6           # 日志里最多列出几处合并区
# 必须连标签一起匹配：sheet XML 里 <dimension ref="..."> 等也带 ref= 属性
_MERGE_CELL_RE = re.compile(r'<mergeCell\s+ref="([^"]+)"')
_RANGE_RE = re.compile(r"^([A-Z]+)(\d+):([A-Z]+)(\d+)$")


def _is_real_range(ref: str) -> bool:
    """A1:A1 这类单格"合并"没有实际影响，不算合并区。"""
    m = _RANGE_RE.match(ref)
    if not m:
        return False
    return not (m.group(1) == m.group(3) and m.group(2) == m.group(4))


def _col_width(col: str) -> int:
    n = 0
    for ch in col:
        n = n * 26 + (ord(ch) - 64)
    return n


def _range_key(ref: str):
    """按左上角坐标排序；列名不能按字符串排（A10 会跑到 A2 前面）。"""
    m = _RANGE_RE.match(ref)
    return (int(m.group(2)), _col_width(m.group(1))) if m else (0, 0)


def _sorted_unique(refs):
    return sorted(dict.fromkeys(refs), key=_range_key)


def _scan_sheet_stream(zf: zipfile.ZipFile, inner: str) -> list:
    """流式读出该 sheet 的全部 mergeCell 引用。

    不做"命中即停"：20 万行的表本来就要扫到底才能确认无合并（实测 116 ms），
    早停省不下什么，却会让"含 N 处合并区"的 N 恒等于 1，报出假数字。
    """
    refs = []
    with zf.open(inner) as fh:
        carry = b""
        while True:
            buf = fh.read(CHUNK)
            if not buf:
                break
            window = carry + buf
            refs += [r for r in _MERGE_CELL_RE.findall(window.decode("utf-8", "replace"))
                     if _is_real_range(r)]
            carry = window[-CARRY:]
    # 分块边界会重复命中同一条，去重后按坐标排序
    return _sorted_unique(refs)


class MergedCellsError(ValueError):
    """文件含合并单元格，拒绝导入。"""

    def __init__(self, filepath, merged):
        self.filepath = filepath
        self.merged = merged          # {sheet名: [范围, ...]}
        parts = [f"「{name}」{len(ranges)} 处（{self._fmt(ranges)}）"
                 for name, ranges in merged.items()]
        super().__init__("检测到合并单元格：" + "；".join(parts))

    @staticmethod
    def _fmt(ranges):
        shown = "、".join(ranges[:MAX_REPORT])
        return shown + (f" 等" if len(ranges) > MAX_REPORT else "")


def _col_letter(idx0: int) -> str:
    """0 基列号 -> Excel 列名。"""
    s = ""
    n = idx0 + 1
    while n:
        n, r = divmod(n - 1, 26)
        s = chr(65 + r) + s
    return s


def _scan_xlsx(path: str) -> dict:
    """流式扫描 xlsx 各 sheet 的 <mergeCell>，按 sheet 汇总合并区。"""
    out = {}
    with zipfile.ZipFile(path) as z:
        names = set(z.namelist())
        try:
            wbxml = z.read("xl/workbook.xml").decode("utf-8", "replace")
        except KeyError:
            return out
        rel_path = "xl/_rels/workbook.xml.rels"
        rels = {}
        if rel_path in names:
            relxml = z.read(rel_path).decode("utf-8", "replace")
            for tag in _REL_TAG_RE.findall(relxml):
                rid, tgt = _REL_ID_RE.search(tag), _REL_TARGET_RE.search(tag)
                if rid and tgt:
                    rels[rid.group(1)] = tgt.group(1)
        for tag in _SHEET_TAG_RE.findall(wbxml):
            nm, rid = _SHEET_NAME_RE.search(tag), _SHEET_RID_RE.search(tag)
            if not (nm and rid):
                continue
            tgt = rels.get(rid.group(1), "")
            if not tgt or tgt.startswith(("http:", "file:", "mailto:")):
                continue                      # 外部链接，不是包内部件
            inner = tgt.lstrip("/")
            if not inner.startswith("xl/"):
                inner = "xl/" + inner
            if inner not in names:
                continue
            found = _scan_sheet_stream(z, inner)
            if found:
                out[nm.group(1)] = found
    return out


def _scan_xls(path: str) -> dict:
    """老格式走 xlrd。

    merged_cells 来自 BIFF 的 MERGEDAREA 记录，必须 formatting_info=True 才会填充；
    默认参数下恒为空，会把满是合并区的表单当成干净文件放过去。
    个别 .xls 没有完整的格式化流，打不开时退回普通模式（宁可漏报也不阻断导入）。
    """
    import xlrd
    out = {}
    try:
        bk = xlrd.open_workbook(path, formatting_info=True)
    except Exception:
        try:
            bk = xlrd.open_workbook(path)
        except Exception:
            return out
    for sh in bk.sheets():
        cells = getattr(sh, "merged_cells", None) or []
        ranges = []
        for rlo, rhi, clo, chi in cells:
            # 只跨一格的不算真合并
            if rhi - rlo <= 1 and chi - clo <= 1:
                continue
            a = f"{_col_letter(clo)}{rlo + 1}"
            b = f"{_col_letter(chi - 1)}{rhi}"
            ranges.append(f"{a}:{b}")
        if ranges:
            out[sh.name] = _sorted_unique(ranges)
    return out


def detect_merged_cells(filepath: str) -> dict:
    """返回 {sheet名: [合并区范围, ...]}；无合并单元格或格式不支持时返回 {}。"""
    ext = os.path.splitext(filepath)[1].lower()
    try:
        if ext in XLSX_EXTS:
            return _scan_xlsx(filepath)
        if ext in XLS_EXTS:
            return _scan_xls(filepath)
    except (zipfile.BadZipFile, KeyError, OSError):
        # 文件本身有问题时不在此处下结论，交给后面的读取流程报权威错误
        return {}
    return {}


def ensure_no_merged_cells(filepath: str) -> None:
    """含合并单元格则抛 MergedCellsError，供 build_table_def 在读数据前调用。"""
    merged = detect_merged_cells(filepath)
    if merged:
        raise MergedCellsError(filepath, merged)
