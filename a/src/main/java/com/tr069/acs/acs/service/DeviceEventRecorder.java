package com.tr069.acs.acs.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 设备事件与设备侧 syslog 的统一落库。
 * 两张表开机自建（ModelSchemaInitializer 不管这里，独立建表避免启动顺序依赖）：
 * - cpe_device_event：Inform 事件码（重启/上线/下发唤醒等）+ 会话异常审计；
 * - cpe_device_syslog：设备 UDP syslog 原文，排障金矿（重启、注册失败、WAN 断开设备侧早有日志）。
 */
@Slf4j
@Service
public class DeviceEventRecorder {

    private final JdbcTemplate jdbcTemplate;

    /** 设备 syslog UDP 监听端口，避开特权端口 514 */
    @Value("${acs.syslog.listen-port:1514}")
    private int syslogPort;

    /** 落库失败日志节流 */
    private volatile long lastFailLog = 0;

    private volatile boolean tablesReady = false;

    public DeviceEventRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 建表，幂等。失败只告警不抛：事件记录是锦上添花，不能拖垮 Inform 主流程 */
    private synchronized void ensureTables() {
        if (tablesReady) {
            return;
        }
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS cpe_device_event ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "sn VARCHAR(64) NOT NULL,"
                    + "event_type VARCHAR(32) NOT NULL,"
                    + "event_code VARCHAR(64),"
                    + "detail VARCHAR(1024),"
                    + "created_at DATETIME NOT NULL,"
                    + "KEY idx_sn (sn, id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS cpe_device_syslog ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "sn VARCHAR(64),"
                    + "source_ip VARCHAR(64),"
                    + "message TEXT,"
                    + "created_at DATETIME NOT NULL,"
                    + "KEY idx_sn (sn, id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            tablesReady = true;
        } catch (Exception e) {
            logOnce("设备事件表创建失败: " + e.getMessage());
        }
    }

    /** 记一条设备事件。type: inform / session / biz；sn 必填，事件按设备聚合查询 */
    public void recordEvent(String sn, String type, String eventCode, String detail) {
        if (sn == null || sn.isBlank()) {
            return;
        }
        ensureTables();
        if (!tablesReady) {
            return;
        }
        try {
            jdbcTemplate.update("INSERT INTO cpe_device_event (sn, event_type, event_code, detail, created_at) "
                            + "VALUES (?,?,?,?,?)",
                    sn, type, eventCode, truncate(detail, 1000), Timestamp.valueOf(LocalDateTime.now()));
        } catch (Exception e) {
            logOnce("设备事件落库失败: " + e.getMessage());
        }
    }

    /** 记一条设备侧 syslog */
    public void recordSyslog(String sn, String sourceIp, String message) {
        ensureTables();
        if (!tablesReady) {
            return;
        }
        try {
            jdbcTemplate.update("INSERT INTO cpe_device_syslog (sn, source_ip, message, created_at) "
                            + "VALUES (?,?,?,?)",
                    sn, sourceIp, message, Timestamp.valueOf(LocalDateTime.now()));
        } catch (Exception e) {
            logOnce("设备 syslog 落库失败: " + e.getMessage());
        }
    }

    /** 设备事件查询，倒序 limit 条 */
    public List<Map<String, Object>> events(String sn, int limit) {
        ensureTables();
        try {
            return jdbcTemplate.queryForList(
                    "SELECT id, event_type, event_code, detail, created_at FROM cpe_device_event "
                            + "WHERE sn = ? ORDER BY id DESC LIMIT ?",
                    sn, Math.min(Math.max(limit, 1), 500));
        } catch (Exception e) {
            log.warn("设备事件查询失败: sn={}, err={}", sn, e.getMessage());
            return List.of();
        }
    }

    /** 设备 syslog 查询，倒序 limit 条 */
    public List<Map<String, Object>> syslogs(String sn, int limit) {
        ensureTables();
        try {
            return jdbcTemplate.queryForList(
                    "SELECT id, sn, source_ip, message, created_at FROM cpe_device_syslog "
                            + "WHERE sn = ? ORDER BY id DESC LIMIT ?",
                    sn, Math.min(Math.max(limit, 1), 500));
        } catch (Exception e) {
            log.warn("设备 syslog 查询失败: sn={}, err={}", sn, e.getMessage());
            return List.of();
        }
    }

    /** 7 天前的事件与 syslog 定期清掉，表不无限膨胀 */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 10 * 60 * 1000L)
    public void cleanup() {
        ensureTables();
        if (!tablesReady) {
            return;
        }
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
            int a = jdbcTemplate.update("DELETE FROM cpe_device_event WHERE created_at < ?",
                    Timestamp.valueOf(cutoff));
            int b = jdbcTemplate.update("DELETE FROM cpe_device_syslog WHERE created_at < ?",
                    Timestamp.valueOf(cutoff));
            if (a + b > 0) {
                log.info("设备事件/syslog 过期清理: event={}, syslog={}", a, b);
            }
        } catch (Exception e) {
            log.warn("设备事件过期清理失败: {}", e.getMessage());
        }
    }

    public int syslogPort() {
        return syslogPort;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private void logOnce(String message) {
        long now = System.currentTimeMillis();
        if (now - lastFailLog > 30_000L) {
            lastFailLog = now;
            log.warn("{}（30 秒内不重复打印）", message);
        }
    }
}
