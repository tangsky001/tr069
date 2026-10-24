package com.tr069.acs.olt.config;

import com.tr069.acs.common.startup.SchemaScript;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OLT 三张表的自动补建与字段补齐。
 *
 * <p>存量部署的库里没有 OLT 台账，从唯一的 {@code sql/schema.sql} 里挑出这几张表补建；
 * 已经建过表但后续加字段（背板型号、板卡类别等）的库，在这里按列补 ALTER。
 * 建表失败不阻断启动（设备侧 TR-069 不依赖 OLT 模块）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OltSchemaInitializer implements InitializingBean {

    private static final List<String> TABLES =
            List.of("olt_device", "olt_board", "olt_sync_record");

    /** 后续版本新增的列：{表, 列, 列定义}，缺哪列补哪列 */
    private static final String[][] EXPECTED_COLUMNS = {
            {"olt_device", "patch_version", "VARCHAR(64) DEFAULT NULL COMMENT '补丁版本，如 SPC107'"},
            {"olt_device", "backplane", "VARCHAR(64) DEFAULT NULL COMMENT '背板/主板型号，如 H802MABO'"},
            {"olt_device", "mac_address", "VARCHAR(32) DEFAULT NULL COMMENT '主控 MAC'"},
            {"olt_device", "uptime", "VARCHAR(64) DEFAULT NULL COMMENT '运行时长'"},
            {"olt_device", "vlan_json", "LONGTEXT DEFAULT NULL COMMENT 'VLAN 快照(JSON)'"},
            {"olt_board", "board_kind", "VARCHAR(16) DEFAULT NULL COMMENT '板卡类别'"},
            {"olt_board", "description", "VARCHAR(128) DEFAULT NULL COMMENT '板卡描述'"},
            {"olt_board", "ports_json", "LONGTEXT DEFAULT NULL COMMENT '端口光模块状态快照(JSON)'"}
    };

    private final JdbcTemplate jdbcTemplate;

    @Value("${acs.olt.auto-create-table:true}")
    private boolean enabled;

    @Override
    public void afterPropertiesSet() {
        if (!enabled) {
            log.info("OLT 表自动建表已关闭");
            return;
        }
        try {
            if (!allTablesExist()) {
                for (String sql : SchemaScript.createStatements(TABLES)) {
                    jdbcTemplate.execute(sql);
                }
                log.info("OLT 表已补齐: {}", String.join(", ", TABLES));
            } else {
                log.info("OLT 表已存在，跳过建表");
            }
            ensureColumns();
        } catch (Exception e) {
            log.error("OLT 表自动建表失败，请手工执行 classpath:{}（对应仓库 sql/schema.sql）: {}",
                    SchemaScript.RESOURCE, e.getMessage());
        }
    }

    /**
     * 老库升级：新增字段靠 ALTER 补，避免用户手工改表。
     */
    private void ensureColumns() {
        for (String[] spec : EXPECTED_COLUMNS) {
            if (columnExists(spec[0], spec[1])) {
                continue;
            }
            jdbcTemplate.execute("ALTER TABLE `" + spec[0] + "` ADD COLUMN `" + spec[1] + "` " + spec[2]);
            log.info("OLT 表结构补齐: {}.{}", spec[0], spec[1]);
        }
    }

    private boolean allTablesExist() {
        for (String table : TABLES) {
            if (!exists("information_schema.tables", "table_schema", "table_name", table)) {
                return false;
            }
        }
        return true;
    }

    private boolean columnExists(String table, String column) {
        return exists("information_schema.columns", "table_schema", "table_name", table, column);
    }

    private boolean exists(String catalog, String schemaColumn, String tableColumn, String table, String... column) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ").append(catalog)
                .append(" WHERE ").append(schemaColumn).append(" = DATABASE() AND ")
                .append(tableColumn).append(" = ?");
        Object[] args;
        if (column.length > 0) {
            sql.append(" AND column_name = ?");
            args = new Object[]{table, column[0]};
        } else {
            args = new Object[]{table};
        }
        Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, args);
        return count != null && count > 0;
    }
}
