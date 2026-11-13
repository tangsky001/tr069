package com.tr069.acs.common.startup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** 纳管下发快照表的自动建表 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProvisionSchemaInitializer implements InitializingBean {

    private static final List<String> TABLES = List.of("cpe_provision_state");

    private final JdbcTemplate jdbcTemplate;

    @Value("${acs.device.provision.auto-create-table:true}")
    private boolean enabled;

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            log.info("纳管下发快照表自动建表已关闭");
            return;
        }
        try {
            if (allTablesExist()) {
                log.info("纳管下发快照表已存在，跳过建表");
                return;
            }
            for (String sql : SchemaScript.createStatements(TABLES)) {
                jdbcTemplate.execute(sql);
            }
            log.info("纳管下发快照表已补齐: {}", String.join(", ", TABLES));
        } catch (Exception e) {
            // 建表失败不阻断启动：快照查不到时按未下发处理，只是会多下发几轮
            log.error("纳管下发快照表自动建表失败，请手工执行 classpath:{}（对应仓库 sql/schema.sql）: {}",
                    SchemaScript.RESOURCE, e.getMessage());
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
