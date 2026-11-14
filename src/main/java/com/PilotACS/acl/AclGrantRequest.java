/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.891
 *
 */

package com.PilotACS.acl;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** ACL 授权请求 */
@Data
public class AclGrantRequest {

    @Schema(description = "授权对象：sys_user.id")
    private Long principalId;

    @Schema(description = "范围类型：device / group")
    private String scopeType;

    @Schema(description = "范围引用：设备为 olt_device.id；分组为 ROOT / G:id")
    private String scopeId;

    @Schema(description = "授予动作列表：view/operate/update/delete")
    private List<String> perms;
}
