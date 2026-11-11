package com.excel2mysql.core;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;

/**
 * 达梦（DM8）库的连接与目录读取。
 *
 * <p>驱动不随包分发（第三方许可与体积都不合适），改成运行时从程序目录
 * {@code drivers\*.jar} 现挂：用 URLClassLoader 装好，再直接拿那个 Driver 实例 connect，
 * 不经过 DriverManager——DriverManager 会按调用方类加载器过滤可见驱动，跨加载器注册的
 * 驱动在这儿根本取不到。
 *
 * <p>表结构只读达梦那几本 Oracle 兼容目录视图（ALL_TABLES / ALL_TAB_COLUMNS /
 * ALL_TAB_COMMENTS / ALL_COL_COMMENTS / ALL_CONSTRAINTS），库名、表名一律走绑定变量。
 */
public final class DmReader implements AutoCloseable {

    public static final int DEFAULT_PORT = 5236;
    /** 握手超时（毫秒）：驱动参数名 connectTimeout，实测支持。 */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;

    private static final String SQL_TABLES =
            "SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER = ? ORDER BY TABLE_NAME";
    private static final String SQL_COLUMNS =
            "SELECT COLUMN_NAME, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE, "
                    + "NULLABLE, DATA_DEFAULT, COLUMN_ID FROM ALL_TAB_COLUMNS "
                    + "WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY COLUMN_ID";
    private static final String SQL_TABLE_COMMENT =
            "SELECT COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER = ? AND TABLE_NAME = ?";
    private static final String SQL_COLUMN_COMMENTS =
            "SELECT COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS WHERE OWNER = ? AND TABLE_NAME = ?";
    private static final String SQL_PRIMARY_KEY =
            "SELECT CC.COLUMN_NAME FROM ALL_CONSTRAINTS C JOIN ALL_CONS_COLUMNS CC "
                    + "ON C.OWNER = CC.OWNER AND C.CONSTRAINT_NAME = CC.CONSTRAINT_NAME "
                    + "WHERE C.OWNER = ? AND C.TABLE_NAME = ? AND C.CONSTRAINT_TYPE = 'P' "
                    + "ORDER BY CC.POSITION";

    public static final class Conf {
        public String host = "127.0.0.1";
        public int port = DEFAULT_PORT;
        public String user = "SYSDBA";
        public String password = "";
        public String schema = "";
        /** 追加到达梦 URL 的驱动参数，例如 socketTimeout=8000；空则不加。 */
        public String params = "";

        public String jdbcUrl() {
            StringBuilder sb = new StringBuilder("jdbc:dm://").append(host.trim())
                    .append(':').append(port);
            List<String> kv = new ArrayList<String>();
            String sch = schema == null ? "" : schema.trim();
            if (!sch.isEmpty()) {
                kv.add("schema=" + sch);
            }
            String extra = params == null ? "" : params.trim();
            if (!extra.isEmpty()) {
                kv.add(extra.replaceFirst("^[?&]", ""));
            }
            // 没自己写 connectTimeout 就补一个：地址是不可达的主机时，驱动会一路等到
            // TCP 超时（实测黑洞要挂十几秒），这一页的进度框不该把人钉在那儿
            if (!kv.toString().contains("connectTimeout")) {
                kv.add("connectTimeout=" + DEFAULT_CONNECT_TIMEOUT_MS);
            }
            for (int i = 0; i < kv.size(); i++) {
                sb.append(i == 0 ? '?' : '&').append(kv.get(i));
            }
            return sb.toString();
        }
    }

    private final Connection conn;
    private final String schema;

    private DmReader(Connection conn, String schema) {
        this.conn = conn;
        this.schema = schema;
    }

    /** 驱动 jar 的落点：程序目录（jar/exe 同级）下的 drivers 文件夹。 */
    public static File driverDir() {
        String jarDir = Config.jarDirectory();
        File base = jarDir == null ? new File(".") : new File(jarDir);
        return new File(base, "drivers");
    }

