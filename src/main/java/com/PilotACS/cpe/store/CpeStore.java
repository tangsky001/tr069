/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.store;

import com.PilotACS.cpe.model.CpeParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CPE 存储访问层，按职责拆两库：
 * <ul>
 *   <li>MySQL（主库 jdbcTemplate）：设备台账 cpe_device、下发任务 cpe_task</li>
 *   <li>SQLite template.pack（cpeJdbcTemplate）：设备参数树（能力树）cpe_device_param、
 *       机型模板 cpe_model/cpe_model_param（品牌+型号+版本号+运营商 绑定参数树）</li>
 * </ul>
 */
@Repository
public class CpeStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** MySQL 主库（设备台账/任务） */
    private final JdbcTemplate jdbcTemplate;
    /** SQLite template.pack（参数树/机型模板） */
    private final JdbcTemplate cpeJdbcTemplate;
    /** SQLite 事务（整树重写用） */
    private final TransactionTemplate cpeTxTemplate;

    /** 容器里有两个 JdbcTemplate/TransactionTemplate，必须显式限定，不能靠参数名推断 */
    public CpeStore(@Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate,
                    @Qualifier("cpeJdbcTemplate") JdbcTemplate cpeJdbcTemplate,
                    @Qualifier("cpeTxTemplate") TransactionTemplate cpeTxTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.cpeJdbcTemplate = cpeJdbcTemplate;
        this.cpeTxTemplate = cpeTxTemplate;
    }

    // ==================== 设备台账（MySQL） ====================

    /** Inform 时一次性刷新设备台账 */
    public void upsertDevice(Map<String, Object> row) {
        long now = System.currentTimeMillis();
        Object sn = row.get("sn");
        Object oui = row.get("oui");
        Object manufacturer = row.get("manufacturer");
        Object productClass = row.get("product_class");
        Object hw = row.get("hardware_version");
        Object sw = row.get("software_version");
        Object crUrl = row.get("connection_request_url");
        Object cwmpVersion = row.get("cwmp_version");
        Object rootNode = row.get("root_node");
        Object lastInform = row.get("last_inform_ms");
        jdbcTemplate.update("""
                INSERT INTO cpe_device (sn, oui, manufacturer, product_class, hardware_version, software_version,
                                        connection_request_url, cwmp_version, root_node, status, last_inform_ms,
                                        created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,'online',?,?,?)
                ON DUPLICATE KEY UPDATE
                    oui = ?, manufacturer = ?, product_class = ?, hardware_version = ?, software_version = ?,
                    connection_request_url = ?, cwmp_version = ?,
                    root_node = COALESCE(?, root_node),
                    status = 'online', last_inform_ms = ?, updated_at = ?
                """,
                sn, oui, manufacturer, productClass, hw, sw, crUrl, cwmpVersion, rootNode,
                lastInform, now, now,
                oui, manufacturer, productClass, hw, sw, crUrl, cwmpVersion, rootNode, lastInform, now);
    }

    public void markEvents(String sn, Long bootMs, Long bootstrapMs) {
        jdbcTemplate.update("""
                UPDATE cpe_device SET
                    last_boot_ms = COALESCE(?, last_boot_ms),
                    last_bootstrap_ms = COALESCE(?, last_bootstrap_ms),
                    updated_at = ?
                WHERE sn = ?
                """, bootMs, bootstrapMs, System.currentTimeMillis(), sn);
    }

    public Map<String, Object> findDevice(String sn) {
        return jdbcTemplate.queryForMap("SELECT * FROM cpe_device WHERE sn = ?", sn);
    }

    public boolean deviceExists(String sn) {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cpe_device WHERE sn = ?", Integer.class, sn);
        return n != null && n > 0;
    }

    /** 设备分页（排序键白名单在服务层处理） */
    public List<Map<String, Object>> pageDevices(String where, Object[] args, String orderBy, int offset, int size) {
        String sql = "SELECT * FROM cpe_device " + where
                + (orderBy == null ? "" : " ORDER BY " + orderBy)
                + " LIMIT ? OFFSET ?";
        Object[] all = args == null ? new Object[]{size, offset}
                : java.util.Arrays.copyOf(args, args.length + 2);
        if (args != null) {
            all[args.length] = size;
            all[args.length + 1] = offset;
        }
        return jdbcTemplate.queryForList(sql, all);
    }

    public int countDevices(String where, Object[] args) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cpe_device " + where, Integer.class, args);
        return n == null ? 0 : n;
    }

    public Map<String, Integer> deviceStat() {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("total", countDevices("", null));
        out.put("online", countDevices(" WHERE status = 'online'", null));
        out.put("offline", countDevices(" WHERE status <> 'online'", null));
        return out;
    }

    public List<Map<String, Object>> onlineDevices() {
        return jdbcTemplate.queryForList(
                "SELECT * FROM cpe_device WHERE status = 'online' ORDER BY last_inform_ms DESC");
    }

    /** 超过窗口没上报的设备翻成离线 */
    public void markOfflineStale(long deadlineMs) {
        jdbcTemplate.update(
                "UPDATE cpe_device SET status='offline', updated_at=? WHERE status='online' AND last_inform_ms < ?",
                System.currentTimeMillis(), deadlineMs);
    }

    public void updateDeviceColumns(String sn, Map<String, Object> cols) {
        if (cols.isEmpty()) {
            return;
        }
        StringBuilder set = new StringBuilder();
        List<Object> args = new ArrayList<>();
        cols.forEach((k, v) -> {
            if (!set.isEmpty()) {
                set.append(", ");
            }
            set.append(k).append(" = ?");
            args.add(v);
        });
        args.add(sn);
        jdbcTemplate.update("UPDATE cpe_device SET " + set + ", updated_at = ? WHERE sn = ?", args.toArray(),
                System.currentTimeMillis(), sn);
    }

    private static final String DEVICE_BRIEF_COLS =
            "sn, oui, manufacturer, product_class, software_version, isp, status, last_inform_ms, connection_request_url";

    /** 分组树/成员装配用：按 SN 批量取台账（MySQL） */
    public List<Map<String, Object>> devicesBySns(java.util.Collection<String> sns) {
        if (sns == null || sns.isEmpty()) {
            return List.of();
        }
        StringBuilder in = new StringBuilder();
        Object[] args = new Object[sns.size()];
        int i = 0;
        for (String sn : sns) {
            if (i > 0) {
                in.append(',');
            }
            in.append('?');
            args[i++] = sn;
        }
        return jdbcTemplate.queryForList(
                "SELECT " + DEVICE_BRIEF_COLS + " FROM cpe_device WHERE sn IN (" + in + ")", args);
    }

    /** 全量台账简要（分组树根节点"未归属光猫"装配用），最近上报倒序 */
    public List<Map<String, Object>> allDevices() {
        return jdbcTemplate.queryForList(
                "SELECT " + DEVICE_BRIEF_COLS + " FROM cpe_device ORDER BY last_inform_ms DESC");
    }

    // ==================== 设备参数树（SQLite） ====================

    public Map<String, CpeParam> loadParams(String sn) {
        Map<String, CpeParam> out = new LinkedHashMap<>();
        cpeJdbcTemplate.query(
                "SELECT path, value, value_type, object, writable, ts FROM cpe_device_param WHERE device_sn = ?",
                rs -> {
                    out.put(rs.getString(1), new CpeParam(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4) == 1, rs.getInt(5) == 1, rs.getLong(6)));
                }, sn);
        return out;
    }

    /** 会话结束统一落库：全树刷新 → 整树重写；否则只写脏路径 */
    public void saveDeviceParams(String sn, Map<String, CpeParam> all, Set<String> dirty, boolean fullSync) {
        cpeTxTemplate.executeWithoutResult(st -> {
            if (fullSync) {
                cpeJdbcTemplate.update("DELETE FROM cpe_device_param WHERE device_sn = ?", sn);
                insertParams(sn, all.values());
            } else if (dirty != null && !dirty.isEmpty()) {
                List<CpeParam> changed = dirty.stream().map(all::get).filter(p -> p != null).toList();
                insertParams(sn, changed);
            }
        });
    }

    private void insertParams(String sn, Iterable<CpeParam> params) {
        List<Object[]> batch = new ArrayList<>();
        for (CpeParam p : params) {
            batch.add(new Object[]{sn, p.path(), p.value(), p.type(), p.object() ? 1 : 0, p.writable() ? 1 : 0, p.ts()});
        }
        if (!batch.isEmpty()) {
            cpeJdbcTemplate.batchUpdate("""
                    INSERT INTO cpe_device_param (device_sn, path, value, value_type, object, writable, ts)
                    VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT(device_sn, path) DO UPDATE SET
                        value=excluded.value, value_type=excluded.value_type, object=excluded.object,
                        writable=excluded.writable, ts=excluded.ts
                    """, batch);
        }
    }

    /** 台账页派生字段用的少量参数（MAC/IP/UpTime/PON 模式/光功率） */
    public Map<String, String> loadBizParams(String sn) {
        Map<String, String> out = new LinkedHashMap<>();
        cpeJdbcTemplate.query("""
                SELECT path, value FROM cpe_device_param
                WHERE device_sn = ? AND value IS NOT NULL AND value <> '' AND (
                    path LIKE '%MACAddress' OR path LIKE '%ExternalIPAddress'
                    OR path LIKE '%UpTime' OR path LIKE '%.Mode'
                    OR path LIKE '%RxPower' OR path LIKE '%SignalLevel')
                """, rs -> {
            out.putIfAbsent(rs.getString(1), rs.getString(2));
        }, sn);
        return out;
    }

    // ==================== 机型模板（SQLite：品牌+型号+版本号+运营商 → 参数树） ====================

    /** 唯一键冲突时整树覆盖 */
    public long saveModel(String brand, String model, String version, String isp, String ispVersion,
                          String rootNode, String capabilityJson, String sourceSn, Map<String, CpeParam> params) {
        long now = System.currentTimeMillis();
        Long id = cpeJdbcTemplate.queryForObject(
                "SELECT id FROM cpe_model WHERE brand=? AND model=? AND version=? AND isp=?",
                Long.class, brand, model, version, isp);
        if (id == null) {
            cpeJdbcTemplate.update("""
                    INSERT INTO cpe_model (brand, model, version, isp, isp_version, root_node,
                                           capability_json, source_sn, status, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?, 'ready', ?, ?)
                    """, brand, model, version, isp, ispVersion, rootNode, capabilityJson, sourceSn, now, now);
            id = cpeJdbcTemplate.queryForObject(
                    "SELECT id FROM cpe_model WHERE brand=? AND model=? AND version=? AND isp=?",
                    Long.class, brand, model, version, isp);
        } else {
            cpeJdbcTemplate.update("""
                    UPDATE cpe_model SET isp_version=?, root_node=?, capability_json=?, source_sn=?,
                                          status='ready', updated_at=? WHERE id=?
                    """, ispVersion, rootNode, capabilityJson, sourceSn, now, id);
        }
        long mid = id;
        cpeTxTemplate.executeWithoutResult(st -> {
            cpeJdbcTemplate.update("DELETE FROM cpe_model_param WHERE model_id = ?", mid);
            List<Object[]> batch = new ArrayList<>();
            for (CpeParam p : params.values()) {
                batch.add(new Object[]{mid, p.path(), p.value(), p.type(), p.object() ? 1 : 0, p.writable() ? 1 : 0});
            }
            if (!batch.isEmpty()) {
                cpeJdbcTemplate.batchUpdate("""
                        INSERT INTO cpe_model_param (model_id, path, value, value_type, object, writable)
                        VALUES (?,?,?,?,?,?)
                        ON CONFLICT(model_id, path) DO UPDATE SET
                            value=excluded.value, value_type=excluded.value_type,
                            object=excluded.object, writable=excluded.writable
                        """, batch);
            }
        });
        return mid;
    }

    public Map<String, Object> findModel(long id) {
        return cpeJdbcTemplate.queryForMap("SELECT * FROM cpe_model WHERE id = ?", id);
    }

    /** 精确匹配：品牌+型号+版本+运营商 */
    public Map<String, Object> findModelExact(String brand, String model, String version, String isp) {
        List<Map<String, Object>> list = cpeJdbcTemplate.queryForList(
                "SELECT * FROM cpe_model WHERE brand=? AND model=? AND version=? AND isp=?",
                brand, model, version, isp);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 宽松匹配：同品牌+型号+运营商，忽略版本（取最近更新的） */
    public Map<String, Object> findModelLoose(String brand, String model, String isp) {
        List<Map<String, Object>> list = cpeJdbcTemplate.queryForList(
                "SELECT * FROM cpe_model WHERE brand=? AND model=? AND isp=? ORDER BY updated_at DESC LIMIT 1",
                brand, model, isp);
        return list.isEmpty() ? null : list.get(0);
    }

    public Map<String, CpeParam> loadModelParams(long modelId) {
        Map<String, CpeParam> out = new LinkedHashMap<>();
        cpeJdbcTemplate.query(
                "SELECT path, value, value_type, object, writable FROM cpe_model_param WHERE model_id = ?",
                rs -> {
                    out.put(rs.getString(1), new CpeParam(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4) == 1, rs.getInt(5) == 1, CpeParam.TS_UNKNOWN));
                },
                modelId);
        return out;
    }

    public List<Map<String, Object>> listModels() {
        return cpeJdbcTemplate.queryForList("SELECT * FROM cpe_model ORDER BY updated_at DESC");
    }

    // ==================== 任务（MySQL） ====================

    public long insertTask(String sn, String type, Map<String, Object> params, String createdBy) {
        long now = System.currentTimeMillis();
        String taskNo = "T" + now + String.format("%04d", (int) (now % 10000));
        jdbcTemplate.update("""
                INSERT INTO cpe_task (task_no, sn, type, params_json, status, created_by, created_at)
                VALUES (?,?,?,?, 'queued', ?, ?)
                """, taskNo, sn, type, toJson(params), createdBy, now);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM cpe_task WHERE task_no = ?", Long.class, taskNo);
        return id == null ? 0 : id;
    }

    public Map<String, Object> getTask(long id) {
        List<Map<String, Object>> list = jdbcTemplate.queryForList("SELECT * FROM cpe_task WHERE id = ?", id);
        return list.isEmpty() ? null : list.get(0);
    }

    public void updateTask(long id, String status, String faultCode, String faultString, String resultContent) {
        jdbcTemplate.update("""
                UPDATE cpe_task SET status=?, fault_code=?, fault_string=?, result_content=?,
                    started_at = COALESCE(started_at, CASE WHEN ?='running' THEN ? END),
                    finished_at = CASE WHEN ? IN ('success','fail') THEN ? ELSE finished_at END
                WHERE id = ?
                """, status, faultCode, faultString, resultContent, status, System.currentTimeMillis(),
                status, System.currentTimeMillis(), id);
    }

    public List<Map<String, Object>> loadQueuedTasks(String sn) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM cpe_task WHERE sn = ? AND status = 'queued' ORDER BY id ASC LIMIT 20", sn);
    }

    /** 各设备未完成任务（queued/running），按 sn 分组 */
    public Map<String, List<Map<String, Object>>> activeTasksBySn(List<String> sns) {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        if (sns == null || sns.isEmpty()) {
            return out;
        }
        StringBuilder in = new StringBuilder();
        Object[] args = new Object[sns.size()];
        for (int i = 0; i < sns.size(); i++) {
            if (i > 0) {
                in.append(',');
            }
            in.append('?');
            args[i] = sns.get(i);
        }
        jdbcTemplate.queryForList(
                "SELECT id, task_no, sn, type, status, created_at FROM cpe_task WHERE sn IN (" + in
                        + ") AND status IN ('queued','running') ORDER BY id ASC", args)
                .forEach(row -> out.computeIfAbsent((String) row.get("sn"), k -> new ArrayList<>()).add(row));
        return out;
    }

    public int countQueued(String sn) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cpe_task WHERE sn = ? AND status IN ('queued','running')", Integer.class, sn);
        return n == null ? 0 : n;
    }

    /** 某设备某类型在队/在跑的任务数（自动排任务防重复用） */
    public int countActiveTasks(String sn, String type) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cpe_task WHERE sn = ? AND type = ? AND status IN ('queued','running')",
                Integer.class, sn, type);
        return n == null ? 0 : n;
    }

    /** 兜底：把卡在 running 的任务判死 */
    public void failStaleRunningTasks(long olderThanMs) {
        long deadline = System.currentTimeMillis() - olderThanMs;
        jdbcTemplate.update("""
                UPDATE cpe_task SET status='fail', fault_string='会话超时中断', finished_at=?
                WHERE status='running' AND started_at IS NOT NULL AND started_at < ?
                """, System.currentTimeMillis(), deadline);
    }

    private String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o == null ? Map.of() : o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
