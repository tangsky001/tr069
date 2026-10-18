package com.tr069.acs.worklist.tpl.dao;

import com.tr069.acs.worklist.tpl.entity.DeviceLan;
import com.tr069.acs.worklist.tpl.util.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * LAN 网口实例 DAO。一行 = 一个物理网口的下发路径与变量值。
 *
 * <p>网口个数由 {@code device.cap_lan_ports} 决定，速率可选项由
 * {@code device.cap_lan_speed} 决定（如「自动 / 100M / 1000M / 2500M」）。</p>
 */
@Repository
public class DeviceLanDao {

    private final JdbcTemplate jdbc;
    private final SnowflakeIdGenerator snowflake;

    private static final String COLS = "id, device_id, instance_no, action, verified, " +
            "path_enable, val_enable, path_duplex, val_duplex, path_speed, val_speed";

    private static final RowMapper<DeviceLan> ROW_MAPPER = (rs, n) -> {
        DeviceLan r = new DeviceLan();
        r.setId(rs.getLong("id"));
        r.setDeviceId(rs.getLong("device_id"));
        r.setInstanceNo(rs.getInt("instance_no"));
        r.setAction(rs.getString("action"));
        r.setVerified(rs.getString("verified"));
        r.setPathEnable(rs.getString("path_enable"));
        r.setValEnable(rs.getString("val_enable"));
        r.setPathDuplex(rs.getString("path_duplex"));
        r.setValDuplex(rs.getString("val_duplex"));
        r.setPathSpeed(rs.getString("path_speed"));
        r.setValSpeed(rs.getString("val_speed"));
        return r;
    };

    public DeviceLanDao(@Qualifier("tr069TplJdbcTemplate") JdbcTemplate jdbc,
                        SnowflakeIdGenerator snowflake) {
        this.jdbc = jdbc;
        this.snowflake = snowflake;
    }

    public Optional<DeviceLan> findOne(long deviceId, int instanceNo) {
        List<DeviceLan> list = jdbc.query(
                "SELECT " + COLS + " FROM device_lan WHERE device_id = ? AND instance_no = ? LIMIT 1",
                ROW_MAPPER, deviceId, instanceNo);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<DeviceLan> findByDeviceId(long deviceId) {
        return jdbc.query("SELECT " + COLS + " FROM device_lan WHERE device_id = ? ORDER BY instance_no",
                ROW_MAPPER, deviceId);
    }

    public int deleteByDeviceId(long deviceId) {
        return jdbc.update("DELETE FROM device_lan WHERE device_id = ?", deviceId);
    }

    /** 按 (device_id, instance_no) 新增或更新，返回主键并回填到实体。 */
    public long upsert(DeviceLan r) {
        Optional<DeviceLan> exist = findOne(r.getDeviceId(), r.getInstanceNo());
        if (exist.isPresent()) {
            long id = exist.get().getId();
            jdbc.update("UPDATE device_lan SET action=?, verified=?, path_enable=?, val_enable=?, " +
                            "path_duplex=?, val_duplex=?, path_speed=?, val_speed=? WHERE id=?",
                    r.getAction(), r.getVerified(), r.getPathEnable(), r.getValEnable(),
                    r.getPathDuplex(), r.getValDuplex(), r.getPathSpeed(), r.getValSpeed(), id);
            r.setId(id);
            return id;
        }
        long id = snowflake.nextId();
        jdbc.update("INSERT INTO device_lan(id, device_id, instance_no, action, verified, " +
                        "path_enable, val_enable, path_duplex, val_duplex, path_speed, val_speed) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id, r.getDeviceId(), r.getInstanceNo(), r.getAction(), r.getVerified(),
                r.getPathEnable(), r.getValEnable(), r.getPathDuplex(), r.getValDuplex(),
                r.getPathSpeed(), r.getValSpeed());
        r.setId(id);
        return id;
    }
}
