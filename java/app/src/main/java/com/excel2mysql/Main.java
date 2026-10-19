package com.excel2mysql;

import com.excel2mysql.core.Config;
import com.excel2mysql.core.Ddl;
import com.excel2mysql.core.DbClient;
import com.excel2mysql.core.Infer;
import com.excel2mysql.core.LogText;
import com.excel2mysql.core.Names;
import com.excel2mysql.core.PinyinLoader;
import com.excel2mysql.core.SqlRunner;
import com.excel2mysql.core.SqlScript;
import com.excel2mysql.core.TableBuilder;
import com.excel2mysql.core.TableOps;
import com.excel2mysql.core.ZipImport;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令行入口。动作由路径类型决定：给 .sql 就执行脚本，给 .zip 就展开解析 Excel，
 * 给目录就两件都干；排除靠 --no-sql / --no-excel。
 * 退出码与 Python 线同表：0 成功 / 1 用法 / 2 解析 / 3 连接 / 4 导入 / 5 脚本失败。
 */
public final class Main {

    static final int EXIT_OK = 0;
    static final int EXIT_USAGE = 1;
    static final int EXIT_PARSE = 2;
    static final int EXIT_CONNECT = 3;
    static final int EXIT_IMPORT = 4;
    static final int EXIT_SCRIPT = 5;

    private static final String VERSION = "1.0.1";

    public static void main(String[] argv) {
        // 图形界面不能走 System.exit：窗口刚立起来就被退出码 0 关掉
        if (argv.length > 0 && "--gui".equals(argv[0])) {
            String[] rest = new String[argv.length - 1];
            System.arraycopy(argv, 1, rest, 0, rest.length);
            GuiMain.main(rest);
            return;
        }
        System.exit(run(argv));
    }

    static int run(String[] argv) {
        List<String> paths = new ArrayList<String>();
        List<String> connFiles = new ArrayList<String>();
        String prefix = null;
        boolean dryRun = false;
        boolean checkOnly = false;
        boolean noSql = false;
        boolean noExcel = false;
        boolean verbose = false;

        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if ("--version".equals(a) || "-V".equals(a)) {
                System.out.println("excel2mysql " + VERSION + " (Java 1.8 线)");
                return EXIT_OK;
            } else if ("--help".equals(a) || "-h".equals(a)) {
                usage();
                return EXIT_OK;
            } else if ("--dry-run".equals(a)) {
                dryRun = true;
            } else if ("--check".equals(a)) {
                checkOnly = true;
            } else if ("--debug".equals(a)) {
                verbose = true;
            } else if ("--no-sql".equals(a)) {
                noSql = true;
            } else if ("--no-excel".equals(a)) {
                noExcel = true;
            } else if ("--prefix".equals(a)) {
                if (i + 1 >= argv.length) {
                    System.out.println("x [ERROR] USAGE | --prefix 需要一个值");
                    return EXIT_USAGE;
                }
                prefix = argv[++i];
            } else if ("-c".equals(a) || "--conn".equals(a)) {
                if (i + 1 >= argv.length) {
                    System.out.println("x [ERROR] USAGE | " + a + " 需要一个配置文件路径");
                    return EXIT_USAGE;
                }
                connFiles.add(argv[++i]);
            } else if (a.startsWith("-")) {
                System.out.println("x [ERROR] USAGE | 未知选项 " + a);
                usage();
                return EXIT_USAGE;
            } else {
                paths.add(a);
            }
        }
        if (noSql && noExcel) {
            System.out.println("x [ERROR] USAGE | 参数冲突：--no-sql 与 --no-excel 不能同时开");
            return EXIT_USAGE;
        }
        if (!checkOnly && paths.isEmpty()) {
            usage();
            return EXIT_USAGE;
        }
        if (checkOnly && !paths.isEmpty()) {
            System.out.println("x [ERROR] USAGE | --check 只测连接，不要同时给路径");
            return EXIT_USAGE;
        }

        Config cfg = loadConfig(connFiles);
        if (cfg == null) {
            return EXIT_USAGE;
        }
        if (!dryRun) {
            if (!cfg.hasDatabase()) {
                System.out.println("x [ERROR] USAGE | 没指定目标库：在 conn.ini 写 database，"
                        + "或设 E2M_DB，或用 --dry-run 只看建表语句");
                return EXIT_USAGE;
            }
        }
        String[] actions = describeActions(paths, noSql, noExcel, dryRun, checkOnly);
        System.out.println("- [INFO] 启动 | 版本 " + VERSION + " | 动作 " + actions[0]
                + " | 配置来源 " + cfg.source);
        System.out.println("- [INFO] 目标 | " + cfg);

