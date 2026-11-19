/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.490
 *
 */

package com.PilotACS.acl;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 设备/分组级 ACL：细到每一台设备的看/操作/编辑/删除授权。 */
@Data
@TableName("res_acl")
public class ResAcl {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 授权对象：sys_user.id */
    private Long principalId;

    /** 范围类型：device / group */
    private String scopeType;

    /** 范围引用：设备为 olt_device.id 数字串；分组为 ROOT / G:id */
    private String scopeId;

    /** 授予的动作，逗号分隔：view,operate,update,delete */
    private String perms;

    private String createdBy;

    private LocalDateTime createdAt;
}
