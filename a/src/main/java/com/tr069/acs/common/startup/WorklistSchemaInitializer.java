package com.tr069.acs.common.startup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 工单表的存量库补列：建单扩展字段是后加的，老库不会自动拥有 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorklistSchemaInitializer implements InitializingBean {

    /** 工单表，worklist 与 worklist_step 由部署脚本建，这里只负责补列 */
    private static final String TABLE = "worklist";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void afterPropertiesSet() {
        try {
            // 存量库补列：每次启动都确认一遍，缺哪条补哪条
            addColumnIfMissing("biz_type",
                    "VARCHAR(16) NOT NULL DEFAULT 'normal' COMMENT '工单类型 normal/reset/reboot'");
            addColumnIfMissing("group_id",
                    "BIGINT DEFAULT NULL COMMENT '设备分组 id'");
            addColumnIfMissing("room_no",
                    "VARCHAR(64) DEFAULT NULL COMMENT '房间号（建单录入，同步设备台账）'");
            addColumnIfMissing("extra",
                    "VARCHAR(255) DEFAULT NULL COMMENT '扩展属性'");
            addColumnIfMissing("remark",
                    "VARCHAR(512) DEFAULT NULL COMMENT '备注'");
            addColumnIfMissing("active",
                    "TINYINT NOT NULL DEFAULT 1 COMMENT '是否激活：0否 1是'");
        } catch (Exception e) {
            log.error("工单表补列失败，请手工执行 classpath:{}（对应仓库 sql/schema.sql）: {}",
                    SchemaScript.RESOURCE, e.getMessage());
        }
    }

    private void addColumnIfMissing(String column, String definition) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema = DATABASE() AND table_name = ? "
                            + "AND column_name = ?",
                    Integer.class, TABLE, column);
            if (count != null && count > 0) {
                return;
            }
            jdbcTemplate.execute("ALTER TABLE " + TABLE + " ADD COLUMN " + column + " " + definition);
            log.info("工单表已补齐列: {}.{}", TABLE, column);
        } catch (Exception e) {
            // 补列失败只是扩展字段存不下，不能连带把整个启动拖垮
            log.error("补齐 {}.{} 失败，建单扩展字段将不可用: {}", TABLE, column, e.getMessage());
        }
    }
}
