package com.tr069.acs.openapi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 北向 Webhook 订阅：把设备事件与业务结果推给上层系统 */
@Data
@TableName("openapi_webhook")
public class WebhookSubscription {

    /** 关心全部事件类型 */
    public static final String ALL_EVENTS = "*";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String url;

    /** 生成 X-ACS-Signature 的密钥，可空表示不签名 */
    private String secret;

    /** 逗号分隔的事件类型，{@code *} 表示全部 */
    private String eventTypes;

    private Integer enabled;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
