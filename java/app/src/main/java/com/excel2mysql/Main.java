package com.excel2mysql;

import com.excel2mysql.core.Infer;
import com.excel2mysql.core.Names;
import com.excel2mysql.core.PinyinLoader;
import com.excel2mysql.core.TableBuilder;
import com.excel2mysql.core.TableOps;
import com.excel2mysql.core.ZipImport;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令行入口。当前实现覆盖"解析到建表语句"这一段：
 * 递归找 Excel 与压缩包、逐表推断、批量前缀、打印 DDL。
 * 连库写入在下一条切片里接（Ddl 与按类型转值已单独对撞通过）。
 */
public final class Main {

    private static final String VERSION = "1.0.1";

    private static boolean verbose;

    public static void main(String[] argv) {
        List<String> paths = new ArrayList<String>();
        String prefix = "";
        boolean dryRun = true;
        boolean noSql = false;
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if ("--version".equals(a) || "-V".equals(a)) {
                System.out.println("excel2mysql " + VERSION + " (Java 1.8 线)");
                return;
            } else if ("--debug".equals(a)) {
                verbose = true;
            } else if ("--dry-run".equals(a)) {
                dryRun = true;
            } else if ("--prefix".equals(a)) {
                if (i + 1 >= argv.length) {
                    fail("参数错误：--prefix 需要一个值");
                }
                prefix = argv[++i];
            } else if ("--no-sql".equals(a)) {
                noSql = true;
            } else if ("-c".equals(a) || "--conn".equals(a)) {
                if (i + 1 >= argv.length) {
                    fail("参数错误：" + a + " 需要一个配置文件路径");
                }
                i++;
                if (!new File(argv[i]).isFile()) {
                    fail("参数错误：连接配置不存在：" + argv[i]);
                }
            } else if ("--execute".equals(a)) {
                fail("暂不支持：连库写入这一条还没接进 Java 线，当前只能 --dry-run 出建表语句");
            } else if (a.startsWith("-")) {
                fail("参数错误：未知选项 " + a);
            } else {
                paths.add(a);
            }
        }
        if (paths.isEmpty()) {
            System.out.println("用法: java -jar excel2mysql.jar <文件或目录>... [--prefix 前缀] [--dry-run]");
            System.out.println("      支持 .xlsx / .xls / .zip / 目录（递归），当前只做解析与建表语句输出");
            System.exit(2);
        }
        if (noSql) {
            System.out.println("! [WARN] CLI | --no-sql 在不连库的模式下没有额外含义，已忽略");
        }
        if (!dryRun) {
            fail("暂不支持：连库写入还没接入");
        }

        List<String> files = collect(paths);
        if (files.isEmpty()) {
            System.out.println("x [ERROR] CLI | 没有找到可导入的 Excel（.xlsx/.xls），检查给的路径");
            System.exit(3);
        }
        System.out.println("- [INFO] CLI | 动作 解析建表语句（dry-run）| 待处理 Excel " + files.size() + " 个");

        Names names = new Names(PinyinLoader.get());
        TableBuilder builder = new TableBuilder(new Infer(names), names);
        List<TableOps.Table> tables = new ArrayList<TableOps.Table>();
        Map<TableOps.Table, TableBuilder.Result> byTable =
                new LinkedHashMap<TableOps.Table, TableBuilder.Result>();
        int failed = 0;
        for (String path : files) {
            try {
                TableBuilder.Result r = builder.build(path);
                tables.add(r.table);
                byTable.put(r.table, r);
            } catch (Exception e) {
                failed++;
                // 异常常常没有 message（NPE 就是 null），只打 message 现场等于没日志
                System.out.println("x [ERROR] PARSE | " + baseName(path) + " | "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                if (verbose) {
                    e.printStackTrace(System.out);
                }
            }
        }
        if (!prefix.isEmpty()) {
            TableOps.applyPrefix(tables, prefix);
        } else {
            TableOps.makeUniqueNames(tables);
        }
        for (int i = 0; i < tables.size(); i++) {
            TableOps.Table t = tables.get(i);
            TableBuilder.Result r = byTable.get(t);
            System.out.println();
            System.out.println("- [INFO] TABLE | 文件 " + baseName(t.filepath) + " | 表 " + t.tableName
                    + " | 模式 " + t.source + " | 行数 " + r.rows.size());
            System.out.println(com.excel2mysql.core.Ddl.buildDdl(
                    t.tableName, t.tableComment, t.columns, false));
            if (r.pkReason != null && !r.pkReason.isEmpty()) {
                System.out.println("  -- 主键：" + r.pkReason);
            }
            for (Infer.Column c : t.columns) {
                if (c.typeNote != null) {
                    System.out.println("  ! [WARN] INFER | " + c.typeNote);
                }
            }
        }
        System.out.println();
        System.out.println((failed == 0 ? "+ [OK] CLI | " : "! [WARN] CLI | ")
                + "成功 " + tables.size() + " 张，解析失败 " + failed + " 个"
                + "（未连库，未建表，未写入任何数据）");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** 路径类型决定动作：目录递归找 Excel，zip 先解压再取里面的 Excel。 */
    private static List<String> collect(List<String> paths) {
        List<String> out = new ArrayList<String>();
        File work = new File(System.getProperty("java.io.tmpdir"),
                "excel2mysql-" + System.currentTimeMillis());
        for (String p : paths) {
            File f = new File(p);
            if (!f.exists()) {
                System.out.println("x [ERROR] CLI | 路径不存在：" + p);
                continue;
            }
            if (f.isDirectory()) {
                walk(f, out);
            } else if (f.getName().toLowerCase().endsWith(".zip")) {
                File dest = new File(work, "zip" + out.size());
                dest.mkdirs();
                try {
                    ZipImport.Result r = ZipImport.extract(f.getPath(), dest.getPath());
                    for (ZipImport.Entry e : r.files) {
                        out.add(e.path);
                    }
                    System.out.println("- [INFO] ZIP | " + f.getName() + " | 解出 Excel "
                            + r.files.size() + " 个 | 忽略非 Excel " + r.ignored.size()
                            + " 个 | 嵌套 zip 不递归 " + r.nestedZips + " 个");
                } catch (Exception e) {
                    System.out.println("x [ERROR] ZIP | " + f.getName() + " | " + e.getMessage());
                }
            } else if (com.excel2mysql.core.ZipImport.isExcel(f.getName())) {
                out.add(f.getPath());
            } else {
                System.out.println("! [WARN] CLI | 不是 Excel，已跳过：" + f.getName());
            }
        }
        return out;
    }

    private static void walk(File dir, List<String> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        List<File> list = new ArrayList<File>();
        for (File c : children) {
            list.add(c);
        }
        java.util.Collections.sort(list);
        for (File c : list) {
            if (c.isDirectory()) {
                walk(c, out);
            } else if (c.getName().startsWith("~$")) {
                // Excel 临时锁文件不是合法工作簿
            } else if (com.excel2mysql.core.ZipImport.isExcel(c.getName())) {
                out.add(c.getPath());
            }
        }
    }

    private static String baseName(String path) {
        String s = path.replace('\\', '/');
        int at = s.lastIndexOf('/');
        return at < 0 ? path : s.substring(at + 1);
    }

    private static void fail(String message) {
        System.out.println("x [ERROR] CLI | " + message);
        System.exit(2);
    }
}
