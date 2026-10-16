package com.tr069.acs.worklist.tpl.dao;

import com.tr069.acs.worklist.tpl.entity.Device;
import com.tr069.acs.worklist.tpl.util.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 型号主表 DAO。两步点查的核心入口：
 * <pre>
 *   ① findByIspModelKeyVersion(...)  → 拿 device.id
 *   ② 业务表按 device_id + instance_no 取具体参数
 * </pre>
 * id 由注入的雪花生成器产生，跨节点部署时只要节点号不撞就安全。
 */
@Repository
public class DeviceDao {

    private final JdbcTemplate jdbc;
    private final SnowflakeIdGenerator snowflake;

    private static final String COLS = "id, isp, model_key, manufacturer, product_class, " +
            "software_version, hardware_version, status, sample_sn, root_node, " +
            "cap_lan_ports, cap_lan_speed, cap_sip_count, cap_wifi_spec, " +
            "remark, created_at, updated_at";

    private static final RowMapper<Device> ROW_MAPPER = (rs, rowNum) -> {
        Device d = new Device();
        d.setId(rs.getLong("id"));
        d.setIsp(rs.getString("isp"));
        d.setModelKey(rs.getString("model_key"));
        d.setManufacturer(rs.getString("manufacturer"));
        d.setProductClass(rs.getString("product_class"));
        d.setSoftwareVersion(rs.getString("software_version"));
        d.setHardwareVersion(rs.getString("hardware_version"));
        d.setStatus(rs.getString("status"));
        d.setSampleSn(rs.getString("sample_sn"));
        d.setRootNode(rs.getString("root_node"));
        d.setCapLanPorts(rs.getString("cap_lan_ports"));
        d.setCapLanSpeed(rs.getString("cap_lan_speed"));
        d.setCapSipCount(rs.getString("cap_sip_count"));
        d.setCapWifiSpec(rs.getString("cap_wifi_spec"));
        d.setRemark(rs.getString("remark"));
        d.setCreatedAt(rs.getString("created_at"));
        d.setUpdatedAt(rs.getString("updated_at"));
        return d;
    };

    public DeviceDao(@Qualifier("tr069TplJdbcTemplate") JdbcTemplate jdbc,
                     SnowflakeIdGenerator snowflake) {
        this.jdbc = jdbc;
        this.snowflake = snowflake;
    }

    /**
     * 核心点查：按 (isp, model_key) 查唯一一条，命中唯一索引。
     *
     * <p>model_key 本身已含固件版本（{@code 型号_固件}，见
     * {@code ModelMappingService.buildModelKey}），所以不需要再拼 software_version——
     * 与 MySQL {@code cpe_model} 的 {@code uk_cpe_model(isp, model_key)} 保持一致，
     * 同一份 modelKey 在两边查到的必须是同一条记录。</p>
     */
    public Optional<Device> findByIspModelKey(String isp, String modelKey) {
        List<Device> list = jdbc.query(
                "SELECT " + COLS + " FROM device WHERE isp = ? AND model_key = ? LIMIT 1",
                ROW_MAPPER, isp, modelKey);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 同型号不分运营商的全部归档行。
     *
     * <p>适配是按「申报的运营商」归档的（比如选了 CMCC 就挂在 CMCC 目录下），
     * 但设备台账里的运营商未必一致——光猫上报通常不带运营商，适配时才人工定。
     * 于是会出现同一个 modelKey 存两份：一份没申报过（早期采集留下的空档）、
     * 一份带完整能力申报。按 isp 只查一份就会命中空的那份，表现为「明明适配过，
     * 模板却生成不出来」。这里按型号捞全，交给调用方挑有申报的那份。</p>
     */
    public List<Device> findByModelKey(String modelKey) {
        return jdbc.query("SELECT " + COLS + " FROM device WHERE model_key = ? ORDER BY id",
                ROW_MAPPER, modelKey);
    }

    public Optional<Device> findById(long id) {
        List<Device> list = jdbc.query(
                "SELECT " + COLS + " FROM device WHERE id = ?", ROW_MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<Device> listAll() {
        return jdbc.query("SELECT " + COLS + " FROM device ORDER BY id", ROW_MAPPER);
    }

    /**
     * 新增或更新：同 (isp, model_key, software_version) 已存在则 UPDATE，否则 INSERT。
     */
    public boolean upsert(Device d) {
        // isp / status / root_node 在表里是 NOT NULL，业务上也都有明确默认值，
        // 调用方没设就补齐。注意 SQLite 的 DEFAULT 只在「完全不指定该列」时生效，
        // 显式传 null 会直接报 SQLITE_CONSTRAINT_NOTNULL
        if (d.getIsp() == null) {
            d.setIsp("standard");
        }
        if (d.getStatus() == null) {
            d.setStatus("PENDING");
        }
        if (d.getRootNode() == null) {
            d.setRootNode("InternetGatewayDevice.");
        }
        Optional<Device> existing = findByIspModelKey(d.getIsp(), d.getModelKey());
        String now = String.valueOf(System.currentTimeMillis());
        if (existing.isPresent()) {
            jdbc.update("UPDATE device SET manufacturer = ?, product_class = ?, hardware_version = ?, " +
                            "status = ?, sample_sn = ?, root_node = ?, cap_lan_ports = ?, cap_lan_speed = ?, " +
                            "cap_sip_count = ?, cap_wifi_spec = ?, remark = ?, updated_at = ? WHERE id = ?",
                    d.getManufacturer(), d.getProductClass(), d.getHardwareVersion(),
                    d.getStatus(), d.getSampleSn(), d.getRootNode(), d.getCapLanPorts(), d.getCapLanSpeed(),
                    d.getCapSipCount(), d.getCapWifiSpec(), d.getRemark(), now, existing.get().getId());
            d.setId(existing.get().getId());
            return true;
        }
        long newId = snowflake.nextId();
        jdbc.update("INSERT INTO device(id, isp, model_key, manufacturer, product_class, software_version, " +
                        "hardware_version, status, sample_sn, root_node, cap_lan_ports, cap_lan_speed, " +
                        "cap_sip_count, cap_wifi_spec, remark, created_at, updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                newId, d.getIsp(), d.getModelKey(), d.getManufacturer(), d.getProductClass(),
                d.getSoftwareVersion(), d.getHardwareVersion(), d.getStatus(), d.getSampleSn(),
                d.getRootNode(), d.getCapLanPorts(), d.getCapLanSpeed(), d.getCapSipCount(),
                d.getCapWifiSpec(), d.getRemark(), now, now);
        d.setId(newId);
        return true;
    }

    public int deleteAll() {
        return jdbc.update("DELETE FROM device");
    }
}
