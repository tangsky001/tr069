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
import java.util.Locale;
import java.util.Map;

/**
 * 首页概览统计：设备总量、在线离线、品牌分布、已下发未下发。
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
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT COALESCE(NULLIF(TRIM(manufacturer), ''), NULLIF(TRIM(oui), ''), '未识别') AS brand, "
                        + "COUNT(*) AS total "
                        + "FROM cpe_device WHERE deleted = ? "
                        + "GROUP BY brand",
                NOT_DELETED);
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = brandName(String.valueOf(row.get("brand")));
            merged.merge(name, toInt(row.get("total")), Integer::sum);
        }
        List<Map<String, Object>> brands = new ArrayList<>(merged.size());
        merged.entrySet().stream()
                .sorted((a, b) -> b.getValue().equals(a.getValue())
                        ? a.getKey().compareTo(b.getKey())
                        : b.getValue() - a.getValue())
                .forEach(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", e.getKey());
                    item.put("count", e.getValue());
                    brands.add(item);
                });
        return brands;
    }

    /**
     * 厂商品牌映射：上报的厂商名（大小写不敏感，取子串）-> 现场用的中文品牌。
     *
     * <p>表里放各家固件实际会写的字样。英文那列是固件上报的写法，中文那列是
     * 部分国产厂商直接回中文的情况，两种都要能认。</p>
     */
    private static final Map<String, String> BRAND_NAMES = brandNames();

    private static Map<String, String> brandNames() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("huawei", "华为");
        map.put("华为", "华为");
        map.put("ciot", "中移物联");
        map.put("中移", "中移物联");
        map.put("raisecom", "瑞斯达康");
        map.put("瑞斯", "瑞斯达康");
        map.put("zte", "中兴");
        map.put("中兴", "中兴");
        map.put("fiberhome", "烽火");
        map.put("烽火", "烽火");
        // 创维固件有的报 SKYWORTH、有的只报 SKYW 前缀，取前四个字母覆盖两种
        map.put("skyw", "创维");
        map.put("创维", "创维");
        map.put("tianyi", "天邑");
        map.put("天邑", "天邑");
        map.put("youhua", "友华");
        map.put("友华", "友华");
        map.put("zhaoneng", "兆能");
        // 兆能固件也直接报缩写 ZNXT
        map.put("znxt", "兆能");
        map.put("兆能", "兆能");
        return map;
    }

    /** 厂商名归一：认得的换成中文品牌，认不得的保留原样。 */
    private static String brandName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || "未识别".equals(name)) {
            return "未识别";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        // RC 是 RAISECOM 的简称，MSG2100E 上报的厂商就是它。
        // 这两个字太短，做包含匹配会把 Arcadyan 之类一起吞掉，只能整名比对
        if ("rc".equals(lower)) {
            return "瑞斯达康";
        }
        for (Map.Entry<String, String> entry : BRAND_NAMES.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return name;
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
