package com.PilotACS.worklist.config;

import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** 工单两张表自动补建（MySQL，DDL 与 sql/mysql.sql 保持一致，老库无感升级）。 */
@Component
@RequiredArgsConstructor
public class WorklistSchemaInitializer implements InitializingBean {

    private final JdbcTemplate jdbc;

    @Override
    public void afterPropertiesSet() {
        List<String> sqlList = allDdl();
        for (String sql : sqlList) {
            try {
                jdbc.execute(sql);
            } catch (Exception e) {
                Logs.warn(Logs.Module.CPE_WORKLIST, "工单表补建失败", "原因", e.getMessage());
            }
        }
        Logs.info(Logs.Module.CPE_WORKLIST, "工单表补建完成（共 " + sqlList.size() + " 张，已存在则跳过）");
    }

    private static List<String> allDdl() {
        return List.of(WORKLIST_DDL, WORKLIST_STEP_DDL);
    }

    private static final String WORKLIST_DDL = """
            CREATE TABLE IF NOT EXISTS `worklist` (
                `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '工单 id',
                `worklist_desc`     VARCHAR(128) DEFAULT NULL COMMENT '调用方流水号（幂等键）',
                `worklist_name`     VARCHAR(128) DEFAULT NULL COMMENT '工单名称',
                `worklist_type`     VARCHAR(16)  DEFAULT 'logic' COMMENT '工单类别：logic 管理端下发',
                `sn`                VARCHAR(64)  DEFAULT NULL COMMENT '设备序列号',
                `isp`               VARCHAR(16)  DEFAULT NULL COMMENT '运营商',
                `isp_version`       VARCHAR(16)  DEFAULT NULL COMMENT '运营商版本',
                `domain`            VARCHAR(64)  DEFAULT NULL COMMENT '设备类型（台账 device_type）',
                `status`            VARCHAR(16)  DEFAULT 'build' COMMENT '状态 build/standby/reserve/sending/success/fail/abnormal/expire',
                `rollback_flag`     INT          DEFAULT 0 COMMENT '失败是否回退',
                `parameters`        TEXT         COMMENT '下发参数（变量值 JSON）',
                `result`            TEXT         COMMENT '执行结果摘要',
                `username`          VARCHAR(64)  DEFAULT NULL COMMENT '创建人',
                `biz_type`          VARCHAR(16)  DEFAULT 'normal' COMMENT '工单类型 normal/reset/reboot',
                `group_id`          BIGINT       DEFAULT NULL COMMENT '设备分组 id',
                `room_no`           VARCHAR(64)  DEFAULT NULL COMMENT '房间号（同步设备台账）',
                `extra`             VARCHAR(255) DEFAULT NULL COMMENT '扩展属性',
                `remark`            VARCHAR(255) DEFAULT NULL COMMENT '备注',
                `active`            INT          DEFAULT 1 COMMENT '是否激活：0 只建单不下发',
                `time_init`         DATETIME     DEFAULT NULL COMMENT '建单时间',
                `time_bind`         DATETIME     DEFAULT NULL COMMENT '绑定时间',
                `time_reserve`      DATETIME     DEFAULT NULL COMMENT '预约时间（满 10 秒下发）',
                `time_exec_start`   DATETIME     DEFAULT NULL COMMENT '执行开始时间',
                `time_exec_finish`  DATETIME     DEFAULT NULL COMMENT '执行完成时间',
                `created_at`        DATETIME     DEFAULT NULL COMMENT '创建时间',
                `updated_at`        DATETIME     DEFAULT NULL COMMENT '更新时间',
                PRIMARY KEY (`id`),
                KEY `idx_wl_sn` (`sn`),
                KEY `idx_wl_status` (`status`),
                KEY `idx_wl_desc` (`worklist_desc`)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '下发工单实例'
            """;

    private static final String WORKLIST_STEP_DDL = """
            CREATE TABLE IF NOT EXISTS `worklist_step` (
                `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '步骤 id',
                `worklist_id`      BIGINT       NOT NULL COMMENT '所属工单 id',
                `step_index`       INT          DEFAULT 0 COMMENT '步骤序号，从 0 开始',
                `comment`          VARCHAR(255) DEFAULT NULL COMMENT '步骤说明',
                `rpc`              VARCHAR(64)  DEFAULT NULL COMMENT '执行方式：CWMP 方法 / IntentDelivery / BizTemplate',
                `request_args`     MEDIUMTEXT   COMMENT '下发参数 JSON',
                `response_content` MEDIUMTEXT   COMMENT '设备返回结果 JSON',
                `status`           VARCHAR(16)  DEFAULT 'init' COMMENT '状态 init/success/fail/skip',
                `error_msg`        VARCHAR(512) DEFAULT NULL COMMENT '错误信息',
                `duration_ms`      BIGINT       DEFAULT NULL COMMENT '耗时（毫秒）',
                `created_at`       DATETIME     DEFAULT NULL COMMENT '创建时间',
                PRIMARY KEY (`id`),
                KEY `idx_ws_wl` (`worklist_id`)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工单执行步骤'
            """;
}
