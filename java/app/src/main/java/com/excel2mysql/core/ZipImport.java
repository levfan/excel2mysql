package com.excel2mysql.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * core/zip_import.py：把压缩包里的 Excel 解到目标目录，供逐表导入。
 * 只解 .xlsx / .xls，嵌套 zip 不递归（产品口径明确排除）。
 */
public final class ZipImport {

    public static final class Entry {
        public final String path;
        public final String origin;

        public Entry(String path, String origin) {
            this.path = path;
            this.origin = origin;
        }
    }

    public static final class Result {
        public final List<Entry> files = new ArrayList<Entry>();
        public final List<String> ignored = new ArrayList<String>();
        public int dirs;
        public int nestedZips;
    }

    /** 压缩包本身有问题：不是 zip、已损坏、带密码。 */
    public static final class ZipArchiveException extends RuntimeException {
        public ZipArchiveException(String message) {
            super(message);
        }
    }

    private static final Charset CP437 = Charset.forName("CP437");
    private static final Charset GBK = Charset.forName("GBK");

    private ZipImport() {
    }

    /**
     * 还原 zip 条目名。通用位标记 bit11 为 0 时按 cp437 存，
     * 而 Windows 自带"发送到压缩文件夹"写的是 GBK，中文条目名会变乱码，
     * 直接毁掉表名与表注释，所以要 cp437 -> GBK 折回去。
     */
    public static String decodeName(ZipEntry entry) {
        return decodeName(entry.getName(), flags.entryFlag(entry.getName()));
    }

    static String decodeName(String name, int flag) {
        if ((flag & 0x800) != 0) {
            return name;
        }
        try {
            return new String(name.getBytes(CP437), GBK);
        } catch (Exception e) {
            return name;
        }
    }

    /** 每次解压/预扫前先 load 一次。 */
    private static final CentralDirectory flags = new CentralDirectory();

    /**
     * JDK 的 ZipEntry 不暴露通用位标记，而 bit11 正是"这个名字该按 UTF-8 还是 cp437 读"的开关。
     * 反射去读 JDK 内部字段在别的 JVM 上会静默失效，所以自己扫一遍中央目录，
     * 只取名字到位标记的映射，不碰数据区。
     */
    static final class CentralDirectory {
        private final Map<String, Integer> byName = new LinkedHashMap<String, Integer>();

        void load(String zipPath) throws IOException {
            byName.clear();
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(zipPath, "r");
            try {
                long len = raf.length();
                long cursor = Math.max(0, len - 65557);
                byte[] buf = new byte[65557];
                raf.seek(cursor);
                raf.readFully(buf, 0, (int) Math.min(buf.length, len - cursor));
                long eocd = -1;
                for (int i = buf.length - 22; i >= 0; i--) {
                    if (readLe32(buf, i) == 0x06054b50L) {
                        eocd = cursor + i;
                        break;
                    }
                }
                if (eocd < 0) {
                    return;
                }
                raf.seek(eocd);
                byte[] e = new byte[22];
                raf.readFully(e);
                int total = (int) readLe32(e, 10);
                long cdOffset = readLe32(e, 16) & 0xFFFFFFFFL;
                raf.seek(cdOffset);
                for (int idx = 0; idx < total; idx++) {
                    byte[] h = new byte[46];
                    raf.readFully(h);
                    if (readLe32(h, 0) != 0x02014b50L) {
                        return;
                    }
                    int flag = (int) readLe16(h, 8);
                    int nameLen = (int) readLe16(h, 28);
                    int extraLen = (int) readLe16(h, 30);
                    int commentLen = (int) readLe16(h, 32);
                    byte[] raw = new byte[nameLen];
                    raf.readFully(raw);
                    raf.skipBytes(extraLen + commentLen);
                    String asUtf8 = new String(raw, Charset.forName("UTF-8"));
                    String asCp437 = new String(raw, CP437);
                    put(asUtf8, flag);
                    put(asCp437, flag);
                }
            } finally {
                raf.close();
            }
        }

        private void put(String name, int flag) {
            Integer old = byName.get(name);
            if (old == null || old.intValue() == 0) {
                byName.put(name, Integer.valueOf(flag));
            }
        }

        int entryFlag(String name) {
            Integer v = byName.get(name);
            return v == null ? 0 : v.intValue();
        }
    }

    private static long readLe32(byte[] b, int at) {
        return (b[at] & 0xFFL) | ((b[at + 1] & 0xFFL) << 8) | ((b[at + 2] & 0xFFL) << 16)
                | ((b[at + 3] & 0xFFL) << 24);
    }

