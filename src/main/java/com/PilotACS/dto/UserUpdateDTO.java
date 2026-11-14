package com.PilotACS.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

// 用户编辑请求：role / status / password+currentPassword 任选
@Data
@Schema(description = "用户编辑请求：role、status、password 可单项或组合提交")
public class UserUpdateDTO {
    @Schema(description = "新角色编码，不传不修改", example = "OPS_ADMIN")
    private String role;
    @Schema(description = "账号状态：0禁用 1启用，不传不修改", example = "1")
    private Integer status;
    @Schema(description = "新密码，至少 6 位；修改密码时必传 currentPassword", example = "newpass123")
    private String password;
    @Schema(description = "操作者当前密码，修改密码时用于校验", example = "123456")
    private String currentPassword;
}
