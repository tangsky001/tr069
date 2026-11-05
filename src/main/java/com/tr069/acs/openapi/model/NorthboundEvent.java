package com.tr069.acs.openapi.model;

import java.time.LocalDateTime;
import java.util.Map;

/** 一条北向事件 */
public record NorthboundEvent(String eventId, String eventType, LocalDateTime occurredAt,
                              Map<String, Object> data) {

    /** 设备上线 */
    public static final String DEVICE_ONLINE = "device.online";

    /** 设备离线 */
    public static final String DEVICE_OFFLINE = "device.offline";

    /** 下发任务结束（成功、失败、被拒绝都会发） */
    public static final String DELIVERY_FINISHED = "delivery.finished";

    /** 构造一条事件，事件号缺省用「类型 + 时间戳」拼 */
    public static NorthboundEvent of(String eventType, Map<String, Object> data) {
        LocalDateTime now = LocalDateTime.now();
        String id = eventType + ":" + System.nanoTime() + ":" + (int) (Math.random() * 1_000_000);
        return new NorthboundEvent(id, eventType, now, data);
    }
}