    private static int readLe16(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    /** 把包内路径转成安全的相对路径，挡掉 ../ 与绝对路径的 zip slip。 */
    public static String safeRelPath(String name) {
        String[] parts = name.replace('\\', '/').split("/");
        List<String> keep = new ArrayList<String>();
        for (String p : parts) {
            if (!p.isEmpty() && !".".equals(p) && !"..".equals(p)) {
                keep.add(p);
            }
        }
        if (keep.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String p : keep) {
            if (sb.length() > 0) {
                sb.append(File.separatorChar);
            }
            sb.append(p);
        }
        return sb.toString();
    }

    public static boolean isExcel(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    /** 条目归类：解压和预扫条数必须共用这一套判定，否则进度总项数会对不上。 */
    public static String classify(ZipEntry entry) {
        String name = decodeName(entry);
        if (entry.isDirectory()) {
            return "dir";
        }
        if (safeRelPath(name) == null) {
            return "unsafe";
        }
        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        base = slash < 0 ? base : base.substring(slash + 1);
        if (base.toLowerCase().endsWith(".zip")) {
            return "nested";
        }
        if (!isExcel(base)) {
            return "other";
        }
        if (base.startsWith("~$")) {
            return "lock";
        }
        return "excel";
    }

    /** 预扫包内 Excel 条目数；打不开或不存在的包返回 0，真正的错误留给解压时报。 */
    public static int countExcelEntries(String zipPath) {
        ZipFile z = null;
        try {
            flags.load(zipPath);
            z = new ZipFile(new File(zipPath));
            int n = 0;
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                if ("excel".equals(classify(en.nextElement()))) {
                    n++;
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        } finally {
            close(z);
        }
    }

    public static Result extract(String zipPath, String destDir) {
        return extract(zipPath, destDir, null);
    }

    public static Result extract(String zipPath, String destDir, ProgressCallback cb) {
        File f = new File(zipPath);
        if (!f.exists() || f.length() < 4) {
            throw new ZipArchiveException("不是有效的 ZIP 文件（或文件已损坏）");
        }
        Result result = new Result();
        ZipFile z;
        try {
            flags.load(zipPath);
            z = new ZipFile(f);
        } catch (IOException e) {
            throw new ZipArchiveException("压缩包无法打开：" + e.getMessage());
        }
        try {
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            List<ZipEntry> entries = new ArrayList<ZipEntry>();
            while (en.hasMoreElements()) {
                entries.add(en.nextElement());
            }
            for (ZipEntry entry : entries) {
                String name = decodeName(entry);
                String kind = classify(entry);
                if ("dir".equals(kind)) {
                    result.dirs++;
                    continue;
                }
                if ("unsafe".equals(kind)) {
                    continue;
                }
                if ((flags.entryFlag(entry.getName()) & 0x1) != 0) {
                    throw new ZipArchiveException("压缩包已加密（" + baseOf(name)
                            + "），请先解密后再导入");
                }
                if ("nested".equals(kind)) {
                    result.nestedZips++;
                    continue;
                }
                if ("other".equals(kind) || "lock".equals(kind)) {
                    result.ignored.add(name);
                    continue;
                }
                String target = destDir + File.separatorChar + safeRelPath(name);
                File out = new File(target);
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new ZipArchiveException("写入 " + target + " 失败：目录建不出来");
                }
                InputStream in = null;
                OutputStream os = null;
                try {
                    in = z.getInputStream(entry);
                    os = new java.io.FileOutputStream(out);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                } catch (IOException e) {
                    throw new ZipArchiveException("写入 " + target + " 失败：" + e.getMessage());
                } finally {
                    close(in);
                    close(os);
                }
                result.files.add(new Entry(target, name.replace('\\', '/')));
                if (cb != null) {
                    cb.onEntry(name);
                }
            }
        } finally {
            close(z);
        }
        return result;
    }

    public interface ProgressCallback {
        void onEntry(String name);
    }

    private static String baseOf(String name) {
        String s = name.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        return slash < 0 ? s : s.substring(slash + 1);
    }

    private static void close(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignored) {
            // 收尾关闭失败不影响已经拿到的结果
        }
    }

    /** 供上层按需取用的空结果，避免调用方各自 new。 */
    public static Map<String, Object> emptyMap() {
        return new LinkedHashMap<String, Object>();
    }
}
