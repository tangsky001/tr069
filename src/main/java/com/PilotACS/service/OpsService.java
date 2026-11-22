/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.599
 *
 */

package com.PilotACS.service;

import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.sql.DatabaseMetaData;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** 运维管理页环境信息 */
@Service
@RequiredArgsConstructor
public class OpsService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataSource dataSource;
    private final StringRedisTemplate redisTemplate;

    public Map<String, List<Map<String, Object>>> environment() {
        Map<String, List<Map<String, Object>>> data = new LinkedHashMap<>();
        data.put("system", systemRows());
        data.put("server", serverRows());
        data.put("database", databaseRows());
        data.put("redis", redisRows());
        data.put("git", gitRows());
        return data;
    }

    private List<Map<String, Object>> systemRows() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long usedMb = memory.getHeapMemoryUsage().getUsed() / 1024 / 1024;
        long totalMb = memory.getHeapMemoryUsage().getMax() / 1024 / 1024;
        long threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
        int cpus = Runtime.getRuntime().availableProcessors();
        long uptimeMin = runtime.getUptime() / 60000;
        String uptime = String.format("%d天%d小时%d分", uptimeMin / 1440, uptimeMin % 1440 / 60, uptimeMin % 60);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("操作系统", System.getProperty("os.name") + " " + System.getProperty("os.version")));
        rows.add(row("系统架构", System.getProperty("os.arch")));
        rows.add(row("JDK 版本", System.getProperty("java.version")));
        rows.add(row("JVM 内存", usedMb + "M / " + totalMb + "M"));
        rows.add(row("线程数", String.valueOf(threadCount)));
        rows.add(row("CPU 核心", String.valueOf(cpus)));
        rows.add(row("进程 PID", runtime.getName().split("@")[0]));
        rows.add(row("运行时长", uptime));
        return rows;
    }

    private List<Map<String, Object>> serverRows() {
        Properties git = gitProperties();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("服务名称", "PilotACS"));
        rows.add(row("服务版本", git.getProperty("git.build.version", "未知")));
        rows.add(row("Java Home", System.getProperty("java.home")));
        return rows;
    }

    private List<Map<String, Object>> databaseRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            rows.add(row("数据库类型", meta.getDatabaseProductName()));
            rows.add(row("数据库版本", meta.getDatabaseProductVersion()));
            rows.add(row("驱动版本", meta.getDriverVersion()));
        } catch (Exception e) {
            Logs.warn(Logs.Module.OPS, "获取数据库信息失败", e);
            rows.add(row("数据库状态", "不可用：" + e.getMessage()));
        }
        return rows;
    }

    private List<Map<String, Object>> redisRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            Properties info = redisTemplate.execute((RedisCallback<Properties>) conn -> conn.info("server"));
            String version = "未知";
            if (info != null && info.containsKey("redis_version")) {
                version = String.valueOf(info.getProperty("redis_version"));
            }
            rows.add(row("Redis 版本", version));
            rows.add(row("连接状态", "正常"));
        } catch (Exception e) {
            rows.add(row("连接状态", "不可用：" + e.getMessage()));
        }
        return rows;
    }

    private List<Map<String, Object>> gitRows() {
        Properties git = gitProperties();
        String commitTime = git.getProperty("git.commit.time", "");
        String timeText = "未知";
        if (!commitTime.isBlank()) {
            try {
                timeText = FMT.format(OffsetDateTime.parse(commitTime,
                        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")));
            } catch (Exception e) {
                try {
                    timeText = FMT.format(OffsetDateTime.parse(commitTime));
                } catch (Exception ignore) {
                    timeText = commitTime;
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("当前分支", git.getProperty("git.branch", "未知")));
        rows.add(row("提交标识", git.getProperty("git.commit.id.abbrev", "未知")));
        rows.add(row("构建时间", timeText));
        rows.add(row("Git 地址", git.getProperty("git.remote.origin.url", "未知")));
        return rows;
    }

    /** 读取 git.properties */
    private Properties gitProperties() {
        Properties props = new Properties();
        try (InputStream in = new ClassPathResource("git.properties").getInputStream()) {
            props.load(in);
        } catch (Exception e) {
            Logs.debug(Logs.Module.OPS, "git.properties 不存在", "原因", e.getMessage());
        }
        return props;
    }

    private Map<String, Object> row(String label, String value) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("label", label);
        r.put("value", value);
        return r;
    }
}
