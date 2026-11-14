package com.PilotACS.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

// 角色新增请求
@Data
@Schema(description = "角色新增请求")
public class RoleCreateDTO {
    @Schema(description = "角色编码，大写字母/数字/下划线", example = "AUDITOR")
    @NotBlank(message = "角色编码不能为空")
    private String code;
    @Schema(description = "角色名称", example = "审计员")
    @NotBlank(message = "角色名称不能为空")
    private String name;
}
