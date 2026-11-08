package com.tr069.acs.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** 运维环境总览：系统版本、服务器配置、数据库、Redis、Git，全部后端采集，前端只渲染 */
@Slf4j
@Service
public class OpsEnvironmentService {

    private final JdbcTemplate jdbcTemplate;

    private final JdbcTemplate tplJdbcTemplate;

    private final StringRedisTemplate stringRedisTemplate;

    public OpsEnvironmentService(JdbcTemplate jdbcTemplate,
                                 @Qualifier("tr069TplJdbcTemplate") JdbcTemplate tplJdbcTemplate,
                                 StringRedisTemplate stringRedisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.tplJdbcTemplate = tplJdbcTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 运维环境总览，按分组返回展示行 */
    public Map<String, List<Map<String, String>>> environment() {
        Map<String, List<Map<String, String>>> data = new LinkedHashMap<>();
        List<Map<String, String>> system = new ArrayList<>(systemRows());
        system.addAll(gitRows());
        data.put("system", system);
        data.put("server", serverRows());
        data.put("database", databaseRows());
        data.put("redis", redisRows());
        return data;
    }

    private List<Map<String, String>> systemRows() {
        List<Map<String, String>> rows = new ArrayList<>();
        // pom <version> 打包时由 git-commit-id-plugin 写进 git.properties 的 git.build.version
        rows.add(row("系统版本", gitProperties().getProperty("git.build.version")));
        rows.add(row("应用名称", "Pilot ACS"));
        return rows;
    }

    private List<Map<String, String>> serverRows() {
        Runtime runtime = Runtime.getRuntime();
        long startedAt = ManagementFactory.getRuntimeMXBean().getStartTime();
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("操作系统", System.getProperty("os.name") + " " + System.getProperty("os.version")));
        rows.add(row("系统架构", System.getProperty("os.arch")));
        rows.add(row("Java 版本", System.getProperty("java.version")
                + "（" + System.getProperty("java.vendor") + "）"));
        rows.add(row("JVM", System.getProperty("java.vm.name")));
        rows.add(row("处理器核数", String.valueOf(runtime.availableProcessors())));
        rows.add(row("JVM 内存上限", mb(runtime.maxMemory())));
        rows.add(row("JVM 已用内存", mb(runtime.totalMemory() - runtime.freeMemory())));
        rows.add(row("服务启动时间", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date(startedAt))));
        return rows;
    }

    private static String mb(long bytes) {
        return (bytes / 1024 / 1024) + " MB";
    }

    private List<Map<String, String>> databaseRows() {
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("业务库（MySQL）", jdbcVersion(jdbcTemplate)));
        rows.add(row("模板库（SQLite）", sqliteVersion()));
        return rows;
    }

    private String sqliteVersion() {
        try {
            return "SQLite " + tplJdbcTemplate.queryForObject("SELECT sqlite_version()", String.class);
        } catch (Exception e) {
            log.warn("读取模板库版本失败: {}", e.getMessage());
            return "连接失败";
        }
    }

    private String jdbcVersion(JdbcTemplate t) {
        try {
            var meta = t.getDataSource().getConnection().getMetaData();
            return meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
        } catch (Exception e) {
            log.warn("读取数据库版本失败: {}", e.getMessage());
            return "连接失败";
        }
    }

    private List<Map<String, String>> redisRows() {
        List<Map<String, String>> rows = new ArrayList<>();
        String version;
        try {
            version = stringRedisTemplate.execute(
                    (org.springframework.data.redis.core.RedisCallback<String>) conn -> {
                        Properties info = conn.serverCommands().info();
                        return info == null ? null : info.getProperty("redis_version");
                    });
            if (version == null || version.isBlank()) {
                version = "未返回版本";
            }
        } catch (Exception e) {
            log.warn("读取 Redis 版本失败: {}", e.getMessage());
            version = "连接失败";
        }
        rows.add(row("Redis 版本", version));
        rows.add(row("Redis 地址", stringRedisTemplate.getConnectionFactory() != null
                ? "已配置" : "未配置"));
        return rows;
    }

    private List<Map<String, String>> gitRows() {
        List<Map<String, String>> rows = new ArrayList<>();
        Properties gitProps = gitProperties();
        String branch = gitProps.getProperty("git.branch");
        String commit = gitProps.getProperty("git.commit.id");
        if (commit == null || commit.isBlank()) {
            commit = execGit("rev-parse", "--short", "HEAD");
        }
        if (branch == null || branch.isBlank()) {
            branch = execGit("rev-parse", "--abbrev-ref", "HEAD");
        }
        rows.add(row("Git 分支", branch == null ? "未知" : branch.trim()));
        rows.add(row("Git 提交", commit == null ? "未知" : commit.trim()));
        String remote = gitProps.getProperty("git.remote.origin.url");
        if (remote == null || remote.isBlank()) {
            remote = execGit("remote", "get-url", "origin");
        }
        rows.add(row("Git 地址", remote == null ? "未知" : remote.trim()));
        String commitTime = gitProps.getProperty("git.commit.time");
        if (commitTime != null && !commitTime.isBlank()) {
            rows.add(row("提交时间", commitTime.trim()));
        }
        return rows;
    }

    /** 打包时生成了 git.properties 就用它，没有再现场执行 git 命令 */
    private static Properties gitProperties() {
        Properties props = new Properties();
        try (InputStream in = OpsEnvironmentService.class.getClassLoader()
                .getResourceAsStream("git.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (Exception ignored) {
            // 没有这份文件很正常，退到 git 命令
        }
        return props;
    }

    private static String execGit(String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder()
                    .directory(Paths.get(System.getProperty("user.dir")).toFile())
                    .command(cmd)
                    .redirectErrorStream(true)
                    .start();
            boolean done = p.waitFor(3, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return null;
            }
            return new String(p.getInputStream().readAllBytes()).trim();
        } catch (Exception e) {
            log.debug("执行 git 命令失败: {}", e.getMessage());
            return null;
        }
    }

    private static Map<String, String> row(String label, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("label", label);
        map.put("value", value == null || value.isBlank() ? "未知" : value);
        return map;
    }
}
