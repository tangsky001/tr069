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
            if (!allTablesExist()) {
                for (String sql : SchemaScript.createStatements(TABLES)) {
                    jdbcTemplate.execute(sql);
                }
                log.info("远程运维表已补齐: {}", String.join(", ", TABLES));
            }
            // 存量库补列：建表只在「表不存在」时跑一次，后加的列老库没有
            addColumnIfMissing("ops_remote_config", "subdomain",
                    "VARCHAR(64) DEFAULT NULL COMMENT '内网穿透分配的随机二级域名前缀（10位[a-z0-9]）'");
            addColumnIfMissing("ops_remote_config", "instance_id",
                    "VARCHAR(64) DEFAULT NULL COMMENT 'ACS 实例唯一标识（UUID）'");
            addColumnIfMissing("ops_remote_config", "remote_enabled",
                    "TINYINT DEFAULT 0 COMMENT '是否启用内网穿透：0否 1是'");
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

    private void addColumnIfMissing(String table, String column, String definition) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema = DATABASE() AND table_name = ? "
                            + "AND column_name = ?",
                    Integer.class, table, column);
            if (count != null && count > 0) {
                return;
            }
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("远程运维表已补齐列: {}.{}", table, column);
        } catch (Exception e) {
            log.error("补齐 {}.{} 失败: {}", table, column, e.getMessage());
        }
    }
}
