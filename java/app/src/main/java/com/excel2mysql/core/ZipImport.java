package com.excel2mysql.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        /** 因为"解压后路径太长"而被去掉包内目录层级的条目名，日志要单独说一句。 */
        public final List<String> flattened = new ArrayList<String>();
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
    /** 解压落点的长度预算：Windows 上限 260，留 20 给后续可能的短文件名转换与反斜杠。 */
    static final int PATH_BUDGET = 240;

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
        // 折回 GBK 的前提是"这个名字本来就是 cp437 解出来的"。汉字不在 cp437 的取值范围里，
        // getBytes(CP437) 会把每个编不进的字符写成 '?'，落点就成了 ????.xlsx——
        // Windows 判它「文件名、目录名或卷标语法不正确」，整包一个都解不出来。
        // 位标记读不到的时候（ZIP64 的条目数与目录偏移写在 64 位字段里，
        // 只按 16 位 EOCD 取的扫描会整份失明）也必须原样留名，而不是毁掉它。
        byte[] raw;
        try {
            raw = name.getBytes(CP437);
        } catch (Exception e) {
            return name;
        }
        if (!new String(raw, CP437).equals(name)) {
            return name;              // 有字符编不进 cp437：这名字不是 cp437 解出来的，别折
        }
        String gbk;
        try {
            gbk = new String(raw, GBK);
        } catch (Exception e) {
            return name;
        }
        // 折出来带替换符说明这串字节本来就不是 GBK，硬用只会得到一个看不出原因的文件名
        return gbk.indexOf('\uFFFD') >= 0 ? name : gbk;
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
        /** 第一个带 bit0（加密）的条目名；JDK 在打开阶段就整包拒绝，只能靠这里给出名字。 */
        private String encryptedEntry;

        void load(String zipPath) throws IOException {
            byName.clear();
            encryptedEntry = null;
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
                // EOCD 里"条目数"是 16 位（偏移 8 与 10），只有目录大小与偏移才是 32 位。
                // 读成 32 位会把"目录大小"的低两字节当成条目数，循环一路读出真实目录去，
                // 最后撞在 EOCD 尾巴上抛 EOFException——整个压缩包会被判成"打不开"。
                long total = readLe16(e, 10) & 0xFFFFL;
                long cdSize = readLe32(e, 12) & 0xFFFFFFFFL;
                long cdOffset = readLe32(e, 16) & 0xFFFFFFFFL;
                if (total == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
                    // ZIP64：条目数、目录大小与目录偏移都改写在 64 位的 ZIP64 EOCD 里，
                    // 16/32 位那三格填的是 0xFFFF / 0xFFFFFFFF 占位。只按旧字段取的话，
                    // 整个映射建不起来，位标记全按"没标记 UTF-8"处理，
                    // 中文条目名就会被折成一行问号（现场表现为拖包后报路径语法错误）。
                    // 定位块紧贴 EOCD 前面，包有注释时还要再往前让开注释那段。
                    long[] z64 = readZip64Eocd(raf,
                            eocd - 20 - (readLe16(e, 20) & 0xFFFF));
                    if (z64 == null) {
                        return;
                    }
                    total = z64[0];
                    cdSize = z64[1];
                    cdOffset = z64[2];
                }
                if (total <= 0 || total > Integer.MAX_VALUE || cdOffset + cdSize > len) {
                    return;                 // 结构异常：不去猜，交给上层按"没有位标志"处理
                }
                raf.seek(cdOffset);
                for (int idx = 0; idx < (int) total; idx++) {
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
                    // ZipFile 现在按 GBK 解无 UTF-8 标记的名字，所以第三种写法才是
                    // entry.getName() 真正拿到的那串；少了它，加密条目那位 bit0 就查不到，
                    // 「压缩包已加密」这句会变成一句静默的错解
                    put(asUtf8, flag);
                    put(asCp437, flag);
                    put(new String(raw, GBK), flag);
                    if (encryptedEntry == null && (flag & 0x1) != 0) {
                        encryptedEntry = (flag & 0x800) != 0 ? asUtf8 : new String(raw, GBK);
                    }
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

        String encryptedEntry() {
            return encryptedEntry;
        }
    }

    private static long readLe32(byte[] b, int at) {
        return (b[at] & 0xFFL) | ((b[at + 1] & 0xFFL) << 8) | ((b[at + 2] & 0xFFL) << 16)
                | ((b[at + 3] & 0xFFL) << 24);
    }

    private static int readLe16(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    private static long readLe64(byte[] b, int at) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[at + i] & 0xFFL);
        }
        return v;
    }

    /**
     * 按 ZIP64 EOCD 定位块的偏移读那条 64 位记录。
     * 返回 {条目数, 中央目录大小, 中央目录偏移}；定位块缺失、签名对不上或读不出去就回 null，
     * 由调用方按"读不到位标志"处理——宁可名字原样留着，也不要猜一个偏移读进数据区。
     */
    private static long[] readZip64Eocd(java.io.RandomAccessFile raf, long locatorAt) {
        if (locatorAt < 0) {
            return null;
        }
        try {
            byte[] loc = new byte[20];
            raf.seek(locatorAt);
            raf.readFully(loc);
            if (readLe32(loc, 0) != 0x07064b50L) {
                return null;
            }
            long at = readLe64(loc, 8);
            byte[] r = new byte[56];
            raf.seek(at);
            raf.readFully(r);
            if (readLe32(r, 0) != 0x06064b50L) {
                return null;
            }
            return new long[]{readLe64(r, 32), readLe64(r, 40), readLe64(r, 48)};
        } catch (IOException e) {
            return null;
        }
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

    /**
     * 打开压缩包。字符集必须显式给 GBK：
     * JDK 默认按 UTF-8 解条目名，而条目名里出现不合法的 UTF-8 字节时它不替换、
     * 直接抛 IllegalArgumentException: MALFORMED（ZipCoder.toString）。
     * Windows 自带「发送到压缩文件夹」写的正是 GBK 条目名，于是整包在枚举第一条
     * 非 ASCII 名字时就炸掉——现场表现为「拖进去就报错」，而 Python 线读得下来。
     * 给了 GBK 之后，带 UTF-8 标记（bit11）的条目仍按 UTF-8 解，两种包都对。
     */
    private static ZipFile open(File f) throws IOException {
        return new ZipFile(f, GBK);
    }

    /** 预扫包内 Excel 条目数；打不开或不存在的包返回 0，真正的错误留给解压时报。 */
    public static int countExcelEntries(String zipPath) {
        ZipFile z = null;
        try {
            flags.load(zipPath);
            z = open(new File(zipPath));
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
        // 平铺时不能把两个同名文件写到同一个落点：那样后一张表会静默盖掉前一张
        Set<String> written = new HashSet<String>();
        ZipFile z;
        try {
            flags.load(zipPath);
            z = open(f);
        } catch (IOException e) {
            // JDK 8 在打开阶段就整包拒绝带 bit0 的压缩包，回的是英文那句
            // 「invalid CEN header (encrypted entry)」——现场看不懂也做不了什么。
            // 自己扫过中央目录就知道到底是哪一条被加密了，说人话并给出下一步。
            String encrypted = flags.encryptedEntry();
            if (encrypted != null) {
                throw new ZipArchiveException("压缩包已加密（" + encrypted
                        + "），请先解密后再导入");
            }
            throw new ZipArchiveException("压缩包无法打开：" + LogText.describe(e));
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
                String rel = safeRelPath(name);
                String target = destDir + File.separatorChar + rel;
                // Windows 传统路径上限是 260 字符，而这条路径的四段里三段不可控：
                // 现场机器的 TEMP（用户名长）、包名、包内目录层级与中文文件名。
                // 顶到上限时症状是"拖进来就报"，且只在现场报、开发机不复现。
                // 先压掉包内目录层级——表名取自文件名，这一刀不改表名；
                // 压完还太长就明说该怎么做，别抛一句 "系统找不到指定的路径"。
                if (target.length() > PATH_BUDGET) {
                    String flat = new File(rel).getName();
                    String flatTarget = destDir + File.separatorChar + flat;
                    if (flatTarget.length() <= PATH_BUDGET && !written.contains(flatTarget)) {
                        target = flatTarget;
                        result.flattened.add(name);
                    } else {
                        throw new ZipArchiveException("解压后的路径过长（" + target.length()
                                + " 字符，Windows 上限 260）：" + name
                                + "；把压缩包拷到一个短路径目录（如 D:\\data）再拖，"
                                + "或改短环境变量 TEMP 后重试");
                    }
                }
                written.add(target);
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
                    // 带上异常类型：现场那句「被占用」还是「路径语法不正确」，
                    // 只有类型 + 系统原文能分清，光一句 message 抄不回原因
                    throw new ZipArchiveException("写入 " + target + " 失败：" + LogText.describe(e));
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
