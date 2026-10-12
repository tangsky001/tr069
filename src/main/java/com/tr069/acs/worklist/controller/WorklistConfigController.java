package com.tr069.acs.worklist.controller;

import com.tr069.acs.common.Result;
import com.tr069.acs.worklist.model.DeviceProfile;
import com.tr069.acs.worklist.model.ReloadReport;
import com.tr069.acs.worklist.model.WorklistTemplate;
import com.tr069.acs.worklist.registry.ProfileRegistry;
import com.tr069.acs.worklist.registry.WorklistRegistry;
import com.tr069.acs.worklist.service.DeviceProfileMatcher;
import com.tr069.acs.worklist.service.WorklistConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 光猫适配档案与工单模板的管理接口。
 *
 * <p>核心能力是 {@code POST /api/worklist/reload}：现场放入新的光猫 yaml 后，
 * 调用一次即可生效，无需重启服务。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/worklist")
@RequiredArgsConstructor
public class WorklistConfigController {

    private final WorklistConfigService configService;
    private final ProfileRegistry profileRegistry;
    private final WorklistRegistry worklistRegistry;
    private final DeviceProfileMatcher profileMatcher;

    /**
     * 手动触发一次全量重载。
     */
    @PostMapping("/reload")
    public Result<ReloadReport> reload() {
        ReloadReport report = configService.reloadAll();
        if (report.isSuccess()) {
            return Result.success(report);
        }
        return new Result<>(Result.ERROR_CODE, "重载完成，但有配置解析失败，详见 errors", report);
    }

    /**
     * 释放/补齐示例配置到配置根目录。
     */
    @PostMapping("/samples")
    public Result<Integer> unpackSamples() {
        int count = configService.unpackSamples();
        return Result.success(count);
    }

    /**
     * 配置总览：根目录、条目数、版本号。
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> data = new HashMap<>();
        data.put("rootPath", String.valueOf(configService.getRoot()));
        data.put("profileCount", profileRegistry.size());
        data.put("profileInvalidCount", profileRegistry.invalidItems().size());
        data.put("worklistCount", worklistRegistry.size());
        data.put("worklistInvalidCount", worklistRegistry.invalidItems().size());
        data.put("version", profileRegistry.getVersion().get());
        data.put("lastLoadTime", String.valueOf(profileRegistry.getLastLoadTime()));
        return Result.success(data);
    }

    /**
     * 光猫适配档案列表。
     */
    @GetMapping("/profiles")
    public Result<List<DeviceProfile>> profiles() {
        return Result.success(profileRegistry.orderedProfiles());
    }

    /**
     * 单个光猫适配档案详情。
     */
    @GetMapping("/profiles/{key}")
    public Result<DeviceProfile> profile(@PathVariable String key) {
        return profileRegistry.get(key)
                .map(item -> item.isValid()
                        ? Result.success(item.getValue())
                        : Result.<DeviceProfile>failure("该档案解析失败: " + item.getError()))
                .orElseGet(() -> Result.failure("未找到光猫适配档案: " + key));
    }

    /**
     * 按设备标识匹配应该使用的适配档案。
     */
    @GetMapping("/profiles/match")
    public Result<Map<String, Object>> match(
            @RequestParam(required = false) String manufacturer,
            @RequestParam(required = false) String oui,
            @RequestParam(required = false) String productClass,
            @RequestParam(required = false) String softwareVersion,
            @RequestParam(required = false) String serialNumber) {
        Map<String, Object> data = new HashMap<>();
        profileMatcher.match(manufacturer, oui, productClass, softwareVersion, serialNumber)
                .ifPresentOrElse(
                        profile -> {
                            data.put("matched", true);
                            data.put("profile", profile);
                        },
                        () -> {
                            data.put("matched", false);
                            data.put("profile", null);
                        });
        return Result.success(data);
    }

    /**
     * 工单模板列表，支持按运营商 / 版本 / 业务域 / 设备类型过滤。
     */
    @GetMapping("/templates")
    public Result<List<WorklistTemplate>> templates(
            @RequestParam(required = false) String isp,
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String deviceType) {
        return Result.success(worklistRegistry.find(isp, version, domain, deviceType));
    }
}
