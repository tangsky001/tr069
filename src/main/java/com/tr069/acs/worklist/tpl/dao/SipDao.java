package com.tr069.acs.worklist.tpl.dao;

import com.tr069.acs.worklist.tpl.entity.Sip;
import com.tr069.acs.worklist.tpl.util.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * SIP 语音实例 DAO。一行 = 一路 SIP 账号的全部下发路径与变量值。
 *
 * <p>实例个数由 {@code device.cap_sip_count} 决定。</p>
 *
 * <p>注意：账号口令落 {@code val_pwd} 时必须是变量（如 {@code %sip_pwd%}），
 * 库里出现明文视为 Bug——模板库是要能整个拷走的。</p>
 */
@Repository
public class SipDao {

    private final JdbcTemplate jdbc;
    private final SnowflakeIdGenerator snowflake;

    private static final String COLS = "id, device_id, instance_no, action, verified, " +
            "path_enable, val_enable, path_user, val_user, path_pwd, val_pwd, " +
            "path_uri, val_uri, path_proxy, val_proxy, path_port, val_port";

    private static final RowMapper<Sip> ROW_MAPPER = (rs, n) -> {
        Sip r = new Sip();
        r.setId(rs.getLong("id"));
        r.setDeviceId(rs.getLong("device_id"));
        r.setInstanceNo(rs.getInt("instance_no"));
        r.setAction(rs.getString("action"));
        r.setVerified(rs.getString("verified"));
        r.setPathEnable(rs.getString("path_enable"));
        r.setValEnable(rs.getString("val_enable"));
        r.setPathUser(rs.getString("path_user"));
        r.setValUser(rs.getString("val_user"));
        r.setPathPwd(rs.getString("path_pwd"));
        r.setValPwd(rs.getString("val_pwd"));
        r.setPathUri(rs.getString("path_uri"));
        r.setValUri(rs.getString("val_uri"));
        r.setPathProxy(rs.getString("path_proxy"));
        r.setValProxy(rs.getString("val_proxy"));
        r.setPathPort(rs.getString("path_port"));
        r.setValPort(rs.getString("val_port"));
        return r;
    };

    public SipDao(@Qualifier("tr069TplJdbcTemplate") JdbcTemplate jdbc,
                  SnowflakeIdGenerator snowflake) {
        this.jdbc = jdbc;
        this.snowflake = snowflake;
    }

    public Optional<Sip> findOne(long deviceId, int instanceNo) {
        List<Sip> list = jdbc.query(
                "SELECT " + COLS + " FROM sip WHERE device_id = ? AND instance_no = ? LIMIT 1",
                ROW_MAPPER, deviceId, instanceNo);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<Sip> findByDeviceId(long deviceId) {
        return jdbc.query("SELECT " + COLS + " FROM sip WHERE device_id = ? ORDER BY instance_no",
                ROW_MAPPER, deviceId);
    }

    public int deleteByDeviceId(long deviceId) {
        return jdbc.update("DELETE FROM sip WHERE device_id = ?", deviceId);
    }

    /** 按 (device_id, instance_no) 新增或更新，返回主键并回填到实体。 */
    public long upsert(Sip r) {
        Optional<Sip> exist = findOne(r.getDeviceId(), r.getInstanceNo());
        if (exist.isPresent()) {
            long id = exist.get().getId();
            jdbc.update("UPDATE sip SET action=?, verified=?, path_enable=?, val_enable=?, " +
                            "path_user=?, val_user=?, path_pwd=?, val_pwd=?, path_uri=?, val_uri=?, " +
                            "path_proxy=?, val_proxy=?, path_port=?, val_port=? WHERE id=?",
                    r.getAction(), r.getVerified(), r.getPathEnable(), r.getValEnable(),
                    r.getPathUser(), r.getValUser(), r.getPathPwd(), r.getValPwd(),
                    r.getPathUri(), r.getValUri(), r.getPathProxy(), r.getValProxy(),
                    r.getPathPort(), r.getValPort(), id);
            r.setId(id);
            return id;
        }
        long id = snowflake.nextId();
        jdbc.update("INSERT INTO sip(id, device_id, instance_no, action, verified, " +
                        "path_enable, val_enable, path_user, val_user, path_pwd, val_pwd, " +
                        "path_uri, val_uri, path_proxy, val_proxy, path_port, val_port) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, r.getDeviceId(), r.getInstanceNo(), r.getAction(), r.getVerified(),
                r.getPathEnable(), r.getValEnable(), r.getPathUser(), r.getValUser(),
                r.getPathPwd(), r.getValPwd(), r.getPathUri(), r.getValUri(),
                r.getPathProxy(), r.getValProxy(), r.getPathPort(), r.getValPort());
        r.setId(id);
        return id;
    }
}
