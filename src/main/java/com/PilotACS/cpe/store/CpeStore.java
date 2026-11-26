/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.store;

import com.PilotACS.cpe.model.CpeParam;
import com.PilotACS.cpe.redis.ParamTreeCache;
import com.PilotACS.cpe.rpc.TaskPlanner;
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
import java.util.UUID;

/** CPE 存储访问层（MySQL 台账/任务 + SQLite 参数树/机型模板） */
@Repository
public class CpeStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** MySQL 主库（设备台账/任务） */
    private final JdbcTemplate jdbcTemplate;
    /** SQLite template.pack（参数树/机型模板） */
    private final JdbcTemplate cpeJdbcTemplate;
    /** SQLite 事务（整树重写用） */
    private final TransactionTemplate cpeTxTemplate;
    private final ParamTreeCache paramTreeCache;

    public CpeStore(@Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate,
                    @Qualifier("cpeJdbcTemplate") JdbcTemplate cpeJdbcTemplate,
                    @Qualifier("cpeTxTemplate") TransactionTemplate cpeTxTemplate,
                    ParamTreeCache paramTreeCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.cpeJdbcTemplate = cpeJdbcTemplate;
        this.cpeTxTemplate = cpeTxTemplate;
        this.paramTreeCache = paramTreeCache;
    }

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

    /** Inform 事件码落台账 */
    public void markEvents(String sn, Long bootMs, Long bootstrapMs, Long periodicMs, Long valueChangeMs,
                           Long connReqMs, Long diagMs) {
        jdbcTemplate.update("""
                UPDATE cpe_device SET
                    last_boot_ms = COALESCE(?, last_boot_ms),
                    last_bootstrap_ms = COALESCE(?, last_bootstrap_ms),
                    last_periodic_ms = COALESCE(?, last_periodic_ms),
                    last_valuechange_ms = COALESCE(?, last_valuechange_ms),
                    last_connreq_ms = COALESCE(?, last_connreq_ms),
                    last_diag_ms = COALESCE(?, last_diag_ms),
                    updated_at = ?
                WHERE sn = ?
                """, bootMs, bootstrapMs, periodicMs, valueChangeMs, connReqMs, diagMs,
                System.currentTimeMillis(), sn);
    }

    public Map<String, Object> findDevice(String sn) {
        return jdbcTemplate.queryForMap("SELECT * FROM cpe_device WHERE sn = ?", sn);
    }

    /** MAC/LOID 参数值反查设备 SN */
    public List<String> findSnsByParamValue(String value) {
        return cpeJdbcTemplate.queryForList("""
                SELECT DISTINCT device_sn FROM cpe_device_param
                WHERE value LIKE ? AND (path LIKE '%MACAddress' OR path LIKE '%LOID' OR path LIKE '%LogicalID')
                LIMIT 200
                """, String.class, "%" + value + "%");
    }

    /** 房间号反查设备 SN */
    public List<String> findSnsByRoom(String room) {
        return jdbcTemplate.queryForList(
                "SELECT sn FROM cpe_device WHERE room_no LIKE ? LIMIT 500",
                String.class, "%" + room + "%");
    }

    public boolean deviceExists(String sn) {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cpe_device WHERE sn = ?", Integer.class, sn);
        return n != null && n > 0;
    }

    /** 设备分页 */
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

    /** 超时未上报的设备翻成离线 */
    public void markOfflineStale(long deadlineMs) {
        jdbcTemplate.update("""
                UPDATE cpe_device SET status='offline', updated_at=?
                WHERE status='online' AND (last_inform_ms IS NULL OR last_inform_ms < ?)
                """, System.currentTimeMillis(), deadlineMs);
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
        set.append(", updated_at = ?");
        args.add(System.currentTimeMillis());
        args.add(sn);
        jdbcTemplate.update("UPDATE cpe_device SET " + set + " WHERE sn = ?", args.toArray());
    }

    /** 保存业务快照（整份 JSON 覆盖） */
    public void saveSnapshot(String sn, String json) {
        jdbcTemplate.update("""
                INSERT INTO cpe_device_snapshot (sn, snapshot_json, updated_at) VALUES (?,?,?)
                ON DUPLICATE KEY UPDATE snapshot_json = VALUES(snapshot_json), updated_at = VALUES(updated_at)
                """, sn, json, System.currentTimeMillis());
    }

    public String findSnapshot(String sn) {
        List<String> list = jdbcTemplate.queryForList(
                "SELECT snapshot_json FROM cpe_device_snapshot WHERE sn = ?", String.class, sn);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 初始化下发是否已完成 */
    public boolean isInitProvisioned(String sn) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cpe_device WHERE sn = ? AND init_provisioned = 1", Integer.class, sn);
        return n != null && n > 0;
    }

    /** 标记初始化下发完成 */
    public void markInitProvisioned(String sn) {
        jdbcTemplate.update(
                "UPDATE cpe_device SET init_provisioned = 1, init_provisioned_at = ?, updated_at = ? WHERE sn = ?",
                System.currentTimeMillis(), System.currentTimeMillis(), sn);
    }

    /** 恢复出厂后重置初始化标记 */
    public void clearInitProvisioned(String sn) {
        jdbcTemplate.update(
                "UPDATE cpe_device SET init_provisioned = 0, init_provisioned_at = NULL, updated_at = ? WHERE sn = ?",
                System.currentTimeMillis(), sn);
    }

    private static final String DEVICE_BRIEF_COLS =
            "sn, oui, manufacturer, product_class, software_version, isp, status, last_inform_ms, connection_request_url";

    /** 按 SN 批量取台账 */
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

    /** 全量台账简要 */
    public List<Map<String, Object>> allDevices() {
        return jdbcTemplate.queryForList(
                "SELECT " + DEVICE_BRIEF_COLS + " FROM cpe_device ORDER BY last_inform_ms DESC");
    }

    /** 存量参数树（走 Redis 缓存） */
    public Map<String, CpeParam> loadParams(String sn) {
        return paramTreeCache.getOrLoad(sn, this::loadParamsFromDb);
    }

    private Map<String, CpeParam> loadParamsFromDb(String sn) {
        Map<String, CpeParam> out = new LinkedHashMap<>();
        cpeJdbcTemplate.query(
                "SELECT path, value, value_type, object, writable, ts FROM cpe_device_param WHERE device_sn = ?",
                rs -> {
                    out.put(rs.getString(1), new CpeParam(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4) == 1, rs.getInt(5) == 1, rs.getLong(6)));
                }, sn);
        return out;
    }

    /**
     * 参数树落库（只落白名单内的参数，见 {@link ParamTreeFilter}）：
     * 全量同步 = 全删重插（自然只留白名单）；增量 = 只 upsert 白名单内的脏路径，
     * 并把存量库里白名单外的行清掉（老数据收敛，避免占库）。
     */
    public void saveDeviceParams(String sn, Map<String, CpeParam> all, Set<String> dirty, boolean fullSync,
                                 Set<String> tombstones) {
        Map<String, CpeParam> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, CpeParam> e : all.entrySet()) {
            if (ParamTreeFilter.keep(e.getKey())) {
                filtered.put(e.getKey(), e.getValue());
            }
        }
        Set<String> dirtyKept = dirty == null ? Set.of()
                : dirty.stream().filter(ParamTreeFilter::keep).collect(java.util.stream.Collectors.toSet());
        cpeTxTemplate.executeWithoutResult(st -> {
            if (fullSync) {
                cpeJdbcTemplate.update("DELETE FROM cpe_device_param WHERE device_sn = ?", sn);
                insertParams(sn, filtered.values());
            } else {
                if (!dirtyKept.isEmpty()) {
                    List<CpeParam> changed = dirtyKept.stream()
                            .map(filtered::get).filter(p -> p != null).toList();
                    insertParams(sn, changed);
                }
                if (tombstones != null && !tombstones.isEmpty()) {
                    cpeJdbcTemplate.batchUpdate(
                            "DELETE FROM cpe_device_param WHERE device_sn = ? AND path = ?",
                            tombstones.stream().map(p -> new Object[]{sn, p}).toList());
                }
                purgeOutsideWhitelist(sn);
            }
        });
        paramTreeCache.evict(sn);
    }

    /** 存量库里白名单外的行清掉（老数据迁移收敛；对象行不在清理范围） */
    private void purgeOutsideWhitelist(String sn) {
        List<String> junk = new ArrayList<>();
        cpeJdbcTemplate.query("SELECT path FROM cpe_device_param WHERE device_sn = ?", rs -> {
            String p = rs.getString(1);
            if (!ParamTreeFilter.keep(p)) {
                junk.add(p);
            }
        }, sn);
        if (!junk.isEmpty()) {
            cpeJdbcTemplate.batchUpdate("DELETE FROM cpe_device_param WHERE device_sn = ? AND path = ?",
                    junk.stream().map(p -> new Object[]{sn, p}).toList());
        }
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

    /** 台账页派生字段用的少量参数 */
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

    /** 保存机型模板（冲突时整树覆盖） */
    public long saveModel(String brand, String model, String version, String isp, String ispVersion,
                          String rootNode, String capabilityJson, String sourceSn, Map<String, CpeParam> params) {
        long now = System.currentTimeMillis();
        List<Long> ids = cpeJdbcTemplate.queryForList(
                "SELECT id FROM cpe_model WHERE brand=? AND model=? AND version=? AND isp=?",
                Long.class, brand, model, version, isp);
        long id;
        if (ids.isEmpty()) {
            cpeJdbcTemplate.update("""
                    INSERT INTO cpe_model (brand, model, version, isp, isp_version, root_node,
                                           capability_json, source_sn, status, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?, 'ready', ?, ?)
                    """, brand, model, version, isp, ispVersion, rootNode, capabilityJson, sourceSn, now, now);
            id = cpeJdbcTemplate.queryForObject(
                    "SELECT id FROM cpe_model WHERE brand=? AND model=? AND version=? AND isp=?",
                    Long.class, brand, model, version, isp);
        } else {
            id = ids.get(0);
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

    /** 精确匹配机型模板（brand/model 大小写归一，兼容外部导入库的原始大小写） */
    public Map<String, Object> findModelExact(String brand, String model, String version, String isp) {
        List<Map<String, Object>> list = cpeJdbcTemplate.queryForList(
                "SELECT * FROM cpe_model WHERE lower(brand)=lower(?) AND lower(model)=lower(?) AND version=? AND isp=?",
                brand, model, version, isp);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 宽松匹配机型模板（不比版本，brand/model 大小写归一） */
    public Map<String, Object> findModelLoose(String brand, String model, String isp) {
        List<Map<String, Object>> list = cpeJdbcTemplate.queryForList(
                "SELECT * FROM cpe_model WHERE lower(brand)=lower(?) AND lower(model)=lower(?) AND isp=?"
                        + " ORDER BY updated_at DESC LIMIT 1",
                brand, model, isp);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 兜底匹配：不限运营商（isp 未识别或口径不一致时用），优先同版本、其次最近更新 */
    public Map<String, Object> findModelAnyIsp(String brand, String model, String version) {
        List<Map<String, Object>> list = cpeJdbcTemplate.queryForList("""
                SELECT * FROM cpe_model
                WHERE lower(brand)=lower(?) AND lower(model)=lower(?)
                ORDER BY CASE WHEN version = ? THEN 0 ELSE 1 END, updated_at DESC
                LIMIT 1
                """, brand, model, version == null ? "" : version);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 模板总数（诊断日志用） */
    public int countModels() {
        Integer n = cpeJdbcTemplate.queryForObject("SELECT COUNT(*) FROM cpe_model", Integer.class);
        return n == null ? 0 : n;
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

    public long insertTask(String sn, String type, Map<String, Object> params, String createdBy) {
        long now = System.currentTimeMillis();
        String taskNo = "T" + now + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
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
        // 业务下发任务（DeleteObject/AddObject/SPV/GPV verify/Reboot）优先装载；
        // 采集探测/业务刷新排后，避免它们占满会话 RPC 预算把下发饿死
        return jdbcTemplate.queryForList("""
                SELECT * FROM cpe_task WHERE sn = ? AND status = 'queued'
                ORDER BY CASE WHEN type IN ('AdaptProbe','GetParameterValues')
                              AND created_by IN ('system-isp-detect','system-biz-refresh')
                              THEN 1 ELSE 0 END,
                         id ASC
                LIMIT 20
                """, sn);
    }

    /** 长时间排队无人派发的任务（按 sn 分组取最早一条），兜底重派发用 */
    public List<Map<String, Object>> findStaleQueuedTasks(long createdBefore, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT sn, MIN(id) AS task_id, COUNT(*) AS task_cnt
                FROM cpe_task
                WHERE status = 'queued' AND created_at < ?
                GROUP BY sn
                ORDER BY task_id ASC
                LIMIT ?
                """, createdBefore, limit);
    }

    /** 各设备未完成任务，按 sn 分组 */
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

    /** 某设备某类型在队/在跑的任务数 */
    public int countActiveTasks(String sn, String type) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cpe_task WHERE sn = ? AND type = ? AND status IN ('queued','running')",
                Integer.class, sn, type);
        return n == null ? 0 : n;
    }

    /** 某设备某类型最早的在队/在跑任务 id */
    public long findActiveTaskId(String sn, String type) {
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT id FROM cpe_task WHERE sn = ? AND type = ? AND status IN ('queued','running') "
                        + "ORDER BY id ASC LIMIT 1", sn, type);
        return list.isEmpty() ? 0 : ((Number) list.get(0).get("id")).longValue();
    }

    /** 覆写任务参数 */
    public void updateTaskParams(long id, String paramsJson) {
        jdbcTemplate.update("UPDATE cpe_task SET params_json = ? WHERE id = ?", paramsJson, id);
    }

    /** 按来源统计在队/在跑任务数 */
    public int countActiveTasks(String sn, String type, String createdBy) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM cpe_task
                WHERE sn = ? AND type = ? AND created_by = ? AND status IN ('queued','running')
                """, Integer.class, sn, type, createdBy);
        return n == null ? 0 : n;
    }

    /** 写操作类任务类型 SQL 片段 */
    private static final String NON_RETRIABLE_TYPES =
            TaskPlanner.WRITE_TYPES.stream()
                    .map(t -> "'" + t + "'")
                    .collect(java.util.stream.Collectors.joining(",", "(", ")"));

    /** 卡死任务按类型分流收口 */
    public void failStaleRunningTasks(long olderThanMs, java.util.Set<Long> excludeTaskIds) {
        long deadline = System.currentTimeMillis() - olderThanMs;
        StringBuilder placeholders = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(deadline);
        if (excludeTaskIds != null && !excludeTaskIds.isEmpty()) {
            for (Long id : excludeTaskIds) {
                placeholders.append(placeholders.length() == 0 ? "" : ',').append('?');
                args.add(id);
            }
        }
        String tail = placeholders.length() == 0 ? "" : " AND id NOT IN (" + placeholders + ")";
        jdbcTemplate.update("""
                UPDATE cpe_task SET
                    status = CASE WHEN type IN %s THEN 'fail' ELSE 'queued' END,
                    fault_code = CASE WHEN type IN %s THEN 'timeout' ELSE fault_code END,
                    fault_string = CASE WHEN type IN %s
                        THEN '下发后未收到设备回执（执行超时）。写操作类不自动重发——重发等于真实二次执行，请确认设备实际状态后重新下发'
                        ELSE '执行超时回队，待设备下次上报续跑' END
                WHERE status='running' AND started_at IS NOT NULL AND started_at < ?%s
                """.formatted(NON_RETRIABLE_TYPES, NON_RETRIABLE_TYPES, NON_RETRIABLE_TYPES, tail),
                args.toArray());
    }

    /** 给任务打续跑标记 */
    public void markTaskResume(long id) {
        Map<String, Object> row = getTask(id);
        if (row == null) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        Object pj = row.get("params_json");
        if (pj instanceof String s && !s.isBlank()) {
            try {
                params.putAll(new ObjectMapper().readValue(s, Map.class));
            } catch (Exception ignore) {
            }
        }
        if (Boolean.TRUE.equals(params.get("resume"))) {
            return;
        }
        params.put("resume", true);
        jdbcTemplate.update("UPDATE cpe_task SET params_json=? WHERE id=?", toJson(params), id);
    }

    private String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o == null ? Map.of() : o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
