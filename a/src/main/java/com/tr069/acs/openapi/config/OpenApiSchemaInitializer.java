package com.tr069.acs.openapi.config;

import com.tr069.acs.common.startup.SchemaScript;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** 北向事件推送两张表的自动建表，与其它自动建表同一套路 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenApiSchemaInitializer implements InitializingBean {

    private static final List<String> TABLES =
            List.of("openapi_webhook", "openapi_webhook_delivery");

    private final JdbcTemplate jdbcTemplate;

    @Value("${acs.openapi.auto-create-table:true}")
    private boolean enabled;

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            log.info("北向事件表自动建表已关闭");
            return;
        }
        try {
            if (allTablesExist()) {
                log.info("北向事件表已存在，跳过建表");
                return;
            }
            for (String sql : SchemaScript.createStatements(TABLES)) {
                jdbcTemplate.execute(sql);
            }
            log.info("北向事件表已补齐: {}", String.join(", ", TABLES));
        } catch (Exception e) {
            log.error("北向事件表自动建表失败，请手工执行 classpath:{}（对应仓库 sql/schema.sql）: {}",
                    SchemaScript.RESOURCE, e.getMessage());
        }
    }

    /** 两张表都得在：只查订阅表的话，若订阅表已存在而投递表缺失（手工建过、或上一版脚本只建了一半） */
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
