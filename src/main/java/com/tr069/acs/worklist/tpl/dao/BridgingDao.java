package com.tr069.acs.worklist.tpl.dao;

import com.tr069.acs.worklist.tpl.entity.Bridging;
import com.tr069.acs.worklist.tpl.util.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 桥接实例 DAO。一行 = 一个 (bridge, port) 组合。
 *
 * <p>桥接是唯一<b>默认需要 AddObject</b> 的业务：设备出厂通常没有现成的桥接 WAN 实例，
 * 下发必须先 AddObject 建 {@code WANConnectionDevice.{j}.WANIPConnection.} 拿到实例号，
 * 再 SetParameterValues 设 {@code Enable / ConnectionType=Bridged / NATEnabled=0 /
 * X_CMCC_VLANID / X_CMCC_BindLANPort}。所以这里 {@code action} 默认取 {@code ADD}。</p>
 *
 * <p>唯一键是 (device_id, bridge_instance, port_instance) 三元组，
 * 因为一个网桥下可以挂多个端口。</p>
 */
@Repository
public class BridgingDao {

    private final JdbcTemplate jdbc;
    private final SnowflakeIdGenerator snowflake;

    private static final String COLS = "id, device_id, bridge_instance, port_instance, action, verified, " +
            "path_bridge_enable, val_bridge_enable, path_port_enable, val_port_enable, " +
            "path_bind_iface, val_bind_iface, path_conn_type, val_conn_type, " +
            "path_nat_enabled, val_nat_enabled, bind_lan_no";

    private static final RowMapper<Bridging> ROW_MAPPER = (rs, n) -> {
        Bridging r = new Bridging();
        r.setId(rs.getLong("id"));
        r.setDeviceId(rs.getLong("device_id"));
        r.setBridgeInstance(rs.getInt("bridge_instance"));
        r.setPortInstance(rs.getInt("port_instance"));
        r.setAction(rs.getString("action"));
        r.setVerified(rs.getString("verified"));
        r.setPathBridgeEnable(rs.getString("path_bridge_enable"));
        r.setValBridgeEnable(rs.getString("val_bridge_enable"));
        r.setPathPortEnable(rs.getString("path_port_enable"));
        r.setValPortEnable(rs.getString("val_port_enable"));
        r.setPathBindIface(rs.getString("path_bind_iface"));
        r.setValBindIface(rs.getString("val_bind_iface"));
        r.setPathConnType(rs.getString("path_conn_type"));
        r.setValConnType(rs.getString("val_conn_type"));
        r.setPathNatEnabled(rs.getString("path_nat_enabled"));
        r.setValNatEnabled(rs.getString("val_nat_enabled"));
        int lan = rs.getInt("bind_lan_no");
        r.setBindLanNo(rs.wasNull() ? null : lan);
        return r;
    };

    public BridgingDao(@Qualifier("tr069TplJdbcTemplate") JdbcTemplate jdbc,
                       SnowflakeIdGenerator snowflake) {
        this.jdbc = jdbc;
        this.snowflake = snowflake;
    }

    public Optional<Bridging> findOne(long deviceId, int bridgeInstance, int portInstance) {
        List<Bridging> list = jdbc.query("SELECT " + COLS + " FROM bridging " +
                        "WHERE device_id = ? AND bridge_instance = ? AND port_instance = ? LIMIT 1",
                ROW_MAPPER, deviceId, bridgeInstance, portInstance);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<Bridging> findByDeviceId(long deviceId) {
        return jdbc.query("SELECT " + COLS + " FROM bridging " +
                        "WHERE device_id = ? ORDER BY bridge_instance, port_instance",
                ROW_MAPPER, deviceId);
    }

    public int deleteByDeviceId(long deviceId) {
        return jdbc.update("DELETE FROM bridging WHERE device_id = ?", deviceId);
    }

    /** 按 (device_id, bridge_instance, port_instance) 新增或更新，返回主键并回填到实体。 */
    public long upsert(Bridging r) {
        Optional<Bridging> exist = findOne(r.getDeviceId(), r.getBridgeInstance(), r.getPortInstance());
        if (exist.isPresent()) {
            long id = exist.get().getId();
            jdbc.update("UPDATE bridging SET action=?, verified=?, " +
                            "path_bridge_enable=?, val_bridge_enable=?, path_port_enable=?, val_port_enable=?, " +
                            "path_bind_iface=?, val_bind_iface=?, path_conn_type=?, val_conn_type=?, " +
                            "path_nat_enabled=?, val_nat_enabled=?, bind_lan_no=? WHERE id=?",
                    r.getAction(), r.getVerified(),
                    r.getPathBridgeEnable(), r.getValBridgeEnable(),
                    r.getPathPortEnable(), r.getValPortEnable(),
                    r.getPathBindIface(), r.getValBindIface(),
                    r.getPathConnType(), r.getValConnType(),
                    r.getPathNatEnabled(), r.getValNatEnabled(), r.getBindLanNo(), id);
            r.setId(id);
            return id;
        }
        long id = snowflake.nextId();
        jdbc.update("INSERT INTO bridging(id, device_id, bridge_instance, port_instance, action, verified, " +
                        "path_bridge_enable, val_bridge_enable, path_port_enable, val_port_enable, " +
                        "path_bind_iface, val_bind_iface, path_conn_type, val_conn_type, " +
                        "path_nat_enabled, val_nat_enabled, bind_lan_no) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, r.getDeviceId(), r.getBridgeInstance(), r.getPortInstance(),
                r.getAction(), r.getVerified(),
                r.getPathBridgeEnable(), r.getValBridgeEnable(),
                r.getPathPortEnable(), r.getValPortEnable(),
                r.getPathBindIface(), r.getValBindIface(),
                r.getPathConnType(), r.getValConnType(),
                r.getPathNatEnabled(), r.getValNatEnabled(), r.getBindLanNo());
        r.setId(id);
        return id;
    }
}
