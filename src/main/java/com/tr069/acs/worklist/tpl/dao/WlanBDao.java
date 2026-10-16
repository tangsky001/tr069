package com.tr069.acs.worklist.tpl.dao;

import com.tr069.acs.worklist.tpl.entity.WlanB;
import com.tr069.acs.worklist.tpl.util.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 5.8G WiFi 实例 DAO。结构与 {@link WlanADao} 一致，分成两张表是因为
 * 2.4G 与 5.8G 实例号不连续且在 TR-098 里挂不同射频，下发时要分别处理。
 *
 * <p>该型号是否应该有行由 {@code device.cap_wifi_spec} 决定（含 5.8G 才有）。</p>
 */
@Repository
public class WlanBDao {

    private final JdbcTemplate jdbc;
    private final SnowflakeIdGenerator snowflake;

    private static final String COLS = "id, device_id, instance_no, action, verified, " +
            "path_enable, val_enable, path_ssid, val_ssid, path_channel, val_channel, " +
            "path_beacon, val_beacon, path_wpa_mode, val_wpa_mode, " +
            "path_key, val_key, path_psk, val_psk";

    private static final RowMapper<WlanB> ROW_MAPPER = (rs, n) -> {
        WlanB r = new WlanB();
        r.setId(rs.getLong("id"));
        r.setDeviceId(rs.getLong("device_id"));
        r.setInstanceNo(rs.getInt("instance_no"));
        r.setAction(rs.getString("action"));
        r.setVerified(rs.getString("verified"));
        r.setPathEnable(rs.getString("path_enable"));
        r.setValEnable(rs.getString("val_enable"));
        r.setPathSsid(rs.getString("path_ssid"));
        r.setValSsid(rs.getString("val_ssid"));
        r.setPathChannel(rs.getString("path_channel"));
        r.setValChannel(rs.getString("val_channel"));
        r.setPathBeacon(rs.getString("path_beacon"));
        r.setValBeacon(rs.getString("val_beacon"));
        r.setPathWpaMode(rs.getString("path_wpa_mode"));
        r.setValWpaMode(rs.getString("val_wpa_mode"));
        r.setPathKey(rs.getString("path_key"));
        r.setValKey(rs.getString("val_key"));
        r.setPathPsk(rs.getString("path_psk"));
        r.setValPsk(rs.getString("val_psk"));
        return r;
    };

    public WlanBDao(@Qualifier("tr069TplJdbcTemplate") JdbcTemplate jdbc,
                    SnowflakeIdGenerator snowflake) {
        this.jdbc = jdbc;
        this.snowflake = snowflake;
    }

    public Optional<WlanB> findOne(long deviceId, int instanceNo) {
        List<WlanB> list = jdbc.query(
                "SELECT " + COLS + " FROM wlan_b WHERE device_id = ? AND instance_no = ? LIMIT 1",
                ROW_MAPPER, deviceId, instanceNo);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<WlanB> findByDeviceId(long deviceId) {
        return jdbc.query("SELECT " + COLS + " FROM wlan_b WHERE device_id = ? ORDER BY instance_no",
                ROW_MAPPER, deviceId);
    }

    public int deleteByDeviceId(long deviceId) {
        return jdbc.update("DELETE FROM wlan_b WHERE device_id = ?", deviceId);
    }

    /** 按 (device_id, instance_no) 新增或更新，返回主键并回填到实体。 */
    public long upsert(WlanB r) {
        Optional<WlanB> exist = findOne(r.getDeviceId(), r.getInstanceNo());
        if (exist.isPresent()) {
            long id = exist.get().getId();
            jdbc.update("UPDATE wlan_b SET action=?, verified=?, path_enable=?, val_enable=?, " +
                            "path_ssid=?, val_ssid=?, path_channel=?, val_channel=?, path_beacon=?, val_beacon=?, " +
                            "path_wpa_mode=?, val_wpa_mode=?, path_key=?, val_key=?, path_psk=?, val_psk=? WHERE id=?",
                    r.getAction(), r.getVerified(), r.getPathEnable(), r.getValEnable(),
                    r.getPathSsid(), r.getValSsid(), r.getPathChannel(), r.getValChannel(),
                    r.getPathBeacon(), r.getValBeacon(), r.getPathWpaMode(), r.getValWpaMode(),
                    r.getPathKey(), r.getValKey(), r.getPathPsk(), r.getValPsk(), id);
            r.setId(id);
            return id;
        }
        long id = snowflake.nextId();
        jdbc.update("INSERT INTO wlan_b(id, device_id, instance_no, action, verified, " +
                        "path_enable, val_enable, path_ssid, val_ssid, path_channel, val_channel, " +
                        "path_beacon, val_beacon, path_wpa_mode, val_wpa_mode, " +
                        "path_key, val_key, path_psk, val_psk) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, r.getDeviceId(), r.getInstanceNo(), r.getAction(), r.getVerified(),
                r.getPathEnable(), r.getValEnable(), r.getPathSsid(), r.getValSsid(),
                r.getPathChannel(), r.getValChannel(), r.getPathBeacon(), r.getValBeacon(),
                r.getPathWpaMode(), r.getValWpaMode(), r.getPathKey(), r.getValKey(),
                r.getPathPsk(), r.getValPsk());
        r.setId(id);
        return id;
    }
}
