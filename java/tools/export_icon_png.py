# -*- coding: utf-8 -*-
"""把 assets/app_icon.ico 里最大的那张 PNG 抽成 Java 随包资源。

Java 的 ImageIO 不认 .ico 容器（图本身是 PNG，外层目录结构读不了），
所以在这里做一次转换；源图仍然只有 assets/app_icon.ico 一份，两条线共用。
ICO 目录项的字段顺序是"先大小后偏移"，读反了会切出一段错位字节，
所以抽出后再用 IHDR 头核对一次宽高。
"""
import io
import os
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
ICO = os.path.normpath(os.path.join(HERE, "..", "..", "assets", "app_icon.ico"))
OUT = os.path.normpath(os.path.join(HERE, "..", "app", "src", "main",
                                   "resources", "app_icon.png"))
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


def pick_entries(blob):
    count = struct.unpack_from("<H", blob, 4)[0]
    out = []
    for i in range(count):
        base = 6 + i * 16
        w = blob[base] or 256
        h = blob[base + 1] or 256
        size, offset = struct.unpack_from("<II", blob, base + 8)
        if offset + size > len(blob):
            continue
        payload = blob[offset:offset + size]
        if payload.startswith(PNG_MAGIC):
            out.append((w * h, w, h, payload))
    return out


def main():
    blob = io.open(ICO, "rb").read()
    picks = pick_entries(blob)
    if not picks:
        raise SystemExit("ico 里没有 PNG 载荷，检查源文件")
    picks.sort()
    area, w, h, data = picks[-1]
    # IHDR：签名 8 字节 + 长度 4 + 类型 4 之后才是宽与高
    iw, ih = struct.unpack_from(">II", data, 16)
    if (iw, ih) != (w, h):
        raise SystemExit("切出来的 PNG 与目录声明不一致：%s" % ((iw, ih), (w, h)))
    io.open(OUT, "wb").write(data)
    print("取最大一张 %dx%d，写出 %s（%d 字节，共 %d 个 PNG 条目）"
          % (iw, ih, os.path.relpath(OUT, os.path.join(HERE, "..")), len(data), len(picks)))


if __name__ == "__main__":
    main()
