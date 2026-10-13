package com.tr069.acs.common.startup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库表结构启动自检。
 *
 * <p>建表脚本 sql/schema.sql 位于 jar 外部，首次部署若漏执行，应用仍会启动成功，
 * 直到设备上报时才抛出「表不存在」，排查成本高。这里在启动阶段做一次校验，
 * 缺失时直接给出可执行 sql/schema.sql 的明确提示。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseSchemaChecker implements ApplicationRunner {

    /** 要求存在的核心表，需与 sql/schema.sql 保持一致。 */
    private static final String[] REQUIRED_TABLES = {
            "sys_user", "operator_config", "cpe_device", "rpc_record",
            "soap_message", "worklist", "worklist_step",
            "monitor_rule", "monitor_result", "sys_log"
    };

    private final DataSource dataSource;

    /** 是否执行自检。 */
    @Value("${acs.database.schema-check-enabled:true}")
    private boolean enabled;

    /** 表缺失时是否终止启动。默认仅告警，避免影响已有部署。 */
    @Value("${acs.database.fail-on-missing-table:false}")
    private boolean failOnMissingTable;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("数据库表结构自检已关闭");
            return;
        }
        List<String> missing;
        try {
            missing = findMissingTables();
        } catch (SQLException e) {
            log.error("数据库自检失败，请确认数据库已启动、库已创建且连接参数正确：{}", e.getMessage());
            if (failOnMissingTable) {
                throw new IllegalStateException("数据库不可用：" + e.getMessage(), e);
            }
            return;
        }
        if (missing.isEmpty()) {
            log.info("数据库自检通过，{} 张核心表均已存在", REQUIRED_TABLES.length);
            return;
        }
        log.error("数据库缺少以下表：{}。请先执行发布包中 sql/schema.sql 建表后重启", missing);
        if (failOnMissingTable) {
            throw new IllegalStateException("数据库缺少表 " + missing + "，请先执行 sql/schema.sql");
        }
    }

    /**
     * 逐个核对核心表是否存在于当前库。
     */
    private List<String> findMissingTables() throws SQLException {
        List<String> missing = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            String schema = conn.getCatalog();
            String sql = "SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema = ? AND table_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (String table : REQUIRED_TABLES) {
                    ps.setString(1, schema);
                    ps.setString(2, table);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || rs.getInt(1) == 0) {
                            missing.add(table);
                        }
                    }
                }
            }
        }
        return missing;
    }
}
