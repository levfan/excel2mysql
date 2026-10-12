package com.excel2mysql.verify;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * class 主版本闸门：高版本 JDK 上开发一切正常，字节码却可能是 53+，
 * 到随包的 JRE 8u202 上才炸 UnsupportedClassVersionError。
 * 所以交付前必须逐 class 校验主版本 &lt;= 52，产物 jar 与每个依赖 jar 都要过。
 *
 * 用法：java com.excel2mysql.verify.ClassVersionAudit &lt;路径&gt;...
 * 路径可以是 jar 文件、class 目录，或装着依赖 jar 的目录。
 */
public final class ClassVersionAudit {

    private static final int MAX_MAJOR = 52;

    private static final class Row {
        private String name;
        private long size;
        private int checked;
        private int maxMajor;
        private int offenders;
        private String sample;
    }

    public static void main(String[] args) throws IOException {
        List<File> jars = new ArrayList<File>();
        List<File> classDirs = new ArrayList<File>();
        for (String arg : args) {
            collect(new File(arg), jars, classDirs);
        }
        if (jars.isEmpty() && classDirs.isEmpty()) {
            System.out.println("x [ERROR] CLASS-VERSION | 没有可检查的 jar 或 class 目录，参数是："
                    + java.util.Arrays.toString(args));
            System.exit(2);
        }

        List<Row> rows = new ArrayList<Row>();
        for (File dir : classDirs) {
            Row row = new Row();
            row.name = dir.getPath();
            scanTree(dir, row);
            rows.add(row);
        }
        for (File jar : jars) {
            Row row = new Row();
            row.name = jar.getName();
            row.size = jar.length();
            scanJar(jar, row);
            rows.add(row);
        }

        Collections.sort(rows, new Comparator<Row>() {
            public int compare(Row a, Row b) {
                return Long.compare(b.size, a.size);
            }
        });

        int checked = 0;
        int bad = 0;
        long bytes = 0;
        for (Row r : rows) {
            checked += r.checked;
            bad += r.offenders;
            bytes += r.size;
        }
        System.out.println("- [INFO] CLASS-VERSION | 上限 " + MAX_MAJOR + " | class " + checked
                + " 个 | jar " + jars.size() + " 个 / " + (bytes / 1024) + " KB | 目录 " + classDirs.size() + " 个");
        for (Row r : rows) {
            System.out.println(String.format("%-36s %7d KB  max=%d  %s%s",
                    trim(r.name, 36), r.size / 1024, r.maxMajor,
                    r.offenders > 0 ? "超标 " + r.offenders + " 个 " : "OK ",
                    r.sample == null ? "" : r.sample));
        }
        if (bad > 0) {
            System.out.println("x [ERROR] CLASS-VERSION | " + bad + " 个 class 主版本高于 " + MAX_MAJOR
                    + "，随包 JRE 8u202 加载时会抛 UnsupportedClassVersionError");
            System.exit(1);
        }
        System.out.println("+ [OK] CLASS-VERSION | 全部 " + checked + " 个 class 主版本 <= " + MAX_MAJOR);
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n + 1, s.length());
    }

    private static void collect(File path, List<File> jars, List<File> classDirs) {
        if (!path.exists()) {
            System.out.println("! [WARN] CLASS-VERSION | 路径不存在，已跳过：" + path);
            return;
        }
        if (path.isFile()) {
            if (path.getName().toLowerCase().endsWith(".jar")) {
                jars.add(path);
            } else {
                System.out.println("! [WARN] CLASS-VERSION | 不是 jar，已跳过：" + path);
            }
            return;
        }
        File[] children = path.listFiles();
        if (children == null) {
            return;
        }
        boolean plainClassDir = false;
        for (File child : children) {
            if (child.isFile() && child.getName().endsWith(".class")) {
                plainClassDir = true;
            }
        }
        if (plainClassDir) {
            classDirs.add(path);
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, jars, classDirs);
            } else if (child.getName().toLowerCase().endsWith(".jar")) {
                jars.add(child);
            }
        }
    }

    private static void scanTree(File dir, Row row) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                scanTree(child, row);
            } else if (child.getName().endsWith(".class")) {
                feed(row, majorOf(fileHeader(child)), child.getName());
            }
        }
    }

    private static void scanJar(File jar, Row row) throws IOException {
        ZipFile z = new ZipFile(jar);
        try {
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry entry = en.nextElement();
                String name = entry.getName();
                // 多版本 jar 的 META-INF/versions/** 是给 9+ 读的，JDK 8 只看根目录；
                // 把它们算进闸门会让守卫永久变红，那是假警报，比没有守卫更糟
                if (!name.endsWith(".class") || name.startsWith("META-INF/versions/")
                        || name.equals("module-info.class")) {
                    continue;
                }
                feed(row, majorOf(jarHeader(z, entry)), name);
            }
        } finally {
            z.close();
        }
    }

    private static void feed(Row row, int major, String where) {
        row.checked++;
        if (major > row.maxMajor) {
            row.maxMajor = major;
        }
        if (major > MAX_MAJOR) {
            row.offenders++;
            if (row.sample == null) {
                row.sample = where;
            }
        }
    }

    private static byte[] jarHeader(ZipFile z, ZipEntry entry) {
        byte[] buf = new byte[8];
        InputStream in = null;
        try {
            in = z.getInputStream(entry);
            fill(in, buf);
        } catch (IOException e) {
            throw new IllegalStateException("读取 class 头失败：" + entry.getName(), e);
        } finally {
            close(in);
        }
        return buf;
    }

    private static byte[] fileHeader(File f) {
        byte[] buf = new byte[8];
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            raf.readFully(buf);
        } catch (IOException e) {
            throw new IllegalStateException("读取 class 头失败：" + f, e);
        } finally {
            close(raf);
        }
        return buf;
    }

    private static void close(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignored) {
            // 取证工具收尾，关不掉不影响已经读到的头
        }
    }

    private static void fill(InputStream in, byte[] buf) throws IOException {
        int got = 0;
        while (got < buf.length) {
            int n = in.read(buf, got, buf.length - got);
            if (n < 0) {
                break;
            }
            got += n;
        }
        if (got < buf.length) {
            throw new IOException("class 头不足 8 字节");
        }
    }

    private static int majorOf(byte[] head) {
        return ((head[6] & 0xFF) << 8) | (head[7] & 0xFF);
    }
}
