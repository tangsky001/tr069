/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.config;

import com.PilotACS.common.log.Logs;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** template.pack（SQLite）自动建表兜底： */
@Component
public class CpeSchemaInitializer implements InitializingBean {

    private static final List<String> CREATE_SQL = List.of(
            """
            CREATE TABLE IF NOT EXISTS cpe_device_param (
                device_sn  TEXT    NOT NULL,
                path       TEXT    NOT NULL,
                value      TEXT,
                value_type TEXT,
                object     INTEGER DEFAULT 0,
                writable   INTEGER DEFAULT 0,
                ts         INTEGER,
                PRIMARY KEY (device_sn, path)
            )""",
            "CREATE INDEX IF NOT EXISTS idx_cdp_sn ON cpe_device_param(device_sn)",
            """
            CREATE TABLE IF NOT EXISTS cpe_model (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                brand           TEXT NOT NULL,
                model           TEXT NOT NULL,
                version         TEXT NOT NULL,
                isp             TEXT NOT NULL,
                isp_version     TEXT,
                root_node       TEXT,
                capability_json TEXT,
                source_sn       TEXT,
                status          TEXT DEFAULT 'ready',
                created_at      INTEGER,
                updated_at      INTEGER,
                UNIQUE (brand, model, version, isp)
            )""",
            """
            CREATE TABLE IF NOT EXISTS cpe_model_param (
                model_id   INTEGER NOT NULL,
                path       TEXT    NOT NULL,
                value      TEXT,
                value_type TEXT,
                object     INTEGER DEFAULT 0,
                writable   INTEGER DEFAULT 0,
                PRIMARY KEY (model_id, path)
            )""",
            "CREATE INDEX IF NOT EXISTS idx_cmp_model ON cpe_model_param(model_id)");

    private final JdbcTemplate cpeJdbcTemplate;

    /** 显式限定 SQLite 的 JdbcTemplate（容器里有两个 JdbcTemplate，不能靠参数名推断） */
    public CpeSchemaInitializer(@Qualifier("cpeJdbcTemplate") JdbcTemplate cpeJdbcTemplate) {
        this.cpeJdbcTemplate = cpeJdbcTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        for (String sql : CREATE_SQL) {
            cpeJdbcTemplate.execute(sql);
        }
        Logs.info(Logs.Module.CPE, "template.pack 结构校验完成",
                "表", "cpe_device_param/cpe_model/cpe_model_param");
    }
}
