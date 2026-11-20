/**
 * @author: tangsky001
 * @date:   2026年11月19日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.redis.DeviceInfoCache;
import com.PilotACS.cpe.redis.RedisTaskQueue;
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

    @Override
    public void dispatch(String sn, long taskId) {
        // 任务可能已被删除/收口（队列里是旧消息）：不再是排队状态就直接丢弃
        Map<String, Object> task = store.getTask(taskId);
        if (task == null || !"queued".equals(String.valueOf(task.get("status")))) {
            Logs.debug(Logs.Module.CPE_PUSH, "派发消息对应任务已失效，跳过唤醒", "sn", sn, "任务", taskId);
            return;
        }
        wakeNow(sn, taskId);
    }

    /** 查档案→反向唤醒（带重试退避，异步）；Redis 派发队列故障时 TaskService 也走这条降级路径 */
    public void wakeNow(String sn, long taskId) {
        // 设备档案：优先 Redis 缓存（cpe:info:{sn}），未命中再查 MySQL 并回填
        Map<String, Object> device = null;
        try {
            device = infoCache.get(sn);
            if (device == null) {
                device = store.findDevice(sn);
                infoCache.put(sn, device);
            }
        } catch (Exception ignore) {
            // 设备还没上报过
        }
        String crUrl = device == null ? null : str(device.get("connection_request_url"));
        if (crUrl == null || crUrl.isBlank()) {
            Logs.debug(Logs.Module.CPE_PUSH, "无反向连接地址，等设备下次 Inform 装载任务", "sn", sn, "任务", taskId);
            return;
        }
        // 只用配置账密唤醒；设备上报的 CR 用户名（密码不可知）不再参与猜配。
        connectionRequestService.wakeWithRetry(sn, crUrl,
                paramEndingWith(sn, "ManagementServer.ConnectionRequestUsername"),
                paramEndingWith(sn, "ManagementServer.ConnectionRequestPassword"));
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
            // 设备还没采集参数树：退回配置里的默认账号
        }
        return null;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
