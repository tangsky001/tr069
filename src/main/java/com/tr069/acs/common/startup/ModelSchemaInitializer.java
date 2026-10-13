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
 * 型号语义映射表的自动建表。
 *
 * <p>与能力树表同一套路：存量库里没有这三张表，启动阶段按脚本幂等补建，
 * 避免设备上线时才报「表不存在」。建表失败不阻断启动，型号映射只是增强能力。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelSchemaInitializer implements ApplicationRunner {

    private static final String SCRIPT = "db/model-mapping-schema.sql";

    private static final String TABLE = "cpe_model";

    private final JdbcTemplate jdbcTemplate;

    @Value("${acs.model.auto-create-table:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("型号映射表自动建表已关闭");
            return;
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() AND table_name = ?",
                    Integer.class, TABLE);
            if (count != null && count > 0) {
                log.info("型号映射表已存在，跳过建表");
                return;
            }
            for (String sql : loadStatements()) {
                jdbcTemplate.execute(sql);
            }
            log.info("型号映射表已补齐: cpe_model / model_param_mapping / model_object_mapping");
        } catch (Exception e) {
            log.error("型号映射表自动建表失败，请手工执行 classpath:{} : {}", SCRIPT, e.getMessage());
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
