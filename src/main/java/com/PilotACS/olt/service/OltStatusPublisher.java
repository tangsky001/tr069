package com.PilotACS.olt.service;

import com.PilotACS.common.log.Logs;
import com.PilotACS.common.ws.DashboardWsHandler;
import com.PilotACS.olt.entity.OltDevice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/** OLT 在线/离线状态变更 → 首页概览 WebSocket 实时推送 */
@Service
@RequiredArgsConstructor
public class OltStatusPublisher {

    /** 推送字段全部在后端算好，前端只渲染 */
    private static final DateTimeFormatter TIME_TEXT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DashboardWsHandler wsHandler;

    /** 上线事件 */
    public void publishOnline(OltDevice device) {
        publish(device, "online", null);
    }

    /** 离线事件：reason 即设备 lastError（连接失败原因） */
    public void publishOffline(OltDevice device, String reason) {
        publish(device, "offline", reason);
    }

    private void publish(OltDevice device, String status, String reason) {
        boolean online = "online".equals(status);
        Map<String, Object> data = new HashMap<>();
        data.put("deviceId", device.getId());
        data.put("name", device.getName());
        data.put("host", device.getHost());
        data.put("vendor", device.getVendor());
        data.put("model", device.getModel());
        data.put("status", status);
        data.put("statusText", online ? "上线" : "离线");
        // 设备台账口径的状态值，前端直接回写行数据
        data.put("deviceStatus", online ? "ONLINE" : "OFFLINE");
        data.put("time", LocalDateTime.now().toString());
        data.put("timeText", TIME_TEXT.format(LocalDateTime.now()));
        data.put("reason", reason == null ? "" : reason);
        try {
            wsHandler.broadcast(DashboardWsHandler.envelope("olt-status", data));
            Logs.info(Logs.Module.OLT, "状态变更已推送到概览页",
                    "设备", device.getName(), "状态", status, "原因", reason == null ? "-" : reason);
        } catch (Exception e) {
            // 推送失败不影响业务主流程
            Logs.warn(Logs.Module.OLT, "状态推送失败", "设备", device.getName(), "原因", e.getMessage());
        }
    }
}