        try {
            if (checkOnly) {
                return check(cfg);
            }
            return importPaths(cfg, paths, prefix, dryRun, noSql, noExcel, verbose);
        } catch (ConnectFailed e) {
            System.out.println("x [ERROR] CONNECT | " + e.getMessage());
            return EXIT_CONNECT;
        }
    }

    private static final class ConnectFailed extends RuntimeException {
        ConnectFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 日志首行就把意图写清楚：同一命令在不同目录里会做完全不同的事。 */
    private static String[] describeActions(List<String> paths, boolean noSql, boolean noExcel,
                                            boolean dryRun, boolean checkOnly) {
        if (checkOnly) {
            return new String[]{"仅测连接并列库"};
        }
        boolean excel = !noExcel;
        boolean sql = !noSql;
        StringBuilder sb = new StringBuilder();
        if (excel && sql) {
            sb.append("Excel 建表导入 + SQL 脚本执行");
        } else if (excel) {
            sb.append("Excel 建表导入");
        } else {
            sb.append("SQL 脚本执行");
        }
        if (dryRun) {
            sb.append("（dry-run，不连库）");
        }
        return new String[]{sb.toString()};
    }

    private static Config loadConfig(List<String> connFiles) {
        if (!connFiles.isEmpty()) {
            String path = connFiles.get(0);
            if (!new File(path).isFile()) {
                System.out.println("x [ERROR] USAGE | 连接配置不存在：" + path);
                return null;
            }
            try {
                return Config.fromFile(path);
            } catch (Exception e) {
                // 显式指定的文件坏了必须报错，静默兜底会让人以为配置生效了
                System.out.println("x [ERROR] USAGE | 连接配置读不了：" + path + " — "
                        + e.getMessage());
                return null;
            }
        }
        return Config.discover(defaultConfigPaths());
    }

    private static List<String> defaultConfigPaths() {
        List<String> out = new ArrayList<String>();
        String jarDir = jarDirectory();
        if (jarDir != null) {
            out.add(jarDir + File.separator + "conn.ini");
        }
        out.add(new File(System.getProperty("user.dir"), "conn.ini").getPath());
        return out;
    }

    /** 随包 conn.ini 放在 jar 同级；从 classpath 反推目录，取不到就只找当前目录。 */
    private static String jarDirectory() {
        try {
            String p = Main.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            File f = new File(java.net.URLDecoder.decode(p, "UTF-8"));
            return f.getParentFile() == null ? null : f.getParentFile().getPath();
        } catch (Exception e) {
            return null;
        }
    }

    private static int check(Config cfg) {
        DbClient db = open(cfg);
        try {
            List<String> dbs = db.listDatabases();
            System.out.println("+ [OK] CONNECT | " + cfg.host + ":" + cfg.port + " 用户 "
                    + cfg.user + " 可连，库 " + dbs.size() + " 个");
            for (String name : dbs) {
                System.out.println("      " + name);
            }
            return EXIT_OK;
        } catch (Exception e) {
            System.out.println("x [ERROR] CONNECT | 列库失败：" + e.getMessage());
            return EXIT_CONNECT;
        } finally {
            db.close();
        }
    }

    private static DbClient open(Config cfg) {
        try {
            return new DbClient(cfg);
        } catch (Exception e) {
            throw new ConnectFailed(e.getMessage() == null ? e.toString() : e.getMessage(), e);
        }
    }

    private static int importPaths(Config cfg, List<String> paths, String prefix, boolean dryRun,
                                   boolean noSql, boolean noExcel, boolean verbose) {
        Scan scan = collect(paths, noSql, noExcel);
        if (scan.excel.isEmpty() && scan.scripts.isEmpty()) {
            System.out.println("x [ERROR] PARSE | 没有找到可处理的文件"
                    + "（Excel: .xlsx/.xls/.zip，脚本: .sql）");
            return EXIT_PARSE;
        }
        System.out.println("- [INFO] 扫描 | Excel " + scan.excel.size() + " 个"
                + "（含压缩包展开）| SQL 脚本 " + scan.scripts.size() + " 个");

        List<TableBuilder.Result> built = new ArrayList<TableBuilder.Result>();
        Names names = new Names(PinyinLoader.get());
        TableBuilder builder = new TableBuilder(new Infer(names), names);
        int parseFailed = 0;
        for (String path : scan.excel) {
            try {
                built.add(builder.build(path));
            } catch (Exception e) {
                parseFailed++;
                System.out.println("x [ERROR] PARSE | " + baseName(path) + " | "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                if (verbose) {
                    e.printStackTrace(System.out);
                }
            }
        }
        List<TableOps.Table> tables = new ArrayList<TableOps.Table>();
        Map<TableOps.Table, TableBuilder.Result> byTable =
                new LinkedHashMap<TableOps.Table, TableBuilder.Result>();
        for (TableBuilder.Result r : built) {
            tables.add(r.table);
            byTable.put(r.table, r);
        }
        if (prefix != null && !prefix.isEmpty()) {
            TableOps.applyPrefix(tables, prefix);
        } else {
            TableOps.makeUniqueNames(tables);
        }

        // 脚本先读盘并按 --prefix 改写完，再打印——和 Python 一样：
        // 载入与改写发生在"打印会做什么"之前，dry-run 与真导入用的是同一份已改写的文本，
        // 不会出现预览里是 A 表名、落库时建出 B 表名。
        List<ScriptPlan> plans = new ArrayList<ScriptPlan>();
        if (!noSql) {
            for (File f : scan.scripts) {
                plans.add(planScript(f, prefix));
            }
        }

        if (dryRun) {
            for (TableOps.Table t : tables) {
                TableBuilder.Result r = byTable.get(t);
                System.out.println();
                System.out.println("- [INFO] TABLE | 文件 " + baseName(t.filepath) + " | 表 "
                        + t.tableName + " | 模式 " + t.source + " | 行数 " + r.rows.size());
                System.out.println(Ddl.buildDdl(t.tableName, t.tableComment, t.columns, false));
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
            System.out.println("+ [OK] DRY-RUN | 表 " + tables.size() + " 张，解析失败 "
                    + parseFailed + " 个（未连库，未建表，未写入任何数据）");
            // dry-run 也得把 .sql 会做什么说出来：现场靠这一眼核对前缀改写对不对，
            // 只报 Excel 的话，脚本要建哪些表得等真连库才知道。
            for (ScriptPlan p : plans) {
                if (p.statements.isEmpty()) {
                    continue;
                }
                System.out.println("- [INFO] 脚本预览 | " + p.name + " | 待执行 "
                        + p.statements.size() + " 条语句（本次没有连库）");
                for (int i = 0; i < p.statements.size() && i < 3; i++) {
                    detail(cut(p.statements.get(i), 200));
                }
                if (p.statements.size() > 3) {
                    detail("…（其余 " + (p.statements.size() - 3) + " 条未打印）");
                }
            }
            if (!plans.isEmpty()) {
                System.out.println("+ [OK] 脚本预览完成 | 共 " + plans.size()
                        + " 个脚本未执行（本次没有连接 MySQL）");
            }
            return parseFailed == 0 ? EXIT_OK : EXIT_PARSE;
        }

        DbClient db = open(cfg);
        int importFailed = 0;
        long totalRows = 0;
        try {
            for (TableOps.Table t : tables) {
                TableBuilder.Result r = byTable.get(t);
                try {
                    db.createTable(Ddl.buildDdl(t.tableName, t.tableComment, t.columns, false));
                    long written = db.insertData(t.tableName, t.columns, r.headers, r.rows,
                            new DbClient.WarnCallback() {
                                public void onWarn(String m) {
                                    System.out.println("  " + m);
                                }
                            }, null);
                    totalRows += written;
                    System.out.println("+ [OK] IMPORT | " + t.tableName + " | 建表 + 写入 "
                            + written + " 行");
                } catch (Exception e) {
                    importFailed++;
                    System.out.println("x [ERROR] IMPORT | `" + t.tableName + "` | "
                            + r.rows.size() + " 行数据未写入，本表事务已回滚 | "
                            + LogText.describe(e));
                    detail(LogText.ROLLBACK_NOTE);
                }
            }
        } finally {
            db.close();
        }

        int scriptFailed = 0;
        if (!plans.isEmpty()) {
            DbClient db2 = open(cfg);
            try {
                for (ScriptPlan p : plans) {
                    if (p.readFailed) {
                        scriptFailed++;
                        continue;
                    }
                    if (p.statements.isEmpty()) {
                        // 空脚本在 planScript 里已经报过"脚本为空"，Python 也不把它算进失败
                        continue;
                    }
                    SqlRunResult res = runPlan(db2, p);
                    if (res.ok) {
                        System.out.println("+ [OK] SCRIPT | " + p.name + " | 语句 "
                                + res.statements + " 条 | CREATE " + res.creates + " 个");
                    } else {
                        scriptFailed++;
                        System.out.println("x [ERROR] SCRIPT | " + p.name + " | 整文件已回滚 | "
                                + res.error);
                        detail(LogText.ROLLBACK_NOTE);
                    }
                }
            } finally {
                db2.close();
            }
        }

        System.out.println();
        int worst = EXIT_OK;
        if (parseFailed > 0) {
            worst = Math.max(worst, EXIT_PARSE);
        }
        if (importFailed > 0) {
            worst = Math.max(worst, EXIT_IMPORT);
        }
        if (scriptFailed > 0) {
            worst = Math.max(worst, EXIT_SCRIPT);
        }
        String tag = worst == EXIT_OK ? "+ [OK]" : "x [ERROR]";
        System.out.println(tag + " 汇总 | 表 " + (tables.size() - importFailed) + "/"
                + tables.size() + " 导入成功，写入 " + totalRows + " 行 | 解析失败 " + parseFailed
                + " | 脚本失败 " + scriptFailed + " | 退出码 " + worst);
        return worst;
    }

    private static final class SqlRunResult {
        boolean ok;
        int statements;
        int creates;
        String error;
    }

    /** 一个脚本读盘并改写完的结果。 */
    private static final class ScriptPlan {
        String name;
        String text;
        boolean readFailed;
        List<String> statements = new ArrayList<String>();
    }

    /**
     * 读脚本、报条数、按 --prefix 改写，全在连库之前做完。
     *
     * 前缀这一步必须做：Python 线会先把脚本里的表名整体改写再执行，
     * 少这一步的话同一个命令两条线建出来的表名不一样，现场按"加了前缀"去查表一张也找不到。
     */
    private static ScriptPlan planScript(File f, String prefix) {
        ScriptPlan p = new ScriptPlan();
        p.name = f.getName();
        String text;
        try {
            text = SqlRunner.readScript(f);
        } catch (java.io.IOException e) {
            System.out.println("x [ERROR] 脚本读取失败 | " + p.name + " | " + e.getMessage());
            p.readFailed = true;
            return p;
        }
        List<Map<String, Object>> created = SqlScript.extractCreateTables(text);
        int spots = 0;
        StringBuilder listed = new StringBuilder();
        for (Map<String, Object> c : created) {
            spots += ((Integer) c.get("count")).intValue();
            if (listed.length() > 0) {
                listed.append("、");
            }
            listed.append(c.get("name")).append("(").append(c.get("count")).append(")");
        }
        System.out.println("- [INFO] 脚本载入 | " + p.name + " | CREATE TABLE " + spots
                + " 处 / 表 " + created.size() + " 张："
                + (listed.length() == 0 ? "无" : listed.toString()));
        if (prefix != null && !prefix.trim().isEmpty()) {
            SqlScript.Rewrite rw = SqlScript.applyPrefix(text, prefix.trim());
            text = rw.text;
            StringBuilder changed = new StringBuilder();
            for (Map<String, Object> r : rw.renamed) {
                if (changed.length() > 0) {
                    changed.append("、");
                }
                changed.append(r.get("old")).append("→").append(r.get("new"))
                        .append("（").append(r.get("n")).append(" 处）");
            }
            System.out.println("+ [OK] 脚本前缀 | " + p.name + " | 改写 " + rw.renamed.size()
                    + " 张表：" + (changed.length() == 0 ? "无" : changed.toString()));
            if (!rw.skipped.isEmpty()) {
                System.out.println("- [INFO] 脚本前缀 | " + p.name + " | 未改写 "
                        + rw.skipped.size() + " 张（已带该前缀，或脚本里没有它的引用）："
                        + join(rw.skipped));
            }
        }
        p.text = text;
        p.statements = SqlScript.splitStatements(text);
        if (p.statements.isEmpty()) {
            System.out.println("! [WARN] 脚本为空 | " + p.name + " | 没有可执行的语句");
        }
        return p;
    }

    private static SqlRunResult runPlan(DbClient db, ScriptPlan p) {
        SqlRunResult out = new SqlRunResult();
        try {
            com.excel2mysql.core.SqlRunner.FileResult r =
                    SqlRunner.runText(db, p.name, p.text);
            out.ok = r.ok;
            out.statements = r.statements.size();
            out.creates = r.creates;
            out.error = r.error;
        } catch (Exception e) {
            out.ok = false;
            out.error = e.getMessage();
        }
        return out;
    }

    /** 与 Python 的 log.detail 同形：每条详情缩进六格跟在上一条记录后面。 */
    private static void detail(String text) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            System.out.println("      " + lines[i]);
        }
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static final class Scan {
        final List<String> excel = new ArrayList<String>();
        final List<File> scripts = new ArrayList<File>();
    }

    /** 路径类型决定动作：.sql 执行脚本，.zip/.xlsx/.xls 解析导入，目录两件都干。 */
    private static Scan collect(List<String> paths, boolean noSql, boolean noExcel) {
        Scan scan = new Scan();
        File work = new File(System.getProperty("java.io.tmpdir"),
                "e2m-" + System.currentTimeMillis());
        for (String p : paths) {
            File f = new File(p);
            if (!f.exists()) {
                System.out.println("x [ERROR] PATH | 路径不存在：" + p);
                continue;
            }
            if (f.isDirectory()) {
                walkDir(f, scan, noSql, noExcel, work);
            } else if (isSql(f.getName())) {
                if (noSql) {
                    System.out.println("! [WARN] PATH | --no-sql 已开，跳过 " + f.getName());
                } else {
                    scan.scripts.add(f);
                }
            } else if (f.getName().toLowerCase().endsWith(".zip")) {
                if (noExcel) {
                    System.out.println("! [WARN] PATH | --no-excel 已开，跳过 " + f.getName());
                } else {
                    expandZip(f, scan, work);
                }
            } else if (ZipImport.isExcel(f.getName())) {
                if (noExcel) {
                    System.out.println("! [WARN] PATH | --no-excel 已开，跳过 " + f.getName());
                } else {
                    scan.excel.add(f.getPath());
                }
            } else {
                System.out.println("! [WARN] PATH | 既不是 Excel 也不是 .sql，已跳过："
                        + f.getName());
            }
        }
        return scan;
    }

    private static void expandZip(File f, Scan scan, File work) {
        File dest = new File(work, "zip-" + Integer.toHexString(f.getAbsolutePath().hashCode()));
        dest.mkdirs();
        try {
            ZipImport.Result r = ZipImport.extract(f.getPath(), dest.getPath());
            for (ZipImport.Entry e : r.files) {
                scan.excel.add(e.path);
            }
            System.out.println("- [INFO] ZIP | " + f.getName() + " | 解出 Excel "
                    + r.files.size() + " 个 | 忽略非 Excel " + r.ignored.size()
                    + " 个 | 目录条目 " + r.dirs + " | 嵌套 zip 不递归 " + r.nestedZips);
        } catch (Exception e) {
            System.out.println("x [ERROR] ZIP | " + f.getName() + " | " + e.getMessage());
        }
    }

    private static void walkDir(File dir, Scan scan, boolean noSql, boolean noExcel, File work) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        List<File> list = new ArrayList<File>(Arrays.asList(children));
        java.util.Collections.sort(list);
        for (File c : list) {
            if (c.isDirectory()) {
                walkDir(c, scan, noSql, noExcel, work);
                continue;
            }
            if (c.getName().startsWith("~$")) {
                continue;
            }
            if (isSql(c.getName())) {
                if (!noSql) {
                    scan.scripts.add(c);
                }
            } else if (c.getName().toLowerCase().endsWith(".zip")) {
                if (!noExcel) {
                    expandZip(c, scan, work);
                }
            } else if (ZipImport.isExcel(c.getName())) {
                if (!noExcel) {
                    scan.excel.add(c.getPath());
                }
            }
        }
    }

    private static boolean isSql(String name) {
        return name.toLowerCase().endsWith(".sql");
    }

    private static String baseName(String path) {
        String s = path.replace('\\', '/');
        int at = s.lastIndexOf('/');
        return at < 0 ? path : s.substring(at + 1);
    }

    private static void usage() {
        System.out.println("用法: java -jar excel2mysql.jar <路径>... [选项]");
        System.out.println("      <路径> 可以是 .xlsx/.xls/.zip/.sql 文件或目录（递归）");
        System.out.println("      动作由路径类型决定：给 .sql 执行脚本，给 Excel/zip 建表导入，");
        System.out.println("      给目录两件都干；--no-sql / --no-excel 各自排除");
        System.out.println("选项:");
        System.out.println("  --dry-run          只打印建表语句与脚本预览，不连库");
        System.out.println("  --check            只测连接并列出数据库");
        System.out.println("  --prefix 文本      表名批量前缀");
        System.out.println("  -c, --conn 文件    指定 conn.ini；不给时自动读程序同级、再读当前目录");
        System.out.println("  --debug            解析失败时打印完整异常栈");
        System.out.println("  --version / --help 版本与用法");
        System.out.println("退出码: 0 成功 / 1 用法 / 2 解析失败 / 3 连接失败 / 4 导入失败 / 5 脚本失败");
    }
}
