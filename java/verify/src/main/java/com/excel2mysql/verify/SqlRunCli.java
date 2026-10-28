package com.excel2mysql.verify;

import com.excel2mysql.core.Config;
import com.excel2mysql.core.DbClient;
import com.excel2mysql.core.ImportParse;
import com.excel2mysql.core.LogText;
import com.excel2mysql.gui.SqlPage;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * SQL 执行任务体的真库回归：SqlPage.runScripts 就是界面上「执行脚本到当前库」那一步的函数体，
 * GUI 那条路在离屏自检里跑不了（要连库、模态框还会把自检自己锁死），所以在这儿用同一个函数跑。
 *
 * 断的是副作用：进度计数与语句条数逐一对得上、好脚本的行真读得回来、坏脚本整文件回滚。
 * 只断"没抛异常"等于没断——回滚没生效时语句照样一条条"执行成功"。
 *
 * 只动 e2msqlprobe 前缀的探针表；跑前跑后库里的表清单必须一张不多一张不少。
 * 口令只走环境变量 E2M_PASS，不落任何文件。
 */
public final class SqlRunCli {

    private static final String PROBE_A = "e2msqlprobe_ok";
    private static final String PROBE_B = "e2msqlprobe_bad";
    /** 外键那支用的两张表：父表会被脚本先删再建，子表整程不动。 */
    private static final String FK_CHILD = "e2msqlprobe_fkc";
    private static final String FK_PARENT = "e2msqlprobe_fkp";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 只记账不渲染的回报口：进度计数与日志都要能数出来。 */
    private static final class Recorder implements ImportParse.Sink {
        final List<String> lines = new ArrayList<String>();
        final List<String> errors = new ArrayList<String>();
        int total = -1;
        int advanced;

        @Override
        public void setTotal(int total) {
            this.total = total;
        }

        @Override
        public void step(String label) {
            advanced += 1;
        }

        @Override
        public void advance(int units, String label) {
            advanced += units;
        }

        @Override
        public void log(String text) {
            lines.add(text);
        }

        @Override
        public void logError(String text) {
            lines.add(text);
            errors.add(text);
        }

