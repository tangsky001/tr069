package com.tr069.acs.openapi.model;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 一条北向事件。
 *
 * <p>事件来源有两类：设备侧状态变化（上下线）与平台侧业务结果（下发任务结束）。
 * 统一成这个结构后，订阅方只按 {@code eventType} 分流。</p>
 *
 * @param eventId   事件唯一号，订阅方据此去重
 * @param eventType 事件类型：device.online / device.offline / delivery.finished
 * @param occurredAt 发生时间
 * @param data      事件内容
 */
public record NorthboundEvent(String eventId, String eventType, LocalDateTime occurredAt,
                              Map<String, Object> data) {

    /** 设备上线。 */
    public static final String DEVICE_ONLINE = "device.online";

    /** 设备离线。 */
    public static final String DEVICE_OFFLINE = "device.offline";

    /** 下发任务结束（成功、失败、被拒绝都会发）。 */
    public static final String DELIVERY_FINISHED = "delivery.finished";

    /**
     * 构造一条事件，事件号缺省用「类型 + 时间戳」拼。
     */
    public static NorthboundEvent of(String eventType, Map<String, Object> data) {
        LocalDateTime now = LocalDateTime.now();
        String id = eventType + ":" + System.nanoTime() + ":" + (int) (Math.random() * 1_000_000);
        return new NorthboundEvent(id, eventType, now, data);
    }
}
