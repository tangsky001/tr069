package com.tr069.acs.worklist.tpl.config;

import com.tr069.acs.worklist.service.WorklistRootResolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 模板库 SQLite 数据源与 JdbcTemplate 配置。
 *
 * <p>走独立的 {@link javax.sql.DataSource} 与 {@link JdbcTemplate} bean，不接主
 * MyBatis-Plus 数据源——模板库只有单文件、单写多读、低并发的特性，连连接池都省了。</p>
 *
 * <p><b>建表就在这里做，不放 {@code CommandLineRunner}</b>：那要等容器刷新完才轮到，
 * 期间任何一次模板库查询都会打到一个还没有表的库上。SQLite 连不存在的库会直接
 * 造出一个空文件，于是现象变成「文件在、表没有、查询报 no such table」，
 * 且很难看出连的到底是哪一份。放在 bean 创建时执行，任何 DAO 拿到
 * {@code JdbcTemplate} 时表必然已经就绪。</p>
 */
@Configuration
@EnableConfigurationProperties(Tr069TplProperties.class)
public class Tr069TplDataSourceConfig {

    private static final Logger LOG = LoggerFactory.getLogger(Tr069TplDataSourceConfig.class);

    private static final String SCHEMA = "classpath:db/schema-tr069tpl.sql";

    /**
     * 单连接 SQLite 数据源。SQLite 不需要连接池，文件锁本身就是协调机制。
     *
     * <p><b>路径必须经 {@link WorklistRootResolver#resolve(String)} 解析。</b>配置里写的是
     * 相对路径，按进程工作目录拼就会随启动目录漂移：从 {@code target/} 起 jar 时会去找
     * {@code target/configuration/}，而配置根目录实际在项目根的 {@code configuration/}
     * ——SQLite 不负责建父目录，直接抛 {@code path to '...' does not exist}，
     * 表现为「保存适配就报拿不到 JDBC 连接」。这里统一锚定到配置根目录的同级，
     * 并在连接前把父目录建出来，首次启动也不会因为目录不存在而炸。</p>
     */
    @Bean
    public SQLiteDataSource tr069TplDataSource(Tr069TplProperties props,
                                               WorklistRootResolver rootResolver) {
        Path dbFile = rootResolver.resolve(props.getDbPath());
        try {
            Path parent = dbFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("模板库目录无法创建: " + dbFile.getParent()
                    + " —— 请检查 tr069tpl.db-path 与启动账号的写权限", e);
        }
        SQLiteDataSource ds = new SQLiteDataSource();
        // 不开只读：模板写入只在点「保存适配」时发生（低频），但要支持 upsert
        ds.setUrl("jdbc:sqlite:" + dbFile);
        return ds;
    }

    /**
     * 模板库专用 JdbcTemplate，与主库（MyBatis-Plus）完全隔离。
     *
     * <p>建库顺序：先切 WAL / 设 busy_timeout，再建表，最后把「连的是哪个文件、
     * 几张表、几个已适配型号」打进日志——这类装配问题靠设备上报失败才发现太晚了。</p>
     */
    @Bean
    public JdbcTemplate tr069TplJdbcTemplate(SQLiteDataSource tr069TplDataSource,
                                             Tr069TplProperties props,
                                             WorklistRootResolver rootResolver,
                                             ResourceLoader resourceLoader) {
        JdbcTemplate t = new JdbcTemplate(tr069TplDataSource);
        String absPath;
        try {
            // 与数据源同一套解析规则，日志里打印的必须是实际连上的那一份
            absPath = rootResolver.resolve(props.getDbPath()).toString();
        } catch (Exception e) {
            absPath = props.getDbPath();
        }
        try {
            t.execute("PRAGMA journal_mode=WAL");
            t.execute("PRAGMA busy_timeout=5000");
        } catch (Exception e) {
            // 首次创建文件时某些 PRAGMA 不一定回显，无害
            LOG.warn("模板库 PRAGMA 设置时遇到非致命异常: {}", e.getMessage());
        }
        initSchema(t, tr069TplDataSource, resourceLoader);
        migrateColumns(t);
        logState(t, absPath);
        return t;
    }