        @Override
        public boolean cancelled() {
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> problems = new ArrayList<String>();
        Config cfg = Config.discover(new ArrayList<String>());
        if (!cfg.hasDatabase()) {
            System.out.println("x [ERROR] SQL-RUN | 没指定目标库（E2M_DB），拒绝在未知库上执行脚本");
            System.exit(2);
        }
        System.out.println("- [INFO] SQL-RUN | 目标 " + cfg);

        File dir = Files.createTempDirectory("e2m-sqlrun").toFile();
        File good = new File(dir, "good.sql");
        File bad = new File(dir, "bad.sql");
        File dump = new File(dir, "dump.sql");
        String nl = String.valueOf((char) 10);
        Files.write(good.toPath(), (
                "CREATE TABLE " + PROBE_A + " (id INT PRIMARY KEY, name VARCHAR(20));" + nl
                        + "INSERT INTO " + PROBE_A + " VALUES (1, '甲');" + nl
                        + "INSERT INTO " + PROBE_A + " VALUES (2, '乙');" + nl).getBytes(UTF8));
        Files.write(bad.toPath(), (
                "CREATE TABLE " + PROBE_B + " (id INT PRIMARY KEY);" + nl
                        + "INSERT INTO " + PROBE_B + " VALUES (1);" + nl
                        + "INSERT INTO no_such_table_e2m VALUES (1);" + nl).getBytes(UTF8));
        // 现场那份 MariaDB 整库 dump 的形状：抬头 + 版本注释里的 SET + 自己写的 DROP
        Files.write(dump.toPath(), (
                "-- MariaDB dump 10.19  Distrib 10.11.16-MariaDB" + nl
                        + "/*!40101 SET NAMES utf8mb4 */;" + nl
                        + "/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS,"
                        + " FOREIGN_KEY_CHECKS=0 */;" + nl
                        + "DROP TABLE IF EXISTS `" + FK_PARENT + "`;" + nl
                        + "CREATE TABLE `" + FK_PARENT + "` (id INT PRIMARY KEY);" + nl
                        + "/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;" + nl
                        + "-- Dump completed on 2026-10-25 12:00:00" + nl).getBytes(UTF8));

        // 连不上就把原因说清楚退出：让异常栈糊在控制台上，等于这条闸门没跑过
        DbClient db;
        try {
            db = new DbClient(cfg);
        } catch (Exception ex) {
            System.out.println("x [ERROR] SQL-RUN | 连不上 " + cfg + "：" + LogText.describe(ex));
            System.exit(3);
            return;
        }
        Set<String> before = tables(db);
        for (String t : before) {
            if (t.startsWith("e2msqlprobe")) {
                System.out.println("x [ERROR] SQL-RUN | 库里已有同名探针表，先清掉再跑：" + t);
                System.exit(4);
            }
        }
        try {
            List<SqlPage.Src> items = new ArrayList<SqlPage.Src>();
            items.add(new SqlPage.Src(good.getPath(), new String(
                    Files.readAllBytes(good.toPath()), UTF8)));
            items.add(new SqlPage.Src(bad.getPath(), new String(
                    Files.readAllBytes(bad.toPath()), UTF8)));

            Recorder sink = new Recorder();
            SqlPage.RunResult res = SqlPage.runScripts(items, cfg, sink);

            // 计数：两个脚本各 3 条语句，进度必须一格不多一格不少地补齐（总数边跑边加会倒退）
            if (sink.total != 6) {
                problems.add("进度总数应为 6 条语句，实际 " + sink.total);
            }
            if (sink.advanced != 6) {
                problems.add("进度推进应为 6 格，实际 " + sink.advanced);
            }
            if (res.ok != 1 || res.fail != 1 || res.skipped != 0) {
                problems.add("收尾计数应为成功 1 失败 1，实际 成功 " + res.ok
                        + " 失败 " + res.fail + " 跳过 " + res.skipped);
            }
            if (res.cancelled || res.connFailed) {
                problems.add("没取消也没断连，结果却标了 cancelled=" + res.cancelled
                        + " connFailed=" + res.connFailed);
            }
            if (!contains(sink.lines, "[提交] good.sql：3 条语句已提交")) {
                problems.add("好脚本没报出提交了几条语句：" + tail(sink.lines));
            }
            if (!contains(sink.errors, "[执行失败] bad.sql 第 3/3 条语句报错")) {
                problems.add("坏脚本没定位到出错的那一条：" + tail(sink.errors));
            }
            // MySQL 错误码要原样带出来，只写 message 分不清是哪类失败
            if (!contains(sink.errors, "MySQL 错误码 1146")) {
                problems.add("坏脚本没带出 MySQL 错误码：" + tail(sink.errors));
            }

            // 落库取证：好脚本两行读得回来；坏脚本的 INSERT 要跟着事务回滚
            if (count(db, PROBE_A) != 2) {
                problems.add("好脚本应有 2 行，实际 " + count(db, PROBE_A));
            }
            if (tableExists(db, PROBE_B) && count(db, PROBE_B) != 0) {
                problems.add("坏脚本回滚没生效：" + PROBE_B + " 里有 " + count(db, PROBE_B) + " 行");
            }

            // ---- 带外键的整库 dump + 「覆盖已存在表」：现场那条 MySQL 3730 的复现 ----
            // 这一支跑的是用户实际走过的那条路（第二页的执行任务体），断的是落库副作用：
            // 版本注释 /*!40014 SET ... FOREIGN_KEY_CHECKS=0 */; 被当纯注释丢掉、或者
            // 补出来的 DROP 排在它前面，父表就删不掉——被挡住时那句 DROP 只是报错停下，
            // 所以查的是"父表真被删掉又重建（行数归零）、子表一行没动"。
            // 与 Python 侧 pack_test 的 G13 同一份夹具形状。
            try (Statement st = db.connection().createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS=0");
                st.execute("DROP TABLE IF EXISTS `" + FK_CHILD + "`");
                st.execute("DROP TABLE IF EXISTS `" + FK_PARENT + "`");
                st.execute("CREATE TABLE `" + FK_PARENT + "` (id INT PRIMARY KEY)");
                st.execute("CREATE TABLE `" + FK_CHILD + "` (id INT, pid INT,"
                        + " CONSTRAINT runcl_fk_ibfk_1 FOREIGN KEY (pid)"
                        + " REFERENCES `" + FK_PARENT + "` (id))");
                st.execute("INSERT INTO `" + FK_PARENT + "` VALUES (7)");
                st.execute("INSERT INTO `" + FK_CHILD + "` VALUES (1, NULL)");
                st.execute("SET FOREIGN_KEY_CHECKS=1");
            }
            db.connection().commit();
            String dumpText = new String(Files.readAllBytes(dump.toPath()), UTF8);
            com.excel2mysql.core.SqlScript.DropPrep prep =
                    com.excel2mysql.core.SqlScript.prependDropTables(dumpText);
            if (!prep.tables.isEmpty()) {
                problems.add("脚本自己已经写了 DROP 的表还是被补了一遍（补出来的那句排在"
                        + " dump 自带的 SET 之前，父表删不掉）：" + prep.tables);
            }
            List<SqlPage.Src> fkItems = new ArrayList<SqlPage.Src>();
            fkItems.add(new SqlPage.Src(dump.getPath(), prep.text));
            Recorder recFk = new Recorder();
            SqlPage.RunResult resFk = SqlPage.runScripts(fkItems, cfg, recFk);
            if (resFk.ok != 1 || resFk.fail != 0) {
                problems.add("带外键的整库 dump 在第二页跑失败：" + tail(recFk.errors));
            }
            if (tableExists(db, FK_PARENT) && count(db, FK_PARENT) != 0) {
                problems.add("父表没被删掉重建：" + FK_PARENT + " 里还剩 "
                        + count(db, FK_PARENT) + " 行");
            }
            if (count(db, FK_CHILD) != 1) {
                problems.add("子表该一行没动，实际 " + count(db, FK_CHILD) + " 行");
            }
        } finally {
            try (Statement st = db.connection().createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS=0");
                st.execute("DROP TABLE IF EXISTS `" + FK_CHILD + "`");
                st.execute("DROP TABLE IF EXISTS `" + FK_PARENT + "`");
                st.execute("SET FOREIGN_KEY_CHECKS=1");
            } catch (Exception ignore) {
                problems.add("外键探针表没清掉：" + FK_CHILD + " / " + FK_PARENT);
            }
            for (String t : new String[]{PROBE_A, PROBE_B}) {
                try (Statement st = db.connection().createStatement()) {
                    st.execute("DROP TABLE IF EXISTS `" + t + "`");
                } catch (Exception ignore) {
                    problems.add("探针表 " + t + " 没清掉");
                }
            }
            Set<String> after = tables(db);
            if (!before.equals(after)) {
                Set<String> diff = new TreeSet<String>(after);
                diff.removeAll(before);
                problems.add("跑完库里表清单变了，多出 " + diff + LogText.NL
                        + "    前后：" + before.size() + " -> " + after.size());
            }
            db.close();
            good.delete();
            bad.delete();
            dump.delete();
            dir.delete();
        }

        int bad0 = problems.size();
        for (String p : problems) {
            System.out.println("x [ERROR] SQL-RUN | " + p);
        }
        if (bad0 > 0) {
            System.out.println("x [ERROR] SQL-RUN | 真库执行回归未通过");
            System.exit(1);
        }
        System.out.println("+ [OK] SQL-RUN | 语句计数 6/6 对齐，好脚本落库 2 行、坏脚本整文件回滚、"
                + "带外键的整库 dump 在这条路上删得掉父表，探针表零残留");
    }

    private static boolean contains(List<String> lines, String needle) {
        for (String l : lines) {
            if (l.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String tail(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l.replace((char) 10, ' ')).append(" | ");
        }
        String s = sb.toString();
        return s.length() <= 300 ? s : s.substring(s.length() - 300);
    }

    /**
     * 回查前先收口这条连接上早先开始的读快照。
     *
     * 探针表是任务体自己那条连接建出来的；这条连接还停在建表之前的快照上，
     * 直接查那张刚出现的表，MariaDB 报的是 1023「Table definition has changed,
     * please retry transaction」——读不到证据，反而像产品出了问题。
     */
    private static void fresh(DbClient db) throws Exception {
        db.connection().commit();
    }

    private static Set<String> tables(DbClient db) throws Exception {
        fresh(db);
        Set<String> out = new LinkedHashSet<String>();
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery("SHOW TABLES")) {
            while (rs.next()) {
                out.add(rs.getString(1).toLowerCase());
            }
        }
        return out;
    }

    private static boolean tableExists(DbClient db, String name) throws Exception {
        return tables(db).contains(name.toLowerCase());
    }

    private static int count(DbClient db, String name) throws Exception {
        fresh(db);
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + name + "`")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private SqlRunCli() {
    }
}
