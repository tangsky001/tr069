package com.tr069.acs.api.service;

import com.tr069.acs.template.service.ConfigTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页概览统计：设备总量、在线离线、品牌分布、已下发未下发。
 *
 * <p>在线判定用「最近上报时间落在窗口内」而不是内存会话：会话是进程内的，
 * 服务重启或多实例部署时内存会话并不可靠，而 last_contact_time 是落库的，
 * 任何实例看到的结论都一致。窗口默认 900 秒，覆盖常见的周期性 Inform 间隔
 * （多为 300~1800 秒），可按现场设备配置调整。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** cpe_device 逻辑删除字段值，MyBatis-Plus 的 @TableLogic 默认 0 表示未删除。 */
    private static final int NOT_DELETED = 0;

    private final JdbcTemplate jdbcTemplate;
    private final ConfigTemplateService configTemplateService;

    /** 判定在线的「最近上报」时间窗口(秒)。 */
    @Value("${acs.dashboard.online-window-seconds:900}")
    private long onlineWindowSeconds;

    /**
     * 首页概览。
     */
    public Map<String, Object> overview() {
        Map<String, Object> data = new LinkedHashMap<>();

        int deviceTotal = count("SELECT COUNT(*) FROM cpe_device WHERE deleted = ?", NOT_DELETED);
        int online = count("SELECT COUNT(*) FROM cpe_device WHERE deleted = ? "
                + "AND last_contact_time >= DATE_SUB(NOW(), INTERVAL ? SECOND)", NOT_DELETED, onlineWindowSeconds);
        int offline = Math.max(0, deviceTotal - online);

        int dispatched = count("SELECT COUNT(DISTINCT w.sn) FROM worklist w "
                + "INNER JOIN cpe_device d ON d.sn = w.sn AND d.deleted = ?", NOT_DELETED);
        int undispatched = Math.max(0, deviceTotal - dispatched);

        data.put("deviceTotal", deviceTotal);
        data.put("online", online);
        data.put("offline", offline);
        data.put("onlineWindowSeconds", onlineWindowSeconds);
        data.put("dispatched", dispatched);
        data.put("undispatched", undispatched);
        data.put("brands", brands());
        data.put("worklistStats", worklistStats());
        data.put("templateStats", templateStats());
        return data;
    }

    /**
     * 品牌（厂商）分布，空厂商用 OUI 兜底。
     */
    private List<Map<String, Object>> brands() {
        List<Map<String, Object>> brands = new ArrayList<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT COALESCE(NULLIF(TRIM(manufacturer), ''), NULLIF(TRIM(oui), ''), '未识别') AS brand, "
                        + "COUNT(*) AS total "
                        + "FROM cpe_device WHERE deleted = ? "
                        + "GROUP BY brand ORDER BY total DESC, brand ASC",
                NOT_DELETED);
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.get("brand"));
            item.put("count", toInt(row.get("total")));
            brands.add(item);
        }
        return brands;
    }

    /**
     * 工单执行结果统计。
     */
    private Map<String, Object> worklistStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        int total = count("SELECT COUNT(*) FROM worklist");
        stats.put("total", total);
        stats.put("success", count("SELECT COUNT(*) FROM worklist WHERE status = 'success'"));
        stats.put("fail", count("SELECT COUNT(*) FROM worklist WHERE status IN ('fail', 'abnormal')"));
        stats.put("running", count("SELECT COUNT(*) FROM worklist WHERE status IN "
                + "('build', 'bind', 'standby', 'running')"));
        return stats;
    }

    /**
     * 配置模板统计：意图模板总数与启用数。
     */
    private Map<String, Object> templateStats() {
        int enabled = 0;
        for (Map<String, Object> template : configTemplateService.list(null, null)) {
            if (Boolean.TRUE.equals(template.get("enabled"))) {
                enabled++;
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", configTemplateService.count());
        stats.put("enabled", enabled);
        return stats;
    }

    private int count(String sql, Object... args) {
        try {
            Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
            return value == null ? 0 : value;
        } catch (Exception e) {
            log.warn("概览统计查询失败: {} -> {}", sql, e.getMessage());
            return 0;
        }
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? 0 : Integer.parseInt(String.valueOf(value));
    }
}
