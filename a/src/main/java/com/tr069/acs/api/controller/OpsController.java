package com.tr069.acs.api.controller;

import com.tr069.acs.api.model.OpsRemoteView;
import com.tr069.acs.api.service.OpsEnvironmentService;
import com.tr069.acs.api.service.OpsRemoteService;
import com.tr069.acs.api.util.QrCodeUtil;
import com.tr069.acs.common.Result;
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

/** 运维管理 */
@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class OpsController {

    private final OpsEnvironmentService service;

    private final OpsRemoteService remoteService;

    /** 运维环境总览：系统版本、服务器、数据库、Redis、Git */
    @GetMapping("/environment")
    public Result<Map<String, List<Map<String, String>>>> environment() {
        return Result.success(service.environment());
    }

    /** 远程运维配置：Token、权限项、可见范围、三个二维码内容 */
    @GetMapping("/remote")
    public Result<OpsRemoteView> remote() {
        return Result.success(remoteService.view());
    }

    /** 保存远程运维设置 */
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
        return Result.success(remoteService.save(permissions, scope));
    }

    /** 重新生成 Token：旧 Token 立刻失效 */
    @PostMapping("/remote/token/regenerate")
    public Result<OpsRemoteView> regenerateToken() {
        return Result.success(remoteService.regenerateToken());
    }

    /**
     * 二维码图片：content 参数是要编码的文本。
     * 前端直接用 img 的 src 指向它，不用自己画。
     */
    @GetMapping(value = "/remote/qrcode", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] qrcode(@RequestParam String content,
                         @RequestParam(defaultValue = "240") int size) {
        int px = size < 80 ? 80 : (size > 512 ? 512 : size);
        return QrCodeUtil.toPngBytes(content, px);
    }
}
