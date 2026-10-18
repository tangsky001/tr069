package com.tr069.acs.worklist.tpl.dao;

import com.tr069.acs.worklist.tpl.entity.Vlan;
import com.tr069.acs.worklist.tpl.util.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * VLAN 实例 DAO。
 *
 * <p>VLAN 不像 WiFi / SIP 那样有自己的顶层对象，它挂在某条 WAN 连接下面
 * （TR-098 里常见 {@code WANIPConnection.{i}.X_CMCC_VLANID}），
 * 所以这里用 {@code wan_instance} 关联 {@code device_wan.instance_no}，
 * 而不是自己的实例号。该字段可为空——表示这条 VLAN 尚未绑定到具体 WAN 实例。</p>
 *
 * <p>注意：SQLite 的 UNIQUE 约束允许多个 NULL，所以 {@code wan_instance IS NULL}
 * 的行不会被去重，判重时必须走显式查询而不是依赖约束报错。</p>
 */
@Repository
public class VlanDao {

    private final JdbcTemplate jdbc;
    private final SnowflakeIdGenerator snowflake;

    private static final String COLS = "id, device_id, wan_instance, action, verified, " +
            "path_vlan_id, val_vlan_id, path_vlan_mode, val_vlan_mode, " +
            "path_multicast, val_multicast, path_8021p, val_8021p";

    private static final RowMapper<Vlan> ROW_MAPPER = (rs, n) -> {
        Vlan r = new Vlan();
        r.setId(rs.getLong("id"));
        r.setDeviceId(rs.getLong("device_id"));
        int wi = rs.getInt("wan_instance");
        r.setWanInstance(rs.wasNull() ? null : wi);
        r.setAction(rs.getString("action"));
        r.setVerified(rs.getString("verified"));
        r.setPathVlanId(rs.getString("path_vlan_id"));
        r.setValVlanId(rs.getString("val_vlan_id"));
        r.setPathVlanMode(rs.getString("path_vlan_mode"));
        r.setValVlanMode(rs.getString("val_vlan_mode"));
        r.setPathMulticast(rs.getString("path_multicast"));
        r.setValMulticast(rs.getString("val_multicast"));
        r.setPath8021p(rs.getString("path_8021p"));
        r.setVal8021p(rs.getString("val_8021p"));
        return r;
    };

    public VlanDao(@Qualifier("tr069TplJdbcTemplate") JdbcTemplate jdbc,
                   SnowflakeIdGenerator snowflake) {
        this.jdbc = jdbc;
        this.snowflake = snowflake;
    }

    /**
     * 按 (device_id, wan_instance) 查。{@code wanInstance} 为 null 时匹配未绑定的行。
     */
    public Optional<Vlan> findOne(long deviceId, Integer wanInstance) {
        String sql = wanInstance == null
                ? "SELECT " + COLS + " FROM vlan WHERE device_id = ? AND wan_instance IS NULL LIMIT 1"
                : "SELECT " + COLS + " FROM vlan WHERE device_id = ? AND wan_instance = ? LIMIT 1";
        Object[] args = wanInstance == null ? new Object[]{deviceId} : new Object[]{deviceId, wanInstance};
        List<Vlan> list = jdbc.query(sql, ROW_MAPPER, args);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<Vlan> findByDeviceId(long deviceId) {
        return jdbc.query("SELECT " + COLS + " FROM vlan WHERE device_id = ? ORDER BY wan_instance",
                ROW_MAPPER, deviceId);
    }

    public int deleteByDeviceId(long deviceId) {
        return jdbc.update("DELETE FROM vlan WHERE device_id = ?", deviceId);
    }

    /** 按 (device_id, wan_instance) 新增或更新，返回主键并回填到实体。 */
    public long upsert(Vlan r) {
        Optional<Vlan> exist = findOne(r.getDeviceId(), r.getWanInstance());
        if (exist.isPresent()) {
            long id = exist.get().getId();
            jdbc.update("UPDATE vlan SET action=?, verified=?, " +
                            "path_vlan_id=?, val_vlan_id=?, path_vlan_mode=?, val_vlan_mode=?, " +
                            "path_multicast=?, val_multicast=?, path_8021p=?, val_8021p=? WHERE id=?",
                    r.getAction(), r.getVerified(),
                    r.getPathVlanId(), r.getValVlanId(), r.getPathVlanMode(), r.getValVlanMode(),
                    r.getPathMulticast(), r.getValMulticast(), r.getPath8021p(), r.getVal8021p(), id);
            r.setId(id);
            return id;
        }
        long id = snowflake.nextId();
        jdbc.update("INSERT INTO vlan(id, device_id, wan_instance, action, verified, " +
                        "path_vlan_id, val_vlan_id, path_vlan_mode, val_vlan_mode, " +
                        "path_multicast, val_multicast, path_8021p, val_8021p) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, r.getDeviceId(), r.getWanInstance(), r.getAction(), r.getVerified(),
                r.getPathVlanId(), r.getValVlanId(), r.getPathVlanMode(), r.getValVlanMode(),
                r.getPathMulticast(), r.getValMulticast(), r.getPath8021p(), r.getVal8021p());
        r.setId(id);
        return id;
    }
}
