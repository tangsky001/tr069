package com.tr069.acs.worklist.tpl.dao;

import com.tr069.acs.worklist.tpl.entity.DeviceWan;
import com.tr069.acs.worklist.tpl.util.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * WAN 连接实例 DAO。一行 = 一条 WAN 连接的全部下发路径与变量值。
 *
 * <p><b>实例号不同型号差别很大</b>：中兴 P22g-CIac 实测是 9-12，别的型号可能从 1 开始，
 * 所以按设备实际采集值存，不做任何"从 1 连续编号"的假设。</p>
 *
 * <p>{@code conn_type} 标出这条连接的业务类别（IPoE / PPPoE / Bridge），
 * 下发入口据此挑该用哪套模板。</p>
 */
@Repository
public class DeviceWanDao {

    private final JdbcTemplate jdbc;
    private final SnowflakeIdGenerator snowflake;

    private static final String COLS = "id, device_id, instance_no, conn_type, action, verified, " +
            "object_path, path_enable, val_enable, path_conn_type, val_conn_type, " +
            "path_addr_type, val_addr_type, path_nat, val_nat, path_bind_port, val_bind_port, " +
            "path_username, val_username, path_password, val_password, path_ip, val_ip, " +
            "path_mask, val_mask, path_gw, val_gw, path_dns, val_dns";

    private static final RowMapper<DeviceWan> ROW_MAPPER = (rs, n) -> {
        DeviceWan r = new DeviceWan();
        r.setId(rs.getLong("id"));
        r.setDeviceId(rs.getLong("device_id"));
        r.setInstanceNo(rs.getInt("instance_no"));
        r.setConnType(rs.getString("conn_type"));
        r.setAction(rs.getString("action"));
        r.setVerified(rs.getString("verified"));
        r.setObjectPath(rs.getString("object_path"));
        r.setPathEnable(rs.getString("path_enable"));
        r.setValEnable(rs.getString("val_enable"));
        r.setPathConnType(rs.getString("path_conn_type"));
        r.setValConnType(rs.getString("val_conn_type"));
        r.setPathAddrType(rs.getString("path_addr_type"));
        r.setValAddrType(rs.getString("val_addr_type"));
        r.setPathNat(rs.getString("path_nat"));
        r.setValNat(rs.getString("val_nat"));
        r.setPathBindPort(rs.getString("path_bind_port"));
        r.setValBindPort(rs.getString("val_bind_port"));
        r.setPathUsername(rs.getString("path_username"));
        r.setValUsername(rs.getString("val_username"));
        r.setPathPassword(rs.getString("path_password"));
        r.setValPassword(rs.getString("val_password"));
        r.setPathIp(rs.getString("path_ip"));
        r.setValIp(rs.getString("val_ip"));
        r.setPathMask(rs.getString("path_mask"));
        r.setValMask(rs.getString("val_mask"));
        r.setPathGw(rs.getString("path_gw"));
        r.setValGw(rs.getString("val_gw"));
        r.setPathDns(rs.getString("path_dns"));
        r.setValDns(rs.getString("val_dns"));
        return r;
    };

    public DeviceWanDao(@Qualifier("tr069TplJdbcTemplate") JdbcTemplate jdbc,
                        SnowflakeIdGenerator snowflake) {
        this.jdbc = jdbc;
        this.snowflake = snowflake;
    }

    public Optional<DeviceWan> findOne(long deviceId, int instanceNo) {
        List<DeviceWan> list = jdbc.query(
                "SELECT " + COLS + " FROM device_wan WHERE device_id = ? AND instance_no = ? LIMIT 1",
                ROW_MAPPER, deviceId, instanceNo);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<DeviceWan> findByDeviceId(long deviceId) {
        return jdbc.query("SELECT " + COLS + " FROM device_wan WHERE device_id = ? ORDER BY instance_no",
                ROW_MAPPER, deviceId);
    }

    public List<DeviceWan> findByDeviceIdAndType(long deviceId, String connType) {
        return jdbc.query("SELECT " + COLS + " FROM device_wan " +
                        "WHERE device_id = ? AND conn_type = ? ORDER BY instance_no",
                ROW_MAPPER, deviceId, connType);
    }

    public int deleteByDeviceId(long deviceId) {
        return jdbc.update("DELETE FROM device_wan WHERE device_id = ?", deviceId);
    }

    /** 按 (device_id, instance_no) 新增或更新，返回主键并回填到实体。 */
    public long upsert(DeviceWan r) {
        Optional<DeviceWan> exist = findOne(r.getDeviceId(), r.getInstanceNo());
        if (exist.isPresent()) {
            long id = exist.get().getId();
            jdbc.update("UPDATE device_wan SET conn_type=?, action=?, verified=?, object_path=?, " +
                            "path_enable=?, val_enable=?, path_conn_type=?, val_conn_type=?, " +
                            "path_addr_type=?, val_addr_type=?, path_nat=?, val_nat=?, " +
                            "path_bind_port=?, val_bind_port=?, " +
                            "path_username=?, val_username=?, path_password=?, val_password=?, " +
                            "path_ip=?, val_ip=?, path_mask=?, val_mask=?, path_gw=?, val_gw=?, " +
                            "path_dns=?, val_dns=? WHERE id=?",
                    r.getConnType(), r.getAction(), r.getVerified(), r.getObjectPath(),
                    r.getPathEnable(), r.getValEnable(), r.getPathConnType(), r.getValConnType(),
                    r.getPathAddrType(), r.getValAddrType(), r.getPathNat(), r.getValNat(),
                    r.getPathBindPort(), r.getValBindPort(),
                    r.getPathUsername(), r.getValUsername(), r.getPathPassword(), r.getValPassword(),
                    r.getPathIp(), r.getValIp(), r.getPathMask(), r.getValMask(),
                    r.getPathGw(), r.getValGw(), r.getPathDns(), r.getValDns(), id);
            r.setId(id);
            return id;
        }
        long id = snowflake.nextId();
        jdbc.update("INSERT INTO device_wan(id, device_id, instance_no, conn_type, action, verified, " +
                        "object_path, path_enable, val_enable, path_conn_type, val_conn_type, " +
                        "path_addr_type, val_addr_type, path_nat, val_nat, path_bind_port, val_bind_port, " +
                        "path_username, val_username, path_password, val_password, path_ip, val_ip, " +
                        "path_mask, val_mask, path_gw, val_gw, path_dns, val_dns) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, r.getDeviceId(), r.getInstanceNo(), r.getConnType(), r.getAction(), r.getVerified(),
                r.getObjectPath(),
                r.getPathEnable(), r.getValEnable(), r.getPathConnType(), r.getValConnType(),
                r.getPathAddrType(), r.getValAddrType(), r.getPathNat(), r.getValNat(),
                r.getPathBindPort(), r.getValBindPort(),
                r.getPathUsername(), r.getValUsername(), r.getPathPassword(), r.getValPassword(),
                r.getPathIp(), r.getValIp(), r.getPathMask(), r.getValMask(),
                r.getPathGw(), r.getValGw(), r.getPathDns(), r.getValDns());
        r.setId(id);
        return id;
    }
}
