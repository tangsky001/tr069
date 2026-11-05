package com.tr069.acs.olt.model;

import lombok.Data;

/** OLT 会话保活开关请求（管理页） */
@Data
public class OltKeepaliveRequest {

    /** 保活开关 */
    private Boolean enabled;

    /** 探活周期(毫秒)，不传保持当前值 */
    private Long intervalMs;
}
