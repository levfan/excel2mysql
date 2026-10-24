package com.excel2mysql.gui;

import java.util.Locale;

/** 脚本文件大小按人看的一档显示；与 main.py 的 format_file_size 同一套进位与一位小数。 */
public final class FileSizes {

    private FileSizes() {
    }

    public static String format(long size) {
        if (size < 1024) {
            return size + " B";
        }
        double value = size / 1024.0;
        if (value < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", value);
        }
        value /= 1024.0;
        if (value < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", value);
        }
        return String.format(Locale.ROOT, "%.1f GB", value / 1024.0);
    }
}