    /**
     * 建表。脚本里每张表都是 {@code CREATE TABLE IF NOT EXISTS}，幂等可反复执行。
     *
     * <p>建不出来必须吼出来，而不是等第一次下发时以「查不到型号」的面目出现。</p>
     */
    private void initSchema(JdbcTemplate t, javax.sql.DataSource ds, ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(SCHEMA);
        if (!resource.exists()) {
            LOG.error("模板库 schema 脚本未找到: {} —— 模板库不可用，型号适配与下发都会失败", SCHEMA);
            return;
        }
        try {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(resource);
            populator.setIgnoreFailedDrops(true);
            populator.execute(ds);
        } catch (Exception e) {
            LOG.error("模板库建表失败: {} —— 模板库不可用，型号适配与下发都会失败", e.getMessage());
        }
    }

    /**
     * 存量模板库补列。
     *
     * <p>建表脚本对已有表不起作用——{@code CREATE TABLE IF NOT EXISTS} 看到表在就跳过，
     * 后加的列永远补不上。而模板库是随仓库走的便携库，现场那份常常比代码老，
     * 缺列的后果是「保存适配成功、下发时才报 no such column」，很难回查。</p>
     *
     * <p>SQLite 的 {@code ADD COLUMN} 同样没有 {@code IF NOT EXISTS}，
     * 只能先查 {@code PRAGMA table_info} 再决定，与主库补列一个套路。
     * 每次启动都跑一遍，成本是几次元数据查询。</p>
     */
    private void migrateColumns(JdbcTemplate t) {
        for (String[] col : EXTRA_COLUMNS) {
            addColumnIfMissing(t, col[0], col[1], col[2]);
        }
        backfillIpModePath(t);
        backfillBridgeWan(t);
    }

    /**
     * 存量型号补一条桥接模板行（{@code instance_no = 4}）。
     *
     * <p>桥接业务是后加的，之前适配过的型号只有 1/2/3 三行，下发桥接时直接报
     * 「WAN 模板里没有 WAN_BRIDGE 对应的连接」。这里按 DHCP 那行复制：对象与路径同构，
     * 只把连接类型换成 {@code IP_Bridged}、值留空——桥接没有地址获取方式与 NAT，
     * 复制过来的那些列不填，渲染时自然不会带。</p>
     *
     * <p>用复制而不是让现场重新适配，是因为重新适配会按当前能力树重写全部 WAN 路径，
     * 而现场可能已经手工校过；补一行只影响桥接本身。</p>
     */
    private void backfillBridgeWan(JdbcTemplate t) {
        try {
            int wanRows = t.update("""
                    INSERT INTO device_wan (id, device_id, instance_no, conn_type, action, verified,
                        object_path, path_enable, val_enable, path_conn_type, val_conn_type,
                        path_bind_port, val_bind_port, path_mtu, val_mtu, path_ip_mode, val_ip_mode)
                    SELECT (SELECT COALESCE(MAX(id), 0) FROM device_wan)
                             + ROW_NUMBER() OVER (ORDER BY src.device_id),
                        src.device_id, 4, 'Bridge', src.action, src.verified,
                        replace(src.object_path, 'WANPPPConnection.', 'WANIPConnection.'),
                        src.path_enable, src.val_enable,
                        replace(src.path_conn_type, 'WANPPPConnection.', 'WANIPConnection.'),
                        'IP_Bridged',
                        src.path_bind_port, src.val_bind_port,
                        src.path_mtu, src.val_mtu,
                        replace(src.path_ip_mode, 'WANPPPConnection.', 'WANIPConnection.'),
                        ''
                    FROM device_wan src
                    WHERE src.instance_no = 1
                      AND src.object_path IS NOT NULL AND src.object_path <> ''
                      AND NOT EXISTS (SELECT 1 FROM device_wan b
                                      WHERE b.device_id = src.device_id AND b.instance_no = 4)
                    """);
            int vlanRows = t.update("""
                    INSERT INTO vlan (id, device_id, wan_instance, action, verified,
                        path_vlan_id, val_vlan_id, path_vlan_mode, val_vlan_mode,
                        path_multicast, val_multicast, path_8021p, val_8021p)
                    SELECT (SELECT COALESCE(MAX(id), 0) FROM vlan)
                             + ROW_NUMBER() OVER (ORDER BY src.device_id),
                        src.device_id, 4, src.action, src.verified,
                        replace(src.path_vlan_id, 'WANPPPConnection.', 'WANIPConnection.'),
                        src.val_vlan_id, src.path_vlan_mode, src.val_vlan_mode,
                        src.path_multicast, src.val_multicast, src.path_8021p, src.val_8021p
                    FROM vlan src
                    WHERE src.wan_instance = 1
                      AND NOT EXISTS (SELECT 1 FROM vlan b
                                      WHERE b.device_id = src.device_id AND b.wan_instance = 4)
                    """);
            if (wanRows > 0 || vlanRows > 0) {
                LOG.info("模板库已补桥接模板行: wan={} 行, vlan={} 行", wanRows, vlanRows);
            }
        } catch (Exception e) {
            LOG.error("模板库补桥接模板行失败: {} —— 桥接下发会提示先重新适配", e.getMessage());
        }
    }

