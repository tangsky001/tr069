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
 * WAN 杩炴帴瀹炰緥 DAO銆備竴琛?= 涓€鏉?WAN 杩炴帴鐨勫叏閮ㄤ笅鍙戣矾寰勪笌鍙橀噺鍊笺€?
 *
 * <p><b>瀹炰緥鍙蜂笉鍚屽瀷鍙峰樊鍒緢澶?/b>锛氫腑鍏?P22g-CIac 瀹炴祴鏄?9-12锛屽埆鐨勫瀷鍙峰彲鑳戒粠 1 寮€濮嬶紝
 * 鎵€浠ユ寜璁惧瀹為檯閲囬泦鍊煎瓨锛屼笉鍋氫换浣?浠?1 杩炵画缂栧彿"鐨勫亣璁俱€?/p>
 *
 * <p>{@code conn_type} 鏍囧嚭杩欐潯杩炴帴鐨勪笟鍔＄被鍒紙IPoE / PPPoE / Bridge锛夛紝
 * 涓嬪彂鍏ュ彛鎹鎸戣鐢ㄥ摢濂楁ā鏉裤€?/p>
 */
@Repository
public class DeviceWanDao {

    private final JdbcTemplate jdbc;
    private final SnowflakeIdGenerator snowflake;

    private static final String COLS = "id, device_id, instance_no, conn_type, action, verified, " +
            "object_path, path_enable, val_enable, path_conn_type, val_conn_type, " +
            "path_addr_type, val_addr_type, path_nat, val_nat, path_bind_port, val_bind_port, " +
            "path_username, val_username, path_password, val_password, path_ip, val_ip, " +
            "path_mask, val_mask, path_gw, val_gw, path_dns, val_dns, path_mtu, val_mtu";

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
        r.setPathMtu(rs.getString("path_mtu"));
        r.setValMtu(rs.getString("val_mtu"));
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

    /** 鎸?(device_id, instance_no) 鏂板鎴栨洿鏂帮紝杩斿洖涓婚敭骞跺洖濉埌瀹炰綋銆?*/
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
                            "path_dns=?, val_dns=?, path_mtu=?, val_mtu=? WHERE id=?",
                    r.getConnType(), r.getAction(), r.getVerified(), r.getObjectPath(),
                    r.getPathEnable(), r.getValEnable(), r.getPathConnType(), r.getValConnType(),
                    r.getPathAddrType(), r.getValAddrType(), r.getPathNat(), r.getValNat(),
                    r.getPathBindPort(), r.getValBindPort(),
                    r.getPathUsername(), r.getValUsername(), r.getPathPassword(), r.getValPassword(),
                    r.getPathIp(), r.getValIp(), r.getPathMask(), r.getValMask(),
                    r.getPathGw(), r.getValGw(), r.getPathDns(), r.getValDns(),
                    r.getPathMtu(), r.getValMtu(), id);
            r.setId(id);
            return id;
        }
        long id = snowflake.nextId();
        jdbc.update("INSERT INTO device_wan(id, device_id, instance_no, conn_type, action, verified, " +
                        "object_path, path_enable, val_enable, path_conn_type, val_conn_type, " +
                        "path_addr_type, val_addr_type, path_nat, val_nat, path_bind_port, val_bind_port, " +
                        "path_username, val_username, path_password, val_password, path_ip, val_ip, " +
                        "path_mask, val_mask, path_gw, val_gw, path_dns, val_dns, " +
                        "path_mtu, val_mtu) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, r.getDeviceId(), r.getInstanceNo(), r.getConnType(), r.getAction(), r.getVerified(),
                r.getObjectPath(),
                r.getPathEnable(), r.getValEnable(), r.getPathConnType(), r.getValConnType(),
                r.getPathAddrType(), r.getValAddrType(), r.getPathNat(), r.getValNat(),
                r.getPathBindPort(), r.getValBindPort(),
                r.getPathUsername(), r.getValUsername(), r.getPathPassword(), r.getValPassword(),
                r.getPathIp(), r.getValIp(), r.getPathMask(), r.getValMask(),
                r.getPathGw(), r.getValGw(), r.getPathDns(), r.getValDns(),
                r.getPathMtu(), r.getValMtu());
        r.setId(id);
        return id;
    }
}
