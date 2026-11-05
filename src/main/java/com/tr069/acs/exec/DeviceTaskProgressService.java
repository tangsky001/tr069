package com.tr069.acs.exec;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.exec.entity.DeviceTask;
import com.tr069.acs.exec.mapper.DeviceTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 下发任务进度查询：给列表页每行显示「正在下发 xxx」 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceTaskProgressService {

    private final DeviceTaskMapper taskMapper;

    /** 算「正在进行」的两个状态：已受理还没排到 / 指令已在队列里跑 */
    private static final List<String> ACTIVE_STATUS =
            List.of(DeviceTask.STATUS_PENDING, DeviceTask.STATUS_RUNNING);

    /** 批量查一批设备的进行中任务 */
    public Map<String, Map<String, Object>> activeBatch(List<String> sns) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        if (sns == null || sns.isEmpty()) {
            return result;
        }
        for (String sn : sns) {
            result.put(sn, empty(sn));
        }
        List<DeviceTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<DeviceTask>()
                .in(DeviceTask::getSn, sns)
                .in(DeviceTask::getStatus, ACTIVE_STATUS)
                .orderByDesc(DeviceTask::getId));
        // 一台设备可能同时压好几条任务，界面只显示最新那条，其余折叠成「还有 n 条」
        Map<String, List<DeviceTask>> grouped = new LinkedHashMap<>();
        for (DeviceTask task : tasks) {
            grouped.computeIfAbsent(task.getSn(), k -> new ArrayList<>()).add(task);
        }
        for (Map.Entry<String, List<DeviceTask>> entry : grouped.entrySet()) {
            List<DeviceTask> rows = entry.getValue();
            if (rows.isEmpty()) {
                continue;
            }
            DeviceTask head = rows.get(0);
            Map<String, Object> taskView = new LinkedHashMap<>();
            taskView.put("id", head.getId());
            taskView.put("taskNo", head.getTaskNo());
            taskView.put("category", head.getCategory());
            taskView.put("status", head.getStatus());
            taskView.put("label", labelOf(head.getCategory(), head.getStatus()));
            taskView.put("phase", phaseOf(head.getStatus()));
            // 行内小标签要的是「排队 / 执行」两态，页面原来拿 PENDING 字符串自己判
            taskView.put("phaseKey", DeviceTask.STATUS_PENDING.equals(head.getStatus()) ? "queued" : "running");
            taskView.put("createdAt", head.getCreatedAt() == null ? null : head.getCreatedAt().toString());
            taskView.put("queuedOthers", rows.size() - 1);
            // 折叠文案也在这里拼：条数是后端数的，文案不该在页面上拼一次
            taskView.put("queuedText", rows.size() > 1 ? "还有 " + (rows.size() - 1) + " 条任务排队" : "");
            result.computeIfAbsent(entry.getKey(), DeviceTaskProgressService::empty)
                    .put("tasks", List.of(taskView));
        }
        return result;
    }

    private static Map<String, Object> empty(String sn) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sn", sn);
        row.put("tasks", List.of());
        return row;
    }

    /** 任务正在做什么，直接给页面上「正在下发 xxx」用 */
    private static String labelOf(String category, String status) {
        String name = wordOf(category);
        if (DeviceTask.STATUS_PENDING.equals(status)) {
            return "待下发 " + name;
        }
        return "正在下发 " + name;
    }

    private static String wordOf(String category) {
        if (category == null || category.isBlank()) {
            return "业务参数";
        }
        return switch (category.trim().toUpperCase(Locale.ROOT)) {
            case "WAN_DHCP" -> "WAN 参数（DHCP）";
            case "WAN_PPPOE" -> "WAN 参数（PPPoE）";
            case "WAN_STATIC" -> "WAN 参数（静态 IP）";
            case "WAN_BRIDGE" -> "WAN 桥接参数";
            case "WIFI_24G" -> "2.4G WiFi 参数";
            case "WIFI_58G" -> "5.8G WiFi 参数";
            case "SIP" -> "语音参数";
            case "OTHER" -> "设备管理参数";
            default -> "业务参数";
        };
    }

    /** 人看得懂的阶段名：排队 / 下发中 */
    private static String phaseOf(String status) {
        return DeviceTask.STATUS_PENDING.equals(status) ? "排队中" : "下发中";
    }
}
