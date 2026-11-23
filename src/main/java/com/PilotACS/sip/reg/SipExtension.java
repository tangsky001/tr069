/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.reg;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** SIP 分机配置 */
@Data
@TableName("sys_sip_extension")
public class SipExtension {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分机号 */
    private String extension;

    /** SIP 注册密码 */
    private String password;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
