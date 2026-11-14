package com.tr069.acs.common.startup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** 型号语义映射表的自动建表 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelSchemaInitializer implements InitializingBean {

    /** 主表，补列时用 */
    private static final String TABLE = "cpe_model";

    private static final List<String> TABLES =
            List.of("cpe_model", "model_param_mapping", "model_object_mapping");

    private final JdbcTemplate jdbcTemplate;

    @Value("${acs.model.auto-create-table:true}")
    private boolean enabled;

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            log.info("型号映射表自动建表已关闭");
            return;
        }
        try {
            if (allTablesExist()) {
                log.info("型号映射表已存在，跳过建表");
            } else {
                for (String sql : SchemaScript.createStatements(TABLES)) {
                    jdbcTemplate.execute(sql);
                }
                log.info("型号映射表已补齐: {}", String.join(" / ", TABLES));
            }
            // 存量库补列与是否建过表无关，每次启动都确认一遍
            addColumnsIfMissing();
        } catch (Exception e) {
            log.error("型号映射表自动建表失败，请手工执行 classpath:{}（对应仓库 sql/schema.sql）: {}",
                    SchemaScript.RESOURCE, e.getMessage());
        }
    }

    /** 三张表都得在：只查主表的话，主表已存在而映射表缺失时会被判定成「已存在」直接跳过 */
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

    /** 存量库补列：后加到 cpe_model 上的字段，已建表的现场不会自动拥有它们 */
    private void addColumnsIfMissing() {
        for (String[] col : EXTRA_COLUMNS) {
            addColumnIfMissing(TABLE, col[0], col[1]);
        }
    }

    /** 列名 -> 建列语句片段 */
    private static final List<String[]> EXTRA_COLUMNS = List.of(
            new String[]{"root_node", "VARCHAR(64) NOT NULL DEFAULT 'InternetGatewayDevice.' "
                    + "COMMENT '样机的根数据模型节点：Device.(TR-181) / InternetGatewayDevice.(TR-098)'"},
            new String[]{"cap_lan_ports", "VARCHAR(16) DEFAULT NULL "
                    + "COMMENT '适配申报网口数量：无/1/2/3/4-8'"},
            new String[]{"cap_lan_speed", "VARCHAR(16) DEFAULT NULL "
                    + "COMMENT '适配申报网口速率：无/自动/100M/1000M/2500M'"},
            new String[]{"cap_sip_count", "VARCHAR(16) DEFAULT NULL "
                    + "COMMENT '适配申报SIP数量：无/1/2/3/4-8'"},
            new String[]{"cap_wifi_spec", "VARCHAR(32) DEFAULT NULL "
                    + "COMMENT '适配申报WIFI规格：无/2.4G/2.4G+5.8G'"},
            new String[]{"adapt_path", "VARCHAR(255) DEFAULT NULL "
                    + "COMMENT '适配状态文件(yml)相对路径，adapted=true 才算已适配'"});

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
            log.info("型号档案已补齐列: {}.{}", table, column);
        } catch (Exception e) {
            log.error("补齐 {}.{} 失败，相关能力降级: {}", table, column, e.getMessage());
        }
    }

}
