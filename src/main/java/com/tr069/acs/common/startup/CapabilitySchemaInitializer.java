package com.tr069.acs.common.startup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 设备能力树表的自动建表。
 *
 * <p>能力采集是后加的功能，存量部署的库里没有 {@code cpe_parameter}。若等到设备上
 * 报时才报「表不存在」，采集已经跑了一半，排查成本高。这里在启动阶段补建，
 * 与 sql/schema.sql 内容一致，重复执行无害（CREATE TABLE IF NOT EXISTS）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilitySchemaInitializer implements ApplicationRunner {

    private static final String SCRIPT = "db/capability-schema.sql";

    private static final String TABLE = "cpe_parameter";

    private final JdbcTemplate jdbcTemplate;

    @Value("${acs.device.capability.auto-create-table:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("设备能力树表自动建表已关闭");
            return;
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() AND table_name = ?",
                    Integer.class, TABLE);
            if (count != null && count > 0) {
                log.info("设备能力树表已存在，跳过建表");
                return;
            }
            for (String sql : loadStatements()) {
                jdbcTemplate.execute(sql);
            }
            log.info("设备能力树表已补齐: {}", TABLE);
        } catch (Exception e) {
            // 建表失败不阻断启动：CWMP 交互与工单下发不依赖能力树
            log.error("设备能力树表自动建表失败，请手工执行 sql/schema.sql 或 classpath:{} : {}",
                    SCRIPT, e.getMessage());
        }
    }

    /**
     * 读取建表脚本并按分号拆成单条语句，跳过注释行。
     */
    private List<String> loadStatements() throws Exception {
        ClassPathResource resource = new ClassPathResource(SCRIPT);
        String script;
        try (InputStream in = resource.getInputStream()) {
            script = FileCopyUtils.copyToString(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String sql = current.toString().trim();
                statements.add(sql.substring(0, sql.length() - 1));
                current.setLength(0);
            }
        }
        return statements;
    }
}
