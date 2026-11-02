package com.tr069.acs.olt.model;

import lombok.Data;

/**
 * OLT 会话保活开关请求（管理页）。
 *
 * <p>enabled 必填：开 = 每台 OLT 池中最多 2 个会话轮换，定期 display client 探活；
 * 关 = 会话 1 分钟没人用就 quit 退出登录。intervalMs 可选：探活周期，默认 2 分钟。</p>
 */
@Data
public class OltKeepaliveRequest {

    /** 保活开关 */
    private Boolean enabled;

    /** 探活周期(毫秒)，不传保持当前值 */
    private Long intervalMs;
}