    /** drivers 目录里现在有哪些 jar（按名字排，日志里要报得出名子）。 */
    public static List<String> driverJars() {
        List<String> out = new ArrayList<String>();
        File[] jars = driverDir().listFiles();
        if (jars != null) {
            for (File f : jars) {
                if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    out.add(f.getName());
                }
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    public static boolean driverPresent() {
        return !driverJars().isEmpty();
    }

    /** 没驱动时把下一步说全：去哪儿拿、放哪儿、要不要重启。 */
    public static String missingDriverHint() {
        return "没找到达梦 JDBC 驱动：把达梦安装目录 drivers/java 下的 DmJdbcDriver18.jar"
                + "（或同版本的达梦 JDBC 包）复制到 " + driverDir().getPath()
                + " 下面，然后重开程序再连。这一页的转换逻辑不依赖驱动，"
                + "只有「连达梦读结构」这一步需要它。";
    }

    public static DmReader open(Conf cfg) throws SQLException {
        String sch = cfg.schema == null ? "" : cfg.schema.trim();
        if (sch.isEmpty()) {
            throw new SQLException("模式（SCHEMA）为空：达梦的表按模式归口，"
                    + "顶部那格得填一个模式名，SYSDBA 装默认表时模式通常就是用户名。");
        }
        List<String> jars = driverJars();
        if (jars.isEmpty()) {
            throw new SQLException(missingDriverHint());
        }
        Driver driver = pickDriver(jars, cfg.jdbcUrl());
        Properties props = new Properties();
        props.setProperty("user", cfg.user == null ? "" : cfg.user.trim());
        props.setProperty("password", cfg.password == null ? "" : cfg.password);
        Connection c = driver.connect(cfg.jdbcUrl(), props);
        if (c == null) {
            throw new SQLException("驱动 " + driver.getClass().getName()
                    + " 不认这条连接串：" + cfg.jdbcUrl()
                    + "（drivers 目录里放的是不是达梦的 jar？或者主机端口写错了）");
        }
        c.setAutoCommit(true);
        return new DmReader(c, sch.toUpperCase(Locale.ROOT));
    }

    /** 从 drivers 目录里挑一个真能接这条 URL 的驱动。 */
    private static Driver pickDriver(List<String> jars, String url) throws SQLException {
        URL[] urls = new URL[jars.size()];
        File dir = driverDir();
        for (int i = 0; i < jars.size(); i++) {
            try {
                urls[i] = new File(dir, jars.get(i)).toURI().toURL();
            } catch (java.net.MalformedURLException e) {
                throw new SQLException("drivers 目录里的 jar 路径拼不出 URL："
                        + jars.get(i) + " — " + e.getMessage(), e);
            }
        }
        URLClassLoader loader = new URLClassLoader(urls, DmReader.class.getClassLoader());
        List<Driver> found = new ArrayList<Driver>();
        try {
            for (Driver d : ServiceLoader.<Driver>load(Driver.class, loader)) {
                found.add(d);
            }
        } catch (Throwable ignored) {
            // 老驱动包没注册 META-INF/services，接着走显式类名那一条
        }
        if (found.isEmpty()) {
            for (String cls : new String[] {"dm.jdbc.driver.DmDriver",
                                            "dm.jdbc.driver.DMDriver"}) {
                try {
                    found.add((Driver) Class.forName(cls, true, loader).newInstance());
                } catch (Throwable ignored) {
                    // 这个类名不在包里，换下一个候选
                }
            }
        }
        for (Driver d : found) {
            try {
                if (d.acceptsURL(url)) {
                    return d;
                }
            } catch (SQLException ignored) {
                // 有的驱动对 acceptsURL 会抛，跳过这一支
            }
        }
        throw new SQLException("drivers 目录里有 jar（" + jars + "），但里面没有任何驱动认这条 URL："
                + url + "。确认放的是达梦的 JDBC 驱动，且主机端口写得对。");
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            System.out.println("! [WARN] DM | 关闭达梦连接失败：" + e.getMessage());
        }
    }

    /** 服务端版本串；读不到不报错（V$VERSION 在部分权限下不可见）。 */
    public String serverVersion() {
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT BANNER FROM V$VERSION");
            try {
                ResultSet rs = ps.executeQuery();
                try {
                    return rs.next() ? rs.getString(1) : "";
                } finally {
                    rs.close();
                }
            } finally {
                ps.close();
            }
        } catch (SQLException e) {
            return "";
        }
    }

    public List<String> listTables() throws SQLException {
        List<String> out = new ArrayList<String>();
        PreparedStatement ps = conn.prepareStatement(SQL_TABLES);
        try {
            ps.setString(1, schema);
            ResultSet rs = ps.executeQuery();
            try {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
        return out;
    }

    /** 读一张表的结构（含表注释、列注释、主键），目录视图缺哪本就少哪本，不编。 */
    public DmMysql.Table readTable(String table) throws SQLException {
        DmMysql.Table t = new DmMysql.Table();
        t.schema = schema;
        t.name = table;
        t.comment = firstText(SQL_TABLE_COMMENT, table);
        Map<String, String> comments = new LinkedHashMap<String, String>();
        PreparedStatement ps = conn.prepareStatement(SQL_COLUMN_COMMENTS);
        try {
            ps.setString(1, schema);
            ps.setString(2, table);
            ResultSet rs = ps.executeQuery();
            try {
                while (rs.next()) {
                    comments.put(rs.getString(1), rs.getString(2));
                }
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
        ps = conn.prepareStatement(SQL_COLUMNS);
        try {
            ps.setString(1, schema);
            ps.setString(2, table);
            ResultSet rs = ps.executeQuery();
            try {
                while (rs.next()) {
                    DmMysql.Column c = new DmMysql.Column();
                    c.name = nz(rs.getString(1));
                    c.type = nz(rs.getString(2));
                    c.length = rs.getInt(3);
                    c.precision = rs.getInt(4);
                    c.scale = rs.getInt(5);
                    c.nullable = !"N".equalsIgnoreCase(nz(rs.getString(6)));
                    c.dbDefault = readDefault(rs);
                    String cm = comments.get(c.name);
                    c.comment = cm == null ? "" : cm;
                    t.columns.add(c);
                }
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
        ps = conn.prepareStatement(SQL_PRIMARY_KEY);
        try {
            ps.setString(1, schema);
            ps.setString(2, table);
            ResultSet rs = ps.executeQuery();
            try {
                while (rs.next()) {
                    t.primaryKeys.add(rs.getString(1));
                }
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
        return t;
    }

    /** DATA_DEFAULT 在部分驱动里是 CLOB，取不到就当没默认值，不能把整张表读失败。 */
    private static String readDefault(ResultSet rs) {
        try {
            String v = rs.getString(7);
            return v == null ? "" : v;
        } catch (SQLException e) {
            return "";
        }
    }

    private String firstText(String sql, String table) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(sql);
        try {
            ps.setString(1, schema);
            ps.setString(2, table);
            ResultSet rs = ps.executeQuery();
            try {
                return rs.next() ? nz(rs.getString(1)) : "";
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    /** 自检用：drivers 目录里到底有没有能用的东西，报一句人话。 */
    public static String describeDrivers() {
        List<String> jars = driverJars();
        if (jars.isEmpty()) {
            return "无（" + driverDir().getPath() + " 里没有 jar）";
        }
        return jars.size() + " 个：" + jars;
    }
}
