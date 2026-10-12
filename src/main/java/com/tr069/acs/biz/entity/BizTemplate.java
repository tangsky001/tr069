package com.tr069.acs.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务模板：一组配置模板（工单模板）的有序集合，对应一次完整业务开通。
 *
 * <p>例如「宽带+语音+WiFi」= WAN_DHCP_41 + WAN_DHCP_45 + SIP + WIFI_2.4 + LAN-DHCP + Reboot，
 * 现场只需选一个业务模板整包下发，不必逐个配置模板去点。</p>
 */
@Data
@TableName("biz_template")
public class BizTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务模板名称。 */
    private String name;

    /** 业务分组，页面按分组归类展示。 */
    private String groupName;

    /** 备注。 */
    private String remark;

    /** 是否启用，停用的模板不允许下发。 */
    private Boolean enabled;

    /** 成员执行失败时是否继续后续成员。 */
    private Boolean continueOnError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