    /**
     * 存量 WAN 模板行补上 IP 模式路径。
     *
     * <p>列补上了不等于有值：路径是适配时写的，加这个字段之前适配过的型号
     * 那两列全是空，字段照样不下发，现象还是「模板选了 IPv4、设备上还是 IPv4/v6」。
     * 这里按 {@code object_path} 补出路径；<b>值仍然留空</b>＝不改设备现有地址族，
     * 只有运维在模板上明确选了才下发。</p>
     */
    private void backfillIpModePath(JdbcTemplate t) {
        try {
            int rows = t.update("UPDATE device_wan SET path_ip_mode = object_path || '{i}.' || 'X_CMCC_IPMode' "
                    + "WHERE (path_ip_mode IS NULL OR path_ip_mode = '') "
                    + "AND object_path IS NOT NULL AND object_path <> ''");
            if (rows > 0) {
                LOG.info("模板库已补 IP 模式路径: {} 行", rows);
            }
        } catch (Exception e) {
            LOG.error("模板库补 IP 模式路径失败: {} —— 该字段下发时会被跳过", e.getMessage());
        }
    }

    /** 表名、列名、列类型——补列的登记处，加新字段时在这里加一行。 */
    private static final List<String[]> EXTRA_COLUMNS = List.of(
            new String[]{"device_wan", "path_mtu", "TEXT"},
            new String[]{"device_wan", "val_mtu", "TEXT"},
            new String[]{"device_wan", "path_ip_mode", "TEXT"},
            new String[]{"device_wan", "val_ip_mode", "TEXT"});

    private void addColumnIfMissing(JdbcTemplate t, String table, String column, String type) {
        try {
            List<String> cols = t.query("PRAGMA table_info(" + table + ")",
                    (rs, n) -> rs.getString("name"));
            if (cols.contains(column)) {
                return;
            }
            t.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            LOG.info("模板库已补列: {}.{}", table, column);
        } catch (Exception e) {
            LOG.error("模板库补列失败: {}.{} —— 该字段下发时会被跳过: {}",
                    table, column, e.getMessage());
        }
    }

    /** 启动自检：把连的哪个文件、表齐不齐、有几个已适配型号一次说清。 */
    private void logState(JdbcTemplate t, String absPath) {
        try {
            List<String> tables = t.queryForList(
                    "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", String.class);
            if (!tables.contains("device")) {
                LOG.error("模板库缺 device 主表，现有表={} file={}", tables, absPath);
                return;
            }
            Integer models = t.queryForObject("SELECT COUNT(*) FROM device", Integer.class);
            LOG.info("模板库就绪: file={}, 表={}张, 已适配型号={}个, journal_mode={}",
                    absPath, tables.size(), models,
                    t.queryForObject("PRAGMA journal_mode", String.class));
        } catch (Exception e) {
            LOG.error("模板库自检失败: file={}, err={}", absPath, e.getMessage());
        }
    }
}
