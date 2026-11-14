/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.357
 *
 */

package com.PilotACS.controller;

import com.PilotACS.common.result.Result;
import com.PilotACS.common.security.RequiresPermission;
import com.PilotACS.common.util.QrCodeUtil;
import com.PilotACS.dto.OpsRemoteView;
import com.PilotACS.service.OpsRemoteService;
import com.PilotACS.service.OpsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

// 运维管理接口
@Tag(name = "运维管理")
@RestController
@RequestMapping("/ops")
@RequiredArgsConstructor
public class OpsController {

    private final OpsService opsService;
    private final OpsRemoteService remoteService;

    @Operation(summary = "环境信息")
    @RequiresPermission("ops:view")
    @GetMapping("/environment")
    public Result<Map<String, List<Map<String, Object>>>> environment() {
        return Result.ok(opsService.environment());
    }

    @Operation(summary = "远程运维配置")
    @RequiresPermission("ops:view")
    @GetMapping("/remote")
    public Result<OpsRemoteView> remote() {
        return Result.ok(remoteService.view());
    }

    @Operation(summary = "保存远程运维设置")
    @RequiresPermission("ops:update")
    @PostMapping("/remote")
    public Result<OpsRemoteView> saveRemote(@RequestBody Map<String, Object> body) {
        List<String> permissions = null;
        Object raw = body.get("permissions");
        if (raw instanceof List<?> list) {
            permissions = new java.util.ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    permissions.add(String.valueOf(item));
                }
            }
        }
        String scope = body.get("deviceScope") == null ? null : String.valueOf(body.get("deviceScope"));
        return Result.ok(remoteService.save(permissions, scope));
    }

    @Operation(summary = "重新生成 Token")
    @RequiresPermission("ops:update")
    @PostMapping("/remote/token/regenerate")
    public Result<OpsRemoteView> regenerateToken() {
        return Result.ok(remoteService.regenerateToken());
    }

    // 二维码图片：img 标签带不了 X-Token 头，token 走查询参数
    @Operation(summary = "二维码图片 PNG")
    @GetMapping(value = "/remote/qrcode", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] qrcode(@Parameter(description = "二维码编码内容，通常是视图里的 *QrContent 字段") @RequestParam String content,
                         @Parameter(description = "边长像素，默认 240，范围 80~512") @RequestParam(defaultValue = "240") int size) {
        int px = size < 80 ? 80 : Math.min(size, 512);
        return QrCodeUtil.toPngBytes(content, px);
    }
}
