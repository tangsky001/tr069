package com.PilotACS.dashboard;

import com.PilotACS.common.log.Logs;
import com.PilotACS.olt.entity.OltBoard;
import com.PilotACS.olt.entity.OltDevice;
import com.PilotACS.olt.mapper.OltBoardMapper;
import com.PilotACS.olt.mapper.OltDeviceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页概览数据：左 OLT（设备台账 + 在线离线状态），右光猫（板卡端口快照聚合）
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final OltDeviceMapper deviceMapper;
    private final OltBoardMapper boardMapper;
    private final ObjectMapper objectMapper;

    /** 概览聚合数据 */
    public Map<String, Object> overview() {
        List<OltDevice> devices = deviceMapper.selectList(new LambdaQueryWrapper<OltDevice>()
                .orderByAsc(OltDevice::getId));

        Map<String, Object> olt = oltSection(devices);
        Map<String, Object> ont = ontSection(devices);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("olt", olt);
        result.put("ont", ont);
        return result;
    }

    /** 左侧 OLT：状态统计 + 设备列表（含最近离线原因/时间） */
    private Map<String, Object> oltSection(List<OltDevice> devices) {
        int online = 0;
        int offline = 0;
        int unknown = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OltDevice device : devices) {
            String status = device.getStatus() == null ? "UNKNOWN" : device.getStatus();
            switch (status) {
                case "ONLINE" -> online++;
                case "OFFLINE" -> offline++;
                default -> unknown++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", device.getId());
            row.put("name", device.getName());
            row.put("host", device.getHost());
            // 登录方式与展示文案（TELNET→Telnet / SSH→SSH）
            row.put("protocol", device.getProtocol());
            row.put("protocolText", protocolText(device.getProtocol()));
            row.put("vendor", device.getVendor());
            row.put("vendorText", vendorText(device.getVendor()));
            row.put("model", device.getModel());
            row.put("softwareVersion", device.getSoftwareVersion());
            row.put("status", status);
            row.put("statusText", statusText(status));
            row.put("uptime", device.getUptime());
            row.put("lastConnectAt", device.getLastConnectAt());
            row.put("lastSyncAt", device.getLastSyncAt());
            // 离线原因与离线时间：lastError + updatedAt
            row.put("lastError", device.getLastError());
            row.put("offlineAt", "OFFLINE".equals(status) ? device.getUpdatedAt() : null);
            rows.add(row);
        }
        Map<String, Object> olt = new LinkedHashMap<>();
        olt.put("total", devices.size());
        olt.put("online", online);
        olt.put("offline", offline);
        olt.put("unknown", unknown);
        olt.put("onlineRate", rate(online, online + offline));
        olt.put("devices", rows);
        return olt;
    }

    /** 右侧光猫：从板卡端口快照聚合 ONT 总数/在线数，按 OLT 分组 */
    private Map<String, Object> ontSection(List<OltDevice> devices) {
        Map<Long, int[]> byOlt = new LinkedHashMap<>();
        int total = 0;
        int online = 0;
        for (OltBoard board : boardMapper.selectList(null)) {
            int[] pair = byOlt.computeIfAbsent(board.getOltId(), k -> new int[2]);
            try {
                if (board.getPortsJson() != null && !board.getPortsJson().isBlank()) {
                    JsonNode ports = objectMapper.readTree(board.getPortsJson());
                    for (JsonNode port : ports) {
                        int ontTotal = port.path("onuTotal").asInt(0);
                        int ontOnline = port.path("onuOnline").asInt(0);
                        pair[0] += ontTotal;
                        pair[1] += ontOnline;
                        total += ontTotal;
                        online += ontOnline;
                    }
                }
            } catch (Exception e) {
                Logs.warn(Logs.Module.OLT, "概览解析端口快照失败", "板卡", board.getId(), "原因", e.getMessage());
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OltDevice device : devices) {
            int[] pair = byOlt.getOrDefault(device.getId(), new int[2]);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("oltId", device.getId());
            row.put("name", device.getName());
            row.put("host", device.getHost());
            row.put("vendorText", vendorText(device.getVendor()));
            row.put("status", device.getStatus());
            row.put("statusText", statusText(device.getStatus()));
            row.put("total", pair[0]);
            row.put("online", pair[1]);
            row.put("offline", Math.max(pair[0] - pair[1], 0));
            rows.add(row);
        }
        Map<String, Object> ont = new LinkedHashMap<>();
        ont.put("total", total);
        ont.put("online", online);
        ont.put("offline", Math.max(total - online, 0));
        ont.put("onlineRate", rate(online, total));
        ont.put("byOlt", rows);
        return ont;
    }

    /** 登录方式展示文案 */
    private String protocolText(String protocol) {
        return "SSH".equalsIgnoreCase(protocol) ? "SSH" : "Telnet";
    }

    /** 厂商展示文案：前端不再维护映射表 */
    private String vendorText(String vendor) {
        if (vendor == null || vendor.isBlank()) {
            return "—";
        }
        return switch (vendor.toUpperCase()) {
            case "HUAWEI" -> "华为";
            case "HSGQ" -> "鸿升光";
            case "ZTE" -> "中兴";
            case "FIBERHOME" -> "烽火";
            case "GENERIC" -> "通用";
            default -> vendor;
        };
    }

    /** 状态展示文案 */
    private String statusText(String status) {
        if (status == null) {
            return "未采集";
        }
        return switch (status) {
            case "ONLINE" -> "在线";
            case "OFFLINE" -> "离线";
            default -> "未采集";
        };
    }

    /** 在线率：分母为 0 时返回 0 */
    private int rate(int part, int total) {
        return total <= 0 ? 0 : Math.round(part * 100f / total);
    }

    /** 分页兜底：设备很多时列表只取前 N 台，概览页面用小表 */
    @SuppressWarnings("unused")
    private void noop(Page<OltDevice> page) {
    }
}
