package com.tr069.acs.openapi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事件投递记录。
 *
 * <p>幂等键是 {@code webhook_id + event_id}：同一条事件不会因为重试推给接收方两遍，
 * 接收方拿 {@code eventId} 去重，平台这边则能查到每一次尝试的结果。</p>
 */
@Data
@TableName("openapi_webhook_delivery")
public class WebhookDelivery {

    /** 等待推送。 */
    public static final String STATUS_PENDING = "PENDING";

    /** 推送成功（2xx）。 */
    public static final String STATUS_SUCCESS = "SUCCESS";

    /** 重试次数用尽仍未成功。 */
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long webhookId;

    private String eventId;

    private String eventType;

    private String payload;

    /** {@link #STATUS_PENDING} / {@link #STATUS_SUCCESS} / {@link #STATUS_FAILED} */
    private String status;

    private Integer attempts;

    private LocalDateTime nextRetryAt;

    private Integer responseCode;

    private String responseBody;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
