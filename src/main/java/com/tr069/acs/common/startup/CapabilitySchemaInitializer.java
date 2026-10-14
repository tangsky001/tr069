package com.tr069.acs.common.startup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 设备能力树表的自动建表。
 *
 * <p>能力采集是后加的功能，存量部署的库里没有 {@code cpe_parameter}。若等到设备上
 * 报时才报「表不存在」，采集已经跑了一半，排查成本高。启动阶段从唯一的
 * {@code sql/schema.sql} 里挑出这张表的定义补建，缺表就重跑，
 * 重复执行无害（CREATE TABLE IF NOT EXISTS）。</p>
 *
 * <p>跑在 Bean 初始化阶段（InitializingBean）而非 ApplicationRunner：后者要等容器刷新结束
 * 才轮到，届时定时任务已在并发运行，表若晚于任务建出来就会被查成「不存在」。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilitySchemaInitializer implements InitializingBean {

    private static final List<String> TABLES = List.of("cpe_parameter");

    private final JdbcTemplate jdbcTemplate;

    @Value("${acs.device.capability.auto-create-table:true}")
    private boolean enabled;

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            log.info("设备能力树表自动建表已关闭");
            return;
        }
        try {
            if (allTablesExist()) {
                log.info("设备能力树表已存在，跳过建表");
                return;
            }
            for (String sql : SchemaScript.createStatements(TABLES)) {
                jdbcTemplate.execute(sql);
            }
            log.info("设备能力树表已补齐: {}", String.join(", ", TABLES));
        } catch (Exception e) {
            // 建表失败不阻断启动：CWMP 交互与工单下发不依赖能力树
            log.error("设备能力树表自动建表失败，请手工执行 classpath:{}（对应仓库 sql/schema.sql）: {}",
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
