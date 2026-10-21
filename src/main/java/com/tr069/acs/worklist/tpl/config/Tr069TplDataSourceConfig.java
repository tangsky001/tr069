package com.tr069.acs.worklist.tpl.config;

import com.tr069.acs.common.log.AcsCoreLog;
import com.tr069.acs.common.log.LogCategory;
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
                                             ResourceLoader resourceLoader,
                                             AcsCoreLog coreLog) {
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
        logState(t, absPath, coreLog);
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

    /** 启动自检：把连的哪个文件、表齐不齐、有几个已适配型号一次说清。 */
    private void logState(JdbcTemplate t, String absPath, AcsCoreLog coreLog) {
        try {
            List<String> tables = t.queryForList(
                    "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", String.class);
            if (!tables.contains("device")) {
                coreLog.entry(LogCategory.STARTUP).bizKey("模板库").result(false)
                        .with("file", absPath).with("现有表", tables)
                        .error("模板库缺 device 主表，型号适配与下发都会失败");
                return;
            }
            Integer models = t.queryForObject("SELECT COUNT(*) FROM device", Integer.class);
            coreLog.entry(LogCategory.STARTUP).bizKey("模板库").result(true)
                    .with("file", absPath).with("表数", tables.size())
                    .with("已适配型号", models)
                    .with("journal_mode", t.queryForObject("PRAGMA journal_mode", String.class))
                    .info("模板库就绪");
        } catch (Exception e) {
            coreLog.entry(LogCategory.STARTUP).bizKey("模板库").result(false)
                    .with("file", absPath).error("模板库自检失败: " + e.getMessage());
        }
    }
}
