package com.excel2mysql.core;

import java.io.File;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 版本与构建信息的读取口，对应 appinfo.py。
 *
 * 提交号与构建日期必须在打包那一刻烧进产物：跑在别人机器上没有 .git，
 * 运行时再去查是查不到的。开发态没有这份资源也完全正常——现场问 git，
 * 问不到就老实显示"开发态（未打包）"，绝不编一个假提交号出来。
 */
public final class AppInfo {

    public static final String UNKNOWN = "未知";

    public String version = "";
    public String commit = UNKNOWN;
    public String branch = "";
    public boolean dirty;
    public String builtAt = UNKNOWN;
    /** bundle=打包时烧进来的，git=开发态现场取的，unknown=两者都没有。 */
    public String source = "unknown";

    private AppInfo() {
    }

    public static AppInfo load() {
        AppInfo info = new AppInfo();
        Properties p = new Properties();
        InputStream in = AppInfo.class.getResourceAsStream("/appinfo.properties");
        if (in != null) {
            try {
                p.load(in);
            } catch (Exception e) {
                p = new Properties();          // 读不动就按开发态处理，不影响主流程
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // 关不掉也不影响已经读到的值
                }
            }
        }
        if (p.getProperty("version") != null) {
            info.version = p.getProperty("version").trim();
            info.builtAt = toLocal(orUnknown(p.getProperty("builtAt")));
            info.commit = orUnknown(p.getProperty("commit"));
            info.branch = trim(p.getProperty("branch"));
            info.dirty = Boolean.parseBoolean(trim(p.getProperty("dirty")));
            info.source = "bundle";
            return info;
        }
        // 没有随包信息：开发态现场问 git
        info.builtAt = "开发态（未打包）";
        String here = projectDir();
        String commit = runGit(here, "rev-parse", "--short", "HEAD");
        if (commit.isEmpty()) {
            info.commit = "无 git 信息";
            return info;
        }
        info.commit = commit;
        info.branch = runGit(here, "rev-parse", "--abbrev-ref", "HEAD");
        info.dirty = !runGit(here, "status", "--porcelain", "-uno").isEmpty();
        info.source = "git";
        return info;
    }

    /** 版本只有一个来源：随包属性里的 project.version。 */
    public String versionText() {
        return version.isEmpty() ? UNKNOWN : version;
    }

    /** 提交号一行的展示文本：带上分支与"有未提交改动"提示，仍然只占一行。 */
    public String commitDisplay() {
        StringBuilder extra = new StringBuilder();
        if (!branch.isEmpty()) {
            extra.append(branch);
        }
        if (dirty) {
            if (extra.length() > 0) {
                extra.append("，");
            }
            extra.append("工作区有未提交改动");
        }
        return extra.length() == 0 ? commit : commit + "（" + extra + "）";
    }

    /** 窗口标题：带版本号，不点开任何对话框也能看见跑的是哪一版。 */
    public String titleText() {
        return "Excel → MySQL 批量导入工具 v" + versionText();
    }

    /**
     * Maven 打的时间戳固定是 UTC，而 Python 那边 `time.strftime` 打的是本机时间。
     * 不换算就等于同一个时刻在两条线上差 8 小时，用户对不上构建号。
     * 认不出来（不是那 19 个字符）就原样给出，绝不自编一个时间。
     */
    public static String toLocal(String utc) {
        if (utc == null || utc.length() != 19) {
            return utc;
        }
        java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        in.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        try {
            java.util.Date at = in.parse(utc);
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(at);
        } catch (java.text.ParseException e) {
            return utc;
        }
    }

    private static String orUnknown(String v) {
        String s = trim(v);
        return s.isEmpty() ? UNKNOWN : s;
    }

    private static String trim(String v) {
        return v == null ? "" : v.trim();
    }

    private static String projectDir() {
        try {
            File f = new File(AppInfo.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            // classes 目录往上退两层就是工程根（target/classes → target → java/）
            File base = f.isFile() ? f.getParentFile() : f;
            return base == null ? "." : base.getPath();
        } catch (Exception e) {
            return ".";
        }
    }

    /** 跑一条 git 命令拿输出；没有 git、不是仓库、超时都返回空串，不抛异常。 */
    private static String runGit(String cwd, String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "git";
            System.arraycopy(args, 0, cmd, 1, args.length);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(cwd));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] buf = readAll(p.getInputStream());
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroy();
                return "";
            }
            return p.exitValue() == 0 ? new String(buf, "UTF-8").trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) {
            out.write(chunk, 0, n);
        }
        in.close();
        return out.toByteArray();
    }
}
