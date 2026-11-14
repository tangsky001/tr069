package com.tr069.acs.exec.config;

import com.tr069.acs.common.startup.SchemaScript;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** 下发任务表的自动建表，与业务模板表同一套路：启动阶段幂等补建，失败不阻断启动 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceTaskSchemaInitializer implements InitializingBean {

    private static final List<String> TABLES = List.of("device_task");

    private final JdbcTemplate jdbcTemplate;

    @Value("${acs.exec.auto-create-table:true}")
    private boolean enabled;

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            log.info("下发任务表自动建表已关闭");
            return;
        }
        try {
            if (allTablesExist()) {
                log.info("下发任务表已存在，跳过建表");
                return;
            }
            for (String sql : SchemaScript.createStatements(TABLES)) {
                jdbcTemplate.execute(sql);
            }
            log.info("下发任务表已补齐: {}", String.join(", ", TABLES));
        } catch (Exception e) {
            log.error("下发任务表自动建表失败，请手工执行 classpath:{}（对应仓库 sql/schema.sql）: {}",
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
