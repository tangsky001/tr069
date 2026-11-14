package com.PilotACS.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

// 用户新增请求
@Data
@Schema(description = "用户新增请求")
public class UserCreateDTO {
    @Schema(description = "登录名，3-32 位字母/数字/下划线", example = "opsuser")
    @NotBlank(message = "用户名不能为空")
    private String username;
    @Schema(description = "登录密码，至少 6 位", example = "123456")
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, message = "密码至少 6 位")
    private String password;
    @Schema(description = "昵称，留空默认用登录名", example = "张三")
    private String nickname;
    @Schema(description = "角色编码", example = "OPS_ADMIN")
    @NotBlank(message = "角色不能为空")
    private String role;
}
