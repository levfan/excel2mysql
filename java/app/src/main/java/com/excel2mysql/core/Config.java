package com.excel2mysql.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * conn.ini 与命令行/环境变量的连接配置。
 *
 * 优先级：命令行参数 &gt; 环境变量 E2M_HOST/E2M_PORT/E2M_USER/E2M_PASS/E2M_DB &gt; 配置文件。
 * 配置文件里密码留空是刻意的——随包的 conn.ini 会被拷来拷去，口令应该走环境变量。
 */
public final class Config {

    public String host = "127.0.0.1";
    public int port = 3306;
    public String user = "root";
    public String password = "";
    public String database = "";

    /** 只记录来源，报错时能指出是哪个文件被写坏了。 */
    public String source = "内置默认值";

    private static final Map<String, String> ENV_KEYS = new LinkedHashMap<String, String>();

    static {
        ENV_KEYS.put("host", "E2M_HOST");
        ENV_KEYS.put("port", "E2M_PORT");
        ENV_KEYS.put("user", "E2M_USER");
        ENV_KEYS.put("password", "E2M_PASS");
        ENV_KEYS.put("database", "E2M_DB");
    }

    public Config() {
    }

    public static Config defaults() {
        Config c = new Config();
        c.applyEnv();
        return c;
    }

    /**
     * 自动发现：程序目录 → 当前目录。
     * 自动发现的文件写坏了只告警并退回默认值；显式 -c 指定的文件坏了必须报错，
     * 因为那是使用者的明确意图，静默兜底会让人以为配置生效了。
     */
    public static Config discover(List<String> candidatePaths) {
        for (String path : candidatePaths) {
            File f = new File(path);
            if (f.isFile()) {
                try {
                    return fromFile(path);
                } catch (Exception e) {
                    System.out.println("! [WARN] CONFIG | 自动发现的配置读不了，改用默认值："
                            + path + " — " + e.getMessage());
                    return defaults();
                }
            }
        }
        return defaults();
    }

    public static Config fromFile(String path) throws IOException {
        Config c = new Config();
        c.source = path;
        BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(path), Charset.forName("UTF-8")));
        String section = "";
        try {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.charAt(0) == ';' || t.charAt(0) == '#') {
                    continue;
                }
                if (t.charAt(0) == '[' && t.endsWith("]")) {
                    section = t.substring(1, t.length() - 1).trim();
                    continue;
                }
                int eq = t.indexOf('=');
                if (eq < 0) {
                    eq = t.indexOf(':');
                }
                if (eq < 0) {
                    continue;
                }
                String key = t.substring(0, eq).trim().toLowerCase();
                String value = t.substring(eq + 1).trim();
                if (section.isEmpty()) {
                    throw new IOException("配置里有不属于任何小节的键：" + key);
                }
                if (!"mysql".equals(section)) {
                    continue;
                }
                c.assign(key, value);
            }
        } finally {
            r.close();
        }
        c.applyEnv();
        return c;
    }

    private void assign(String key, String value) {
        if ("host".equals(key)) {
            host = value.isEmpty() ? host : value;
        } else if ("port".equals(key)) {
            if (!value.isEmpty()) {
                try {
                    port = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("port 不是数字：" + value);
                }
            }
        } else if ("user".equals(key)) {
            user = value.isEmpty() ? user : value;
        } else if ("password".equals(key)) {
            password = value;
        } else if ("database".equals(key)) {
            database = value;
        }
    }

    private void applyEnv() {
        assignFromEnv("host", ENV_KEYS.get("host"));
        assignFromEnv("port", ENV_KEYS.get("port"));
        assignFromEnv("user", ENV_KEYS.get("user"));
        assignFromEnv("password", ENV_KEYS.get("password"));
        assignFromEnv("database", ENV_KEYS.get("database"));
    }

    private void assignFromEnv(String key, String env) {
        String v = System.getenv(env);
        if (v != null && !v.isEmpty()) {
            assign(key, v);
        }
    }

    /** 目标库留空是有意为之：没指定库时调用方要直接报用法错误，不能跑半截。 */
    public boolean hasDatabase() {
        return database != null && !database.isEmpty();
    }

    public String jdbcUrl() {
        StringBuilder sb = new StringBuilder("jdbc:mysql://").append(host).append(':')
                .append(port).append('/');
        // 没选库时连过去只为列库；选了库就带上库名
        sb.append(hasDatabase() ? database : "");
        sb.append("?useSSL=false");
        sb.append("&allowPublicKeyRetrieval=true");
        sb.append("&characterEncoding=utf8");
        sb.append("&serverTimezone=Asia/Shanghai");
        // 不开这个开关，addBatch() 会被拆成一条一条发，批量导入的吞吐差一个量级
        sb.append("&rewriteBatchedStatements=true");
        return sb.toString();
    }

    @Override
    public String toString() {
        // 口令绝不进字符串：日志里会整行落盘，也可能被拷进工单
        return "Config{" + source + ", " + host + ":" + port + ", user=" + user
                + ", db=" + (hasDatabase() ? database : "(未指定)") + "}";
    }
}
