package com.tr069.acs.common.startup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 配置意图模板表的自动建表。
 *
 * <p>与型号映射表同一套路：启动阶段按脚本幂等补建，建表失败不阻断启动。</p>
 *
 * <p>跑在 Bean 初始化阶段（InitializingBean）而非 ApplicationRunner：后者要等容器刷新结束
 * 才轮到，届时定时任务已在并发运行，表若晚于任务建出来就会被查成「不存在」。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigTemplateSchemaInitializer implements InitializingBean {

    private static final List<String> TABLES = List.of("config_template");

    private final JdbcTemplate jdbcTemplate;

    @Value("${acs.config-template.auto-create-table:true}")
    private boolean enabled;

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            log.info("配置意图模板表自动建表已关闭");
            return;
        }
        try {
            if (allTablesExist()) {
                log.info("配置意图模板表已存在，跳过建表");
                return;
            }
            for (String sql : SchemaScript.createStatements(TABLES)) {
                jdbcTemplate.execute(sql);
            }
            log.info("配置意图模板表已补齐: {}", String.join(", ", TABLES));
        } catch (Exception e) {
            log.error("配置意图模板表自动建表失败，请手工执行 classpath:{}（对应仓库 sql/schema.sql）: {}",
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
