package com.tr069.acs.common.startup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** 远程运维配置表的自动建表 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsSchemaInitializer implements InitializingBean {

    private static final List<String> TABLES = List.of("ops_remote_config");

    private final JdbcTemplate jdbcTemplate;

    @Value("${acs.ops.auto-create-table:true}")
    private boolean enabled;

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            log.info("远程运维表自动建表已关闭");
            return;
        }
        try {
            if (allTablesExist()) {
                log.info("远程运维表已存在，跳过建表");
                return;
            }
            for (String sql : SchemaScript.createStatements(TABLES)) {
                jdbcTemplate.execute(sql);
            }
            log.info("远程运维表已补齐: {}", String.join(", ", TABLES));
        } catch (Exception e) {
            log.error("远程运维表自动建表失败，请手工执行 classpath:{}: {}", SchemaScript.RESOURCE, e.getMessage());
        }
    }

    private boolean allTablesExist() {
        for (String table : TABLES) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() AND table_name = ?",
                    Integer.class, table);
            if (count == null || count == 0) {
                return false;
            }
        }
        return true;
    }
}
