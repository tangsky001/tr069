/**
 * @author: tangsky001
 * @date:   2026年11月19日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.redis.DeviceInfoCache;
import com.PilotACS.cpe.redis.RedisTaskQueue;
import com.PilotACS.cpe.server.DeliveryWaiter;
import com.PilotACS.cpe.store.CpeStore;
import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 任务派发器：消费 tr069:queue:rpc，对离线设备的排队任务发起 Connection Request 唤醒。 */
@Component
@RequiredArgsConstructor
public class TaskDispatcher implements RedisTaskQueue.DispatchHandler {

    private final CpeStore store;
    private final ConnectionRequestService connectionRequestService;
    private final DeviceInfoCache infoCache;
    private final DeliveryWaiter deliveryWaiter;

    @Override
    public void dispatch(String sn, long taskId) {
        Map<String, Object> task = store.getTask(taskId);
        if (task == null || !"queued".equals(String.valueOf(task.get("status")))) {
            Logs.debug(Logs.Module.CPE_PUSH, "派发消息对应任务已失效，跳过唤醒", "sn", sn, "任务", taskId);
            return;
        }
        // 设备当前有挂起的空 POST：直接 signal 唤醒同跳下发，不要打 CR（可能串台/无效）
        if (deliveryWaiter.hasPendingHop(sn)) {
            deliveryWaiter.signal(sn);
            Logs.info(Logs.Module.CPE_PUSH, "设备有挂起跳，指令直接同跳下发（跳过 CR 反踢）",
                    "sn", sn, "任务", taskId);
            return;
        }
        wakeNow(sn, taskId);
    }

    /** 查档案并反向唤醒 */
    public void wakeNow(String sn, long taskId) {
        Map<String, Object> device = null;
        try {
            device = infoCache.get(sn);
            if (device == null) {
                device = store.findDevice(sn);
                infoCache.put(sn, device);
            }
        } catch (Exception ignore) {
        }
        String crUrl = device == null ? null : str(device.get("connection_request_url"));
        if (crUrl == null || crUrl.isBlank()) {
            Logs.debug(Logs.Module.CPE_PUSH, "无反向连接地址，等设备下次 Inform 装载任务", "sn", sn, "任务", taskId);
            return;
        }
        // 密码允许空串：部分设备 CR 密码就是空，原先要求非空导致空密码设备拿 null 凭据认证失败
        connectionRequestService.wakeWithRetry(sn, crUrl,
                paramEndingWith(sn, "ManagementServer.ConnectionRequestUsername"),
                paramEndingWithAllowEmpty(sn, "ManagementServer.ConnectionRequestPassword"));
        Logs.info(Logs.Module.CPE_PUSH, "离线任务已派发反向唤醒", "sn", sn, "任务", taskId);
    }

    /** 参数树里按后缀取值（如 ManagementServer.ConnectionRequestUsername） */
    private String paramEndingWith(String sn, String suffix) {
        try {
            for (Map.Entry<String, com.PilotACS.cpe.model.CpeParam> e : store.loadParams(sn).entrySet()) {
                if (!e.getKey().endsWith(suffix) || e.getValue() == null) {
                    continue;
                }
                String v = e.getValue().value();
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /** 按后缀取值，允许空串（用于 CR 密码） */
    private String paramEndingWithAllowEmpty(String sn, String suffix) {
        try {
            for (Map.Entry<String, com.PilotACS.cpe.model.CpeParam> e : store.loadParams(sn).entrySet()) {
                if (!e.getKey().endsWith(suffix) || e.getValue() == null || e.getValue().value() == null) {
                    continue;
                }
                return e.getValue().value();
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
