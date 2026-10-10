"""ZIP 压缩包展开：把包内的 Excel 解压到目标目录，供逐表导入。

只处理 zip 内直接/子目录里的 .xlsx / .xls；嵌套 zip 不递归（按产品口径明确排除）。
"""
import os
import zipfile

EXCEL_EXTS = (".xlsx", ".xls")


class ZipArchiveError(Exception):
    """压缩包本身有问题：不是 zip、已损坏、带密码。"""


def decode_name(info: zipfile.ZipInfo) -> str:
    """还原 zip 条目名。

    通用位标记 bit11 为 0 时 zipfile 按 cp437 解码，而 Windows 自带"发送到压缩
    文件夹"写的是 GBK，中文条目名会变成乱码，直接毁掉表名与表注释。
    """
    name = info.filename
    if info.flag_bits & 0x800:
        return name
    try:
        return name.encode("cp437").decode("gbk")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return name


def safe_relpath(name: str):
    """把包内路径转成安全的相对路径，挡掉 ../ 与绝对路径的 zip slip。"""
    parts = [p for p in name.replace("\\", "/").split("/")
             if p not in ("", ".", "..", "")]
    return os.path.join(*parts) if parts else None


def is_excel(name: str) -> bool:
    return name.lower().endswith(EXCEL_EXTS)


def classify(info: zipfile.ZipInfo) -> str:
    """条目归类：dir / unsafe / nested / other / lock / excel。

    解压和预扫条数必须用同一套判定，否则进度总项数和实际项数会对不上。
    """
    name = decode_name(info)
    if info.is_dir():
        return "dir"
    if safe_relpath(name) is None:
        return "unsafe"
    base = os.path.basename(name)
    if base.lower().endswith(".zip"):
        return "nested"                                  # 嵌套 zip 不递归展开
    if not is_excel(base):
        return "other"
    if base.startswith("~$"):
        return "lock"                                    # Excel 临时锁文件不是合法工作簿
    return "excel"


def count_excel_entries(zip_path: str) -> int:
    """预扫包内 Excel 条目数，供上层一次算准进度总项数（只读中央目录，代价很小）。

    打不开或不存在的包返回 0，真正的错误留给解压时报。
    """
    try:
        with zipfile.ZipFile(zip_path) as zf:
            return sum(1 for info in zf.infolist() if classify(info) == "excel")
    except Exception:
        return 0


def extract_excel_archive(zip_path: str, dest_dir: str, progress_cb=None) -> dict:
    """解压 zip_path 内全部 Excel 到 dest_dir，保留包内目录结构。

    返回 {"files": [{"path","origin"}], "ignored": [包内非 Excel 文件名],
          "dirs": 跳过的目录条目数, "nested_zips": 跳过的嵌套 zip 数}
    files 按包内出现顺序排列。progress_cb(包内条目名) 在每解出一个 Excel 后调用，
    供上层报逐文件进度。
    """
    if not zipfile.is_zipfile(zip_path):
        raise ZipArchiveError("不是有效的 ZIP 文件（或文件已损坏）")

    result = {"files": [], "ignored": [], "dirs": 0, "nested_zips": 0}
    try:
        zf = zipfile.ZipFile(zip_path)
    except zipfile.BadZipFile as e:
        raise ZipArchiveError(f"压缩包无法打开：{e}") from e

    with zf:
        for info in zf.infolist():
            name = decode_name(info)
            kind = classify(info)
            if kind == "dir":
                result["dirs"] += 1
                continue
            if kind == "unsafe":
                continue
            if info.flag_bits & 0x1:
                raise ZipArchiveError(
                    f"压缩包已加密（{os.path.basename(name)}），请先解密后再导入")
            if kind == "nested":
                result["nested_zips"] += 1
                continue
            if kind in ("other", "lock"):
                result["ignored"].append(name)
                continue
            if info.flag_bits & 0x1:
                raise ZipArchiveError(
                    f"压缩包已加密（{os.path.basename(name)}），请先解密后再导入")
            target = os.path.join(dest_dir, safe_relpath(name))
            os.makedirs(os.path.dirname(target), exist_ok=True)
            try:
                with zf.open(info) as src, open(target, "wb") as out:
                    out.write(src.read())
            except RuntimeError as e:              # zipfile 对加密条目抛 RuntimeError
                raise ZipArchiveError(
                    f"解压 {name} 失败，压缩包可能带密码：{e}") from e
            except OSError as e:
                raise ZipArchiveError(f"写入 {target} 失败：{e}") from e
            result["files"].append({"path": target, "origin": name.replace("\\", "/")})
            if progress_cb:
                progress_cb(name)
    return result
