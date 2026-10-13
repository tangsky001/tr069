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
 * 配置意图模板表的自动建表。
 *
 * <p>与型号映射表同一套路：启动阶段按脚本幂等补建，建表失败不阻断启动。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigTemplateSchemaInitializer implements ApplicationRunner {

    private static final String SCRIPT = "db/config-template-schema.sql";

    private static final String TABLE = "config_template";

    private final JdbcTemplate jdbcTemplate;

    @Value("${acs.config-template.auto-create-table:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("配置意图模板表自动建表已关闭");
            return;
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() AND table_name = ?",
                    Integer.class, TABLE);
            if (count != null && count > 0) {
                log.info("配置意图模板表已存在，跳过建表");
                return;
            }
            for (String sql : loadStatements()) {
                jdbcTemplate.execute(sql);
            }
            log.info("配置意图模板表已补齐: config_template");
        } catch (Exception e) {
            log.error("配置意图模板表自动建表失败，请手工执行 classpath:{} : {}", SCRIPT, e.getMessage());
        }
    }

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
