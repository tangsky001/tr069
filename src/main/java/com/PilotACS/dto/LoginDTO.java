/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.582
 *
 */

package com.PilotACS.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

// 登录请求
@Data
@Schema(description = "登录请求")
public class LoginDTO {
    @Schema(description = "用户名", example = "admin")
    @NotBlank(message = "用户名不能为空")
    private String username;
    @Schema(description = "密码", example = "123456")
    @NotBlank(message = "密码不能为空")
    private String password;
}
